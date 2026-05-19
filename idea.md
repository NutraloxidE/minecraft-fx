# Project Specification: GekiyabaFX (Minecraft Item FX Platform)

## 1. 概要 (Overview)
本プロジェクトは、Minecraft (Paper Server) のプラグイン内部に超軽量Webサーバー (Javalin) を内包し、ブラウザ側（React）からゲーム内のアイテムを「24時間リアルタイムトレードおよび出し入れ」できる、完全スタンドアロン型のアイテムFXプラットフォームである。
前提プラグインや外部データベースには一切依存せず、本プラグイン単体（単一の .jar ファイル）でWeb画面配信、API、ゲーム内インベントリ操作、データ永続化のすべてを完結させる。

---

## 2. システムアーキテクチャ (Architecture)

### 2.1 全体構造
```
[ Browser (React Frontend) ]
       │
       ▼ (HTTP/JSON 通信) -> Port: 3010
[ GekiyabaFX.jar (Minecraft Paper Plugin) ]
       ├── 🌐 Javalin Web Server & API Gateway
       ├── 🗄️ Hot Storage & Queue Manager (storage.json)
       └── 🎮 Paper API (Inventory Sync & Login Event)
```

### 2.2 ディレクトリ構造 (Runtime Directory)
プラグインが起動した際、`plugins/GekiyabaFX/` 内に以下の構造を自動生成する。
```
plugins/GekiyabaFX/
├── config.yml    # サーバー設定（ポート番号・CORS・OTP有効期限・セッション有効期限・ローソク足時間足・履歴最大保持件数）
├── storage.json  # ホットストレージ残高およびオフライン保留データの保存ファイル
└── www/          # Reactのビルド済みスタティックファイル（HTML/JS/CSS）
```

---

## 3. データ構造 (Data Schema)

### 3.0 マーケットモデルの概念
本プラットフォームは **複数の通貨ペア** を動的に管理できる。ペアは管理者がブラウザの管理画面から任意に追加・無効化できる。
- **ペアの表記:** `BASE/QUOTE`（例: `DIAMOND/EMERALD`、`DIAMOND/IRON`）
- **Base currency:** 売買される商品アイテム
- **Quote currency:** 決済に使うアイテム
- **レート表示:** `1 BASE = N QUOTE`
- プレイヤーは **指値注文 (Limit Order)** または **成行注文 (Market Order)** を出す。
- サーバー側の **マッチングエンジン** が各ペアのBid/Askを突き合わせ、約定させる。
- **オーダーブックはペアごとに独立して管理される。**
- 約定が発生した瞬間に、そのペアの `rates_history` が更新される。
- **数値の表現精度は小数点以下4桁とする。** ブラウザ上では `0.0001` 単位での売買・送金が可能。
- **数値の取り扱い方針:** 残高・注文数量・価格のすべてを、メモリ上・JSON保存・APIレスポンスにわたって **`BigDecimal` で統一する。** `double` / `float` は一切使用しない。
  - `scale=4`・`RoundingMode.HALF_UP` を全演算に統一適用する。
  - JSON保存・APIレスポンス時は、GSONに **`BigDecimal` 用カスタムタイプアダプター** を登録し、必ず**ダブルクォーテーション囲みの文字列**（例: `"4.3000"`）として出力する。GSONデフォルトの裸の数値型で出力すると、ブラウザの `JSON.parse()` 時にJavaScriptの `number`（IEEE 754倍精度）へ暗黙キャストされ、$2^{53}-1$ を超えた値で端数が消失するため。
  - フロントエンドはAPIレスポンスの数値フィールドをすべて `new BigDecimal(string)` で受け取る。
  - API入力値は `String` → `new BigDecimal(string)` で変換し、パース失敗・負値・`scale` 超過は即時 `400 Bad Request` として拒否する。
- **ゲーム内インベントリとの入出金（Deposit / Withdraw）は整数個単位のみ**とし、小数残高はホットストレージ内に保持したまま次の取引に使用できる。

### 3.1 storage.json (ホットストレージ 兼 保留キュー 兼 オーダーブック)
データベースの代わりに、Java側でGSON等を用いて以下のJSON構造を読み書きし、永続化する。

