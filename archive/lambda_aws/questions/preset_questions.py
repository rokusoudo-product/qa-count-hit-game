"""
プリセット質問データ
GET /questions で返す質問リスト
"""
import json
import os

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


def handler(event, context):
    return {
        "statusCode": 200,
        "headers": {"Content-Type": "application/json"},
        "body": json.dumps({"questions": QUESTIONS}, ensure_ascii=False),
    }
