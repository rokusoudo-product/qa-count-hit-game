"""
POST /rooms/{roomId}/join
ルームに参加し、WebSocket経由で全員に通知する
"""
import json
import os
import uuid
from datetime import datetime, timezone

import boto3
from boto3.dynamodb.conditions import Key

dynamodb = boto3.resource("dynamodb")
rooms_table = dynamodb.Table(os.environ["ROOMS_TABLE"])
players_table = dynamodb.Table(os.environ["PLAYERS_TABLE"])
connections_table = dynamodb.Table(os.environ["CONNECTIONS_TABLE"])

apigw = boto3.client(
    "apigatewaymanagementapi",
    endpoint_url=os.environ["WEBSOCKET_API_URL"],
)


def broadcast(room_id: str, message: dict):
    """ルーム内の全接続にメッセージを送信する"""
    result = connections_table.scan(
        FilterExpression=boto3.dynamodb.conditions.Attr("roomId").eq(room_id)
    )
    payload = json.dumps(message).encode("utf-8")
    for conn in result.get("Items", []):
        try:
            apigw.post_to_connection(
                ConnectionId=conn["connectionId"],
                Data=payload,
            )
        except apigw.exceptions.GoneException:
            connections_table.delete_item(Key={"connectionId": conn["connectionId"]})


def handler(event, context):
    room_id = event["pathParameters"]["roomId"]

    try:
        body = json.loads(event.get("body") or "{}")
        nickname = (body.get("nickname") or "").strip()

        if not nickname:
            return {
                "statusCode": 400,
                "headers": {"Content-Type": "application/json"},
                "body": json.dumps({"error": "nickname is required"}),
            }

        # ルーム存在・状態チェック
        room = rooms_table.get_item(Key={"roomId": room_id}).get("Item")
        if not room:
            return {
                "statusCode": 404,
                "headers": {"Content-Type": "application/json"},
                "body": json.dumps({"error": "Room not found"}),
            }
        if room.get("status") != "WAITING":
            return {
                "statusCode": 409,
                "headers": {"Content-Type": "application/json"},
                "body": json.dumps({"error": "Game already started"}),
            }

        # 満員チェック（最大20人）
        existing = players_table.query(
            KeyConditionExpression=Key("roomId").eq(room_id)
        )
        if existing["Count"] >= 20:
            return {
                "statusCode": 409,
                "headers": {"Content-Type": "application/json"},
                "body": json.dumps({"error": "Room is full"}),
            }

        player_id = str(uuid.uuid4())
        players_table.put_item(Item={
            "roomId": room_id,
            "playerId": player_id,
            "nickname": nickname,
            "isHost": False,
            "joinedAt": datetime.now(timezone.utc).isoformat(),
        })

        # 全員にプレイヤー参加を通知
        broadcast(room_id, {
            "action": "playerJoined",
            "playerId": player_id,
            "nickname": nickname,
            "totalPlayers": existing["Count"] + 1,
        })

        return {
            "statusCode": 200,
            "headers": {"Content-Type": "application/json"},
            "body": json.dumps({
                "playerId": player_id,
                "roomId": room_id,
                "nickname": nickname,
            }),
        }

    except Exception as e:
        return {
            "statusCode": 500,
            "headers": {"Content-Type": "application/json"},
            "body": json.dumps({"error": "Internal server error"}),
        }
