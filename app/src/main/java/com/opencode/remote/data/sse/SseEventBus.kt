package com.opencode.remote.data.sse

import com.opencode.remote.data.api.dto.ServerEvent
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SseEventBus @Inject constructor() {
    private val _events = MutableSharedFlow<ServerEvent>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<ServerEvent> = _events.asSharedFlow()

    fun emit(event: ServerEvent) {
        _events.tryEmit(event)
    }
}
