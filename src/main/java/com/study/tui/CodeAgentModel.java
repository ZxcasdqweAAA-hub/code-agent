package com.study.tui;

import com.study.agent.Agent;
import com.study.agent.AgentEvent;
import com.study.agent.CancelToken;
import com.study.agent.Mode;
import com.study.agent.Phase;
import com.study.config.ProviderConfig;
import com.study.conversation.ConversationManager;
import com.study.llm.LlmClient;
import com.study.prompt.PromptBuilder;
import com.study.tool.ToolRegistry;
import com.study.tui.tea.Command;
import com.study.tui.tea.KeyPressMessage;
import com.study.tui.tea.Message;
import com.study.tui.tea.Model;
import com.study.tui.tea.QuitMessage;
import com.study.tui.tea.UpdateResult;
import com.study.tui.tea.WindowSizeMessage;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;

public class CodeAgentModel implements Model {
    private final List<ProviderConfig> providers;
    private final String systemPrompt;
    private final ToolRegistry toolRegistry;
    private final ConversationManager conversation = new ConversationManager();
    private final List<ChatMessage> chatMessages = new ArrayList<>();
    private final MarkdownRenderer markdownRenderer = new MarkdownRenderer();

    private AppState state;
    private int selectedProvider;
    private LlmClient client;
    private String input = "";
    private boolean streaming;
    private StringBuilder streamBuf = new StringBuilder();
    private BlockingQueue<AgentEvent> agentQueue;
    private Instant streamStart;
    private int width = 100;
    private int height = 24;
    private int committedUpTo;
    private long spinnerTick;
    private Instant lastStreamRender = Instant.EPOCH;
    private int pendingStreamChars;
    private Mode mode = Mode.NORMAL;
    private int iter;
    private long usageIn;
    private long usageOut;
    private final List<ToolDisplay> curTools = new ArrayList<>();
    private CancelToken turnCancel;

    public CodeAgentModel(List<ProviderConfig> providers, String systemPrompt) {
        this(providers, systemPrompt, ToolRegistry.createDefault());
    }

    public CodeAgentModel(List<ProviderConfig> providers, String systemPrompt, ToolRegistry toolRegistry) {
        this.providers = List.copyOf(providers);
        this.systemPrompt = systemPrompt;
        this.toolRegistry = toolRegistry;
        this.state = providers.size() == 1 ? AppState.CHAT : AppState.PROVIDER_SELECT;
        if (providers.size() == 1) {
            initializeProvider(0);
        }
    }

    @Override
    public Command init() {
        return Command.checkWindowSize();
    }

    @Override
    public UpdateResult<? extends Model> update(Message msg) {
        if (msg instanceof WindowSizeMessage size) {
            width = size.width();
            height = size.height();
            return stay(Command.none());
        }
        if (msg instanceof AgentEventMessage agentEvent) {
            return handleAgentEvent(agentEvent.event());
        }
        if (msg instanceof KeyPressMessage key) {
            if (state == AppState.PROVIDER_SELECT) {
                return handleProviderSelect(key);
            }
            if (state == AppState.CHAT) {
                if (streaming) {
                    if ("ctrl+c".equals(key.key()) || "esc".equals(key.key())) {
                        if (turnCancel != null) {
                            turnCancel.cancel();
                        }
                        return stay(Command.tick(Duration.ofMillis(50), ignored -> pollAgent()));
                    }
                    return stay(Command.none());
                }
                return handleChatKey(key);
            }
        }
        return stay(Command.none());
    }

    @Override
    public String view() {
        StringBuilder out = new StringBuilder();
        out.append(renderBanner()).append(System.lineSeparator());
        out.append(Styles.GREEN).append("Ready. Agent loop mode. Use /plan and /do when needed.").append(Styles.RESET).append(System.lineSeparator());
        if (state == AppState.PROVIDER_SELECT) {
            out.append(renderProviderSelect());
        } else {
            out.append(renderMessages()).append(System.lineSeparator());
            if (streaming) {
                long seconds = Duration.between(streamStart, Instant.now()).toSeconds();
                out.append(Styles.DIM)
                        .append(renderStreamingStatus())
                        .append(" (")
                        .append(seconds)
                        .append("s)")
                        .append(Styles.RESET)
                        .append(System.lineSeparator());
            }
            out.append(renderInputBox()).append(System.lineSeparator());
            out.append(renderStatus());
        }
        return out.toString();
    }