```json
{
  "pairs": {
    "DIAMOND/EMERALD": {
      "base": "diamond",
      "quote": "emerald",
      "enabled": true,
      "min_amount": 0.0001,
      "min_price": 0.0001,
      "order_book": {
        "bids": [
          {
            "order_id": "550e8400-e29b-41d4-a716-446655440001",
            "uuid": "de305d54-...",
            "type": "LIMIT",
            "side": "BUY",
            "price": 4.2,
            "amount": 0.5,
            "filled": 0.0,
            "status": "OPEN",
            "created_at": 1716142000
          }
        ],
        "asks": [
          {
            "order_id": "550e8400-e29b-41d4-a716-446655440002",
            "uuid": "ab12ef56-...",
            "type": "LIMIT",
            "side": "SELL",
            "price": 4.5,
            "amount": 5.0,
            "filled": 0.0,
            "status": "OPEN",
            "created_at": 1716142010
          }
        ]
      },
      "order_history": [
        {
          "order_id": "550e8400-e29b-41d4-a716-446655440000",
          "uuid": "de305d54-...",
          "type": "LIMIT",
          "side": "BUY",
          "price": 4.1,
          "amount": 1.0,
          "filled": 1.0,
          "status": "FILLED",
          "created_at": 1716141000,
          "closed_at": 1716141500
        }
      ],
      "executions": [
        { "timestamp": 1716142005, "price": 4.3, "amount": 0.5 },
        { "timestamp": 1716142062, "price": 4.4, "amount": 1.0 }
      ],
      "last_price": 4.3
    },
    "DIAMOND/IRON": {
      "base": "diamond",
      "quote": "iron_ingot",
      "enabled": false,
      "min_amount": 0.0001,
      "min_price": 0.0001,
      "order_book": { "bids": [], "asks": [] },
      "order_history": [],
      "executions": [],
      "last_price": null
    }
  },
  "players": {
    "de305d54-75b4-431b-adb2-eb6b9e546013": {
      "name": "R1cefarm",
      "hot_storage": {
        "diamond": 120.5025,
        "emerald": 4500.0000,
        "iron_ingot": 0.0
      },
      "locked_balance": {
        "DIAMOND/EMERALD": {
          "diamond": 0.0,
          "emerald": 2.1
        },
        "DIAMOND/IRON": {
          "diamond": 0.5,
          "iron_ingot": 0.0
        }
      },
      "pending_withdraw": {
        "diamond": 0,
        "emerald": 64
      },
      "pending_deposit": {
        "diamond": 0,
        "emerald": 0
      }
    }
  }
}
```

#### フィールド説明
| フィールド | 説明 |
|---|---|
| `pairs` | ペアIDをキーとするオブジェクト。ペアは動的に追加可能。 |
| `pairs.<ID>.enabled` | `false` の場合、新規注文を受け付けない。既存注文はキャンセル扱いで返金。 |
| `pairs.<ID>.min_amount` | そのペアの最小注文数量（`BigDecimal`）。管理者が設定。 |
| `pairs.<ID>.order_book` | そのペア専用のBid/Ask配列。他ペアと完全に独立。 |
| `pairs.<ID>.order_history` | FILLED / CANCELLED になった注文の履歴。最大保持件数は `config.yml` で設定（デフォルト: `500件/ペア`）。 |
| `pairs.<ID>.executions` | そのペアの生の約定履歴。`{ timestamp, price, amount }` の配列。最大保持件数は `config.yml` で設定（デフォルト: `10000件/ペア`）。ローソク足はフロントエンドが任意の時間足で動的集計する。 |
| `hot_storage` | 自由に使える残高（`BigDecimal`、小数点以下4桁精度）。アイテム種別をキーに持つ。**存在しないキーへのアクセスは常に `0.0` として扱う（オンデマンド初期化）。** キーはデポジット等で初めて値が生じた時点で自動生成される。 |
| `locked_balance` | ペアIDをキーとするオブジェクト。各ペアでロック中のアイテム残高を保持。キャンセルまたは約定時に対応する `hot_storage` へ返還・移動する。 |
| `pending_withdraw` | オフライン中の引き出し保留数（整数）。 |
| `pending_deposit` | オフライン中の預け入れ保留数（整数）。 |

