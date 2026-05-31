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

type TradeSide = 'BUY' | 'SELL'

export default function TradePage() {
  const navigate = useNavigate()
  const isDebug = useDebugMode()
  const { authState, playerState, error, loginWithOtp, logout, refresh } = usePlayerAuth()
  const [isMobile, setIsMobile] = useState<boolean>(() => {
    try {
      return window.matchMedia('(max-width: 900px)').matches
    } catch {
      return false
    }
  })
  const [pairs, setPairs] = useState<PairSummary[]>([])
  const [selectedPairId, setSelectedPairId] = useState<string | null>(null)
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false)
  const [mobileTradeSheetOpen, setMobileTradeSheetOpen] = useState(false)
  const [mobileTradeSide, setMobileTradeSide] = useState<TradeSide>('BUY')
  const [mobileMarketTab, setMobileMarketTab] = useState<'chart' | 'orderbook'>(() => {
    try {
      return window.localStorage.getItem('gekiyabafx:trade:market-tab') === 'orderbook'
        ? 'orderbook'
        : 'chart'
    } catch {
      return 'chart'
    }
  })
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
  const [mobileOtp, setMobileOtp] = useState('')
  const [otpSubmitting, setOtpSubmitting] = useState(false)

  useEffect(() => {
    try {
      const mq = window.matchMedia('(max-width: 900px)')
      const onChange = (ev: MediaQueryListEvent) => {
        setIsMobile(ev.matches)
        if (!ev.matches) setMobileMenuOpen(false)
      }
      setIsMobile(mq.matches)
      mq.addEventListener('change', onChange)
      return () => mq.removeEventListener('change', onChange)
    } catch {
      return
    }
  }, [])

  useEffect(() => {
    if (!isMobile) setMobileMenuOpen(false)
  }, [isMobile])

  useEffect(() => {
    if (!isMobile) {
      setMobileTradeSheetOpen(false)
      return
    }
    if (!mobileTradeSheetOpen) return

    const prevOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => {
      document.body.style.overflow = prevOverflow
    }
  }, [isMobile, mobileTradeSheetOpen])

  useEffect(() => {
    try {
      window.localStorage.setItem('gekiyabafx:trade:market-tab', mobileMarketTab)
    } catch {
      // localStorage が無効な環境では永続化をスキップ
    }
  }, [mobileMarketTab])

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
  const openMobileTradeSheet = (side: TradeSide) => {
    setMobileTradeSide(side)
    setMobileTradeSheetOpen(true)
  }

  const closeMobileTradeSheet = () => {
    setMobileTradeSheetOpen(false)
  }

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
    if (isMobile) {
      return (
        <div className="auth-screen">
          <h1 className="auth-title">💥GekiyabaFX</h1>
          <p className="auth-message">マイクラで <strong>/fx login phone</strong> を実行して、表示された6桁OTPを入力してください（有効期限1分）。</p>

          <form
            className="mobile-otp-form"
            onSubmit={async (e) => {
              e.preventDefault()
              if (otpSubmitting) return
              setOtpSubmitting(true)
              try {
                await loginWithOtp(mobileOtp)
              } finally {
                setOtpSubmitting(false)
              }
            }}
          >
            <input
              className="mobile-otp-input"
              type="text"
              inputMode="numeric"
              autoComplete="one-time-code"
              maxLength={6}
              placeholder="6桁OTP"
              value={mobileOtp}
              onChange={(e) => setMobileOtp(e.target.value.replace(/\D/g, '').slice(0, 6))}
            />
            <button className="mobile-otp-submit" type="submit" disabled={otpSubmitting || mobileOtp.length !== 6}>
              {otpSubmitting ? '認証中...' : 'ログイン'}
            </button>
          </form>

          {error && <p className="auth-message">認証エラー: {error}</p>}
        </div>
      )
    }

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
  const showChart = !isMobile || mobileMarketTab === 'chart'
  const showOrderBook = !isMobile || mobileMarketTab === 'orderbook'

  return (
    <div className={`trade-page${isMobile ? ' mobile-has-trade-footer' : ''}`}>
      <header className="trade-header">
        <span className="trade-header-title">💥GekiyabaFX</span>
        <span className="trade-header-user">{playerState?.name ?? ''}</span>
        <div className="trade-header-actions">
          <button className="trade-header-logout" onClick={() => navigate('/transfer')}>
            振込
          </button>
          <button className="trade-header-logout" onClick={logout}>
            ログアウト
          </button>
        </div>
        {isMobile && (
          <button
            type="button"
            className={`trade-header-menu-btn${mobileMenuOpen ? ' open' : ''}`}
            aria-label="メニュー"
            aria-controls="trade-mobile-menu"
            aria-expanded={mobileMenuOpen}
            onClick={() => setMobileMenuOpen((v) => !v)}
          >
            <span />
            <span />
            <span />
          </button>
        )}
      </header>

      {isMobile && mobileMenuOpen && (
        <nav id="trade-mobile-menu" className="trade-mobile-menu" aria-label="トレードメニュー">
          <button
            type="button"
            className="trade-mobile-menu-item"
            onClick={() => {
              setMobileMenuOpen(false)
              navigate('/transfer')
            }}
          >
            振込ページへ
          </button>
          <button
            type="button"
            className="trade-mobile-menu-item"
            onClick={() => {
              setMobileMenuOpen(false)
              logout()
            }}
          >
            ログアウト
          </button>
        </nav>
      )}

      {/* ペア選択 */}
      <PairSelector
        pairs={pairs}
        selectedId={selectedPairId}
        onChange={setSelectedPairId}
      />

      {isMobile && (
        <section className="trade-section">
          <div className="trade-market-tabs" role="tablist" aria-label="マーケット表示タブ">
            <button
              type="button"
              role="tab"
              aria-selected={mobileMarketTab === 'chart'}
              className={`trade-market-tab${mobileMarketTab === 'chart' ? ' active' : ''}`}
              onClick={() => setMobileMarketTab('chart')}
            >
              チャート
            </button>
            <button
              type="button"
              role="tab"
              aria-selected={mobileMarketTab === 'orderbook'}
              className={`trade-market-tab${mobileMarketTab === 'orderbook' ? ' active' : ''}`}
              onClick={() => setMobileMarketTab('orderbook')}
            >
              板情報
            </button>
          </div>
        </section>
      )}

      {/* ローソク足チャート */}
      {showChart && (
        <section className="trade-section">
          <CandleChart
            pairId={selectedPairId}
            onSetPrice={(price, side) => setExternalPrice({ price: price.toFixed(4), side, key: Date.now() })}
          />
        </section>
      )}

      {/* メインエリア */}
      <main className="trade-main trade-layout">
        {/* 板 */}
        {showOrderBook && (
          <section className="trade-section trade-section-ob">
            <h2 className="section-title">板情報</h2>
            <OrderBookView orderBook={orderBook} pairId={selectedPairId} />
          </section>
        )}

        {/* 注文フォーム */}
        <section className="trade-section trade-section-form">
          {!isMobile && (
            <OrderForm
              pair={selectedPair}
              orderBook={orderBook}
              hotStorage={hotStorage}
              onOrderPlaced={handleOrderPlaced}
              externalPrice={externalPrice}
            />
          )}

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

      {isMobile && (
        <>
          <div
            className={`mobile-trade-sheet-backdrop${mobileTradeSheetOpen ? ' open' : ''}`}
            onClick={closeMobileTradeSheet}
            aria-hidden={!mobileTradeSheetOpen}
          />

          <section
            className={`mobile-trade-sheet${mobileTradeSheetOpen ? ' open' : ''}`}
            role="dialog"
            aria-modal="true"
            aria-label={mobileTradeSide === 'BUY' ? '購入注文パネル' : '売却注文パネル'}
          >
            <header className="mobile-trade-sheet-header">
              <h2 className={`mobile-trade-sheet-title ${mobileTradeSide === 'BUY' ? 'buy' : 'sell'}`}>
                {mobileTradeSide === 'BUY' ? '購入注文' : '売却注文'}
              </h2>
              <button
                type="button"
                className="mobile-trade-sheet-close"
                onClick={closeMobileTradeSheet}
                aria-label="注文パネルを閉じる"
              >
                ×
              </button>
            </header>

            <div className="mobile-trade-sheet-body">
              <OrderForm
                key={`mobile-${mobileTradeSide}`}
                pair={selectedPair}
                orderBook={orderBook}
                hotStorage={hotStorage}
                onOrderPlaced={handleOrderPlaced}
                externalPrice={externalPrice}
                forceSide={mobileTradeSide}
              />
            </div>
          </section>

          <div className="mobile-trade-footer" role="toolbar" aria-label="注文アクション">
            <button
              type="button"
              className="mobile-trade-footer-btn buy"
              onClick={() => openMobileTradeSheet('BUY')}
            >
              購入
            </button>
            <button
              type="button"
              className="mobile-trade-footer-btn sell"
              onClick={() => openMobileTradeSheet('SELL')}
            >
              売却
            </button>
          </div>
        </>
      )}
    </div>
  )
}
