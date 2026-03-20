"""
POST /rooms/{roomId}/prediction
参加者の予測を受信・保存し、全員完了で採点→結果表示へ（BE-010, BE-011, BE-012）
"""
import json
import os
from datetime import datetime, timezone
from decimal import Decimal

import boto3
from boto3.dynamodb.conditions import Key

from _broadcast import broadcast
from questions.preset_questions import QUESTIONS

dynamodb = boto3.resource("dynamodb")
rooms_table = dynamodb.Table(os.environ["ROOMS_TABLE"])
players_table = dynamodb.Table(os.environ["PLAYERS_TABLE"])
answers_table = dynamodb.Table(os.environ["ANSWERS_TABLE"])


def handler(event, context):
    room_id = event["pathParameters"]["roomId"]

    try:
        body = json.loads(event.get("body") or "{}")
        player_id = body.get("playerId", "").strip()
        target_option = body.get("targetOption", "").strip()
        predicted_count = body.get("predictedCount")

        if not player_id or not target_option or predicted_count is None:
            return _error(400, "playerId, targetOption and predictedCount are required")

        try:
            predicted_count = int(predicted_count)
            if predicted_count < 0:
                raise ValueError
        except (ValueError, TypeError):
            return _error(400, "predictedCount must be a non-negative integer")

        room = rooms_table.get_item(Key={"roomId": room_id}).get("Item")
        if not room:
            return _error(404, "Room not found")
        if room.get("status") != "PREDICTING":
            return _error(409, "Not in predicting phase")

        current_round = int(room.get("currentRound", 1))
        room_round_key = f"{room_id}#{current_round}"

        # 予測を保存
        answers_table.update_item(
            Key={"roomRound": room_round_key, "playerId": player_id},
            UpdateExpression="SET prediction = :p, targetOption = :o, predictedAt = :t",
            ExpressionAttributeValues={
                ":p": predicted_count,
                ":o": target_option,
                ":t": datetime.now(timezone.utc).isoformat(),
            },
        )

        # 全員予測完了チェック
        total_players = players_table.query(
            KeyConditionExpression=Key("roomId").eq(room_id)
        )["Count"]

        all_answers = answers_table.query(
            KeyConditionExpression=Key("roomRound").eq(room_round_key)
        )["Items"]

        predicted_count_players = sum(1 for a in all_answers if "prediction" in a)

        if predicted_count_players >= total_players:
            _calculate_and_broadcast_results(
                room_id, current_round, room_round_key, all_answers
            )

        return {
            "statusCode": 200,
            "headers": {"Content-Type": "application/json"},
            "body": json.dumps({"message": "Prediction recorded"}),
        }

    except Exception:
        return _error(500, "Internal server error")


def _calculate_score(actual: int, predicted: int) -> int:
    """採点: max(0, 100 - |予測 - 実際| × 20)"""
    diff = abs(predicted - actual)
    return max(0, 100 - diff * 20)


def _calculate_and_broadcast_results(
    room_id: str, current_round: int, room_round_key: str, all_answers: list
):
    """採点・結果をbroadcastし、次ラウンドまたはゲーム終了へ移行"""
    question = QUESTIONS[current_round - 1]

    # 実際の回答集計
    counts = {opt: 0 for opt in question["options"]}
    for a in all_answers:
        opt = a.get("answer")
        if opt in counts:
            counts[opt] += 1

    # 採点
    player_scores = []
    for a in all_answers:
        if "prediction" not in a:
            continue
        actual = counts.get(a.get("targetOption", ""), 0)
        predicted = int(a["prediction"])
        score = _calculate_score(actual, predicted)

        # スコアをDynamoDBに保存
        answers_table.update_item(
            Key={"roomRound": room_round_key, "playerId": a["playerId"]},
            UpdateExpression="SET roundScore = :s",
            ExpressionAttributeValues={":s": score},
        )
        player_scores.append({
            "playerId": a["playerId"],
            "targetOption": a.get("targetOption"),
            "predictedCount": int(a["prediction"]),
            "actualCount": actual,
            "roundScore": score,
        })

    # ランキング（スコア降順）
    player_scores.sort(key=lambda x: x["roundScore"], reverse=True)

    is_last_round = current_round >= len(QUESTIONS)

    if is_last_round:
        _finalize_game(room_id, player_scores, current_round, counts, question)
    else:
        _next_round(room_id, current_round, counts, question, player_scores)


def _next_round(room_id, current_round, counts, question, player_scores):
    next_round = current_round + 1
    next_question = QUESTIONS[next_round - 1]

    rooms_table.update_item(
        Key={"roomId": room_id},
        UpdateExpression="SET #s = :s, currentRound = :r, currentQuestion = :q",
        ExpressionAttributeNames={"#s": "status"},
        ExpressionAttributeValues={
            ":s": "ANSWERING",
            ":r": next_round,
            ":q": next_question["questionId"],
        },
    )

    broadcast(room_id, {
        "action": "roundResult",
        "round": current_round,
        "question": question,
        "answerCounts": counts,
        "scores": player_scores,
        "nextRound": next_round,
        "nextQuestion": next_question,
    })


def _finalize_game(room_id, player_scores, current_round, counts, question):
    rooms_table.update_item(
        Key={"roomId": room_id},
        UpdateExpression="SET #s = :s",
        ExpressionAttributeNames={"#s": "status"},
        ExpressionAttributeValues={":s": "FINISHED"},
    )

    broadcast(room_id, {
        "action": "gameEnded",
        "round": current_round,
        "question": question,
        "answerCounts": counts,
        "finalScores": player_scores,
    })


def _error(status: int, message: str) -> dict:
    return {
        "statusCode": status,
        "headers": {"Content-Type": "application/json"},
        "body": json.dumps({"error": message}),
    }
