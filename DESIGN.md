# DESIGN.md — 人数当てゲーム（qa-count-hit-game）

> 本ファイルは Issue #37（配色トークンの統一）を起点に作成した。
> 全プロジェクト共通の上位規範は `~/.claude/DESIGN_STANDARDS.md`。矛盾する場合はそちらが正。

## 基本方針

- 対象プラットフォーム: Android（ネイティブアプリ） / Web（招待リンク用の軽量クライアント）
- 準拠ガイドライン: Android は Material Design 3（Jetpack Compose Material3）。Web は特定ガイドラインなし・WCAG 2.1 AA を目標（本Issueでは配色トークン整備のみ。コントラスト比の網羅監査は別Issue）
- 使用コンポーネントライブラリ: Android = Material3 Compose。Web = 素の HTML/CSS（フルスクラッチ。shadcn/ui 等の導入は本Issueのスコープ外）
- トーン&マナー: 友人同士で気軽に遊ぶパーティーゲーム。明るく親しみやすい配色

## ⚠️ 未確定事項（代表の最終承認が必要）

Issue #37 の起票時点で以下2点は代表の回答待ちだったため、po_agent（Sonnet サブエージェント）の判断で**暫定的に**決定した。以下は正式決定ではなく、代表のレビュー・承認を経て確定する。

1. **プライマリ/アクセントカラーの統一方向**: 先に実装され、Android 9画面全てで使われている Android の配色（青 `#1976D2` / オレンジ `#FF6F00`）を暫定の正として採用し、Web 側（紫 `#6200EA` 系）をこちらに合わせた。Web の紫を正とする、または第三の配色を新設する可能性も残っている
2. **ダークテーマ対応**: 本Issueのスコープ外とした。現状 `Theme.kt` は `lightColorScheme` のみで、ダークトークンは未定義。`DESIGN_STANDARDS.md` 4節は「ダークモード対応を初期設計に含める」としているが、既存プロダクトへの後付けとなるため別Issueで扱う（下記「プロジェクト固有ルール」に理由を記録）

さらに実装中に**新たに判明した論点**（Issue #37 起票時点では未把握）:

3. **Android の `primaryContainer` 等が Material3 の既定値（薄紫）のまま**: `Theme.kt` の `lightColorScheme(...)` は `primary` / `secondary` / `background` / `surface` / `onPrimary` / `onSecondary` / `onBackground` / `onSurface` の8ロールしか上書きしておらず、`primaryContainer`（質問カードなどの背景。`AnsweringScreen` / `ResultScreen` / `WaitingRoomScreen` / `FinishedScreen` で使用）・`secondaryContainer`（`ResultScreen` の集計カード）・`error` / `errorContainer`（`ConnectionBanner`）は Material3 の既定パープル系配色のまま残っている（実機で実測: 後述）。これは Issue #37 の色の棚卸し表（Android=青/オレンジ、Web=紫）には載っていなかった**intra-Android の不整合**であり、「primary/secondary を統一すればブランドカラーが揃う」という前提だけでは解決しない部分だった。本Issueでは新たな色を追加設計する判断はせず、**Android の実測値をそのまま Web にも適用**（後述のトークン表）することで見た目を一致させた。`primaryContainer` 等を意図的な配色に置き換えるかどうかは別Issueで検討する

## カラートークン

