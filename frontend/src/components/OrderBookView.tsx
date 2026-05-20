/**
 * オーダーブック（板）コンポーネント
 *
 * Asks（売り注文）を上部に降順、Bids（買い注文）を下部に降順で表示する。
 */

import type { OrderBookResponse } from '@/types/api'

interface Props {
  orderBook: OrderBookResponse | null
  pairId: string | null
}

export default function OrderBookView({ orderBook, pairId }: Props) {
  if (!pairId) {
    return <div className="orderbook orderbook-empty">ペアを選択してください</div>
  }
  if (!orderBook) {
    return <div className="orderbook orderbook-empty">読み込み中...</div>
  }

  // asks を価格降順（表示上: 高い値が上）に並べる
  const asks = [...orderBook.asks].sort((a, b) =>
    parseFloat(b.price ?? '0') - parseFloat(a.price ?? '0'),
  )
  // bids を価格降順（高い値が上）に並べる
  const bids = [...orderBook.bids].sort((a, b) =>
    parseFloat(b.price ?? '0') - parseFloat(a.price ?? '0'),
  )

  // 累計（上から積み上げ）を計算
  const asksWithCumul = asks.slice(0, 20).reduce<{ order_id: string; price: string; remaining: number; cumul: number }[]>((acc, o) => {
    const remaining = parseFloat(o.amount) - parseFloat(o.filled)
    const prev = acc.length > 0 ? acc[acc.length - 1].cumul : 0
    acc.push({ order_id: o.order_id, price: o.price ?? '0', remaining, cumul: prev + remaining })
    return acc
  }, [])

  const bidsWithCumul = bids.slice(0, 20).reduce<{ order_id: string; price: string; remaining: number; cumul: number }[]>((acc, o) => {
    const remaining = parseFloat(o.amount) - parseFloat(o.filled)
    const prev = acc.length > 0 ? acc[acc.length - 1].cumul : 0
    acc.push({ order_id: o.order_id, price: o.price ?? '0', remaining, cumul: prev + remaining })
    return acc
  }, [])

  // スプレッド計算
  const bestAsk = asks.length > 0 ? parseFloat(asks[asks.length - 1].price ?? '0') : null
  const bestBid = bids.length > 0 ? parseFloat(bids[0].price ?? '0') : null
  const spread = bestAsk !== null && bestBid !== null ? bestAsk - bestBid : null
  const spreadPct = spread !== null && bestAsk !== null && bestAsk > 0
    ? (spread / bestAsk * 100).toFixed(2)
    : null

  const [, quote] = pairId.split('/')

  return (
    <div className="orderbook">
      <div className="orderbook-header">
        <span>価格 ({quote})</span>
        <span>数量</span>
        <span>累計</span>
      </div>

      {/* Asks（売り） */}
      <div className="orderbook-asks">
        {asksWithCumul.map((o) => (
          <div key={o.order_id} className="orderbook-row ask">
            <span className="ob-price">{o.price}</span>
            <span className="ob-amount">{o.remaining.toFixed(4)}</span>
            <span className="ob-filled">{o.cumul.toFixed(4)}</span>
          </div>
        ))}
        {asks.length === 0 && (
          <div className="orderbook-row-empty">売り注文なし</div>
        )}
      </div>

      {/* スプレッド */}
      <div className="orderbook-spread">
        {spread !== null
          ? <><span className="ob-spread-value">{spread.toFixed(4)}</span><span className="ob-spread-pct">({spreadPct}%)</span></>
          : '── スプレッド ──'
        }
      </div>

      {/* Bids（買い） */}
      <div className="orderbook-bids">
        {bidsWithCumul.map((o) => (
          <div key={o.order_id} className="orderbook-row bid">
            <span className="ob-price">{o.price}</span>
            <span className="ob-amount">{o.remaining.toFixed(4)}</span>
            <span className="ob-filled">{o.cumul.toFixed(4)}</span>
          </div>
        ))}
        {bids.length === 0 && (
          <div className="orderbook-row-empty">買い注文なし</div>
        )}
      </div>
    </div>
  )
}
