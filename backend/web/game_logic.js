/**
 * 人数当てゲーム - Web（ブラウザ）クライアント向けコアロジック
 *
 * Firestore に一切依存しない純粋関数のみを置く。以前はこれらの関数が `index.html` の
 * `<script type="module">` に直接埋め込まれており、テスト可能な形になっておらず自動テストが
 * 1件もなかった（Issue #45）。`index.html` からはこのファイルを `import` して使う。
 *
 * 採点式・累計スコアの加算方法は Android（`android/.../game/GameLogic.kt`）・
 * Cloud Functions（`backend/functions/game_logic.py`）と同一にすること。
 * 期待値（採点・集計・累計スコア）は `shared/logic_vectors.json` を単一正本とし、
 * Kotlin/JavaScript/Python の3実装のテストがそこから読み込んで検証する
 * （このファイルのテストは `backend/web/game_logic.test.mjs`。`node --test` で実行する）。
 */

// ─── 採点ロジック ─────────────────────────────────────────────

/**
 * 予測と実際の回答数の差から得点を計算する。
 * score = max(0, 100 - |predicted - actual| × 20)
 */
export function calculateScore(actual, predicted) {
  return Math.max(0, 100 - Math.abs(predicted - actual) * 20);
}

/**
 * 前ラウンドまでの累計スコアに、今回のラウンドスコアを加算する。
 */
export function cumulativeTotal(previousTotal, roundScore) {
  return previousTotal + roundScore;
}

// ─── フェーズ遷移ロジック ─────────────────────────────────────

/** 全員が回答済みかどうか（ANSWERING → PREDICTING）。 */
export function shouldTransitionToPredicting(answerCount, playerCount) {
  return answerCount >= playerCount;
}

/** 全員が予測済みかどうか（ラウンド確定）。 */
export function shouldFinalizeRound(predictionCount, playerCount) {
  return predictionCount >= playerCount;
}

/** ラウンド確定後の次のステータス（最終ラウンドなら FINISHED、それ以外は RESULT）。 */
export function getNextStatus(currentRound, totalRounds) {
  return currentRound >= totalRounds ? 'FINISHED' : 'RESULT';
}

// ─── 集計ロジック ─────────────────────────────────────────────

/** 回答の配列（例: ["はい","いいえ","はい"]）を選択肢ごとに集計する。 */
export function countAnswers(answers, options) {
  const counts = {};
  options.forEach(opt => { counts[opt] = 0; });
  answers.forEach(a => { if (a in counts) counts[a]++; });
  return counts;
}

/**
 * 予測リストと集計結果から各プレイヤーのラウンドスコアを計算し、降順に並べる。
 * predictions の各要素は { playerId, targetOption, predictedCount, ...任意の追加フィールド }。
 * 追加フィールド（nickname 等）はそのまま結果にコピーされる。
 * 同点の並び順は元の配列の順序を保つ（Array#sort は安定ソート）。
 */
export function finalizeRoundScores(predictions, answerCounts) {
  const results = predictions.map(p => {
    const actualCount = answerCounts[p.targetOption] || 0;
    const roundScore = calculateScore(actualCount, p.predictedCount);
    return { ...p, actualCount, roundScore };
  });
  return results.sort((a, b) => b.roundScore - a.roundScore);
}

/**
 * ラウンド確定後の cumulativeTotals を計算する。今回のラウンドで提出しなかった
 * プレイヤーは、前回までの累計を持っていても新しい cumulativeTotals にエントリを
 * 残さない（Android の FirebaseRepository.finalizeRound と同一仕様。Issue #27）。
 */
export function computeCumulativeTotals(previousTotals, roundResults) {
  const newTotals = {};
  roundResults.forEach(r => {
    newTotals[r.playerId] = cumulativeTotal(previousTotals[r.playerId] || 0, r.roundScore);
  });
  return newTotals;
}

/** 最終順位: totalScore の降順（同点は元の順序を保つ安定ソート）。 */
export function sortByTotalDesc(scores) {
  return [...scores].sort((a, b) => b.totalScore - a.totalScore);
}

// ─── ルームの保持期限（Issue #34） ────────────────────────────

/**
 * 指定時刻（省略時は現在時刻）から hours 時間後の Date を返す。
 * `now`（ミリ秒）を明示的に渡せるようにすることで、日時に依存しないテストができる。
 */
export function expireAtAfterHours(hours, now = Date.now()) {
  return new Date(now + hours * 60 * 60 * 1000);
}

// ─── ラウンドドキュメントID ─────────────────────────────────

/**
 * rounds サブコレクションのドキュメントID。gameCount を含めることで、restartGame() 後の
 * 2ゲーム目が前ゲームの rounds/{round}/answers と衝突しないようにする（Issue #17）。
 * Android（FirebaseRepository.roundDocId）・Python（round_doc_id）と同じ形式にすること。
 */
export function roundDocId(gameCount, round) {
  return `${gameCount || 1}_${round}`;
}
