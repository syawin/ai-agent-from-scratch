# OpenAI Agent Loop (Kotlin)

A terminal-based interactive AI agent that connects to a locally running LM Studio instance via the OpenAI SDK for Kotlin.

## Dependencies

- OpenAI Java/Kotlin SDK (`com.openai:openai-java:4.42.0`) for chat completions

## Configuration

- `.idea/` and `.env` (which contains `OPENAI_API_KEY`) are not committed to version control

## Agent Loop

The main loop reads lines from stdin and drives the conversation.

- **Blank input** (empty or whitespace-only) is skipped; no API call is made.
- **`\exit`** terminates the loop; no API call is made.
- Each user message is appended to the running conversation history before the API call.

### Error Handling

- **`ConnectException`** — prints a message indicating LM Studio is not reachable (including `e.message`), then exits with code 1.
- **`SocketTimeoutException`** — prints a timeout message (including `e.message`), then exits with code 1.
- **Generic `Exception`** — prints `"An unexpected error occurred: <e.message>"`, rolls back the conversation history to its state before the failed turn, and continues the loop.

## Tests

Key previously-failing cases that are now covered:

- Blank input is skipped without making an API call.
- `\exit` terminates the loop without making an API call.
- A generic exception rolls back the failed message from conversation history so the next turn starts clean.