---

## 4. バックエンド仕様 (Java / Javalin / Paper API)

### 4.1 内蔵Webサーバー (Javalin) の要件
- **ポート番号:** デフォルト `3010` (config.yml で可変)
- **静的ファイル配信:** `plugins/GekiyabaFX/www/` を外部ディレクトリ（Location.EXTERNAL）としてマウントし、SPA（Single Page Application）として配信する。
- **CORS設定:** 開発中のReact（localhost:5173など）からのクロスオリジンリクエストを許可するため、開発モード時は `anyHost()` を有効化する。

### 4.2 プレイヤー認証フロー（ワンタイムパスワード方式）
管理者認証と同じ仕組みで、**ゲーム内で発行したOTP**をセッショントークンに交換する。プレイヤー向けAPIは一切 `uuid` クエリパラメータを受け付けず、すべて `Authorization: Bearer <session_token>` で認証する。

1. **ゲーム内コマンドによるOTP発行:**
   - プレイヤーが `/fx login` コマンドを実行。
   - サーバー側でランダムな **OTP（英数字16文字）** を生成し、**そのプレイヤーのUUIDに紐づけて**メモリ上に保持（有効期限: デフォルト `5分`、config.yml で可変）。
   - ゲーム内チャットに以下を表示する:
     ```
     [GekiyabaFX] ログインURLを生成しました（有効期限: 5分）
     ► http://<サーバーIP>:3010/trade?otp=XXXXXXXXXXXXXXXX
     （クリックでブラウザが開きます）
     ```
   - URLはチャット上でクリック可能な **ClickEvent（OPEN_URL）** として送信する。
   - OTPは **1回使用したら即時無効化**する。未使用でも有効期限切れで自動破棄。
   - `/fx login` 実行時、`storage.json` の `players` に該当UUIDのエントリが存在しない場合は**空のプレイヤーレコードを自動作成**する（`hot_storage: {}`・`locked_balance: {}`・`pending_withdraw: {}`・`pending_deposit: {}`）。これにより以降のAPIで「プレイヤーが存在しない」ケースを考慮不要にする。

2. **OTP認証エンドポイント:**
   - `POST /api/auth` でOTPをセッショントークンに交換する。
   - `/trade?otp=...` にアクセスした際、フロントエンドが自動的にこのエンドポイントを呼ぶ。
   - **パラメータ:** `{ "otp": "XXXXXXXXXXXXXXXX" }`
   - **レスポンス（成功）:**
     ```json
     { "session_token": "...", "uuid": "de305d54-...", "name": "R1cefarm", "expires_at": 1716145000 }
     ```
   - **レスポンス（失敗）:** `401 Unauthorized`（OTP不正・期限切れ・使用済み）
   - 発行されたセッショントークンの有効期限は `config.yml` で設定（デフォルト: `30分`）。
   - セッション期限が切れたら、ゲーム内で `/fx login` を再実行するよう案内する。

3. **プレイヤーAPIの認証:**
   - 以降の全プレイヤー向け `/api/*` リクエストは `Authorization: Bearer <session_token>` ヘッダーで検証する。
   - サーバー側はトークンからUUIDを逆引きし、そのプレイヤーのデータのみ操作・返却する。
   - トークンの期限切れ・不正時は `401 Unauthorized`。

---

### 4.3 API エンドポイント (REST API)
すべてのリクエスト/レスポンスは JSON 形式とする。
**全エンドポイントは `Authorization: Bearer <session_token>` ヘッダーを必須とする。**ただし以下は認証不要（公開データ）：
- `POST /api/auth`
- `GET /api/pairs`
- `GET /api/orderbook`
- `GET /api/candles`
- `/api/admin/*`（独自の管理者セッショントークンで別途保護）

