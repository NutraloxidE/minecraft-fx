/**
 * 入出金パネル
 *
 * - アイテム名（Minecraft Material 名）と数量を入力して入金・出金を実行
 * - pending_deposit / pending_withdraw をリアルタイム表示
 * - オフライン中に処理された分はログイン時に自動解消される旨をメッセージで案内
 */

import { useEffect, useState } from 'react'
import { ApiException, deposit, fetchAtmSession, withdraw } from '@/lib/api'
import type { AtmSessionResponse, PlayerStateResponse, PairSummary } from '@/types/api'

interface Props {
  playerState: PlayerStateResponse | null
  pair: PairSummary | null
  onDone: () => void
}

export default function DepositPanel({ playerState, pair, onDone }: Props) {
  const [item, setItem]       = useState('')
  const [amount, setAmount]   = useState('')
  const [msg, setMsg]         = useState<{ text: string; ok: boolean } | null>(null)
  const [busy, setBusy]       = useState(false)
  const [atmSession, setAtmSession] = useState<AtmSessionResponse | null>(null)
  const [atmLoading, setAtmLoading] = useState(true)

  useEffect(() => {
    let mounted = true
    setAtmLoading(true)
    fetchAtmSession()
      .then((s) => {
        if (!mounted) return
        setAtmSession(s)
      })
      .catch(() => {
        if (!mounted) return
        setAtmSession({ active: false, atm_id: null, grade: null, max_distance: 3 })
      })
      .finally(() => {
        if (!mounted) return
        setAtmLoading(false)
      })

    return () => {
      mounted = false
    }
  }, [playerState?.uuid])

  const isLocked = atmLoading || !atmSession?.active

  const validate = (): number | null => {
    const n = parseInt(amount, 10)
    if (!item.trim()) { setMsg({ text: 'アイテム名を入力してください', ok: false }); return null }
    if (isNaN(n) || n <= 0) { setMsg({ text: '数量は1以上の整数で入力してください', ok: false }); return null }
    return n
  }

  const handleDeposit = async () => {
    if (isLocked) {
      setMsg({ text: 'ATMの近くで右クリックしてから利用してください（3ブロック以内）', ok: false })
      return
    }
    const n = validate(); if (n === null) return
    setBusy(true); setMsg(null)
    try {
      const res = await deposit({ item: item.trim(), amount: n })
      if (res.pending && res.pending > 0) {
        setMsg({ text: `${res.pending} 個を預け入れ待機中（オフライン）`, ok: true })
      } else {
        setMsg({ text: `${res.deposited ?? n} 個を入金しました`, ok: true })
      }
      onDone()
    } catch (e: unknown) {
      if (e instanceof ApiException && e.status === 403) {
        setAtmSession({ active: false, atm_id: null, grade: null, max_distance: 3 })
        setMsg({ text: 'ATMから3ブロック以内で操作してください。最寄りATMへ移動してください。', ok: false })
      } else {
        const err = e as { message?: string }
        setMsg({ text: err.message ?? '入金に失敗しました', ok: false })
      }
    } finally {
      setBusy(false)
    }
  }

  const handleWithdraw = async () => {
    if (isLocked) {
      setMsg({ text: 'ATMの近くで右クリックしてから利用してください（3ブロック以内）', ok: false })
      return
    }
    const n = validate(); if (n === null) return
    setBusy(true); setMsg(null)
    try {
      const res = await withdraw({ item: item.trim(), amount: n })
      if (res.pending && res.pending > 0) {
        setMsg({ text: `${res.pending} 個を出金待機中（オフライン）`, ok: true })
      } else {
        setMsg({ text: `${res.withdrawn ?? n} 個を出金しました`, ok: true })
      }
      onDone()
    } catch (e: unknown) {
      if (e instanceof ApiException && e.status === 403) {
        setAtmSession({ active: false, atm_id: null, grade: null, max_distance: 3 })
        setMsg({ text: 'ATMから3ブロック以内で操作してください。最寄りATMへ移動してください。', ok: false })
      } else {
        const err = e as { message?: string }
        setMsg({ text: err.message ?? '出金に失敗しました', ok: false })
      }
    } finally {
      setBusy(false)
    }
  }

  const pendingDep  = Object.entries(playerState?.pending_deposit  ?? {})
  const pendingWith = Object.entries(playerState?.pending_withdraw ?? {})
  const hasPending  = pendingDep.length > 0 || pendingWith.length > 0

  return (
    <div className="deposit-panel">
      <h3 className="deposit-panel-title">入出金</h3>

      {/* 入力フォーム */}
      <div className="deposit-form">
        {isLocked && (
          <div className="deposit-message err" style={{ marginBottom: 8 }}>
            ATMの近くで右クリックしてから利用してください（3ブロック以内）
            <div style={{ marginTop: 6 }}>
              <button className="deposit-btn" type="button" onClick={() => setMsg({ text: 'ATMへ移動して [FX] 看板を右クリックしてください。', ok: false })}>
                ATMへ行く
              </button>
            </div>
          </div>
        )}

        {pair && (
          <div className="deposit-item-chips">
            {[pair.base, pair.quote].map((itm) => (
              <button
                key={itm}
                className={`deposit-chip${item === itm ? ' active' : ''}`}
                onClick={() => setItem(itm)}
                disabled={busy || isLocked}
                type="button"
              >
                {itm}
              </button>
            ))}
          </div>
        )}
        <div className="deposit-row">
          <input
            className="deposit-input"
            placeholder="アイテム名 (例: diamond)"
            value={item}
            onChange={(e) => setItem(e.target.value)}
            disabled={busy || isLocked}
          />
          <input
            className="deposit-input"
            style={{ maxWidth: 80 }}
            placeholder="数量"
            type="number"
            min={1}
            step={1}
            value={amount}
            onChange={(e) => setAmount(e.target.value)}
            disabled={busy || isLocked}
          />
          <button className="deposit-btn"  onClick={handleDeposit}  disabled={busy || isLocked}>入金</button>
          <button className="withdraw-btn" onClick={handleWithdraw} disabled={busy || isLocked}>出金</button>
        </div>
        {msg && (
          <p className={`deposit-message ${msg.ok ? 'ok' : 'err'}`}>{msg.text}</p>
        )}
      </div>

      {/* Pending 表示 */}
      {hasPending && (
        <div className="pending-section">
          <p className="pending-title">保留中（次回ログイン時に処理）</p>
          <ul className="pending-list">
            {pendingDep.map(([itm, cnt]) => (
              <li key={`dep-${itm}`} className="pending-dep">
                <span>入金待ち: {itm}</span>
                <span>{cnt} 個</span>
              </li>
            ))}
            {pendingWith.map(([itm, cnt]) => (
              <li key={`with-${itm}`} className="pending-with">
                <span>出金待ち: {itm}</span>
                <span>{cnt} 個</span>
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  )
}
