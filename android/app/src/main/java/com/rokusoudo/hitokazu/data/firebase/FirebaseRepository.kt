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
    suspend fun createRoom(hostName: String): Result<JoinRoomResponse> = runCatching {
        ensureSignedIn()
        val uid = auth.currentUser!!.uid
        val roomId = generateRoomId()

        db.collection("rooms").document(roomId).set(
            mapOf(
                "hostName" to hostName,
                "hostUid" to uid,
                "status" to "WAITING",
                "currentRound" to 0,
                "totalRounds" to DEFAULT_ROUNDS,
                "currentQuestion" to null,
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
        val shuffled = QUESTIONS.shuffled().take(DEFAULT_ROUNDS)
        val questionList = shuffled.map { it.toMap() }
        db.collection("rooms").document(roomId).update(
            mapOf(
                "status" to "ANSWERING",
                "currentRound" to 1,
                "totalRounds" to shuffled.size,
                "questionList" to questionList,
                "currentQuestion" to shuffled[0].toMap(),
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
    suspend fun advanceToNextRound(roomId: String): Result<Unit> = runCatching {
        val roomRef = db.collection("rooms").document(roomId)
        val roomData = roomRef.get().await().data ?: error("ルームが見つかりません")

        val nextRound = (roomData["nextRound"] as? Long)?.toInt() ?: error("次のラウンド情報なし")

        @Suppress("UNCHECKED_CAST")
        val questionList = roomData["questionList"] as? List<Map<String, Any>>
            ?: error("問題リストが見つかりません")
        val nextQuestionMap = questionList[nextRound - 1]

        roomRef.update(
            mapOf(
                "status" to "ANSWERING",
                "currentRound" to nextRound,
                "currentQuestion" to nextQuestionMap,
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

private const val DEFAULT_ROUNDS = 5

private data class QuestionData(
    val questionId: String,
    val text: String,
    val options: List<String>,
    val tags: List<String> = emptyList(),
    val answerSeconds: Int = 30,
    val predictSeconds: Int = 20,
) {
    fun toMap() = mapOf(
        "questionId" to questionId,
        "text" to text,
        "options" to options,
        "tags" to tags,
        "answerSeconds" to answerSeconds,
        "predictSeconds" to predictSeconds,
    )
}

private val QUESTIONS = listOf(
    // friend: アイスブレーキング
    QuestionData("q001", "自分は猫派だ", listOf("はい", "いいえ"), tags = listOf("friend")),
    QuestionData("q002", "出身地は田舎だと思っている", listOf("はい", "いいえ"), tags = listOf("friend")),
    QuestionData("q003", "ディズニーよりジブリ派だ", listOf("はい", "いいえ"), tags = listOf("friend")),
    QuestionData("q004", "朝型人間だと思う", listOf("はい", "いいえ"), tags = listOf("friend")),
    QuestionData("q005", "犬を飼ったことがある", listOf("はい", "いいえ"), tags = listOf("friend")),
    QuestionData("q006", "運転免許を持っている", listOf("はい", "いいえ"), tags = listOf("friend")),
    QuestionData("q007", "海外に行ったことがある", listOf("はい", "いいえ"), tags = listOf("friend")),
    QuestionData("q008", "コーヒーを毎日飲む", listOf("はい", "いいえ"), tags = listOf("friend")),
    QuestionData("q009", "辛い食べ物が好きだ", listOf("はい", "いいえ"), tags = listOf("friend")),
    QuestionData("q010", "カラオケは得意なほうだ", listOf("はい", "いいえ"), tags = listOf("friend")),
    QuestionData("q011", "旅行は計画をたてる派だ", listOf("はい", "いいえ"), tags = listOf("friend")),
    QuestionData("q012", "朝ごはんを毎日食べる", listOf("はい", "いいえ"), tags = listOf("friend")),
    QuestionData("q013", "SNSを毎日チェックする", listOf("はい", "いいえ"), tags = listOf("friend")),
    QuestionData("q014", "スポーツ観戦が好きだ", listOf("はい", "いいえ"), tags = listOf("friend")),
    QuestionData("q015", "映画より本派だ", listOf("はい", "いいえ"), tags = listOf("friend")),
    QuestionData("q016", "甘いものより辛いものが好きだ", listOf("はい", "いいえ"), tags = listOf("friend")),
    QuestionData("q017", "ひとり旅をしたことがある", listOf("はい", "いいえ"), tags = listOf("friend")),
    QuestionData("q018", "音楽を聴きながら作業する派だ", listOf("はい", "いいえ"), tags = listOf("friend")),
    // party: パーティー向け
    QuestionData("q019", "自分は右隣の人よりイケてると思う", listOf("はい", "いいえ"), tags = listOf("party")),
    QuestionData("q020", "このメンバー内で一番テンションが高いと思う", listOf("はい", "いいえ"), tags = listOf("party")),
    QuestionData("q021", "今夜一番盛り上げるのは自分だと思う", listOf("はい", "いいえ"), tags = listOf("party")),
    QuestionData("q022", "このメンバー内で一番おしゃれだと思う", listOf("はい", "いいえ"), tags = listOf("party")),
    QuestionData("q023", "このメンバーの中で一番遅刻しがちだと思う", listOf("はい", "いいえ"), tags = listOf("party")),
    QuestionData("q024", "今日の主役は自分だと思う", listOf("はい", "いいえ"), tags = listOf("party")),
    QuestionData("q025", "このメンバーの中で一番食べると思う", listOf("はい", "いいえ"), tags = listOf("party")),
    QuestionData("q026", "このメンバーの中で一番声が大きいと思う", listOf("はい", "いいえ"), tags = listOf("party")),
    // deep: 仲が良い関係性向け・成人向け
    QuestionData("q027", "お酒で失敗したことがある", listOf("はい", "いいえ"), tags = listOf("deep")),
    QuestionData("q028", "人に言えない趣味がある", listOf("はい", "いいえ"), tags = listOf("deep")),
    QuestionData("q029", "徹夜したことが10回以上ある", listOf("はい", "いいえ"), tags = listOf("deep")),
    QuestionData("q030", "人に絶対言えない秘密がある", listOf("はい", "いいえ"), tags = listOf("deep")),
    QuestionData("q031", "恋人や好きな人に嘘をついたことがある", listOf("はい", "いいえ"), tags = listOf("deep")),
    QuestionData("q032", "人生で大きく後悔していることがある", listOf("はい", "いいえ"), tags = listOf("deep")),
    QuestionData("q033", "泣いた映画・ドラマがある", listOf("はい", "いいえ"), tags = listOf("deep")),
    QuestionData("q034", "今の仕事・学業に満足している", listOf("はい", "いいえ"), tags = listOf("deep")),
    QuestionData("q035", "一目惚れをしたことがある", listOf("はい", "いいえ"), tags = listOf("deep")),
)
