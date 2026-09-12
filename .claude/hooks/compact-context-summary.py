#!/usr/bin/env python3
"""SessionStart(compact) hook: summarize files touched during the session so
far, so the summary can be re-injected as context immediately after
conversation compaction.

Source data (best-effort, see LIMITATIONS below):
  1. This session's transcript file (JSONL) - which files were opened,
     edited, or grepped by name via tool calls.
  2. `git status` / `git diff --numstat` in the project - which tracked
     files actually changed on disk right now.

LIMITATIONS (files this can miss or mis-rank):
  - Only tool_use calls for Read/Write/Edit/MultiEdit/NotebookEdit/Grep are
    inspected. Files touched only through Bash (cat, mv, sed, a build step,
    a script) are invisible here.
  - Sub-agent (Task) transcripts are separate files not referenced from the
    main transcript, so file activity inside a dispatched sub-agent is not
    visible to this script.
  - "Recency" is transcript order, not semantic relevance - a file opened
    once early and abandoned can still outrank a file discussed without a
    matching tool call.
  - The git portion reflects the current working tree only (vs. the last
    commit); it cannot tell which *earlier, since-reverted* edits happened
    mid-session.
  - Paths outside the project directory, common build/dependency
    directories, and common secret-like filenames are deliberately
    excluded (see EXCLUDE_DIRS / SECRET_NAME_RE below) rather than risk
    surfacing generated or sensitive content.
"""

import json
import os
import re
import subprocess
import sys

MAX_FILES = 15
EXCLUDE_DIRS = {
    ".git", "build", ".gradle", "out", "target", "node_modules", "dist",
    ".kotlin", ".idea",
}
FILE_TOOLS = {
    "Read": "file_path",
    "Write": "file_path",
    "Edit": "file_path",
    "MultiEdit": "file_path",
    "NotebookEdit": "notebook_path",
}
SECRET_NAME_RE = re.compile(
    r"(^|/)\.env($|[./])|credentials|secrets?\.|\.pem$|\.key$|id_rsa|\.p12$",
    re.IGNORECASE,
)


def eprint(*args):
    print(*args, file=sys.stderr)


def is_excluded(rel_path: str) -> bool:
    parts = rel_path.split(os.sep)
    if any(p in EXCLUDE_DIRS for p in parts):
        return True
    if SECRET_NAME_RE.search(rel_path):
        return True
    return False


def normalize(path: str, project_dir: str) -> str | None:
    """Return a path relative to project_dir, or None if outside it."""
    if not path:
        return None
    abs_path = path if os.path.isabs(path) else os.path.join(project_dir, path)
    abs_path = os.path.normpath(abs_path)
    try:
        rel = os.path.relpath(abs_path, project_dir)
    except ValueError:
        return None
    if rel.startswith(".." + os.sep) or rel == "..":
        return None
    return rel


def touched_files_from_transcript(transcript_path: str, project_dir: str):
    """Return {rel_path: [action_names_in_order]} ordered by first sighting,
    reflecting the order files were touched across the transcript."""
    touched: dict[str, list[str]] = {}
    if not transcript_path or not os.path.isfile(transcript_path):
        return touched

    try:
        with open(transcript_path, "r", encoding="utf-8", errors="replace") as fh:
            for line in fh:
                line = line.strip()
                if not line:
                    continue
                try:
                    entry = json.loads(line)
                except json.JSONDecodeError:
                    continue

                message = entry.get("message") or {}
                content = message.get("content")
                if not isinstance(content, list):
                    continue

                for block in content:
                    if not isinstance(block, dict) or block.get("type") != "tool_use":
                        continue
                    name = block.get("name")
                    tool_input = block.get("input") or {}

                    field = FILE_TOOLS.get(name)
                    if field:
                        raw_path = tool_input.get(field)
                    elif name == "Grep":
                        raw_path = tool_input.get("path")
                        # Only treat it as a single-file reference, not a
                        # directory-scoped search.
                        if raw_path and "." not in os.path.basename(raw_path):
                            raw_path = None
                    else:
                        raw_path = None

                    if not raw_path:
                        continue
                    rel = normalize(raw_path, project_dir)
                    if not rel or is_excluded(rel):
                        continue
                    actions = touched.get(rel, [])
                    if name not in actions:
                        actions.append(name)
                    touched.pop(rel, None)
                    touched[rel] = actions  # re-insert at end: recency order
    except OSError as exc:
        eprint(f"compact-context-summary: could not read transcript: {exc}")

    return touched


