/**
 * 実ゲームフローが新しい firestore.rules 下で通ることを検証する。
 *
 * test_game_flow.py は firebase-admin SDK を使うためルールを迂回してしまい、
 * ルール変更の影響を検出できない。こちらはクライアント SDK の権限で
 * FirebaseRepository.kt と同じ順序の操作を再現する。
 *
 * 実行方法（backend/ から）:
 *   npm --prefix rules-tests run test:emulator
 */
import { readFileSync } from 'node:fs';
import { after, before, describe, it } from 'node:test';
import assert from 'node:assert';
import { assertSucceeds, initializeTestEnvironment } from '@firebase/rules-unit-testing';
import { doc, getDoc, setDoc, updateDoc, collection, getDocs } from 'firebase/firestore';

const HOST = 'flow-host';
const P2 = 'flow-p2';
const P3 = 'flow-p3';
const ROOM = 'FLOWROOM';

let testEnv;

before(async () => {
  testEnv = await initializeTestEnvironment({
    projectId: 'hitokazu-game-flow-test',
    firestore: {
      rules: readFileSync(new URL('../firestore.rules', import.meta.url), 'utf8'),
    },
  });
  await testEnv.clearFirestore();
});

after(async () => {
  await testEnv?.cleanup();
});

describe('実ゲームフロー（クライアント権限・ルール適用下）', () => {
  it('1) ホストがルームを作成し、自分を players に登録できる', async () => {
    const db = testEnv.authenticatedContext(HOST).firestore();
    await assertSucceeds(
      setDoc(doc(db, `rooms/${ROOM}`), {
        hostName: 'ホスト太郎',
        hostUid: HOST,
        status: 'WAITING',
        currentRound: 0,
        totalRounds: 5,
        category: 'ALL',
      }),
    );
    await assertSucceeds(
      setDoc(doc(db, `rooms/${ROOM}/players/${HOST}`), { nickname: 'ホスト太郎', isHost: true }),
    );
  });

  it('2) 参加者が入室できる（存在確認→満員判定→登録）', async () => {
    for (const uid of [P2, P3]) {
      const db = testEnv.authenticatedContext(uid).firestore();
      // joinRoom() と同じ順序: ルーム読み → players 件数確認 → 自分を登録
      const room = await assertSucceeds(getDoc(doc(db, `rooms/${ROOM}`)));
      assert.strictEqual(room.data().status, 'WAITING');
      await assertSucceeds(
        setDoc(doc(db, `rooms/${ROOM}/players/${uid}`), { nickname: uid, isHost: false }),
      );
    }
    // 登録後は参加者として players 一覧を読める
    const db = testEnv.authenticatedContext(P2).firestore();
    const players = await assertSucceeds(getDocs(collection(db, `rooms/${ROOM}/players`)));
    assert.strictEqual(players.size, 3);
  });

  it('3) ホストがゲームを開始できる', async () => {
    const db = testEnv.authenticatedContext(HOST).firestore();
    await assertSucceeds(
      updateDoc(doc(db, `rooms/${ROOM}`), {
        status: 'ANSWERING',
        currentRound: 1,
        gameCount: 1,
        currentQuestion: { questionId: 'q001', text: 'テスト質問', options: ['はい', 'いいえ'] },
      }),
    );
  });

  // roundId は `{gameCount}_{round}`（Issue #17）。1ゲーム目・round=1 なので "1_1"。
  it('4) 全員が回答を送信できる', async () => {
    const answers = { [HOST]: 'はい', [P2]: 'いいえ', [P3]: 'はい' };
    for (const [uid, answer] of Object.entries(answers)) {
      const db = testEnv.authenticatedContext(uid).firestore();
      await assertSucceeds(
        setDoc(doc(db, `rooms/${ROOM}/rounds/1_1/answers/${uid}`), { answer }),
      );
    }
  });

  it('5) 最後の回答者が集計して PREDICTING へ遷移できる', async () => {
    // finalizeRound と同様、ホストとは限らないプレイヤーが実行する
    const db = testEnv.authenticatedContext(P3).firestore();
    const snap = await assertSucceeds(getDocs(collection(db, `rooms/${ROOM}/rounds/1_1/answers`)));
    assert.strictEqual(snap.size, 3);
    await assertSucceeds(
      updateDoc(doc(db, `rooms/${ROOM}`), {
        status: 'PREDICTING',
        answerCounts: { 'はい': 2, 'いいえ': 1 },
      }),
    );
  });

  it('6) 全員が予測を送信できる', async () => {
    const preds = { [HOST]: 2, [P2]: 1, [P3]: 3 };
    for (const [uid, prediction] of Object.entries(preds)) {
      const db = testEnv.authenticatedContext(uid).firestore();
      await assertSucceeds(
        updateDoc(doc(db, `rooms/${ROOM}/rounds/1_1/answers/${uid}`), {
          prediction,
          targetOption: 'はい',
        }),
      );
    }
  });

  it('7) 採点者が全員のスコアを書き込める（ホスト以外でも可）', async () => {
    // finalizeRound: 最後に予測したプレイヤー（P3）が全員分を書く
    const db = testEnv.authenticatedContext(P3).firestore();
    for (const [uid, score] of [[HOST, 100], [P2, 80], [P3, 80]]) {
      await assertSucceeds(
        updateDoc(doc(db, `rooms/${ROOM}/rounds/1_1/answers/${uid}`), {
          roundScore: score,
          totalScore: score,
        }),
      );
    }
    await assertSucceeds(
      updateDoc(doc(db, `rooms/${ROOM}`), {
        status: 'RESULT',
        roundScores: [{ playerId: HOST, roundScore: 100 }],
        nextRound: 2,
      }),
    );
  });

  it('8) 最終ラウンド後に FINISHED へ遷移できる', async () => {
    const db = testEnv.authenticatedContext(P3).firestore();
    await assertSucceeds(
      updateDoc(doc(db, `rooms/${ROOM}`), {
        status: 'FINISHED',
        finalScores: [{ playerId: HOST, totalScore: 100 }],
      }),
    );
    const room = await getDoc(doc(db, `rooms/${ROOM}`));
    assert.strictEqual(room.data().status, 'FINISHED');
  });

  // ── Issue #17: 再戦（restartGame） ──────────────────────────
  // FirebaseRepository.restartGame() / index.html の restartGame() と同じ操作を
  // クライアント権限で再現する。ホストが「もう一度遊ぶ」を押した想定。
  it('9) ホストが再戦できる（FINISHED → WAITING、gameCountをインクリメント）', async () => {
    const db = testEnv.authenticatedContext(HOST).firestore();
    await assertSucceeds(
      updateDoc(doc(db, `rooms/${ROOM}`), {
        status: 'WAITING',
        currentRound: 0,
        gameCount: 2,
        currentQuestion: null,
        answerCounts: {},
        roundScores: [],
        finalScores: [],
        cumulativeTotals: {},
      }),
    );
    const room = await getDoc(doc(db, `rooms/${ROOM}`));
    assert.strictEqual(room.data().status, 'WAITING');
    assert.strictEqual(room.data().gameCount, 2);
    assert.strictEqual(room.data().currentRound, 0);
  });

  it('10) 再戦後の2ゲーム目は round=1 でも前ゲームの回答と衝突しない（roundId に gameCount を含む）', async () => {
    // 1ゲーム目の rounds/1_1/answers はテスト4で作成済み。2ゲーム目は rounds/2_1 を使うため、
    // 同じ round=1 でも「すでに回答済みです」に相当する衝突が起きないことを確認する。
    const hostDb = testEnv.authenticatedContext(HOST).firestore();
    await assertSucceeds(
      updateDoc(doc(hostDb, `rooms/${ROOM}`), {
        status: 'ANSWERING',
        currentRound: 1,
        currentQuestion: { questionId: 'q002', text: 'テスト質問2', options: ['はい', 'いいえ'] },
      }),
    );

    const answers = { [HOST]: 'はい', [P2]: 'いいえ', [P3]: 'はい' };
    for (const [uid, answer] of Object.entries(answers)) {
      const db = testEnv.authenticatedContext(uid).firestore();
      // 1ゲーム目と同じ round=1 だが gameCount=2 のドキュメントへ書く
      await assertSucceeds(
        setDoc(doc(db, `rooms/${ROOM}/rounds/2_1/answers/${uid}`), { answer }),
      );
    }

    // 1ゲーム目の rounds/1_1/answers はそのまま残っている（削除しない仕様）
    const hostReadDb = testEnv.authenticatedContext(HOST).firestore();
    const oldGameAnswers = await assertSucceeds(
      getDocs(collection(hostReadDb, `rooms/${ROOM}/rounds/1_1/answers`)),
    );
    assert.strictEqual(oldGameAnswers.size, 3, '1ゲーム目のデータは削除されずに残る');

    const newGameAnswers = await assertSucceeds(
      getDocs(collection(hostReadDb, `rooms/${ROOM}/rounds/2_1/answers`)),
    );
    assert.strictEqual(newGameAnswers.size, 3, '2ゲーム目は独立したドキュメントに書き込める');
  });
});
