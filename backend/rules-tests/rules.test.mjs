/**
 * firestore.rules のユニットテスト。
 *
 * 実行方法（backend/ から）:
 *   npm --prefix rules-tests install
 *   npm --prefix rules-tests run test:emulator
 *
 * 検証方針: ルームの参加者だけがそのルーム配下を読み書きでき、
 * 無関係な第三者（匿名認証は通っている）は操作できないこと。
 */
import { readFileSync } from 'node:fs';
import { after, before, beforeEach, describe, it } from 'node:test';
import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} from '@firebase/rules-unit-testing';
import { doc, getDoc, setDoc, updateDoc, deleteDoc, collection, getDocs } from 'firebase/firestore';

const HOST = 'host-uid';
const PLAYER = 'player-uid';
const OUTSIDER = 'outsider-uid'; // 匿名認証は通っているが、このルームの参加者ではない
const ROOM = 'ROOM1234';

let testEnv;

/** ルールを無効化してテスト用のルーム・参加者を用意する */
async function seedRoom() {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await setDoc(doc(db, `rooms/${ROOM}`), {
      hostName: 'ホスト太郎',
      hostUid: HOST,
      status: 'WAITING',
      currentRound: 0,
      totalRounds: 5,
    });
    await setDoc(doc(db, `rooms/${ROOM}/players/${HOST}`), { nickname: 'ホスト太郎', isHost: true });
    await setDoc(doc(db, `rooms/${ROOM}/players/${PLAYER}`), { nickname: 'アリス', isHost: false });
    await setDoc(doc(db, `rooms/${ROOM}/rounds/1/answers/${PLAYER}`), { answer: 'はい' });
  });
}

before(async () => {
  testEnv = await initializeTestEnvironment({
    projectId: 'hitokazu-game-rules-test',
    firestore: {
      rules: readFileSync(new URL('../firestore.rules', import.meta.url), 'utf8'),
    },
  });
});

after(async () => {
  await testEnv?.cleanup();
});

beforeEach(async () => {
  await testEnv.clearFirestore();
  await seedRoom();
});

describe('rooms/{roomId} — ルーム本体', () => {
  it('参加者はルームを読める', async () => {
    const db = testEnv.authenticatedContext(PLAYER).firestore();
    await assertSucceeds(getDoc(doc(db, `rooms/${ROOM}`)));
  });

  it('未参加でもルーム本体は読める（入室前の存在確認に必要）', async () => {
    const db = testEnv.authenticatedContext(OUTSIDER).firestore();
    await assertSucceeds(getDoc(doc(db, `rooms/${ROOM}`)));
  });

  it('未認証はルームを読めない', async () => {
    const db = testEnv.unauthenticatedContext().firestore();
    await assertFails(getDoc(doc(db, `rooms/${ROOM}`)));
  });

  it('自分を hostUid にしたルームは作成できる', async () => {
    const db = testEnv.authenticatedContext(HOST).firestore();
    await assertSucceeds(
      setDoc(doc(db, 'rooms/NEWROOM1'), { hostUid: HOST, status: 'WAITING' }),
    );
  });

  it('他人を hostUid にしたルームは作成できない（なりすまし防止）', async () => {
    const db = testEnv.authenticatedContext(OUTSIDER).firestore();
    await assertFails(
      setDoc(doc(db, 'rooms/NEWROOM2'), { hostUid: HOST, status: 'WAITING' }),
    );
  });

  it('参加者はルームを更新できる（ゲーム進行）', async () => {
    const db = testEnv.authenticatedContext(PLAYER).firestore();
    await assertSucceeds(updateDoc(doc(db, `rooms/${ROOM}`), { status: 'ANSWERING' }));
  });

  it('部外者はルームを更新できない', async () => {
    const db = testEnv.authenticatedContext(OUTSIDER).firestore();
    await assertFails(updateDoc(doc(db, `rooms/${ROOM}`), { status: 'FINISHED' }));
  });

  it('ホストはルームを削除できる', async () => {
    const db = testEnv.authenticatedContext(HOST).firestore();
    await assertSucceeds(deleteDoc(doc(db, `rooms/${ROOM}`)));
  });

  it('ホスト以外はルームを削除できない', async () => {
    const db = testEnv.authenticatedContext(PLAYER).firestore();
    await assertFails(deleteDoc(doc(db, `rooms/${ROOM}`)));
  });
});

