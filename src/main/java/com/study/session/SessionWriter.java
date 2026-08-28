package com.study.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.study.conversation.Message;
import com.study.conversation.ConversationTruncation;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

public final class SessionWriter implements Closeable {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path file;
    private final FileChannel channel;
    private final FileChannel lockChannel;
    private final FileLock fileLock;
    private final BufferedWriter out;
    private final ReentrantLock lock = new ReentrantLock();
    private final String model;
    private boolean firstMessage;
    private boolean failed;

    private SessionWriter(Path file, FileChannel channel, FileChannel lockChannel, FileLock fileLock,
                          String model, boolean firstMessage) {
        this.file = file;
        this.channel = channel;
        this.lockChannel = lockChannel;
        this.fileLock = fileLock;
        this.out = new BufferedWriter(java.nio.channels.Channels.newWriter(channel, StandardCharsets.UTF_8));
        this.model = model == null ? "" : model;
        this.firstMessage = firstMessage;
    }

    public static SessionWriter create(Path sessionDir, String model) throws IOException {
        Files.createDirectories(sessionDir);
        return openInternal(sessionDir, model, true, false);
    }

    public static SessionWriter open(Path sessionDir, String model) throws IOException {
        if (!Files.isDirectory(sessionDir)) {
            throw new IOException("session 目录不存在: " + sessionDir);
        }
        return openInternal(sessionDir, model, !Files.exists(sessionDir.resolve("conversation.jsonl")), false);
    }

    public static SessionWriter openForResume(Path sessionDir, String model) throws IOException {
        if (!Files.isDirectory(sessionDir)) {
            throw new IOException("session 目录不存在: " + sessionDir.getFileName());
        }
        return openInternal(sessionDir, model, !Files.exists(sessionDir.resolve("conversation.jsonl")), true);
    }

    private static SessionWriter openInternal(Path sessionDir, String model, boolean firstMessage,
                                              boolean nonBlocking) throws IOException {
        Path file = sessionDir.resolve("conversation.jsonl");
        FileChannel channel = null;
        FileChannel lockChannel = null;
        FileLock fileLock = null;
        boolean success = false;
        try {
            channel = FileChannel.open(file,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
            lockChannel = FileChannel.open(sessionDir.resolve("conversation.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            try {
                fileLock = nonBlocking ? lockChannel.tryLock() : lockChannel.lock();
            } catch (OverlappingFileLockException e) {
                if (!nonBlocking) {
                    throw e;
                }
                throw new IOException("session 正被当前进程中的其他实例使用", e);
            }
            if (fileLock == null) {
                throw new IOException("session 正被其他实例使用");
            }
            SessionWriter writer = new SessionWriter(file, channel, lockChannel, fileLock, model, firstMessage);
            success = true;
            return writer;
        } finally {
            if (!success) {
                if (fileLock != null && fileLock.isValid()) {
                    fileLock.release();
                }
                if (lockChannel != null) {
                    lockChannel.close();
                }
                if (channel != null) {
                    channel.close();
                }
            }
        }
    }

    public void append(Message msg) throws IOException {
        if (msg == null) {
            return;
        }
        lock.lock();
        try {
            ensureWritable();
            Entry entry = toEntry(msg, firstMessage ? model : null);
            writeEntry(entry, false);
            out.flush();
            firstMessage = false;
        } catch (IOException e) {
            failed = true;
            throw e;
        } finally {
            lock.unlock();
        }
    }

    public void replace(List<Message> messages) throws IOException {
        lock.lock();
        try {
            ensureWritable();
            String snapshotId = UUID.randomUUID().toString();
            if (messages != null) {
                for (Message msg : messages) {
                    writeEntry(toSnapshotEntry(msg, snapshotId), false);
                }
            }
            writeEntry(new Entry("snapshot_commit", null, null, null, null,
                    Instant.now().getEpochSecond(), null, snapshotId), false);
            out.flush();
        } catch (IOException e) {
            failed = true;
            throw e;
        } finally {
            lock.unlock();
        }
    }

    public void truncate(ConversationTruncation truncation) throws IOException {
        if (truncation == null) {
            return;
        }
        lock.lock();
        try {
            ensureWritable();
            writeEntry(new Entry("truncate", null, null, null, null,
                    Instant.now().getEpochSecond(), null, null,
                    truncation.operationId(), truncation.fromSize(), truncation.toSize()), false);
            out.flush();
        } catch (IOException e) {
            failed = true;
            throw e;
        } finally {
            lock.unlock();
        }
    }

    public Path file() {
        return file;
    }

    private Entry toEntry(Message msg, String model) {
        return new Entry(
                null,
                msg.role(),
                msg.content().isBlank() ? null : msg.content(),
                msg.toolCalls().isEmpty() ? null : msg.toolCalls(),
                msg.toolResults().isEmpty() ? null : msg.toolResults(),
                Instant.now().getEpochSecond(),
                model == null || model.isBlank() ? null : model);
    }

    private Entry toSnapshotEntry(Message msg, String snapshotId) {
        Entry entry = toEntry(msg, null);
        return new Entry("snapshot", entry.role(), entry.content(), entry.toolCalls(), entry.toolResults(),
                entry.ts(), null, snapshotId);
    }

    private void writeEntry(Entry entry, boolean flush) throws IOException {
        out.write(JSON.writeValueAsString(entry));
        out.newLine();
        if (flush) {
            out.flush();
        }
    }

    private void ensureWritable() throws IOException {
        if (failed) {
            throw new IOException("session writer is in failed state");
        }
    }

    @Override
    public void close() throws IOException {
        lock.lock();
        try {
            out.close();
            fileLock.release();
            lockChannel.close();
            channel.close();
        } finally {
            lock.unlock();
        }
    }
}
