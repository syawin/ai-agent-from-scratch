package com.example.aiagent

import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.models.chat.completions.ChatCompletionCreateParams
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.time.Duration
import kotlin.system.exitProcess

fun main() {
    val client: OpenAIClient = OpenAIOkHttpClient.builder()
        .apiKey("lm-studio")
        .baseUrl("http://localhost:1234/v1/")
        .timeout(Duration.ofSeconds(30))
        .build()

    val params = ChatCompletionCreateParams.builder()
        .addSystemMessage("You are a helpful assistant!")
        .addUserMessage("Hello!")
        .model("local-model")
        .build()

    try {
        val completion = client.chat().completions().create(params)
        println(completion.choices().first().message().content().orElse(""))
    } catch (e: ConnectException) {
        println("Could not connect to the service at http://localhost:1234. Is LM Studio running?")
        exitProcess(1)
    } catch (e: SocketTimeoutException) {
        println("Request timed out. The service at http://localhost:1234 did not respond in time.")
        exitProcess(1)
    } catch (e: Exception) {
        println("An unexpected error occurred: ${e.message}")
        exitProcess(1)
    }
}
