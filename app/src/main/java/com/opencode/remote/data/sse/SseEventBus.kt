package com.opencode.remote.data.sse

import com.opencode.remote.data.api.dto.ServerEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SseEventBus @Inject constructor() {
    private val _events = MutableSharedFlow<ServerEvent>(
        replay = 0,
        extraBufferCapacity = 64
    )
    val events: SharedFlow<ServerEvent> = _events.asSharedFlow()

    fun emit(event: ServerEvent) {
        _events.tryEmit(event)
    }
}
