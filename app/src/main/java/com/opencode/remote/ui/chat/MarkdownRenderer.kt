package com.opencode.remote.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import dev.jeziellago.compose.markdowntext.MarkdownText as LibMarkdownText

@Composable
internal fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    val style = if (color != Color.Unspecified) {
        TextStyle(color = color)
    } else {
        TextStyle.Default
    }
    LibMarkdownText(
        markdown = text,
        modifier = modifier,
        style = style,
    )
}