1. `GET /api/state`
   - **用途:** チャート描画用レート、プレイヤーの現在のホットストレージ残高・保有注文の取得。
   - **クエリパラメータ:** `?pair=DIAMOND/EMERALD`
   - **認証:** `Authorization: Bearer <session_token>`（UUIDはトークンから取得）
   - **レスポンス:**
     ```json
     {
       "hot_storage": { "diamond": 120.5025, "emerald": 4500.0 },
       "locked_balance": {
         "DIAMOND/EMERALD": { "diamond": 0.0, "emerald": 2.1 },
         "DIAMOND/IRON":    { "diamond": 0.5, "iron_ingot": 0.0 }
       },
       "open_orders": [ { "order_id": "550e8400-e29b-41d4-a716-446655440001", "pair": "DIAMOND/EMERALD", "side": "BUY", "price": 4.2, "amount": 0.5, "filled": 0.0 } ]
     }
     ```
   - ローソク足データは含まない。チャート描画は `GET /api/candles` を使用すること。
   - `open_orders` は全ペアを横断して、セッショントークンに紐づくプレイヤーの注文のみを返す。

2. `GET /api/pairs`
   - **用途:** 有効な取引ペア一覧の取得。フロントエンドのペア選択UIに使用。
   - **レスポンス:**
     ```json
     [
       { "id": "DIAMOND/EMERALD", "base": "diamond", "quote": "emerald", "enabled": true, "last_price": 4.3 },
       { "id": "DIAMOND/IRON",    "base": "diamond", "quote": "iron_ingot", "enabled": false, "last_price": null }
     ]
     ```

3. `GET /api/orderbook`
   - **用途:** 指定ペアの板情報取得。
   - **クエリパラメータ:** `?pair=DIAMOND/EMERALD`
   - **レスポンス:** Bid・Ask それぞれ**最大20段**（同一価格の注文は数量を集約）。
     ```json
     {
       "pair": "DIAMOND/EMERALD",
       "bids": [ { "price": 4.2, "total_amount": 25.0 } ],
       "asks": [ { "price": 4.5, "total_amount": 15.0 } ],
       "spread": 0.3,
       "last_price": 4.3
     }
     ```

4. `GET /api/executions`
   - **用途:** 生の約定履歴の取得。フロントエンドが任意の時間足でローソク足を動的集計する。
   - **クエリパラメータ:**
     - `?pair=DIAMOND/EMERALD` （必須）
     - `&since=<timestamp>` （省略時は全件返す）
   - **レスポンス:**
     ```json
     [
       { "timestamp": 1716142005, "price": 4.3, "amount": 0.5 },
       { "timestamp": 1716142062, "price": 4.4, "amount": 1.0 }
     ]
     ```
   - `since` を指定した場合、そのタイムスタンプより新しい約定のみを返す。変化がない場合は空配列 `[]` を返す。
   - 認証不要（公開データ）。

