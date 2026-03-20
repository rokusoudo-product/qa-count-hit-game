"""
WebSocket $connect handler
接続時にconnectionIdをDynamoDBに保存する
"""
import json
import os
import boto3
from datetime import datetime, timezone

dynamodb = boto3.resource("dynamodb")
connections_table = dynamodb.Table(os.environ["CONNECTIONS_TABLE"])


def handler(event, context):
    connection_id = event["requestContext"]["connectionId"]
    room_id = event.get("queryStringParameters", {}).get("roomId")

    item = {
        "connectionId": connection_id,
        "connectedAt": datetime.now(timezone.utc).isoformat(),
    }
    if room_id:
        item["roomId"] = room_id

    connections_table.put_item(Item=item)

    return {"statusCode": 200, "body": "Connected"}
