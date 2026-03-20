package com.rokusoudo.hitokazu.data.websocket

import android.util.Log
import com.rokusoudo.hitokazu.BuildConfig
import com.rokusoudo.hitokazu.data.model.WsEvent
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import okhttp3.*

private const val TAG = "WebSocketManager"

sealed class WsState {
    object Connecting : WsState()
    object Connected : WsState()
    data class Disconnected(val reason: String) : WsState()
    data class Error(val message: String) : WsState()
}

class WebSocketManager {

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()
    private val eventAdapter = moshi.adapter(WsEvent::class.java)

    private val client = OkHttpClient()
    private var webSocket: WebSocket? = null

    private val _events = MutableSharedFlow<WsEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<WsEvent> = _events

    private val _state = MutableSharedFlow<WsState>(replay = 1, extraBufferCapacity = 8)
    val state: SharedFlow<WsState> = _state

    fun connect(roomId: String) {
        val url = "${BuildConfig.WS_URL}?roomId=$roomId"
        val request = Request.Builder().url(url).build()

        _state.tryEmit(WsState.Connecting)

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(TAG, "Connected")
                _state.tryEmit(WsState.Connected)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                Log.d(TAG, "Received: $text")
                runCatching { eventAdapter.fromJson(text) }
                    .onSuccess { event -> event?.let { _events.tryEmit(it) } }
                    .onFailure { Log.e(TAG, "Parse error: $it") }
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                ws.close(1000, null)
                _state.tryEmit(WsState.Disconnected(reason))
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket error: $t")
                _state.tryEmit(WsState.Error(t.message ?: "Unknown error"))
            }
        })
    }

    fun disconnect() {
        webSocket?.close(1000, "User left")
        webSocket = null
    }
}
