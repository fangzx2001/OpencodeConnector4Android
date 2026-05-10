package com.opencode.remote.ui.chat

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf

internal enum class BottomLockMode {
    Auto,
    Force,
}

@Stable
internal class BottomFollowCoordinator {
    val shouldAutoFollowBottomState = mutableStateOf(true)
    val isProgrammaticScrollState = mutableStateOf(false)
    val isNearBottomState = mutableStateOf(true)
    val bottomLockModeState = mutableStateOf(BottomLockMode.Auto)
    val suspendAutoScrollUntilGestureEndsState = mutableStateOf(false)
    val scrollRequestGenerationState = mutableIntStateOf(0)
    val bottomControlVisibleState = mutableStateOf(false)
    val bottomNoticeState = mutableStateOf<String?>(null)
    val bottomNoticeVersionState = mutableIntStateOf(0)

    val forceBottomMode: Boolean
        get() = bottomLockModeState.value == BottomLockMode.Force

    fun showBottomNotice(message: String) {
        bottomNoticeState.value = message
        bottomNoticeVersionState.intValue++
        bottomControlVisibleState.value = true
    }

    fun showBottomControl() {
        bottomControlVisibleState.value = true
    }

    fun enableForceBottomMode(onAutoScroll: () -> Unit, onNotice: (String) -> Unit) {
        bottomLockModeState.value = BottomLockMode.Force
        shouldAutoFollowBottomState.value = true
        showBottomControl()
        onNotice("__force_on__")
        onAutoScroll()
    }

    fun disableForceBottomMode(showNotice: Boolean, onNotice: (String) -> Unit) {
        if (bottomLockModeState.value != BottomLockMode.Force) return
        bottomLockModeState.value = BottomLockMode.Auto
        shouldAutoFollowBottomState.value = false
        showBottomControl()
        if (showNotice) {
            onNotice("__force_off__")
        }
    }

    fun cancelForceBottomFromGesture(onNotice: (String) -> Unit) {
        if (!forceBottomMode) return
        suspendAutoScrollUntilGestureEndsState.value = true
        scrollRequestGenerationState.intValue++
        disableForceBottomMode(showNotice = true, onNotice = onNotice)
    }

    fun beginProgrammaticScroll(): Int {
        scrollRequestGenerationState.intValue++
        isProgrammaticScrollState.value = true
        return scrollRequestGenerationState.intValue
    }

    fun finishProgrammaticScroll(requestGeneration: Int) {
        if (requestGeneration == scrollRequestGenerationState.intValue) {
            isProgrammaticScrollState.value = false
        }
    }
}
