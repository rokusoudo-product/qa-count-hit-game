package com.rokusoudo.hitokazu.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rokusoudo.hitokazu.data.firebase.FirebaseRepository
import com.rokusoudo.hitokazu.data.firebase.PlayersEvent
import com.rokusoudo.hitokazu.data.firebase.RoomEvent
import com.rokusoudo.hitokazu.data.local.RoomPrefs
import com.rokusoudo.hitokazu.data.model.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
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
    // Firestoreとの接続が切れている（と判定された）状態。ConnectionBannerの表示制御に使う（Issue #18）。
    val isDisconnected: Boolean = false,
    // 再入室時に、現在のラウンドで既に送信済みの予測（Issue #49）。
    // PredictingScreenの初期表示（「予測済み」の直接表示）にのみ使う。新ラウンド開始時にクリアする。
    val myPrediction: Int? = null,
    val myTargetOption: String? = null,
    // 端末に保存された「直近参加していたルームID」。ホーム画面の「ルームXXXXXXXXに戻る」
    // 導線の表示制御に使う（Issue #49）。ホストとしての参加では保存しない（ホスト復帰はスコープ外）。
    val savedRoomId: String? = null,
)

// 回答・予測フェーズのタイムアウト猶予秒数（通信遅延・端末クロックのズレを吸収するバッファ）
private const val TIMEOUT_GRACE_SECONDS = 3

// ホスト端末がルームドキュメントにハートビートを書き込む間隔（秒）。
// 参加者端末はこの3倍の時間ハートビートが更新されなければホスト離脱とみなす。
// 例: 15秒間隔・5ラウンド(1ラウンド50秒程度)＝約5分のゲームで書き込み回数は約20回。
// Firestore無料枠（書き込み2万回/日）に対して十分小さい。
private const val HOST_HEARTBEAT_INTERVAL_SECONDS = 15L
private const val HOST_HEARTBEAT_TIMEOUT_SECONDS = HOST_HEARTBEAT_INTERVAL_SECONDS * 3

// snapshotがキャッシュ由来のまま連続した場合に「切断」とみなすまでの猶予（ミリ秒）。
// 代表確認済み: 5秒固定。調整する場合はこの定数のみを変更すればよい（Issue #18）。
private const val DISCONNECT_THRESHOLD_MS = 5_000L

class GameViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = FirebaseRepository()
    private val roomPrefs = RoomPrefs(application)
    private val _uiState = MutableStateFlow(GameUiState(savedRoomId = roomPrefs.load()))
    val uiState: StateFlow<GameUiState> = _uiState.asStateFlow()

    private var roomObserverJob: Job? = null
    private var playerObserverJob: Job? = null
    private var autoAdvanceJob: Job? = null
    private var answeringTimeoutJob: Job? = null
    private var predictingTimeoutJob: Job? = null
    private var hostHeartbeatJob: Job? = null
    private var hostLeftCheckJob: Job? = null

    // 直近でタイムアウト監視をスケジュールした（フェーズ, ラウンド, フェーズ開始時刻）の組。
    // 同じ組に対して重複してタイマーを仕込まないようにするためのガード。
    private var lastScheduledTimeoutKey: String? = null

    // 直近でホスト離脱監視をスケジュールしたhostHeartbeatAtMillisの値。
    // 同じ値に対して重複してタイマーを仕込まないようにするためのガード。
    private var lastScheduledHeartbeatAt: Long? = null

    // ── 接続状態の監視（Issue #18） ───────────────────────────
    // room / players それぞれのリスナーについて、キャッシュ由来のデータが
    // DISCONNECT_THRESHOLD_MS 続いたら「切断」とみなすためのタイマー。
    private var roomCacheTimeoutJob: Job? = null
    private var playersCacheTimeoutJob: Job? = null

    // 切断と判定されているソース（"room" / "players"）の集合。
    // 空であれば isDisconnected = false。どちらか一方でも切断中ならバナーを表示する。
    private val disconnectedSources = mutableSetOf<String>()

    private fun markSourceDisconnected(source: String) {
        if (disconnectedSources.add(source)) {
            _uiState.update { it.copy(isDisconnected = true) }
        }
    }

    private fun markSourceConnected(source: String) {
        if (disconnectedSources.remove(source)) {
            _uiState.update { it.copy(isDisconnected = disconnectedSources.isNotEmpty()) }
        }
    }

    // isFromCacheな更新を受けるたびに呼ぶ。「キャッシュ由来が5秒続いたら切断」という
    // 仕様なので、すでに計測中のタイマーがあればそれを流用し、キャッシュ由来の更新が
    // 連続するたびに5秒を数え直す（＝ずっと切断判定に到達しない）ことを避ける。
    private fun scheduleCacheTimeout(source: String, existingJob: Job?): Job {
        if (existingJob?.isActive == true) return existingJob
        return viewModelScope.launch {
            delay(DISCONNECT_THRESHOLD_MS)
            markSourceDisconnected(source)
        }
    }

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
    // players/{uid}が既に存在するルームの場合はrepo側で「再入室」として扱われ、
    // 現在のフェーズ・既存の回答/予測がresに含まれる（Issue #49）。
    fun joinRoom(roomId: String, nickname: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            repo.joinRoom(roomId, nickname)
                .onSuccess { res -> applyJoinResult(res) }
                .onFailure { e ->
                    val msg = when {
                        e.message?.contains("見つかりません") == true -> "ルームが見つかりません"
                        e.message?.contains("開始されています") == true -> "ゲームはすでに開始されています"
                        e.message?.contains("満員") == true -> "ルームが満員です"
                        e.message?.contains("ホストの復帰") == true -> e.message!!
                        else -> "参加に失敗しました: ${e.message}"
                    }
                    _uiState.update { it.copy(errorMessage = msg) }
                }
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    // ── ルーム再入室（ホーム画面の「ルームXXXXXXXXに戻る」から呼び出す） ──
    // アプリ再起動・クラッシュ後、端末に保存されたルームIDを使って復帰する（Issue #49）。
    // 参加者として登録されていない（players/{uid}が無い）場合は新規参加へフォールバックせず
    // 失敗させ、保存済みルームIDを破棄する（ホーム画面から復帰導線を消す）。
    fun rejoinSavedRoom() {
        val roomId = _uiState.value.savedRoomId ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            repo.rejoinRoom(roomId)
                .onSuccess { res -> applyJoinResult(res) }
                .onFailure { e ->
                    Log.e("GameViewModel", "rejoinSavedRoom failed", e)
                    roomPrefs.clear()
                    _uiState.update {
                        it.copy(
                            savedRoomId = null,
                            errorMessage = "ルームへの復帰に失敗しました: ${e.message}",
                        )
                    }
                }
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    // ── 保存済みルームIDの復帰導線を明示的に消す（ユーザーが「戻らない」を選んだ場合） ──
    fun dismissSavedRoom() {
        roomPrefs.clear()
        _uiState.update { it.copy(savedRoomId = null) }
    }

    // joinRoom()/rejoinSavedRoom()の成功時に共通する状態反映処理。
    // res.initialSnapshotには、参加/再入室時点のルーム状態が同期的に含まれているため、
    // observeRoomの最初のスナップショット到達を待たずに現在のフェーズへ直接遷移できる（Issue #49）。
    private fun applyJoinResult(res: JoinRoomResponse) {
        val snap = res.initialSnapshot
        _uiState.update { state ->
            state.copy(
                roomId = res.roomId,
                playerId = res.playerId,
                nickname = res.nickname,
                isHost = false,
                phase = snap?.status ?: state.phase,
                currentRound = snap?.currentRound ?: state.currentRound,
                totalRounds = snap?.totalRounds ?: state.totalRounds,
                currentQuestion = snap?.currentQuestion ?: state.currentQuestion,
                answerCounts = snap?.answerCounts ?: state.answerCounts,
                scores = when (snap?.status) {
                    null -> state.scores
                    GamePhase.FINISHED -> snap.finalScores
                    else -> snap.roundScores
                },
                selectedAnswer = res.existingAnswer ?: "",
                myPrediction = res.existingPrediction,
                myTargetOption = res.existingTargetOption,
            )
        }
        startObserving(res.roomId)
    }

    // ── ゲーム開始（ホストのみ） ──────────────────────────────
    fun startGame() {
        viewModelScope.launch {
            repo.startGame(_uiState.value.roomId)
                .onFailure { _uiState.update { it.copy(errorMessage = "ゲーム開始に失敗しました") } }
        }
    }

    // ── 再戦（ホストのみ・終了画面の「もう一度遊ぶ」） ────────
    // resetGame() と異なり roomId/playerId/observer は維持する。
    // observeRoom が FINISHED → WAITING への遷移を全端末に伝え、各画面がそれを検知して
    // 待合室へ自動遷移する（参加者はルームID再入力・QR再スキャン不要）。
    fun restartGame() {
        viewModelScope.launch {
            repo.restartGame(_uiState.value.roomId)
                .onFailure { _uiState.update { it.copy(errorMessage = "再戦の開始に失敗しました") } }
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
        // リスナーの張り直し（reconnect含む）のたびに、古い接続監視タイマーも
        // 引き継がず作り直す。引き継ぐと張り直し直後に古いタイマーが誤発火しうる。
        roomCacheTimeoutJob?.cancel()
        roomCacheTimeoutJob = null
        playersCacheTimeoutJob?.cancel()
        playersCacheTimeoutJob = null

        // ホスト端末は観測開始と同時に定期ハートビートの送信を始める（フェーズを問わず、
        // 待合室段階からのホスト離脱も検知できるようにするため）。
        if (_uiState.value.isHost) {
            startHostHeartbeat(roomId)
        }

        roomObserverJob?.cancel()
        roomObserverJob = viewModelScope.launch {
            repo.observeRoom(roomId).collect { event ->
                when (event) {
                    is RoomEvent.Error -> {
                        // Logcatへの出力はFirebaseRepository側で実施済み。
                        // ここではUiState経由でバナー表示に反映する。
                        roomCacheTimeoutJob?.cancel()
                        roomCacheTimeoutJob = null
                        markSourceDisconnected("room")
                    }
                    is RoomEvent.Data -> {
                        val snapshot = event.snapshot
                        if (event.isFromCache) {
                            roomCacheTimeoutJob = scheduleCacheTimeout("room", roomCacheTimeoutJob)
                        } else {
                            roomCacheTimeoutJob?.cancel()
                            roomCacheTimeoutJob = null
                            markSourceConnected("room")
                        }

                        _uiState.update { state ->
                            // 新しいANSWERINGラウンドに入ったら、前ラウンドの送信済み回答・予測
                            // （再入室時のブートストラップ含む）を持ち越さずクリアする（Issue #49）。
                            val isNewAnsweringRound = snapshot.status == GamePhase.ANSWERING &&
                                snapshot.currentRound != state.currentRound
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
                                selectedAnswer = if (isNewAnsweringRound) "" else state.selectedAnswer,
                                myPrediction = if (isNewAnsweringRound) null else state.myPrediction,
                                myTargetOption = if (isNewAnsweringRound) null else state.myTargetOption,
                            )
                        }

                        // 直近参加していたルームIDを端末に保存する（参加者のみ。Issue #49）。
                        // ゲーム終了・ホスト離脱で確定したら、以後の復帰導線を出さないよう破棄する。
                        if (!_uiState.value.isHost) {
                            if (snapshot.status == GamePhase.FINISHED || snapshot.status == GamePhase.HOST_LEFT) {
                                if (roomPrefs.load() != null) {
                                    roomPrefs.clear()
                                    _uiState.update { it.copy(savedRoomId = null) }
                                }
                            } else if (roomPrefs.load() != roomId) {
                                roomPrefs.save(roomId)
                                _uiState.update { it.copy(savedRoomId = roomId) }
                            }
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

                        // ゲームが終了・確定した場合、ホストのハートビート送信も止める（無駄な書き込みを避ける）。
                        if (_uiState.value.isHost &&
                            (snapshot.status == GamePhase.FINISHED || snapshot.status == GamePhase.HOST_LEFT)
                        ) {
                            hostHeartbeatJob?.cancel()
                            hostHeartbeatJob = null
                        }

                        // 参加者端末はホストのハートビート停止を監視し、離脱を検知したらルームを終了させる。
                        scheduleHostLeftCheck(roomId, snapshot)
                    }
                }
            }
        }

        playerObserverJob?.cancel()
        playerObserverJob = viewModelScope.launch {
            repo.observePlayers(roomId).collect { event ->
                when (event) {
                    is PlayersEvent.Error -> {
                        playersCacheTimeoutJob?.cancel()
                        playersCacheTimeoutJob = null
                        markSourceDisconnected("players")
                    }
                    is PlayersEvent.Data -> {
                        if (event.isFromCache) {
                            playersCacheTimeoutJob = scheduleCacheTimeout("players", playersCacheTimeoutJob)
                        } else {
                            playersCacheTimeoutJob?.cancel()
                            playersCacheTimeoutJob = null
                            markSourceConnected("players")
                        }
                        _uiState.update { it.copy(players = event.players) }
                    }
                }
            }
        }
    }

    // ── ホストのハートビート定期送信をスケジュール（ホストのみ） ──
    private fun startHostHeartbeat(roomId: String) {
        hostHeartbeatJob?.cancel()
        hostHeartbeatJob = viewModelScope.launch {
            while (isActive) {
                repo.sendHostHeartbeat(roomId)
                    .onFailure { Log.e("GameViewModel", "sendHostHeartbeat failed", it) }
                delay(HOST_HEARTBEAT_INTERVAL_SECONDS * 1000L)
            }
        }
    }

    // ── ホスト離脱監視をスケジュール（参加者のみ） ──────────
    // hostHeartbeatAtが更新されるたびに監視タイマーを仕込み直す（ウォッチドッグ方式）。
    // 次の更新が来る前にタイマーが尽きたら、ホストが離脱したとみなしルームを終了させる。
    private fun scheduleHostLeftCheck(roomId: String, snapshot: RoomSnapshot) {
        if (_uiState.value.isHost) return

        if (snapshot.status == GamePhase.FINISHED || snapshot.status == GamePhase.HOST_LEFT) {
            hostLeftCheckJob?.cancel()
            hostLeftCheckJob = null
            lastScheduledHeartbeatAt = null
            return
        }

        // 初回のハートビートが届くまでは判定しない（createRoom時に書き込まれるため、
        // 通常はルーム参加直後から値が存在する）。
        val heartbeatAt = snapshot.hostHeartbeatAtMillis ?: return
        if (lastScheduledHeartbeatAt == heartbeatAt) return
        lastScheduledHeartbeatAt = heartbeatAt

        val deadline = heartbeatAt + HOST_HEARTBEAT_TIMEOUT_SECONDS * 1000L
        hostLeftCheckJob?.cancel()
        hostLeftCheckJob = viewModelScope.launch {
            val waitMs = deadline - System.currentTimeMillis()
            if (waitMs > 0) delay(waitMs)
            repo.terminateRoomHostLeft(roomId)
                .onFailure { Log.e("GameViewModel", "terminateRoomHostLeft failed", it) }
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

    // ── 離脱・ローカル状態のリセット ──────────────────────────
    // 「トップに戻る」（FinishedScreen）・ホスト離脱後の退室（HostLeftScreen）・
    // 待合室/ゲーム中の「退室する」（WaitingRoomScreen等）から共通で呼ばれる。
    // 参加者（ホスト以外）の場合は、ローカル状態を破棄する前に自分の
    // players/{uid} ドキュメントをFirestoreから削除する（Issue #33）。
    // ホストはここでは削除しない（ホスト離脱はハートビート停止検知の経路に一本化する）。
    fun resetGame() {
        val state = _uiState.value
        // 明示的な離脱なので、以後の「ルームXXXXXXXXに戻る」導線も破棄する（Issue #49）。
        roomPrefs.clear()
        if (!state.isHost && state.roomId.isNotEmpty() && state.playerId.isNotEmpty()) {
            val roomId = state.roomId
            val playerId = state.playerId
            viewModelScope.launch {
                repo.leaveRoom(roomId, playerId)
                    .onFailure { Log.e("GameViewModel", "leaveRoom failed", it) }
            }
        }

        autoAdvanceJob?.cancel()
        answeringTimeoutJob?.cancel()
        predictingTimeoutJob?.cancel()
        hostHeartbeatJob?.cancel()
        hostLeftCheckJob?.cancel()
        roomObserverJob?.cancel()
        playerObserverJob?.cancel()
        roomCacheTimeoutJob?.cancel()
        playersCacheTimeoutJob?.cancel()
        roomCacheTimeoutJob = null
        playersCacheTimeoutJob = null
        disconnectedSources.clear()
        lastScheduledTimeoutKey = null
        lastScheduledHeartbeatAt = null
        _uiState.value = GameUiState()
    }

    fun clearError() = _uiState.update { it.copy(errorMessage = null) }

    // ── 再接続（ConnectionBannerの「再接続」ボタンから呼び出す） ──
    // Firestoreのネットワークを明示的に有効化しつつ、room/playersリスナーを
    // 張り直す。切断中に進んだルーム状態は、張り直し後の最新snapshotで反映される。
    fun reconnect() {
        val roomId = _uiState.value.roomId
        if (roomId.isEmpty()) return

        viewModelScope.launch {
            repo.enableNetwork()
                .onFailure { Log.e("GameViewModel", "enableNetwork failed", it) }
        }
        startObserving(roomId)
    }

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
        hostHeartbeatJob?.cancel()
        hostLeftCheckJob?.cancel()
        roomObserverJob?.cancel()
        playerObserverJob?.cancel()
        roomCacheTimeoutJob?.cancel()
        playersCacheTimeoutJob?.cancel()
    }
}