    @Override
    public String dumpHistory() {
        return renderMessages(0);
    }

    private UpdateResult<CodeAgentModel> handleProviderSelect(KeyPressMessage key) {
        return switch (key.key()) {
            case "ctrl+c" -> stay(Command.of(QuitMessage::new));
            case "up" -> {
                selectedProvider = Math.max(0, selectedProvider - 1);
                yield stay(Command.none());
            }
            case "down" -> {
                selectedProvider = Math.min(providers.size() - 1, selectedProvider + 1);
                yield stay(Command.none());
            }
            case "enter" -> {
                initializeProvider(selectedProvider);
                state = AppState.CHAT;
                yield stay(Command.none());
            }
            default -> {
                String text = new String(key.runes()).trim();
                if (text.length() == 1 && Character.isDigit(text.charAt(0))) {
                    int index = Character.digit(text.charAt(0), 10) - 1;
                    if (index >= 0 && index < providers.size()) {
                        selectedProvider = index;
                    }
                }
                yield stay(Command.none());
            }
        };
    }

    private UpdateResult<CodeAgentModel> handleChatKey(KeyPressMessage key) {
        switch (key.key()) {
            case "ctrl+c" -> {
                return stay(Command.of(QuitMessage::new));
            }
            case "enter" -> {
                String text = input;
                input = "";
                if ("/exit".equalsIgnoreCase(text.trim())) {
                    return stay(Command.of(QuitMessage::new));
                }
                return submit(text);
            }
            case "ctrl+j", "alt+enter" -> {
                input += System.lineSeparator();
                return stay(Command.none());
            }
            case "backspace" -> {
                if (!input.isEmpty()) {
                    input = input.substring(0, input.length() - 1);
                }
                return stay(Command.none());
            }
            case "rune" -> {
                input += new String(key.runes());
                return stay(Command.none());
            }
            default -> {
                return stay(Command.none());
            }
        }
    }

    private UpdateResult<CodeAgentModel> submit(String text) {
        if (text == null || text.isBlank()) {
            return stay(Command.none());
        }
        String trimmed = text.trim();
        if ("/plan".equalsIgnoreCase(trimmed)) {
            input = "";
            mode = Mode.PLAN;
            chatMessages.add(new ChatMessage("assistant", "已进入计划模式（只读工具）。", false));
            return stay(Command.println(renderMessagesRange(committedUpTo, chatMessages.size())));
        }
        boolean executePlan = "/do".equalsIgnoreCase(trimmed);
        input = "";
        String userText = executePlan ? PromptBuilder.EXECUTE_DIRECTIVE : text;
        if (executePlan) {
            mode = Mode.NORMAL;
        } else {
            chatMessages.add(new ChatMessage("user", text, false));
        }
        conversation.addUserMessage(userText);
        streamBuf = new StringBuilder();
        streaming = true;
        streamStart = Instant.now();
        lastStreamRender = Instant.EPOCH;
        pendingStreamChars = 0;
        iter = 0;
        curTools.clear();
        turnCancel = new CancelToken();
        agentQueue = new Agent(client, toolRegistry).run(conversation, mode, turnCancel);
        return stay(Command.tick(Duration.ofMillis(50), ignored -> pollAgent()));
    }

    private Message pollAgent() {
        AgentEvent event = agentQueue == null ? null : agentQueue.poll();
        if (event == null) {
            return new AgentEventMessage(new AgentEvent.Text(""));
        }
        return new AgentEventMessage(event);
    }

