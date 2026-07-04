package com.example.aiagent

import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.OpenAI
import com.aallam.openai.client.OpenAIConfig
import com.aallam.openai.client.OpenAIHost
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse

// Integration test: requires LM Studio to be running at http://localhost:1234
class ServiceRunningTest {
    @Test
    fun `service is running and returns a response`() = runBlocking {
        val openAI = OpenAI(
            OpenAIConfig(
                token = "lm-studio",
                host = OpenAIHost(baseUrl = "http://localhost:1234/v1/"),
            )
        )
        val request = ChatCompletionRequest(
            model = ModelId("wizardlm-1.0-uncensored-codellama-34b"),
            messages = listOf(
                ChatMessage(role = ChatRole.System, content = "You are a helpful assistant!"),
                ChatMessage(role = ChatRole.User, content = "Say hello."),
            ),
        )
        val completion = openAI.chatCompletion(request)
        val content = completion.choices.first().message.content
        assertFalse(content.isNullOrBlank(), "Expected a non-blank response from the service")
    }
}
