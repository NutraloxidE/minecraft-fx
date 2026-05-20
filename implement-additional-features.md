## 追加の機能

- [x]ブラウザで、タイピングでアイテム名を直接入力しなくとも、ペアのアイテムの入出金を行えるようにする
- [x]時間スケールを20秒単位で表示できるようにする

#### 変更を実行する前に、ここで仕様を詰める

### 1. 目的

- [ ] 約定履歴（history）を `storage.json` から分離し、SQLite管理に移行する
- [ ] APIのレスポンス形式は現状維持し、フロントエンド改修なしで動作させる
- [ ] チャート上の時点に対してコメント（market event）を保存できるようにする
- [ ] コメントに `いいね / わるいね` を付与できるようにし、同一ユーザーの重複投票を防ぐ

---

### 2. フェーズ分割（実装順）

#### Phase A: 約定履歴のSQLite移行（先行リリース）

- 対象: 約定履歴のみ
- 互換性要件:
    - `GET /api/executions?pair=...&since=...` のレスポンスJSONは変更しない
    - フロントエンド変更不要
- 完了条件:
    - 新規約定がSQLiteに永続化される
    - 再起動後も履歴が失われない
    - 既存APIの挙動（並び順・フィルタ）が同じ

#### Phase B: market_events（コメント）

- 対象: チャート時点に紐づくコメント投稿・取得
- 要件:
    - コメントは `pair_id` と `event_time`（Unix秒）に紐付く
    - 投稿者UUID、本文、作成時刻を保存
    - 削除は一旦「投稿者本人 or 管理者」のみ（論理削除は今回は不要）

#### Phase C: リアクション（いいね/わるいね）

- 要件:
    - 1ユーザーは1コメントに対して1票のみ
    - `LIKE` と `DISLIKE` は切り替え可能（再投票で上書き）
    - 集計値（like_count/dislike_count）をAPIで返す

#### Phase D: フロントUI

- チャート右クリックでコメント入力UIを表示
- クリックした足の時刻を初期値として投稿
- コメント一覧で `いいね / わるいね` ボタンを表示

---

### 3. API仕様（追加分）

#### 3.1 既存（据え置き）

- `GET /api/executions?pair={pairId}&since={unixSec?}`
    - レスポンス形式は現状維持

#### 3.2 新規（market_events）

- `GET /api/market-events?pair={pairId}&from={unixSec?}&to={unixSec?}&limit={n?}`
    - 返却: コメント一覧（時刻昇順）
- `POST /api/market-events`
    - body: `{ pair_id, event_time, comment }`
    - 認証必須（プレイヤー）
- `POST /api/market-events/{event_id}/reaction`
    - body: `{ reaction: "LIKE" | "DISLIKE" }`
    - 認証必須
    - 同一ユーザー既存票があれば更新

---

### 4. SQLite データスキーマ定義

> DBファイル例: `plugins/GekiyabaFX/history.db`

```sql
PRAGMA foreign_keys = ON;

-- 約定履歴（Phase A）
CREATE TABLE IF NOT EXISTS executions (
    execution_id   TEXT PRIMARY KEY,                 -- UUID
    pair_id        TEXT NOT NULL,                    -- 例: DIAMOND/EMERALD
    buyer_uuid     TEXT NOT NULL,
    seller_uuid    TEXT NOT NULL,
    price          TEXT NOT NULL,                    -- 小数精度維持のため TEXT
    amount         TEXT NOT NULL,                    -- 小数精度維持のため TEXT
    timestamp_sec  INTEGER NOT NULL,                 -- Unix秒
    created_at_sec INTEGER NOT NULL                  -- 保存時刻
);

CREATE INDEX IF NOT EXISTS idx_executions_pair_time
    ON executions(pair_id, timestamp_sec);

CREATE INDEX IF NOT EXISTS idx_executions_time
    ON executions(timestamp_sec);


-- コメント本体（Phase B）
CREATE TABLE IF NOT EXISTS market_events (
    event_id         TEXT PRIMARY KEY,               -- UUID
    pair_id          TEXT NOT NULL,
    event_time_sec   INTEGER NOT NULL,               -- チャート上の対象時刻（Unix秒）
    author_uuid      TEXT NOT NULL,
    comment          TEXT NOT NULL CHECK(length(comment) BETWEEN 1 AND 500),
    created_at_sec   INTEGER NOT NULL,
    updated_at_sec   INTEGER                         -- 編集対応を見据えて確保
);

CREATE INDEX IF NOT EXISTS idx_market_events_pair_event_time
    ON market_events(pair_id, event_time_sec);

CREATE INDEX IF NOT EXISTS idx_market_events_created
    ON market_events(created_at_sec);


-- コメントへのリアクション（Phase C）
CREATE TABLE IF NOT EXISTS market_event_reactions (
    event_id         TEXT NOT NULL,
    voter_uuid       TEXT NOT NULL,
    reaction         TEXT NOT NULL CHECK(reaction IN ('LIKE', 'DISLIKE')),
    reacted_at_sec   INTEGER NOT NULL,
    PRIMARY KEY (event_id, voter_uuid),             -- 重複投票防止
    FOREIGN KEY (event_id) REFERENCES market_events(event_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_reactions_event
    ON market_event_reactions(event_id);
```

---

### 5. APIレスポンス（market_events）

```json
{
    "event_id": "uuid",
    "pair_id": "DIAMOND/EMERALD",
    "event_time": 1716100000,
    "author_uuid": "player-uuid",
    "comment": "ここで急騰した",
    "created_at": 1716100123,
    "updated_at": null,
    "like_count": 3,
    "dislike_count": 1,
    "my_reaction": "LIKE"
}
```

---

### 6. 運用/移行ルール

- 既存 `storage.json` にある約定履歴は、起動時に一度だけ `executions` へ移行
- 移行済みフラグ（例: `schema_version` テーブル）で二重移行を防ぐ
- 書き込みは「約定成立時に即INSERT」、読み取りは「pair + since」でSELECT
- 障害時はSQLiteを正として復旧（JSONへは戻さない）

---

### 7. 受け入れ条件（Definition of Done）

- [ ] Phase Aのみ有効化して、フロント未変更でチャート/約定履歴が継続表示される
- [ ] 再起動後に約定履歴が保持される
- [ ] コメント投稿・取得・リアクションが可能
- [ ] 同一ユーザーの重複投票がDB制約で防止される
- [ ] APIの主要クエリが `pair_id + time` インデックスで高速に取得される