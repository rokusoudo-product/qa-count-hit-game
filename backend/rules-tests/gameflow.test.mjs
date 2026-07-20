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
        currentQuestion: { questionId: 'q001', text: 'テスト質問', options: ['はい', 'いいえ'] },
      }),
    );
  });

  it('4) 全員が回答を送信できる', async () => {
    const answers = { [HOST]: 'はい', [P2]: 'いいえ', [P3]: 'はい' };
    for (const [uid, answer] of Object.entries(answers)) {
      const db = testEnv.authenticatedContext(uid).firestore();
      await assertSucceeds(
        setDoc(doc(db, `rooms/${ROOM}/rounds/1/answers/${uid}`), { answer }),
      );
    }
  });

  it('5) 最後の回答者が集計して PREDICTING へ遷移できる', async () => {
    // finalizeRound と同様、ホストとは限らないプレイヤーが実行する
    const db = testEnv.authenticatedContext(P3).firestore();
    const snap = await assertSucceeds(getDocs(collection(db, `rooms/${ROOM}/rounds/1/answers`)));
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
        updateDoc(doc(db, `rooms/${ROOM}/rounds/1/answers/${uid}`), {
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
        updateDoc(doc(db, `rooms/${ROOM}/rounds/1/answers/${uid}`), {
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
});
