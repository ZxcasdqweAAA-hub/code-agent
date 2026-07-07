package com.study.tui.tea;

import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.NonBlockingReader;

import java.io.IOException;
import java.io.PrintWriter;
import java.time.Instant;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class Program {
    private final Model model;
    private final BlockingQueue<Message> mailbox = new LinkedBlockingQueue<>();
    private Terminal terminal;
    private int availableHeight = 24;
    private volatile boolean running = true;
    private int linesRendered;

    public Program(Model model) {
        this.model = model;
    }

    public void run() {
        Attributes previousAttributes = null;
        try {
            terminal = TerminalBuilder.builder().system(true).build();
            previousAttributes = terminal.enterRawMode();
            terminal.handle(Terminal.Signal.INT, signal -> send(new KeyPressMessage("ctrl+c", new char[0])));
            terminal.handle(Terminal.Signal.WINCH,
                    signal -> send(new WindowSizeMessage(terminal.getWidth(), terminal.getHeight())));
            execute(model.init());
            render();
            NonBlockingReader reader = terminal.reader();
            while (running) {
                drainMailbox();
                int ch = reader.read(50L);
                if (ch >= 0) {
                    dispatch(readKey(reader, ch));
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to start terminal", e);
        } finally {
            if (terminal != null) {
                clearView();
                if (previousAttributes != null) {
                    terminal.setAttributes(previousAttributes);
                }
                PrintWriter writer = terminal.writer();
                String history = model.dumpHistory();
                if (!history.isBlank()) {
                    writer.println(history);
                }
                writer.flush();
            }
        }
    }

    public void send(Message msg) {
        mailbox.offer(msg);
    }

    public int getAvailableHeight() {
        return availableHeight;
    }

    private void drainMailbox() {
        Message msg;
        while ((msg = mailbox.poll()) != null) {
            dispatch(msg);
        }
    }

    private Message readKey(NonBlockingReader reader, int ch) throws IOException {
        if (ch == 3) {
            return new KeyPressMessage("ctrl+c", new char[0]);
        }
        if (ch == 10) {
            return new KeyPressMessage("ctrl+j", new char[]{'\n'});
        }
        if (ch == 13) {
            return new KeyPressMessage("enter", new char[]{'\n'});
        }
        if (ch == 127 || ch == 8) {
            return new KeyPressMessage("backspace", new char[0]);
        }
        if (ch == 27) {
            int next = reader.read(10L);
            if (next == '[') {
                return readEscapeSequence(reader);
            }
            if (next == 'O') {
                int code = reader.read(10L);
                return arrowKey(code);
            }
            if (next == 10 || next == 13) {
                return new KeyPressMessage("alt+enter", new char[]{'\n'});
            }
            if (next >= 0) {
                return new KeyPressMessage("alt+" + (char) next, new char[]{(char) next});
            }
            return new KeyPressMessage("escape", new char[0]);
        }
        return new KeyPressMessage("rune", Character.toChars(ch));
    }

    private Message readEscapeSequence(NonBlockingReader reader) throws IOException {
        int code = reader.read(10L);
        if (code == 'A' || code == 'B' || code == 'C' || code == 'D') {
            return arrowKey(code);
        }
        while (code >= 0) {
            if (code == 'A' || code == 'B' || code == 'C' || code == 'D') {
                return arrowKey(code);
            }
            if (Character.isLetter(code) || code == '~') {
                return new KeyPressMessage("escape", new char[0]);
            }
            code = reader.read(10L);
        }
        return new KeyPressMessage("escape", new char[0]);
    }

    private Message arrowKey(int code) {
        return switch (code) {
            case 'A' -> new KeyPressMessage("up", new char[0]);
            case 'B' -> new KeyPressMessage("down", new char[0]);
            case 'C' -> new KeyPressMessage("right", new char[0]);
            case 'D' -> new KeyPressMessage("left", new char[0]);
            default -> new KeyPressMessage("escape", new char[0]);
        };
    }

    private void dispatch(Message msg) {
        if (msg instanceof QuitMessage) {
            running = false;
            return;
        }
        UpdateResult<? extends Model> result = model.update(msg);
        execute(result.command());
        if (result.render()) {
            render();
        }
    }

    private void execute(Command command) {
        switch (command) {
            case Command.None ignored -> {
            }
            case Command.Simple simple -> send(simple.supplier().get());
            case Command.Tick tick -> Thread.ofVirtual().start(() -> {
                try {
                    Thread.sleep(tick.delay());
                    send(tick.fn().apply(Instant.now()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            case Command.CheckWindowSize ignored -> {
                if (terminal != null) {
                    availableHeight = terminal.getHeight();
                    send(new WindowSizeMessage(terminal.getWidth(), terminal.getHeight()));
                }
            }
            case Command.Batch batch -> batch.commands().forEach(this::execute);
            case Command.PrintLine printLine -> {
                if (terminal != null) {
                    clearView();
                    terminal.writer().println(printLine.text());
                    terminal.writer().flush();
                }
            }
        }
    }

    private void render() {
        if (terminal == null) {
            return;
        }
        clearView();
        PrintWriter writer = terminal.writer();
        String view = model.view();
        writer.print(view);
        if (!view.endsWith(System.lineSeparator())) {
            writer.println();
        }
        linesRendered = countLines(view);
        writer.flush();
    }

    private void clearView() {
        if (terminal == null || linesRendered <= 0) {
            return;
        }
        PrintWriter writer = terminal.writer();
        for (int i = 0; i < linesRendered; i++) {
            writer.print("\033[1A");
            writer.print("\r\033[2K");
        }
        writer.print("\r");
        writer.flush();
        linesRendered = 0;
    }

    private int countLines(String text) {
        if (text == null || text.isEmpty()) {
            return 1;
        }
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }
}
