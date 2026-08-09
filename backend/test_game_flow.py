"""
人数当てゲーム - ゲームフロー統合テスト
Firebase Emulator (Firestore) を使ってローカルで全フローを検証する

実行方法:
  1. エミュレーター起動（別ターミナル）:
     cd backend && firebase emulators:start --only firestore,auth
  2. このスクリプト実行:
     FIRESTORE_EMULATOR_HOST=localhost:8080 python3 test_game_flow.py
"""

import os
import sys
import uuid
from datetime import datetime, timezone

# Firestore エミュレーター向け設定
os.environ.setdefault("FIRESTORE_EMULATOR_HOST", "localhost:8080")

sys.path.insert(0, "functions/venv/lib/python3.12/site-packages")

import firebase_admin
from firebase_admin import credentials, firestore

# エミュレーター使用時は認証不要
app = firebase_admin.initialize_app(options={"projectId": "hitokazu-game"})
db = firestore.client()

PASS = "✅"
FAIL = "❌"
errors: list[str] = []


def check(label: str, condition: bool, detail: str = "") -> None:
    if condition:
        print(f"  {PASS} {label}" + (f"  ({detail})" if detail else ""))
    else:
        msg = f"  {FAIL} {label}" + (f"  ({detail})" if detail else "")
        print(msg)
        errors.append(msg)


def calculate_score(actual: int, predicted: int) -> int:
    return max(0, 100 - abs(predicted - actual) * 20)


# ─────────────────────────────────────────────────────────────
print("=" * 55)
print("人数当てゲーム 統合テスト (Firestore Emulator)")
print("=" * 55)

room_id = "TEST_" + str(uuid.uuid4())[:6].upper()
room_ref = db.collection("rooms").document(room_id)

# ── TEST 1: ルーム作成 ────────────────────────────────────────
print("\n[TEST 1] ルーム作成")
room_ref.set({
    "hostName": "ホスト太郎",
    "hostUid": "player_A",
    "status": "WAITING",
    "currentRound": 0,
    "totalRounds": 5,
    "currentQuestion": None,
    "createdAt": datetime.now(timezone.utc),
})
snap = room_ref.get()
check("ルームが作成された", snap.exists)
check("status=WAITING", snap.get("status") == "WAITING")
check("hostName 保存", snap.get("hostName") == "ホスト太郎")

# ── TEST 2: プレイヤー参加 ────────────────────────────────────
print("\n[TEST 2] プレイヤー参加")
players = [
    ("player_A", "ホスト太郎", True),
    ("player_B", "アリス", False),
    ("player_C", "ボブ", False),
    ("player_D", "チャーリー", False),
]
for pid, name, is_host in players:
    room_ref.collection("players").document(pid).set({
        "nickname": name,
        "isHost": is_host,
        "joinedAt": datetime.now(timezone.utc),
    })
players_snap = room_ref.collection("players").get()
check("全員が参加した", len(players_snap) == len(players), f"{len(players_snap)}人")
check("ホストが設定された",
      room_ref.collection("players").document("player_A").get().get("isHost") is True)

# ── TEST 3: ゲーム開始 ────────────────────────────────────────
print("\n[TEST 3] ゲーム開始")
question = {
    "questionId": "f001",
    "text": "自分は猫派だ（犬よりも猫が好き）",
    "options": ["はい", "いいえ"],
    "answerSeconds": 30,
    "predictSeconds": 20,
}
question_queue = [question] + [
    {"questionId": f"q{i:03d}", "text": f"テスト質問{i}", "options": ["はい", "いいえ"],
     "answerSeconds": 30, "predictSeconds": 20}
    for i in range(2, 6)
]
room_ref.update({
    "status": "ANSWERING",
    "currentRound": 1,
    "totalRounds": 5,
    "questionQueue": question_queue,
    "currentQuestion": question,
    "startedAt": datetime.now(timezone.utc),
})
snap = room_ref.get()
check("status=ANSWERING", snap.get("status") == "ANSWERING")
check("currentRound=1", snap.get("currentRound") == 1)
check("currentQuestion 保存", snap.get("currentQuestion") is not None)