5. `POST /api/order`
   - **用途:** 新規注文の発注（指値 or 成行）。
   - **パラメータ:**
     ```json
     { "pair": "DIAMOND/EMERALD", "type": "LIMIT|MARKET", "side": "BUY|SELL", "price": 4.2, "amount": 0.5 }
     ```
     ※ `type: MARKET` の場合は `price` フィールド不要。
     ※ `type: MARKET` かつ `side: BUY` の場合は `max_spend` フィールド（`BigDecimal`）が**必須**。`max_spend` 分の `quote` アイテムを `locked_balance` にロックし、約定後に余剰分を `hot_storage` へ即時返還する。`max_spend` が不足して部分約定になった場合、残量はキャンセルとして処理する。
     ※ `type: MARKET` かつ `side: SELL` の場合は `amount` 分の `base` アイテムをロックする（通常の SELL と同じ）。
     ※ 発注者のUUIDは `Authorization` ヘッダーのセッショントークンから取得する。
     ※ `amount` および `price` は小数点以下4桁まで有効。最小注文数量はペアの `min_amount` に従う。
     ※ `pair.enabled` が `false` の場合 ➔ `403 Forbidden (Pair Disabled)`
     ※ `order_id` は発注時に **UUID v4**（`java.util.UUID.randomUUID()`）で生成する。
   - **処理ロジック（マッチングエンジン）:**
     1. **残高チェック & ロック:**
        - `BUY` 指値注文 → `quote` アイテムの `hot_storage` から `price × amount` 分を `locked_balance` へ移動。
        - `BUY` 成行注文 → `quote` アイテムの `hot_storage` から `max_spend` 分を `locked_balance` へ移動。約定後に余剰を即時返還。
        - `SELL` 注文（指値・成行共通）→ `base` アイテムの `hot_storage` から `amount` 分を `locked_balance` へ移動。
        - 残高不足の場合 ➔ `400 Bad Request (Insufficient Balance)`
     2. **マッチング実行（Price-Time Priority）:**
        - `BUY` 注文: そのペアの `asks` の最安値から順に `order.price >= ask.price` の条件で突き合わせる。
        - `SELL` 注文: そのペアの `bids` の最高値から順に `order.price <= bid.price` の条件で突き合わせる。
        - `MARKET` 注文は price 条件なしで板の先頭から即時消費。
        - `MARKET` 注文発注時に相手方の板が空の場合 → ロックを即時返還し `400 Bad Request (No liquidity)` を返す。
     3. **約定処理（Execution）:**
        - 約定した数量分、双方の `locked_balance` を消費し相手の `hot_storage` へ加算する。
        - 約定価格は **受動側（既存注文）の price** を使用（Price Improvement あり）。
        - そのペアの `executions` に `{ timestamp, price, amount }` を追記し、`last_price` を更新する。
     4. **約定済み注文の処理:**
        - `FILLED` / `CANCELLED` になった注文は `order_book` 配列から**即時除去**し、そのペアの `order_history` 配列へ移動する。
        - `order_history` が最大件数を超えた場合は古いものから削除する。
     5. **部分約定 / 残注文:**
        - 完全に約定しなかった残量は `PARTIALLY_FILLED` または `OPEN` ステータスで `order_book` に残す（`MARKET` 注文は残量キャンセル）。
     6. **レスポンス:**
        ```json
        {
          "order_id": "550e8400-e29b-41d4-a716-446655440003",
          "status": "FILLED",
          "filled": 0.5,
          "avg_price": 4.3,
          "remaining": 0.0
        }
        ```
        - `status`: `OPEN` / `PARTIALLY_FILLED` / `FILLED`
        - `avg_price`: 平均約定価格。未約定の場合は `null`。

6. `DELETE /api/order`
   - **用途:** 未約定 or 部分約定注文のキャンセル。
   - **パラメータ:** `{ "pair": "DIAMOND/EMERALD", "order_id": "550e8400-e29b-41d4-a716-446655440001" }`
   - **認証:** セッショントークンから取得したUUIDの注文のみキャンセル可能。他プレイヤーの `order_id` を指定した場合は `403 Forbidden`。
   - **処理:** `locked_balance` の未約定残量分を `hot_storage` へ戻し、注文を `CANCELLED` にして `order_history` へ移動する。

7. `POST /api/deposit`
   - **用途:** マイクラのインベントリからホットストレージへの預け入れ。
   - **パラメータ:** `{ "item": "diamond", "amount": 10 }`
   - **認証:** UUIDはセッショントークンから取得。
   - ⚠️ `amount` は **正の整数のみ**（ゲーム内アイテムは整数個単位のため）。入金後はホットストレージ上で小数取引が可能になる。
   - **処理ロジック:**
     1. `Bukkit.getPlayer(uuid)` でオンライン状態を確認。
     2. **[オンラインの場合]** `BukkitScheduler` でメインスレッドに同期し、`player.getInventory()` に指定アイテムが `amount` 以上あるか確認。
        - 在庫が足りる場合 ➔ `player.getInventory().removeItem()` でアイテムを回収し、`hot_storage` へ加算して `200 OK`
        - アイテムが不足の場合 ➔ 処理をロールバックし `400 Bad Request (Insufficient Items)` を返す。
     3. **[オフラインの場合]** `pending_deposit` に数量を加算。`200 OK (Pending)` を返す。次回ログイン時にインベントリからの回収を試みる（後述 4.5）。

