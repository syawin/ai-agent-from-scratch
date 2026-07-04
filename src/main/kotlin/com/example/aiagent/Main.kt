package com.example.aiagent

import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.OpenAI
import com.aallam.openai.client.OpenAIConfig
import com.aallam.openai.client.OpenAIHost

suspend fun main() {
    val openAI = OpenAI(
        OpenAIConfig(
            token = "lm-studio",
            host = OpenAIHost(baseUrl = "http://localhost:1234/v1"),
        )
    )

    val request = ChatCompletionRequest(
        model = ModelId("local-model"),
        messages = listOf(
            ChatMessage(role = ChatRole.System, content = "You are a helpful assistant!"),
            ChatMessage(role = ChatRole.User, content = "Hello!"),
        ),
    )

    val completion = openAI.chatCompletion(request)
    println(completion.choices.first().message.content)
}
