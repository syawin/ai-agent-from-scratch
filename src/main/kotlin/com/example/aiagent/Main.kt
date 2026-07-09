package com.example.aiagent

import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.models.chat.completions.ChatCompletionCreateParams
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.time.Duration
import kotlin.system.exitProcess

data class Message(
    val role: String,
    val content: String,
)

fun agentLoop(
    client: OpenAIClient,
    exit: (Int) -> Nothing = ::exitProcess,
) {
    val history = mutableListOf(Message("system", "You are a helpful assistant!"))

    while (true) {
        print("> ")
        val input = readlnOrNull() ?: break
        if (input.trim() == "\\exit") break
        if (input.isBlank()) continue

        history.add(Message("user", input))

        val paramsBuilder = ChatCompletionCreateParams.builder().model("local-model")
        for (msg in history) {
            when (msg.role) {
                "system" -> paramsBuilder.addSystemMessage(msg.content)
                "user" -> paramsBuilder.addUserMessage(msg.content)
                "assistant" -> paramsBuilder.addAssistantMessage(msg.content)
            }
        }

        try {
            val completion = client.chat().completions().create(paramsBuilder.build())
            val response =
                completion
                    .choices()
                    .firstOrNull()
                    ?.message()
                    ?.content()
                    ?.orElse("")
                    ?: "(no response)"
            println(response)
            history.add(Message("assistant", response))
        } catch (e: ConnectException) {
            println("Could not connect to the service at http://localhost:1234. Is LM Studio running? (${e.message})")
            exit(1)
        } catch (e: SocketTimeoutException) {
            println("Request timed out. The service at http://localhost:1234 did not respond in time. (${e.message})")
            exit(1)
        } catch (e: Exception) {
            println("An unexpected error occurred: ${e.message}")
            history.removeLastOrNull()
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
