# Future security considerations

Findings from an automated security review of `src/main/kotlin/com/example/aiagent/Tools.kt`
(commit `80ce39b`, 2026-07-10). Not addressed at the time — this is a learning project and the
agent-tool functions intentionally mirror the *unrestricted* capabilities of a real coding
agent (arbitrary shell exec, arbitrary file read) to keep the implementation simple. Revisit
these if the project moves beyond a learning exercise (e.g. gets exposed to untrusted input,
runs unattended, or is used as a base for something real).

## Findings

1. **Command injection** — `runBash(command)` passes the raw string straight into `bash -c`,
   so anything embedded in `command` executes with the process's full privileges. No allowlist,
   no confirmation step, no sandboxing.

2. **Path traversal** — `readFile`, `globFiles`, and `grep` pass `filePath`/`path` straight into
   `File()` / `Paths.get()` with no canonicalization or confinement to a project root. A
   `../../etc/passwd`-style input escapes any intended directory.

3. **Information disclosure** — `readFile` has no restriction on which files can be read.
   Anything the OS user running the JVM can access (SSH keys, `.env`, credentials, `/etc/passwd`)
   is readable through this tool.

4. **ReDoS / resource exhaustion** — `grep` compiles a user-supplied `pattern` directly into
   `Regex(...)` with no complexity check or timeout, so a crafted pattern can trigger catastrophic
   backtracking. Separately, `Files.walk` (used by `globFiles` and `grep`) has no depth cap and
   can hang on symlink loops or very large trees.

## Mitigation ideas (if/when this matters)

- Confine file/path tools to a canonicalized project root; reject any resolved path outside it.
- Add a regex match timeout or a pattern-complexity check before compiling user-supplied patterns.
- Add a depth limit and/or result cap to `Files.walk` calls.
- Gate `runBash` behind an allowlist or an explicit confirmation step, similar to how production
  agent harnesses (e.g. Claude Code itself) require permission prompts before executing shell
  commands.

See [[CLAUDE.md]] for the project-level note pointing here.
