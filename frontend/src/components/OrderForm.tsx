/**
 * 注文パネルコンポーネント
 *
 * 買い・売りを左右並列表示（Binanceスタイル）。
 * 各列に LIMIT / MARKET タブ、入力欄、発注ボタンを独立配置。
 */

import { useState, useEffect } from 'react'
import { placeOrder } from '@/lib/api'
import { ApiException } from '@/lib/api'
import type { PairSummary, PlaceOrderResponse } from '@/types/api'

interface Props {
  pair: PairSummary | null
  hotStorage: Record<string, string>
  onOrderPlaced: (res: PlaceOrderResponse) => void
  externalPrice?: { price: string; side: 'BUY' | 'SELL'; key: number } | null
}

type Side = 'BUY' | 'SELL'
type OrderType = 'LIMIT' | 'MARKET'

interface SideFormProps {
  side: Side
  pair: PairSummary
  base: string
  quote: string
  hotStorage: Record<string, string>
  onOrderPlaced: (res: PlaceOrderResponse) => void
  externalPrice?: { price: string; side: 'BUY' | 'SELL'; key: number } | null
}

function OrderSideForm({ side, pair, base, quote, hotStorage, onOrderPlaced, externalPrice }: SideFormProps) {
  const [type, setType] = useState<OrderType>('LIMIT')
  const [price, setPrice] = useState(pair.last_price ?? '')
  const [amount, setAmount] = useState('')
  const [maxSpend, setMaxSpend] = useState('')
  const [pct, setPct] = useState(0)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [flashing, setFlashing] = useState(false)

  // ペアが切り替わったら価格・数量をリセット
  useEffect(() => {
    setPrice(pair.last_price ?? '')
    setAmount('')
    setMaxSpend('')
    setPct(0)
    setError(null)
  }, [pair.id, pair.last_price])

  // チャートから価格設定
  useEffect(() => {
    if (!externalPrice || externalPrice.side !== side) return
    setType('LIMIT')
    setPrice(externalPrice.price)
    setPct(0)
    setFlashing(true)
    const t = setTimeout(() => setFlashing(false), 600)
    return () => clearTimeout(t)
  }, [externalPrice?.key])

  const isBuy = side === 'BUY'

  // 価格の上下ステップ（min_price 単位）
  const priceStep = parseFloat(pair.min_price) || 1
  const adjustPrice = (dir: 1 | -1, multiplier = 1) => {
    const current = parseFloat(price)
    const base_ = isNaN(current) ? (parseFloat(pair.last_price ?? '0') || 0) : current
    const next = Math.max(0, base_ + dir * priceStep * multiplier)
    const decimals = (pair.min_price.split('.')[1] ?? '').length
    setPrice(next.toFixed(decimals))
    setPct(0)
  }

  // 数量の上下ステップ（min_amount 単位）
  const amountStep = parseFloat(pair.min_amount) || 1
  const adjustAmount = (dir: 1 | -1, multiplier = 1) => {
    const current = parseFloat(amount)
    const base_ = isNaN(current) ? 0 : current
    const next = Math.max(0, base_ + dir * amountStep * multiplier)
    const decimals = (pair.min_amount.split('.')[1] ?? '').length
    setAmount(next.toFixed(decimals))
    setPct(0)
  }

  // 最大支払いの上下ステップ（min_price 単位で代用）
  const adjustMaxSpend = (dir: 1 | -1, multiplier = 1) => {
    const current = parseFloat(maxSpend)
    const base_ = isNaN(current) ? 0 : current
    const next = Math.max(0, base_ + dir * priceStep * multiplier)
    const decimals = (pair.min_price.split('.')[1] ?? '').length
    setMaxSpend(next.toFixed(decimals))
    setPct(0)
  }

  // 利用可能残高（hotStorageキーは小文字前提）
  const baseBalance  = parseFloat(hotStorage[base.toLowerCase()]  ?? '0') || 0
  const quoteBalance = parseFloat(hotStorage[quote.toLowerCase()] ?? '0') || 0

  // スライダー変更時に入力値を自動計算
  const applyPct = (newPct: number) => {
    setPct(newPct)
    const ratio = newPct / 100
    if (isBuy) {
      const spend = quoteBalance * ratio
      if (type === 'LIMIT') {
        const p = parseFloat(price)
        if (!isNaN(p) && p > 0) {
          setAmount((spend / p).toFixed(4))
        } else {
          setMaxSpend(spend.toFixed(4))
        }
      } else {
        setMaxSpend(spend.toFixed(4))
      }
    } else {
      setAmount((baseBalance * ratio).toFixed(4))
    }
  }

  // 最大値表示用
  const maxInfoText = (() => {
    if (isBuy) {
      const p = parseFloat(price)
      if (type === 'LIMIT' && !isNaN(p) && p > 0)
        return `最大買い: ${(quoteBalance / p).toFixed(4)} ${base}`
      return `最大支払い: ${quoteBalance.toFixed(4)} ${quote}`
    }
    return `最大売り: ${baseBalance.toFixed(4)} ${base}`
  })()

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setError(null)
    setSubmitting(true)
    try {
      const req = {
        pair_id: pair.id,
        side,
        type,
        ...(type === 'LIMIT' ? { price, amount } : {}),
        ...(type === 'MARKET' && side === 'BUY' ? { max_spend: maxSpend } : {}),
        ...(type === 'MARKET' && side === 'SELL' ? { amount } : {}),
      }
      const res = await placeOrder(req)
      onOrderPlaced(res)
      setPrice('')
      setAmount('')
      setMaxSpend('')
      setPct(0)
    } catch (e) {
      if (e instanceof ApiException) setError(e.code)
      else setError('unknown_error')
    } finally {
      setSubmitting(false)
    }
  }

  // ロック計算
  const lockInfo = (() => {
    if (side === 'BUY') {
      if (type === 'LIMIT') {
        const p = parseFloat(price)
        const a = parseFloat(amount)
        if (!isNaN(p) && p > 0 && !isNaN(a) && a > 0)
          return { item: quote, amount: (p * a).toFixed(4) }
      } else {
        const s = parseFloat(maxSpend)
        if (!isNaN(s) && s > 0)
          return { item: quote, amount: s.toFixed(4) }
      }
    } else {
      const a = parseFloat(amount)
      if (!isNaN(a) && a > 0)
        return { item: base, amount: a.toFixed(4) }
    }
    return null
  })()

  return (
    <form className={`order-col${isBuy ? ' buy-col' : ' sell-col'}${flashing ? ' order-col-flash' : ''}`} onSubmit={handleSubmit}>
      {/* 列ヘッダー */}
      <div className={`order-col-header${isBuy ? ' buy' : ' sell'}`}>
        {isBuy ? `買い (${base})` : `売り (${base})`}
      </div>

      {/* 注文種別 */}
      <div className="order-type-tabs">
        {(['LIMIT', 'MARKET'] as OrderType[]).map((t) => (
          <button
            key={t}
            type="button"
            className={`type-tab${type === t ? ` active ${isBuy ? 'buy' : 'sell'}` : ''}`}
            onClick={() => { setType(t); setPct(0) }}
          >
            {t === 'LIMIT' ? '指値' : '成行'}
          </button>
        ))}
      </div>

      {/* 価格（指値のみ） */}
      {type === 'LIMIT' && (
        <label className="order-field">
          <span>価格 ({quote})<span className="price-step-hint"> ▼▲ Ctrl+クリックで10倍</span></span>
          <div className="order-price-input-wrap">
            <button type="button" className="price-step-btn" onClick={(e) => adjustPrice(-1, e.ctrlKey ? 10 : 1)}>▼</button>
            <input
              type="text"
              inputMode="decimal"
              value={price}
              onChange={(e) => { setPrice(e.target.value); setPct(0) }}
              placeholder={pair.min_price}
              required
            />
            <button type="button" className="price-step-btn" onClick={(e) => adjustPrice(1, e.ctrlKey ? 10 : 1)}>▲</button>
          </div>
        </label>
      )}

      {/* 数量（LIMIT または MARKET SELL） */}
      {(type === 'LIMIT' || (type === 'MARKET' && side === 'SELL')) && (
        <label className="order-field">
          <span>数量 ({base})</span>
          <div className="order-price-input-wrap">
            <button type="button" className="price-step-btn" onClick={(e) => adjustAmount(-1, e.ctrlKey ? 10 : 1)}>▼</button>
            <input
              type="text"
              inputMode="decimal"
              value={amount}
              onChange={(e) => { setAmount(e.target.value); setPct(0) }}
              placeholder={pair.min_amount}
              required
            />
            <button type="button" className="price-step-btn" onClick={(e) => adjustAmount(1, e.ctrlKey ? 10 : 1)}>▲</button>
          </div>
        </label>
      )}

      {/* 最大支払い（MARKET BUY） */}
      {type === 'MARKET' && side === 'BUY' && (
        <label className="order-field">
          <span>最大支払い ({quote})</span>
          <div className="order-price-input-wrap">
            <button type="button" className="price-step-btn" onClick={(e) => adjustMaxSpend(-1, e.ctrlKey ? 10 : 1)}>▼</button>
            <input
              type="text"
              inputMode="decimal"
              value={maxSpend}
              onChange={(e) => { setMaxSpend(e.target.value); setPct(0) }}
              placeholder="0.0000"
              required
            />
            <button type="button" className="price-step-btn" onClick={(e) => adjustMaxSpend(1, e.ctrlKey ? 10 : 1)}>▲</button>
          </div>
        </label>
      )}

      {error && <p className="order-error">{error}</p>}

      {/* 残高スライダー */}
      <div className="order-slider-wrap">
        <div className="order-slider-labels">
          <span className="order-slider-balance">
            {isBuy
              ? `残高: ${quoteBalance.toFixed(4)} ${quote}`
              : `残高: ${baseBalance.toFixed(4)} ${base}`
            }
          </span>
          <span className="order-slider-pct">{pct}%</span>
        </div>
        <input
          type="range"
          min={0} max={100} step={1}
          value={pct}
          onChange={(e) => applyPct(Number(e.target.value))}
          className={`order-slider${isBuy ? ' buy' : ' sell'}`}
        />
        <div className="order-slider-steps">
          {[25, 50, 75, 100].map((v) => (
            <button key={v} type="button"
              className={`slider-step${pct === v ? ` active ${isBuy ? 'buy' : 'sell'}` : ''}`}
              onClick={() => applyPct(v)}>
              {v}%
            </button>
          ))}
        </div>
      </div>

      <div className="order-col-spacer" />

      {/* 最大値表示 */}
      <div className="order-max-info">{maxInfoText}</div>

      <button
        type="submit"
        className={`order-submit${isBuy ? ' buy' : ' sell'}`}
        disabled={submitting}
      >
        <span className="order-submit-label">
          {submitting ? '発注中...' : isBuy ? '買い注文' : '売り注文'}
        </span>
        {!submitting && lockInfo && (
          <span className="order-submit-lock">
            約定までロックされます: {lockInfo.item} {lockInfo.amount}
          </span>
        )}
      </button>
    </form>
  )
}

export default function OrderForm({ pair, hotStorage, onOrderPlaced, externalPrice }: Props) {
  if (!pair) {
    return <div className="order-form-empty">ペアを選択してください</div>
  }

  const base  = pair.base
  const quote = pair.quote

  return (
    <div className="order-form-dual">
      <OrderSideForm side="BUY" pair={pair} base={base} quote={quote} hotStorage={hotStorage} onOrderPlaced={onOrderPlaced} externalPrice={externalPrice} />
      <OrderSideForm side="SELL" pair={pair} base={base} quote={quote} hotStorage={hotStorage} onOrderPlaced={onOrderPlaced} externalPrice={externalPrice} />
    </div>
  )
}
