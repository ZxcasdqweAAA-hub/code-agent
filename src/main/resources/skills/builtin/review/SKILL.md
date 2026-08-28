---
name: review
description: Review code changes and identify concrete risks
allowed_tools: [ReadFile, Grep, Glob]
mode: fork
fork_context: recent
---

# Review Skill

Review the current code changes with a bug-finding stance.

Focus on:

1. Behavioral regressions.
2. Security or permission mistakes.
3. Data loss, race conditions, and error-handling gaps.
4. Missing tests for changed behavior.

Return findings first, ordered by severity. Include file paths and line references when available. If no issues are found, say that clearly and mention residual test risk.

$ARGUMENTS
