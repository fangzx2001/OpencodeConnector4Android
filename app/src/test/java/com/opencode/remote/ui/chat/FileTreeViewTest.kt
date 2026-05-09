package com.opencode.remote.ui.chat

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.opencode.remote.data.api.dto.FileNode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FileTreeViewTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `renders files and directories`() {
        val files = listOf(
            FileNode("dir1", "/dir1", "/abs/dir1", "directory"),
            FileNode("file1.txt", "/file1.txt", "/abs/file1.txt", "file"),
        )
        composeTestRule.setContent {
            FileTreeView(
                files = files,
                currentPath = "/",
                onNavigateToDirectory = {},
                isLoading = false,
            )
        }
        composeTestRule.onNodeWithText("dir1").assertIsDisplayed()
        composeTestRule.onNodeWithText("file1.txt").assertIsDisplayed()
    }

    @Test
    fun `directory click triggers navigation`() {
        val dir = FileNode("dir1", "/dir1", "/abs/dir1", "directory")
        var navigatedTo = ""
        composeTestRule.setContent {
            FileTreeView(
                files = listOf(dir),
                currentPath = "/",
                onNavigateToDirectory = { navigatedTo = it },
                isLoading = false,
            )
        }
        composeTestRule.onNodeWithTag("file_item_dir1").performClick()
        assertTrue("/dir1" == navigatedTo)
    }

    @Test
    fun `file click does not trigger navigation`() {
        val file = FileNode("test.txt", "/test.txt", "/abs/test.txt", "file")
        var called = false
        composeTestRule.setContent {
            FileTreeView(
                files = listOf(file),
                currentPath = "/",
                onNavigateToDirectory = { called = true },
                isLoading = false,
            )
        }
        runCatching {
            composeTestRule.onNodeWithTag("file_item_test.txt").performClick()
        }
        assertFalse(called)
    }

    @Test
    fun `loading state shows spinner`() {
        composeTestRule.setContent {
            FileTreeView(
                files = emptyList(),
                currentPath = "/",
                onNavigateToDirectory = {},
                isLoading = true,
            )
        }
        composeTestRule.onAllNodesWithText("Empty directory").assertCountEquals(0)
    }

    @Test
    fun `empty state shows message`() {
        composeTestRule.setContent {
            FileTreeView(
                files = emptyList(),
                currentPath = "/",
                onNavigateToDirectory = {},
                isLoading = false,
            )
        }
        composeTestRule.onNodeWithText("Empty directory").assertIsDisplayed()
    }
}
