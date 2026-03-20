"""
フェーズタイムアウトハンドラ（BE-013）
EventBridgeスケジューラまたはStep Functionsから呼び出す。
回答/予測フェーズがタイムアウトした際に強制的に次フェーズへ進める。
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
    """
    event:
      roomId: str
      phase: "ANSWERING" | "PREDICTING"
      round: int
    """
    room_id = event.get("roomId")
    expected_phase = event.get("phase")
    round_num = int(event.get("round", 1))

    if not room_id or not expected_phase:
        return {"statusCode": 400, "body": "roomId and phase are required"}

    room = rooms_table.get_item(Key={"roomId": room_id}).get("Item")
    if not room:
        return {"statusCode": 404, "body": "Room not found"}

    # 既に次フェーズに進んでいれば何もしない
    if room.get("status") != expected_phase:
        return {"statusCode": 200, "body": "Phase already advanced"}

    room_round_key = f"{room_id}#{round_num}"
    question = QUESTIONS[round_num - 1]

    if expected_phase == "ANSWERING":
        # 未回答者はスキップ扱いで集計
        answers = answers_table.query(
            KeyConditionExpression=Key("roomRound").eq(room_round_key)
        )["Items"]
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
            "round": round_num,
            "question": question,
            "answerCounts": counts,
            "timedOut": True,
        })

    elif expected_phase == "PREDICTING":
        # 未予測者は0点として採点
        all_answers = answers_table.query(
            KeyConditionExpression=Key("roomRound").eq(room_round_key)
        )["Items"]
        players = players_table.query(
            KeyConditionExpression=Key("roomId").eq(room_id)
        )["Items"]

        counts = {opt: 0 for opt in question["options"]}
        for a in all_answers:
            opt = a.get("answer")
            if opt in counts:
                counts[opt] += 1

        answered_ids = {a["playerId"] for a in all_answers}
        for player in players:
            if player["playerId"] not in answered_ids:
                answers_table.put_item(Item={
                    "roomRound": room_round_key,
                    "playerId": player["playerId"],
                    "answer": "",
                    "prediction": 0,
                    "targetOption": question["options"][0],
                    "roundScore": 0,
                    "timedOut": True,
                    "answeredAt": datetime.now(timezone.utc).isoformat(),
                })

        is_last = round_num >= len(QUESTIONS)
        next_status = "FINISHED" if is_last else "ANSWERING"

        rooms_table.update_item(
            Key={"roomId": room_id},
            UpdateExpression="SET #s = :s" + ("" if is_last else ", currentRound = :r"),
            ExpressionAttributeNames={"#s": "status"},
            ExpressionAttributeValues={
                ":s": next_status,
                **({":r": round_num + 1} if not is_last else {}),
            },
        )

        action = "gameEnded" if is_last else "roundResult"
        broadcast(room_id, {
            "action": action,
            "round": round_num,
            "timedOut": True,
            **({"nextRound": round_num + 1, "nextQuestion": QUESTIONS[round_num]} if not is_last else {}),
        })

    return {"statusCode": 200, "body": "Timeout handled"}
