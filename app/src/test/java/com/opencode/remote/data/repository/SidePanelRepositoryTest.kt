package com.opencode.remote.data.repository

import android.content.Context
import com.opencode.remote.data.api.OConnectorApiClient
import com.opencode.remote.data.api.OConnectorSseClient
import com.opencode.remote.data.api.dto.*
import com.opencode.remote.service.SseForegroundService
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for OConnectorRepositoryImpl side-panel related methods.
 *
 * Uses mockk for dependency mocking and reflection to bypass Android
 * framework dependency in connect().
 */
class SidePanelRepositoryTest {

    private lateinit var apiClient: OConnectorApiClient
    private lateinit var sseClient: OConnectorSseClient
    private lateinit var context: Context
    private lateinit var repository: OConnectorRepositoryImpl

    @Before
    @Suppress("unchecked")
    fun setUp() {
        apiClient = mockk(relaxed = true)
        sseClient = mockk(relaxed = true)
        context = mockk(relaxed = true)
        repository = OConnectorRepositoryImpl(apiClient, sseClient, context)

        // Set connected=true via reflection to bypass connect()
        // (which requires Android framework classes for SseForegroundService)
        val connectedField = OConnectorRepositoryImpl::class.java.getDeclaredField("connected")
        connectedField.isAccessible = true
        connectedField.setBoolean(repository, true)
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    // ─── listFiles ──────────────────────────────────────────────────────

    @Test
    fun `listFiles delegates to apiClient`() = runTest {
        val expectedFiles = listOf(
            FileNode("file1.txt", "/file1.txt", "/project/file1.txt", "file"),
            FileNode("dir1", "/dir1", "/project/dir1", "directory"),
        )
        coEvery { apiClient.listFiles("/root", null) } returns expectedFiles

        val result = repository.listFiles("/root")

        assertEquals(expectedFiles, result)
        coVerify(exactly = 1) { apiClient.listFiles("/root", null) }
    }

    @Test
    fun `listFiles with directory delegates to apiClient`() = runTest {
        val expectedFiles = listOf(
            FileNode("f.txt", "/f.txt", "/p/f.txt", "file"),
        )
        coEvery { apiClient.listFiles("/root", "/myproject") } returns expectedFiles

        val result = repository.listFiles("/root", "/myproject")

        assertEquals(expectedFiles, result)
        coVerify(exactly = 1) { apiClient.listFiles("/root", "/myproject") }
    }

    @Test
    fun `listFiles returns empty list when directory empty`() = runTest {
        coEvery { apiClient.listFiles("/empty", null) } returns emptyList()

        val result = repository.listFiles("/empty")

        assertTrue(result.isEmpty())
        coVerify(exactly = 1) { apiClient.listFiles("/empty", null) }
    }

    // ─── listProviders ──────────────────────────────────────────────────

    @Test
    fun `listProviders caches models on first call and returns empty on second`() = runTest {
        val providerList = ProviderList(
            providers = listOf(
                ProviderInfo("openai", "OpenAI", models = listOf(
                    ModelInfo("gpt-4o", "GPT-4o"),
                    ModelInfo("gpt-4o-mini", "GPT-4o Mini"),
                )),
                ProviderInfo("anthropic", "Anthropic", models = listOf(
                    ModelInfo("claude-3-opus", "Claude 3 Opus"),
                )),
            ),
        )
        coEvery { apiClient.listProviders() } returns providerList

        // First call: fetches from apiClient
        val result1 = repository.listProviders()
        assertEquals(2, result1.providers.size)
        coVerify(exactly = 1) { apiClient.listProviders() }

        // Second call: cachedModels is set → returns empty ProviderList
        val result2 = repository.listProviders()
        assertTrue(result2.providers.isEmpty())
        // apiClient.listProviders still called only once
        coVerify(exactly = 1) { apiClient.listProviders() }

        // Third call: also empty (still cached)
        val result3 = repository.listProviders()
        assertTrue(result3.providers.isEmpty())
        coVerify(exactly = 1) { apiClient.listProviders() }
    }

    @Test
    fun `listProviders with empty provider list still caches`() = runTest {
        val emptyList = ProviderList()
        coEvery { apiClient.listProviders() } returns emptyList

        val result1 = repository.listProviders()
        assertTrue(result1.providers.isEmpty())
        coVerify(exactly = 1) { apiClient.listProviders() }

        // Second call should also return empty (cached)
        val result2 = repository.listProviders()
        assertTrue(result2.providers.isEmpty())
        coVerify(exactly = 1) { apiClient.listProviders() }
    }

    // ─── getCachedModels ────────────────────────────────────────────────

    @Test
    fun `getCachedModels returns empty before any listProviders call`() {
        assertTrue(repository.getCachedModels().isEmpty())
    }

    @Test
    fun `getCachedModels returns cached models after listProviders`() = runTest {
        val models = listOf(
            ModelInfo("gpt-4o", "GPT-4o"),
            ModelInfo("claude-3-opus", "Claude 3 Opus"),
        )
        val providerList = ProviderList(
            providers = listOf(
                ProviderInfo("openai", "OpenAI", models = listOf(models[0])),
                ProviderInfo("anthropic", "Anthropic", models = listOf(models[1])),
            ),
        )
        coEvery { apiClient.listProviders() } returns providerList

        repository.listProviders()

        val cached = repository.getCachedModels()
        assertEquals(2, cached.size)
        assertTrue(cached.any { it.id == "gpt-4o" })
        assertTrue(cached.any { it.id == "claude-3-opus" })
    }

    @Test
    fun `getCachedModels returns empty models after empty providerList`() = runTest {
        coEvery { apiClient.listProviders() } returns ProviderList()

        repository.listProviders()

        assertTrue(repository.getCachedModels().isEmpty())
    }

    // ─── listAgents ─────────────────────────────────────────────────────

    @Test
    fun `listAgents filters out subagent mode agents`() = runTest {
        val agents = listOf(
            AgentInfo("primary-agent"),
            AgentInfo("sub-agent", mode = "subagent"),
            AgentInfo("another-primary", mode = "primary"),
        )
        coEvery { apiClient.listAgents() } returns agents

        val result = repository.listAgents()

        assertEquals(2, result.size)
        assertEquals("primary-agent", result[0].name)
        assertEquals("another-primary", result[1].name)
    }

    @Test
    fun `listAgents filters out hidden agents`() = runTest {
        val agents = listOf(
            AgentInfo("visible-agent"),
            AgentInfo("hidden-agent", hidden = true),
        )
        coEvery { apiClient.listAgents() } returns agents

        val result = repository.listAgents()

        assertEquals(1, result.size)
        assertEquals("visible-agent", result[0].name)
    }

    @Test
    fun `listAgents filters subagent and hidden`() = runTest {
        val agents = listOf(
            AgentInfo("primary-agent"),
            AgentInfo("sub-agent", mode = "subagent"),
            AgentInfo("hidden-agent", hidden = true),
            AgentInfo("sub-hidden", mode = "subagent", hidden = true),
            AgentInfo("another-primary", mode = "primary"),
        )
        coEvery { apiClient.listAgents() } returns agents

        val result = repository.listAgents()

        assertEquals(2, result.size)
        assertEquals("primary-agent", result[0].name)
        assertEquals("another-primary", result[1].name)
    }

    @Test
    fun `listAgents caches results on first call`() = runTest {
        val agents = listOf(AgentInfo("primary-agent"))
        coEvery { apiClient.listAgents() } returns agents

        repository.listAgents()
        repository.listAgents()
        repository.listAgents()

        // Should only call apiClient once
        coVerify(exactly = 1) { apiClient.listAgents() }
    }

    @Test
    fun `listAgents with all filtered out returns empty`() = runTest {
        val agents = listOf(
            AgentInfo("sub1", mode = "subagent"),
            AgentInfo("sub2", mode = "subagent"),
        )
        coEvery { apiClient.listAgents() } returns agents

        val result = repository.listAgents()

        assertTrue(result.isEmpty())
    }

    @Test
    fun `listAgents with no agents returns empty`() = runTest {
        coEvery { apiClient.listAgents() } returns emptyList()

        val result = repository.listAgents()

        assertTrue(result.isEmpty())
    }

    // ─── getCachedAgents ────────────────────────────────────────────────

    @Test
    fun `getCachedAgents returns empty before listAgents`() {
        assertTrue(repository.getCachedAgents().isEmpty())
    }

    @Test
    fun `getCachedAgents returns cached filtered agents after listAgents`() = runTest {
        val agents = listOf(
            AgentInfo("primary-agent"),
            AgentInfo("sub-agent", mode = "subagent"),
        )
        coEvery { apiClient.listAgents() } returns agents

        repository.listAgents()

        val cached = repository.getCachedAgents()
        assertEquals(1, cached.size)
        assertEquals("primary-agent", cached[0].name)
    }

    // ─── disconnect ─────────────────────────────────────────────────────

    @Test
    fun `disconnect clears cachedModels`() {
        // Use reflection to simulate cached state (since we can't call listProviders
        // which requires coEvery + runTest)
        val cachedModelsField = OConnectorRepositoryImpl::class.java
            .getDeclaredField("cachedModels")
        cachedModelsField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        cachedModelsField.set(repository, listOf(ModelInfo("gpt-4", "GPT-4")))

        assertFalse(repository.getCachedModels().isEmpty())

        // Mock SseForegroundService to prevent Android API calls during disconnect
        mockkObject(SseForegroundService)
        every { SseForegroundService.stop(any()) } returns Unit

        repository.disconnect()

        assertTrue(repository.getCachedModels().isEmpty())
    }

    @Test
    fun `disconnect clears cachedAgents`() {
        val cachedAgentsField = OConnectorRepositoryImpl::class.java
            .getDeclaredField("cachedAgents")
        cachedAgentsField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        cachedAgentsField.set(repository, listOf(AgentInfo("primary-agent")))

        assertFalse(repository.getCachedAgents().isEmpty())

        mockkObject(SseForegroundService)
        every { SseForegroundService.stop(any()) } returns Unit

        repository.disconnect()

        assertTrue(repository.getCachedAgents().isEmpty())
    }

    @Test
    fun `disconnect sets connected to false`() {
        mockkObject(SseForegroundService)
        every { SseForegroundService.stop(any()) } returns Unit

        assertTrue(repository.isConnected)

        repository.disconnect()

        assertFalse(repository.isConnected)
    }
}
