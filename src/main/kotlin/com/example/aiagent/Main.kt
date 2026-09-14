package com.example.aiagent

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.core.JsonValue
import com.openai.models.responses.EasyInputMessage
import com.openai.models.responses.FunctionTool
import com.openai.models.responses.Response
import com.openai.models.responses.ResponseCreateParams
import com.openai.models.responses.ResponseFunctionToolCall
import com.openai.models.responses.ResponseInputItem
import com.openai.models.responses.ResponseOutputItem
import com.openai.models.responses.Tool
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.default
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.nio.file.Paths
import java.time.Duration
import kotlin.system.exitProcess

private val json = ObjectMapper()

/**
 * A registry of tools represented as a map. Each key is a tool name (String) and
 * its corresponding value is a lambda function that takes a map of string keys
 * and optional values as input (`Map<String, Any?>`) and returns a string output.
 *
 * The available tools include:
 * - `run_bash`: Executes a Bash command.
 * - `read_file`: Reads a specified range of lines from a file.
 * - `glob_files`: Finds files matching a glob pattern in a directory.
 * - `grep`: Searches for lines matching a regex pattern in files under a specified path.
 * - `write_file`: Writes content to a file at a given path.
 * - `edit_file`: Replaces the first occurrence of a string in a file.
 * - `webfetch`: Fetches content from a specified URL.
 * - `read_scratchpad`: Reads the current scratchpad content.
 * - `write_scratchpad`: Replaces the current scratchpad content.
 * - `todo_append`: Adds an item to the to-do list.
 * - `todo_list`: Lists current to-do items.
 * - `todo_update`: Updates an existing to-do item.
 * - `ask_question`: Prompts the user with a question via stdin and returns their trimmed answer.
 *
 * This registry enables dynamic invocation of predefined tools based on their string key.
 */
private val TOOL_REGISTRY: Map<String, (Map<String, Any?>) -> String> =
    mapOf(
        "run_bash" to { args -> runBash(args.getValue("command") as String) },
        "read_file" to { args ->
            readFile(
                args.getValue("path") as String,
                (args["offset"] as? Number)?.toInt() ?: 1,
                (args["limit"] as? Number)?.toInt() ?: 200,
            )
        },
        "glob_files" to { args ->
            globFiles(args.getValue("pattern") as String, args["path"] as? String ?: ".")
        },
        "grep" to { args ->
            grep(
                args.getValue("pattern") as String,
                args["path"] as? String ?: ".",
                args["include"] as? String ?: "*",
            )
        },
        "write_file" to { args ->
            writeFile(args.getValue("path") as String, args.getValue("content") as String)
        },
        "edit_file" to { args ->
            editFile(
                args.getValue("path") as String,
                args.getValue("old_string") as String,
                args.getValue("new_string") as String,
            )
        },
        "webfetch" to { args -> webfetch(args.getValue("url") as String) },
        "read_scratchpad" to { readScratchpad() },
        "write_scratchpad" to { args -> writeScratchpad(args.getValue("content") as String) },
        "todo_append" to { args ->
            todoAppend(
                (args.getValue("id") as Number).toInt(),
                args.getValue("content") as String,
                args.getValue("status") as String,
            )
        },
        "todo_list" to { args -> todoList(args["include_completed"] as? Boolean ?: false) },
        "todo_update" to { args ->
            todoUpdate(
                args.getValue("id") as String,
                args["content"] as? String,
                (args["status"] as? String)?.let(TaskStatus::from),
            )
        },
        "ask_question" to { args -> askQuestion(args.getValue("question") as String) },
    )

/**
 * A list of pre-defined tools represented as `Tool` objects, derived from raw schema
 * definitions. Each tool's schema is parsed and transformed into a structured representation,
 * encapsulating its function name, description, and parameters.
 *
 * The transformation involves:
 * - Extracting the `function` and its `parameters` from the raw schema.
 * - Constructing a `FunctionTool.Parameters` object by mapping schema parameter values.
 * - Building a `FunctionTool` object to represent the function metadata.
 * - Wrapping the function metadata in a `Tool` object.
 *
 * This variable helps streamline the handling of tool schemas within the application, providing
 * a strongly-typed and structured set of tools for the Responses API and related tasks.
 */
