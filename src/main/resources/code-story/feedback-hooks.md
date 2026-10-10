# Optional feedback reminders

Code Story keeps `.codestory/local/pending-feedback.json` at each repository root.
It lists story IDs, local/shared folders, pending counts and rounds, including
feedback an agent has fetched but not answered. It contains no comment text or
code. Drafts are not pending feedback. Shared stories also use this local index.
The IDE maintains it while the project is open; reopen the project after external
story changes to refresh the index.

The plugin never installs hooks. Copy the helper and the configuration for your
agent yourself, merging with existing hooks. These hooks only remind the agent;
they do not make code changes or start feedback mode. They need Python 3. On Windows,
replace `python3` with your Python executable if needed.

Save this helper as `~/.codestory/feedback-hook.py` (create the directory yourself):

```python
import json
import os
from pathlib import Path
import sys

try:
    event = json.load(sys.stdin)
    if not isinstance(event, dict):
        raise ValueError("Invalid hook input")
    cwd = Path(event.get("cwd") or os.getcwd()).resolve()
    ancestors = [cwd, *cwd.parents]
    root = next((p for p in ancestors if (p / ".git").exists()), None)
    if root is None:
        root = next((p for p in ancestors if (p / ".codestory").is_dir()), cwd)
    index = root / ".codestory/local/pending-feedback.json"
    data = json.loads(index.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise ValueError("Invalid feedback index")
    if data.get("schemaVersion") != 1 or data.get("pendingComments", 0) <= 0:
        raise ValueError("No pending feedback")
    context = (
        "Code Story feedback is pending in this repository. Mention it to the "
        "developer and process it with the code-story feedback skill when they "
        "ask or agree. Fetch current feedback from the IDE before making changes."
    )
    if sys.argv[1] == "copilot":
        print(json.dumps({"additionalContext": context}))
    else:
        print(json.dumps({"hookSpecificOutput": {
            "hookEventName": event["hook_event_name"],
            "additionalContext": context
        }}))
except (OSError, ValueError, KeyError, TypeError, IndexError):
    print("{}")
```

Claude Code: merge into `~/.claude/settings.json` (or `.claude/settings.json` in the
project). Reminders run at session start and on each prompt:

```json
{
  "hooks": {
    "SessionStart": [{"hooks": [{"type": "command", "command": "python3 \"$HOME/.codestory/feedback-hook.py\" claude"}]}],
    "UserPromptSubmit": [{"hooks": [{"type": "command", "command": "python3 \"$HOME/.codestory/feedback-hook.py\" claude"}]}]
  }
}
```

Codex: merge into `~/.codex/hooks.json` (or `.codex/hooks.json` in the project), using
a Codex version with lifecycle hook support. Reminders run at session start and on
each prompt. Review/enable the hook in Codex if your version requires that:

```json
{
  "hooks": {
    "SessionStart": [{"hooks": [{"type": "command", "command": "python3 \"$HOME/.codestory/feedback-hook.py\" codex"}]}],
    "UserPromptSubmit": [{"hooks": [{"type": "command", "command": "python3 \"$HOME/.codestory/feedback-hook.py\" codex"}]}]
  }
}
```

Copilot CLI: merge into `.github/hooks/code-story.json` in the project. The reminder
runs at session start. The Rider Copilot plugin has no suitable hook:

```json
{
  "version": 1,
  "hooks": {
    "sessionStart": [{
      "type": "command",
      "bash": "python3 \"$HOME/.codestory/feedback-hook.py\" copilot",
      "powershell": "python \"$HOME/.codestory/feedback-hook.py\" copilot",
      "timeoutSec": 5
    }]
  }
}
```

Agent references: [Claude Code hooks](https://code.claude.com/docs/en/hooks),
[Codex hooks](https://developers.openai.com/codex/hooks),
[Copilot CLI hooks](https://docs.github.com/en/copilot/reference/hooks-configuration).
