package com.study.memory;

import com.study.conversation.Message;
import com.study.llm.LlmClient;
import com.study.llm.Request;
import com.study.llm.StreamEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryManagerTest {
    @TempDir
    Path tempDir;

    @Test
    void detectsExplicitMemorySignal() {
        assertTrue(MemoryManager.hasMemorySignal("记住：包名是 com.study"));
        assertTrue(MemoryManager.hasMemorySignal("please remember this"));
        assertTrue(MemoryManager.hasMemorySignal("忘记之前的循环偏好"));
        assertTrue(MemoryManager.hasMemorySignal("纠正一下，项目使用 Java 21"));
        assertFalse(MemoryManager.hasMemorySignal("普通聊天"));
    }

    @Test
    void promptContextExposesIndexPathTypeTimeAndUsageRules() throws Exception {
        MemoryStore store = new MemoryStore(tempDir.resolve("memory"));
        store.apply(List.of(new UpdateAction(
                "create", "user_preference", "Java Preferences", "java_preferences",
                "The user prefers Maven.", null, "Prefer Maven for Java projects.")));
        MemoryManager manager = new MemoryManager(store, null);

        String context = manager.loadPromptContext();

        assertTrue(context.contains(".code-agent/memory/MEMORY.md"));
        assertTrue(context.contains(".code-agent/memory/<filename>"));
        assertTrue(context.contains("type: user_preference; updated:"));
        assertTrue(context.contains("user_preference` is relatively reliable"));
        assertTrue(context.contains("Current user instructions and verified evidence"));
    }

    @Test
    void updateUsesNoToolsAndAppliesReturnedActions() throws Exception {
        MemoryStore store = new MemoryStore(tempDir.resolve("memory"));
        FakeClient client = new FakeClient("""
                [{"action":"create","type":"project_knowledge","title":"Build Tool","slug":"build_tool","summary":"Use Maven.","content":"This project uses Maven."}]
                """);
        MemoryManager manager = new MemoryManager(store, client);

        manager.update(List.of(Message.user("记住：这个项目使用 Maven"), Message.assistant("好的")), client);

        assertEquals(1, client.calls);
        assertTrue(client.lastRequest.tools().isEmpty());
        assertTrue(Files.readString(tempDir.resolve("memory").resolve("project_knowledge_build_tool.md")).contains("Maven"));
        String index = Files.readString(tempDir.resolve("memory").resolve("MEMORY.md"));
        assertTrue(index.contains("Use Maven."));
        assertTrue(index.contains("type: project_knowledge; updated:"));
    }

    @Test
    void updateSendsExistingNotesToModel() throws Exception {
        MemoryStore store = new MemoryStore(tempDir.resolve("memory"));
        store.apply(List.of(new UpdateAction(
                "create", "project_knowledge", "Build Tool", "build_tool",
                "Existing full note body.", null, "Existing summary.")));
        FakeClient client = new FakeClient("[]");
        MemoryManager manager = new MemoryManager(store, client);

        manager.update(List.of(Message.user("记住：这个项目仍然使用 Maven")), client);

        assertTrue(client.lastRequest.messages().get(0).content().contains("现有记忆正文："));
        assertTrue(client.lastRequest.messages().get(0).content().contains("[file: project_knowledge_build_tool.md]"));
        assertTrue(client.lastRequest.messages().get(0).content().contains("Existing full note body."));
    }

    @Test
    void invokesCallbackAfterSuccessfulChange() throws Exception {
        MemoryStore store = new MemoryStore(tempDir.resolve("memory"));
        FakeClient client = new FakeClient("""
                [{"action":"create","type":"user_preference","title":"Language","slug":"language","summary":"Reply in Chinese.","content":"The user prefers Chinese replies."}]
                """);
        MemoryManager manager = new MemoryManager(store, client);
        int[] updates = new int[1];
        manager.onUpdated(() -> updates[0]++);

        manager.update(List.of(Message.user("记住：以后用中文回复"), Message.assistant("好的")), client);

        assertEquals(1, updates[0]);
        assertTrue(Files.readString(tempDir.resolve("memory").resolve("MEMORY.md")).contains("[Language](user_preference_language.md)"));
    }

    @Test
    void sendsOnlyMessagesNotPreviouslySummarized() throws Exception {
        MemoryStore store = new MemoryStore(tempDir.resolve("memory"));
        FakeClient client = new FakeClient("[]");
        MemoryManager manager = new MemoryManager(store, client);

        manager.update(List.of(
                Message.user("记住：第一条"),
                Message.assistant("好的")), client, 2);
        manager.updateAsyncIfNeeded(List.of(
                Message.user("记住：第一条"),
                Message.assistant("好的"),
                Message.user("记住：第二条"),
                Message.assistant("好的")), false);

        Thread.sleep(300);

        assertEquals(2, client.calls);
        String secondPrompt = client.lastRequest.messages().get(0).content();
        assertFalse(secondPrompt.contains("第一条"));
        assertTrue(secondPrompt.contains("第二条"));
    }

    @Test
    void periodicUpdateIncludesAccumulatedUnsummarizedMessages() throws Exception {
        MemoryStore store = new MemoryStore(tempDir.resolve("memory"));
        FakeClient client = new FakeClient("[]");
        MemoryManager manager = new MemoryManager(store, client);

        manager.updateAsyncIfNeeded(List.of(
                Message.user("普通第一轮"),
                Message.assistant("好的"),
                Message.user("普通第二轮"),
                Message.assistant("好的")), true);

        Thread.sleep(300);

        assertEquals(1, client.calls);
        String prompt = client.lastRequest.messages().get(0).content();
        assertTrue(prompt.contains("普通第一轮"));
        assertTrue(prompt.contains("普通第二轮"));
    }

    @Test
    void markSummarizedThroughSkipsRestoredHistory() throws Exception {
        MemoryStore store = new MemoryStore(tempDir.resolve("memory"));
        FakeClient client = new FakeClient("[]");
        MemoryManager manager = new MemoryManager(store, client);

        manager.markSummarizedThrough(2);
        manager.updateAsyncIfNeeded(List.of(
                Message.user("恢复出来的历史"),
                Message.assistant("旧回复")), true);

        Thread.sleep(300);

        assertEquals(0, client.calls);
    }

    @Test
    void resetSummarizedThroughSupportsSwitchingFromLongToShortSession() throws Exception {
        MemoryStore store = new MemoryStore(tempDir.resolve("memory"));
        FakeClient client = new FakeClient("[]");
        MemoryManager manager = new MemoryManager(store, client);
        manager.markSummarizedThrough(100);
        manager.resetSummarizedThrough(2);

        manager.updateAsyncIfNeeded(List.of(
                Message.user("短会话历史"),
                Message.assistant("旧回复"),
                Message.user("记住：恢复后的新增消息"),
                Message.assistant("新回复")), false);

        Thread.sleep(300);

        assertEquals(1, client.calls);
        String prompt = client.lastRequest.messages().getFirst().content();
        assertFalse(prompt.contains("短会话历史"));
        assertTrue(prompt.contains("恢复后的新增消息"));
    }

    @Test
    void listFilesReturnsMarkdownFilesSorted() throws Exception {
        Path memoryDir = tempDir.resolve("memory");
        Files.createDirectories(memoryDir);
        Files.writeString(memoryDir.resolve("z.md"), "");
        Files.writeString(memoryDir.resolve("a.md"), "");
        Files.writeString(memoryDir.resolve("ignore.txt"), "");
        Files.writeString(memoryDir.resolve("MEMORY.md"), "");
        MemoryManager manager = new MemoryManager(new MemoryStore(memoryDir), null);

        List<String> files = manager.listFiles();

        assertEquals(List.of("MEMORY.md", "a.md", "z.md"), files);
    }

    private static final class FakeClient implements LlmClient {
        private final String reply;
        private int calls;
        private Request lastRequest;

        private FakeClient(String reply) {
            this.reply = reply;
        }

        @Override
        public com.study.llm.LlmStream stream(Request request) {
            calls++;
            lastRequest = request;
            BlockingQueue<StreamEvent> queue = new LinkedBlockingQueue<>();
            queue.add(new StreamEvent.TextDelta(reply));
            queue.add(new StreamEvent.StreamEnd("stop", 0, 0));
            return new com.study.llm.TestLlmStream(queue);
        }

        @Override
        public String name() {
            return "fake";
        }

        @Override
        public String model() {
            return "fake-model";
        }
    }
}
