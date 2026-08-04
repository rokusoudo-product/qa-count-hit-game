package com.rokusoudo.hitokazu.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rokusoudo.hitokazu.data.firebase.FirebaseRepository
import com.rokusoudo.hitokazu.data.model.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    val errorMessage: String? = null,
    val isLoading: Boolean = false,
    val pendingJoinRoomId: String? = null,
    val selectedCategory: QuestionCategory = QuestionCategory.ALL,
)

// 回答・予測フェーズのタイムアウト猶予秒数（通信遅延・端末クロックのズレを吸収するバッファ）
private const val TIMEOUT_GRACE_SECONDS = 3

class GameViewModel : ViewModel() {

    private val repo = FirebaseRepository()
    private val _uiState = MutableStateFlow(GameUiState())
    val uiState: StateFlow<GameUiState> = _uiState.asStateFlow()

    private var roomObserverJob: Job? = null
    private var playerObserverJob: Job? = null
    private var autoAdvanceJob: Job? = null
    private var answeringTimeoutJob: Job? = null
    private var predictingTimeoutJob: Job? = null

    // 直近でタイムアウト監視をスケジュールした（フェーズ, ラウンド, フェーズ開始時刻）の組。
    // 同じ組に対して重複してタイマーを仕込まないようにするためのガード。
    private var lastScheduledTimeoutKey: String? = null