8. `POST /api/withdraw`
   - **用途:** ホットストレージからマイクラのインベントリへの引き出し。
   - **パラメータ:** `{ "item": "diamond", "amount": 10 }`
   - **認証:** UUIDはセッショントークンから取得。
   - ⚠️ `amount` は **正の整数のみ**。`hot_storage` の残高が整数部分以上ある場合のみ許可する（例: 残高 `2.75` の場合、最大 `2` まで引き出し可能）。
   - **処理ロジック:**
     1. `Bukkit.getPlayer(uuid)` でオンライン状態を確認。
     2. **[オンラインの場合]** `BukkitScheduler` を用いてメインスレッドに同期し、`player.getInventory().addItem()` を実行。
        - インベントリに全て収まった場合 ➔ `hot_storage` を減算して `200 OK`
        - 満杯で入り切らなかった場合 ➔ 処理をロールバックし `400 Bad Request (Inventory Full)` を返す。
     3. **[オフラインの場合]** `hot_storage` から指定数を減算し、`pending_withdraw` にその数量を加算。`200 OK (Pending)` を返す。

### 4.4 管理者API (`/api/admin/*`)

#### 認証フロー（ワンタイムパスワード方式）
管理者APIへのアクセスは、**ゲーム内で発行したOTP（ワンタイムパスワード）** を用いたセッショントークンで保護する。`config.yml` の静的トークンは使用しない。

1. **ゲーム内コマンドによるOTP発行:**
   - `op` 権限または `gekiyabafx.admin` パーミッションを持つプレイヤーが `/fx admin` コマンドを実行。
   - サーバー側でランダムな **OTP（英数字16文字）** と **セッションID** を生成し、メモリ上に保持（有効期限: `config.yml` で設定、デフォルト `5分`）。
   - ゲーム内チャットに以下を表示する:
     ```
     [GekiyabaFX] 管理者ログインURLを生成しました（有効期限: 5分）
     ► http://<サーバーIP>:3010/admin?otp=XXXXXXXXXXXXXXXX
     （クリックでブラウザが開きます）
     ```
   - URLはチャット上でクリック可能な **ClickEvent（OPEN_URL）** として送信する。
   - OTPは**1回使用したら即時無効化**する。未使用でも有効期限切れで自動破棄。

2. **OTP認証エンドポイント:**
   - `POST /api/admin/auth` でOTPをセッショントークンに交換する。
   - `/admin?otp=...` にアクセスした際、フロントエンドが自動的にこのエンドポイントを呼ぶ。
   - **パラメータ:** `{ "otp": "XXXXXXXXXXXXXXXX" }`
   - **レスポンス（成功）:** `{ "session_token": "...", "expires_at": 1716145000 }`
   - **レスポンス（失敗）:** `401 Unauthorized`（OTP不正・期限切れ・使用済み）
   - 発行されたセッショントークンの有効期限は `config.yml` で設定（デフォルト: `30分`）。

3. **管理者APIの認証:**
   - 以降の全 `/api/admin/*` リクエストは `Authorization: Bearer <session_token>` ヘッダーで検証する。
   - セッショントークンの期限切れ・不正時は `401 Unauthorized`。

#### 管理者APIエンドポイント

1. `GET /api/admin/pairs`
   - **用途:** 全ペア一覧（無効ペア含む）の取得。

2. `POST /api/admin/pairs`
   - **用途:** 新規取引ペアの追加。
   - **パラメータ:** `{ "id": "DIAMOND/IRON", "base": "diamond", "quote": "iron_ingot", "min_amount": 0.001, "min_price": 0.001 }`
   - **処理:** `pairs` に新規エントリを `enabled: false` で追加し、`storage.json` を保存。

3. `PATCH /api/admin/pairs/:id`
   - **用途:** ペアの設定変更（有効/無効の切り替え、min_amount 変更など）。
   - `:id` はURLエンコードして渡す（例: `DIAMOND%2FEMERALD`）。Javalin側は `ctx.pathParam("id")` で自動デコード。フロントエンドは `encodeURIComponent(pairId)` を使用。
   - **パラメータ:** `{ "enabled": true }` など変更するフィールドのみ。
   - `enabled: false` にした場合、そのペアの全未約定注文を自動キャンセルして `locked_balance` を返金する。

