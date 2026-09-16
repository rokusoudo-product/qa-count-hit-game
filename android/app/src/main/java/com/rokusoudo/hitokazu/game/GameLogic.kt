package com.rokusoudo.hitokazu.game

/**
 * 人数当てゲームのコアロジック（採点・累計）。
 *
 * Firestore（[com.rokusoudo.hitokazu.data.firebase.FirebaseRepository]）に依存しない
 * 純粋関数として切り出すことで、`android/app/src/test` でユニットテストできるようにする
 * （Issue #29）。以前は `FirebaseRepository.kt` 内の private トップレベル関数だったが、
 * CIでKotlinの退行（コンパイルエラー・採点結果の変化）を検知できるよう、このファイルへ
 * 移動しテスト可能な可視性にした。
 *
 * 採点式は `backend/functions/game_logic.py`（Python版・Web/Cloud Functionsが使用）と
 * 同一にすること。
 */
object GameLogic {

    /**
     * 1ルームに参加できる人数の上限（`docs/requirements.md` 3.1 / 4節）。
     * Web側（`backend/web/index.html` の `MAX_PLAYERS_PER_ROOM`）と同一の値にすること（Issue #50）。
     */
    const val MAX_PLAYERS_PER_ROOM = 20

    /**
     * ゲーム開始に必要な最低人数（`docs/user_stories.md` US-002）。
     * Web側（`backend/web/index.html` の `MIN_PLAYERS_TO_START`）と同一の値にすること（Issue #50）。
     */
    const val MIN_PLAYERS_TO_START = 2

    /**
     * 予測と実際の回答数の差から得点を計算する。
     * score = max(0, 100 - |predicted - actual| × 20)
     */
    fun calculateScore(actual: Int, predicted: Int): Int =
        maxOf(0, 100 - kotlin.math.abs(predicted - actual) * 20)

    /**
     * 前ラウンドまでの累計スコアに、今回のラウンドスコアを加算する。
     * （[FirebaseRepository.finalizeRound] が rounds/{gameCount}_{round}/answers の
     * totalScore を計算する処理と同一のロジック）
     */
    fun cumulativeTotal(previousTotal: Int, roundScore: Int): Int =
        previousTotal + roundScore

    /** rounds サブコレクションのドキュメントID。gameCount を含めることで、restartGame() 後の
     * 2ゲーム目が前ゲームの rounds/{round}/answers と衝突しないようにする（Issue #17）。
     */
    fun roundDocId(gameCount: Long, round: Int): String = "${gameCount}_${round}"
}