describe('rooms/{roomId}/players — 参加者', () => {
  it('参加者は参加者一覧を読める', async () => {
    const db = testEnv.authenticatedContext(PLAYER).firestore();
    await assertSucceeds(getDocs(collection(db, `rooms/${ROOM}/players`)));
  });

  it('部外者は参加者一覧を読めない', async () => {
    const db = testEnv.authenticatedContext(OUTSIDER).firestore();
    await assertFails(getDocs(collection(db, `rooms/${ROOM}/players`)));
  });

  it('自分のプレイヤー情報として入室できる', async () => {
    const db = testEnv.authenticatedContext(OUTSIDER).firestore();
    await assertSucceeds(
      setDoc(doc(db, `rooms/${ROOM}/players/${OUTSIDER}`), { nickname: 'ボブ', isHost: false }),
    );
  });

  it('他人のプレイヤー情報は書き換えられない（なりすまし防止）', async () => {
    const db = testEnv.authenticatedContext(OUTSIDER).firestore();
    await assertFails(
      setDoc(doc(db, `rooms/${ROOM}/players/${PLAYER}`), { nickname: '乗っ取り', isHost: false }),
    );
  });

  it('自分は退室できる', async () => {
    const db = testEnv.authenticatedContext(PLAYER).firestore();
    await assertSucceeds(deleteDoc(doc(db, `rooms/${ROOM}/players/${PLAYER}`)));
  });

  it('ホストは参加者を削除できる', async () => {
    const db = testEnv.authenticatedContext(HOST).firestore();
    await assertSucceeds(deleteDoc(doc(db, `rooms/${ROOM}/players/${PLAYER}`)));
  });

  it('部外者は参加者を削除できない', async () => {
    const db = testEnv.authenticatedContext(OUTSIDER).firestore();
    await assertFails(deleteDoc(doc(db, `rooms/${ROOM}/players/${PLAYER}`)));
  });
});

describe('rooms/{roomId}/rounds/{n}/answers — 回答・予測', () => {
  it('参加者は回答を読める', async () => {
    const db = testEnv.authenticatedContext(PLAYER).firestore();
    await assertSucceeds(getDocs(collection(db, `rooms/${ROOM}/rounds/1/answers`)));
  });

  it('部外者は回答を読めない（予測ゲームの根幹）', async () => {
    const db = testEnv.authenticatedContext(OUTSIDER).firestore();
    await assertFails(getDocs(collection(db, `rooms/${ROOM}/rounds/1/answers`)));
  });

  it('自分の回答は送信できる', async () => {
    const db = testEnv.authenticatedContext(HOST).firestore();
    await assertSucceeds(
      setDoc(doc(db, `rooms/${ROOM}/rounds/1/answers/${HOST}`), { answer: 'いいえ' }),
    );
  });

  it('他人の名義で回答を新規作成できない', async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), `rooms/${ROOM}/players/${OUTSIDER}`), { nickname: 'ボブ' });
    });
    const db = testEnv.authenticatedContext(OUTSIDER).firestore();
    await assertFails(
      setDoc(doc(db, `rooms/${ROOM}/rounds/1/answers/${HOST}`), { answer: 'はい' }),
    );
  });

  it('部外者は回答を書き込めない', async () => {
    const db = testEnv.authenticatedContext(OUTSIDER).firestore();
    await assertFails(
      setDoc(doc(db, `rooms/${ROOM}/rounds/1/answers/${OUTSIDER}`), { answer: 'はい' }),
    );
  });

  it('参加者は他人の回答を更新できる（採点処理のため。既知の限界）', async () => {
    const db = testEnv.authenticatedContext(HOST).firestore();
    await assertSucceeds(
      updateDoc(doc(db, `rooms/${ROOM}/rounds/1/answers/${PLAYER}`), { roundScore: 100 }),
    );
  });

  it('部外者は他人の回答を更新できない', async () => {
    const db = testEnv.authenticatedContext(OUTSIDER).firestore();
    await assertFails(
      updateDoc(doc(db, `rooms/${ROOM}/rounds/1/answers/${PLAYER}`), { roundScore: 100 }),
    );
  });
});

describe('別ルームからの越境アクセス', () => {
  it('別ルームの参加者は、このルームを操作できない', async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      const db = ctx.firestore();
      await setDoc(doc(db, 'rooms/OTHER111'), { hostUid: OUTSIDER, status: 'WAITING' });
      await setDoc(doc(db, `rooms/OTHER111/players/${OUTSIDER}`), { nickname: 'ボブ' });
    });
    const db = testEnv.authenticatedContext(OUTSIDER).firestore();
    await assertFails(updateDoc(doc(db, `rooms/${ROOM}`), { status: 'FINISHED' }));
    await assertFails(getDocs(collection(db, `rooms/${ROOM}/players`)));
  });
});
