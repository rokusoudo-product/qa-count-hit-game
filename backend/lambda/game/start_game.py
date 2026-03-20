"""
POST /rooms/{roomId}/start
ゲームを開始し、全参加者に最初の質問をbroadcastする（BE-007, BE-008）
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


def handler(event, context):
    room_id = event["pathParameters"]["roomId"]

    try:
        room = rooms_table.get_item(Key={"roomId": room_id}).get("Item")
        if not room:
            return _error(404, "Room not found")
        if room.get("status") != "WAITING":
            return _error(409, "Game already started")

        # 最低2人チェック
        players = players_table.query(
            KeyConditionExpression=Key("roomId").eq(room_id)
        )
        if players["Count"] < 2:
            return _error(400, "Need at least 2 players")

        first_question = QUESTIONS[0]
        now = datetime.now(timezone.utc).isoformat()

        rooms_table.update_item(
            Key={"roomId": room_id},
            UpdateExpression="SET #s = :s, currentRound = :r, currentQuestion = :q, startedAt = :t",
            ExpressionAttributeNames={"#s": "status"},
            ExpressionAttributeValues={
                ":s": "ANSWERING",
                ":r": 1,
                ":q": first_question["questionId"],
                ":t": now,
            },
        )

        broadcast(room_id, {
            "action": "gameStarted",
            "round": 1,
            "totalRounds": len(QUESTIONS),
            "question": first_question,
            "startedAt": now,
        })

        return {
            "statusCode": 200,
            "headers": {"Content-Type": "application/json"},
            "body": json.dumps({"message": "Game started", "round": 1}),
        }

    except Exception:
        return _error(500, "Internal server error")


def _error(status: int, message: str) -> dict:
    return {
        "statusCode": status,
        "headers": {"Content-Type": "application/json"},
        "body": json.dumps({"error": message}),
    }