# ── TEST 4: 回答送信（全員） ──────────────────────────────────
print("\n[TEST 4] 回答送信（全員）")
answers_input = [
    ("player_A", "はい"),
    ("player_B", "いいえ"),
    ("player_C", "はい"),
    ("player_D", "はい"),
]
round_ref = room_ref.collection("rounds").document("1")
for pid, answer in answers_input:
    round_ref.collection("answers").document(pid).set({
        "answer": answer,
        "answeredAt": datetime.now(timezone.utc),
    })

# 集計して PREDICTING へ
counts = {"はい": 0, "いいえ": 0}
for _, ans in answers_input:
    counts[ans] += 1
room_ref.update({
    "status": "PREDICTING",
    "answerCounts": counts,
})
snap = room_ref.get()
ans_snap = round_ref.collection("answers").get()
check("全員回答した", len(ans_snap) == len(players), f"{len(ans_snap)}/{len(players)}人")
check("status=PREDICTING", snap.get("status") == "PREDICTING")
# snap.get("answerCounts.はい") のドット記法は ASCII のフィールド名しか解釈できず、
# 日本語キーだと ValueError になる。辞書ごと取得してキーを引く。
answer_counts = snap.get("answerCounts") or {}
check("answerCounts 集計正確（はい=3）", answer_counts.get("はい") == counts["はい"],
      f"はい={counts['はい']}, いいえ={counts['いいえ']}")

# ── TEST 5: 予測送信 + 採点 ───────────────────────────────────
print("\n[TEST 5] 予測送信 & 採点")
# 実際: はい=3, いいえ=1
predictions = [
    ("player_A", "はい", 3),   # 差0 → 100点
    ("player_B", "はい", 2),   # 差1 → 80点
    ("player_C", "いいえ", 1), # 差0 → 100点
    ("player_D", "はい", 1),   # 差2 → 60点
]
expected_scores = {
    "player_A": 100,
    "player_B": 80,
    "player_C": 100,
    "player_D": 60,
}
for pid, option, pred in predictions:
    round_ref.collection("answers").document(pid).update({
        "prediction": pred,
        "targetOption": option,
        "predictedAt": datetime.now(timezone.utc),
    })

# 採点
scores = []
for pid, option, pred in predictions:
    actual = counts.get(option, 0)
    score = calculate_score(actual, pred)
    round_ref.collection("answers").document(pid).update({"roundScore": score})
    scores.append({
        "playerId": pid,
        "predictedCount": pred,
        "actualCount": actual,
        "roundScore": score,
    })

# スコア検証
for pid, _, pred in predictions:
    doc = round_ref.collection("answers").document(pid).get()
    actual_score = doc.get("roundScore")
    expected = expected_scores[pid]
    check(f"{pid} スコア正確", actual_score == expected,
          f"予測={pred}, 期待={expected}点, 実際={actual_score}点")

scores.sort(key=lambda x: x["roundScore"], reverse=True)
room_ref.update({
    "status": "RESULT",
    "roundScores": scores,
    "nextRound": 2,
})
snap = room_ref.get()
check("status=RESULT", snap.get("status") == "RESULT")
check("roundScores 保存", len(snap.get("roundScores")) == len(players))

# ── TEST 6: 次のラウンドへ ────────────────────────────────────
print("\n[TEST 6] 次のラウンドへ進む")
next_q = question_queue[1]
room_ref.update({
    "status": "ANSWERING",
    "currentRound": 2,
    "currentQuestion": next_q,
    "answerCounts": {},
    "roundScores": [],
})
snap = room_ref.get()
check("status=ANSWERING（第2ラウンド）", snap.get("status") == "ANSWERING")
check("currentRound=2", snap.get("currentRound") == 2)

# ── TEST 7: 採点ロジック単体テスト ───────────────────────────
print("\n[TEST 7] 採点ロジック単体テスト")
cases = [
    (5, 5, 100, "ぴったり"),
    (5, 4, 80,  "差1"),
    (5, 3, 60,  "差2"),
    (5, 0, 0,   "差5以上"),
    (3, 6, 40,  "オーバー差3"),
    (0, 0, 100, "0人ぴったり"),
]
for actual, pred, expected, label in cases:
    result = calculate_score(actual, pred)
    check(f"{label}: actual={actual}, pred={pred} → {expected}点", result == expected)

