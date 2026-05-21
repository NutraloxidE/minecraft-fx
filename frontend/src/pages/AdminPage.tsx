
/**
 * 管理者ページ（/admin）
 *
 * 認証の3ステートに応じて表示を切り替える:
 * - `loading`         — スピナー表示
 * - `unauthenticated` — 「OTP リンクからアクセスしてください」メッセージ
 * - `authenticated`   — ペア管理 UI
 */

import { useState, useEffect, useCallback } from 'react'
import { useAdminAuth } from '@/hooks/useAdminAuth'
import { useDebugMode } from '@/hooks/useDebugMode'
import {
  adminFetchPairs,
  adminCreatePair,
  adminPatchPair,
  adminDeletePair,
  adminFetchServiceAccounts,
} from '@/lib/api'
import { DEBUG_ADMIN_PAIRS, DEBUG_SERVICE_ACCOUNTS } from '@/lib/debugData'
import type { AdminPair, CreatePairRequest } from '@/types/api'
import type { ServiceAccount } from '@/lib/api'

// ─── ペア作成フォーム ──────────────────────────────────────────────────────────

const EMPTY_FORM: CreatePairRequest = {
  id: '', base: '', quote: '', enabled: true, min_amount: '1', min_price: '0.0001',
}

interface CreateFormProps {
  onCreated: () => void
  isDebug: boolean
}

function CreatePairForm({ onCreated, isDebug }: CreateFormProps) {
  const [form, setForm] = useState<CreatePairRequest>(EMPTY_FORM)
  const [busy, setBusy] = useState(false)
  const [msg,  setMsg]  = useState<{ text: string; ok: boolean } | null>(null)

  const set = (k: keyof CreatePairRequest, v: string | boolean) =>
    setForm((f) => ({ ...f, [k]: v }))

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!form.id || !form.base || !form.quote) {
      setMsg({ text: 'ID / Base / Quote は必須です', ok: false })
      return
    }

    if (isDebug) {
      setMsg({ text: `[DEBUG] ペア ${form.id} を作成したことにします`, ok: true })
      setForm(EMPTY_FORM)
      onCreated()
      return
    }

    setBusy(true); setMsg(null)
    try {
      await adminCreatePair(form)
      setMsg({ text: `ペア ${form.id} を作成しました`, ok: true })
      setForm(EMPTY_FORM)
      onCreated()
    } catch (e: unknown) {
      const err = e as { message?: string }
      setMsg({ text: err.message ?? '作成に失敗しました', ok: false })
    } finally {
      setBusy(false)
    }
  }

  return (
    <form className="admin-create-form" onSubmit={handleSubmit}>
      <h3 className="admin-section-title">ペア作成</h3>
      <div className="admin-form-row">
        <label>ペアID
          <input className="admin-input" value={form.id}
            onChange={(e) => set('id', e.target.value)} placeholder="DIAMOND/EMERALD" disabled={busy} />
        </label>
        <label>Base
          <input className="admin-input" value={form.base}
            onChange={(e) => set('base', e.target.value)} placeholder="diamond" disabled={busy} />
        </label>
        <label>Quote
          <input className="admin-input" value={form.quote}
            onChange={(e) => set('quote', e.target.value)} placeholder="emerald" disabled={busy} />
        </label>
        <label>Min Amount
          <input className="admin-input" value={form.min_amount}
            onChange={(e) => set('min_amount', e.target.value)} disabled={busy} />
        </label>
        <label>Min Price
          <input className="admin-input" value={form.min_price}
            onChange={(e) => set('min_price', e.target.value)} disabled={busy} />
        </label>
        <label className="admin-check-label">
          <input type="checkbox" checked={form.enabled}
            onChange={(e) => set('enabled', e.target.checked)} disabled={busy} />
          有効
        </label>
      </div>
      <button className="admin-submit-btn" type="submit" disabled={busy}>作成</button>
      {isDebug && <p className="admin-msg ok">[DEBUG] APIには保存されません</p>}
      {msg && <p className={`admin-msg ${msg.ok ? 'ok' : 'err'}`}>{msg.text}</p>}
    </form>
  )
}

// ─── ペア行 ──────────────────────────────────────────────────────────────────

interface PairRowProps {
  pair: AdminPair
  onChanged: () => void
  isDebug: boolean
}

