# システムアーキテクチャ概要

**作成日**: 2026-03-20
**最終更新**: 2026-03-20（AWS → Firebase移行）
**バージョン**: 2.0

---

## 全体構成図

```
[Android App]
     │
     ├── Callable Functions (HTTPS)
     │       └── Firebase Cloud Functions (Python 3.12)
     │                └── Firestore（読み書き）
     │
     └── Firestore Real-time Listener
             └── Firestore → onSnapshot → UI更新
                 （WebSocket不要・自動再接続）
```

---

## Firebase構成

| コンポーネント | サービス | 用途 |
|--------------|---------|------|
| リアルタイムDB | Cloud Firestore | ゲーム状態・プレイヤー・回答管理 |
| サーバーレス関数 | Cloud Functions (Python 3.12) | ゲームロジック・採点 |
| リアルタイム通信 | Firestoreリスナー | WebSocket代替・自動再接続 |
| Android SDK | Firebase SDK for Android | Firestore + Functions クライアント |

---

## Firestore データ構造

```
rooms/{roomId}
  ├── hostName: string
  ├── status: "WAITING" | "ANSWERING" | "PREDICTING" | "RESULT" | "FINISHED"
  ├── currentRound: number
  ├── totalRounds: number
  ├── currentQuestion: { questionId, text, options, answerSeconds, predictSeconds }
  ├── answerCounts: { "選択肢A": 3, "選択肢B": 2 }
  ├── roundScores: [PlayerScore]
  ├── finalScores: [PlayerScore]
  └── createdAt: timestamp

rooms/{roomId}/players/{playerId}
  ├── nickname: string
  ├── isHost: boolean
  └── joinedAt: timestamp

rooms/{roomId}/rounds/{round}/answers/{playerId}
  ├── answer: string
  ├── prediction: number
  ├── targetOption: string
  ├── roundScore: number
  └── answeredAt: timestamp
```

---

## Cloud Functions 一覧

| 関数名 | トリガー | 説明 |
|--------|---------|------|
| `create_room` | Callable | ルームID生成・Firestore保存 |
| `join_room` | Callable | プレイヤー参加・バリデーション |
| `start_game` | Callable | ゲーム開始・最初の質問セット |
| `submit_answer` | Callable | 回答保存・全員完了で予測フェーズ移行 |
| `submit_prediction` | Callable | 予測保存・採点・次ラウンド/終了 |

---

## フェーズ状態遷移

```
WAITING（待合室）
  ↓ ホストがゲーム開始
ANSWERING（回答フェーズ：30秒）
  ↓ 全員回答完了
PREDICTING（予測フェーズ：20秒）
  ↓ 全員予測完了
RESULT（結果表示：10秒）
  ↓ 次のラウンドへ or ゲーム終了
FINISHED（最終結果）
```

**状態変更はFirestoreドキュメントの更新で行い、
AndroidはonSnapshotリスナーで自動検知・UI更新する。**

---

## スコア計算式

```
差分 = |予測値 - 実際の人数|
スコア = max(0, 100 - 差分 × 20)
```

---

## AWSからの移行メモ

旧AWS実装は `archive/` ディレクトリに保存済み。
- WebSocket API Gateway → Firestoreリアルタイムリスナー
- Lambda → Cloud Functions
- DynamoDB → Cloud Firestore
- Terraform → Firebase CLI
