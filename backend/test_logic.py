"""
人数当てゲーム - ゲームロジック単体テスト
Firestoreへの接続なしで、採点・状態遷移ロジックを検証する

実行方法:
  python3 test_logic.py
"""

import sys

errors: list[str] = []


def check(label: str, condition: bool, detail: str = "") -> None:
    if condition:
        print(f"  ✅ {label}" + (f"  ({detail})" if detail else ""))
    else:
        msg = f"  ❌ {label}" + (f"  ({detail})" if detail else "")
        print(msg)
        errors.append(msg)


# ─── 採点ロジック ─────────────────────────────────────────────
def calculate_score(actual: int, predicted: int) -> int:
    return max(0, 100 - abs(predicted - actual) * 20)


# ─── フェーズ遷移ロジック ─────────────────────────────────────
def should_transition_to_predicting(answer_count: int, player_count: int) -> bool:
    return answer_count >= player_count


def should_finalize_round(prediction_count: int, player_count: int) -> bool:
    return prediction_count >= player_count


def get_next_status(current_round: int, total_rounds: int) -> str:
    return "FINISHED" if current_round >= total_rounds else "RESULT"


# ─── 集計ロジック ─────────────────────────────────────────────
def count_answers(answers: list[str], options: list[str]) -> dict[str, int]:
    return {opt: answers.count(opt) for opt in options}


def finalize_scores(
    predictions: list[dict],
    answer_counts: dict[str, int],
) -> list[dict]:
    results = []
    for p in predictions:
        actual = answer_counts.get(p["targetOption"], 0)
        score = calculate_score(actual, p["predictedCount"])
        results.append({**p, "actualCount": actual, "roundScore": score})
    return sorted(results, key=lambda x: x["roundScore"], reverse=True)


# ═════════════════════════════════════════════════════════════
print("=" * 55)
print("人数当てゲーム ロジック単体テスト")
print("=" * 55)

# ── TEST 1: 採点ロジック ──────────────────────────────────────
print("\n[TEST 1] 採点ロジック")
cases = [
    (5, 5, 100, "ぴったり"),
    (5, 4, 80,  "差1"),
    (5, 3, 60,  "差2"),
    (5, 2, 40,  "差3"),
    (5, 1, 20,  "差4"),
    (5, 0, 0,   "差5"),
    (5, 6, 80,  "オーバー差1"),
    (5, 10, 0,  "オーバー差5以上"),
    (0, 0, 100, "0人ぴったり"),
    (0, 1, 80,  "0人に対し1予測"),
    (0, 5, 0,   "0人に対し5予測"),
]
for actual, pred, expected, label in cases:
    result = calculate_score(actual, pred)
    check(f"{label}: actual={actual}, pred={pred} → {expected}点", result == expected,
          f"実際={result}点")

# ── TEST 2: 回答集計 ──────────────────────────────────────────
print("\n[TEST 2] 回答集計")
answers = ["はい", "いいえ", "はい", "はい", "いいえ"]
counts = count_answers(answers, ["はい", "いいえ"])
check("はい=3", counts["はい"] == 3)
check("いいえ=2", counts["いいえ"] == 2)
check("合計=参加人数", sum(counts.values()) == len(answers))

answers2 = ["はい", "はい", "はい", "はい"]
counts2 = count_answers(answers2, ["はい", "いいえ"])
check("全員はい: はい=4, いいえ=0", counts2["はい"] == 4 and counts2["いいえ"] == 0)

# ── TEST 3: フェーズ遷移（ANSWERING→PREDICTING）──────────────
print("\n[TEST 3] フェーズ遷移")
check("全員回答 → PREDICTING", should_transition_to_predicting(4, 4))
check("未回答あり → 遷移しない", not should_transition_to_predicting(3, 4))
check("0人部屋 → 遷移しない", not should_transition_to_predicting(0, 4))

check("全員予測 → ラウンド終了", should_finalize_round(4, 4))
check("未予測あり → 終了しない", not should_finalize_round(3, 4))

# ── TEST 4: ラウンド終了後のステータス判定 ─────────────────
print("\n[TEST 4] RESULT vs FINISHED 判定")
check("最終ラウンド → FINISHED", get_next_status(5, 5) == "FINISHED")
check("途中ラウンド → RESULT",   get_next_status(3, 5) == "RESULT")
check("第1ラウンド → RESULT",    get_next_status(1, 5) == "RESULT")
check("round=total-1 → RESULT", get_next_status(4, 5) == "RESULT")

# ── TEST 5: ラウンドスコア集計 ───────────────────────────────
print("\n[TEST 5] ラウンドスコア集計・ランキング")
answer_counts = {"はい": 3, "いいえ": 1}
predictions = [
    {"playerId": "A", "nickname": "太郎", "targetOption": "はい",   "predictedCount": 3},
    {"playerId": "B", "nickname": "花子", "targetOption": "はい",   "predictedCount": 2},
    {"playerId": "C", "nickname": "次郎", "targetOption": "いいえ", "predictedCount": 1},
    {"playerId": "D", "nickname": "桜",   "targetOption": "はい",   "predictedCount": 1},
]
scores = finalize_scores(predictions, answer_counts)

