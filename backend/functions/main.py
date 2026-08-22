"""
人数当てゲーム - Firebase Cloud Functions (Python 3.12)
"""
import json
import random
import uuid
from datetime import datetime, timedelta, timezone

from firebase_admin import initialize_app, firestore as firebase_firestore
from firebase_functions import https_fn, options, scheduler_fn
from google.cloud.firestore_v1.base_query import FieldFilter

initialize_app()

REGION = options.SupportedRegion.US_CENTRAL1
CORS = options.CorsOptions(cors_origins="*", cors_methods=["POST", "OPTIONS"])

TOTAL_ROUNDS_PER_GAME = 5

# ── ルームの保持期限（Issue #34） ─────────────────────────────
# 終了済み（FINISHED / HOST_LEFT）は終了から24時間、それ以外（作成直後・ラウンド確定の
# たび）は基準時刻から6時間で expireAt を更新する。Android（FirebaseRepository.kt）・
# Web（backend/web/index.html）と同一の値にすること。
ROOM_WAITING_RETENTION_HOURS = 6
ROOM_FINISHED_RETENTION_HOURS = 24
# expireAt を持たない旧ルーム（本Issue導入前に作成された分）のフォールバック保持期間。
ROOM_LEGACY_MAX_AGE_HOURS = 24


def _expire_at_after_hours(hours: int, now: datetime | None = None) -> datetime:
    return (now or datetime.now(timezone.utc)) + timedelta(hours=hours)


# 質問マスタは shared/questions.json を正本として生成される（Issue #16）。
# 直接編集せず、shared/questions.json を更新して
# scripts/generate_questions.py を実行すること。
from questions import QUESTIONS  # noqa: E402  (initialize_app() より後に import する既存構成を踏襲)

# ルーム作成時に選べる質問カテゴリと、対応する質問タグ。
# Android の QuestionCategory（data/model/Models.kt）・Web（backend/web/index.html）と
# 同一の表記・絞り込み仕様にすること（Issue #12）。
CATEGORY_TAGS = {
    "FRIEND": ["friend"],
    "PARTY": ["party"],
    "DEEP": ["deep"],
    "ALL": ["friend", "party", "deep"],
}


def _normalize_category(value) -> str:
    name = (value or "ALL").strip().upper()
    return name if name in CATEGORY_TAGS else "ALL"


def _filter_questions_by_category(category: str) -> list:
    tags = CATEGORY_TAGS.get(category, CATEGORY_TAGS["ALL"])
    filtered = [q for q in QUESTIONS if any(t in tags for t in q.get("tags", []))]
    return filtered if filtered else QUESTIONS


def _ok(data: dict):
    return https_fn.Response(json.dumps(data), status=200, content_type="application/json")


def _err(message: str, status: int = 400):
    return https_fn.Response(json.dumps({"error": message}), status=status, content_type="application/json")


def _calculate_score(actual: int, predicted: int) -> int:
    return max(0, 100 - abs(predicted - actual) * 20)


# ── ルーム作成 ─────────────────────────────────────────────────
@https_fn.on_request(region=REGION, cors=CORS)
def create_room(req: https_fn.Request) -> https_fn.Response:
    data = req.get_json(silent=True) or {}
    host_name = (data.get("hostName") or "ホスト").strip()
    category = _normalize_category(data.get("category"))

    db = firebase_firestore.client()
    room_id = str(uuid.uuid4())[:8].upper()

    now = datetime.now(timezone.utc)
    db.collection("rooms").document(room_id).set({
        "hostName": host_name,
        "status": "WAITING",
        "currentRound": 0,
        "totalRounds": len(QUESTIONS),
        "currentQuestion": None,
        "category": category,
        "createdAt": now,
        # 未開始のまま放置されたルームを delete_expired_rooms が回収するための保持期限。
        "expireAt": _expire_at_after_hours(ROOM_WAITING_RETENTION_HOURS, now),
    })

    return _ok({"roomId": room_id})


# ── ルーム参加 ─────────────────────────────────────────────────
@https_fn.on_request(region=REGION, cors=CORS)
def join_room(req: https_fn.Request) -> https_fn.Response:
    data = req.get_json(silent=True) or {}
    room_id = (data.get("roomId") or "").strip().upper()
    nickname = (data.get("nickname") or "").strip()

    if not room_id or not nickname:
        return _err("roomId と nickname は必須です")

    db = firebase_firestore.client()
    room_ref = db.collection("rooms").document(room_id)
    room = room_ref.get()

    if not room.exists:
        return _err("ルームが見つかりません", 404)

    room_data = room.to_dict()
    if room_data.get("status") != "WAITING":
        return _err("ゲームはすでに開始されています", 409)

    players = room_ref.collection("players").get()
    if len(players) >= 20:
        return _err("ルームが満員です", 409)

    player_id = str(uuid.uuid4())
    room_ref.collection("players").document(player_id).set({
        "nickname": nickname,
        "isHost": False,
        "joinedAt": datetime.now(timezone.utc),
    })

    return _ok({"playerId": player_id, "roomId": room_id, "nickname": nickname})


