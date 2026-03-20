# Firebase セットアップガイド

**作成日**: 2026-03-20

---

## 1. Firebaseプロジェクト作成

1. https://console.firebase.google.com/ にアクセス
2. 「プロジェクトを追加」→ プロジェクト名: `hitokazu-game`
3. Googleアナリティクスは任意（OFFで可）

---

## 2. Firestore設定

1. Firebase Console → **Firestore Database** → 「データベースを作成」
2. **本番環境モード** で開始（rules は `backend/firestore.rules` で管理）
3. リージョン: `asia-northeast1`（東京）

---

## 3. Cloud Functions設定

1. Firebase Console → **Functions** → 有効化
2. **Blazeプラン（従量制）** へのアップグレードが必要（無料枠内で運用可能）

---

## 4. Firebase CLI セットアップ

```bash
# Firebase CLIインストール
npm install -g firebase-tools

# ログイン
firebase login

# プロジェクトID確認・設定
cd hitokazu_game/backend
firebase use hitokazu-game  # 実際のプロジェクトIDに置き換え
```

---

## 5. デプロイ

```bash
cd hitokazu_game/backend

# Firestoreルールのデプロイ
firebase deploy --only firestore:rules

# Cloud Functionsのデプロイ
firebase deploy --only functions
```

---

## 6. Androidアプリの設定

1. Firebase Console → **プロジェクトの設定** → 「アプリを追加」→ Android
2. Android パッケージ名: `com.rokusoudo.hitokazu`
3. `google-services.json` をダウンロード
4. `android/app/google-services.json` に配置（`.gitignore` で除外済み）

---

## 7. ローカル開発（エミュレータ）

```bash
# Firebaseエミュレータ起動
firebase emulators:start --only firestore,functions

# Androidアプリでエミュレータに接続する場合
# FirebaseRepository.kt に以下を追加：
# Firebase.firestore.useEmulator("10.0.2.2", 8080)
# Firebase.functions("asia-northeast1").useEmulator("10.0.2.2", 5001)
```

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
  ├── roundScores: [{ playerId, targetOption, predictedCount, actualCount, roundScore }]
  ├── finalScores: [{ ... }]
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

## Cloud Functions エンドポイント

| 関数名 | 説明 |
|--------|------|
| `create_room` | ルーム作成 |
| `join_room` | ルーム参加 |
| `start_game` | ゲーム開始 |
| `submit_answer` | 回答送信 |
| `submit_prediction` | 予測送信（採点含む） |
