package com.study.permission;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleTest {
    @Test
    void matchesExactAndGlobRulesAgainstWholeCommand() {
        Rule exact = Rule.parse("Bash(git status)", Decision.ALLOW).orElseThrow();
        Rule glob = Rule.parse("bash(mvn *)", Decision.ALLOW).orElseThrow();

        assertTrue(exact.matches("git status"));
        assertFalse(exact.matches("git status --short"));
        assertTrue(glob.matches("mvn test"));
        assertFalse(glob.matches("cmd /c mvn test"));
    }

    @Test
    void treatsRegexCharactersLiterallyAndSupportsEscapes() {
        Rule regexChars = Rule.parse("Bash(echo [a].txt)", Decision.ALLOW).orElseThrow();
        Rule literalStar = Rule.parse("Bash(echo \\*)", Decision.ALLOW).orElseThrow();

        assertTrue(regexChars.matches("echo [a].txt"));
        assertFalse(regexChars.matches("echo a.txt"));
        assertTrue(literalStar.matches("echo *"));
        assertFalse(literalStar.matches("echo anything"));
    }

    @Test
    void exactRuleRoundTripsWindowsPathsAndGlobCharacters() {
        String command = "type C:\\work\\*.txt";
        Rule original = Rule.exact(command, Decision.ALLOW);
        Rule parsed = Rule.parse(original.render(), Decision.ALLOW).orElseThrow();

        assertTrue(parsed.matches(command));
        assertFalse(parsed.matches("type C:\\work\\a.txt"));
        assertEquals(original, parsed);
    }

    @Test
    void denyWinsWithinRuleSet() {
        Rule allow = Rule.parse("Bash(git *)", Decision.ALLOW).orElseThrow();
        Rule deny = Rule.parse("Bash(git push *)", Decision.DENY).orElseThrow();
        RuleSet rules = RuleSet.of(List.of(allow), List.of(deny));

        assertEquals(Decision.DENY, rules.match("git push origin main").orElseThrow().decision());
        assertEquals(Decision.ALLOW, rules.match("git status").orElseThrow().decision());
    }

    @Test
    void rejectsInvalidAndNonBashRules() {
        assertTrue(Rule.parse("Read(file)", Decision.ALLOW).isEmpty());
        assertTrue(Rule.parse("Bash()", Decision.ALLOW).isEmpty());
        assertTrue(Rule.parse("Bash(git status", Decision.ALLOW).isEmpty());
        assertTrue(Rule.parse("Bash(git status)", Decision.ASK).isEmpty());
    }
}
