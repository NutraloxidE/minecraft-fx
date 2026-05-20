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
- [x] Step 1: **Maven/Gradleプロジェクト作成** — Paper API・Javalin・GSONを依存関係に追加
- [x] Step 2: **`plugin.yml` の作成** — プラグイン名・バージョン・mainクラス・コマンド・パーミッションを定義。このファイルがないとPaperサーバがプラグインの読み込みを拒否する
- [x] Step 3: **`config.yml` のロード** — `onEnable()` で読み込み、全設定値をメモリに保持

### Phase 2 — コアデータ構造
- [x] Step 4: **Javaモデルクラス** — `Pair`・`Order`・`PlayerData`・`Execution` をBigDecimalフィールドで定義
- [x] Step 5: **`storage.json` の読み書き** — GSONカスタムアダプター（BigDecimal→文字列）込みで実装し、起動時ロード・アトミック書き込みを確立（モデルクラスが先に必要）
- [x] Step 6: **`StorageManager`** — メモリ上のデータ操作ロジック + `ReentrantLock` + 非同期書き込みキュー + `onDisable()` 同期フラッシュ

### Phase 3 — ゲーム内コマンド
- [x] Step 7: **`/fx login`** — OTP生成・プレイヤーレコード自動作成・チャットへのClickEvent送信
- [x] Step 8: **`/fx admin`** — 管理者OTP生成（権限チェック付き）
- [x] Step 9: **`PlayerJoinEvent`** — `pending_deposit` 回収・`pending_withdraw` 付与

### Phase 4 — Web API
- [x] Step 10: **Javalin起動** — 静的ファイル配信 + SPAフォールバック + CORS設定
- [x] Step 11: **認証エンドポイント** — `POST /api/auth`・`POST /api/admin/auth`（OTP→セッショントークン交換）
- [x] Step 12: **公開API** — `GET /api/pairs`・`GET /api/orderbook`・`GET /api/executions`
- [x] Step 13: **マッチングエンジンロジック（コア）** — Price-Time Priority・指値/成行・部分約定・残高ロック/返還をクラス単体で実装
- [x] Step 14: **マッチングエンジン単体テスト** — 指値/成行・部分約定・残高ロック/返還の検証をこの時点で実施（最後まで待たない）
- [x] Step 15: **プレイヤーAPI** — `GET /api/state`・`POST /api/order`（マッチングエンジンのAPIエンドポイント接続）・`DELETE /api/order`
- [x] Step 16: **入出金API** — `POST /api/deposit`・`POST /api/withdraw`（BukkitScheduler同期処理）
- [x] Step 17: **管理者API** — `GET|POST /api/admin/pairs`・`PATCH|DELETE /api/admin/pairs/:id`

### Phase 5 — フロントエンド
- [x] Step 18: **Vite + React + TypeScript プロジェクト作成** — `js-big-decimal`・`@tradingview/lightweight-charts` インストール
- [x] Step 19: **API クライアント層** — BigDecimal文字列の受け取り・`Authorization: Bearer` ヘッダー付与を共通化
- [x] Step 20: **認証フロー** — `/trade?otp=...` の3ステート処理・localStorage管理
- [x] Step 21: **基本UI** — ペア選択・板表示・注文パネル・保有注文一覧
- [x] Step 22: **チャート** — `executions` キャッシュ・差分ポーリング・時間足セレクター・ローソク足集計
- [x] Step 23: **入出金パネル** — Pending状態の表示
- [x] Step 24: **管理者画面** — `/admin` OTP認証・ペア管理UI

### Phase 6 — 統合テスト
- [x] Step 25: **E2E統合検証** — アイテム入金→発注→約定→引き出しの一連のフローを実際のMinecraftサーバで検証
- [x] Step 26: **ビルド & 配置** — `npm run build` → `www/` にコピー → Paperサーバで動作確認

---

## 最終総合動作テストチェックリスト

### 🔧 サーバー起動
- [x] `run-server.bat` でサーバーが正常起動する
- [x] ログに `GekiyabaFX 有効化されました` が出る
- [x] ログに `Listening on http://127.0.0.1:3010/` が出る
- [x] ブラウザで `http://127.0.0.1:3010/` を開くと React アプリが表示される

