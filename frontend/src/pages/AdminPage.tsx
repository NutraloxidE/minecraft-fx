
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
  adminReorderPairs,
  adminDeletePair,
  adminFetchServiceAccounts,
  adminCreateServiceAccount,
  adminDeleteServiceAccount,
  adminFetchWebSettings,
  adminPatchWebSettings,
  adminFetchFeeSettings,
  adminPatchFeeSettings,
  adminFetchArbitrageStatus,
  adminToggleArbitrage,
  adminFetchMarketMakerStatus,
  adminToggleMarketMaker,
  adminDownloadServerBackup,
} from '@/lib/api'
import {
  DEBUG_ADMIN_PAIRS,
  DEBUG_SERVICE_ACCOUNTS,
  DEBUG_ARBITRAGE_STATUS,
  DEBUG_MARKET_MAKER_STATUS,
} from '@/lib/debugData'
import type { AdminPair, CreatePairRequest } from '@/types/api'
import type { ServiceAccount, ArbitrageStatusResponse, AdminWebSettingsResponse, AdminFeeSettingsResponse, MarketMakerStatusResponse } from '@/lib/api'

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
  canMoveUp: boolean
  canMoveDown: boolean
  movingDisabled: boolean
  onMoveUp: () => void
  onMoveDown: () => void
  onChanged: () => void
  isDebug: boolean
}

