package com.example.aiagent

import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.models.chat.completions.ChatCompletionCreateParams
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertFalse

// Integration test: requires LM Studio to be running at http://localhost:1234
@Tag("integration")
class ServiceRunningTest {
    @Test
    fun `service is running and returns a response`() {
        val client: OpenAIClient =
            OpenAIOkHttpClient
                .builder()
                .apiKey("lm-studio")
                .baseUrl("http://localhost:1234/v1/")
                .build()

        val params =
            ChatCompletionCreateParams
                .builder()
                .addSystemMessage("You are a helpful assistant!")
                .addUserMessage("Say hello.")
                .model("local-model")
                .build()

        val completion = client.chat().completions().create(params)
        val content =
            completion
                .choices()
                .first()
                .message()
                .content()
                .orElse("")
        assertFalse(content.isBlank(), "Expected a non-blank response from the service")
    }
}