check("A(太郎): 差0→100点", scores[0]["playerId"] in ("A", "C") and scores[0]["roundScore"] == 100)
check("C(次郎): 差0→100点", any(s["playerId"] == "C" and s["roundScore"] == 100 for s in scores))
check("B(花子): 差1→80点",  any(s["playerId"] == "B" and s["roundScore"] == 80  for s in scores))
check("D(桜):   差2→60点",  any(s["playerId"] == "D" and s["roundScore"] == 60  for s in scores))
check("降順ソート",
      scores[0]["roundScore"] >= scores[1]["roundScore"] >= scores[2]["roundScore"] >= scores[3]["roundScore"])

# ── TEST 6: エッジケース ─────────────────────────────────────
print("\n[TEST 6] エッジケース")
# 全員同じ回答
counts_all_yes = count_answers(["はい"] * 5, ["はい", "いいえ"])
check("全員はいの集計", counts_all_yes["はい"] == 5 and counts_all_yes["いいえ"] == 0)

# 1人参加
check("1人ゲーム: 回答=参加→PREDICTING", should_transition_to_predicting(1, 1))
check("1人ゲーム: 予測=参加→終了",       should_finalize_round(1, 1))

# 最大プレイヤー（20人）
answers_20 = ["はい"] * 12 + ["いいえ"] * 8
counts_20 = count_answers(answers_20, ["はい", "いいえ"])
check("20人ゲーム集計", counts_20["はい"] == 12 and counts_20["いいえ"] == 8)
check("20人全員回答→PREDICTING", should_transition_to_predicting(20, 20))

# ── TEST 7: ルームID形式 ─────────────────────────────────────
print("\n[TEST 7] ルームID生成")
import random
import string

def generate_room_id() -> str:
    chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    return "".join(random.choices(chars, k=8))

for _ in range(100):
    rid = generate_room_id()
    check_chars = set(rid)
    check_all_valid = all(c in "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" for c in rid)
    if not check_all_valid:
        check("ルームID: 有効文字のみ", False, rid)
        break
    if len(rid) != 8:
        check("ルームID: 8文字", False, f"len={len(rid)}")
        break
else:
    check("ルームID: 100回生成すべて有効（8文字・紛らわしい文字なし）", True)

# 紛らわしい文字（O, I, 0, 1）が含まれないことを確認
confusable = set("OI01")
ids = [generate_room_id() for _ in range(1000)]
has_confusable = any(c in confusable for rid in ids for c in rid)
check("ルームID: O/I/0/1 を含まない（1000サンプル）", not has_confusable)

# ── TEST 8: 累計スコア計算 ───────────────────────────────────
print("\n[TEST 8] 累計スコア（複数ラウンド）")

def simulate_rounds(rounds_data: list[tuple[dict, dict]]) -> dict[str, int]:
    """
    rounds_data: [(answer_counts, predictions_with_ids), ...]
    Returns: {playerId: totalScore}
    """
    totals: dict[str, int] = {}
    for answer_counts, predictions in rounds_data:
        for pid, option, pred in predictions:
            actual = answer_counts.get(option, 0)
            score = calculate_score(actual, pred)
            totals[pid] = totals.get(pid, 0) + score
    return totals

# ラウンド1: はい=3, いいえ=1
r1_counts = {"はい": 3, "いいえ": 1}
r1_preds = [
    ("A", "はい",   3),  # +100
    ("B", "はい",   2),  # +80
    ("C", "いいえ", 1),  # +100
    ("D", "はい",   1),  # +60
]
# ラウンド2: はい=2, いいえ=2
r2_counts = {"はい": 2, "いいえ": 2}
r2_preds = [
    ("A", "はい",   2),  # +100
    ("B", "はい",   3),  # +80
    ("C", "いいえ", 2),  # +100
    ("D", "はい",   2),  # +100
]
totals = simulate_rounds([(r1_counts, r1_preds), (r2_counts, r2_preds)])
check("A 累計: 100+100=200", totals["A"] == 200, f"実際={totals['A']}")
check("B 累計: 80+80=160",   totals["B"] == 160, f"実際={totals['B']}")
check("C 累計: 100+100=200", totals["C"] == 200, f"実際={totals['C']}")
check("D 累計: 60+100=160",  totals["D"] == 160, f"実際={totals['D']}")

# 5ラウンド満点の場合
r_perfect_counts = {"はい": 3, "いいえ": 2}
r_perfect_preds = [("X", "はい", 3)]
totals_5 = simulate_rounds([(r_perfect_counts, r_perfect_preds)] * 5)
check("5ラウンド満点: 500点", totals_5["X"] == 500, f"実際={totals_5['X']}")

# ─── 結果サマリー ─────────────────────────────────────────────
print("\n" + "=" * 55)
if errors:
    print(f"⚠️  {len(errors)}件の失敗:")
    for e in errors:
        print(e)
    sys.exit(1)
else:
    print("✅ 全テスト合格！ゲームロジックは正常です")
print("=" * 55)
