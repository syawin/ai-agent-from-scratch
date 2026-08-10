# OpenAI Agent Loop (Kotlin)

A terminal-based interactive AI agent that connects to a locally running LM Studio instance via the OpenAI SDK for Kotlin.

## Dependencies

- OpenAI Java/Kotlin SDK (`com.openai:openai-java:4.42.0`) for chat completions
- Jsoup for HTML parsing in the web fetch tool
- MockK for unit testing

## Configuration

- Connects to `http://localhost:1234/v1/` using API key `"lm-studio"`
- Uses model name `"local-model"`
- Request timeout: 30 seconds
- `.idea/` and `.env` (which contains `OPENAI_API_KEY`) are not committed to version control

## Agent Loop

The main loop reads lines from stdin and drives the conversation:

- Prints a `> ` prompt before each input
- **Blank input** (empty or whitespace-only) is skipped; no API call is made
- **`\exit` command** terminates the loop; no API call is made
- **EOF** terminates the loop
- Each user message is appended to the running conversation history before the API call

### Tool-calling inner loop

After each user message, the agent calls the model in a loop until a plain-text response with no tool calls is returned:

1. Build a `ChatCompletionCreateParams` with the full message history and the tool schemas, `temperature=0.7`
2. Call the model
3. If the response has tool calls, execute them (see Tools), append results to history, and repeat
4. If the response has no tool calls, print the content and break
5. If the model returns no choices, print `"(no response)"` and break

### Error handling

- **`ConnectException`**: prints a message indicating LM Studio is not reachable (including `e.message`), then exits with code 1
- **`SocketTimeoutException`**: prints a timeout message (including `e.message`), then exits with code 1
- **Generic `Exception`**: prints `"An unexpected error occurred: <e.message>"`, removes all messages added during the failed turn from conversation history (rolling back to the state before the user's message), and continues the loop

## Tools

The agent exposes seven tools to the model. Tool errors (unknown tool, bad arguments, execution failure) are returned as error strings to the model rather than crashing the loop.

| Tool | Description |
|---|---|
| `run_bash` | Runs a bash command; returns stdout and stderr (stderr appended with `STDERR:` header); returns `"(no output)"` if both are empty |
| `read_file` | Reads a range of lines from a file; 1-indexed `offset`, default `limit=200`; returns `"File not found: …"` for missing files |
| `glob_files` | Finds files matching a glob pattern under a root directory; matches files at any depth; returns paths in sorted order or `"(no matches)"` |
| `grep` | Searches file contents with a regex; results include absolute path and 1-based line number; supports `include` glob to filter files; returns `"(no matches)"` or error strings for bad regex/path |
| `write_file` | Writes content to a file, creating parent directories as needed; reports UTF-8 byte count written |
| `edit_file` | Replaces the **first** occurrence of `old_string` with `new_string` in a file; returns an error if the file or string is not found |
| `webfetch` | Fetches a public `http`/`https` URL (up to 2 MiB); returns plain text extracted from HTML; rejects other schemes |

## Tests

- **Unit tests** (`AgentLoopTest`, `ToolsTest`): run by default via `./gradlew test`; tagged `integration` tests are excluded
- **Integration test** (`ServiceRunningTest`): tagged `integration`; requires LM Studio running at `localhost:1234`; run via `./gradlew integrationTest`
- Coverage is enforced via JaCoCo: ≥80% line coverage, 100% method coverage

### Key agent loop test cases

- Blank input is skipped without making an API call
- `\exit` terminates the loop without making an API call
- Generic exception removes the failed message from conversation history so the next turn starts clean
- Tool failures (malformed arguments, missing arguments, execution errors) are returned to the model as error strings and the conversation recovers
- `ConnectException` and `SocketTimeoutException` print error messages containing the exception details and exit with status 1
