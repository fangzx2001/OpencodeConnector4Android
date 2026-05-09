package com.opencode.remote.ui.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ContextUsageDisplayTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `displays usage text`() {
        composeTestRule.setContent {
            ContextUsageDisplay(usageK = "128K")
        }
        composeTestRule.onNodeWithText("128K").assertIsDisplayed()
    }

    @Test
    fun `displays zero usage`() {
        composeTestRule.setContent {
            ContextUsageDisplay(usageK = "0K")
        }
        composeTestRule.onNodeWithText("0K").assertIsDisplayed()
    }
}
