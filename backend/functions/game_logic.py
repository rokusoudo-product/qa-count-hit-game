"""
人数当てゲーム - コアロジック（採点・集計・フェーズ遷移判定）

Firestore / firebase_admin / firebase_functions に一切依存しない純粋関数のみを置く。
そのため `backend/test_logic.py` はこのモジュールを import するだけで
（追加の pip install なしに）検証対象の実装そのものをテストできる（Issue #29）。

`main.py`（Cloud Functions）もこのモジュールの関数を呼び出す。採点式は
Android（`android/.../game/GameLogic.kt`）・Web（`backend/web/index.html`）と
同一にすること。
"""

from __future__ import annotations


# ─── 採点ロジック ─────────────────────────────────────────────
def calculate_score(actual: int, predicted: int) -> int:
    """予測と実際の回答数の差から得点を計算する: max(0, 100 - |予測-実際|×20)"""
    return max(0, 100 - abs(predicted - actual) * 20)


def cumulative_total(previous_total: int, round_score: int) -> int:
    """前ラウンドまでの累計スコアに、今回のラウンドスコアを加算する。"""
    return previous_total + round_score


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
    """予測リストと集計結果から各プレイヤーのラウンドスコアを計算し、降順に並べる。"""
    results = []
    for p in predictions:
        actual = answer_counts.get(p["targetOption"], 0)
        score = calculate_score(actual, p["predictedCount"])
        results.append({**p, "actualCount": actual, "roundScore": score})
    return sorted(results, key=lambda x: x["roundScore"], reverse=True)


# ─── ルームID生成（Android FirebaseRepository.generateRoomId と同一の文字集合） ──
ROOM_ID_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"  # 紛らわしい文字（O, I, 0, 1）を除く


def generate_room_id(rng=None) -> str:
    import random

    r = rng or random
    return "".join(r.choices(ROOM_ID_CHARS, k=8))


# ─── 再戦（restartGame, Issue #17）───────────────────────────
def round_doc_id(game_count: int, round_num: int) -> str:
    """FirebaseRepository.roundDocId() / index.html roundDocId() と同じ形式。"""
    return f"{game_count}_{round_num}"


def pick_first_question(question_queue: list[dict], avoid_question_id: str | None) -> list[dict]:
    """
    startGame() の「前ゲーム最後の質問を1問目にしない」ロジックの純粋関数版。
    Kotlin(FirebaseRepository.startGame) / JS(index.html startGame) と同じ優先順位:
      1. queue内に別の質問があれば先頭と入れ替え
      2. queue内が全部同じ質問なら諦める（呼び出し側でプール全体から探す運用は別途）
    """
    if not avoid_question_id or not question_queue:
        return question_queue
    if question_queue[0]["questionId"] != avoid_question_id:
        return question_queue
    alt_index = next(
        (i for i, q in enumerate(question_queue) if q["questionId"] != avoid_question_id),
        -1,
    )
    if alt_index > 0:
        swapped = question_queue[:]
        swapped[0], swapped[alt_index] = swapped[alt_index], swapped[0]
        return swapped
    return question_queue  # 代替なし


def restart_room_fields(prev_game_count: int) -> dict:
    """restartGame() が上書きするフィールドの純粋関数版（category は含めない＝引き継ぐ）。"""
    return {
        "status": "WAITING",
        "currentRound": 0,
        "gameCount": prev_game_count + 1,
        "currentQuestion": None,
        "answerCounts": {},
        "roundScores": [],
        "finalScores": [],
        "cumulativeTotals": {},
    }