# ── TEST 8: ゲーム終了（FINISHED）────────────────────────────
print("\n[TEST 8] ゲーム終了 (FINISHED)")
final_scores = [
    {"playerId": "player_A", "nickname": "ホスト太郎", "totalScore": 200},
    {"playerId": "player_C", "nickname": "ボブ", "totalScore": 180},
    {"playerId": "player_B", "nickname": "アリス", "totalScore": 160},
    {"playerId": "player_D", "nickname": "チャーリー", "totalScore": 120},
]
room_ref.update({
    "status": "FINISHED",
    "finalScores": final_scores,
    "finishedAt": datetime.now(timezone.utc),
})
snap = room_ref.get()
check("status=FINISHED", snap.get("status") == "FINISHED")
check("finalScores 保存", len(snap.get("finalScores")) == len(players))

# ── TEST 9: 再戦（restartGame, Issue #17）────────────────────
# FirebaseRepository.restartGame() と同じ操作を再現し、
# 「2ゲーム目でも submitAnswer 相当の処理がすでに回答済みエラーにならない」
# 「スコアが0から始まる」「カテゴリが引き継がれる」ことを検証する。
print("\n[TEST 9] 再戦（同一ルームで2ゲーム目）")

room_ref.update({"category": "PARTY"})  # 1ゲーム目のカテゴリを明示しておく

room_before_restart = room_ref.get()
prev_game_count = (room_before_restart.to_dict() or {}).get("gameCount") or 1
check("再戦前 gameCount は未設定なら1扱い", prev_game_count == 1, f"実際={prev_game_count}")

room_ref.update({
    "status": "WAITING",
    "currentRound": 0,
    "gameCount": prev_game_count + 1,
    "currentQuestion": None,
    "answerCounts": {},
    "roundScores": [],
    "finalScores": [],
    "cumulativeTotals": {},
})
snap = room_ref.get()
check("status=WAITING に戻る", snap.get("status") == "WAITING")
check("gameCount=2 にインクリメントされる", snap.get("gameCount") == 2)
check("cumulativeTotals が空（前ゲームのスコアを持ち越さない）", snap.get("cumulativeTotals") == {})
check("category が引き継がれる（PARTY のまま）", snap.get("category") == "PARTY")

# 2ゲーム目開始（gameCount=2）。round=1 は1ゲーム目と同じ番号だが、
# roundId に gameCount を含めることで rounds/1_1 とは別ドキュメントになる。
game_count = snap.get("gameCount")
round_ref_g2 = room_ref.collection("rounds").document(f"{game_count}_1")
room_ref.update({
    "status": "ANSWERING",
    "currentRound": 1,
    "currentQuestion": question,
    "startedAt": datetime.now(timezone.utc),
})

for pid, answer in answers_input:
    round_ref_g2.collection("answers").document(pid).set({
        "answer": answer,
        "answeredAt": datetime.now(timezone.utc),
    })

g2_answers_snap = round_ref_g2.collection("answers").get()
check("2ゲーム目 round=1 に全員分の回答が書き込める（衝突なし）",
      len(g2_answers_snap) == len(players), f"{len(g2_answers_snap)}/{len(players)}人")

g1_answers_snap = room_ref.collection("rounds").document("1").collection("answers").get()
check("1ゲーム目 rounds/1/answers は削除されず残っている（roundIdが別なので独立）",
      len(g1_answers_snap) == len(players), f"{len(g1_answers_snap)}人")

# ── クリーンアップ ─────────────────────────────────────────────
print("\n[CLEANUP] テストデータ削除...")
for col in ["1", "2", "2_1"]:
    ans_docs = room_ref.collection("rounds").document(col).collection("answers").get()
    for d in ans_docs:
        d.reference.delete()
    room_ref.collection("rounds").document(col).delete()
for p in room_ref.collection("players").get():
    p.reference.delete()
room_ref.delete()
print(f"  {PASS} roomId={room_id} のデータを削除")

# ── 結果サマリー ──────────────────────────────────────────────
print("\n" + "=" * 55)
if errors:
    print(f"⚠️  {len(errors)}件の失敗:")
    for e in errors:
        print(f"  {e}")
    sys.exit(1)
else:
    print("✅ 全テスト合格！ゲームフローは正常に動作しています")
print("=" * 55)