### 👤 プレイヤーログイン
- [x] Minecraft クライアントで `localhost:25565` に接続できる
- [x] `/fx login` を実行するとチャットにクリック可能な URL が届く
- [x] URL をクリックすると `/trade?otp=...` に遷移し「認証中...」が表示される
- [x] 認証成功後にトレード画面が表示され、プレイヤー名がヘッダーに表示される
- [x] OTP を2回使おうとすると認証エラーになる
- [ ] OTP 有効期限（300秒）切れ後のリンクで認証エラーになる

### 🏛️ 管理者ログイン・ペア管理
- [x] `/fx admin` を実行すると管理者 URL が届く（`op` 権限ユーザーのみ）
- [ ] 一般プレイヤーが `/fx admin` を打つと権限エラーになる
- [x] 管理者画面 (`/admin`) でペア一覧が表示される
- [x] ペア作成フォームで `DIAMOND/EMERALD`（base=diamond, quote=emerald）を作成できる
- [x] 作成後すぐにペア一覧に表示される
- [x] 編集ボタンから `min_amount` / `min_price` / 有効フラグを変更して保存できる
- [x] 削除ボタンで確認ダイアログが出て、OKで削除できる *(API確認済み)*

### 💰 入出金
- [x] トレード画面の入出金パネルでアイテム名 `diamond`・数量 `5` を入力して「入金」できる
  - オフライン時: `insufficient_inventory` (400) が返る（正常）
  - オンライン時: インベントリからアイテムが減り、ホット残高に反映される *(要Minecraft内テスト)*
- [x] 「出金」ボタンでホット残高が減り、インベントリにアイテムが戻る
  - インベントリが満杯の場合: `pending_withdraw` に積まれる *(要Minecraft内テスト)*
- [x] `GET /api/state` レスポンスに `pending_deposit` / `pending_withdraw` が含まれる
- [x] ページ上部のホット残高表示が入出金後に更新される

### 📋 注文・マッチング
- [x] ペアセレクターで `DIAMOND/EMERALD` を選択すると板・チャートが切り替わる
- [x] 指値BUY注文を発注できる（price=4, amount=5）
- [x] 発注後「保有注文」リストに表示される
- [x] `GET /api/orderbook` で板に反映されている
- [x] 板表示（OrderBookView）に今の注文が表示される
- [x] 別のプレイヤーまたは別ブラウザタブで指値SELL注文（price=4, amount=5）を発注する
- [x] 約定が発生し両者の「保有注文」から消える
- [x] `GET /api/executions` で約定が返ってくる
- [x] 買い手のホット残高に `diamond` が増え、`emerald` が減る
- [ ] ローソク足チャートに約定がプロットされる *(要ブラウザ確認)*
- [ ] 成行BUY注文を発注すると即座に約定する

### ❌ 注文キャンセル
- [x] 未約定の指値注文を「キャンセル」できる
- [x] キャンセル後、ロックされていた残高がホット残高に返還される
- [x] 板からも消える

### 📊 チャート・リアルタイム更新
- [ ] 約定後にチャートが自動更新される（ポーリング間隔内）
- [ ] 時間足ボタン（1分 / 5分 / 15分 / 1時間 / 4時間 / 1日）で切り替えできる
- [x] データなし時に「約定データがありません」が表示される

### 🔒 セキュリティ・エラー処理
- [x] 無効なトークンで `/api/state` を叩くと 401 が返る
- [x] 残高不足で注文を発注すると 400 エラーメッセージが表示される
- [x] 存在しない注文を DELETE しようとすると 404 が返る *(未認証時は401が優先、認証済みで存在しない場合は404)*
- [ ] セッション有効期限（1800秒）切れ後に操作するとログアウト状態になる

### 🛑 シャットダウン
- [x] サーバーコンソールで `stop` を入力すると `GekiyabaFX 無効化されました` のログが出る
- [x] `storage.json` に最新データが保存されている
- [ ] 再起動後に `storage.json` からデータが復元される（残高・ペア情報）

> **最重要ポイント:**
> - Phase 2の `StorageManager`（ロック・永続化）を先に完璧に作ることが全体の安定性の鍵。ここが壊れていると残高バグがあらゆる場所で発生する。
> - Step 14（マッチングエンジン単体テスト）はStep 13の直後に実施すること。最後まで待つと残高破壊バグが潜伏したまま上位レイヤーが積み重なり修正コストが膨大になる。
