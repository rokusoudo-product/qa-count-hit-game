"""
POST /rooms
ルームを作成してroomIdを返す
"""
import json
import os
import uuid
from datetime import datetime, timezone, timedelta

import boto3

dynamodb = boto3.resource("dynamodb")
rooms_table = dynamodb.Table(os.environ["ROOMS_TABLE"])
TTL_HOURS = int(os.environ.get("ROOM_TTL_HOURS", "24"))


def handler(event, context):
    try:
        body = json.loads(event.get("body") or "{}")
        host_name = body.get("hostName", "ホスト")

        room_id = str(uuid.uuid4())[:8].upper()
        now = datetime.now(timezone.utc)
        ttl = int((now + timedelta(hours=TTL_HOURS)).timestamp())

        rooms_table.put_item(Item={
            "roomId": room_id,
            "hostName": host_name,
            "status": "WAITING",
            "currentRound": 0,
            "createdAt": now.isoformat(),
            "ttl": ttl,
        })

        return {
            "statusCode": 201,
            "headers": {"Content-Type": "application/json"},
            "body": json.dumps({"roomId": room_id}),
        }

    except Exception as e:
        return {
            "statusCode": 500,
            "headers": {"Content-Type": "application/json"},
            "body": json.dumps({"error": "Internal server error"}),
        }