    private UpdateResult<CodeAgentModel> handleAgentEvent(AgentEvent event) {
        if (!streaming) {
            return stay(Command.none());
        }
        if (event instanceof AgentEvent.Text text) {
            if (text.delta().isEmpty()) {
                return quiet(Command.tick(Duration.ofMillis(120), ignored -> pollAgent()));
            }
            streamBuf.append(text.delta());
            pendingStreamChars += text.delta().length();
            if (shouldRenderStream()) {
                lastStreamRender = Instant.now();
                pendingStreamChars = 0;
                return stay(Command.tick(Duration.ofMillis(50), ignored -> pollAgent()));
            }
            return quiet(Command.tick(Duration.ofMillis(35), ignored -> pollAgent()));
        }
        if (event instanceof AgentEvent.Tool tool) {
            if (tool.event().phase() == Phase.START) {
                flushStreamBufferAsAssistant();
                curTools.add(new ToolDisplay(tool.event().name(), tool.event().argumentsPreview()));
                chatMessages.add(new ChatMessage("tool", "● " + tool.event().name() + "(" + tool.event().argumentsPreview() + ")", false));
            } else {
                if (!curTools.isEmpty()) {
                    curTools.remove(0);
                }
                String prefix = tool.event().error() ? "工具失败" : "工具结果";
                chatMessages.add(new ChatMessage("tool", prefix + System.lineSeparator() + tool.event().result(), tool.event().error()));
            }
            return stay(Command.batch(
                    Command.println(renderMessagesRange(committedUpTo, chatMessages.size())),
                    Command.tick(Duration.ofMillis(50), ignored -> pollAgent())));
        }
        if (event instanceof AgentEvent.Failed error) {
            finishTurn();
            chatMessages.add(new ChatMessage("error", error.message(), true));
            return stay(Command.println(renderMessagesRange(committedUpTo, chatMessages.size())));
        }
        if (event instanceof AgentEvent.Iter currentIter) {
            iter = currentIter.value();
            return quiet(Command.tick(Duration.ofMillis(50), ignored -> pollAgent()));
        }
        if (event instanceof AgentEvent.UsageReport usage) {
            usageIn += usage.inputTokens();
            usageOut += usage.outputTokens();
            return quiet(Command.tick(Duration.ofMillis(50), ignored -> pollAgent()));
        }
        if (event instanceof AgentEvent.Notice notice) {
            chatMessages.add(new ChatMessage("assistant", notice.message(), false));
            return stay(Command.batch(
                    Command.println(renderMessagesRange(committedUpTo, chatMessages.size())),
                    Command.tick(Duration.ofMillis(50), ignored -> pollAgent())));
        }
        if (event instanceof AgentEvent.Done) {
            flushStreamBufferAsAssistant();
            finishTurn();
            return stay(Command.println(renderMessagesRange(committedUpTo, chatMessages.size())));
        }
        return stay(Command.none());
    }

    private void initializeProvider(int index) {
        selectedProvider = index;
        client = LlmClient.create(providers.get(index), systemPrompt);
    }

    private String renderBanner() {
        return """
                  /\\_/\\\\
                 ( o.o )
                  > ^ <
                Code Agent v0.1.0
                cwd: %s
                """.formatted(Path.of("").toAbsolutePath().normalize());
    }

    private String renderProviderSelect() {
        StringBuilder out = new StringBuilder("Select provider:").append(System.lineSeparator());
        for (int i = 0; i < providers.size(); i++) {
            ProviderConfig provider = providers.get(i);
            out.append(i == selectedProvider ? "❯ " : "  ")
                    .append(i + 1)
                    .append(". ")
                    .append(provider.getName())
                    .append(" (")
                    .append(provider.getModel())
                    .append(")")
                    .append(System.lineSeparator());
        }
        out.append(Styles.DIM).append("Use ↑/↓ and Enter, or type a number.").append(Styles.RESET);
        return out.toString();
    }

    private String renderMessages() {
        return renderMessages(committedUpTo);
    }

