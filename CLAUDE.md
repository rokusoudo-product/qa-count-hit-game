# 人数当てゲーム 開発プロジェクト

## プロジェクト概要
複数人でQRコードを使ってグループを作り、質問への回答人数を当てるAndroidアプリ。
株式会社六創堂の技術アピール用プロダクトとしてGoogle Playで公開予定。

## ゲームフロー
1. ホストがルーム作成・QRコード発行
2. 参加者がQRをスキャンして入室
3. 全員に同じ質問が表示される（例：「犬派？猫派？」）
4. 全員が回答する
5. 「〇〇を選んだのは何人？」を当てるフェーズ
6. 正解に近い人が高得点→採点・結果表示

## 技術スタック

> 2026-03-20 に **AWS から Firebase へ移行済み**。AWS 実装（Terraform / Lambda / DynamoDB）は
> `archive/` に退避してあり、現行構成では使用しない。詳細は `docs/architecture.md`（v2.0）。

- **フロント**: Android / Kotlin / Jetpack Compose（Navigation Compose + ViewModel）
- **バックエンド**: Python 3.12 / Firebase Cloud Functions（HTTPS 関数・us-central1）
- **DB**: Cloud Firestore（ルーム・プレイヤー・回答・スコア管理）
- **リアルタイム通信**: Firestore リスナー（onSnapshot）。WebSocket は使わない
- **QRコード**: Android 側で生成・スキャンとも完結（ZXing）
- **Web**: Firebase Hosting（招待リンク `web/public/join/`）
- **IaC**: `backend/firebase.json`（Functions / Firestore / Hosting / Emulator 設定）

パッケージ名: `com.rokusoudo.hitokazu`

## エージェント構成
| エージェント | 役割 |
|------------|------|
| POエージェント | 要件定義・仕様策定・バックログ管理 |
| エンジニアエージェント | 設計・実装・インフラ構築 |

## POエージェントへの指示
あなたは「人数当てゲーム」のプロダクトオーナーです。
このディレクトリ（/home/zakis/hitokazu_game）を作業場所として、
要件定義・ユーザーストーリー・バックログ管理を担当してください。
仕様はdocs/ディレクトリにMarkdownで管理してください。

## エンジニアエージェントへの指示
あなたは「人数当てゲーム」のエンジニアです。
このディレクトリ（/home/zakis/hitokazu_game）を作業場所として、
POの仕様（docs/）をもとに設計・実装を担当してください。
バックエンドはbackend/、Androidアプリはandroid/に配置してください。

実装前に `docs/architecture.md` を読むこと。`archive/` の AWS 実装は
移行前の遺構なので参照しない。

## ディレクトリ構成
```
hitokazu_game/                    # リポジトリ名は qa-count-hit-game
├── CLAUDE.md                     # このファイル（プロジェクト共通情報）
├── docs/                         # PO管理：仕様・要件・バックログ
│   ├── architecture.md           #   システム構成（v2.0 / Firebase）
│   ├── firebase_setup.md
│   ├── requirements.md
│   ├── user_stories.md
│   └── backlog.md
├── backend/                      # エンジニア：Firebase
│   ├── firebase.json             #   Functions / Firestore / Hosting / Emulator
│   ├── firestore.rules
│   ├── functions/main.py         #   Cloud Functions 本体
│   ├── test_logic.py             #   ロジック単体（Firestore 非依存）
│   ├── test_game_flow.py         #   Emulator 統合テスト
│   └── test_functions.py         #   ⚠️ 本番 Firestore に直接書き込む
├── android/                      # エンジニア：Kotlin / Compose
├── web/                          # Firebase Hosting（招待リンク）
└── archive/                      # AWS 旧実装（参照のみ・使用しない）
```

## テスト実行

```bash
cd backend

# ロジック単体（依存なし）
python3 test_logic.py

# Emulator 統合テスト（firebase CLI + Java が必要）
firebase emulators:exec --only firestore,auth \
  "FIRESTORE_EMULATOR_HOST=localhost:8080 python3 test_game_flow.py"
```

⚠️ `test_functions.py` は **本番 Firestore（`hitokazu-game`）に直接書き込む**スクリプト。
末尾に削除処理はあるが、途中で失敗するとテストデータが本番に残る。
内容は `test_game_flow.py` とほぼ重複するため、通常は実行しないこと。
