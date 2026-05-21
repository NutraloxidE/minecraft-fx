import { useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { ApiException, resolveTransferTarget, transfer } from '@/lib/api'
import { usePlayerAuth } from '@/hooks/usePlayerAuth'
import { useDebugMode } from '@/hooks/useDebugMode'
import { DEBUG_PLAYER_STATE, DEBUG_TRANSFER_TARGET } from '@/lib/debugData'

export default function TransferPage() {
  const navigate = useNavigate()
  const isDebug = useDebugMode()
  const { authState, playerState: realPlayerState, error, logout, refresh } = usePlayerAuth()

  // デバッグモードでは認証なしでフェイクデータを使う
  const playerState = isDebug ? DEBUG_PLAYER_STATE : realPlayerState
  const effectiveAuthState = isDebug ? 'authenticated' : authState

  const [query, setQuery] = useState('')
  const [searching, setSearching] = useState(false)
  const [target, setTarget] = useState<{ found: boolean; uuid?: string; name?: string } | null>(null)
  const [targetErr, setTargetErr] = useState<string | null>(null) 

  const [item, setItem] = useState('')
  const [amount, setAmount] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [submitMsg, setSubmitMsg] = useState<{ ok: boolean; text: string } | null>(null)
  const [showMyQr, setShowMyQr] = useState(false)
  const [shareMsg, setShareMsg] = useState<string | null>(null)

  const hotStorage = playerState?.hot_storage ?? {}
  const selectableItems = useMemo(
    () => Object.entries(hotStorage).filter(([, v]) => (parseFloat(v) || 0) > 0),
    [hotStorage],
  )

  const canSubmit = !!target?.found && !!target.uuid && !!item && (parseFloat(amount) || 0) > 0 && !submitting
  const selfTargetId = playerState?.uuid ?? ''
  const myTransferUrl = useMemo(() => {
    if (!selfTargetId || typeof window === 'undefined') return ''
    const url = new URL(window.location.href)
    url.searchParams.set('targetUUID', selfTargetId)
    return url.toString()
  }, [selfTargetId])
  const myTransferQrSrc = useMemo(() => {
    if (!myTransferUrl) return ''
    return `https://api.qrserver.com/v1/create-qr-code/?size=220x220&data=${encodeURIComponent(myTransferUrl)}`
  }, [myTransferUrl])

  useEffect(() => {
    if (typeof window === 'undefined') return
    const targetFromQuery = new URLSearchParams(window.location.search).get('targetUUID')
    if (!targetFromQuery) {
      return
    }

    setQuery(targetFromQuery)

    // クエリに targetUUID がある場合は自動検索実行
    const performAutoSearch = async () => {
      setTargetErr(null)
      setSubmitMsg(null)

      if (isDebug) {
        setTarget(DEBUG_TRANSFER_TARGET)
        if (DEBUG_TRANSFER_TARGET.uuid === playerState?.uuid) {
          setTargetErr('自分自身には振り込めません')
        }
        return
      }

      try {
        const res = await resolveTransferTarget(targetFromQuery)
        setTarget(res)
        if (!res.found) {
          setTargetErr('振込先が見つかりません')
        } else if (res.uuid === playerState?.uuid) {
          setTargetErr('自分自身には振り込めません')
        }
      } catch (e) {
        if (e instanceof ApiException) {
          setTargetErr(e.code)
        } else {
          setTargetErr('検索に失敗しました')
        }
        setTarget(null)
      }
    }

    performAutoSearch()
  }, [isDebug, playerState?.uuid])

  const onSearch = async () => {
    setTargetErr(null)
    setSubmitMsg(null)
    if (!query.trim()) {
      setTargetErr('検索文字列を入力してください')
      return
    }

    if (isDebug) {
      setTarget(DEBUG_TRANSFER_TARGET)
      if (DEBUG_TRANSFER_TARGET.uuid === playerState?.uuid) setTargetErr('自分自身には振り込めません')
      return
    }

    setSearching(true)
    try {
      const res = await resolveTransferTarget(query.trim())
      setTarget(res)
      if (!res.found) setTargetErr('振込先が見つかりません')
      else if (res.uuid === playerState?.uuid) setTargetErr('自分自身には振り込めません')
    } catch (e) {
      if (e instanceof ApiException) setTargetErr(e.code)
      else setTargetErr('検索に失敗しました')
      setTarget(null)
    } finally {
      setSearching(false)
    }
  }

  const onShareQr = async () => {
    if (!myTransferUrl) return
    try {
      await navigator.clipboard.writeText(myTransferUrl)
      setShareMsg('URLをコピーしました')
      setTimeout(() => setShareMsg(null), 2000)
    } catch {
      setShareMsg('コピーに失敗しました')
      setTimeout(() => setShareMsg(null), 2000)
    }
  }

  const onSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setSubmitMsg(null)
    if (!canSubmit || !target?.uuid) return

    if (isDebug) {
      setSubmitMsg({ ok: true, text: `[DEBUG] 振込完了: ${amount} ${item} → ${target.name ?? target.uuid}` })
      setAmount('')
      return
    }

    setSubmitting(true)
    try {
      const res = await transfer({
        to_uuid: target.uuid,
        item,
        amount,
      })
      setSubmitMsg({ ok: true, text: `振込完了: ${res.amount} ${res.item}` })
      setAmount('')
      await refresh()
    } catch (e) {
      if (e instanceof ApiException) setSubmitMsg({ ok: false, text: e.code })
      else setSubmitMsg({ ok: false, text: '振込に失敗しました' })
    } finally {
      setSubmitting(false)
    }
  }

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
        <h1 className="auth-title">GekiyabaFX</h1>
        <p className="auth-message">
          {error
            ? `認証エラー: ${error}`
            : 'ゲーム内で /fx trade コマンドを実行し、表示された URL からアクセスしてください。'}
        </p>
      </div>
    )
  }

  return (
    <div className="trade-page">
      <header className="trade-header">
        <span className="trade-header-title">💥GekiyabaFX Transfer</span>
        <span className="trade-header-user">{playerState?.name ?? ''}</span>
        <button className="trade-header-logout" onClick={() => navigate('/trade')}>戻る</button>
        <button className="trade-header-logout" onClick={logout}>ログアウト</button>
      </header>

      <main className="transfer-main">
        <section className="trade-section transfer-section">
          <h2 className="section-title">自分の振込先QR</h2>
          <div className="transfer-qr-controls">
            <button
              className="order-submit buy"
              type="button"
              onClick={() => setShowMyQr((v) => !v)}
              disabled={!myTransferUrl}
            >
              {showMyQr ? 'QRを閉じる' : '自分のQRを表示'}
            </button>
          </div>

          {showMyQr && myTransferUrl && (
            <div className="transfer-qr-box">
              <img className="transfer-qr-image" src={myTransferQrSrc} alt="自分の振込先QR" />
              <div className="transfer-qr-target">targetUUID: {selfTargetId}</div>
              <button
                className="order-submit sell"
                type="button"
                onClick={onShareQr}
              >
                URLをシェア
              </button>
              {shareMsg && (
                <p className="transfer-share-msg">{shareMsg}</p>
              )}
            </div>
          )}
        </section>

        <section className="trade-section transfer-section">
          <h2 className="section-title">振込先検索</h2>
          <div className="transfer-row">
            <input
              className="transfer-input"
              placeholder="UUID またはユーザー名"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
            />
            <button className="order-submit buy" type="button" onClick={onSearch} disabled={searching}>
              {searching ? '検索中...' : '検索'}
            </button>
          </div>

          {target && target.found && (
            <div className="transfer-target-ok">
              <div>ステータス: 見つかりました</div>
              <div>ユーザー名: {target.name ?? '—'}</div>
              <div>UUID: {target.uuid}</div>
            </div>
          )}
          {targetErr && <p className="order-error">{targetErr}</p>}
        </section>

        <section className="trade-section transfer-section">
          <h2 className="section-title">振込実行</h2>
          <form onSubmit={onSubmit} className="transfer-form">
            <label className="order-field">
              <span>通貨キー</span>
              <select className="transfer-select" value={item} onChange={(e) => setItem(e.target.value)}>
                <option value="">選択してください</option>
                {selectableItems.map(([k, v]) => (
                  <option key={k} value={k}>{k} ({v})</option>
                ))}
              </select>
            </label>

            <label className="order-field">
              <span>金額</span>
              <input
                className="transfer-input"
                type="text"
                inputMode="decimal"
                placeholder="0.0000"
                value={amount}
                onChange={(e) => setAmount(e.target.value)}
              />
            </label>

            <button type="submit" className="order-submit sell" disabled={!canSubmit || (target?.uuid === playerState?.uuid)}>
              {submitting ? '振込中...' : '振り込む'}
            </button>

            {submitMsg && (
              <p className={submitMsg.ok ? 'admin-msg ok' : 'admin-msg err'}>{submitMsg.text}</p>
            )}
          </form>
        </section>
      </main>
    </div>
  )
}
