package com.rokusoudo.hitokazu.game

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [GameLogic] のユニットテスト（Issue #29）。
 *
 * Firestoreに依存しない純粋関数のみを対象とし、`./gradlew testDebugUnitTest` で
 * Android SDK・エミュレータなしに実行できる。ケースは backend/test_logic.py の
 * TEST 1（採点ロジック）・TEST 8（累計スコア）と同一のものを最低限含める。
 */
class GameLogicTest {

    // ── 採点ロジック: score = max(0, 100 - |predicted - actual| × 20) ──

    @Test
    fun calculateScore_exactMatch_returns100() {
        assertEquals(100, GameLogic.calculateScore(actual = 5, predicted = 5))
    }

    @Test
    fun calculateScore_offByOne_returns80() {
        assertEquals(80, GameLogic.calculateScore(actual = 5, predicted = 4))
    }

    @Test
    fun calculateScore_offByTwo_returns60() {
        assertEquals(60, GameLogic.calculateScore(actual = 5, predicted = 3))
    }

    @Test
    fun calculateScore_offByThree_returns40() {
        assertEquals(40, GameLogic.calculateScore(actual = 5, predicted = 2))
    }

    @Test
    fun calculateScore_offByFour_returns20() {
        assertEquals(20, GameLogic.calculateScore(actual = 5, predicted = 1))
    }

    @Test
    fun calculateScore_offByFive_clampsToZero() {
        assertEquals(0, GameLogic.calculateScore(actual = 5, predicted = 0))
    }

    @Test
    fun calculateScore_overPrediction_isSymmetric() {
        // 差の絶対値なので、予測が実際より多くても同じ点数になる
        assertEquals(80, GameLogic.calculateScore(actual = 5, predicted = 6))
    }

    @Test
    fun calculateScore_farOverPrediction_clampsToZero() {
        assertEquals(0, GameLogic.calculateScore(actual = 5, predicted = 10))
    }

    @Test
    fun calculateScore_zeroActualExactMatch_returns100() {
        assertEquals(100, GameLogic.calculateScore(actual = 0, predicted = 0))
    }

    @Test
    fun calculateScore_zeroActualOffByOne_returns80() {
        assertEquals(80, GameLogic.calculateScore(actual = 0, predicted = 1))
    }

    @Test
    fun calculateScore_zeroActualFarOff_clampsToZero() {
        assertEquals(0, GameLogic.calculateScore(actual = 0, predicted = 5))
    }

    @Test
    fun calculateScore_boundaryJustAboveZero_isOnePoint() {
        // 差4.99... ではなく整数なので、差4がスコア0にならない境界（20点）を確認する
        assertEquals(20, GameLogic.calculateScore(actual = 20, predicted = 16))
    }

    @Test
    fun calculateScore_boundaryExactlyZero_atDiffFive() {
        // 差5でちょうど0点（max(0, 0)）になる境界
        assertEquals(0, GameLogic.calculateScore(actual = 20, predicted = 15))
    }

    // ── 累計スコアの加算 ──────────────────────────────────────

    @Test
    fun cumulativeTotal_addsRoundScoreToPreviousTotal() {
        assertEquals(180, GameLogic.cumulativeTotal(previousTotal = 100, roundScore = 80))
    }

    @Test
    fun cumulativeTotal_firstRound_startsFromZero() {
        assertEquals(100, GameLogic.cumulativeTotal(previousTotal = 0, roundScore = 100))
    }

    @Test
    fun cumulativeTotal_roundScoreZero_keepsPreviousTotal() {
        assertEquals(200, GameLogic.cumulativeTotal(previousTotal = 200, roundScore = 0))
    }

    @Test
    fun cumulativeTotal_fiveRoundsPerfectScore_accumulatesTo500() {
        var total = 0
        repeat(5) {
            val roundScore = GameLogic.calculateScore(actual = 3, predicted = 3)
            total = GameLogic.cumulativeTotal(total, roundScore)
        }
        assertEquals(500, total)
    }

    @Test
    fun cumulativeTotal_mixedRounds_matchesManualSum() {
        // ラウンド1: 差0→100点、ラウンド2: 差1→80点
        var total = GameLogic.cumulativeTotal(0, GameLogic.calculateScore(actual = 3, predicted = 3))
        total = GameLogic.cumulativeTotal(total, GameLogic.calculateScore(actual = 2, predicted = 3))
        assertEquals(180, total)
    }

    // ── ラウンドドキュメントID ────────────────────────────────

    @Test
    fun roundDocId_formatsAsGameCountUnderscoreRound() {
        assertEquals("1_1", GameLogic.roundDocId(gameCount = 1L, round = 1))
        assertEquals("2_1", GameLogic.roundDocId(gameCount = 2L, round = 1))
    }

    @Test
    fun roundDocId_differsAcrossGameCounts_forSameRound() {
        val first = GameLogic.roundDocId(gameCount = 1L, round = 3)
        val second = GameLogic.roundDocId(gameCount = 2L, round = 3)
        assert(first != second) { "同じroundでもgameCountが違えば別IDになるべき: $first vs $second" }
    }
}
