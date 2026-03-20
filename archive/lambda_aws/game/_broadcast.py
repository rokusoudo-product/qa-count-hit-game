"""
共通ユーティリティ: WebSocket broadcast
"""
import json
import os

import boto3

dynamodb = boto3.resource("dynamodb")
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
    payload = json.dumps(message, ensure_ascii=False).encode("utf-8")
    for conn in result.get("Items", []):
        try:
            apigw.post_to_connection(
                ConnectionId=conn["connectionId"],
                Data=payload,
            )
        except apigw.exceptions.GoneException:
            connections_table.delete_item(Key={"connectionId": conn["connectionId"]})
