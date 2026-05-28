/**
 * トレードページ（/trade）
 *
 * 認証の3ステートに応じて表示を切り替える:
 * - `loading`         — スピナー表示
 * - `unauthenticated` — 「OTP リンクからアクセスしてください」メッセージ
 * - `authenticated`   — トレード UI
 */

import { useState, useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import { fetchPairs } from '@/lib/api'
import { usePlayerAuth } from '@/hooks/usePlayerAuth'
import { useDebugMode } from '@/hooks/useDebugMode'
import { DEBUG_PAIRS } from '@/lib/debugData'
import { useMarketData } from '@/hooks/useMarketData'
import PairSelector from '@/components/PairSelector'
import OrderBookView from '@/components/OrderBookView'
import OrderForm from '@/components/OrderForm'
import OpenOrders from '@/components/OpenOrders'
import ExecutionHistory from '@/components/ExecutionHistory'
import CandleChart from '@/components/CandleChart'
import DepositPanel from '@/components/DepositPanel'
import type { PairSummary, PlaceOrderResponse } from '@/types/api'

export default function TradePage() {
  const navigate = useNavigate()
  const isDebug = useDebugMode()
  const { authState, playerState, error, logout, refresh } = usePlayerAuth()
  const [pairs, setPairs] = useState<PairSummary[]>([])
  const [selectedPairId, setSelectedPairId] = useState<string | null>(null)
  const [historyTab, setHistoryTab] = useState<'open_orders' | 'executions'>(() => {
    try {
      return window.localStorage.getItem('gekiyabafx:trade:history-tab') === 'executions'
        ? 'executions'
        : 'open_orders'
    } catch {
      return 'open_orders'
    }
  })
  const { orderBook } = useMarketData(selectedPairId)
  const [externalPrice, setExternalPrice] = useState<{ price: string; side: 'BUY' | 'SELL'; key: number } | null>(null)

  useEffect(() => {
    try {
      window.localStorage.setItem('gekiyabafx:trade:history-tab', historyTab)
    } catch {
      // localStorage が無効な環境では永続化をスキップ
    }
  }, [historyTab])

  // ペア一覧を取得する
  useEffect(() => {
    if (authState !== 'authenticated') return
    if (isDebug) {
      setPairs(DEBUG_PAIRS)
      if (!selectedPairId) setSelectedPairId(DEBUG_PAIRS[0].id)
      return
    }
    fetchPairs()
      .then((list) => {
        setPairs(list)
        if (!selectedPairId && list.find((p) => p.enabled)) {
          setSelectedPairId(list.find((p) => p.enabled)!.id)
        }
      })
      .catch(() => {})
  }, [authState, isDebug, selectedPairId])

  const selectedPair = pairs.find((p) => p.id === selectedPairId) ?? null

  const handleOrderPlaced = (_res: PlaceOrderResponse) => {
    refresh()
  }

  const handleCancelled = (_orderId: string) => {
    refresh()
  }

  // ── 未認証 / ローディング ────────────────────────────────────────────────
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
        <h1 className="auth-title">💥GekiyabaFX</h1>
        <p className="auth-message">
          {error
            ? `認証エラー: ${error}`
            : 'ゲーム内で /fx trade コマンドを実行し、表示された URL からアクセスしてください。'}
        </p>
      </div>
    )
  }

  // ── 認証済み ─────────────────────────────────────────────────────────────
  const openOrders = playerState?.open_orders ?? []
  const hotStorage = playerState?.hot_storage ?? {}

  return (
    <div className="trade-page">
      <header className="trade-header">
        <span className="trade-header-title">💥GekiyabaFX</span>
        <span className="trade-header-user">{playerState?.name ?? ''}</span>
        <button className="trade-header-logout" onClick={() => navigate('/transfer')}>
          振込
        </button>
        <button className="trade-header-logout" onClick={logout}>
          ログアウト
        </button>
      </header>

      {/* ペア選択 */}
      <PairSelector
        pairs={pairs}
        selectedId={selectedPairId}
        onChange={setSelectedPairId}
      />

      {/* ローソク足チャート */}
      <section className="trade-section">
        <CandleChart
          pairId={selectedPairId}
          onSetPrice={(price, side) => setExternalPrice({ price: price.toFixed(4), side, key: Date.now() })}
        />
      </section>

      {/* メインエリア */}
      <main className="trade-main trade-layout">
        {/* 板 */}
        <section className="trade-section trade-section-ob">
          <h2 className="section-title">板情報</h2>
          <OrderBookView orderBook={orderBook} pairId={selectedPairId} />
        </section>

        {/* 注文フォーム */}
        <section className="trade-section trade-section-form">
          <OrderForm
            pair={selectedPair}
            orderBook={orderBook}
            hotStorage={hotStorage}
            onOrderPlaced={handleOrderPlaced}
            externalPrice={externalPrice}
          />

          {/* 残高 */}
          <div className="balance-box">
            <h3 className="balance-title">ホット残高</h3>
            {Object.keys(hotStorage).length === 0 ? (
              <p className="balance-empty">残高なし</p>
            ) : (
              <ul className="balance-list">
                {Object.entries(hotStorage).map(([item, amount]) => (
                  <li key={item}>
                    <span className="balance-item">{item}</span>
                    <span className="balance-amount">{amount}</span>
                  </li>
                ))}
              </ul>
            )}
          </div>

          {/* 入出金 */}
          <DepositPanel playerState={playerState} pair={selectedPair} onDone={refresh} />
        </section>
      </main>

      {/* 保有注文 / 約定履歴 */}
      <section className="trade-section">
        <div className="trade-history-tabs" role="tablist" aria-label="履歴タブ">
          <button
            type="button"
            role="tab"
            aria-selected={historyTab === 'open_orders'}
            className={`trade-history-tab${historyTab === 'open_orders' ? ' active' : ''}`}
            onClick={() => setHistoryTab('open_orders')}
          >
            保有注文
          </button>
          <button
            type="button"
            role="tab"
            aria-selected={historyTab === 'executions'}
            className={`trade-history-tab${historyTab === 'executions' ? ' active' : ''}`}
            onClick={() => setHistoryTab('executions')}
          >
            約定履歴
          </button>
        </div>

        {historyTab === 'open_orders' ? (
          <OpenOrders orders={openOrders} onCancelled={handleCancelled} />
        ) : (
          <ExecutionHistory pairId={selectedPairId} playerUuid={playerState?.uuid ?? null} />
        )}
      </section>
    </div>
  )
}