function PairRow({
  pair,
  canMoveUp,
  canMoveDown,
  movingDisabled,
  onMoveUp,
  onMoveDown,
  onChanged,
  isDebug,
}: PairRowProps) {
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
          <button className="admin-edit-btn" onClick={onMoveUp} disabled={busy || movingDisabled || !canMoveUp}>↑</button>
          <button className="admin-edit-btn" onClick={onMoveDown} disabled={busy || movingDisabled || !canMoveDown}>↓</button>
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
  const [busy, setBusy] = useState(false)
  const [newAccountName, setNewAccountName] = useState('')
  const [msg, setMsg] = useState<{ text: string; ok: boolean } | null>(null)
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

  const normalizeName = (value: string) => value.trim().toLowerCase().replace(/^svc:/, '')

  const handleCreate = async () => {
    const normalized = normalizeName(newAccountName)
    if (!/^[a-z0-9][a-z0-9._-]{0,63}$/.test(normalized)) {
      setMsg({ text: 'サービスアカウント名は英小文字・数字・._- のみで入力してください', ok: false })
      return
    }

    if (isDebug) {
      if (accounts.some((a) => a.name === normalized)) {
        setMsg({ text: `[DEBUG] 既に存在します: svc:${normalized}`, ok: false })
        return
      }
      setAccounts((prev) => [...prev, { name: normalized, id: `svc:${normalized}`, hot_storage: {} }])
      setNewAccountName('')
      setMsg({ text: `[DEBUG] 作成: svc:${normalized}`, ok: true })
      return
    }

    setBusy(true)
    setMsg(null)
    try {
      await adminCreateServiceAccount(normalized)
      setNewAccountName('')
      setMsg({ text: `作成しました: svc:${normalized}`, ok: true })
      await load()
    } catch (e: unknown) {
      const apiErr = e as { message?: string }
      setMsg({ text: apiErr.message ?? 'サービスアカウント作成に失敗しました', ok: false })
    } finally {
      setBusy(false)
    }
  }

  const handleDelete = async (accountName: string) => {
    const confirmation = window.prompt(`サービスアカウント "svc:${accountName}" を削除するには (delete) と入力してください`)
    if (confirmation !== 'delete') {
      setMsg({ text: '削除をキャンセルしました（delete 未入力）', ok: false })
      return
    }

    if (isDebug) {
      setAccounts((prev) => prev.filter((a) => a.name !== accountName))
      setMsg({ text: `[DEBUG] 削除: svc:${accountName}`, ok: true })
      return
    }

    setBusy(true)
    setMsg(null)
    try {
      await adminDeleteServiceAccount(accountName)
      setMsg({ text: `削除しました: svc:${accountName}`, ok: true })
      await load()
    } catch (e: unknown) {
      const apiErr = e as { message?: string }
      setMsg({ text: apiErr.message ?? 'サービスアカウント削除に失敗しました', ok: false })
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="svc-balances">
      <div className="svc-balances-header">
        <h3 className="admin-section-title">サービスアカウント残高</h3>
        <button className="admin-edit-btn" onClick={load} disabled={loading || busy}>更新</button>
      </div>
      <div className="admin-form-row">
        <label>新規サービスアカウント名
          <input
            className="admin-input"
            placeholder="例: market-bot-2"
            value={newAccountName}
            onChange={(e) => setNewAccountName(e.target.value)}
            disabled={busy || loading}
          />
        </label>
        <button className="admin-submit-btn" type="button" onClick={handleCreate} disabled={busy || loading}>
          追加
        </button>
      </div>
      {loading && <p className="admin-loading">読み込み中...</p>}
      {err     && <p className="admin-err-msg">{err}</p>}
      {!loading && !err && (
        <div className="svc-balances-grid">
          {accounts.map((a) => (
            <div key={a.id} className="svc-card">
              <div className="svc-card-name">{a.name}</div>
              <div className="svc-card-id">{a.id}</div>
              <div className="svc-card-actions">
                <button className="admin-delete-btn" type="button" onClick={() => { void handleDelete(a.name) }} disabled={busy}>
                  削除
                </button>
              </div>
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
      {msg && <p className={`admin-msg ${msg.ok ? 'ok' : 'err'}`}>{msg.text}</p>}
    </div>
  )
}

function FeeSettingsPanel({ isDebug }: { isDebug: boolean }) {
  const [settings, setSettings] = useState<AdminFeeSettingsResponse | null>(null)
  const [makerInput, setMakerInput] = useState('0.0010')
  const [takerInput, setTakerInput] = useState('0.0012')
  const [overridesInput, setOverridesInput] = useState('')
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [msg, setMsg] = useState<{ text: string; ok: boolean } | null>(null)

  const formatOverrides = (overrides: Record<string, string>) =>
    Object.entries(overrides)
      .map(([k, v]) => `${k}=${v}`)
      .join('\n')

  const parseOverrides = (input: string): { ok: true; value: Record<string, string> } | { ok: false; message: string } => {
    const out: Record<string, string> = {}
    const lines = input.split(/\r?\n/)
    for (const line of lines) {
      const trimmed = line.trim()
      if (!trimmed || trimmed.startsWith('#')) continue
      const sep = trimmed.indexOf('=')
      if (sep < 1) {
        return { ok: false, message: `feeOverrides の形式が不正です: ${trimmed}` }
      }
      const key = trimmed.slice(0, sep).trim().toLowerCase()
      const value = trimmed.slice(sep + 1).trim()
      if (!/^[a-z0-9][a-z0-9._-]{0,63}$/.test(key)) {
        return { ok: false, message: `通貨キーが不正です: ${key}` }
      }
      const rate = Number(value)
      if (!Number.isFinite(rate) || rate < 0 || rate > 1) {
        return { ok: false, message: `手数料率は 0 以上 1 以下で入力してください: ${trimmed}` }
      }
      out[key] = String(rate)
    }
    return { ok: true, value: out }
  }

  const load = useCallback(async () => {
    setLoading(true)
    setMsg(null)
    try {
      if (isDebug) {
        const debugSettings: AdminFeeSettingsResponse = {
          maker: '0.0010',
          taker: '0.0012',
          fee_overrides: { tempkey: '0.0050' },
        }
        setSettings(debugSettings)
        setMakerInput(debugSettings.maker)
        setTakerInput(debugSettings.taker)
        setOverridesInput(formatOverrides(debugSettings.fee_overrides))
        return
      }
      const res = await adminFetchFeeSettings()
      setSettings(res)
      setMakerInput(res.maker)
      setTakerInput(res.taker)
      setOverridesInput(formatOverrides(res.fee_overrides))
    } catch (e: unknown) {
      const apiErr = e as { message?: string }
      setMsg({ text: apiErr.message ?? '手数料設定の取得に失敗しました', ok: false })
    } finally {
      setLoading(false)
    }
  }, [isDebug])

  useEffect(() => {
    void load()
  }, [load])

  const handleSave = async () => {
    const maker = Number(makerInput)
    const taker = Number(takerInput)
    if (!Number.isFinite(maker) || maker < 0 || maker > 1) {
      setMsg({ text: 'maker は 0 以上 1 以下で入力してください', ok: false })
      return
    }
    if (!Number.isFinite(taker) || taker < 0 || taker > 1) {
      setMsg({ text: 'taker は 0 以上 1 以下で入力してください', ok: false })
      return
    }

    const parsed = parseOverrides(overridesInput)
    if (!parsed.ok) {
      setMsg({ text: parsed.message, ok: false })
      return
    }

    if (isDebug) {
      const next: AdminFeeSettingsResponse = {
        maker: String(maker),
        taker: String(taker),
        fee_overrides: parsed.value,
      }
      setSettings(next)
      setMakerInput(next.maker)
      setTakerInput(next.taker)
      setOverridesInput(formatOverrides(next.fee_overrides))
      setMsg({ text: `[DEBUG] 手数料設定を更新しました (maker=${next.maker}, taker=${next.taker})`, ok: true })
      return
    }

    setSaving(true)
    setMsg(null)
    try {
      const updated = await adminPatchFeeSettings({
        maker: String(maker),
        taker: String(taker),
        feeOverrides: parsed.value,
      })
      setSettings(updated)
      setMakerInput(updated.maker)
      setTakerInput(updated.taker)
      setOverridesInput(formatOverrides(updated.fee_overrides))
      setMsg({ text: `手数料設定を更新しました (maker=${updated.maker}, taker=${updated.taker})`, ok: true })
    } catch (e: unknown) {
      const apiErr = e as { message?: string }
      setMsg({ text: apiErr.message ?? '手数料設定の更新に失敗しました', ok: false })
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="admin-backup-panel">
      <h3 className="admin-section-title">手数料設定</h3>
      <p className="admin-backup-desc">
        `maker` / `taker` は 0〜1 の率で指定します。feeOverrides は 1行1件で `currency=rate` 形式（例: tempkey=0.0050）。
      </p>

      {loading ? (
        <p className="admin-loading">読み込み中...</p>
      ) : (
        <>
          <div className="admin-form-row">
            <label>maker
              <input
                className="admin-input"
                type="number"
                min="0"
                max="1"
                step="0.0001"
                value={makerInput}
                onChange={(e) => setMakerInput(e.target.value)}
                disabled={saving}
              />
            </label>
            <label>taker
              <input
                className="admin-input"
                type="number"
                min="0"
                max="1"
                step="0.0001"
                value={takerInput}
                onChange={(e) => setTakerInput(e.target.value)}
                disabled={saving}
              />
            </label>
          </div>

          <label className="admin-textarea-wrap">feeOverrides (currency=rate)
            <textarea
              className="admin-textarea"
              value={overridesInput}
              onChange={(e) => setOverridesInput(e.target.value)}
              disabled={saving}
              rows={6}
              placeholder={'tempkey=0.0050\nspecialcoin=0.0000'}
            />
          </label>

          <div className="admin-form-row">
            <button className="admin-submit-btn" type="button" onClick={handleSave} disabled={saving}>
              {saving ? '保存中...' : '保存'}
            </button>
            <button className="admin-edit-btn" type="button" onClick={() => { void load() }} disabled={saving || loading}>
              再読込
            </button>
          </div>
        </>
      )}

      {settings && !loading && (
        <p className="admin-backup-desc">現在値: maker={settings.maker}, taker={settings.taker}, overrides={Object.keys(settings.fee_overrides).length}件</p>
      )}
      {msg && <p className={`admin-msg ${msg.ok ? 'ok' : 'err'}`}>{msg.text}</p>}
    </div>
  )
}

function BackupDownloadPanel({ isDebug }: { isDebug: boolean }) {
  const [downloading, setDownloading] = useState(false)
  const [msg, setMsg] = useState<{ text: string; ok: boolean } | null>(null)

  const handleDownload = async () => {
    if (isDebug) {
      setMsg({ text: '[DEBUG] バックアップZIPのダウンロードを実行したことにします', ok: true })
      return
    }

    setDownloading(true)
    setMsg(null)
    try {
      const { blob, filename } = await adminDownloadServerBackup()
      const url = window.URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = filename
      document.body.appendChild(a)
      a.click()
      a.remove()
      window.URL.revokeObjectURL(url)
      setMsg({ text: `バックアップZIPを生成しました: ${filename}`, ok: true })
    } catch (e: unknown) {
      const err = e as { message?: string }
      setMsg({ text: err.message ?? 'バックアップZIPのダウンロードに失敗しました', ok: false })
    } finally {
      setDownloading(false)
    }
  }

  return (
    <div className="admin-backup-panel">
      <h3 className="admin-section-title">サーバーバックアップ</h3>
      <p className="admin-backup-desc">
        world系データ、plugins/GekiyabaFX、server.properties、config配下のPaper設定を、ディレクトリ構造そのままでZIP化してダウンロードします。
      </p>
      <button className="admin-submit-btn" onClick={handleDownload} disabled={downloading}>
        {downloading ? 'バックアップ生成中...' : 'ワールド + GekiyabaFX データをZIPでダウンロード'}
      </button>
      {msg && <p className={`admin-msg ${msg.ok ? 'ok' : 'err'}`}>{msg.text}</p>}
    </div>
  )
}

function LoginLinkSettingsPanel({ isDebug }: { isDebug: boolean }) {
  const [settings, setSettings] = useState<AdminWebSettingsResponse | null>(null)
  const [serverIpInput, setServerIpInput] = useState('')
  const [urlSchemeInput, setUrlSchemeInput] = useState<'http' | 'https'>('http')
  const [includePortInput, setIncludePortInput] = useState(true)
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [msg, setMsg] = useState<{ text: string; ok: boolean } | null>(null)

  const formatBaseUrl = (s: AdminWebSettingsResponse) =>
    `${s.login_url_scheme}://${s.server_ip}${s.login_url_include_port ? `:${s.web_port}` : ''}`

  const load = useCallback(async () => {
    setLoading(true)
    setMsg(null)
    try {
      if (isDebug) {
        const debugSettings: AdminWebSettingsResponse = {
          server_ip: '127.0.0.1',
          web_port: 3010,
          login_url_scheme: 'http',
          login_url_include_port: true,
        }
        setSettings(debugSettings)
        setServerIpInput(debugSettings.server_ip)
        setUrlSchemeInput(debugSettings.login_url_scheme)
        setIncludePortInput(debugSettings.login_url_include_port)
      } else {
        const res = await adminFetchWebSettings()
        setSettings(res)
        setServerIpInput(res.server_ip)
        setUrlSchemeInput(res.login_url_scheme)
        setIncludePortInput(res.login_url_include_port)
      }
    } catch (e: unknown) {
      const err = e as { message?: string }
      setMsg({ text: err.message ?? 'Web設定の取得に失敗しました', ok: false })
    } finally {
      setLoading(false)
    }
  }, [isDebug])

  useEffect(() => {
    void load()
  }, [load])

  const handleSave = async () => {
    if (!serverIpInput.trim()) {
      setMsg({ text: 'server-ip を入力してください', ok: false })
      return
    }

    if (isDebug) {
      setSettings((prev) => ({
        server_ip: serverIpInput.trim(),
        web_port: prev?.web_port ?? 3010,
        login_url_scheme: urlSchemeInput,
        login_url_include_port: includePortInput,
      }))
      const debugPort = settings?.web_port ?? 3010
      const debugBase = `${urlSchemeInput}://${serverIpInput.trim()}${includePortInput ? `:${debugPort}` : ''}`
      setMsg({ text: `[DEBUG] ログインURL設定を更新したことにします: ${debugBase}`, ok: true })
      return
    }

    setSaving(true)
    setMsg(null)
    try {
      const updated = await adminPatchWebSettings({
        serverIp: serverIpInput.trim(),
        loginUrlScheme: urlSchemeInput,
        loginUrlIncludePort: includePortInput,
      })
      setSettings(updated)
      setServerIpInput(updated.server_ip)
      setUrlSchemeInput(updated.login_url_scheme)
      setIncludePortInput(updated.login_url_include_port)
      setMsg({ text: `保存しました。以後のログインURLは ${formatBaseUrl(updated)} を使います`, ok: true })
    } catch (e: unknown) {
      const err = e as { message?: string }
      setMsg({ text: err.message ?? 'server-ip の更新に失敗しました', ok: false })
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="admin-backup-panel">
      <h3 className="admin-section-title">ログインリンク設定</h3>
      <p className="admin-backup-desc">
        /fx login と /fx admin で表示するURLのプロトコル（http/https）、ホスト名、ポート表示有無を設定します。
      </p>
      {loading ? (
        <p className="admin-loading">読み込み中...</p>
      ) : (
        <div className="admin-form-row">
          <label>URL Scheme
            <select
              className="admin-input"
              value={urlSchemeInput}
              onChange={(e) => setUrlSchemeInput(e.target.value === 'https' ? 'https' : 'http')}
              disabled={saving}
            >
              <option value="http">http</option>
              <option value="https">https</option>
            </select>
          </label>
          <label>server-ip
            <input
              className="admin-input"
              value={serverIpInput}
              onChange={(e) => setServerIpInput(e.target.value)}
              placeholder="例: 203.0.113.10 または fx.example.com"
              disabled={saving}
            />
          </label>
          <label className="admin-check-label">
            <input
              type="checkbox"
              checked={includePortInput}
              onChange={(e) => setIncludePortInput(e.target.checked)}
              disabled={saving}
            />
            URLにポートを含める
          </label>
          <label>web-port
            <input
              className="admin-input"
              value={settings?.web_port ?? ''}
              disabled
              readOnly
            />
          </label>
          <button className="admin-submit-btn" type="button" onClick={handleSave} disabled={saving}>
            {saving ? '保存中...' : '保存'}
          </button>
          <button className="admin-edit-btn" type="button" onClick={() => { void load() }} disabled={loading || saving}>
            再読込
          </button>
        </div>
      )}
      {!loading && settings && (
        <p className="admin-backup-desc">
          現在のログインURLベース: {formatBaseUrl(settings)}
        </p>
      )}
      {msg && <p className={`admin-msg ${msg.ok ? 'ok' : 'err'}`}>{msg.text}</p>}
    </div>
  )
}

// ─── 裁定取引コントロール ─────────────────────────────────────────────────────

function ArbitrageControlPanel({ isDebug }: { isDebug: boolean }) {
  const [status, setStatus] = useState<ArbitrageStatusResponse | null>(null)
  const [accounts, setAccounts] = useState<ServiceAccount[]>([])
  const [selectedAccount, setSelectedAccount] = useState('svc:arbitrage')
  const [intervalSeconds, setIntervalSeconds] = useState('15')
  const [minGrossSpreadPct, setMinGrossSpreadPct] = useState('0.5')
  const [minNetProfitPct, setMinNetProfitPct] = useState('0.30')
  const [slipPriceChangeThresholdPct, setSlipPriceChangeThresholdPct] = useState('3.0')
  const [slipVolumeDropThresholdPct, setSlipVolumeDropThresholdPct] = useState('35')
  const [slipLookbackSeconds, setSlipLookbackSeconds] = useState('3')
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [msg, setMsg] = useState<{ text: string; ok: boolean } | null>(null)
  const [err, setErr] = useState<string | null>(null)

  const syncLocalControls = useCallback((nextStatus: ArbitrageStatusResponse) => {
    setSelectedAccount(nextStatus.service_account)
    setIntervalSeconds(String(Math.max(1, Math.round(nextStatus.check_interval_ticks / 20))))
    setMinGrossSpreadPct(nextStatus.min_gross_spread_pct)
    setMinNetProfitPct(nextStatus.min_net_profit_pct)
    setSlipPriceChangeThresholdPct(nextStatus.slip_price_change_threshold_pct)
    setSlipVolumeDropThresholdPct(nextStatus.slip_volume_drop_threshold_pct)
    setSlipLookbackSeconds(String(Math.max(1, Math.round(nextStatus.slip_check_lookback_ticks / 20))))
  }, [])

  const load = useCallback(async () => {
    setLoading(true)
    setErr(null)
    try {
      if (isDebug) {
        setStatus(DEBUG_ARBITRAGE_STATUS)
        setAccounts(DEBUG_SERVICE_ACCOUNTS)
        syncLocalControls(DEBUG_ARBITRAGE_STATUS)
        return
      }

      const [s, a] = await Promise.all([
        adminFetchArbitrageStatus(),
        adminFetchServiceAccounts(),
      ])
      setStatus(s)
      setAccounts(a)
      syncLocalControls(s)
    } catch (e: unknown) {
      const apiErr = e as { message?: string }
      setErr(apiErr.message ?? '裁定取引ステータスの取得に失敗しました')
    } finally {
      setLoading(false)
    }
  }, [isDebug, syncLocalControls])

  useEffect(() => {
    load()
  }, [load])

  const apply = async (payload: { enabled?: boolean; service_account?: string }) => {
    if (!status) return

    const seconds = Number(intervalSeconds)
    if (!Number.isFinite(seconds) || !Number.isInteger(seconds) || seconds < 1) {
      setMsg({ text: '監視間隔は1以上の整数秒で入力してください', ok: false })
      return
    }

    const grossThreshold = Number(minGrossSpreadPct)
    if (!Number.isFinite(grossThreshold) || grossThreshold < 0) {
      setMsg({ text: '最小総スプレッド(%)は0以上の数値で入力してください', ok: false })
      return
    }

    const netThreshold = Number(minNetProfitPct)
    if (!Number.isFinite(netThreshold) || netThreshold < 0) {
      setMsg({ text: '最小純利益(%)は0以上の数値で入力してください', ok: false })
      return
    }

    const priceThreshold = Number(slipPriceChangeThresholdPct)
    if (!Number.isFinite(priceThreshold) || priceThreshold < 0) {
      setMsg({ text: '価格変化しきい値(%)は0以上の数値で入力してください', ok: false })
      return
    }

    const volumeThreshold = Number(slipVolumeDropThresholdPct)
    if (!Number.isFinite(volumeThreshold) || volumeThreshold < 0) {
      setMsg({ text: '出来高減少しきい値(%)は0以上の数値で入力してください', ok: false })
      return
    }

    const lookbackSec = Number(slipLookbackSeconds)
    if (!Number.isFinite(lookbackSec) || !Number.isInteger(lookbackSec) || lookbackSec < 1) {
      setMsg({ text: 'スリッページ判定期間は1以上の整数秒で入力してください', ok: false })
      return
    }

    const request = {
      ...payload,
      check_interval_ticks: seconds * 20,
      min_gross_spread_pct: String(grossThreshold),
      min_net_profit_pct: String(netThreshold),
      slip_price_change_threshold_pct: String(priceThreshold),
      slip_volume_drop_threshold_pct: String(volumeThreshold),
      slip_check_lookback_ticks: lookbackSec * 20,
    }

    if (isDebug) {
      const next: ArbitrageStatusResponse = {
        ...status,
        enabled: request.enabled ?? status.enabled,
        service_account: request.service_account ?? status.service_account,
        check_interval_ticks: request.check_interval_ticks,
        min_gross_spread_pct: request.min_gross_spread_pct,
        min_net_profit_pct: request.min_net_profit_pct,
        slip_price_change_threshold_pct: request.slip_price_change_threshold_pct,
        slip_volume_drop_threshold_pct: request.slip_volume_drop_threshold_pct,
        slip_check_lookback_ticks: request.slip_check_lookback_ticks,
      }
      setStatus(next)
      syncLocalControls(next)
      setMsg({
        text: `[DEBUG] 更新: enabled=${next.enabled} service=${next.service_account} interval=${Math.round(next.check_interval_ticks / 20)}秒 spread/net=${next.min_gross_spread_pct}%/${next.min_net_profit_pct}% slip=${next.slip_price_change_threshold_pct}%/${next.slip_volume_drop_threshold_pct}%/${Math.round(next.slip_check_lookback_ticks / 20)}秒`,
        ok: true,
      })
      return
    }

    setSaving(true)
    setMsg(null)
    try {
      const updated = await adminToggleArbitrage(request)
      setMsg({
        text: `更新しました（enabled=${updated.enabled}, interval=${Math.round(updated.current_check_interval_ticks / 20)}秒, spread/net=${updated.current_min_gross_spread_pct}%/${updated.current_min_net_profit_pct}%, slip=${updated.current_slip_price_change_threshold_pct}%/${updated.current_slip_volume_drop_threshold_pct}%/${Math.round(updated.current_slip_check_lookback_ticks / 20)}秒）`,
        ok: true,
      })
      await load()
    } catch (e: unknown) {
      const apiErr = e as { message?: string }
      setMsg({ text: apiErr.message ?? '更新に失敗しました', ok: false })
    } finally {
      setSaving(false)
    }
  }

  if (loading) return <p className="admin-loading">読み込み中...</p>
  if (err) return <p className="admin-err-msg">{err}</p>
  if (!status) return <p className="admin-err-msg">状態を読み込めませんでした</p>

  return (
    <div className="arb-panel">
      <div className="arb-header">
        <h3 className="admin-section-title">裁定取引 実行管理</h3>
        <button className="admin-edit-btn" onClick={load} disabled={saving}>更新</button>
      </div>

      <div className="arb-state-row">
        <span className={`arb-badge ${status.enabled ? 'on' : 'off'}`}>
          {status.enabled ? '実行中' : '停止中'}
        </span>
        <span className="arb-meta">監視ペア: {status.pairs_under_watch.join(', ') || 'なし'}</span>
        <span className="arb-meta">監視間隔: {Math.max(1, Math.round(status.check_interval_ticks / 20))}秒</span>
        <span className="arb-meta">最小閾値: spread {status.min_gross_spread_pct}% / net {status.min_net_profit_pct}%</span>
        <span className="arb-meta">スリップ閾値: {status.slip_price_change_threshold_pct}% / {status.slip_volume_drop_threshold_pct}% / {Math.max(1, Math.round(status.slip_check_lookback_ticks / 20))}秒</span>
        <span className="arb-meta">最終チェック: {status.last_check ?? '未実行'}</span>
      </div>

      <div className="admin-form-row">
        <label>実行アカウント
          <select
            className="admin-input"
            value={selectedAccount}
            onChange={(e) => setSelectedAccount(e.target.value)}
            disabled={saving}
          >
            {accounts.map((a) => (
              <option key={a.id} value={a.id}>{a.id}</option>
            ))}
          </select>
        </label>

        <label>監視間隔（秒）
          <input
            className="admin-input"
            type="number"
            min="1"
            step="1"
            value={intervalSeconds}
            onChange={(e) => setIntervalSeconds(e.target.value)}
            disabled={saving}
          />
        </label>

        <label>最小総スプレッド（%）
          <input
            className="admin-input"
            type="number"
            min="0"
            step="0.01"
            value={minGrossSpreadPct}
            onChange={(e) => setMinGrossSpreadPct(e.target.value)}
            disabled={saving}
          />
        </label>

        <label>最小純利益（%）
          <input
            className="admin-input"
            type="number"
            min="0"
            step="0.01"
            value={minNetProfitPct}
            onChange={(e) => setMinNetProfitPct(e.target.value)}
            disabled={saving}
          />
        </label>

        <label>価格変化しきい値（%）
          <input
            className="admin-input"
            type="number"
            min="0"
            step="0.01"
            value={slipPriceChangeThresholdPct}
            onChange={(e) => setSlipPriceChangeThresholdPct(e.target.value)}
            disabled={saving}
          />
        </label>

        <label>出来高減少しきい値（%）
          <input
            className="admin-input"
            type="number"
            min="0"
            step="0.01"
            value={slipVolumeDropThresholdPct}
            onChange={(e) => setSlipVolumeDropThresholdPct(e.target.value)}
            disabled={saving}
          />
        </label>

        <label>スリッページ判定期間（秒）
          <input
            className="admin-input"
            type="number"
            min="1"
            step="1"
            value={slipLookbackSeconds}
            onChange={(e) => setSlipLookbackSeconds(e.target.value)}
            disabled={saving}
          />
        </label>

        <div className="arb-actions">
          <button
            className="admin-save-btn"
            disabled={saving}
            onClick={() => apply({ service_account: selectedAccount })}
          >
            設定反映
          </button>
          <button
            className="admin-submit-btn"
            disabled={saving || status.enabled}
            onClick={() => apply({ enabled: true, service_account: selectedAccount })}
          >
            実行開始
          </button>
          <button
            className="admin-delete-btn"
            disabled={saving || !status.enabled}
            onClick={() => apply({ enabled: false })}
          >
            停止
          </button>
        </div>
      </div>

      <div className="arb-history-grid">
        <div className="arb-recent-skips">
          <h4 className="admin-section-title">直近スキップ理由</h4>
          {status.recent_skips.length === 0 ? (
            <p className="admin-loading">スキップ履歴なし</p>
          ) : (
            <ul className="pending-list">
              {status.recent_skips.map((s, idx) => (
                <li key={`${s.timestamp}-${idx}`} className="pending-with">
                  <span>{s.pair} / {s.reason}</span>
                  <span>{s.timestamp}</span>
                </li>
              ))}
            </ul>
          )}
        </div>

        <div className="arb-recent-results">
          <h4 className="admin-section-title">直近裁定取引結果</h4>
          {status.recent_executions.length === 0 ? (
            <p className="admin-loading">約定履歴なし</p>
          ) : (
            <ul className="pending-list">
              {status.recent_executions.map((r, idx) => (
                <li key={`${r.timestamp}-${r.order_ids.join('-')}-${idx}`} className="pending-dep">
                  <span>{r.pair} / {r.status} / qty {r.quantity}</span>
                  <span>{r.timestamp}</span>
                </li>
              ))}
            </ul>
          )}
        </div>
      </div>

      {msg && <p className={`admin-msg ${msg.ok ? 'ok' : 'err'}`}>{msg.text}</p>}
    </div>
  )
}

function MarketMakerControlPanel({ isDebug }: { isDebug: boolean }) {
  const [status, setStatus] = useState<MarketMakerStatusResponse | null>(null)
  const [accounts, setAccounts] = useState<ServiceAccount[]>([])
  const [selectedAccount, setSelectedAccount] = useState('svc:gekiyaba_mm')
  const [intervalSeconds, setIntervalSeconds] = useState('1')
  const [volumeUsagePct, setVolumeUsagePct] = useState('1.0')
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [msg, setMsg] = useState<{ text: string; ok: boolean } | null>(null)
  const [err, setErr] = useState<string | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    setErr(null)
    try {
      if (isDebug) {
        setStatus(DEBUG_MARKET_MAKER_STATUS)
        setAccounts(DEBUG_SERVICE_ACCOUNTS)
        setSelectedAccount(DEBUG_MARKET_MAKER_STATUS.service_account)
        setIntervalSeconds(String(Math.max(1, Math.round(DEBUG_MARKET_MAKER_STATUS.current_loop_interval_ticks / 20))))
        setVolumeUsagePct(DEBUG_MARKET_MAKER_STATUS.current_volume_usage_pct)
        return
      }
      const [next, svcAccounts] = await Promise.all([
        adminFetchMarketMakerStatus(),
        adminFetchServiceAccounts(),
      ])
      setStatus(next)
      setAccounts(svcAccounts)
      setSelectedAccount(next.service_account)
      setIntervalSeconds(String(Math.max(1, Math.round(next.current_loop_interval_ticks / 20))))
      setVolumeUsagePct(next.current_volume_usage_pct)
    } catch (e: unknown) {
      const apiErr = e as { message?: string }
      setErr(apiErr.message ?? 'アクティブMMステータスの取得に失敗しました')
    } finally {
      setLoading(false)
    }
  }, [isDebug])

  useEffect(() => {
    void load()
  }, [load])

  const handleToggle = async (enabled: boolean) => {
    if (!status) return

    if (isDebug) {
      const next: MarketMakerStatusResponse = {
        ...status,
        running: enabled,
        timestamp: new Date().toISOString(),
      }
      setStatus(next)
      setMsg({ text: `[DEBUG] アクティブMMを ${enabled ? '開始' : '停止'} したことにします`, ok: true })
      return
    }

    setSaving(true)
    setMsg(null)
    try {
      const updated = await adminToggleMarketMaker({ enabled, service_account: selectedAccount })
      setMsg({ text: `アクティブMMを ${updated.enabled ? '開始' : '停止'} しました`, ok: true })
      await load()
    } catch (e: unknown) {
      const apiErr = e as { message?: string }
      setMsg({ text: apiErr.message ?? 'アクティブMMの切り替えに失敗しました', ok: false })
    } finally {
      setSaving(false)
    }
  }

  const handleApplyAccount = async () => {
    if (!status) return

    if (isDebug) {
      setStatus({
        ...status,
        service_account: selectedAccount,
        timestamp: new Date().toISOString(),
      })
      setMsg({ text: `[DEBUG] AMMの実行口座を ${selectedAccount} にしました`, ok: true })
      return
    }

    setSaving(true)
    setMsg(null)
    try {
      await adminToggleMarketMaker({ service_account: selectedAccount })
      setMsg({ text: `AMMの実行口座を ${selectedAccount} にしました`, ok: true })
      await load()
    } catch (e: unknown) {
      const apiErr = e as { message?: string }
      setMsg({ text: apiErr.message ?? 'AMMの実行口座更新に失敗しました', ok: false })
    } finally {
      setSaving(false)
    }
  }

  const handleApplyConfig = async () => {
    if (!status) return

    const seconds = Number(intervalSeconds)
    if (!Number.isFinite(seconds) || !Number.isInteger(seconds) || seconds < 1) {
      setMsg({ text: 'AMMの実行間隔は1以上の整数秒で入力してください', ok: false })
      return
    }

    const usagePct = Number(volumeUsagePct)
    if (!Number.isFinite(usagePct) || usagePct < 0 || usagePct > 100) {
      setMsg({ text: 'AMMの出来高使用率(%)は0以上100以下で入力してください', ok: false })
      return
    }

    if (isDebug) {
      setStatus({
        ...status,
        current_loop_interval_ticks: seconds * 20,
        current_volume_usage_pct: String(usagePct),
        timestamp: new Date().toISOString(),
      })
      setMsg({ text: `[DEBUG] AMM設定を反映しました（間隔 ${seconds} 秒 / 出来高使用率 ${usagePct}%）`, ok: true })
      return
    }

    setSaving(true)
    setMsg(null)
    try {
      await adminToggleMarketMaker({
        loop_interval_ticks: seconds * 20,
        volume_usage_pct: String(usagePct),
        service_account: selectedAccount,
      })
      setIntervalSeconds(String(seconds))
      setVolumeUsagePct(String(usagePct))
      setMsg({ text: `AMM設定を反映しました（間隔 ${seconds} 秒 / 出来高使用率 ${usagePct}%）`, ok: true })
      await load()
    } catch (e: unknown) {
      const apiErr = e as { message?: string }
      setMsg({ text: apiErr.message ?? 'AMM設定の更新に失敗しました', ok: false })
    } finally {
      setSaving(false)
    }
  }

  if (loading) return <p className="admin-loading">読み込み中...</p>
  if (err) return <p className="admin-err-msg">{err}</p>
  if (!status) return <p className="admin-err-msg">状態を読み込めませんでした</p>

  return (
    <div className="arb-panel">
      <div className="arb-header">
        <h3 className="admin-section-title">アクティブMM 制御</h3>
        <button className="admin-edit-btn" onClick={() => { void load() }} disabled={saving}>更新</button>
      </div>

      <div className="arb-state-row">
        <span className={`arb-badge ${status.running ? 'on' : 'off'}`}>
          {status.running ? '稼働中' : '停止中'}
        </span>
        <span className="arb-meta">実行口座: {status.service_account}</span>
        <span className="arb-meta">実行間隔: {Math.max(1, Math.round(status.current_loop_interval_ticks / 20))}秒</span>
        <span className="arb-meta">出来高使用率: {status.current_volume_usage_pct}%</span>
        <span className="arb-meta">監視ペア: {status.tracked_pairs}</span>
        <span className="arb-meta">状態: PASSIVE {status.passive_pairs} / SQUEEZING {status.squeezing_pairs} / MATCHING {status.matching_pairs}</span>
        <span className="arb-meta">自前注文: {status.owned_orders}</span>
        <span className="arb-meta">最終更新: {status.timestamp}</span>
      </div>

      <p className="admin-backup-desc">
        裁定取引とは独立した常駐サービスです。板の流動性維持を担当し、停止すると自前注文を引き上げます。
      </p>

      <div className="admin-form-row">
        <label>実行口座
          <select
            className="admin-input"
            value={selectedAccount}
            onChange={(e) => setSelectedAccount(e.target.value)}
            disabled={saving}
          >
            {accounts.map((account) => (
              <option key={account.id} value={account.id}>{account.id}</option>
            ))}
          </select>
        </label>
        <button className="admin-save-btn" type="button" onClick={() => { void handleApplyAccount() }} disabled={saving}>
          口座反映
        </button>
        <label>実行間隔（秒）
          <input
            className="admin-input"
            type="number"
            min="1"
            step="1"
            value={intervalSeconds}
            onChange={(e) => setIntervalSeconds(e.target.value)}
            disabled={saving}
          />
        </label>
        <label>出来高使用率（%）
          <input
            className="admin-input"
            type="number"
            min="0"
            max="100"
            step="0.1"
            value={volumeUsagePct}
            onChange={(e) => setVolumeUsagePct(e.target.value)}
            disabled={saving}
          />
        </label>
        <button className="admin-save-btn" type="button" onClick={() => { void handleApplyConfig() }} disabled={saving}>
          設定反映
        </button>
      </div>

      <div className="arb-actions">
        <button className="admin-submit-btn" type="button" onClick={() => { void handleToggle(true) }} disabled={saving || status.running}>
          稼働開始
        </button>
        <button className="admin-delete-btn" type="button" onClick={() => { void handleToggle(false) }} disabled={saving || !status.running}>
          停止
        </button>
      </div>

      <div className="amm-log-panel">
        <h4 className="admin-section-title">最近のAMMログ</h4>
        {status.recent_logs.length === 0 ? (
          <p className="admin-loading">ログなし</p>
        ) : (
          <ul className="pending-list amm-log-list">
            {status.recent_logs.map((log, idx) => (
              <li key={`${log.timestamp}-${idx}`} className="pending-dep amm-log-item">
                <span className="amm-log-message">{log.level} / {log.action} / {log.message}</span>
                <span className="amm-log-time">{log.timestamp}</span>
              </li>
            ))}
          </ul>
        )}
      </div>

      {msg && <p className={`admin-msg ${msg.ok ? 'ok' : 'err'}`}>{msg.text}</p>}
    </div>
  )
}

// ─── ペアテーブル ─────────────────────────────────────────────────────────────

function PairTable({ isDebug }: { isDebug: boolean }) {
  const [pairs,    setPairs]    = useState<AdminPair[]>([])
  const [loading,  setLoading]  = useState(true)
  const [reordering, setReordering] = useState(false)
  const [orderMsg, setOrderMsg] = useState<{ text: string; ok: boolean } | null>(null)
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

  const movePair = async (fromIndex: number, toIndex: number) => {
    if (reordering) return
    if (fromIndex < 0 || toIndex < 0 || fromIndex >= pairs.length || toIndex >= pairs.length) return

    const next = [...pairs]
    const [moved] = next.splice(fromIndex, 1)
    next.splice(toIndex, 0, moved)

    if (isDebug) {
      setPairs(next)
      setOrderMsg({ text: `[DEBUG] 並び順を変更: ${moved.id} を ${toIndex + 1} 番目に移動`, ok: true })
      return
    }

    setReordering(true)
    setOrderMsg(null)
    try {
      await adminReorderPairs(next.map((p) => p.id))
      setPairs(next)
      setOrderMsg({ text: `並び順を保存しました: ${moved.id} を ${toIndex + 1} 番目に移動`, ok: true })
    } catch (e: unknown) {
      const err = e as { message?: string }
      setOrderMsg({ text: err.message ?? '並び順の保存に失敗しました', ok: false })
    } finally {
      setReordering(false)
    }
  }

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
            : pairs.map((p, idx) => (
                <PairRow
                  key={p.id}
                  pair={p}
                  canMoveUp={idx > 0}
                  canMoveDown={idx < pairs.length - 1}
                  movingDisabled={reordering}
                  onMoveUp={() => { void movePair(idx, idx - 1) }}
                  onMoveDown={() => { void movePair(idx, idx + 1) }}
                  onChanged={load}
                  isDebug={isDebug}
                />
              ))
          }
        </tbody>
      </table>
      {orderMsg && <p className={`admin-msg ${orderMsg.ok ? 'ok' : 'err'}`}>{orderMsg.text}</p>}
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
          <ArbitrageControlPanel isDebug={isDebug} />
        </section>

        <section className="admin-section">
          <MarketMakerControlPanel isDebug={isDebug} />
        </section>

        <section className="admin-section">
          <ServiceAccountBalances isDebug={isDebug} />
        </section>

        <section className="admin-section">
          <FeeSettingsPanel isDebug={isDebug} />
        </section>

        <section className="admin-section">
          <BackupDownloadPanel isDebug={isDebug} />
        </section>

        <section className="admin-section">
          <LoginLinkSettingsPanel isDebug={isDebug} />
        </section>
      </main>
    </div>
  )
}
