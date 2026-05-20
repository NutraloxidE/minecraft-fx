# Admin Account 実装仕様書

## 1. 目的
- **Admin UUID**: `00000000-0000-0000-0000-000000000000`
- システムテスト・デバッグ用の特別なアカウント
- **ただし、システム的には通常のプレイヤーアカウントと全く同じ**
- 通常アカウント同様に残高制約、マッチング、約定が適用される

---

## 2. Admin UUID の特性

### 2.1 PlayerData として扱う
- 他のプレイヤーと同じ `PlayerData` に登録される
- 初期残高：
  - Diamond: 1000.0000
  - Emerald: 10000.0000
- 初期化タイミング：`StorageManager.initialize()` 時に PlayerData マップに追加（存在しなければ）

### 2.2 取引時の制約
- **残高制約が完全に適用される**
  - BUY 注文：必要な quote 残高がなければ拒否
  - SELL 注文：必要な base 残高がなければ拒否
- **マッチング・約定ロジック**：通常のプレイヤーと同じ
- **lockBalance / settle / refund**：特別扱いなし

---

## 3. アクセス制限

### 3.1 通常プレイヤーは Admin UUID でアクセスできない
- OTP ログイン → SessionManager → identity が Admin UUID にならない

### 3.2 Admin UUID へのアクセスは AdminPage経由のみ
- AdminPage にボタン：「テスト用取引を開く」
- ボタンクリック → バックエンド `/api/admin/trade-session` 呼び出し
- **前提条件**：呼び出し元が管理者認証済み（管理者 token 有効）
- **戻り値**：Admin UUID 用のプレイヤーセッショントークン

### 3.3 TradePage での Admin Mode 検出
- sessionStorage の `admin_trade_mode` フラグで検出
- AdminPage からのアクセス以外：`player_uuid !== 00000000-0000-0000-0000-000000000000` ならリダイレクト
- logout 時：`/admin` へ戻る

---

## 4. フロー図

```
[AdminPage (管理者)]
    ↓
  「テスト用取引を開く」ボタン
    ↓
POST /api/admin/trade-session
  - ヘッダー: Authorization: Bearer <管理者token>
  - バックエンド処理:
    1. 管理者 token 検証
    2. Admin UUID 用プレイヤーセッション作成
    3. token, identity, expires_at 返却
    ↓
[フロントエンド]
  - localStorage に player_token 保存
  - sessionStorage に admin_trade_mode=true, player_uuid=00000000-0000-0000-0000-000000000000 保存
  - /trade へ遷移
    ↓
[TradePage (Admin Mode)]
  - 普通の取引ページ
  - Admin UUID で注文を発注
  - 残高制約あり
  - logout → /admin へ
```

---

## 5. 実装チェックリスト

### Backend
- [ ] StorageManager.initialize(): Admin UUID を PlayerData として初期化（Diamond 1000, Emerald 10000）
- [ ] MatchingEngine: 特別扱いなし（通常のロジック）
- [ ] AdminApiRouter に `/api/admin/trade-session` エンドポイント追加
  - [ ] 管理者 token 検証
  - [ ] Admin UUID 用セッション作成 (playerSessionManager.create("00000000-0000-0000-0000-000000000000"))
  - [ ] JSON レスポンス: token, identity, expires_at
- [ ] GekiyabaFXPlugin: AdminApiRouter にplayerSessionManager を渡す

### Frontend
- [ ] AdminPage: 「テスト用取引を開く」ボタン追加
  - [ ] ボタンクリック → POST /api/admin/trade-session
  - [ ] getAdminToken() で管理者 token を取得
  - [ ] レスポンス token を localStorage.gfx_player_token に保存
  - [ ] sessionStorage に フラグを保存
  - [ ] /trade へ遷移
- [ ] TradePage: Admin Mode 検出
  - [ ] sessionStorage admin_trade_mode チェック
  - [ ] 不正なアクセスなら /admin へリダイレクト
  - [ ] logout: Admin Mode なら /admin へ、通常なら 通常処理
- [ ] api.ts: fetch に Authorization ヘッダー自動付与（既存）

---

## 6. 詳細実装仕様

### 6.1 POST /api/admin/trade-session

**リクエスト:**
```http
POST /api/admin/trade-session
Authorization: Bearer <admin_token>
Content-Type: application/json
{}
```

**レスポンス (200):**
```json
{
  "token": "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
  "identity": "00000000-0000-0000-0000-000000000000",
  "expires_at": 1716200000
}
```

**エラー (401):**
```json
{ "error": "unauthorized" }
```

**実装:**
- AdminApiRouter.handleAdminTradeSession()
- 管理者 token を検証
- playerSessionManager.create("00000000-0000-0000-0000-000000000000") で新規セッション作成
- JSON レスポンス

---

## 7. ファイル変更一覧

### Backend
- `StorageManager.java`: initialize() メソッド内で Admin UUID 初期化
- `AdminApiRouter.java`: handleAdminTradeSession() 追加、register() に `/api/admin/trade-session` 登録
- `GekiyabaFXPlugin.java`: AdminApiRouter インスタンス化時に playerSessionManager を渡す

### Frontend
- `AdminPage.tsx`: handleAdminTradeMode() 実装、ボタンUI追加
- `TradePage.tsx`: Admin Mode 検出、logout 分岐処理

---

## 8. テスト手順

1. **Admin UUID 初期化確認**
   - サーバー起動 → storage.json で Admin UUID が存在、残高が設定されているか

2. **AdminPage アクセス**
   - 管理者 OTP でログイン
   - 「テスト用取引を開く」ボタンが表示される

3. **Admin Mode 開始**
   - ボタンクリック
   - TradePage に遷移
   - localStorage に player_token が保存されているか確認
   - /api/state で Admin UUID の残高が表示されるか確認

4. **取引テスト**
   - Diamond 1000 で DIAMOND を売却 → OK
   - Diamond 1001 で DIAMOND を売却 → 残高不足エラー

5. **Logout テスト**
   - ログアウトボタン → /admin へリダイレクト

---
