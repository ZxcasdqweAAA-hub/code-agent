package com.study.tui;

import com.study.agent.AgentEvent;
import com.study.tui.tea.Message;

public record AgentEventMessage(AgentEvent event) implements Message {
}
