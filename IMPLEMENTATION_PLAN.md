# Admin Account 実装計画

## 実装戦略（シンプル&非破壊的）

### sessionStorage キー定義
| キー | 値 | 役割 | 保持期間 |
|------|-----|------|---------|
| `admin_player_token` | token 文字列 | Admin UUID 用セッショントークン | sessionStorage（ページ再読み込みで消失） |
| `admin_mode` | `"true"` | Admin Mode フラグ | sessionStorage（ページ再読み込みで消失） |

### localStorage は保持
- `gfx_admin_token`: 管理者トークン（Admin Mode 中も削除しない）
- `gfx_player_token`: プレイヤートークン（保持）

### フロー

#### 1. AdminPage - 「テスト用取引を開く」ボタンクリック
```javascript
POST /api/admin/trade-session
Authorization: Bearer <gfx_admin_token>
↓
レスポンス: { token, identity, expires_at }
↓
sessionStorage.setItem('admin_player_token', token);
sessionStorage.setItem('admin_mode', 'true');
→ /trade へ遷移
```

#### 2. TradePage - Admin Mode 検出
```javascript
const adminToken = sessionStorage.getItem('admin_player_token');
const isAdminMode = sessionStorage.getItem('admin_mode') === 'true';

if (!isAdminMode) {
  // Admin Mode フラグがない = sessionStorage リセット（ページ再読み込みなど）
  → /admin へリダイレクト
}

// API リクエスト時
const token = adminToken || localStorage.getItem('gfx_player_token');
Authorization: Bearer <token>
```

#### 3. Logout 処理
```javascript
sessionStorage.removeItem('admin_player_token');
sessionStorage.removeItem('admin_mode');
// localStorage は何もしない
→ /admin へリダイレクト
```

### 非破壊的な理由
- localStorage の既存トークンは削除しない
- Admin Mode 終了時も管理者トークンは保持
- sessionStorage のみ使用 → ページ再読み込みで自動消失
- 既存のプレイヤーモード（普通のログイン）と共存可能

### エラーハンドリング
- `/api/admin/trade-session` 失敗 → エラーアラート + AdminPage 保持（遷移しない）
- TradePage で admin_mode フラグ消失 → 自動リダイレクト /admin