    // ── ルーム作成（ホスト） ──────────────────────────────────
    fun createRoom(hostName: String, category: QuestionCategory = QuestionCategory.ALL) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            repo.createRoom(hostName, category)
                .onSuccess { res ->
                    _uiState.update {
                        it.copy(
                            roomId = res.roomId,
                            playerId = res.playerId,
                            nickname = res.nickname,
                            isHost = true,
                            selectedCategory = category,
                        )
                    }
                    startObserving(res.roomId)
                }
                .onFailure { e ->
                    Log.e("GameViewModel", "createRoom failed", e)
                    _uiState.update { it.copy(errorMessage = "ルーム作成に失敗しました: ${e.message}") }
                }
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    // ── ルーム参加（参加者） ──────────────────────────────────
    fun joinRoom(roomId: String, nickname: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            repo.joinRoom(roomId, nickname)
                .onSuccess { res ->
                    _uiState.update {
                        it.copy(
                            roomId = res.roomId,
                            playerId = res.playerId,
                            nickname = res.nickname,
                            isHost = false,
                        )
                    }
                    startObserving(res.roomId)
                }
                .onFailure { e ->
                    val msg = when {
                        e.message?.contains("見つかりません") == true -> "ルームが見つかりません"
                        e.message?.contains("開始されています") == true -> "ゲームはすでに開始されています"
                        e.message?.contains("満員") == true -> "ルームが満員です"
                        else -> "参加に失敗しました: ${e.message}"
                    }
                    _uiState.update { it.copy(errorMessage = msg) }
                }
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    // ── ゲーム開始（ホストのみ） ──────────────────────────────
    fun startGame() {
        viewModelScope.launch {
            repo.startGame(_uiState.value.roomId)
                .onFailure { _uiState.update { it.copy(errorMessage = "ゲーム開始に失敗しました") } }
        }
    }

    // ── 回答送信 ──────────────────────────────────────────────
    fun submitAnswer(answer: String) {
        val state = _uiState.value
        viewModelScope.launch {
            _uiState.update { it.copy(selectedAnswer = answer) }
            repo.submitAnswer(state.roomId, state.playerId, answer)
                .onFailure { _uiState.update { it.copy(errorMessage = "回答の送信に失敗しました") } }
        }
    }

    // ── 予測送信 ──────────────────────────────────────────────
    fun submitPrediction(targetOption: String, predictedCount: Int) {
        val state = _uiState.value
        viewModelScope.launch {
            repo.submitPrediction(state.roomId, state.playerId, targetOption, predictedCount)
                .onFailure { _uiState.update { it.copy(errorMessage = "予測の送信に失敗しました") } }
        }
    }

    // ── Firestoreリアルタイム監視 ─────────────────────────────
    private fun startObserving(roomId: String) {
        roomObserverJob?.cancel()
        roomObserverJob = viewModelScope.launch {
            repo.observeRoom(roomId).collect { snapshot ->
                _uiState.update { state ->
                    state.copy(
                        phase = snapshot.status,
                        currentRound = snapshot.currentRound,
                        totalRounds = snapshot.totalRounds,
                        currentQuestion = snapshot.currentQuestion,
                        answerCounts = snapshot.answerCounts,
                        scores = when (snapshot.status) {
                            GamePhase.FINISHED -> snapshot.finalScores
                            else -> snapshot.roundScores
                        },
                        selectedAnswer = if (snapshot.status == GamePhase.ANSWERING &&
                            snapshot.currentRound != state.currentRound
                        ) "" else state.selectedAnswer,
                    )
                }

                // ホストがRESULTを検知したら10秒後に次ラウンドへ自動進行
                if (snapshot.status == GamePhase.RESULT && _uiState.value.isHost) {
                    autoAdvanceJob?.cancel()
                    autoAdvanceJob = viewModelScope.launch {
                        delay(10_000)
                        repo.advanceToNextRound(roomId)
                            .onFailure { Log.e("GameViewModel", "advanceToNextRound failed", it) }
                    }
                }

                // ホスト端末が回答・予測フェーズのタイムアウトを監視する。
                // 1人でも未提出のままタイマーが尽きると、提出済み分だけでフェーズを強制確定する。
                when (snapshot.status) {
                    GamePhase.ANSWERING -> {
                        predictingTimeoutJob?.cancel()
                        predictingTimeoutJob = null
                        scheduleAnsweringTimeout(roomId, snapshot)
                    }
                    GamePhase.PREDICTING -> {
                        answeringTimeoutJob?.cancel()
                        answeringTimeoutJob = null
                        schedulePredictingTimeout(roomId, snapshot)
                    }
                    else -> {
                        answeringTimeoutJob?.cancel()
                        predictingTimeoutJob?.cancel()
                        answeringTimeoutJob = null
                        predictingTimeoutJob = null
                        lastScheduledTimeoutKey = null
                    }
                }
            }
        }

        playerObserverJob?.cancel()
        playerObserverJob = viewModelScope.launch {
            repo.observePlayers(roomId).collect { players ->
                _uiState.update { it.copy(players = players) }
            }
        }
    }

    // ── 回答フェーズのタイムアウト監視をスケジュール（ホストのみ） ──
    // 基準時刻はFirestoreに保存されたサーバー時刻（phaseStartedAt）から算出するため、
    // 途中参加や画面復帰でこのメソッドが再実行されても残り時間はズレない。
    private fun scheduleAnsweringTimeout(roomId: String, snapshot: RoomSnapshot) {
        if (!_uiState.value.isHost) return
        val question = snapshot.currentQuestion ?: return
        val startedAt = snapshot.phaseStartedAtMillis ?: return

        val key = "ANSWERING:${snapshot.currentRound}:$startedAt"
        if (lastScheduledTimeoutKey == key) return
        lastScheduledTimeoutKey = key

        val deadline = startedAt + (question.answerSeconds + TIMEOUT_GRACE_SECONDS) * 1000L
        answeringTimeoutJob?.cancel()
        answeringTimeoutJob = viewModelScope.launch {
            val waitMs = deadline - System.currentTimeMillis()
            if (waitMs > 0) delay(waitMs)
            repo.forceAdvanceFromAnswering(roomId)
                .onFailure { Log.e("GameViewModel", "forceAdvanceFromAnswering failed", it) }
        }
    }

    // ── 予測フェーズのタイムアウト監視をスケジュール（ホストのみ） ──
    private fun schedulePredictingTimeout(roomId: String, snapshot: RoomSnapshot) {
        if (!_uiState.value.isHost) return
        val question = snapshot.currentQuestion ?: return
        val startedAt = snapshot.phaseStartedAtMillis ?: return

        val key = "PREDICTING:${snapshot.currentRound}:$startedAt"
        if (lastScheduledTimeoutKey == key) return
        lastScheduledTimeoutKey = key

        val deadline = startedAt + (question.predictSeconds + TIMEOUT_GRACE_SECONDS) * 1000L
        predictingTimeoutJob?.cancel()
        predictingTimeoutJob = viewModelScope.launch {
            val waitMs = deadline - System.currentTimeMillis()
            if (waitMs > 0) delay(waitMs)
            repo.forceFinalizeFromPredicting(roomId)
                .onFailure { Log.e("GameViewModel", "forceFinalizeFromPredicting failed", it) }
        }
    }

    fun resetGame() {
        autoAdvanceJob?.cancel()
        answeringTimeoutJob?.cancel()
        predictingTimeoutJob?.cancel()
        roomObserverJob?.cancel()
        playerObserverJob?.cancel()
        lastScheduledTimeoutKey = null
        _uiState.value = GameUiState()
    }

    fun clearError() = _uiState.update { it.copy(errorMessage = null) }

    fun reconnect() {}

    // ── ディープリンクから受け取ったルームIDをセット ──────────
    fun setPendingJoinRoomId(roomId: String) {
        _uiState.update { it.copy(pendingJoinRoomId = roomId) }
    }

    fun clearPendingJoinRoomId() {
        _uiState.update { it.copy(pendingJoinRoomId = null) }
    }

    override fun onCleared() {
        super.onCleared()
        autoAdvanceJob?.cancel()
        answeringTimeoutJob?.cancel()
        predictingTimeoutJob?.cancel()
        roomObserverJob?.cancel()
        playerObserverJob?.cancel()
    }
}