    private String renderMessages(int from) {
        StringBuilder out = new StringBuilder();
        for (int i = from; i < chatMessages.size(); i++) {
            out.append(renderMessage(chatMessages.get(i))).append(System.lineSeparator()).append(System.lineSeparator());
        }
        if (streaming && !streamBuf.isEmpty()) {
            out.append(Styles.CYAN).append("assistant").append(Styles.RESET).append(System.lineSeparator());
            out.append(streamBuf).append(System.lineSeparator());
        }
        return out.toString().stripTrailing();
    }

    private String renderMessage(ChatMessage message) {
        String role = message.error() ? Styles.RED + "error" + Styles.RESET
                : "assistant".equals(message.role()) ? Styles.CYAN + "assistant" + Styles.RESET
                : "tool".equals(message.role()) ? Styles.GREEN + "tool" + Styles.RESET
                : Styles.BOLD + "user" + Styles.RESET;
        String body = message.error() || "tool".equals(message.role()) ? message.content() : markdownRenderer.render(message.content(), width);
        return role + System.lineSeparator() + body;
    }

    private void flushStreamBufferAsAssistant() {
        String text = streamBuf.toString();
        if (!text.isBlank()) {
            chatMessages.add(new ChatMessage("assistant", text, false));
            streamBuf = new StringBuilder();
        }
    }

    private String renderMessagesRange(int from, int to) {
        StringBuilder out = new StringBuilder();
        for (int i = from; i < to; i++) {
            out.append(renderMessage(chatMessages.get(i))).append(System.lineSeparator()).append(System.lineSeparator());
        }
        committedUpTo = to;
        return out.toString().stripTrailing();
    }

    private String renderInputBox() {
        int innerWidth = Math.max(20, Math.min(width - 2, 90));
        String content = input.isBlank() ? Styles.DIM + "Send a message..." + Styles.RESET : input;
        StringBuilder box = new StringBuilder();
        box.append("┌").append("─".repeat(innerWidth)).append("┐").append(System.lineSeparator());
        String[] lines = content.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            String prefix = i == 0 ? " ❯ " : "   ";
            box.append("│").append(prefix).append(lines[i]).append(System.lineSeparator());
        }
        box.append("└").append("─".repeat(innerWidth)).append("┘");
        return box.toString();
    }

    private String renderStatus() {
        if (client == null) {
            return "";
        }
        String left = client.name() + (mode == Mode.PLAN ? " PLAN" : "");
        String usage = " ↑" + compact(usageIn) + " ↓" + compact(usageOut) + " tok";
        String right = client.model() + usage;
        return Styles.DIM + left + "  " + " ".repeat(Math.max(1, width - left.length() - right.length() - 4))
                + right + Styles.RESET;
    }

    private UpdateResult<CodeAgentModel> stay(Command command) {
        return new UpdateResult<>(this, command);
    }

    private UpdateResult<CodeAgentModel> quiet(Command command) {
        return new UpdateResult<>(this, command, false);
    }

    private boolean shouldRenderStream() {
        if (pendingStreamChars >= 24) {
            return true;
        }
        return Duration.between(lastStreamRender, Instant.now()).toMillis() >= 120;
    }

    private String renderStreamingStatus() {
        if (!curTools.isEmpty()) {
            ToolDisplay tool = curTools.get(0);
            return "● " + tool.name() + "(" + tool.args() + ") Running…";
        }
        String round = iter > 0 ? " · 第 " + iter + " 轮" : "";
        return SpinnerVerbs.at(spinnerTick++) + "... " + round;
    }

    private void finishTurn() {
        streaming = false;
        curTools.clear();
        iter = 0;
        turnCancel = null;
    }

    private String compact(long value) {
        if (value >= 1_000_000) {
            return "%.1fm".formatted(value / 1_000_000.0);
        }
        if (value >= 1_000) {
            return "%.1fk".formatted(value / 1_000.0);
        }
        return Long.toString(value);
    }

    private record ToolDisplay(String name, String args) {
    }
}
