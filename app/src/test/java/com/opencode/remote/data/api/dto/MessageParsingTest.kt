package com.opencode.remote.data.api.dto

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessageParsingTest {

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    @Test
    fun parsesAssistantTopLevelProviderAndModelIds() {
        val message = json.decodeFromString<MessageInfo>(
            """
            {
              "info": {
                "id": "msg_1",
                "role": "assistant",
                "providerID": "openai",
                "modelID": "gpt-4.1",
                "tokens": {
                  "input": 1200,
                  "output": 300,
                  "reasoning": 100,
                  "cache": {
                    "read": 400,
                    "write": 50
                  }
                }
              },
              "parts": []
            }
            """.trimIndent(),
        )

        assertEquals("openai", message.info.resolvedProviderID)
        assertEquals("gpt-4.1", message.info.resolvedModelID)
        assertNull(message.info.model)
        assertEquals(1200, message.info.tokens?.input)
        assertEquals(300, message.info.tokens?.output)
        assertEquals(100, message.info.tokens?.reasoning)
        assertEquals(400, message.info.tokens?.cache?.read)
        assertEquals(50, message.info.tokens?.cache?.write)
    }

    @Test
    fun parsesNestedModelAliasFields() {
        val message = json.decodeFromString<MessageInfo>(
            """
            {
              "info": {
                "id": "msg_2",
                "role": "assistant",
                "model": {
                  "providerId": "anthropic",
                  "modelId": "claude-sonnet-4"
                }
              },
              "parts": []
            }
            """.trimIndent(),
        )

        assertEquals("anthropic", message.info.resolvedProviderID)
        assertEquals("claude-sonnet-4", message.info.resolvedModelID)
    }
}
