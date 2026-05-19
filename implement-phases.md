# GekiyabaFX 実装手順

## このファイルの使い方
- 完了したステップは `- [ ]` を `- [x]` に変更する
- メモや実装上の決定事項はステップの直下にインデントして追記する

```markdown
- [x] Step 1: **Maven/Gradleプロジェクト作成** — ...
  - Gradleを採用。build.gradle.kts で管理
  - Paper APIバージョンは 1.21.1 に固定
```

---

## 推奨実装順序

### Phase 1 — Javaプロジェクト基盤
- [ ] Step 1: **Maven/Gradleプロジェクト作成** — Paper API・Javalin・GSONを依存関係に追加
- [ ] Step 2: **`plugin.yml` の作成** — プラグイン名・バージョン・mainクラス・コマンド・パーミッションを定義。このファイルがないとPaperサーバがプラグインの読み込みを拒否する
- [ ] Step 3: **`config.yml` のロード** — `onEnable()` で読み込み、全設定値をメモリに保持

### Phase 2 — コアデータ構造
- [ ] Step 4: **Javaモデルクラス** — `Pair`・`Order`・`PlayerData`・`Execution` をBigDecimalフィールドで定義
- [ ] Step 5: **`storage.json` の読み書き** — GSONカスタムアダプター（BigDecimal→文字列）込みで実装し、起動時ロード・アトミック書き込みを確立（モデルクラスが先に必要）
- [ ] Step 6: **`StorageManager`** — メモリ上のデータ操作ロジック + `ReentrantLock` + 非同期書き込みキュー + `onDisable()` 同期フラッシュ

### Phase 3 — ゲーム内コマンド
- [ ] Step 7: **`/fx login`** — OTP生成・プレイヤーレコード自動作成・チャットへのClickEvent送信
- [ ] Step 8: **`/fx admin`** — 管理者OTP生成（権限チェック付き）
- [ ] Step 9: **`PlayerJoinEvent`** — `pending_deposit` 回収・`pending_withdraw` 付与

### Phase 4 — Web API
- [ ] Step 10: **Javalin起動** — 静的ファイル配信 + SPAフォールバック + CORS設定
- [ ] Step 11: **認証エンドポイント** — `POST /api/auth`・`POST /api/admin/auth`（OTP→セッショントークン交換）
- [ ] Step 12: **公開API** — `GET /api/pairs`・`GET /api/orderbook`・`GET /api/executions`
- [ ] Step 13: **マッチングエンジンロジック（コア）** — Price-Time Priority・指値/成行・部分約定・残高ロック/返還をクラス単体で実装
- [ ] Step 14: **マッチングエンジン単体テスト** — 指値/成行・部分約定・残高ロック/返還の検証をこの時点で実施（最後まで待たない）
- [ ] Step 15: **プレイヤーAPI** — `GET /api/state`・`POST /api/order`（マッチングエンジンのAPIエンドポイント接続）・`DELETE /api/order`
- [ ] Step 16: **入出金API** — `POST /api/deposit`・`POST /api/withdraw`（BukkitScheduler同期処理）
- [ ] Step 17: **管理者API** — `GET|POST /api/admin/pairs`・`PATCH|DELETE /api/admin/pairs/:id`

### Phase 5 — フロントエンド
- [ ] Step 18: **Vite + React + TypeScript プロジェクト作成** — `js-big-decimal`・`@tradingview/lightweight-charts` インストール
- [ ] Step 19: **API クライアント層** — BigDecimal文字列の受け取り・`Authorization: Bearer` ヘッダー付与を共通化
- [ ] Step 20: **認証フロー** — `/trade?otp=...` の3ステート処理・localStorage管理
- [ ] Step 21: **基本UI** — ペア選択・板表示・注文パネル・保有注文一覧
- [ ] Step 22: **チャート** — `executions` キャッシュ・差分ポーリング・時間足セレクター・ローソク足集計
- [ ] Step 23: **入出金パネル** — Pending状態の表示
- [ ] Step 24: **管理者画面** — `/admin` OTP認証・ペア管理UI

### Phase 6 — 統合テスト
- [ ] Step 25: **E2E統合検証** — アイテム入金→発注→約定→引き出しの一連のフローを実際のMinecraftサーバで検証
- [ ] Step 26: **ビルド & 配置** — `npm run build` → `www/` にコピー → Paperサーバで動作確認

---

> **最重要ポイント:**
> - Phase 2の `StorageManager`（ロック・永続化）を先に完璧に作ることが全体の安定性の鍵。ここが壊れていると残高バグがあらゆる場所で発生する。
> - Step 14（マッチングエンジン単体テスト）はStep 13の直後に実施すること。最後まで待つと残高破壊バグが潜伏したまま上位レイヤーが積み重なり修正コストが膨大になる。