function PairRow({ pair, onChanged, isDebug }: PairRowProps) {
  const [editing,   setEditing]   = useState(false)
  const [enabled,   setEnabled]   = useState(pair.enabled)
  const [minAmount, setMinAmount] = useState(pair.min_amount)
  const [minPrice,  setMinPrice]  = useState(pair.min_price)
  const [busy,      setBusy]      = useState(false)
  const [msg,       setMsg]       = useState<{ text: string; ok: boolean } | null>(null)

  const handleSave = async () => {
    if (isDebug) {
      setMsg({ text: '[DEBUG] 保存したことにします', ok: true })
      setEditing(false)
      return
    }

    setBusy(true); setMsg(null)
    try {
      await adminPatchPair(pair.id, { enabled, min_amount: minAmount, min_price: minPrice })
      setMsg({ text: '保存しました', ok: true })
      setEditing(false)
      onChanged()
    } catch (e: unknown) {
      const err = e as { message?: string }
      setMsg({ text: err.message ?? '保存に失敗', ok: false })
    } finally {
      setBusy(false)
    }
  }

  const handleDelete = async () => {
    if (isDebug) {
      const confirmation = window.prompt(`[DEBUG] ペア "${pair.id}" を削除するには delete と入力してください`)
      if (confirmation !== 'delete') {
        setMsg({ text: '[DEBUG] 削除をキャンセルしました（delete 未入力）', ok: false })
        return
      }
      setMsg({ text: '[DEBUG] 削除したことにします', ok: true })
      return
    }

    const confirmation = window.prompt(`ペア "${pair.id}" を削除するには delete と入力してください`)
    if (confirmation !== 'delete') {
      setMsg({ text: '削除をキャンセルしました（delete 未入力）', ok: false })
      return
    }
    setBusy(true); setMsg(null)
    try {
      await adminDeletePair(pair.id)
      onChanged()
    } catch (e: unknown) {
      const err = e as { message?: string }
      setMsg({ text: err.message ?? '削除に失敗', ok: false })
      setBusy(false)
    }
  }

  return (
    <tr>
      <td>{pair.id}</td>
      <td>{pair.base}</td>
      <td>{pair.quote}</td>
      <td>
        {editing
          ? <input className="admin-input-sm" value={minAmount}
              onChange={(e) => setMinAmount(e.target.value)} disabled={busy} />
          : minAmount}
      </td>
      <td>
        {editing
          ? <input className="admin-input-sm" value={minPrice}
              onChange={(e) => setMinPrice(e.target.value)} disabled={busy} />
          : minPrice}
      </td>
      <td>
        {editing
          ? <input type="checkbox" checked={enabled}
              onChange={(e) => setEnabled(e.target.checked)} disabled={busy} />
          : (pair.enabled ? '✅' : '❌')}
      </td>
      <td>{pair.last_price ?? '—'}</td>
      <td>
        <div className="admin-row-actions">
          {editing ? (
            <>
              <button className="admin-save-btn"   onClick={handleSave}                              disabled={busy}>保存</button>
              <button className="admin-cancel-btn" onClick={() => { setEditing(false); setMsg(null) }} disabled={busy}>キャンセル</button>
            </>
          ) : (
            <button className="admin-edit-btn" onClick={() => setEditing(true)} disabled={busy}>編集</button>
          )}
          <button className="admin-delete-btn" onClick={handleDelete} disabled={busy}>削除</button>
        </div>
        {msg && <p className={`admin-msg ${msg.ok ? 'ok' : 'err'}`}>{msg.text}</p>}
      </td>
    </tr>
  )
}

// ─── サービスアカウント残高 ────────────────────────────────────────────────────

