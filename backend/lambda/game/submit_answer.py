"""
POST /rooms/{roomId}/answer
参加者の回答を受信・保存する（BE-009）
全員回答完了で予測フェーズへ自動移行する
"""
import json
import os
from datetime import datetime, timezone

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
        answer = body.get("answer", "").strip()

        if not player_id or not answer:
            return _error(400, "playerId and answer are required")

        room = rooms_table.get_item(Key={"roomId": room_id}).get("Item")
        if not room:
            return _error(404, "Room not found")
        if room.get("status") != "ANSWERING":
            return _error(409, "Not in answering phase")

        current_round = int(room.get("currentRound", 1))
        room_round_key = f"{room_id}#{current_round}"

        # 回答を保存（重複不可）
        answers_table.put_item(
            Item={
                "roomRound": room_round_key,
                "playerId": player_id,
                "answer": answer,
                "answeredAt": datetime.now(timezone.utc).isoformat(),
            },
            ConditionExpression="attribute_not_exists(playerId)",
        )

        # 全員回答チェック
        total_players = players_table.query(
            KeyConditionExpression=Key("roomId").eq(room_id)
        )["Count"]

        answered = answers_table.query(
            KeyConditionExpression=Key("roomRound").eq(room_round_key)
        )["Count"]

        if answered >= total_players:
            _transition_to_predicting(room_id, current_round, room_round_key)

        return {
            "statusCode": 200,
            "headers": {"Content-Type": "application/json"},
            "body": json.dumps({"message": "Answer recorded"}),
        }

    except dynamodb.meta.client.exceptions.ConditionalCheckFailedException:
        return _error(409, "Already answered")
    except Exception:
        return _error(500, "Internal server error")


def _transition_to_predicting(room_id: str, current_round: int, room_round_key: str):
    """全員回答完了→予測フェーズへ移行"""
    # 回答集計
    answers = answers_table.query(
        KeyConditionExpression=Key("roomRound").eq(room_round_key)
    )["Items"]
    question = QUESTIONS[current_round - 1]
    counts = {opt: 0 for opt in question["options"]}
    for a in answers:
        opt = a.get("answer")
        if opt in counts:
            counts[opt] += 1

    rooms_table.update_item(
        Key={"roomId": room_id},
        UpdateExpression="SET #s = :s",
        ExpressionAttributeNames={"#s": "status"},
        ExpressionAttributeValues={":s": "PREDICTING"},
    )

    broadcast(room_id, {
        "action": "allAnswered",
        "round": current_round,
        "question": question,
        "answerCounts": counts,
    })