# ── ゲーム開始 ─────────────────────────────────────────────────
@https_fn.on_request(region=REGION, cors=CORS)
def start_game(req: https_fn.Request) -> https_fn.Response:
    data = req.get_json(silent=True) or {}
    room_id = (data.get("roomId") or "").strip().upper()

    db = firebase_firestore.client()
    room_ref = db.collection("rooms").document(room_id)
    room = room_ref.get()

    if not room.exists:
        return _err("ルームが見つかりません", 404)
    room_data = room.to_dict()
    if room_data.get("status") != "WAITING":
        return _err("すでに開始済みです", 409)

    players = room_ref.collection("players").get()
    if len(players) < 1:
        return _err("参加者が必要です")

    category = _normalize_category(room_data.get("category"))
    pool = _filter_questions_by_category(category)
    question_queue = random.sample(pool, min(TOTAL_ROUNDS_PER_GAME, len(pool)))
    first_question = question_queue[0]
    room_ref.update({
        "status": "ANSWERING",
        "currentRound": 1,
        "totalRounds": len(question_queue),
        "questionQueue": question_queue,
        "currentQuestion": first_question,
        "startedAt": datetime.now(timezone.utc),
    })

    return _ok({"message": "started", "round": 1})


# ── 回答送信 ───────────────────────────────────────────────────
@https_fn.on_request(region=REGION, cors=CORS)
def submit_answer(req: https_fn.Request) -> https_fn.Response:
    data = req.get_json(silent=True) or {}
    room_id = (data.get("roomId") or "").strip().upper()
    player_id = (data.get("playerId") or "").strip()
    answer = (data.get("answer") or "").strip()

    if not all([room_id, player_id, answer]):
        return _err("roomId, playerId, answer は必須です")

    db = firebase_firestore.client()
    room_ref = db.collection("rooms").document(room_id)
    room_data = room_ref.get().to_dict()

    if not room_data or room_data.get("status") != "ANSWERING":
        return _err("回答フェーズではありません", 409)

    current_round = room_data["currentRound"]
    answer_ref = (
        room_ref.collection("rounds")
        .document(str(current_round))
        .collection("answers")
        .document(player_id)
    )

    if answer_ref.get().exists:
        return _err("すでに回答済みです", 409)

    answer_ref.set({
        "answer": answer,
        "answeredAt": datetime.now(timezone.utc),
    })

    players = room_ref.collection("players").get()
    answers = (
        room_ref.collection("rounds")
        .document(str(current_round))
        .collection("answers")
        .get()
    )

    if len(answers) >= len(players):
        question = room_data.get("currentQuestion", {})
        counts = {opt: 0 for opt in question.get("options", [])}
        for a in answers:
            opt = a.to_dict().get("answer")
            if opt in counts:
                counts[opt] += 1
        room_ref.update({"status": "PREDICTING", "answerCounts": counts})

    return _ok({"message": "ok"})


# ── 予測送信 ───────────────────────────────────────────────────
@https_fn.on_request(region=REGION, cors=CORS)
def submit_prediction(req: https_fn.Request) -> https_fn.Response:
    data = req.get_json(silent=True) or {}
    room_id = (data.get("roomId") or "").strip().upper()
    player_id = (data.get("playerId") or "").strip()
    target_option = (data.get("targetOption") or "").strip()
    predicted_count = data.get("predictedCount")

    if not all([room_id, player_id, target_option]) or predicted_count is None:
        return _err("必須パラメータが不足しています")

    try:
        predicted_count = int(predicted_count)
    except (ValueError, TypeError):
        return _err("predictedCount は整数である必要があります")

    db = firebase_firestore.client()
    room_ref = db.collection("rooms").document(room_id)
    room_data = room_ref.get().to_dict()

    if not room_data or room_data.get("status") != "PREDICTING":
        return _err("予測フェーズではありません", 409)

    current_round = room_data["currentRound"]
    answer_ref = (
        room_ref.collection("rounds")
        .document(str(current_round))
        .collection("answers")
        .document(player_id)
    )

    answer_ref.update({
        "prediction": predicted_count,
        "targetOption": target_option,
        "predictedAt": datetime.now(timezone.utc),
    })

    players = room_ref.collection("players").get()
    answers = (
        room_ref.collection("rounds")
        .document(str(current_round))
        .collection("answers")
        .get()
    )

    predicted_players = [a for a in answers if "prediction" in a.to_dict()]
    if len(predicted_players) >= len(players):
        _finalize_round(room_ref, room_data, current_round, answers)

    return _ok({"message": "ok"})


