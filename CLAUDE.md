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
> `archive/` に退避してあり、現行構成では使用しない。詳細は `docs/architecture.md`（v3.0）。
>
> ⚠️ **Cloud Functions は実装済みだが、Android・Web のどちらからも呼び出していない。**
> クライアントは Firestore を直接読み書きし、採点・フェーズ遷移もクライアント側で行う。

- **フロント**: Android / Kotlin / Jetpack Compose（Navigation Compose + ViewModel）
- **バックエンド**: Python 3.12 / Firebase Cloud Functions（HTTPS 関数・us-central1）
- **DB**: Cloud Firestore（ルーム・プレイヤー・回答・スコア管理）
- **リアルタイム通信**: Firestore リスナー（onSnapshot）。WebSocket は使わない
- **QRコード**: Android 側で生成・スキャンとも完結（ZXing）
- **Web**: Firebase Hosting（ゲーム本体 `backend/web/index.html` ＋ 招待リンク `backend/web/join/`。Hosting設定は `backend/firebase.json` の1つに統一。Issue #28）
- **IaC**: `backend/firebase.json`（Functions / Firestore / Hosting / Emulator 設定）

パッケージ名: `com.rokusoudo.hitokazu`

## エージェント構成
| エージェント | 役割 |
|------------|------|
| POエージェント | 要件定義・仕様策定・GitHub Issue の起票と優先度づけ |
| エンジニアエージェント | 設計・実装・インフラ構築 |

## POエージェントへの指示
あなたは「人数当てゲーム」のプロダクトオーナーです。
このディレクトリ（/home/zakis/hitokazu_game）を作業場所として、
要件定義・ユーザーストーリーの策定と GitHub Issue の管理を担当してください。
仕様はdocs/ディレクトリにMarkdownで管理し、バックログは GitHub Issue を正としてください。

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
├── docs/                         # PO管理：仕様・要件（バックログは GitHub Issue が正）
│   ├── architecture.md           #   システム構成（v3.0 / Firebase）
│   ├── firebase_setup.md
│   ├── requirements.md           #   要件定義（v2.0）
│   └── user_stories.md
├── shared/
│   └── questions.json            #   質問マスタの正本（36問。Issue #16）
├── scripts/
│   └── generate_questions.py     #   shared/questions.json → Kotlin/JS/Python への生成スクリプト
├── backend/                      # エンジニア：Firebase
│   ├── firebase.json             #   Functions / Firestore / Hosting / Emulator
│   ├── firestore.rules
│   ├── functions/main.py         #   Cloud Functions 本体
│   ├── functions/game_logic.py   #   採点・集計・フェーズ遷移の純粋関数（Firestore非依存。Issue #29）
│   ├── functions/questions.py    #   質問マスタ（自動生成・直接編集しない）
│   ├── web/index.html            #   Webクライアント（質問マスタは生成マーカーで自動反映）
│   ├── test_logic.py             #   ロジック単体（Firestore 非依存。game_logic.py を直接import）
│   ├── test_questions_sync.py    #   質問マスタの正本と3実装の同期テスト（Firestore 非依存）
│   ├── test_room_expiry.py       #   ルーム保持期限・自動削除（delete_expired_rooms）の Emulator 統合テスト（Issue #34）
│   ├── test_game_flow.py         #   Emulator 統合テスト
│   └── test_functions.py         #   ⚠️ 本番 Firestore に直接書き込む
├── android/                      # エンジニア：Kotlin / Compose
│   ├── .../data/questions/Questions.kt  # 質問マスタ（自動生成・直接編集しない）
│   ├── .../game/GameLogic.kt      # 採点・累計スコアの純粋関数（app/src/testで単体テスト。Issue #29）
│   └── app/src/test/.../game/GameLogicTest.kt  # 上記のユニットテスト
├── web/                          # Firebase Hosting（招待リンク）
└── archive/                      # AWS 旧実装（参照のみ・使用しない）
    └── backlog_aws.md            #   AWS 時代のバックログ（凍結。現行は GitHub Issue）
```

### バックログの正本は GitHub Issue

プロダクトバックログは **[GitHub Issue](https://github.com/rokusoudo-product/qa-count-hit-game/issues) を正**とする。
`docs/backlog.md` は GitHub Issue と役割が重複し二重管理になっていたため、2026-08-06 に廃止して
`archive/backlog_aws.md` へ凍結退避した（Issue #19）。ラベル運用は `ISSUE_WORKFLOW.md` に従う。

### 質問マスタ（36問）の正本管理（Issue #16）

質問マスタは `shared/questions.json` を単一の正本とし、以下の3実装は
そこから **生成** される（直接編集しないこと）。

- `android/app/src/main/java/com/rokusoudo/hitokazu/data/questions/Questions.kt`
- `backend/functions/questions.py`
- `backend/web/index.html`（`// GENERATED:QUESTIONS:START` 〜 `:END` の区間のみ）

質問を追加・変更する場合は `shared/questions.json` を編集してから、

```bash
python3 scripts/generate_questions.py         # 3実装に反映する
python3 scripts/generate_questions.py --check # 同期しているか検証するだけ（CI で使用）
python3 backend/test_questions_sync.py        # 正本と3実装の内容一致を検証する
```

を実行すること。

## テスト実行

```bash
cd android

# Androidユニットテスト（Firestore非依存の採点・累計スコアロジックのみ。Issue #29）
./gradlew testDebugUnitTest
```

```bash
cd backend

# ロジック単体（依存なし。backend/functions/game_logic.py を直接import）
python3 test_logic.py

# Emulator 統合テスト（firebase CLI + Java が必要）
firebase emulators:exec --only firestore,auth \
  "FIRESTORE_EMULATOR_HOST=localhost:8080 python3 test_game_flow.py"

# ルーム保持期限・自動削除の Emulator 統合テスト（Issue #34）
firebase emulators:exec --project hitokazu-game --only firestore \
  "FIRESTORE_EMULATOR_HOST=localhost:8080 python3 test_room_expiry.py"
```

⚠️ `test_functions.py` は **本番 Firestore（`hitokazu-game`）に直接書き込む**スクリプト。
末尾に削除処理はあるが、途中で失敗するとテストデータが本番に残る。
内容は `test_game_flow.py` とほぼ重複するため、通常は実行しないこと。
