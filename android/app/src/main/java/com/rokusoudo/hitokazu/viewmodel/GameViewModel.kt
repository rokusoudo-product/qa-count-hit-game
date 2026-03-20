package com.rokusoudo.hitokazu.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rokusoudo.hitokazu.data.api.ApiClient
import com.rokusoudo.hitokazu.data.model.*
import com.rokusoudo.hitokazu.data.websocket.WebSocketManager
import com.rokusoudo.hitokazu.data.websocket.WsState
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class GameUiState(
    val roomId: String = "",
    val playerId: String = "",
    val nickname: String = "",
    val isHost: Boolean = false,
    val players: List<Player> = emptyList(),
    val phase: GamePhase = GamePhase.WAITING,
    val currentRound: Int = 0,
    val totalRounds: Int = 5,
    val currentQuestion: Question? = null,
    val selectedAnswer: String = "",
    val answerCounts: Map<String, Int> = emptyMap(),
    val scores: List<PlayerScore> = emptyList(),
    val qrBase64: String = "",
    val wsState: WsState = WsState.Disconnected(""),
    val errorMessage: String? = null,
    val isLoading: Boolean = false,
)

class GameViewModel : ViewModel() {

    private val api = ApiClient.service
    private val wsManager = WebSocketManager()

    private val _uiState = MutableStateFlow(GameUiState())
    val uiState: StateFlow<GameUiState> = _uiState.asStateFlow()

    init {
        // WebSocket状態監視
        viewModelScope.launch {
            wsManager.state.collect { state ->
                _uiState.update { it.copy(wsState = state) }
            }
        }
        // WebSocketイベント処理
        viewModelScope.launch {
            wsManager.events.collect { event -> handleWsEvent(event) }
        }
    }

    // ── ルーム作成（ホスト） ──────────────────────────────────
    fun createRoom(hostName: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            runCatching {
                api.createRoom(mapOf("hostName" to hostName))
            }.onSuccess { response ->
                if (response.isSuccessful) {
                    val roomId = response.body()?.roomId ?: return@onSuccess
                    _uiState.update { it.copy(roomId = roomId, isHost = true, nickname = hostName) }
                    fetchQr(roomId)
                    wsManager.connect(roomId)
                } else {
                    _uiState.update { it.copy(errorMessage = "ルーム作成に失敗しました") }
                }
            }.onFailure {
                _uiState.update { it.copy(errorMessage = "通信エラーが発生しました") }
            }
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    // ── QRコード取得 ─────────────────────────────────────────
    private fun fetchQr(roomId: String) {
        viewModelScope.launch {
            runCatching { api.getQr(roomId) }.onSuccess { response ->
                if (response.isSuccessful) {
                    val qr = response.body()?.qrCode ?: return@onSuccess
                    _uiState.update { it.copy(qrBase64 = qr) }
                }
            }
        }
    }

    // ── ルーム参加（参加者） ──────────────────────────────────
    fun joinRoom(roomId: String, nickname: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            runCatching {
                api.joinRoom(roomId, JoinRoomRequest(nickname))
            }.onSuccess { response ->
                if (response.isSuccessful) {
                    val body = response.body() ?: return@onSuccess
                    _uiState.update {
                        it.copy(
                            roomId = body.roomId,
                            playerId = body.playerId,
                            nickname = body.nickname,
                            isHost = false
                        )
                    }
                    wsManager.connect(roomId)
                } else {
                    val msg = when (response.code()) {
                        404 -> "ルームが見つかりません"
                        409 -> "ルームが満員またはゲームが開始済みです"
                        else -> "参加に失敗しました"
                    }
                    _uiState.update { it.copy(errorMessage = msg) }
                }
            }.onFailure {
                _uiState.update { it.copy(errorMessage = "通信エラーが発生しました") }
            }
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    // ── ゲーム開始（ホストのみ） ──────────────────────────────
    fun startGame() {
        val roomId = _uiState.value.roomId
        viewModelScope.launch {
            runCatching { api.startGame(roomId) }.onFailure {
                _uiState.update { it.copy(errorMessage = "ゲーム開始に失敗しました") }
            }
        }
    }

    // ── 回答送信 ──────────────────────────────────────────────
    fun submitAnswer(answer: String) {
        val state = _uiState.value
        viewModelScope.launch {
            _uiState.update { it.copy(selectedAnswer = answer) }
            runCatching {
                api.submitAnswer(state.roomId, SubmitAnswerRequest(state.playerId, answer))
            }.onFailure {
                _uiState.update { it.copy(errorMessage = "回答の送信に失敗しました") }
            }
        }
    }

    // ── 予測送信 ──────────────────────────────────────────────
    fun submitPrediction(targetOption: String, predictedCount: Int) {
        val state = _uiState.value
        viewModelScope.launch {
            runCatching {
                api.submitPrediction(
                    state.roomId,
                    SubmitPredictionRequest(state.playerId, targetOption, predictedCount)
                )
            }.onFailure {
                _uiState.update { it.copy(errorMessage = "予測の送信に失敗しました") }
            }
        }
    }

    // ── WebSocketイベント処理 ─────────────────────────────────
    private fun handleWsEvent(event: WsEvent) {
        when (event.action) {
            "playerJoined" -> {
                val newPlayer = Player(
                    playerId = event.playerId ?: return,
                    nickname = event.nickname ?: return
                )
                _uiState.update { state ->
                    val updated = state.players.toMutableList().apply {
                        if (none { it.playerId == newPlayer.playerId }) add(newPlayer)
                    }
                    state.copy(players = updated)
                }
            }
            "gameStarted" -> {
                _uiState.update {
                    it.copy(
                        phase = GamePhase.ANSWERING,
                        currentRound = event.round ?: 1,
                        totalRounds = event.totalRounds ?: 5,
                        currentQuestion = event.question,
                        selectedAnswer = "",
                    )
                }
            }
            "allAnswered" -> {
                _uiState.update {
                    it.copy(
                        phase = GamePhase.PREDICTING,
                        answerCounts = event.answerCounts ?: emptyMap(),
                    )
                }
            }
            "roundResult" -> {
                _uiState.update {
                    it.copy(
                        phase = GamePhase.RESULT,
                        scores = event.scores ?: emptyList(),
                        answerCounts = event.answerCounts ?: emptyMap(),
                    )
                }
            }
            "gameEnded" -> {
                _uiState.update {
                    it.copy(
                        phase = GamePhase.FINISHED,
                        scores = event.finalScores ?: emptyList(),
                    )
                }
            }
        }
    }

    fun clearError() = _uiState.update { it.copy(errorMessage = null) }

    override fun onCleared() {
        super.onCleared()
        wsManager.disconnect()
    }
}