4. `DELETE /api/admin/pairs/:id`
   - **用途:** ペアの完全削除。
   - `:id` は `PATCH` と同様にURLエンコードして渡す。
   - 未約定注文が残っている場合は先に全キャンセル・返金してから削除する。

### 4.5 マッチングエンジンの並行制御
- `order_book` および各プレイヤーの残高操作は必ず **シングルトンの `ReentrantLock`（グローバル1本）** によるシリアライズを行い、競合状態（Race Condition）による残高破損を防ぐ。
- ロックのスコープは「残高チェック〜マッチング〜残高更新」までのメモリ操作のみ。`storage.json` への書き込みはロック解放後に非同期で実行することでスループットを確保する。
- `storage.json` への書き込みはアトミックに行う（tmpファイルへ書き出し後にリネーム）。
- **プラグイン停止時の保全:** `onDisable()` が呼ばれた際（サーバー停止・`/stop`・クラッシュシャットダウン）は、非同期書き込みキューに残った未書き込みデータがあれば**メインスレッドをブロックして即時同期書き込み**を行い、データの巻き戻りを防ぐ。

### 4.6 約定履歴の管理
- 約定が発生するたびに、そのペアの `executions` に `{ timestamp, price, amount }` を追記し、`last_price` を更新する。
- `executions` の最大保持件数は `config.yml` で設定（デフォルト: `10000件/ペア`）。超過分は古い方から削除する。
- ローソク足の生成・時間足の集計はすべて**フロントエンドの責務**とする。バックエンドは生の約定データを返すのみ。

### 4.7 ゲーム内イベント連携 (Paper API)
- **PlayerJoinEvent (ログイン時自動同期):**
  プレイヤーがサーバーにログインした瞬間、以下を順番に処理する。
  1. **`pending_deposit` の回収:**
     `pending_deposit` に値があるアイテムについて、`player.getInventory().removeItem()` でインベントリからアイテムを回収し、`hot_storage` へ加算する。
     - アイテムが足りない場合は、**在庫分だけ部分回収**して残りの `pending_deposit` を差し引いた値に更新し、プレイヤーに不足を通知する。
     - 回収完了後、`pending_deposit` を `0` にリセット。
  2. **`pending_withdraw` の付与:**
     `pending_withdraw` に値があるアイテムについて、`player.getInventory().addItem()` でインベントリに付与する。
     - インベントリが満杯の場合はプレイヤーの足元にドロップ（アイテムロスを防ぐ）。
     - 付与完了後、`pending_withdraw` を `0` にリセット。
  3. 上記処理後、`storage.json` を保存し、プレイヤーに処理内容をチャットで通知する。

---

## 5. フロントエンド仕様 (React / Vite)

### 5.1 テクノロジースタック
- **フレームワーク:** React (TypeScript) + Vite
- **チャートライブラリ:** `@tradingview/lightweight-charts`
- **小数演算ライブラリ:** `js-big-decimal` — フロントエンドで数値計算が必要な場合（注文金額の概算表示・残高チェックなど）は必ず `js-big-decimal` を使用し、JavaScriptネイティブの `number` 型による浮動小数点誤差を避ける。

### 5.2 機能要件
- **プレイヤー情報表示（左上）:** ログイン中のプレイヤー名とスキン顔画像を画面左上に常時表示する。
  - スキン顔画像は `https://crafatar.com/avatars/<uuid>?size=32&overlay` を `<img>` タグで表示する（外部API依存、オフライン時はデフォルトのスティーブ顔画像にフォールバック）。
  - プレイヤー名・UUIDは `POST /api/auth` のレスポンスから取得し、localStorageに保存する。
  - セッション期限切れ時（APIから `401` が返ったとき）はログアウト状態に遷移し、「ゲーム内で `/fx login` を実行してください」と表示する。
