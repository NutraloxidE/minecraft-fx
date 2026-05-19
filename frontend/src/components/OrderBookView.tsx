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
        {asks.slice(0, 20).map((o) => {
          const price = parseFloat(o.price ?? '0')
          const remaining = parseFloat(o.amount) - parseFloat(o.filled)
          return (
            <div key={o.order_id} className="orderbook-row ask">
              <span className="ob-price">{o.price}</span>
              <span className="ob-amount">{remaining.toFixed(4)}</span>
              <span className="ob-filled">{o.filled}</span>
            </div>
          )
          void price
        })}
        {asks.length === 0 && (
          <div className="orderbook-row-empty">売り注文なし</div>
        )}
      </div>

      {/* スプレッド */}
      <div className="orderbook-spread">
        ── スプレッド ──
      </div>

      {/* Bids（買い） */}
      <div className="orderbook-bids">
        {bids.slice(0, 20).map((o) => {
          const remaining = parseFloat(o.amount) - parseFloat(o.filled)
          return (
            <div key={o.order_id} className="orderbook-row bid">
              <span className="ob-price">{o.price}</span>
              <span className="ob-amount">{remaining.toFixed(4)}</span>
              <span className="ob-filled">{o.filled}</span>
            </div>
          )
        })}
        {bids.length === 0 && (
          <div className="orderbook-row-empty">買い注文なし</div>
        )}
      </div>
    </div>
  )
}
