package com.rokusoudo.hitokazu.data.firebase

import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.rokusoudo.hitokazu.data.model.*
import com.rokusoudo.hitokazu.data.questions.QUESTIONS
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class FirebaseRepository {

    private val db = Firebase.firestore
    private val auth = Firebase.auth

    private suspend fun ensureSignedIn() {
        if (auth.currentUser == null) {
            auth.signInAnonymously().await()
        }
    }

    private fun generateRoomId(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..8).map { chars.random() }.joinToString("")
    }

    // ── ルーム作成 ──────────────────────────────────────────
    suspend fun createRoom(hostName: String, category: QuestionCategory = QuestionCategory.ALL): Result<JoinRoomResponse> = runCatching {
        ensureSignedIn()
        val uid = auth.currentUser!!.uid
        val roomId = generateRoomId()

        db.collection("rooms").document(roomId).set(
            mapOf(
                "hostName" to hostName,
                "hostUid" to uid,
                "status" to "WAITING",
                "currentRound" to 0,
                "totalRounds" to TOTAL_ROUNDS_PER_GAME,
                "currentQuestion" to null,
                "category" to category.name,
                "createdAt" to FieldValue.serverTimestamp(),
            )
        ).await()

        db.collection("rooms").document(roomId)
            .collection("players").document(uid).set(
                mapOf(
                    "nickname" to hostName,
                    "isHost" to true,
                    "joinedAt" to FieldValue.serverTimestamp(),
                )
            ).await()

        JoinRoomResponse(playerId = uid, roomId = roomId, nickname = hostName)
    }

    // ── ルーム参加 ──────────────────────────────────────────
    suspend fun joinRoom(roomId: String, nickname: String): Result<JoinRoomResponse> = runCatching {
        ensureSignedIn()
        val uid = auth.currentUser!!.uid

        val roomRef = db.collection("rooms").document(roomId)
        val room = roomRef.get().await()

        if (!room.exists()) error("ルームが見つかりません")

        val roomData = room.data ?: error("ルームが見つかりません")
        if (roomData["status"] != "WAITING") error("ゲームはすでに開始されています")

        val players = roomRef.collection("players").get().await()
        if (players.size() >= 20) error("ルームが満員です")

        roomRef.collection("players").document(uid).set(
            mapOf(
                "nickname" to nickname,
                "isHost" to false,
                "joinedAt" to FieldValue.serverTimestamp(),
            )
        ).await()

        JoinRoomResponse(playerId = uid, roomId = roomId, nickname = nickname)
    }

    // ── ゲーム開始 ──────────────────────────────────────────
    suspend fun startGame(roomId: String): Result<Unit> = runCatching {
        val roomSnap = db.collection("rooms").document(roomId).get().await()
        val categoryStr = roomSnap.getString("category")
        val category = QuestionCategory.fromString(categoryStr)

        val filtered = QUESTIONS.filter { q -> q.tags.any { it in category.tags } }
        val pool = if (filtered.isEmpty()) QUESTIONS else filtered
        val questionQueue = pool.shuffled().take(TOTAL_ROUNDS_PER_GAME)
        val firstQuestion = questionQueue[0]
        db.collection("rooms").document(roomId).update(
            mapOf(
                "status" to "ANSWERING",
                "currentRound" to 1,
                "totalRounds" to questionQueue.size,
                "questionQueue" to questionQueue.map { it.toMap() },
                "currentQuestion" to firstQuestion.toMap(),
                "startedAt" to FieldValue.serverTimestamp(),
                "phaseStartedAt" to FieldValue.serverTimestamp(),
            )
        ).await()
    }

    // ── 回答送信 ────────────────────────────────────────────
    suspend fun submitAnswer(roomId: String, playerId: String, answer: String): Result<Unit> = runCatching {
        val roomRef = db.collection("rooms").document(roomId)
        val roomSnap = roomRef.get().await()
        val roomData = roomSnap.data ?: error("ルームが見つかりません")

        if (roomData["status"] != "ANSWERING") error("回答フェーズではありません")

        val currentRound = (roomData["currentRound"] as? Long)?.toInt() ?: error("ラウンド情報なし")
        val roundRef = roomRef.collection("rounds").document(currentRound.toString())
        val answerRef = roundRef.collection("answers").document(playerId)

        if (answerRef.get().await().exists()) error("すでに回答済みです")

        answerRef.set(
            mapOf(
                "answer" to answer,
                "answeredAt" to FieldValue.serverTimestamp(),
            )
        ).await()

        // 全員回答したか確認
        val players = roomRef.collection("players").get().await()
        val answers = roundRef.collection("answers").get().await()

        if (answers.size() >= players.size()) {
            advanceToPredicting(roomRef, roomData, answers)
        }
    }

    // ── 回答フェーズのタイムアウト強制確定（ホスト端末が呼び出す） ──
    // answerSeconds + 猶予秒数が経過しても未提出者がいる場合、提出済みの回答だけで
    // PREDICTING へ遷移させる。すでに全員回答済みで遷移済みの場合は何もしない。
    suspend fun forceAdvanceFromAnswering(roomId: String): Result<Unit> = runCatching {
        val roomRef = db.collection("rooms").document(roomId)
        val roomSnap = roomRef.get().await()
        val roomData = roomSnap.data ?: return@runCatching

        if (roomData["status"] != "ANSWERING") return@runCatching // すでに遷移済み

        val currentRound = (roomData["currentRound"] as? Long)?.toInt() ?: return@runCatching
        val roundRef = roomRef.collection("rounds").document(currentRound.toString())
        val answers = roundRef.collection("answers").get().await()

        advanceToPredicting(roomRef, roomData, answers)
    }

    private suspend fun advanceToPredicting(
        roomRef: com.google.firebase.firestore.DocumentReference,
        roomData: Map<String, Any>,
        answers: com.google.firebase.firestore.QuerySnapshot,
    ) {
        val currentQuestion = roomData["currentQuestion"] as? Map<*, *>
        val options = (currentQuestion?.get("options") as? List<*>)?.map { it.toString() } ?: emptyList()
        val counts = options.associateWith { opt ->
            answers.documents.count { doc -> doc.getString("answer") == opt }
        }
        roomRef.update(
            mapOf(
                "status" to "PREDICTING",
                "answerCounts" to counts,
                "phaseStartedAt" to FieldValue.serverTimestamp(),
            )
        ).await()
    }

    // ── 予測送信 ────────────────────────────────────────────
    suspend fun submitPrediction(
        roomId: String,
        playerId: String,
        targetOption: String,
        predictedCount: Int,
    ): Result<Unit> = runCatching {
        val roomRef = db.collection("rooms").document(roomId)
        val roomSnap = roomRef.get().await()
        val roomData = roomSnap.data ?: error("ルームが見つかりません")

        if (roomData["status"] != "PREDICTING") error("予測フェーズではありません")

        val currentRound = (roomData["currentRound"] as? Long)?.toInt() ?: error("ラウンド情報なし")
        val roundRef = roomRef.collection("rounds").document(currentRound.toString())
        val answerRef = roundRef.collection("answers").document(playerId)

        answerRef.update(
            mapOf(
                "prediction" to predictedCount,
                "targetOption" to targetOption,
                "predictedAt" to FieldValue.serverTimestamp(),
            )
        ).await()

        // 全員予測したか確認
        val players = roomRef.collection("players").get().await()
        val answers = roundRef.collection("answers").get().await()
        val predicted = answers.documents.filter { it.contains("prediction") }

        if (predicted.size >= players.size()) {
            finalizeRound(roomRef, roomData, currentRound, answers.documents)
        }
    }

    // ── 予測フェーズのタイムアウト強制確定（ホスト端末が呼び出す） ──
    // predictSeconds + 猶予秒数が経過しても未提出者がいる場合、提出済みの予測だけで
    // ラウンドを確定させる。すでに全員予測済みで確定済みの場合は何もしない。
    suspend fun forceFinalizeFromPredicting(roomId: String): Result<Unit> = runCatching {
        val roomRef = db.collection("rooms").document(roomId)
        val roomSnap = roomRef.get().await()
        val roomData = roomSnap.data ?: return@runCatching

        if (roomData["status"] != "PREDICTING") return@runCatching // すでに確定済み

        val currentRound = (roomData["currentRound"] as? Long)?.toInt() ?: return@runCatching
        val roundRef = roomRef.collection("rounds").document(currentRound.toString())
        val answers = roundRef.collection("answers").get().await()

        finalizeRound(roomRef, roomData, currentRound, answers.documents)
    }

    // ── 次のラウンドへ進む（ホストが呼び出す） ─────────────
    @Suppress("UNCHECKED_CAST")
    suspend fun advanceToNextRound(roomId: String): Result<Unit> = runCatching {
        val roomRef = db.collection("rooms").document(roomId)
        val roomData = roomRef.get().await().data ?: error("ルームが見つかりません")

        val nextRound = (roomData["nextRound"] as? Long)?.toInt() ?: error("次のラウンド情報なし")
        val questionQueue = roomData["questionQueue"] as? List<Map<String, Any>>
        val nextQuestion = questionQueue?.getOrNull(nextRound - 1)?.let { q ->
            Question(
                questionId = q["questionId"] as? String ?: "",
                text = q["text"] as? String ?: "",
                options = (q["options"] as? List<*>)?.map { it.toString() } ?: emptyList(),
                answerSeconds = (q["answerSeconds"] as? Long)?.toInt() ?: 30,
                predictSeconds = (q["predictSeconds"] as? Long)?.toInt() ?: 20,
                tags = (q["tags"] as? List<*>)?.map { it.toString() } ?: emptyList(),
            )
        } ?: QUESTIONS.getOrElse(nextRound - 1) { QUESTIONS[0] }

        roomRef.update(
            mapOf(
                "status" to "ANSWERING",
                "currentRound" to nextRound,
                "currentQuestion" to nextQuestion.toMap(),
                "answerCounts" to emptyMap<String, Int>(),
                "roundScores" to emptyList<Any>(),
                "phaseStartedAt" to FieldValue.serverTimestamp(),
            )
        ).await()
    }

    private suspend fun finalizeRound(
        roomRef: com.google.firebase.firestore.DocumentReference,
        roomData: Map<String, Any>,
        currentRound: Int,
        answers: List<com.google.firebase.firestore.DocumentSnapshot>,
    ) {
        val counts = (roomData["answerCounts"] as? Map<*, *>)
            ?.mapKeys { it.key.toString() }
            ?.mapValues { (it.value as? Long)?.toInt() ?: 0 }
            ?: emptyMap()
        val totalRounds = (roomData["totalRounds"] as? Long)?.toInt() ?: QUESTIONS.size

        // ニックネームを取得
        val playersSnap = roomRef.collection("players").get().await()
        val nicknameMap = playersSnap.documents.associate { it.id to (it.getString("nickname") ?: "") }

        // 前ラウンドまでの累計スコアを取得
        @Suppress("UNCHECKED_CAST")
        val prevTotals: Map<String, Int> = (roomData["cumulativeTotals"] as? Map<String, Any>)
            ?.mapValues { (it.value as? Long)?.toInt() ?: 0 }
            ?: emptyMap()

        val scores = answers.mapNotNull { doc ->
            val data = doc.data ?: return@mapNotNull null
            if (!data.containsKey("prediction")) return@mapNotNull null
            val targetOption = data["targetOption"] as? String ?: return@mapNotNull null
            val actual = counts[targetOption] ?: 0
            val predicted = (data["prediction"] as? Long)?.toInt() ?: 0
            val roundScore = calculateScore(actual, predicted)
            val totalScore = (prevTotals[doc.id] ?: 0) + roundScore

            doc.reference.update(mapOf("roundScore" to roundScore, "totalScore" to totalScore))

            mapOf(
                "playerId" to doc.id,
                "nickname" to (nicknameMap[doc.id] ?: ""),
                "targetOption" to targetOption,
                "predictedCount" to predicted,
                "actualCount" to actual,
                "roundScore" to roundScore,
                "totalScore" to totalScore,
            )
        }

        val newTotals = scores.associate {
            (it["playerId"] as String) to (it["totalScore"] as Int)
        }

        val isLast = currentRound >= totalRounds

        if (isLast) {
            val sortedByTotal = scores.sortedByDescending { (it["totalScore"] as? Int) ?: 0 }
            roomRef.update(
                mapOf(
                    "status" to "FINISHED",
                    "finalScores" to sortedByTotal,
                    "cumulativeTotals" to newTotals,
                    "finishedAt" to FieldValue.serverTimestamp(),
                )
            ).await()
        } else {
            val sortedByRound = scores.sortedByDescending { (it["roundScore"] as? Int) ?: 0 }
            roomRef.update(
                mapOf(
                    "status" to "RESULT",
                    "roundScores" to sortedByRound,
                    "cumulativeTotals" to newTotals,
                    "nextRound" to currentRound + 1,
                )
            ).await()
        }
    }

    // ── ルーム状態をリアルタイム監視（Firestoreリスナー） ──
    fun observeRoom(roomId: String): Flow<RoomSnapshot> = callbackFlow {
        val listener = db.collection("rooms").document(roomId)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener
                val data = snapshot.data ?: return@addSnapshotListener
                trySend(RoomSnapshot.fromMap(data))
            }
        awaitClose { listener.remove() }
    }

    // ── プレイヤー一覧をリアルタイム監視 ───────────────────
    fun observePlayers(roomId: String): Flow<List<Player>> = callbackFlow {
        val listener = db.collection("rooms").document(roomId)
            .collection("players")
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener
                val players = snapshot.documents.map { doc ->
                    Player(
                        playerId = doc.id,
                        nickname = doc.getString("nickname") ?: "",
                        isHost = doc.getBoolean("isHost") ?: false,
                    )
                }
                trySend(players)
            }
        awaitClose { listener.remove() }
    }
}

private fun calculateScore(actual: Int, predicted: Int): Int =
    maxOf(0, 100 - kotlin.math.abs(predicted - actual) * 20)

private const val TOTAL_ROUNDS_PER_GAME = 5
