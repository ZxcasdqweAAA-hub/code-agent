package com.study.tui;

import com.study.config.ProviderConfig;
import com.study.tool.ToolRegistry;
import com.study.tui.tea.Program;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProgramTest {
    @TempDir
    Path tempDir;

    @Test
    void loopsUntilExitWithoutDumpingHistory() {
        CodeAgentModel model = model();
        FakeCliIo io = new FakeCliIo().input("").input("/help").input("/exit");

        new Program(model, io).run();

        assertEquals(3, occurrences(io.transcript(), JLineCliIo.PROMPT));
        assertEquals(1, occurrences(io.transcript(), "Code Agent v0.1.0"));
        assertTrue(io.transcript().contains("/help"));
        assertEquals(1, occurrences(io.transcript(), "Clear chat and start a new session"));
    }

    @Test
    void idleInterruptExitsWithoutAnotherPromptOrHistoryReplay() {
        CodeAgentModel model = model();
        FakeCliIo io = new FakeCliIo().interrupt();

        new Program(model, io).run();

        assertEquals(1, occurrences(io.transcript(), JLineCliIo.PROMPT));
        assertEquals(1, occurrences(io.transcript(), "Code Agent v0.1.0"));
    }

    @Test
    void eofExitsCleanly() {
        CodeAgentModel model = model();
        FakeCliIo io = new FakeCliIo().eof();

        new Program(model, io).run();

        assertEquals(1, occurrences(io.transcript(), JLineCliIo.PROMPT));
    }

    @Test
    void resumeListDoesNotConsumeAnInteractiveSelection() {
        CodeAgentModel model = model();
        FakeCliIo io = new FakeCliIo().input("/resume").input("/exit");

        new Program(model, io).run();

        assertEquals(2, occurrences(io.transcript(), JLineCliIo.PROMPT));
        assertTrue(io.transcript().contains("没有可恢复的 session"));
    }

    @Test
    void resumeFailureClearAndExitRemainSingleInputCommands() {
        CodeAgentModel model = model();
        FakeCliIo io = new FakeCliIo()
                .input("/resume")
                .input("/resume 1")
                .input("/clear")
                .input("/exit");

        new Program(model, io).run();

        assertEquals(4, occurrences(io.transcript(), JLineCliIo.PROMPT));
        assertTrue(io.transcript().contains("没有可恢复的 session"));
        assertTrue(io.transcript().contains("无效的完整 session ID"));
        assertTrue(io.transcript().contains("新 session 将在首次请求时创建"));
    }

    private CodeAgentModel model() {
        ProviderConfig provider = new ProviderConfig();
        provider.setName("test");
        provider.setProtocol("openai");
        provider.setBaseUrl("http://127.0.0.1:1");
        provider.setApiKey("test-key");
        provider.setModel("test-model");
        provider.setContextWindow(100_000);
        return new CodeAgentModel(List.of(provider), "system", new ToolRegistry(), null, null, tempDir);
    }

    private int occurrences(String text, String needle) {
        return text.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }
}
