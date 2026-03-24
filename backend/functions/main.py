"""
人数当てゲーム - Firebase Cloud Functions (Python 3.12)
"""
import json
import uuid
from datetime import datetime, timezone

from firebase_admin import initialize_app, firestore as firebase_firestore
from firebase_functions import https_fn, options

initialize_app()

REGION = options.SupportedRegion.US_CENTRAL1
CORS = options.CorsOptions(cors_origins="*", cors_methods=["POST", "OPTIONS"])

QUESTIONS = [
    {
        "questionId": "q001",
        "text": "犬派？猫派？",
        "options": ["犬派", "猫派"],
        "answerSeconds": 30,
        "predictSeconds": 20,
    },
    {
        "questionId": "q002",
        "text": "朝型？夜型？",
        "options": ["朝型", "夜型"],
        "answerSeconds": 30,
        "predictSeconds": 20,
    },
    {
        "questionId": "q003",
        "text": "派手なパーティーと静かな家飲み、どっち派？",
        "options": ["派手なパーティー", "静かな家飲み"],
        "answerSeconds": 30,
        "predictSeconds": 20,
    },
    {
        "questionId": "q004",
        "text": "カラオケで歌う派？聴く派？",
        "options": ["歌う派", "聴く派"],
        "answerSeconds": 30,
        "predictSeconds": 20,
    },
    {
        "questionId": "q005",
        "text": "旅行は計画派？行き当たりばったり派？",
        "options": ["計画派", "行き当たりばったり派"],
        "answerSeconds": 30,
        "predictSeconds": 20,
    },
]


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

    db = firebase_firestore.client()
    room_id = str(uuid.uuid4())[:8].upper()

    db.collection("rooms").document(room_id).set({
        "hostName": host_name,
        "status": "WAITING",
        "currentRound": 0,
        "totalRounds": len(QUESTIONS),
        "currentQuestion": None,
        "createdAt": datetime.now(timezone.utc),
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
    if room.to_dict().get("status") != "WAITING":
        return _err("すでに開始済みです", 409)

    players = room_ref.collection("players").get()
    if len(players) < 1:
        return _err("参加者が必要です")

    first_question = QUESTIONS[0]
    room_ref.update({
        "status": "ANSWERING",
        "currentRound": 1,
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
    total_rounds = room_data.get("totalRounds", len(QUESTIONS))

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

    if is_last:
        room_ref.update({
            "status": "FINISHED",
            "finalScores": scores,
            "finishedAt": datetime.now(timezone.utc),
        })
    else:
        next_round = current_round + 1
        next_question = QUESTIONS[next_round - 1]
        room_ref.update({
            "status": "RESULT",
            "roundScores": scores,
            "nextRound": next_round,
        })
        import time
        time.sleep(10)
        room_ref.update({
            "status": "ANSWERING",
            "currentRound": next_round,
            "currentQuestion": next_question,
        })