private val TOOL_SCHEMAS: List<Tool> =
    // Transforms raw schema definitions into typed tool objects
    getToolSchemas().map { schema ->
        @Suppress("UNCHECKED_CAST")
        val function = schema.getValue("function") as Map<String, Any>

        @Suppress("UNCHECKED_CAST")
        val parameters =
            function.getValue("parameters") as Map<String, Any>
        val functionParameters =
            FunctionTool.Parameters
                .builder()
                .additionalProperties(parameters.mapValues { JsonValue.from(it.value) })
                .build()
        val definition =
            // Constructs function tool definition from schema metadata
            FunctionTool
                .builder()
                .name(function.getValue("name") as String)
                .description(function.getValue("description") as String)
                .parameters(functionParameters)
                .strict(false)
                .build()
        Tool.ofFunction(definition)
    }

/**
 * Processes a list of tool call requests, executes corresponding registry functions,
 * and appends the results to the provided mutable list of input items.
 *
 * @param toolCalls A list of tool call requests represented by `ResponseOutputItem` instances.
 *                  Each item represents a specific function or tool execution request.
 * @param input A mutable list of `ResponseInputItem` to which the results of the tool executions
 *              will be appended.
 */
fun handleToolCalls(
    toolCalls: List<ResponseOutputItem>,
    input: MutableList<ResponseInputItem>,
) {
    // Iterates tool requests; invokes registry functions; appends results
    for (item in toolCalls) {
        if (!item.isFunctionCall()) {
            continue
        }

        val functionCall: ResponseFunctionToolCall = item.asFunctionCall()
        val name = functionCall.name()
        // Executes requested function; captures output or error message
        val result =
            // Executes registry function; captures output or error
            try {
                val args: Map<String, Any?> =
                    json.readValue(
                        functionCall.arguments(),
                        object : TypeReference<Map<String, Any?>>() {},
                    )

                println("  [tool] $name($args)")

                TOOL_REGISTRY[name]?.invoke(args)
                    ?: "Error: unknown tool '$name'. Available tools: ${TOOL_REGISTRY.keys}"
            } catch (e: Exception) {
                "Error executing tool '$name': ${e.message ?: e.javaClass.simpleName}"
            }

        println("  [tool result] ${result.take(200)}${if (result.length > 200) "..." else ""}")
        // Encapsulates tool execution result into input item structure

        input.add(
            // Encapsulates tool result into a function-call-output input item
            ResponseInputItem.ofFunctionCallOutput(
                ResponseInputItem.FunctionCallOutput
                    .builder()
                    .callId(functionCall.callId())
                    .output(result)
                    .build(),
            ),
        )
    }
}

/**
 * The system instructions supplied to the model on every request via
 * `ResponseCreateParams.instructions(...)`. Preserved verbatim from the prior
 * Chat Completions system message.
 */
