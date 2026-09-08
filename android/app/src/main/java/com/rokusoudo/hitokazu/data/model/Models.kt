package com.rokusoudo.hitokazu.data.model

// ── Question Category ─────────────────────────────────────────

enum class QuestionCategory(val displayName: String, val tags: List<String>) {
    FRIEND("フレンド", listOf("friend")),
    PARTY("パーティー", listOf("party")),
    DEEP("ディープ", listOf("deep")),
    ALL("なんでも", listOf("friend", "party", "deep"));

    companion object {
        fun fromString(value: String?): QuestionCategory =
            entries.firstOrNull { it.name == value } ?: ALL
    }
}

// ── API Response Models ───────────────────────────────────────

// 既存メンバーの再入室時、呼び出し元（GameViewModel）が現在のフェーズ画面へ直接
// 復帰できるよう、参加/再入室時点のルームスナップショットと自分の送信済み回答・予測を
// 併せて返す（Issue #49）。新規参加時も（statusはWAITING固定の）スナップショットを含める。
data class JoinRoomResponse(
    val playerId: String,
    val roomId: String,
    val nickname: String,
    val initialSnapshot: RoomSnapshot? = null,
    val existingAnswer: String? = null,
    val existingPrediction: Int? = null,
    val existingTargetOption: String? = null,
)

data class Question(
    val questionId: String,
    val text: String,
    val options: List<String>,
    val answerSeconds: Int,
    val predictSeconds: Int,
    val tags: List<String> = emptyList(),
)

// Firestore へ書き込む際のマップ表現。質問マスタ（data/questions/Questions.kt）は
// Issue #16 で shared/questions.json から生成されるようになった。
fun Question.toMap(): Map<String, Any> = mapOf(
    "questionId" to questionId,
    "text" to text,
    "options" to options,
    "answerSeconds" to answerSeconds,
    "predictSeconds" to predictSeconds,
    "tags" to tags,
)

data class PlayerScore(
    val playerId: String,
    val nickname: String = "",
    val targetOption: String,
    val predictedCount: Int,
    val actualCount: Int,
    val roundScore: Int,
    val totalScore: Int = 0,
)

// ── UI State Models ───────────────────────────────────────────

data class Player(
    val playerId: String,
    val nickname: String,
    val isHost: Boolean = false,
)

enum class GamePhase {
    WAITING, ANSWERING, PREDICTING, RESULT, FINISHED,
    // ホスト端末のハートビート（hostHeartbeatAt）が一定時間更新されなくなったことを
    // 参加者端末が検知し、ルームを終了させたときの状態（Issue #15）。
    HOST_LEFT
}

// ── Firestore Snapshot ────────────────────────────────────────

data class RoomSnapshot(
    val status: GamePhase,
    val currentRound: Int,
    val totalRounds: Int,
    val currentQuestion: Question?,
    val answerCounts: Map<String, Int>,
    val roundScores: List<PlayerScore>,
    val finalScores: List<PlayerScore>,
    // 現在のフェーズ（ANSWERING/PREDICTING）が開始したサーバー時刻（epoch millis）。
    // タイムアウト判定はこの値を基準に行う（端末時計や監視開始タイミングに依存させないため）。
    val phaseStartedAtMillis: Long?,
    // ホスト端末が最後にハートビートを書き込んだサーバー時刻（epoch millis）。
    // 参加者端末はこの値の更新が一定時間止まったことをもってホスト離脱と判定する（Issue #15）。
    val hostHeartbeatAtMillis: Long?,
) {
    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromMap(data: Map<String, Any>): RoomSnapshot {
            val statusStr = data["status"] as? String ?: "WAITING"
            val phase = runCatching { GamePhase.valueOf(statusStr) }.getOrDefault(GamePhase.WAITING)

            val qMap = data["currentQuestion"] as? Map<String, Any>
            val question = qMap?.let {
                Question(
                    questionId = it["questionId"] as? String ?: "",
                    text = it["text"] as? String ?: "",
                    options = (it["options"] as? List<String>) ?: emptyList(),
                    answerSeconds = (it["answerSeconds"] as? Long)?.toInt() ?: 30,
                    predictSeconds = (it["predictSeconds"] as? Long)?.toInt() ?: 20,
                    tags = (it["tags"] as? List<*>)?.map { t -> t.toString() } ?: emptyList(),
                )
            }

            val counts = (data["answerCounts"] as? Map<String, Any>)
                ?.mapValues { (it.value as? Long)?.toInt() ?: 0 }
                ?: emptyMap()

            fun parseScores(key: String): List<PlayerScore> =
                (data[key] as? List<Map<String, Any>>)?.map { s ->
                    PlayerScore(
                        playerId = s["playerId"] as? String ?: "",
                        nickname = s["nickname"] as? String ?: "",
                        targetOption = s["targetOption"] as? String ?: "",
                        predictedCount = (s["predictedCount"] as? Long)?.toInt() ?: 0,
                        actualCount = (s["actualCount"] as? Long)?.toInt() ?: 0,
                        roundScore = (s["roundScore"] as? Long)?.toInt() ?: 0,
                        totalScore = (s["totalScore"] as? Long)?.toInt() ?: 0,
                    )
                } ?: emptyList()

            val phaseStartedAtMillis = (data["phaseStartedAt"] as? com.google.firebase.Timestamp)?.toDate()?.time
            val hostHeartbeatAtMillis = (data["hostHeartbeatAt"] as? com.google.firebase.Timestamp)?.toDate()?.time

            return RoomSnapshot(
                status = phase,
                currentRound = (data["currentRound"] as? Long)?.toInt() ?: 0,
                totalRounds = (data["totalRounds"] as? Long)?.toInt() ?: 5,
                currentQuestion = question,
                answerCounts = counts,
                roundScores = parseScores("roundScores"),
                finalScores = parseScores("finalScores"),
                phaseStartedAtMillis = phaseStartedAtMillis,
                hostHeartbeatAtMillis = hostHeartbeatAtMillis,
            )
        }
    }
}
