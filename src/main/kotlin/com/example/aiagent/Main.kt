package com.example.aiagent

import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.http.Timeout
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.OpenAI
import com.aallam.openai.client.OpenAIConfig
import com.aallam.openai.client.OpenAIHost
import io.ktor.client.plugins.*
import java.net.ConnectException
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds

suspend fun main() {
    val openAI = OpenAI(
        OpenAIConfig(
            token = "lm-studio",
            host = OpenAIHost(baseUrl = "http://localhost:1234/v1/"),
            timeout = Timeout(connect = 5.seconds, socket = 10.seconds, request = 30.seconds),
        )
    )

    val request = ChatCompletionRequest(
        model = ModelId("local-model"),
        messages = listOf(
            ChatMessage(role = ChatRole.System, content = "You are a helpful assistant!"),
            ChatMessage(role = ChatRole.User, content = "Hello!"),
        ),
    )

    try {
        val completion = openAI.chatCompletion(request)
        println(completion.choices.first().message.content)
    } catch (e: ConnectException) {
        println("Could not connect to the service at http://localhost:1234. Is LM Studio running?")
        exitProcess(1)
    } catch (e: HttpRequestTimeoutException) {
        println("Request timed out. The service at http://localhost:1234 did not respond in time.")
        exitProcess(1)
    } catch (e: java.net.SocketTimeoutException) {
        println("Request timed out. The service at http://localhost:1234 did not respond in time.")
        exitProcess(1)
    } catch (e: Exception) {
        println("An unexpected error occurred: ${e.message}")
        exitProcess(1)
    }
}