private val SYSTEM_INSTRUCTIONS =
    """You are a capable coding and research assistant.

## Available tools

Action tools: read_file, write_file, edit_file, glob_files, grep, run_bash, webfetch

Planning tools:
- Scratchpad (read_scratchpad / write_scratchpad): your private working memory. Use it to think through an approach, store intermediate findings, or draft content before committing. Each write fully replaces the previous content.
- To-do list (todo_append / todo_list / todo_update): a persistent task tracker. Items carry a status: pending, in_progress, done, cancelled, or failed.
- Clarifying questions (ask_question): ask the user a question via stdin when a requirement is genuinely ambiguous and only they can resolve it. Use sparingly — prefer acting on a reasonable default.

## Working directory

The current working directory is always the user's project root. When asked to work on a project or codebase without a specified path, start by exploring '.' with glob_files or run_bash. Never ask the user to supply a path.

## How to plan

For complex or multi-step tasks (roughly 3 or more distinct steps, or when the path forward is unclear):
1. Write your initial thinking and approach to the scratchpad before acting.
2. Break the work into concrete steps and add each one to the to-do list with todo_append (status: pending).
3. Before starting a step, mark it in_progress with todo_update. Keep only one item in_progress at a time.
4. Mark items done immediately after completing them — do not batch completions.
5. Call todo_list to review remaining work before moving to the next step.
6. Mark tasks cancelled if they become unnecessary.

For simple, single-step tasks: act directly without creating todos.

Planning tool calls (write_scratchpad, todo_append, todo_update, todo_list) are internal bookkeeping, not responses to the user. After any planning tool call, always continue working immediately — make your next tool call or, once the task is fully complete, give a substantive final answer. Never emit an empty or whitespace-only message.

## Replanning

After every tool result, check whether the outcome matched your expectation. If a tool returns an error, unexpected output, or reveals information that changes your understanding of the task, do not move to the next planned step — replan first.

When a step fails:
1. Diagnose in the scratchpad — is this a recoverable input error (wrong path, typo, wrong argument) or a deeper problem (wrong approach, wrong assumption)?
2. Mark the task failed: todo_update(id, status='failed').
3. Choose a recovery action:
   - Retry: the failure is correctable. Fix the input and set the task back to in_progress. The tool will report which retry attempt this is.
   - Replace: the approach is wrong. Cancel the task and add a revised one.
   - Reorder: new information makes a different task more urgent. Update the pending items before continuing.
4. If todo_update reports that the retry limit has been reached, stop retrying. Write a clear diagnosis in the scratchpad — what you tried, what failed each time, and what you need — then give the user a concise escalation message and wait for their input.

When a tool succeeds but returns information that changes the picture, pause before acting. Call todo_list, reassess all pending items in the scratchpad, and cancel or replace any tasks that no longer make sense.

## How to use the scratchpad

Before each tool call during a complex task, update the scratchpad with your current thinking. Structure each entry around these five steps:

1. Restate the goal — write what you understand the task to be, in your own words. This catches misreads before they compound into wasted work.
2. Survey what you know — note which files you have seen, what the code structure looks like, and what constraints or requirements apply.
3. Evaluate options — reason through at least two approaches and explain why you are choosing one over the other (e.g. 'I could rewrite the middleware, or wrap it. Wrapping is safer because it leaves the existing call sites untouched.').
4. Anticipate failure modes — write down what could go wrong with the chosen approach and how you would diagnose it (e.g. 'If the tests fail after this, the most likely cause is that the session cookie name changed.').
5. Decide the next single action — commit to exactly one tool call. Do not plan several calls at once; decide the next step only.

Re-read the scratchpad whenever you resume after a tool result to keep your reasoning grounded in what you have already learned.

## Done detection

Do not give a final answer based on the task list being empty alone. Before declaring the task complete, verify all three of the following:

1. Structural completion — call todo_list and confirm there are no pending, in_progress, or failed items.
2. Verification — check the output against the original goal. For code tasks: run the tests or build with run_bash and confirm they pass. For research tasks: re-read the scratchpad and confirm the assembled answer addresses what was actually asked.
3. Uncertainty check — read the scratchpad and ask: are there unresolved questions, assumptions that were never validated, or tasks that were cancelled rather than properly completed?

If all three are satisfied, give your final answer. If any are not, re-enter the planning loop — add the outstanding items to the todo list and continue."""
        .trimIndent()

/**
 * Executes an interactive agent loop to facilitate conversation between the user and an AI model.
 * Handles user inputs, AI responses, and tool executions within the loop.
 *
 * @param client An instance of `OpenAIClient` used to communicate with the AI model for chat interactions.
 * @param exit A lambda function to terminate the program, typically used for error handling
 *             (default is `exitProcess`).
 */
