---
name: commit
description: Inspect git changes and prepare a clear commit
allowed_tools: [Bash, ReadFile, Grep]
mode: inline
---

# Commit Skill

Prepare a commit for the current repository.

Follow this SOP:

1. Inspect the working tree with git status.
2. Inspect relevant diffs before summarizing changes.
3. Explain the changes and any risk or missing verification.
4. Propose a concise commit message.
5. Only stage files or create a commit when the user clearly confirms that action.

$ARGUMENTS
