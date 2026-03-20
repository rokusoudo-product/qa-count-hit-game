package com.rokusoudo.hitokazu.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

// ── REST Response Models ──────────────────────────────────────

@JsonClass(generateAdapter = true)
data class CreateRoomResponse(
    @Json(name = "roomId") val roomId: String
)

@JsonClass(generateAdapter = true)
data class QrResponse(
    @Json(name = "roomId") val roomId: String,
    @Json(name = "qrCode") val qrCode: String,   // base64 PNG
    @Json(name = "format") val format: String
)

@JsonClass(generateAdapter = true)
data class JoinRoomRequest(
    @Json(name = "nickname") val nickname: String
)

@JsonClass(generateAdapter = true)
data class JoinRoomResponse(
    @Json(name = "playerId") val playerId: String,
    @Json(name = "roomId") val roomId: String,
    @Json(name = "nickname") val nickname: String
)

@JsonClass(generateAdapter = true)
data class Question(
    @Json(name = "questionId") val questionId: String,
    @Json(name = "text") val text: String,
    @Json(name = "options") val options: List<String>,
    @Json(name = "answerSeconds") val answerSeconds: Int,
    @Json(name = "predictSeconds") val predictSeconds: Int
)

@JsonClass(generateAdapter = true)
data class SubmitAnswerRequest(
    @Json(name = "playerId") val playerId: String,
    @Json(name = "answer") val answer: String
)

@JsonClass(generateAdapter = true)
data class SubmitPredictionRequest(
    @Json(name = "playerId") val playerId: String,
    @Json(name = "targetOption") val targetOption: String,
    @Json(name = "predictedCount") val predictedCount: Int
)

// ── WebSocket Event Models ────────────────────────────────────

@JsonClass(generateAdapter = true)
data class WsEvent(
    @Json(name = "action") val action: String,
    @Json(name = "round") val round: Int? = null,
    @Json(name = "totalRounds") val totalRounds: Int? = null,
    @Json(name = "question") val question: Question? = null,
    @Json(name = "answerCounts") val answerCounts: Map<String, Int>? = null,
    @Json(name = "scores") val scores: List<PlayerScore>? = null,
    @Json(name = "finalScores") val finalScores: List<PlayerScore>? = null,
    @Json(name = "nextRound") val nextRound: Int? = null,
    @Json(name = "nextQuestion") val nextQuestion: Question? = null,
    @Json(name = "playerId") val playerId: String? = null,
    @Json(name = "nickname") val nickname: String? = null,
    @Json(name = "totalPlayers") val totalPlayers: Int? = null,
    @Json(name = "timedOut") val timedOut: Boolean? = null
)

@JsonClass(generateAdapter = true)
data class PlayerScore(
    @Json(name = "playerId") val playerId: String,
    @Json(name = "targetOption") val targetOption: String,
    @Json(name = "predictedCount") val predictedCount: Int,
    @Json(name = "actualCount") val actualCount: Int,
    @Json(name = "roundScore") val roundScore: Int
)

// ── UI State ──────────────────────────────────────────────────

data class Player(
    val playerId: String,
    val nickname: String,
    val isHost: Boolean = false
)

enum class GamePhase {
    WAITING, ANSWERING, PREDICTING, RESULT, FINISHED
}