fun agentLoop(
    client: OpenAIClient,
    exit: (Int) -> Nothing = ::exitProcess,
) {
    // Conversation history, seeded empty — system instructions travel per-request instead
    val input: MutableList<ResponseInputItem> = mutableListOf()

    // Orchestrates interactive user-agent conversation loop
    while (true) {
        print("> ")
        val userInput = readlnOrNull() ?: break
        if (userInput.trim() == "\\exit") break
        if (userInput.isBlank()) continue

        val conversationStart = input.size
        input.add(
            ResponseInputItem.ofEasyInputMessage(
                EasyInputMessage.builder().role(EasyInputMessage.Role.USER).content(userInput).build(),
            ),
        )

        // Manages request lifecycle with error handling and recovery
        // Executes iterative tool-calling loop until final response
        try {
            // Executes iterative tool-calling loop until final response received
            while (true) {
                // Configures response request with model, tools, and instructions
                val params =
                    ResponseCreateParams
                        .builder()
                        .model("local-model")
                        .inputOfResponse(input)
                        .tools(TOOL_SCHEMAS)
                        .instructions(SYSTEM_INSTRUCTIONS)
                        .temperature(0.7)
                        .build()
                val response: Response = client.responses().create(params)
                val output = response.output()

                // Handles empty model response; logs and persists state
                if (output.isEmpty()) {
                    println("(no response)")
                    input.add(
                        ResponseInputItem.ofEasyInputMessage(
                            EasyInputMessage.builder().role(EasyInputMessage.Role.ASSISTANT).content("(no response)").build(),
                        ),
                    )
                    break
                }

                // Appends each output item back into the conversation as an input item
                for (item in output) {
                    if (item.isFunctionCall()) {
                        input.add(ResponseInputItem.ofFunctionCall(item.asFunctionCall()))
                    } else if (item.isMessage()) {
                        input.add(ResponseInputItem.ofResponseOutputMessage(item.asMessage()))
                    }
                }

                val toolCalls = output.filter { it.isFunctionCall() }

                // Executes tool calls or prints content and terminates
                if (toolCalls.isNotEmpty()) {
                    handleToolCalls(toolCalls, input)
                } else {
                    val text =
                        output
                            .filter { it.isMessage() }
                            .flatMap { it.asMessage().content() }
                            .filter { it.isOutputText() }
                            .joinToString("") { it.asOutputText().text() }
                    println(text)
                    break
                }
            }
        } catch (e: ConnectException) {
            println("Could not connect to the service at http://localhost:1234. Is LM Studio running? (${e.message})")
            exit(1)
        } catch (e: SocketTimeoutException) {
            println("Request timed out. The service at http://localhost:1234 did not respond in time. (${e.message})")
            exit(1)
        } catch (e: Exception) {
            println("An unexpected error occurred: ${e.message}")
            while (input.size > conversationStart) input.removeLast()
        }
    }
}

private fun getLlmClient(): OpenAIClient =
    OpenAIOkHttpClient
        .builder()
        .apiKey("lm-studio")
        .baseUrl("http://localhost:1234/v1/")
        .timeout(Duration.ofSeconds(30))
        .build()

fun main(args: Array<String>) {
    val parser = ArgParser("ai-agent-from-scratch")
    // Configures permission mode options for tool execution
    val mode: PermissionMode by parser.option(
        ArgType.Choice<PermissionMode>(toString = { it.value }),
        fullName = "mode",
        description = "Permission mode for tool execution. 'default': read tools are free, " +
            "everything else requires approval. 'acceptEdits': read + write tools are free " +
            "when inside the working directory, everything else requires approval. " +
            "'dangerouslySkipPermissions': all tools run without any prompt.",
    ).default(PermissionMode.DEFAULT)
    parser.parse(args)

    val workingDir = Paths.get("").toAbsolutePath()

    println("Agent started in '${mode.value}' mode (working dir: $workingDir)")

    agentLoop(getLlmClient())
}
