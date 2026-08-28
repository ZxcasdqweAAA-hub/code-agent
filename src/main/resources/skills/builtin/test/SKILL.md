---
name: test
description: Run relevant tests and analyze failures
allowed_tools: [Bash, ReadFile, Grep, Glob]
mode: inline
---

# Test Skill

Run the relevant project checks for the current change.

Follow this SOP:

1. Identify the build system and the smallest meaningful test command.
2. Run the focused test first.
3. If the focused test passes and risk is broader, run the broader suite.
4. If a test fails, read the failure, identify the likely cause, and propose or apply a focused fix.
5. Report the exact command and result.

$ARGUMENTS
