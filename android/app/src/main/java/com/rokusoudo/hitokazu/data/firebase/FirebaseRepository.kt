package com.rokusoudo.hitokazu.data.firebase

import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.rokusoudo.hitokazu.data.model.*
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
            val currentQuestion = roomData["currentQuestion"] as? Map<*, *>
            val options = (currentQuestion?.get("options") as? List<*>)?.map { it.toString() } ?: emptyList()
            val counts = options.associateWith { opt ->
                answers.documents.count { doc -> doc.getString("answer") == opt }
            }
            roomRef.update(
                mapOf(
                    "status" to "PREDICTING",
                    "answerCounts" to counts,
                )
            ).await()
        }
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

    // ── 次のラウンドへ進む（ホストが呼び出す） ─────────────
    @Suppress("UNCHECKED_CAST")
    suspend fun advanceToNextRound(roomId: String): Result<Unit> = runCatching {
        val roomRef = db.collection("rooms").document(roomId)
        val roomData = roomRef.get().await().data ?: error("ルームが見つかりません")

        val nextRound = (roomData["nextRound"] as? Long)?.toInt() ?: error("次のラウンド情報なし")
        val questionQueue = roomData["questionQueue"] as? List<Map<String, Any>>
        val nextQuestion = questionQueue?.getOrNull(nextRound - 1)?.let { q ->
            QuestionData(
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

        val scores = answers.mapNotNull { doc ->
            val data = doc.data ?: return@mapNotNull null
            if (!data.containsKey("prediction")) return@mapNotNull null
            val targetOption = data["targetOption"] as? String ?: return@mapNotNull null
            val actual = counts[targetOption] ?: 0
            val predicted = (data["prediction"] as? Long)?.toInt() ?: 0
            val score = calculateScore(actual, predicted)

            doc.reference.update("roundScore", score)

            mapOf(
                "playerId" to doc.id,
                "nickname" to (nicknameMap[doc.id] ?: ""),
                "targetOption" to targetOption,
                "predictedCount" to predicted,
                "actualCount" to actual,
                "roundScore" to score,
            )
        }.sortedByDescending { (it["roundScore"] as? Int) ?: 0 }

        val isLast = currentRound >= totalRounds

        if (isLast) {
            roomRef.update(
                mapOf(
                    "status" to "FINISHED",
                    "finalScores" to scores,
                    "finishedAt" to FieldValue.serverTimestamp(),
                )
            ).await()
        } else {
            roomRef.update(
                mapOf(
                    "status" to "RESULT",
                    "roundScores" to scores,
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

private data class QuestionData(
    val questionId: String,
    val text: String,
    val options: List<String>,
    val answerSeconds: Int = 30,
    val predictSeconds: Int = 20,
    val tags: List<String> = emptyList(),
) {
    fun toMap() = mapOf(
        "questionId" to questionId,
        "text" to text,
        "options" to options,
        "answerSeconds" to answerSeconds,
        "predictSeconds" to predictSeconds,
        "tags" to tags,
    )
}

private val QUESTIONS = listOf(
    // ── friend: アイスブレーキング ────────────────────────────
    QuestionData("f001", "自分は猫派だ（犬よりも猫が好き）", listOf("はい", "いいえ"), tags = listOf("friend")),
    QuestionData("f002", "自分の出身地は田舎だと思っている", listOf("はい", "いいえ"), tags = listOf("friend")),
    QuestionData("f003", "ディズニーよりジブリ派だ", listOf("はい", "いいえ"), tags = listOf("friend")),
    QuestionData("f004", "自分は朝型人間だと思う", listOf("はい", "いいえ"), tags = listOf("friend")),
    QuestionData("f005", "運転免許を持っている", listOf("はい", "いいえ"), tags = listOf("friend")),
    QuestionData("f006", "海外に行ったことがある", listOf("はい", "いいえ"), tags = listOf("friend")),
    QuestionData("f007", "泳ぐのが得意だ", listOf("はい", "いいえ"), tags = listOf("friend")),
    QuestionData("f008", "辛い食べ物が得意だ", listOf("はい", "いいえ"), tags = listOf("friend")),
    QuestionData("f009", "読書が好きだ", listOf("はい", "いいえ"), tags = listOf("friend")),
    QuestionData("f010", "自炊を週3回以上している", listOf("はい", "いいえ"), tags = listOf("friend")),
    QuestionData("f011", "スポーツを定期的にしている", listOf("はい", "いいえ"), tags = listOf("friend")),
    QuestionData("f012", "ゲームが好きだ", listOf("はい", "いいえ"), tags = listOf("friend")),
    QuestionData("f013", "SNSを毎日チェックする", listOf("はい", "いいえ"), tags = listOf("friend")),
    QuestionData("f014", "カラオケで歌うのが好きだ", listOf("はい", "いいえ"), tags = listOf("friend")),
    QuestionData("f015", "自分は方向音痴だと思う", listOf("はい", "いいえ"), tags = listOf("friend")),
    QuestionData("f016", "初対面の人とすぐ仲良くなれる方だ", listOf("はい", "いいえ"), tags = listOf("friend")),
    QuestionData("f017", "コーヒーよりお茶派だ", listOf("はい", "いいえ"), tags = listOf("friend")),
    QuestionData("f018", "映画を月1回以上見る", listOf("はい", "いいえ"), tags = listOf("friend")),
    QuestionData("f019", "犬を飼ったことがある", listOf("はい", "いいえ"), tags = listOf("friend")),
    QuestionData("f020", "一人旅をしたことがある", listOf("はい", "いいえ"), tags = listOf("friend")),
    // ── party: パーティー向け ─────────────────────────────────
    QuestionData("p001", "自分は右隣の人よりイケてると思う", listOf("はい", "いいえ"), tags = listOf("party")),
    QuestionData("p002", "このグループの中で一番早起きなのは自分だと思う", listOf("はい", "いいえ"), tags = listOf("party")),
    QuestionData("p003", "このメンバーの中で一番食べるのが速いのは自分だと思う", listOf("はい", "いいえ"), tags = listOf("party")),
    QuestionData("p004", "今日このメンバーの中で一番おしゃれをしてきたのは自分だと思う", listOf("はい", "いいえ"), tags = listOf("party")),
    QuestionData("p005", "このメンバーの中で一番歌が上手いのは自分だと思う", listOf("はい", "いいえ"), tags = listOf("party")),
    QuestionData("p006", "このグループで一番旅行好きなのは自分だと思う", listOf("はい", "いいえ"), tags = listOf("party")),
    QuestionData("p007", "このメンバーの中で一番方向音痴なのは自分だと思う", listOf("はい", "いいえ"), tags = listOf("party")),
    QuestionData("p008", "このメンバーの中で一番スマホを見る時間が長いのは自分だと思う", listOf("はい", "いいえ"), tags = listOf("party")),
    // ── deep: 仲が良い関係性向け・成人向け ───────────────────
    QuestionData("d001", "お酒で失敗したことがある", listOf("はい", "いいえ"), tags = listOf("deep")),
    QuestionData("d002", "告白したことがある（した側）", listOf("はい", "いいえ"), tags = listOf("deep")),
    QuestionData("d003", "バイトや仕事を無断でサボったことがある", listOf("はい", "いいえ"), tags = listOf("deep")),
    QuestionData("d004", "徹夜で遊んだことがある", listOf("はい", "いいえ"), tags = listOf("deep")),
    QuestionData("d005", "嘘の理由で欠席・欠勤したことがある", listOf("はい", "いいえ"), tags = listOf("deep")),
    QuestionData("d006", "二日酔いになったことがある", listOf("はい", "いいえ"), tags = listOf("deep")),
    QuestionData("d007", "初対面の人に一目惚れしたことがある", listOf("はい", "いいえ"), tags = listOf("deep")),
    QuestionData("d008", "酔った勢いで連絡してしまったことがある", listOf("はい", "いいえ"), tags = listOf("deep")),
)
