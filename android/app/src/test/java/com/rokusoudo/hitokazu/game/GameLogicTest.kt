package com.rokusoudo.hitokazu.game

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * [GameLogic] のユニットテスト（Issue #29 / #45）。
 *
 * 採点・累計スコア・ラウンドドキュメントIDの期待値は `shared/logic_vectors.json`
 * （Kotlin/JavaScript/Pythonの単一正本。Issue #45）から読み込み、このファイル内には
 * 期待値をハードコードしない。ケースを追加・変更したいときは shared/logic_vectors.json を
 * 編集する（このファイルではなく、そちらが正）。
 *
 * Gradle タスク `copyLogicVectors`（app/build.gradle.kts）が `shared/logic_vectors.json` を
 * `src/test/resources/` へコピーし、`testDebugUnitTest` 等の実行前に必ず走るようにしてある。
 * テストコードからは相対パスで直接読まず classpath リソースとして読む。Gradle のテスト実行時
 * カレントディレクトリは環境によって変わり得るため、CIで確実に動く方（リソース経由）を選んだ
 * （Issue #45 の未解決の質問への回答）。
 */
class GameLogicTest {

    private fun loadVectors(): JSONObject {
        val stream = javaClass.classLoader?.getResourceAsStream("logic_vectors.json")
            ?: error(
                "logic_vectors.json (test resource) が見つかりません。" +
                    "copyLogicVectors タスクが実行されているか確認してください " +
                    "(通常は ./gradlew testDebugUnitTest の依存関係として自動実行されます)。"
            )
        return JSONObject(stream.bufferedReader(Charsets.UTF_8).readText())
    }

    // ── 採点ロジック: score = max(0, 100 - |predicted - actual| × 20) ──

    @Test
    fun calculateScore_matchesSharedVectors() {
        val cases = loadVectors().getJSONArray("calculateScore")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val actual = c.getInt("actual")
            val predicted = c.getInt("predicted")
            val expected = c.getInt("expected")
            assertEquals(
                "calculateScore(actual=$actual, predicted=$predicted) [${c.optString("label")}]",
                expected,
                GameLogic.calculateScore(actual = actual, predicted = predicted),
            )
        }
    }

    // ── 累計スコアの加算 ──────────────────────────────────────

    @Test
    fun cumulativeTotal_matchesSharedVectors() {
        val cases = loadVectors().getJSONArray("cumulativeTotal")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val previousTotal = c.getInt("previousTotal")
            val roundScore = c.getInt("roundScore")
            val expected = c.getInt("expected")
            assertEquals(
                "cumulativeTotal(previousTotal=$previousTotal, roundScore=$roundScore) [${c.optString("label")}]",
                expected,
                GameLogic.cumulativeTotal(previousTotal = previousTotal, roundScore = roundScore),
            )
        }
    }

    @Test
    fun cumulativeTotal_fiveRoundsPerfectScore_matchesPerfectRoundScoreTimesFive() {
        // 5ラウンド満点の累計は「1ラウンド分のスコア×5」と一致するはず（期待値を数値で
        // ハードコードせず、calculateScore/cumulativeTotalの合成から導出する）。
        val perfectRoundScore = GameLogic.calculateScore(actual = 3, predicted = 3)
        var total = 0
        repeat(5) {
            total = GameLogic.cumulativeTotal(total, perfectRoundScore)
        }
        assertEquals(perfectRoundScore * 5, total)
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
    fun roundDocId_matchesSharedVectors() {
        val cases = loadVectors().getJSONArray("roundDocId")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val gameCount = c.getLong("gameCount")
            val round = c.getInt("round")
            val expected = c.getString("expected")
            assertEquals(
                "roundDocId(gameCount=$gameCount, round=$round)",
                expected,
                GameLogic.roundDocId(gameCount = gameCount, round = round),
            )
        }
    }

    @Test
    fun roundDocId_differsAcrossGameCounts_forSameRound() {
        val first = GameLogic.roundDocId(gameCount = 1L, round = 3)
        val second = GameLogic.roundDocId(gameCount = 2L, round = 3)
        assertNotEquals("同じroundでもgameCountが違えば別IDになるべき: $first vs $second", first, second)
    }
}
