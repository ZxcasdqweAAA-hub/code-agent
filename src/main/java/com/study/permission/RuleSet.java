package com.study.permission;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class RuleSet {
    private final List<Rule> allow;
    private final List<Rule> deny;

    private RuleSet(List<Rule> allow, List<Rule> deny) {
        this.allow = allow == null ? List.of() : List.copyOf(allow);
        this.deny = deny == null ? List.of() : List.copyOf(deny);
    }

    public static RuleSet empty() {
        return new RuleSet(List.of(), List.of());
    }

    public static RuleSet of(List<Rule> allow, List<Rule> deny) {
        return new RuleSet(allow, deny);
    }

    public Optional<RuleMatch> match(String command) {
        for (Rule rule : deny) {
            if (rule.matches(command)) {
                return Optional.of(new RuleMatch(Decision.DENY, rule));
            }
        }
        for (Rule rule : allow) {
            if (rule.matches(command)) {
                return Optional.of(new RuleMatch(Decision.ALLOW, rule));
            }
        }
        return Optional.empty();
    }

    public RuleSet withAllow(Rule rule) {
        if (rule == null || rule.decision() != Decision.ALLOW || allow.contains(rule)) {
            return this;
        }
        List<Rule> updated = new ArrayList<>(allow);
        updated.add(rule);
        return new RuleSet(updated, deny);
    }

    public List<Rule> allowRules() {
        return allow;
    }

    public List<Rule> denyRules() {
        return deny;
    }

    public record RuleMatch(Decision decision, Rule rule) {
    }
}
