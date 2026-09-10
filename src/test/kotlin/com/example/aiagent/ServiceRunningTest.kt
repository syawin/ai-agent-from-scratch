package com.example.aiagent

import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.models.responses.EasyInputMessage
import com.openai.models.responses.ResponseCreateParams
import com.openai.models.responses.ResponseInputItem
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
            ResponseCreateParams.builder()
                .instructions("You are a helpful assistant!")
                .inputOfResponse(
                    listOf(
                        ResponseInputItem.ofEasyInputMessage(
                            EasyInputMessage.builder().role(EasyInputMessage.Role.USER).content("Say hello.").build(),
                        ),
                    ),
                ).model("local-model")
                .build()

        val response = client.responses().create(params)
        val content =
            response
                .output()
                .filter { it.isMessage() }
                .flatMap { it.asMessage().content() }
                .filter { it.isOutputText() }
                .joinToString("") { it.asOutputText().text() }
        assertFalse(content.isBlank(), "Expected a non-blank response from the service")
    }
}
