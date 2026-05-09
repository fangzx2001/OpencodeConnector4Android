package com.opencode.remote.data.api.dto

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for side-panel related DTO deserialization.
 *
 * These are pure JVM tests — no Android dependency required.
 * Uses the same Json configuration as the app: ignoreUnknownKeys = true.
 */
class SidePanelDtoTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ─── FileNode ───────────────────────────────────────────────────────

    @Test
    fun `fileNode with all fields deserializes correctly`() {
        val input = """{
            "name": "main.kt",
            "path": "/src/main.kt",
            "absolute": "/project/src/main.kt",
            "type": "file",
            "ignored": true
        }"""
        val node = json.decodeFromString<FileNode>(input)
        assertEquals("main.kt", node.name)
        assertEquals("/src/main.kt", node.path)
        assertEquals("/project/src/main.kt", node.absolute)
        assertEquals("file", node.type)
        assertTrue(node.ignored)
    }

    @Test
    fun `fileNode with type directory deserializes correctly`() {
        val input = """{
            "name": "src",
            "path": "/src",
            "absolute": "/project/src",
            "type": "directory"
        }"""
        val node = json.decodeFromString<FileNode>(input)
        assertEquals("src", node.name)
        assertEquals("directory", node.type)
    }

    @Test
    fun `fileNode ignores unknown fields`() {
        val input = """{
            "name": "test.txt",
            "path": "/test.txt",
            "absolute": "/project/test.txt",
            "type": "file",
            "unknown_field": "should_be_ignored",
            "extra": 42
        }"""
        val node = json.decodeFromString<FileNode>(input)
        assertEquals("test.txt", node.name)
    }

    @Test
    fun `fileNode with minimal fields uses defaults`() {
        val input = """{
            "name": "readme.md",
            "path": "/readme.md",
            "absolute": "/project/readme.md",
            "type": "file"
        }"""
        val node = json.decodeFromString<FileNode>(input)
        assertEquals("readme.md", node.name)
        assertEquals("file", node.type)
        assertEquals(false, node.ignored) // default
    }

    // ─── AgentInfo ──────────────────────────────────────────────────────

    @Test
    fun `agentInfo without model deserializes`() {
        val input = """{
            "name": "primary-agent"
        }"""
        val agent = json.decodeFromString<AgentInfo>(input)
        assertEquals("primary-agent", agent.name)
        assertNull(agent.model)
    }

    @Test
    fun `agentInfo with model present deserializes`() {
        val input = """{
            "name": "gpt-agent",
            "description": "GPT-4o agent",
            "mode": "primary",
            "model": {
                "modelID": "gpt-4o",
                "providerID": "openai"
            }
        }"""
        val agent = json.decodeFromString<AgentInfo>(input)
        assertEquals("gpt-agent", agent.name)
        assertEquals("primary", agent.mode)
        assertEquals("GPT-4o agent", agent.description)
        assertNotNull(agent.model)
        assertEquals("gpt-4o", agent.model!!.modelID)
        assertEquals("openai", agent.model!!.providerID)
        assertEquals(false, agent.hidden)
    }

    @Test
    fun `agentInfo with hidden true deserializes`() {
        val input = """{
            "name": "hidden-agent",
            "mode": "subagent",
            "hidden": true
        }"""
        val agent = json.decodeFromString<AgentInfo>(input)
        assertEquals("hidden-agent", agent.name)
        assertEquals("subagent", agent.mode)
        assertTrue(agent.hidden)
    }

    @Test
    fun `agentInfo with model backwards compat empty object`() {
        val input = """{
            "name": "test-agent",
            "model": {}
        }"""
        val agent = json.decodeFromString<AgentInfo>(input)
        assertEquals("test-agent", agent.name)
        assertNotNull(agent.model)
        assertNull(agent.model!!.modelID)
        assertNull(agent.model!!.providerID)
    }

    // ─── AgentModel ─────────────────────────────────────────────────────

    @Test
    fun `agentModel with serialName fields deserializes`() {
        val input = """{"modelID": "gpt-4", "providerID": "openai"}"""
        val model = json.decodeFromString<AgentModel>(input)
        assertEquals("gpt-4", model.modelID)
        assertEquals("openai", model.providerID)
    }

    @Test
    fun `agentModel with partial fields deserializes`() {
        val input = """{"modelID": "claude-3"}"""
        val model = json.decodeFromString<AgentModel>(input)
        assertEquals("claude-3", model.modelID)
        assertNull(model.providerID)
    }

    // ─── MessageTokens ──────────────────────────────────────────────────

    @Test
    fun `messageTokens without cache deserializes`() {
        val input = """{
            "total": 100,
            "input": 50,
            "output": 50
        }"""
        val tokens = json.decodeFromString<MessageTokens>(input)
        assertEquals(100, tokens.total)
        assertEquals(50, tokens.input)
        assertEquals(50, tokens.output)
        assertNull(tokens.cache)
    }

    @Test
    fun `messageTokens with cache deserializes`() {
        val input = """{
            "total": 200,
            "input": 100,
            "output": 80,
            "reasoning": 20,
            "cache": {
                "read": 50,
                "write": 30
            }
        }"""
        val tokens = json.decodeFromString<MessageTokens>(input)
        assertEquals(200, tokens.total)
        assertEquals(100, tokens.input)
        assertEquals(80, tokens.output)
        assertEquals(20, tokens.reasoning)
        assertNotNull(tokens.cache)
        assertEquals(50, tokens.cache!!.read)
        assertEquals(30, tokens.cache!!.write)
    }

    @Test
    fun `messageTokens with empty cache deserializes`() {
        val input = """{
            "total": 150,
            "cache": {}
        }"""
        val tokens = json.decodeFromString<MessageTokens>(input)
        assertEquals(150, tokens.total)
        assertNotNull(tokens.cache)
        assertNull(tokens.cache!!.read)
        assertNull(tokens.cache!!.write)
    }

    // ─── CacheTokens ────────────────────────────────────────────────────

    @Test
    fun `cacheTokens deserializes correctly`() {
        val input = """{"read": 100, "write": 50}"""
        val cache = json.decodeFromString<CacheTokens>(input)
        assertEquals(100, cache.read)
        assertEquals(50, cache.write)
    }

    @Test
    fun `cacheTokens with null fields deserializes`() {
        val input = """{}"""
        val cache = json.decodeFromString<CacheTokens>(input)
        assertNull(cache.read)
        assertNull(cache.write)
    }

    // ─── ProviderList ───────────────────────────────────────────────────

    @Test
    fun `providerList with nested providers and models deserializes`() {
        val input = """{
            "providers": [
                {
                    "id": "openai",
                    "name": "OpenAI",
                    "models": [
                        {"id": "gpt-4o", "name": "GPT-4o"},
                        {"id": "gpt-4o-mini", "name": "GPT-4o Mini"}
                    ]
                },
                {
                    "id": "anthropic",
                    "name": "Anthropic",
                    "models": [
                        {"id": "claude-3-opus", "name": "Claude 3 Opus"}
                    ]
                }
            ]
        }"""
        val list = json.decodeFromString<ProviderList>(input)
        assertEquals(2, list.providers.size)

        // First provider
        assertEquals("openai", list.providers[0].id)
        assertEquals("OpenAI", list.providers[0].name)
        assertEquals(2, list.providers[0].models.size)
        assertEquals("gpt-4o", list.providers[0].models[0].id)
        assertEquals("GPT-4o", list.providers[0].models[0].name)
        assertEquals("gpt-4o-mini", list.providers[0].models[1].id)
        assertEquals("GPT-4o Mini", list.providers[0].models[1].name)

        // Second provider
        assertEquals("anthropic", list.providers[1].id)
        assertEquals("Anthropic", list.providers[1].name)
        assertEquals(1, list.providers[1].models.size)
        assertEquals("claude-3-opus", list.providers[1].models[0].id)
        assertEquals("Claude 3 Opus", list.providers[1].models[0].name)
    }

    @Test
    fun `providerList with empty providers deserializes`() {
        val input = """{"providers": []}"""
        val list = json.decodeFromString<ProviderList>(input)
        assertTrue(list.providers.isEmpty())
    }

    @Test
    fun `providerList defaults to empty list when missing`() {
        val input = """{}"""
        val list = json.decodeFromString<ProviderList>(input)
        assertTrue(list.providers.isEmpty())
    }

    // ─── ModelInfo ──────────────────────────────────────────────────────

    @Test
    fun `modelInfo with all fields deserializes`() {
        val input = """{"id": "gpt-4", "name": "GPT-4"}"""
        val model = json.decodeFromString<ModelInfo>(input)
        assertEquals("gpt-4", model.id)
        assertEquals("GPT-4", model.name)
    }

    @Test
    fun `modelInfo with only id deserializes`() {
        val input = """{"id": "gpt-4o"}"""
        val model = json.decodeFromString<ModelInfo>(input)
        assertEquals("gpt-4o", model.id)
        assertNull(model.name)
    }

    @Test
    fun `modelInfo with unknown fields is ignored`() {
        val input = """{"id": "gpt-4", "name": "GPT-4", "extra": "ignored"}"""
        val model = json.decodeFromString<ModelInfo>(input)
        assertEquals("gpt-4", model.id)
        assertEquals("GPT-4", model.name)
    }

    // ─── ProviderInfo ───────────────────────────────────────────────────

    @Test
    fun `providerInfo with minimal fields deserializes`() {
        val input = """{"id": "ollama"}"""
        val provider = json.decodeFromString<ProviderInfo>(input)
        assertEquals("ollama", provider.id)
        assertNull(provider.name)
        assertTrue(provider.models.isEmpty())
    }

    @Test
    fun `providerInfo without models defaults to empty`() {
        val input = """{"id": "ollama", "name": "Ollama"}"""
        val provider = json.decodeFromString<ProviderInfo>(input)
        assertEquals("ollama", provider.id)
        assertEquals("Ollama", provider.name)
        assertTrue(provider.models.isEmpty())
    }
}
