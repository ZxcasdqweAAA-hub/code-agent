package com.study.tui;

public final class SpinnerVerbs {
    private static final String[] WORDS = {"Imagining", "Thinking", "Composing", "Streaming"};

    private SpinnerVerbs() {
    }

    public static String at(long tick) {
        return WORDS[(int) (tick % WORDS.length)];
    }
}