function ServiceAccountBalances({ isDebug }: { isDebug: boolean }) {
  const [accounts, setAccounts] = useState<ServiceAccount[]>([])
  const [loading,  setLoading]  = useState(true)
  const [err,      setErr]      = useState<string | null>(null)

  const load = useCallback(async () => {
    setLoading(true); setErr(null)
    try {
      if (isDebug) setAccounts(DEBUG_SERVICE_ACCOUNTS)
      else setAccounts(await adminFetchServiceAccounts())
    }
    catch (e: unknown) { setErr((e as { message?: string }).message ?? '取得失敗') }
    finally { setLoading(false) }
  }, [isDebug])

  useEffect(() => { load() }, [load])

  return (
    <div className="svc-balances">
      <div className="svc-balances-header">
        <h3 className="admin-section-title">サービスアカウント残高</h3>
        <button className="admin-edit-btn" onClick={load} disabled={loading}>更新</button>
      </div>
      {loading && <p className="admin-loading">読み込み中...</p>}
      {err     && <p className="admin-err-msg">{err}</p>}
      {!loading && !err && (
        <div className="svc-balances-grid">
          {accounts.map((a) => (
            <div key={a.id} className="svc-card">
              <div className="svc-card-name">{a.name}</div>
              <div className="svc-card-id">{a.id}</div>
              <div className="svc-card-storage">
                {Object.keys(a.hot_storage).length === 0
                  ? <span className="svc-card-empty">残高なし</span>
                  : Object.entries(a.hot_storage).map(([k, v]) => (
                      <div key={k} className="svc-card-row">
                        <span className="svc-card-item">{k}</span>
                        <span className="svc-card-val">{v}</span>
                      </div>
                    ))
                }
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

// ─── ペアテーブル ─────────────────────────────────────────────────────────────

function PairTable({ isDebug }: { isDebug: boolean }) {
  const [pairs,    setPairs]    = useState<AdminPair[]>([])
  const [loading,  setLoading]  = useState(true)
  const [fetchErr, setFetchErr] = useState<string | null>(null)

  const load = useCallback(async () => {
    setLoading(true); setFetchErr(null)
    try {
      if (isDebug) setPairs(DEBUG_ADMIN_PAIRS)
      else setPairs(await adminFetchPairs())
    } catch (e: unknown) {
      const err = e as { message?: string }
      setFetchErr(err.message ?? 'ペア一覧の取得に失敗しました')
    } finally {
      setLoading(false)
    }
  }, [isDebug])

  useEffect(() => { load() }, [load])

  if (loading)  return <p className="admin-loading">読み込み中...</p>
  if (fetchErr) return <p className="admin-err-msg">{fetchErr}</p>

  return (
    <div className="admin-table-wrap">
      <table className="admin-pairs-table">
        <thead>
          <tr>
            <th>ID</th><th>Base</th><th>Quote</th>
            <th>Min Amount</th><th>Min Price</th><th>有効</th><th>最終価</th><th>操作</th>
          </tr>
        </thead>
        <tbody>
          {pairs.length === 0
            ? <tr><td colSpan={8} style={{ textAlign: 'center', color: 'var(--text)' }}>ペアなし</td></tr>
            : pairs.map((p) => <PairRow key={p.id} pair={p} onChanged={load} isDebug={isDebug} />)
          }
        </tbody>
      </table>
    </div>
  )
}

// ─── メインページ ────────────────────────────────────────────────────────────

export default function AdminPage() {
  const isDebug = useDebugMode()
  const { authState, error, logout } = useAdminAuth()
  const effectiveAuthState = isDebug ? 'authenticated' : authState

  if (effectiveAuthState === 'loading') {
    return (
      <div className="auth-screen">
        <p className="auth-message">認証中...</p>
      </div>
    )
  }

  if (effectiveAuthState === 'unauthenticated') {
    return (
      <div className="auth-screen">
        <h1 className="auth-title">💥GekiyabaFX 管理者</h1>
        <p className="auth-message">
          {error
            ? `認証エラー: ${error}`
            : 'ゲーム内で /fx admin コマンドを実行し、表示された URL からアクセスしてください。'}
        </p>
      </div>
    )
  }

  return (
    <div className="admin-page">
      <header className="trade-header">
        <span className="trade-header-title">GekiyabaFX 管理者</span>
        <button className="trade-header-logout" onClick={logout}>ログアウト</button>
      </header>

      <main className="admin-main">
        <section className="admin-section">
          <h2 className="admin-page-title">ペア管理</h2>
          <PairTable isDebug={isDebug} />
        </section>

        <section className="admin-section">
          <CreatePairForm onCreated={() => { /* PairTable は内部で reload */ }} isDebug={isDebug} />
        </section>

        <section className="admin-section">
          <ServiceAccountBalances isDebug={isDebug} />
        </section>
      </main>
    </div>
  )
}
