package com.opencode.remote.ui.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.opencode.remote.data.api.dto.ModelInfo
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ModelSelectorTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `shows default text when no model selected`() {
        composeTestRule.setContent {
            ModelSelector(
                selectedModel = null,
                availableModels = emptyList(),
                onSelectModel = {},
                isLoading = false,
            )
        }
        composeTestRule.onNodeWithText("Default").assertIsDisplayed()
    }

    @Test
    fun `shows selected model name`() {
        composeTestRule.setContent {
            ModelSelector(
                selectedModel = ModelInfo("model-1", "GPT-4"),
                availableModels = emptyList(),
                onSelectModel = {},
                isLoading = false,
            )
        }
        composeTestRule.onNodeWithText("GPT-4").assertIsDisplayed()
    }

    @Test
    fun `clicking opens dropdown and shows models`() {
        val models = listOf(
            ModelInfo("model-1", "GPT-4"),
            ModelInfo("model-2", "Claude 3"),
        )
        composeTestRule.setContent {
            ModelSelector(
                selectedModel = null,
                availableModels = models,
                onSelectModel = {},
                isLoading = false,
            )
        }
        composeTestRule.onNodeWithText("Default").performClick()
        composeTestRule.onNodeWithText("GPT-4").assertIsDisplayed()
        composeTestRule.onNodeWithText("Claude 3").assertIsDisplayed()
    }

    @Test
    fun `selecting model triggers callback`() {
        val models = listOf(
            ModelInfo("model-1", "GPT-4"),
            ModelInfo("model-2", "Claude 3"),
        )
        var selected: ModelInfo? = null
        composeTestRule.setContent {
            ModelSelector(
                selectedModel = null,
                availableModels = models,
                onSelectModel = { selected = it },
                isLoading = false,
            )
        }
        composeTestRule.onNodeWithText("Default").performClick()
        composeTestRule.onNodeWithText("Claude 3").performClick()
        assertTrue(selected?.id == "model-2" && selected?.name == "Claude 3")
    }
}
