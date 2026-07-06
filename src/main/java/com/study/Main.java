package com.study;

import com.study.config.AppConfig;
import com.study.config.ConfigException;
import com.study.config.ConfigLoader;
import com.study.prompt.PromptBuilder;
import com.study.tool.ToolRegistry;
import com.study.tui.CodeAgentModel;
import com.study.tui.tea.Program;

public class Main {
    private static final String CONFIG_PATH = "config/code-agent.yaml";

    public static void main(String[] args) {
        try {
            AppConfig config = ConfigLoader.load(CONFIG_PATH);
            CodeAgentModel model = new CodeAgentModel(config.getProviders(), PromptBuilder.buildSystemPrompt(), ToolRegistry.createDefault());
            Program program = new Program(model);
            program.run();
        } catch (ConfigException e) {
            System.err.println("配置错误: " + e.getMessage());
            System.exit(1);
        }
    }
}
