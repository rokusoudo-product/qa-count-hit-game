"""
GET /rooms/{roomId}/qr
QRコードをbase64エンコードして返す
依存: qrcode, Pillow (Lambda Layer経由)
"""
import base64
import io
import json
import os

import boto3
import qrcode
from qrcode.image.styledpil import StyledPilImage

dynamodb = boto3.resource("dynamodb")
rooms_table = dynamodb.Table(os.environ["ROOMS_TABLE"])


def handler(event, context):
    room_id = event["pathParameters"]["roomId"]

    # ルーム存在チェック
    result = rooms_table.get_item(Key={"roomId": room_id})
    if "Item" not in result:
        return {
            "statusCode": 404,
            "headers": {"Content-Type": "application/json"},
            "body": json.dumps({"error": "Room not found"}),
        }

    # QRコード生成（ルームIDをエンコード）
    qr = qrcode.QRCode(
        version=1,
        error_correction=qrcode.constants.ERROR_CORRECT_M,
        box_size=10,
        border=4,
    )
    qr.add_data(room_id)
    qr.make(fit=True)

    img = qr.make_image(fill_color="black", back_color="white")

    buffer = io.BytesIO()
    img.save(buffer, format="PNG")
    img_base64 = base64.b64encode(buffer.getvalue()).decode("utf-8")

    return {
        "statusCode": 200,
        "headers": {"Content-Type": "application/json"},
        "body": json.dumps({
            "roomId": room_id,
            "qrCode": img_base64,
            "format": "image/png",
        }),
    }
