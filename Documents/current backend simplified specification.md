# current backend simplified specification

## 1. バックエンド概要

- 実装形態: **PaperMC プラグイン**（Java 21）
- Web/API: **Javalin 6** をプラグイン内で起動（内蔵HTTPサーバー）
- 主要役割:
  - ブラウザ向けAPI提供
  - アイテム売買のマッチング
  - 認証（OTP + セッション）
  - 入出金（Minecraftインベントリ連携）
  - 管理者向けペア管理

---

## 2. 起動・停止ライフサイクル

### onEnable
1. `config.yml` を読み込み
2. `StorageManager` 初期化（`storage.json`ロード）
3. `H2ExecutionRepository` 初期化（約定履歴DB）
4. OTP/Sessionマネージャー作成（player/admin別）
5. `/fx` コマンド登録
6. `PlayerJoinListener` 登録
7. Webサーバー起動
8. 各APIルーター登録

### onDisable
1. Webサーバー停止
2. H2接続クローズ
3. `StorageManager.shutdown()` で未書き込みデータを同期フラッシュ

---

## 3. 認証仕様

## 3.1 OTP
- 16文字英数字（`[A-Z0-9]`）
- 1回消費で無効
- 期限あり（`otp-expire-seconds`）

## 3.2 セッション
- トークンはUUID v4
- 期限あり（`session-expire-seconds`）
- `Authorization: Bearer <token>` で認証

## 3.3 ログイン導線
- プレイヤー: `/fx login` → `/trade?otp=...`
- 管理者: `/fx admin` → `/admin?otp=...`
- サービスアカウント: `/fx login-as <name>`（`serviceAccounts`に定義必須）

---

## 4. 永続化とデータ構造

## 4.1 `storage.json`（メイン状態）
- `pairs`: ペア情報（板、注文履歴、lastPrice等）
- `players`: プレイヤー情報
  - `hot_storage`
  - `locked_balance`
  - `pending_deposit`
  - `pending_withdraw`

## 4.2 H2 DB（`executions`）
- 約定履歴のみ保存
- 主なカラム: `pair_id`, `ts`, `price`, `amount`

## 4.3 書き込み戦略
- メモリ上状態は `StorageManager` が単一管理
- 変更時 `markDirty()` で **500ms デバウンス非同期保存**
- 停止時は同期保存で取りこぼし防止

---

## 5. API仕様（簡略）

## 5.1 認証API
- `POST /api/auth`（プレイヤーOTP交換）
- `POST /api/admin/auth`（管理者OTP交換）

## 5.2 公開API（認証不要）
- `GET /api/pairs`
- `GET /api/pairs/{id}/fee`
- `GET /api/orderbook?pair=...`
- `GET /api/executions?pair=...&since=...`

## 5.3 プレイヤーAPI（Bearer必須）
- `GET /api/state`
- `POST /api/order`
- `DELETE /api/order/{id}`
- `GET /api/transfer/resolve?q=...`
- `POST /api/transfer`

## 5.4 入出金API（Bearer必須）
- `POST /api/deposit`
- `POST /api/withdraw`

## 5.5 管理者API（admin Bearer必須）
- `GET /api/admin/pairs`
- `POST /api/admin/pairs`
- `PATCH /api/admin/pairs/{id}`
- `DELETE /api/admin/pairs/{id}`
- `GET /api/admin/service-accounts`

---

## 6. マッチングエンジン仕様（簡略）

- 方式: **Price-Time Priority**
  - bids: 価格降順→時刻昇順
  - asks: 価格昇順→時刻昇順
- 発注時に必要残高を `hot_storage` から `locked_balance` へ移動
- 約定時にロック解放と残高反映
- LIMIT残量は板に残る／MARKET残量は返金してクローズ
- キャンセル時は未約定ロックを返還

## 手数料
- 設定値: `fee.maker`, `fee.taker`, `feeOverrides`
- 買い側・売り側を独立計算
- 収益先: `svc:treasury-fee`

---

## 7. 入出金の実行ルール

- Bukkitインベントリ操作はメインスレッド限定
- APIスレッドから `BukkitScheduler.runTask(...)` で同期実行
- オフライン時:
  - deposit要求 → `pending_deposit` に積む
  - withdraw要求 → `pending_withdraw` に積む
- ログイン時 `PlayerJoinListener` が pending を処理

---

## 8. 同時実行制御

- `StorageManager` の **単一グローバルロック**で状態変更を直列化
- 原則、残高チェック〜更新〜注文処理はロック内
- I/O（JSON保存）は非同期デバウンスで集約

---

## 9. 設定項目（主要）

- `server-ip`, `web-port`, `dev-mode`
- `otp-expire-seconds`, `session-expire-seconds`
- `executions-max-per-pair`, `order-history-max-per-pair`
- `fee.maker`, `fee.taker`, `feeOverrides`
- `serviceAccounts`

---

## 10. 現在未実装（このバックエンド時点）

- Adminからの裁定取引スクリプト制御API（例: `/api/admin/arbitrage/toggle`）
- 裁定取引の定期実行タスク本体

この2点は現状コードベースには存在せず、今後追加対象。