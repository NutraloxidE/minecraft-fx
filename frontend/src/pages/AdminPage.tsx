/**
 * 管理者ページ（/admin）
 *
 * 認証の3ステートに応じて表示を切り替える:
 * - `loading`         — スピナー表示
 * - `unauthenticated` — 「OTP リンクからアクセスしてください」メッセージ
 * - `authenticated`   — 管理 UI（Step 24 で実装）
 */

import { useAdminAuth } from '@/hooks/useAdminAuth'

export default function AdminPage() {
  const { authState, error, logout } = useAdminAuth()

  if (authState === 'loading') {
    return (
      <div className="auth-screen">
        <p className="auth-message">認証中...</p>
      </div>
    )
  }

  if (authState === 'unauthenticated') {
    return (
      <div className="auth-screen">
        <h1 className="auth-title">GekiyabaFX 管理者</h1>
        <p className="auth-message">
          {error
            ? `認証エラー: ${error}`
            : 'ゲーム内で /fx admin コマンドを実行し、表示された URL からアクセスしてください。'}
        </p>
      </div>
    )
  }

  // authenticated
  return (
    <div className="admin-page">
      <header className="trade-header">
        <span className="trade-header-title">GekiyabaFX 管理者</span>
        <button className="trade-header-logout" onClick={logout}>
          ログアウト
        </button>
      </header>
      {/* Step 24 で管理 UI を実装 */}
      <main className="trade-main">
        <p style={{ color: '#aaa', padding: '2rem' }}>
          管理者 UI を読み込み中... (Step 24)
        </p>
      </main>
    </div>
  )
}
