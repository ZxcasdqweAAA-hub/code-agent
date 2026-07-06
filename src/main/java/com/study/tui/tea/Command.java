package com.study.tui.tea;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

public sealed interface Command permits Command.None, Command.Simple, Command.Tick, Command.CheckWindowSize, Command.Batch, Command.PrintLine {
    static Command none() {
        return new None();
    }

    static Command of(Supplier<Message> supplier) {
        return new Simple(supplier);
    }

    static Command tick(Duration delay, Function<Instant, Message> fn) {
        return new Tick(delay, fn);
    }

    static Command println(String text) {
        return new PrintLine(text);
    }

    static Command checkWindowSize() {
        return new CheckWindowSize();
    }

    static Command batch(Command... commands) {
        return new Batch(Arrays.asList(commands));
    }

    record None() implements Command {
    }

    record Simple(Supplier<Message> supplier) implements Command {
    }

    record Tick(Duration delay, Function<Instant, Message> fn) implements Command {
    }

    record CheckWindowSize() implements Command {
    }

    record Batch(List<Command> commands) implements Command {
    }

    record PrintLine(String text) implements Command {
    }
}