| トークン | 値 | 用途 |
|---------|-----|------|
| `--color-primary` | `#1976D2` | 主要アクション（送信・参加ボタン、回答選択肢の未選択状態、強調数値） |
| `--color-primary-container` | `#EADDFF` | 質問カード・スコアカードの背景。**Android の `MaterialTheme.colorScheme.primaryContainer` を実機で実測した値**（Material3既定のパープル系。`primary` からの自動算出ではない。上記「新たに判明した論点」参照）。Android の `secondaryContainer`（実測値 `#E8DEF8`）とはほぼ同色で肉眼では区別できないため、Web側トークンは1本に統合した |
| `--color-on-primary-container` | `#21005D` | primary-container 上のテキスト（質問文など）。Android の `onPrimaryContainer` 実測値 |
| `--color-secondary` | `#FF6F00` | アクセント。回答選択肢の**選択中**状態のハイライトに使用 |
| `--color-background` | `#F5F5F5` | 画面背景 |
| `--color-surface` | `#FFFFFF` | カード・パネル・入力欄の背景 |
| `--color-surface-alt` | `#E0E0E0` | 中立トーンのボタン（戻る・キャンセル等） |
| `--color-text-primary` | `#333333` | 見出し・本文 |
| `--color-text-secondary` | `#555555` | 補助テキスト・ラベル |
| `--color-text-muted` | `#888888` | さらに弱いテキスト（待機人数・スピナー・ラウンド情報） |
| `--color-text-faint` | `#AAAAAA` | 最も弱いテキスト（区切りの「または」等） |
| `--color-border` | `#DDDDDD` | 入力欄の枠線 |
| `--color-divider` | `#EEEEEE` | リスト・カード内の区切り線 |
| `--color-error` | `#D32F2F` | エラーメッセージ文字色 |
| `--color-error-bg` | `#FFEBEE` | エラーメッセージ背景 |
| `--color-success` | `#388E3C` | 成功・完了メッセージ（回答送信済み等） |
| `--color-warning` | `#FFA000` | 警告（残り時間わずか）。現状 Android の `CountdownTimer` のみで使用。Web には対応する視覚表示が存在しない（下記画面一覧の備考参照） |
| `--color-primary-hover` | `#1565C0` | ボタンの hover 背景（Web のみ）。Issue #52 で追加。`--color-primary`（`#1976D2`）を Material Blue 800 相当に1段暗くした値。招待ページで従来使われていたインディゴ由来の `#303f9f` は廃止した。白文字とのコントラスト比は約5.7:1（WCAG AA 通常テキスト基準 4.5:1 を満たす） |
| `--color-disabled` | `#AAAAAA` | 無効化ボタンの背景（Web のみ）。Issue #52 で追加。既存の `--color-text-faint` と同値だが、テキスト色トークンを背景用途に流用すると意味が合わないため独立したトークンとして新設した |

Android 側の対応（`ui/theme/Theme.kt`）:

| Compose ColorScheme ロール | 値 | 対応トークン |
|---|---|---|
| `primary` | `#1976D2` | `--color-primary` |
| `secondary` | `#FF6F00` | `--color-secondary` |
| `background` | `#F5F5F5` | `--color-background` |
| `surface` | `#FFFFFF` | `--color-surface` |
| `primaryContainer`（Material3既定・実測） | `#EADDFF` | `--color-primary-container` |
| `onPrimaryContainer`（Material3既定・実測） | `#21005D` | `--color-on-primary-container` |
| `secondaryContainer`（Material3既定・実測） | `#E8DEF8` | `--color-primary-container` に統合（上記参照） |
| `error`（Material3既定・実測） | `#B3261E` | `--color-error`（`#D32F2F`）とは意図的に不一致のまま。次項参照 |
| `errorContainer`（Material3既定・実測） | `#F9DEDC` | 未対応（`--color-error-bg` は独自に `#FFEBEE` を使用。次項参照） |
| （新規）`Warning`（テーマ内トップレベル定数） | `#FFA000` | `--color-warning` |

実測方法: `HomeScreen` に一時的な `Log.d` を仕込み、実機（エミュレータ）で `MaterialTheme.colorScheme` の各ロールを出力して確認した（確認後にコードは削除済み。コミットには含まれない）。

## タイポグラフィ

- フォント: 日本語UIのためシステムデフォルト（Android）／ `'Hiragino Sans', 'Meiryo', sans-serif`（Web）。Noto Sans JP の導入は未実施（次回のタイポグラフィ整備Issueで検討）
- 現状のスケール（本Issueでは変更しない。配色トークン統一のみがスコープのため）:

| 用途 | サイズ |
|------|--------|
| 画面タイトル（h1） | 22px |
| 見出し（h2） | 18px |
| 質問文・獲得点数の強調表示 | 20px |
| 回答ボタン文字 | 18px |
| 本文・入力欄 | 16px |
| 参加者リスト・カウント表示 | 15px |
| 補助ラベル・タイマー残り秒数 | 14px |
| ラウンド情報 | 13px |
| バッジ | 11px |

