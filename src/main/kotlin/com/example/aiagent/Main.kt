package com.example.aiagent

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.core.JsonValue
import com.openai.models.FunctionDefinition
import com.openai.models.FunctionParameters
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam
import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.models.chat.completions.ChatCompletionFunctionTool
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall
import com.openai.models.chat.completions.ChatCompletionMessageParam
import com.openai.models.chat.completions.ChatCompletionMessageToolCall
import com.openai.models.chat.completions.ChatCompletionTool
import com.openai.models.chat.completions.ChatCompletionToolMessageParam
import java.net.ConnectException
import java.net.SocketTimeoutException
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
    )

/**
 * A list of pre-defined tools represented as `ChatCompletionTool` objects, derived from raw schema
 * definitions. Each tool's schema is parsed and transformed into a structured representation,
 * encapsulating its function name, description, and parameters.
 *
 * The transformation involves:
 * - Extracting the `function` and its `parameters` from the raw schema.
 * - Constructing a `FunctionParameters` object by mapping schema parameter values.
 * - Building a `FunctionDefinition` object to represent the function metadata.
 * - Wrapping the function metadata in a `ChatCompletionTool` object.
 *
 * This variable helps streamline the handling of tool schemas within the application, providing
 * a strongly-typed and structured set of tools for chat completion and related tasks.
 */
private val TOOL_SCHEMAS: List<ChatCompletionTool> =
    // Transforms raw schema definitions into typed tool objects
    getToolSchemas().map { schema ->
        @Suppress("UNCHECKED_CAST")
        val function = schema.getValue("function") as Map<String, Any>

        @Suppress("UNCHECKED_CAST")
        val parameters = function.getValue("parameters") as Map<String, Any>
        val functionParameters =
            FunctionParameters
                .builder()
                .additionalProperties(parameters.mapValues { JsonValue.from(it.value) })
                .build()
        val definition =
            // Constructs function definition from schema metadata
            FunctionDefinition
                .builder()
                .name(function.getValue("name") as String)
                .description(function.getValue("description") as String)
                .parameters(functionParameters)
                .build()
        ChatCompletionTool.ofFunction(
            ChatCompletionFunctionTool.builder().function(definition).build(),
        )
    }

/**
 * Processes a list of tool call requests, executes corresponding registry functions,
 * and appends the results to the provided mutable list of messages.
 *
 * @param toolCalls A list of tool call requests represented by `ChatCompletionMessageToolCall` instances.
 *                  Each item represents a specific function or tool execution request.
 * @param messages A mutable list of `ChatCompletionMessageParam` to which the results of the tool executions
 *                 will be appended.
 */
fun handleToolCalls(
    toolCalls: List<ChatCompletionMessageToolCall>,
    messages: MutableList<ChatCompletionMessageParam>,
) {
    // Iterates tool requests; invokes registry functions; appends results
    for (toolCall in toolCalls) {
        if (!toolCall.isFunction()) {
            continue
        }

        val functionCall: ChatCompletionMessageFunctionToolCall = toolCall.asFunction()
        val name = functionCall.function().name()
        // Executes requested function; captures output or error message
        val result =
            // Executes registry function; captures output or error
            try {
                val args: Map<String, Any?> =
                    json.readValue(
                        functionCall.function().arguments(),
                        object : TypeReference<Map<String, Any?>>() {},
                    )

                println("  [tool] $name($args)")

                TOOL_REGISTRY[name]?.invoke(args)
                    ?: "Error: unknown tool '$name'. Available tools: ${TOOL_REGISTRY.keys}"
            } catch (e: Exception) {
                "Error executing tool '$name': ${e.message ?: e.javaClass.simpleName}"
            }

        println("  [tool result] ${result.take(200)}${if (result.length > 200) "..." else ""}")
        // Encapsulates tool execution result into chat message structure

        messages.add(
            // Encapsulates tool result into chat message parameter
            ChatCompletionMessageParam.ofTool(
                ChatCompletionToolMessageParam
                    .builder()
                    .toolCallId(functionCall.id())
                    .content(result)
                    .build(),
            ),
        )
    }
}

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
    val messages =
        // Initializes conversation with system instructions
        mutableListOf(
            ChatCompletionMessageParam.ofSystem(
                com.openai.models.chat.completions.ChatCompletionSystemMessageParam
                    .builder()
                    .content(
                        "You are a helpful assistant. You have tools to read and write files, " +
                            "search the file system, and fetch web pages. Use them to help the user.",
                    ).build(),
            ),
        )

    // Orchestrates interactive user-agent conversation loop
    while (true) {
        print("> ")
        val input = readlnOrNull() ?: break
        if (input.trim() == "\\exit") break
        if (input.isBlank()) continue

        val conversationStart = messages.size
        messages.add(
            ChatCompletionMessageParam.ofUser(
                com.openai.models.chat.completions.ChatCompletionUserMessageParam
                    .builder()
                    .content(input)
                    .build(),
            ),
        )

        // Manages request lifecycle with error handling and recovery
        // Executes iterative tool-calling loop until final response
        try {
            // Executes iterative tool-calling loop until final response received
            while (true) {
                // Configures chat completion request with model and tools
                val params =
                    ChatCompletionCreateParams
                        .builder()
                        .model("local-model")
                        .messages(messages)
                        .tools(TOOL_SCHEMAS)
                        .temperature(0.7)
                        .build()
                val completion = client.chat().completions().create(params)
                val message = completion.choices().firstOrNull()?.message()

                // Handles empty model response; logs and persists state
                if (message == null) {
                    println("(no response)")
                    messages.add(
                        ChatCompletionMessageParam.ofAssistant(
                            ChatCompletionAssistantMessageParam
                                .builder()
                                .content("(no response)")
                                .build(),
                        ),
                    )
                    break
                }

                val toolCalls = message.toolCalls().orElse(emptyList())
                val assistantMessageBuilder = ChatCompletionAssistantMessageParam.builder()
                message.content().ifPresent(assistantMessageBuilder::content)
                if (toolCalls.isNotEmpty()) assistantMessageBuilder.toolCalls(toolCalls)
                val assistantMessage = assistantMessageBuilder.build()
                messages.add(ChatCompletionMessageParam.ofAssistant(assistantMessage))

                // Executes tool calls or prints content and terminates
                if (toolCalls.isNotEmpty()) {
                    handleToolCalls(toolCalls, messages)
                } else {
                    println(message.content().orElse(""))
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
            while (messages.size > conversationStart) messages.removeLast()
        }
    }
}

fun main() {
    val client: OpenAIClient =
        OpenAIOkHttpClient
            .builder()
            .apiKey("lm-studio")
            .baseUrl("http://localhost:1234/v1/")
            .timeout(Duration.ofSeconds(30))
            .build()
    agentLoop(client)
}
