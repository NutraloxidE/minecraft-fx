/**
 * オーダーブック（板）コンポーネント
 *
 * Asks（売り注文）を上部に降順、Bids（買い注文）を下部に降順で表示する。
 */

import { useEffect, useMemo, useState, type UIEventHandler } from 'react'

import type { OrderBookResponse } from '@/types/api'

interface Props {
  orderBook: OrderBookResponse | null
  pairId: string | null
}

interface GroupedLevel {
  price: string
  remaining: number
}

const ORDERBOOK_PAGE_SIZE = 20

function aggregateByPrice(entries: OrderBookResponse['asks']): GroupedLevel[] {
  const map = new Map<string, number>()
  for (const o of entries) {
    const price = o.price ?? '0'
    const remaining = Math.max(0, parseFloat(o.amount) - parseFloat(o.filled))
    map.set(price, (map.get(price) ?? 0) + remaining)
  }
  return [...map.entries()].map(([price, remaining]) => ({ price, remaining }))
}

function withCumulative(levels: GroupedLevel[]) {
  return levels.reduce<{ price: string; remaining: number; cumul: number }[]>((acc, l) => {
    const prev = acc.length > 0 ? acc[acc.length - 1].cumul : 0
    acc.push({ price: l.price, remaining: l.remaining, cumul: prev + l.remaining })
    return acc
  }, [])
}

export default function OrderBookView({ orderBook, pairId }: Props) {
  const [askVisibleDepth, setAskVisibleDepth] = useState(ORDERBOOK_PAGE_SIZE)
  const [bidVisibleDepth, setBidVisibleDepth] = useState(ORDERBOOK_PAGE_SIZE)
  const asksEntries = orderBook?.asks ?? []
  const bidsEntries = orderBook?.bids ?? []

  useEffect(() => {
    setAskVisibleDepth(ORDERBOOK_PAGE_SIZE)
    setBidVisibleDepth(ORDERBOOK_PAGE_SIZE)
  }, [pairId])

  const asksAll = useMemo(() => {
    const grouped = aggregateByPrice(asksEntries)
    return grouped.sort((a, b) => parseFloat(b.price) - parseFloat(a.price))
  }, [asksEntries])

  const bidsAll = useMemo(() => {
    const grouped = aggregateByPrice(bidsEntries)
    return grouped.sort((a, b) => parseFloat(b.price) - parseFloat(a.price))
  }, [bidsEntries])

  const asks = asksAll.slice(0, askVisibleDepth)
  const bids = bidsAll.slice(0, bidVisibleDepth)

  // 累計（上から積み上げ）を計算
  const asksWithCumul = withCumulative(asks)
  const bidsWithCumul = withCumulative(bids)

  // スプレッド計算
  const bestAsk = asksAll.length > 0 ? parseFloat(asksAll[asksAll.length - 1].price) : null
  const bestBid = bidsAll.length > 0 ? parseFloat(bidsAll[0].price) : null
  const spread = bestAsk !== null && bestBid !== null ? bestAsk - bestBid : null
  const spreadPct = spread !== null && bestAsk !== null && bestAsk > 0
    ? (spread / bestAsk * 100).toFixed(2)
    : null

  const handleAskScroll: UIEventHandler<HTMLDivElement> = (e) => {
    if (asksAll.length <= askVisibleDepth) return
    const el = e.currentTarget
    const nearBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 24
    if (nearBottom) {
      setAskVisibleDepth((v) => Math.min(v + ORDERBOOK_PAGE_SIZE, asksAll.length))
    }
  }

  const handleBidScroll: UIEventHandler<HTMLDivElement> = (e) => {
    if (bidsAll.length <= bidVisibleDepth) return
    const el = e.currentTarget
    const nearBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 24
    if (nearBottom) {
      setBidVisibleDepth((v) => Math.min(v + ORDERBOOK_PAGE_SIZE, bidsAll.length))
    }
  }

  if (!pairId) {
    return <div className="orderbook orderbook-empty">ペアを選択してください</div>
  }
  if (!orderBook) {
    return <div className="orderbook orderbook-empty">読み込み中...</div>
  }

  const [, quote] = pairId.split('/')

  return (
    <div className="orderbook">
      <div className="orderbook-header">
        <span>価格 ({quote})</span>
        <span>数量</span>
        <span>累計</span>
      </div>

      {/* Asks（売り） */}
      <div className="orderbook-asks orderbook-side-scroll" onScroll={handleAskScroll}>
        {asksWithCumul.map((o) => (
          <div key={o.price} className="orderbook-row ask">
            <span className="ob-price">{o.price}</span>
            <span className="ob-amount">{o.remaining.toFixed(4)}</span>
            <span className="ob-filled">{o.cumul.toFixed(4)}</span>
          </div>
        ))}
        {asksAll.length === 0 && (
          <div className="orderbook-row-empty">売り注文なし</div>
        )}
        {asksAll.length > asks.length && (
          <div className="orderbook-row-more">スクロールでさらに表示</div>
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
      <div className="orderbook-bids orderbook-side-scroll" onScroll={handleBidScroll}>
        {bidsWithCumul.map((o) => (
          <div key={o.price} className="orderbook-row bid">
            <span className="ob-price">{o.price}</span>
            <span className="ob-amount">{o.remaining.toFixed(4)}</span>
            <span className="ob-filled">{o.cumul.toFixed(4)}</span>
          </div>
        ))}
        {bidsAll.length === 0 && (
          <div className="orderbook-row-empty">買い注文なし</div>
        )}
        {bidsAll.length > bids.length && (
          <div className="orderbook-row-more">スクロールでさらに表示</div>
        )}
      </div>
    </div>
  )
}