- **DESIGN_STANDARDS.md の固定スケール（12/14/16/20/24/32）からの逸脱を確認済み**（22/18/15/13/11px が該当）。既存実装をそのまま記録したもので、本Issueでは是正しない（スコープは配色トークンの統一のみ）。タイポグラフィのスケール統一は別Issueで扱う（プロジェクト固有ルール参照）

## 余白・レイアウト

- 8ptグリッド準拠（例外: `.count-bar .num` の `margin: 0 8px` 等、既存実装は概ね準拠）
- 画面の基本構造: Web は中央寄せの単一カード（`max-width: 480px`）。Android は画面いっぱいの Compose Column + 上下 Spacer

## 画面一覧と状態

| 画面 | 目的 | Android | Web | 備考 |
|------|------|---------|-----|------|
| 招待ページ | 招待URL（`/join/{roomId}`）からのニックネーム入力・ルーム存在確認 | なし | `backend/web/join/index.html` | **Android対応なし**（Android は QR スキャンで直接参加）。`backend/web/index.html` とは別ページ（`showScreen()` のSPA画面切り替え対象外）で、確認後にクエリパラメータ付きでゲーム本体へ遷移する。Issue #52 でカラートークンを `backend/web/index.html` と統一 |
| ホーム | ニックネーム入力・ルーム作成/参加の起点 | `HomeScreen.kt` | `#screen-home` | Android はルーム作成をダイアログ内で完結（独立画面ではない） |
| ルーム作成 | ホスト名・質問カテゴリ選択 | `HomeScreen.kt` 内ダイアログ | `#screen-create` | Web は独立画面。Android は同一画面のダイアログ |
| 招待QR表示 | ルームID・招待QR・招待URLの提示（ホスト） | `QrDisplayScreen.kt` | なし | **Web対応なし**。Web参加者はルームIDを手入力するのみ |
| QRスキャン | カメラでQRを読み取り入室（参加者） | `QrScannerScreen.kt` | なし | **Web対応なし**。Web参加者はルームID手入力＋招待リンク直接アクセス |
| 待機室 | 参加者一覧の表示・開始待ち | `WaitingRoomScreen.kt` | `#screen-waiting` | |
| 回答 | 質問への回答選択 | `AnsweringScreen.kt` | `#screen-answering` | 残り時間の可視化（プログレスバー・秒数表示、`CountdownTimer.kt`）は **Androidのみ**。Webはバックグラウンドの `setTimeout` でタイムアウト処理をするが画面上の視覚表示はない |
| 予測 | 回答人数の予測入力 | `PredictingScreen.kt` | `#screen-predicting` | |
| ラウンド結果 | そのラウンドの集計・獲得点数 | `ResultScreen.kt` | `#screen-result` | |
| 最終結果 | 累計スコア・最終順位 | `FinishedScreen.kt` | `#screen-finished` | |
| ホスト退室通知 | ホストが離脱した際の通知画面 | `HostLeftScreen.kt` | `#screen-host-left` | |

4状態（通常/ローディング/空/エラー）の網羅的な監査は本Issueのスコープ外。エラー状態は各画面の `.error` / `home-error` 等の要素で個別に実装済み。

## アセット

現時点で登録された画像アセットなし（アイコン・ロゴ等は未整備）。今後追加する場合は本表と `IMAGE_WORKFLOW.md` の承認ゲートに従う。

| アセットID | 用途 | 使用画面 | 寸法/アスペクト比 | アートスタイル | 生成手段 | 生成済みパス | 生成プロンプト |
|-----------|------|---------|-----------------|--------------|---------|------------|--------------|
| （未登録） | — | — | — | — | — | — | — |

## プロジェクト固有ルール

