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

data class JoinRoomResponse(
    val playerId: String,
    val roomId: String,
    val nickname: String,
)

data class Question(
    val questionId: String,
    val text: String,
    val options: List<String>,
    val answerSeconds: Int,
    val predictSeconds: Int,
    val tags: List<String> = emptyList(),
)

data class PlayerScore(
    val playerId: String,
    val nickname: String = "",
    val targetOption: String,
    val predictedCount: Int,
    val actualCount: Int,
    val roundScore: Int,
)

// ── UI State Models ───────────────────────────────────────────

data class Player(
    val playerId: String,
    val nickname: String,
    val isHost: Boolean = false,
)

enum class GamePhase {
    WAITING, ANSWERING, PREDICTING, RESULT, FINISHED
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
                    )
                } ?: emptyList()

            return RoomSnapshot(
                status = phase,
                currentRound = (data["currentRound"] as? Long)?.toInt() ?: 0,
                totalRounds = (data["totalRounds"] as? Long)?.toInt() ?: 5,
                currentQuestion = question,
                answerCounts = counts,
                roundScores = parseScores("roundScores"),
                finalScores = parseScores("finalScores"),
            )
        }
    }
}
