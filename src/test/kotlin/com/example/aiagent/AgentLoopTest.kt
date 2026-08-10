package com.example.aiagent

import com.fasterxml.jackson.databind.ObjectMapper
import com.openai.client.OpenAIClient
import com.openai.models.chat.completions.ChatCompletion
import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.models.chat.completions.ChatCompletionMessage
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall
import com.openai.models.chat.completions.ChatCompletionMessageToolCall
import com.openai.services.blocking.ChatService
import com.openai.services.blocking.chat.ChatCompletionService
import com.sun.net.httpserver.HttpServer
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.PrintStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.util.Optional
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AgentLoopTest {
    private val originalIn: InputStream = System.`in`
    private val originalOut: PrintStream = System.out
    private val outputCapture = ByteArrayOutputStream()

    private val client: OpenAIClient = mockk()
    private val chatService: ChatService = mockk()
    private val completionService: ChatCompletionService = mockk()

    @BeforeTest
    fun setUp() {
        System.setOut(PrintStream(outputCapture))
        every { client.chat() } returns chatService
        every { chatService.completions() } returns completionService
    }

    @AfterTest
    fun tearDown() {
        System.setIn(originalIn)
        System.setOut(originalOut)
        unmockkAll()
    }

    private fun withInput(vararg lines: String) {
        val text = lines.joinToString("\n") + "\n"
        System.setIn(ByteArrayInputStream(text.toByteArray()))
    }

    private fun capturedOutput(): String = outputCapture.toString()

    private fun mockResponse(content: String): ChatCompletion {
        val message: ChatCompletionMessage = mockk()
        every { message.content() } returns Optional.of(content)
        every { message.toolCalls() } returns Optional.empty()
        val choice: ChatCompletion.Choice = mockk()
        every { choice.message() } returns message
        val completion: ChatCompletion = mockk()
        every { completion.choices() } returns listOf(choice)
        return completion
    }

    private fun mockEmptyChoices(): ChatCompletion {
        val completion: ChatCompletion = mockk()
        every { completion.choices() } returns emptyList()
        return completion
    }

    private fun mockToolCallResponse(
        name: String,
        arguments: String,
        id: String,
    ): ChatCompletion {
        val function =
            ChatCompletionMessageFunctionToolCall.Function.builder()
                .name(name)
                .arguments(arguments)
                .build()
        val functionCall =
            ChatCompletionMessageFunctionToolCall.builder()
                .id(id)
                .function(function)
                .build()
        val message: ChatCompletionMessage = mockk()
        every { message.content() } returns Optional.empty()
        every { message.toolCalls() } returns
            Optional.of(listOf(ChatCompletionMessageToolCall.ofFunction(functionCall)))
        val choice: ChatCompletion.Choice = mockk()
        every { choice.message() } returns message
        val completion: ChatCompletion = mockk()
        every { completion.choices() } returns listOf(choice)
        return completion
    }

    private fun stubCreate(completion: ChatCompletion) {
        every { completionService.create(any<ChatCompletionCreateParams>()) } returns completion
    }

    private fun stubCreateThrows(exception: Exception) {
        every { completionService.create(any<ChatCompletionCreateParams>()) } throws exception
    }

    // --- Input handling tests ---

    @Test
    fun `exit command terminates loop without API call`() {
        withInput("\\exit")
        agentLoop(client)
        verify(exactly = 0) { completionService.create(any<ChatCompletionCreateParams>()) }
    }

    @Test
    fun `EOF terminates loop`() {
        System.setIn(ByteArrayInputStream(ByteArray(0)))
        agentLoop(client)
        verify(exactly = 0) { completionService.create(any<ChatCompletionCreateParams>()) }
    }

    @Test
    fun `blank input is skipped without API call`() {
        withInput("", "   ", "\\exit")
        agentLoop(client)
        verify(exactly = 0) { completionService.create(any<ChatCompletionCreateParams>()) }
    }

    // --- Happy-path response tests ---

    @Test
    fun `successful response is printed to stdout`() {
        withInput("hello", "\\exit")
        stubCreate(mockResponse("Hello there!"))
        agentLoop(client)
        assertContains(capturedOutput(), "Hello there!")
    }

    @Test
    fun `no response fallback when choices list is empty`() {
        withInput("hello", "\\exit")
        stubCreate(mockEmptyChoices())
        agentLoop(client)
        assertContains(capturedOutput(), "(no response)")
    }

    @Test
    fun `empty content returns empty string not no-response`() {
        withInput("hello", "\\exit")
        stubCreate(mockResponse(""))
        agentLoop(client)
        assertFalse(
            capturedOutput().contains("(no response)"),
            "Empty content should not trigger '(no response)' fallback",
        )
    }

    @Test
    fun `multi-turn conversation prints both responses`() {
        withInput("first", "second", "\\exit")
        every { completionService.create(any<ChatCompletionCreateParams>()) } returnsMany
            listOf(
                mockResponse("Response 1"),
                mockResponse("Response 2"),
            )
        agentLoop(client)
        val output = capturedOutput()
        assertContains(output, "Response 1")
        assertContains(output, "Response 2")
    }

    @Test
    fun `tool calls are executed and sent back before the final response`() {
        withInput("use a tool", "\\exit")
        every { completionService.create(any<ChatCompletionCreateParams>()) } returnsMany
            listOf(
                mockToolCallResponse("unknown_tool", "{}", "call-123"),
                mockResponse("Done"),
            )

        agentLoop(client)

        assertContains(capturedOutput(), "[tool] unknown_tool({})")
        assertContains(capturedOutput(), "Done")
        val capturedParams = mutableListOf<ChatCompletionCreateParams>()
        verify(exactly = 2) { completionService.create(capture(capturedParams)) }
        assertContains(capturedParams[1].toString(), "call-123")
        assertContains(capturedParams[1].toString(), "Error: unknown tool 'unknown_tool'")
    }

    @Test
    fun `tool registry dispatches every supported tool`() {
        val root = Files.createTempDirectory("tool-registry")
        val source = root.resolve("source.txt")
        val written = root.resolve("written.txt")
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        // Serves static HTML content via local HTTP server
        server.createContext("/") { exchange ->
            val body = "<p>registry web result</p>".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()

        try {
            Files.writeString(source, "registry file result")
            val json = ObjectMapper()
            val calls =
                // Defines diverse tool execution parameters for registry testing
                listOf(
                    "run_bash" to mapOf("command" to "printf 'registry bash result'"),
                    "read_file" to mapOf("path" to source.toString()),
                    "glob_files" to mapOf("pattern" to "*.txt", "path" to root.toString()),
                    "grep" to mapOf("pattern" to "registry", "path" to root.toString(), "include" to "*.txt"),
                    "write_file" to mapOf("path" to written.toString(), "content" to "old value"),
                    "edit_file" to mapOf("path" to written.toString(), "old_string" to "old", "new_string" to "new"),
                    "webfetch" to mapOf("url" to "http://127.0.0.1:${server.address.port}/"),
                )
            val responses =
                calls.mapIndexed { index, (name, arguments) ->
                    mockToolCallResponse(name, json.writeValueAsString(arguments), "call-$index")
                } + mockResponse("All tools completed")
            withInput("use every tool", "\\exit")
            every { completionService.create(any<ChatCompletionCreateParams>()) } returnsMany responses

            agentLoop(client)

            val output = capturedOutput()
            assertContains(output, "registry bash result")
            assertContains(output, "registry file result")
            assertContains(output, "registry web result")
            assertEquals("new value", Files.readString(written))
            assertContains(output, "All tools completed")
            verify(exactly = responses.size) { completionService.create(any<ChatCompletionCreateParams>()) }
        } finally {
            server.stop(0)
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `malformed tool arguments are returned to the model and conversation recovers`() {
        assertToolFailureIsReturned(
            name = "read_file",
            arguments = "{not-json",
            id = "malformed-call",
        )
    }

    @Test
    fun `missing required tool argument is returned to the model and conversation recovers`() {
        assertToolFailureIsReturned(
            name = "read_file",
            arguments = "{}",
            id = "missing-argument-call",
        )
    }

    @Test
    fun `tool execution failure is returned to the model and conversation recovers`() {
        assertToolFailureIsReturned(
            name = "glob_files",
            arguments = """{"pattern":"["}""",
            id = "execution-failure-call",
        )
    }

    private fun assertToolFailureIsReturned(
        name: String,
        arguments: String,
        id: String,
    ) {
        withInput("use a tool", "\\exit")
        every { completionService.create(any<ChatCompletionCreateParams>()) } returnsMany
            listOf(
                mockToolCallResponse(name, arguments, id),
                mockResponse("Recovered"),
            )

        agentLoop(client)

        assertContains(capturedOutput(), "Recovered")
        val capturedParams = mutableListOf<ChatCompletionCreateParams>()
        verify(exactly = 2) { completionService.create(capture(capturedParams)) }
        assertContains(capturedParams[1].toString(), id)
        assertContains(capturedParams[1].toString(), "Error executing tool '$name':")
    }

    // --- Error-path tests ---

    @Test
    fun `ConnectException prints error message and exits`() {
        withInput("hello")
        stubCreateThrows(ConnectException("Connection refused"))
        val exitStatuses = mutableListOf<Int>()
        try {
            agentLoop(client) { status ->
                exitStatuses.add(status)
                throw ExitProcessException(status)
            }
        } catch (_: ExitProcessException) {
            // expected
        }
        assertContains(capturedOutput(), "Could not connect to the service")
        assertEquals(listOf(1), exitStatuses)
    }

    @Test
    fun `SocketTimeoutException prints timeout message and exits`() {
        withInput("hello")
        stubCreateThrows(SocketTimeoutException("timeout"))
        val exitStatuses = mutableListOf<Int>()
        try {
            agentLoop(client) { status ->
                exitStatuses.add(status)
                throw ExitProcessException(status)
            }
        } catch (_: ExitProcessException) {
            // expected
        }
        assertContains(capturedOutput(), "Request timed out")
        assertEquals(listOf(1), exitStatuses)
    }

    @Test
    fun `generic exception prints error and loop continues`() {
        withInput("hello", "\\exit")
        stubCreateThrows(RuntimeException("Something broke"))
        agentLoop(client)
        assertContains(capturedOutput(), "An unexpected error occurred: Something broke")
    }

    @Test
    fun `generic exception removes failed message from history`() {
        withInput("bad-input", "good-input", "\\exit")
        every { completionService.create(any<ChatCompletionCreateParams>()) } throws
            RuntimeException(
                "fail",
            ) andThen mockResponse("ok")

        agentLoop(client)

        val capturedParams = mutableListOf<ChatCompletionCreateParams>()
        verify(exactly = 2) { completionService.create(capture(capturedParams)) }

        val secondParamsString = capturedParams[1].toString()
        assertFalse(
            secondParamsString.contains("bad-input"),
            "Failed message should have been removed from history",
        )
        assertContains(secondParamsString, "good-input")
    }

    @Test
    fun `main exits before connecting when given the exit command`() {
        withInput("\\exit")

        main()

        assertContains(capturedOutput(), "> ")
    }
}

private class ExitProcessException(
    status: Int,
) : SecurityException("exitProcess($status) called")