- **ペア選択:** `GET /api/pairs` から有効ペア一覧を取得し、画面上部にペア切り替えタブ/ドロップダウンを表示。選択中のペアに応じてチャート・板・注文フォームが切り替わる。
- **リアルタイム・ローソク足チャート:** `GET /api/executions` で取得した生の約定履歴をフロントエンドで集計してFX風チャートを描画。
  - **初回表示時:** `since` なしで全件取得し、`localStorage["executions_<pairId>"]` にキャッシュする。
  - **以降のポーリング（1秒間隔）:** `since=<最後の約定のtimestamp>` で差分のみ取得し、localStorageにマージする。変化がなければ空配列が返るだけなので通信量はほぼゼロ。
  - **時間足セレクター:** 1分 / 5分 / 15分 / 1時間 / 4時間 を切り替え可能。localStorageの `executions` データから選択中の時間足でリアルタイムに再集計する。
  - **ローソク足の集計ルール（フロントエンド実装）:** 各足の `open`・`high`・`low`・`close`・`volume` を `js-big-decimal` で計算。約定がない時間帯は `open == high == low == close == 直前のclose` で補完する。
  - ペアを切り替えた際は、そのペアのlocalStorageキャッシュが存在すれば即時描画し、バックグラウンドで差分を取得する。
- **板情報（オーダーブック）表示:** 選択ペアの `GET /api/orderbook?pair=...` を定期取得し、Bid/Ask の価格帯別数量を縦に並べた板表示を実装する。最良 Bid・Ask・スプレッドをハイライト表示する。
- **注文パネル:** 指値 / 成行 タブ切り替え、数量入力フォーム、BUY（緑）/ SELL（赤）ボタン。
  - 指値時: `price` 入力欄を表示。
  - 成行BUY時: `amount` の代わりに `max_spend`（最大支払い `quote` 量）入力欄を表示。
  - 成行SELL時: `amount`（売る `base` 量）入力欄のみ。
  - 選択ペアの `base`/`quote` に合わせてラベルを動的表示する。
- **保有注文一覧:** `open_orders` を表示し、各行にキャンセルボタンを配置（`DELETE /api/order` を呼ぶ）。
- **入出金パネル（Deposit / Withdraw）:** アイテム種別・数量を指定して預け入れ（`POST /api/deposit`）または引き出し（`POST /api/withdraw`）を実行する。オフライン時は Pending バッジを表示してユーザーに通知する。
- **トレード画面 (`/trade`):** ゲーム内の `/fx login` コマンドで生成されたURLからのみアクセスする。OTP認証後はセッショントークン・プレイヤー名・UUIDをlocalStorageに保存し、以降のAPIコールに `Authorization: Bearer` ヘッダーを付与する。
  - **① URLに `otp` あり:** 自動的に `POST /api/auth` を呼び、成功したらトレード画面を表示。失敗（期限切れ・使用済み）なら③へ。
  - **② URLに `otp` なし & localStorageに有効なトークンあり:** OTP認証をスキップしてそのままトレード画面を表示。
  - **③ URLに `otp` なし & トークンなし or 期限切れ:** 「ゲーム内で `/fx login` を実行してください」と表示し、トレード画面には遷移しない。
- **管理者画面 (`/admin`):** ゲーム内の `/fx admin` コマンドで生成されたURLからのみアクセスする。
  - URLに含まれる `otp` パラメータを自動的に `POST /api/admin/auth` に送信し、セッショントークンを取得してlocalStorageに保存。
  - OTP認証失敗時（期限切れ・使用済み）は「URLが無効または期限切れです」と表示し、ゲーム内で再発行するよう案内する。
  - 認証成功後は通常の管理UIを表示:
    - ペア一覧の表示（有効/無効バッジ付き）
    - 新規ペア追加フォーム（base / quote アイテム名、min_amount、min_price の入力）
    - ペアごとの有効/無効トグル
    - ペアの削除ボタン（確認ダイアログ付き）
  - セッショントークンの期限切れ時は自動的にログアウトし、再発行を促すメッセージを表示する。
- **開発モードの切り替え:** ローカルでの `npm run dev` 時は、APIのフェッチ先を `http://localhost:3010` に向けるよう、`.env.development` で環境変数を管理する。最終ビルド時は相対パス `/api/...` で通信する。