## 追加の機能

- [x]ブラウザで、タイピングでアイテム名を直接入力しなくとも、ペアのアイテムの入出金を行えるようにする
- [x]時間スケールを20秒単位で表示できるようにする

#### 変更を実行する前に、ここで仕様を詰めてください

- [ ] **約定履歴（歴史）をSQLiteに移行する、Storageから分離（原子性の為に）**
  - **バックエンド仕様:** - 残高や有効注文は `storage.json` でアトミックに管理し、増え続ける約定履歴（executions）のみを内蔵SQLite（`gekiyaba_history.db`）へ逃がして非同期で追記保存する。
    - `GET /api/executions` はSQLiteに対するクエリ（`SELECT * FROM executions WHERE timestamp > :since ...`）に切り替える。
  - **フロントエンド仕様:** - フロントエンドの既存ロジックは一切破壊せず、APIが返すJSONフォーマットの互換性を完全に維持する。
  - **検証フェーズ:** この移行のみを先に実装し、チャートがこれまで通りラグなく動くかを一旦動作確認する。

- [ ] **コメントで歴史を残せるようにする**
  - **SQLiteスキーマ定義:**
    ```sql
    -- ① 歴史コメント本体テーブル（通貨ペア別管理）
    CREATE TABLE IF NOT EXISTS market_events (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        timestamp INTEGER NOT NULL,          -- イベント発生日時（UNIX秒）
        pair TEXT NOT NULL,                  -- 投稿時の通貨ペア（例: "DIAMOND_EMERALD"）
        uuid TEXT NOT NULL,                  -- コメントを書き込んだプレイヤーのUUID
        player_name TEXT NOT NULL,           -- 表示用のプレイヤー名
        content TEXT NOT NULL,               -- コメント本文（最大100文字）
        upvotes INTEGER DEFAULT 0,           -- いいね総数（キャッシュフィールド）
        downvotes INTEGER DEFAULT 0          -- わるいね総数（キャッシュフィールド）
    );
    CREATE INDEX IF NOT EXISTS idx_market_events_pair_time ON market_events(pair, timestamp);

    -- ② 重複投票防止・トグル制御用テーブル
    CREATE TABLE IF NOT EXISTS market_event_votes (
        event_id INTEGER NOT NULL,
        uuid TEXT NOT NULL,                  -- 投票したプレイヤーのUUID
        vote_type TEXT NOT NULL,             -- "UP" または "DOWN"
        PRIMARY KEY (event_id, uuid),        -- 1人1コメントにつき1票を強制
        FOREIGN KEY (event_id) REFERENCES market_events(id) ON DELETE CASCADE
    );
    ```
  - **API（バックエンド）仕様:**
    - `GET /api/events?pair=<pair_id>&since=<timestamp>`: 開いているペアのイベントを抽出し、さらに要求したプレイヤー本人の既存投票状態を示す `my_vote` フィールド（`"UP" | "DOWN" | null`）を動的に付与して返す。
    - `POST /api/events/:id/vote`: プレイヤーのUUIDと `vote_type` を受け取る。過去の投票と同一タイプなら「トグル（投票解除・レコード削除）」、別タイプなら「寝返り（レコード更新）」として処理し、本体のカウントを増減させる。
  - **フロントエンド（React/UI）仕様:**
    - **右クリックコメント投稿:** チャート（TradingView）上を右クリックすると、その瞬間のタイムスタンプと現在のペアIDを自動取得し、ポップアップ（投稿モーダル）を開いてコメントを送信できる。
    - **いいね・わるいねトグルUI:** チャート上のイベントマーカーを開いた際、👍／👎ボタンを表示。自分が投票済みのボタンはアクティブ状態としてハイライトされ、再クリックで解除（トグルアウト）、逆クリックで投票の切り替えがリアルタイムに反映される。
```