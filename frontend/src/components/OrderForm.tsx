/**
 * 注文パネルコンポーネント
 *
 * LIMIT / MARKET × BUY / SELL の組み合わせに応じてフォームを動的に切り替える。
 * - LIMIT BUY / SELL : 価格・数量を入力
 * - MARKET BUY       : max_spend（最大支払い quote 量）を入力
 * - MARKET SELL      : 数量を入力
 */

import { useState } from 'react'
import { placeOrder } from '@/lib/api'
import { ApiException } from '@/lib/api'
import type { PairSummary, PlaceOrderResponse } from '@/types/api'

interface Props {
  pair: PairSummary | null
  onOrderPlaced: (res: PlaceOrderResponse) => void
}

type Side = 'BUY' | 'SELL'
type OrderType = 'LIMIT' | 'MARKET'

export default function OrderForm({ pair, onOrderPlaced }: Props) {
  const [side, setSide] = useState<Side>('BUY')
  const [type, setType] = useState<OrderType>('LIMIT')
  const [price, setPrice] = useState('')
  const [amount, setAmount] = useState('')
  const [maxSpend, setMaxSpend] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  if (!pair) {
    return <div className="order-form order-form-empty">ペアを選択してください</div>
  }

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
    } catch (e) {
      if (e instanceof ApiException) setError(e.code)
      else setError('unknown_error')
    } finally {
      setSubmitting(false)
    }
  }

  const [base, quote] = pair.id.split('/')

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
    <form className="order-form" onSubmit={handleSubmit}>
      {/* Side タブ */}
      <div className="order-side-tabs">
        <button
          type="button"
          className={`side-tab buy${side === 'BUY' ? ' active' : ''}`}
          onClick={() => setSide('BUY')}
        >
          買い (BUY)
        </button>
        <button
          type="button"
          className={`side-tab sell${side === 'SELL' ? ' active' : ''}`}
          onClick={() => setSide('SELL')}
        >
          売り (SELL)
        </button>
      </div>

      {/* 注文種別 */}
      <div className="order-type-tabs">
        {(['LIMIT', 'MARKET'] as OrderType[]).map((t) => (
          <button
            key={t}
            type="button"
            className={`type-tab${type === t ? ' active' : ''}`}
            onClick={() => setType(t)}
          >
            {t === 'LIMIT' ? '指値' : '成行'}
          </button>
        ))}
      </div>

      {/* 価格（指値のみ） */}
      {type === 'LIMIT' && (
        <label className="order-field">
          <span>価格 ({quote})</span>
          <input
            type="text"
            inputMode="decimal"
            value={price}
            onChange={(e) => setPrice(e.target.value)}
            placeholder={pair.min_price}
            required
          />
        </label>
      )}

      {/* 数量（LIMIT または MARKET SELL） */}
      {(type === 'LIMIT' || (type === 'MARKET' && side === 'SELL')) && (
        <label className="order-field">
          <span>数量 ({base})</span>
          <input
            type="text"
            inputMode="decimal"
            value={amount}
            onChange={(e) => setAmount(e.target.value)}
            placeholder={pair.min_amount}
            required
          />
        </label>
      )}

      {/* 最大支払い（MARKET BUY） */}
      {type === 'MARKET' && side === 'BUY' && (
        <label className="order-field">
          <span>最大支払い ({quote})</span>
          <input
            type="text"
            inputMode="decimal"
            value={maxSpend}
            onChange={(e) => setMaxSpend(e.target.value)}
            placeholder="0.0000"
            required
          />
        </label>
      )}

      {error && <p className="order-error">{error}</p>}

      <button
        type="submit"
        className={`order-submit${side === 'BUY' ? ' buy' : ' sell'}`}
        disabled={submitting}
      >
        <span className="order-submit-label">
          {submitting ? '発注中...' : side === 'BUY' ? '買い注文' : '売り注文'}
        </span>
        {!submitting && lockInfo && (
          <span className="order-submit-lock">
            🔒 {lockInfo.item} {lockInfo.amount}
          </span>
        )}
      </button>
    </form>
  )
}