def git_change_map(project_dir: str):
    """Return {rel_path: description} for the current working tree state."""
    changes: dict[str, str] = {}
    try:
        status = subprocess.run(
            ["git", "-C", project_dir, "status", "--porcelain=v1"],
            capture_output=True, text=True, timeout=5, check=False,
        )
    except (OSError, subprocess.SubprocessError) as exc:
        eprint(f"compact-context-summary: git status failed: {exc}")
        return changes
    if status.returncode != 0:
        return changes  # not a git repo, or git unavailable

    numstat = {}
    try:
        diff = subprocess.run(
            ["git", "-C", project_dir, "diff", "HEAD", "--numstat"],
            capture_output=True, text=True, timeout=5, check=False,
        )
        for line in diff.stdout.splitlines():
            fields = line.split("\t")
            if len(fields) == 3:
                added, removed, path = fields
                numstat[path] = (added, removed)
    except (OSError, subprocess.SubprocessError):
        pass

    status_labels = {
        "M": "modified", "A": "added", "D": "deleted", "R": "renamed",
        "C": "copied", "U": "unmerged", "?": "untracked", "!": "ignored",
    }
    for line in status.stdout.splitlines():
        if len(line) < 4:
            continue
        code = line[:2]
        path = line[3:]
        if " -> " in path:
            path = path.split(" -> ", 1)[1]
        rel = normalize(path, project_dir)
        if not rel or is_excluded(rel):
            continue
        primary = code.strip()[:1] or "?"
        label = status_labels.get(primary, primary)
        stat = ""
        if rel in numstat:
            added, removed = numstat[rel]
            if added != "-" and removed != "-":
                stat = f" (+{added}/-{removed})"
        changes[rel] = f"{label}{stat}"

    return changes


def main():
    try:
        payload = json.load(sys.stdin)
    except (json.JSONDecodeError, ValueError):
        payload = {}

    source = payload.get("source")
    if source != "compact" and os.environ.get("COMPACT_CONTEXT_FORCE") != "1":
        # Matcher already restricts this, but stay a no-op if invoked
        # any other way (e.g. manual testing without --force).
        sys.exit(0)

    project_dir = (
        os.environ.get("CLAUDE_PROJECT_DIR")
        or payload.get("cwd")
        or os.getcwd()
    )
    transcript_path = payload.get("transcript_path", "")

    touched = touched_files_from_transcript(transcript_path, project_dir)
    git_changes = git_change_map(project_dir)

    lines = []

    changed_paths = [p for p in git_changes if p in touched] + \
        [p for p in git_changes if p not in touched]
    for rel in changed_paths[:MAX_FILES]:
        actions = touched.get(rel)
        role = f", {'/'.join(a.lower() for a in actions)}" if actions else ""
        lines.append(f"- {rel} — {git_changes[rel]}{role}")

    remaining = MAX_FILES - len(lines)
    if remaining > 0:
        referenced_only = [p for p in reversed(list(touched)) if p not in git_changes]
        for rel in referenced_only[:remaining]:
            actions = "/".join(a.lower() for a in touched[rel])
            lines.append(f"- {rel} — referenced ({actions}), no working-tree change")

    if not lines:
        sys.exit(0)  # nothing worth restoring; don't inject empty noise

    summary = (
        "Files modified or referenced earlier this session (auto-summarized "
        "after compaction; git status + transcript tool calls, most "
        "relevant first — see .claude/hooks/compact-context-summary.py "
        "docstring for coverage limitations):\n" + "\n".join(lines)
    )

    print(json.dumps({
        "hookSpecificOutput": {
            "hookEventName": "SessionStart",
            "additionalContext": summary,
        }
    }))
    sys.exit(0)


if __name__ == "__main__":
    main()
