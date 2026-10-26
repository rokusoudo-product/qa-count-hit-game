// 人数当てゲーム - Web ゲームロジックのユニットテスト（Issue #45）
//
// 採点・集計・累計スコアの期待値は shared/logic_vectors.json（単一正本。Kotlin/Python と共有）
// から読み込む。このファイル自体に期待値をハードコードしない。
//
// 実行方法:
//   node --test backend/web/game_logic.test.mjs
//   （または cd backend/web && npm test）
//
// Node 標準の test runner（node:test）を使う。backend/rules-tests/ が既に同じ構成
// （node --test + .mjs）を採用しており、新規の依存追加を避けられるため踏襲した。

import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

import {
  calculateScore,
  cumulativeTotal,
  shouldTransitionToPredicting,
  shouldFinalizeRound,
  getNextStatus,
  countAnswers,
  finalizeRoundScores,
  computeCumulativeTotals,
  sortByTotalDesc,
  expireAtAfterHours,
  roundDocId,
} from './game_logic.js';

const __dirname = dirname(fileURLToPath(import.meta.url));
// CWDに依存しないよう、このテストファイルからの相対パスで shared/logic_vectors.json を解決する
// （リポジトリ内の位置 backend/web/ からは ../../shared/ が単一正本の場所）。
const VECTORS_PATH = join(__dirname, '..', '..', 'shared', 'logic_vectors.json');
const vectors = JSON.parse(readFileSync(VECTORS_PATH, 'utf-8'));

describe('calculateScore（採点式の境界値）', () => {
  for (const c of vectors.calculateScore) {
    test(`actual=${c.actual}, predicted=${c.predicted} → ${c.expected}点 [${c.label}]`, () => {
      assert.equal(calculateScore(c.actual, c.predicted), c.expected);
    });
  }
});

describe('cumulativeTotal（累計スコアの加算）', () => {
  for (const c of vectors.cumulativeTotal) {
    test(`previousTotal=${c.previousTotal}, roundScore=${c.roundScore} → ${c.expected} [${c.label}]`, () => {
      assert.equal(cumulativeTotal(c.previousTotal, c.roundScore), c.expected);
    });
  }
});

describe('countAnswers（回答集計）', () => {
  for (const c of vectors.countAnswers) {
    test(c.label, () => {
      assert.deepEqual(countAnswers(c.answers, c.options), c.expected);
    });
  }
});

describe('finalizeRoundScores（ラウンド確定時の採点・並べ替え）', () => {
  for (const c of vectors.finalizeRoundScores) {
    test(c.label, () => {
      const result = finalizeRoundScores(c.predictions, c.answerCounts);
      const simplified = result.map(r => ({
        playerId: r.playerId,
        actualCount: r.actualCount,
        roundScore: r.roundScore,
      }));
      assert.deepEqual(simplified, c.expected);
    });
  }
});

describe('computeCumulativeTotals（未提出プレイヤーの扱い。Issue #27）', () => {
  for (const c of vectors.cumulativeTotalsAfterRound) {
    test(c.label, () => {
      assert.deepEqual(computeCumulativeTotals(c.previousTotals, c.roundResults), c.expected);
    });
  }
});

describe('sortByTotalDesc（最終順位の並べ替え）', () => {
  for (const c of vectors.sortFinalScoresByTotal) {
    test(c.label, () => {
      const order = sortByTotalDesc(c.scores).map(s => s.playerId);
      assert.deepEqual(order, c.expectedOrder);
    });
  }
});

describe('roundDocId（rounds サブコレクションのドキュメントID）', () => {
  for (const c of vectors.roundDocId) {
    test(`gameCount=${c.gameCount}, round=${c.round} → "${c.expected}"`, () => {
      assert.equal(roundDocId(c.gameCount, c.round), c.expected);
    });
  }

  test('gameCountが違えば同じroundでも別IDになる', () => {
    assert.notEqual(roundDocId(1, 3), roundDocId(2, 3));
  });
});

describe('expireAtAfterHours（ルームの保持期限。Issue #34）', () => {
  for (const c of vectors.expireAtAfterHours) {
    test(`hours=${c.hours} [${c.label}]`, () => {
      const result = expireAtAfterHours(c.hours, c.nowMs);
      assert.equal(result.getTime(), c.expectedMs);
    });
  }
});

// ── フェーズ遷移判定（採点・集計ではないためベクタ化せず、直接ケースを書く。
//    backend/test_logic.py の TEST3・TEST4 と同一のケース） ──────────────
describe('フェーズ遷移判定', () => {
  test('全員回答 → PREDICTINGへ遷移する', () => {
    assert.equal(shouldTransitionToPredicting(4, 4), true);
  });
  test('未回答あり → 遷移しない', () => {
    assert.equal(shouldTransitionToPredicting(3, 4), false);
  });
  test('全員予測 → ラウンドを確定する', () => {
    assert.equal(shouldFinalizeRound(4, 4), true);
  });
  test('未予測あり → 確定しない', () => {
    assert.equal(shouldFinalizeRound(3, 4), false);
  });
  test('最終ラウンド → FINISHED', () => {
    assert.equal(getNextStatus(5, 5), 'FINISHED');
  });
  test('途中ラウンド → RESULT', () => {
    assert.equal(getNextStatus(3, 5), 'RESULT');
  });
});
