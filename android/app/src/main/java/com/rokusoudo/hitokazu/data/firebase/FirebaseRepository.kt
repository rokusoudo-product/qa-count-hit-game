package com.rokusoudo.hitokazu.data.firebase

import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.functions.ktx.functions
import com.google.firebase.ktx.Firebase
import com.rokusoudo.hitokazu.data.model.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class FirebaseRepository {

    private val db = Firebase.firestore
    private val functions = Firebase.functions("us-central1")

    // ── ルーム作成 ──────────────────────────────────────────
    suspend fun createRoom(hostName: String): Result<String> = runCatching {
        val result = functions
            .getHttpsCallable("create_room")
            .call(mapOf("hostName" to hostName))
            .await()
        @Suppress("UNCHECKED_CAST")
        val data = result.getData<Map<String, Any>>()
        data?.get("roomId") as? String ?: error("roomId missing")
    }

    // ── ルーム参加 ──────────────────────────────────────────
    suspend fun joinRoom(roomId: String, nickname: String): Result<JoinRoomResponse> = runCatching {
        val result = functions
            .getHttpsCallable("join_room")
            .call(mapOf("roomId" to roomId, "nickname" to nickname))
            .await()
        @Suppress("UNCHECKED_CAST")
        val data = result.getData<Map<String, Any>>() ?: error("empty response")
        JoinRoomResponse(
            playerId = data["playerId"] as String,
            roomId = data["roomId"] as String,
            nickname = data["nickname"] as String,
        )
    }

    // ── ゲーム開始 ──────────────────────────────────────────
    suspend fun startGame(roomId: String): Result<Unit> = runCatching {
        functions
            .getHttpsCallable("start_game")
            .call(mapOf("roomId" to roomId))
            .await()
        Unit
    }

    // ── 回答送信 ────────────────────────────────────────────
    suspend fun submitAnswer(roomId: String, playerId: String, answer: String): Result<Unit> = runCatching {
        functions
            .getHttpsCallable("submit_answer")
            .call(mapOf("roomId" to roomId, "playerId" to playerId, "answer" to answer))
            .await()
        Unit
    }

    // ── 予測送信 ────────────────────────────────────────────
    suspend fun submitPrediction(
        roomId: String,
        playerId: String,
        targetOption: String,
        predictedCount: Int,
    ): Result<Unit> = runCatching {
        functions
            .getHttpsCallable("submit_prediction")
            .call(mapOf(
                "roomId" to roomId,
                "playerId" to playerId,
                "targetOption" to targetOption,
                "predictedCount" to predictedCount,
            ))
            .await()
        Unit
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
