# システムアーキテクチャ概要

**作成日**: 2026-03-20
**バージョン**: 1.0

---

## 全体構成図

```
[Android App]
     │
     ├── REST API (HTTPS)
     │       └── API Gateway (REST) → Lambda → DynamoDB
     │
     └── WebSocket (WSS)
             └── API Gateway (WebSocket) → Lambda → DynamoDB
                                                 └── (broadcast) → 全接続クライアント
```

---

## AWS構成

| コンポーネント | サービス | 用途 |
|--------------|---------|------|
| REST API | API Gateway + Lambda | ルーム作成、QR生成、回答送信等 |
| WebSocket | API Gateway WebSocket + Lambda | リアルタイム状態同期 |
| DB | DynamoDB | ルーム・プレイヤー・回答・スコア管理 |
| QR生成 | Lambda（qrcode ライブラリ） | QRコード画像生成 |
| IaC | Terraform | インフラ構築・管理 |

---

## DynamoDB テーブル設計（案）

### Rooms テーブル
| PK | SK | Attributes |
|----|----|-----------|
| roomId | "ROOM" | hostConnectionId, status, createdAt, TTL |

### Players テーブル
| PK | SK | Attributes |
|----|----|-----------|
| roomId | playerId | nickname, connectionId, isHost, joinedAt |

### Answers テーブル
| PK | SK | Attributes |
|----|----|-----------|
| roomId#round | playerId | answer, prediction, score, answeredAt |

---

## WebSocketイベント一覧

| イベント | 方向 | 説明 |
|---------|------|------|
| $connect | Client→Server | 接続確立 |
| $disconnect | Client→Server | 接続切断 |
| joinRoom | Client→Server | ルーム参加 |
| playerJoined | Server→Client | 参加者追加通知（全員に） |
| gameStarted | Server→Client | ゲーム開始（全員に） |
| questionRevealed | Server→Client | 質問配信（全員に） |
| answerSubmitted | Client→Server | 回答送信 |
| allAnswered | Server→Client | 全員回答完了通知 |
| predictionSubmitted | Client→Server | 予測送信 |
| roundResult | Server→Client | ラウンド結果配信（全員に） |
| gameEnded | Server→Client | ゲーム終了・最終結果（全員に） |

---

## フェーズ状態遷移

```
WAITING（待合室）
  ↓ ホストがゲーム開始
ANSWERING（回答フェーズ：30秒）
  ↓ 全員回答 or タイムアウト
PREDICTING（予測フェーズ：20秒）
  ↓ 全員予測 or タイムアウト
RESULT（結果表示：10秒）
  ↓ 次のラウンドへ or ゲーム終了
FINISHED（最終結果）
```

---

## スコア計算式（案）

```
差分 = |予測値 - 実際の人数|
スコア = max(0, 100 - 差分 × 20)
```

例：実際5人、予測4人 → 差分1 → 80点
例：実際5人、予測5人 → 差分0 → 100点（パーフェクト）
