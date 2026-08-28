package com.study.tui.tea;

import com.study.tui.CliEofException;
import com.study.tui.CliInterruptedException;
import com.study.tui.CliIo;
import com.study.tui.CodeAgentModel;
import com.study.tui.JLineCliIo;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

public final class Program {
    private final CodeAgentModel model;
    private final CliIo suppliedIo;

    public Program(CodeAgentModel model) {
        this(model, null);
    }

    public Program(CodeAgentModel model, CliIo suppliedIo) {
        this.model = model;
        this.suppliedIo = suppliedIo;
    }

    public void run() {
        try {
            if (suppliedIo != null) {
                runLoop(suppliedIo);
                return;
            }
            Terminal terminal = TerminalBuilder.builder().system(true).build();
            try (JLineCliIo io = new JLineCliIo(terminal, model::completionNames)) {
                runLoop(io);
            }
        } catch (Exception e) {
            System.err.println("终端启动失败: " + e.getMessage());
        } finally {
            model.close();
        }
    }

    private void runLoop(CliIo io) {
        io.println(model.banner().stripTrailing());
        while (!model.quitRequested()) {
            String input;
            try {
                input = io.readLine(JLineCliIo.PROMPT);
            } catch (CliInterruptedException | CliEofException e) {
                break;
            }
            if (input == null || input.isBlank()) {
                continue;
            }
            model.submitLine(input, io);
        }
    }
}