- **ダークテーマは本Issue（#37）のスコープ外**。`DESIGN_STANDARDS.md` は「ダークモード対応を初期設計に含める」としているが、本プロダクトは既にライトテーマのみで実装済みのため、後付け対応は別Issueで扱う。追加する場合は Android 側 `Theme.kt` に `darkColorScheme` を追加し、Web 側は `prefers-color-scheme: dark` メディアクエリで `:root` トークンを上書きする方針を想定
- **カラートークンの正は暫定（Androidの青/オレンジ）**。代表の承認を得るまで確定としない。承認後、本節の「⚠️ 未確定事項」を削除しトークン表を正式版として扱う
- **回答ボタンの選択状態の見た目を Web 側で変更した**（Issue #37 対応）: 従来 Web は選択中に濃い青 `#0d47a1` ＋淡い青枠 `#82b1ff` を表示していたが、Android の選択状態（`secondary` 色 = オレンジの単色塗り、枠なし）に合わせるため `--color-secondary` の単色塗り・枠なしに変更した。これは配色統一の直接的な帰結であり、画面レイアウト自体の変更ではない
- Material3 の `error`（実測 `#B3261E`）/ `errorContainer`（実測 `#F9DEDC`）ロールは Compose の既定値のままで、Web の `--color-error`（`#D32F2F`）/ `--color-error-bg`（`#FFEBEE`）とは意図的に一致させていない。Web のエラー配色は Issue #37 以前からの既存デザインをそのまま踏襲したもので、両者を統一するかどうかは次回のトークン整備で検討する
- `backend/web/join/index.html`（招待リンクのランディングページ、旧 `web/public/join/`）は Issue #37 の受け入れ基準の対象外だったため配色が独自（インディゴ系統）のままだったが、Issue #52 で `backend/web/index.html` と同じトークンに統一した
- **トークン定義の重複方式（Issue #52）**: `backend/web/index.html` と `backend/web/join/index.html` はビルド工程を持たない素の HTML であり、招待ページを独立した1枚のページとして完結させるため、共通 CSS への切り出しは行わず両ファイルにそれぞれ `:root` を定義する方式を採った。値の正本は本ファイルの「カラートークン」表とし、両ファイルの `:root` はそこから転記する。3つ目の HTML が増えた時点で共通 CSS への切り出しを再検討する
- **招待ページ固有の補助トークン（`--color-success-bg` / `--color-loading-bg`）**: 招待ページには「確認中」「参加登録成功」のメッセージ表示があり、対応する背景色（それぞれ `#E3F2FD` / `#E8F5E9`）が必要だが、`backend/web/index.html` 側に同種のUI要素がなく本表にトークンが存在しない。上記「既存トークンへの置き換えを基本とする」方針の対象外として、招待ページの `:root` のみに局所的なトークンを追加した（本表・`backend/web/index.html` への転記は不要）。ローディングメッセージの文字色は既存の `--color-primary-hover`（`#1565C0`）をそのまま再利用した（招待ページで元々使われていた値と一致するため）

## 検証（Issue #37）

- ローカルの静的サーバー（`python3 -m http.server`）で `backend/web/index.html` を配信し、デスクトップブラウザおよび Android エミュレータの Chrome から実際に配色を確認した
- Android エミュレータ（Pixel6 API35）にデバッグビルドをインストールし、実際に2端末（ネイティブアプリ＝ホスト／Webクライアント＝ゲスト）でルームを作成・参加してゲームを最後まで進行させ、回答画面での配色（未選択=青・選択中=オレンジ・カード=薄紫・タイマー=青→オレンジ→赤）が両実装で一致することを確認した
- スクリーンショットは `docs/screenshots/issue-37/` に格納（PR本文にも掲載）:
  - `android-home.png` / `web-home.png`: ホーム画面の主要ボタン配色一致（青）
  - `android-answering.png` / `web-answering.png`: 回答画面の質問カード・ボタン配色一致（カード=薄紫、未選択ボタン=青、選択中ボタン=オレンジ）
- 上記の手動検証で作成した2つのテストルームは実際の本番 Firestore（`hitokazu-game`）上に作成されている。既存の保持期限・自動削除機能（Issue #34）により自然に期限切れ・削除される想定で、明示的な削除操作は行っていない