def _finalize_round(room_ref, room_data: dict, current_round: int, answers):
    question = room_data.get("currentQuestion", {})
    counts = room_data.get("answerCounts", {})
    total_rounds = room_data.get("totalRounds", TOTAL_ROUNDS_PER_GAME)

    scores = []
    for a_doc in answers:
        a = a_doc.to_dict()
        if "prediction" not in a:
            continue
        actual = counts.get(a.get("targetOption", ""), 0)
        score = _calculate_score(actual, int(a["prediction"]))
        room_ref.collection("rounds").document(str(current_round)).collection("answers").document(a_doc.id).update({
            "roundScore": score,
        })
        scores.append({
            "playerId": a_doc.id,
            "targetOption": a.get("targetOption"),
            "predictedCount": int(a["prediction"]),
            "actualCount": actual,
            "roundScore": score,
        })

    scores.sort(key=lambda x: x["roundScore"], reverse=True)
    is_last = current_round >= total_rounds
    now = datetime.now(timezone.utc)

    if is_last:
        room_ref.update({
            "status": "FINISHED",
            "finalScores": scores,
            "finishedAt": now,
            # 終了扱いになるので保持期限を「終了から24時間」に更新する（Issue #34）。
            "expireAt": _expire_at_after_hours(ROOM_FINISHED_RETENTION_HOURS, now),
        })
    else:
        next_round = current_round + 1
        question_queue = room_data.get("questionQueue", QUESTIONS)
        next_question = question_queue[next_round - 1]
        room_ref.update({
            "status": "RESULT",
            "roundScores": scores,
            "nextRound": next_round,
            # ラウンド確定のたびに保持期限を延長する。遊び続けている限り実質失効しない（Issue #34）。
            "expireAt": _expire_at_after_hours(ROOM_WAITING_RETENTION_HOURS, now),
        })
        import time
        time.sleep(10)
        room_ref.update({
            "status": "ANSWERING",
            "currentRound": next_round,
            "currentQuestion": next_question,
        })


# ── 期限切れルームの削除（スケジュール実行） ────────────────────
# rooms/{roomId} と配下の players / rounds / rounds/*/answers をまとめて削除する（Issue #34）。
# Firestore の TTL ポリシーは親ドキュメントの削除のみで、サブコレクションを削除しないため
# （公式ドキュメントに明記された既知の制約）、recursive_delete() で明示的に再帰削除する。
#
# 削除対象は2種類:
#   1. expireAt を過ぎたルーム（本Issue以降に作成・更新されたルーム）
#   2. expireAt を持たない旧ルーム（本Issue導入前に作成され、まだ一度も
#      createRoom/finalizeRound/restartGame/terminateRoomHostLeft を経ていないもの）で、
#      createdAt から ROOM_LEGACY_MAX_AGE_HOURS を超えているもの。
#      expireAt が付いたルームは1のクエリで扱われるため、ここでは明示的にスキップする。
def _sweep_expired_rooms(db) -> int:
    now = datetime.now(timezone.utc)
    deleted = 0

    expired_rooms = db.collection("rooms").where(filter=FieldFilter("expireAt", "<=", now)).stream()
    for room_doc in expired_rooms:
        db.recursive_delete(room_doc.reference)
        deleted += 1

    legacy_cutoff = now - timedelta(hours=ROOM_LEGACY_MAX_AGE_HOURS)
    legacy_candidates = db.collection("rooms").where(filter=FieldFilter("createdAt", "<=", legacy_cutoff)).stream()
    for room_doc in legacy_candidates:
        data = room_doc.to_dict() or {}
        if "expireAt" in data:
            continue  # 上のクエリですでに扱い済み（期限内 or 削除済み）
        db.recursive_delete(room_doc.reference)
        deleted += 1

    return deleted


@scheduler_fn.on_schedule(schedule="every 24 hours", region=REGION)
def delete_expired_rooms(event: scheduler_fn.ScheduledEvent) -> None:
    db = firebase_firestore.client()
    deleted = _sweep_expired_rooms(db)
    print(f"delete_expired_rooms: {deleted} 件のルームを削除しました")
