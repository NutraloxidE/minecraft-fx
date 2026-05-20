/**
 * ローソク足チャートコンポーネント
 *
 * lightweight-charts v5 を使ってローソク足を描画する。
 * - 時間足セレクターで切り替え可能
 * - executions の差分ポーリングによりリアルタイム更新
 * - ダークモード対応（CSS カスタムプロパティ連動）
 */

import { useEffect, useRef, useState } from 'react'
import { createChart, ColorType, CandlestickSeries } from 'lightweight-charts'
import type { IChartApi, ISeriesApi, CandlestickData, Time } from 'lightweight-charts'
import { aggregateCandles, TIMEFRAMES } from '@/lib/candles'
import { useExecutionCache } from '@/hooks/useExecutionCache'

interface ContextMenuItem {
  label: string
  onClick: () => void
  disabled?: boolean
}

interface Props {
  pairId: string | null
  contextMenuItems?: ContextMenuItem[]
}

export default function CandleChart({ pairId, contextMenuItems = [] }: Props) {
  const containerRef = useRef<HTMLDivElement>(null)
  const chartRef     = useRef<IChartApi | null>(null)
  const seriesRef    = useRef<ISeriesApi<'Candlestick'> | null>(null)
  const [tfSec, setTfSec] = useState(60)
  const [hoveredCandle, setHoveredCandle] = useState<{
    open: number; high: number; low: number; close: number
  } | null>(null)
  const candleDataRef = useRef<CandlestickData<Time>[]>([])
  const [contextMenu, setContextMenu] = useState<{ x: number; y: number } | null>(null)

  const { executions, loading } = useExecutionCache(pairId)

  // チャートの初期化（マウント時のみ）
  useEffect(() => {
    if (!containerRef.current) return

    const isDark = window.matchMedia('(prefers-color-scheme: dark)').matches

    const chart = createChart(containerRef.current, {
      layout: {
        background: { type: ColorType.Solid, color: isDark ? '#1a1a1a' : '#ffffff' },
        textColor: isDark ? '#9a9a9a' : '#6b6375',
      },
      grid: {
        vertLines: { color: isDark ? '#2a2a2a' : '#e5e4e7' },
        horzLines: { color: isDark ? '#2a2a2a' : '#e5e4e7' },
      },
      width:  containerRef.current.clientWidth,
      height: 320,
      timeScale: { timeVisible: true, secondsVisible: false },
    })

    // v5: addSeries(SeriesDefinition, options)
    const series = chart.addSeries(CandlestickSeries, {
      upColor:   '#4caf82',
      downColor: '#e05c5c',
      borderUpColor:   '#4caf82',
      borderDownColor: '#e05c5c',
      wickUpColor:   '#4caf82',
      wickDownColor: '#e05c5c',
    })

    chartRef.current  = chart
    seriesRef.current = series

    // クロスヘア移動時にローソク情報を更新
    chart.subscribeCrosshairMove((param) => {
      if (!param.time || !seriesRef.current) {
        setHoveredCandle(null)
        return
      }
      const data = candleDataRef.current
      const candle = data.find((d) => d.time === param.time)
      if (candle) {
        setHoveredCandle({ open: candle.open, high: candle.high, low: candle.low, close: candle.close })
      } else {
        setHoveredCandle(null)
      }
    })

    // リサイズ対応
    const observer = new ResizeObserver(() => {
      if (containerRef.current) {
        chart.applyOptions({ width: containerRef.current.clientWidth })
      }
    })
    observer.observe(containerRef.current)

    return () => {
      observer.disconnect()
      chart.remove()
      chartRef.current  = null
      seriesRef.current = null
    }
  }, [])

  // executions または時間足が変わったらローソク足を再集計してセットする
  useEffect(() => {
    if (!seriesRef.current) return
    const candles = aggregateCandles(executions, tfSec)
    const data: CandlestickData<Time>[] = candles.map((c) => ({
      time:  c.time as Time,
      open:  c.open,
      high:  c.high,
      low:   c.low,
      close: c.close,
    }))
    seriesRef.current.setData(data)
    candleDataRef.current = data
    if (data.length > 0) {
      chartRef.current?.timeScale().fitContent()
    }
  }, [executions, tfSec])

  const changeRate = hoveredCandle
    ? ((hoveredCandle.close - hoveredCandle.open) / hoveredCandle.open) * 100
    : null
  const spread = hoveredCandle ? hoveredCandle.high - hoveredCandle.low : null
  const isUp = hoveredCandle ? hoveredCandle.close >= hoveredCandle.open : true

  return (
    <div
      className="candle-chart-wrap"
      onContextMenu={(e) => { e.preventDefault(); setContextMenu({ x: e.clientX, y: e.clientY }) }}
      onClick={() => setContextMenu(null)}
    >
      {/* コンテキストメニュー */}
      {contextMenu && (
        <ul
          className="chart-context-menu"
          style={{ top: contextMenu.y, left: contextMenu.x }}
          onContextMenu={(e) => e.preventDefault()}
        >
          {contextMenuItems.length === 0 ? (
            <li className="chart-context-item chart-context-empty">(メニューなし)</li>
          ) : (
            contextMenuItems.map((item, i) => (
              <li key={i} className={`chart-context-item${item.disabled ? ' disabled' : ''}`}>
                <button
                  type="button"
                  disabled={item.disabled}
                  onClick={() => { item.onClick(); setContextMenu(null) }}
                >
                  {item.label}
                </button>
              </li>
            ))
          )}
        </ul>
      )}
      {/* ホバー中ローソク情報 */}
      <div className="candle-ohlc-bar">
        {hoveredCandle ? (
          <>
            <span className="ohlc-item"><span className="ohlc-label">始値</span><span className="ohlc-value">{hoveredCandle.open.toFixed(2)}</span></span>
            <span className="ohlc-item"><span className="ohlc-label">高値</span><span className="ohlc-value ohlc-high">{hoveredCandle.high.toFixed(2)}</span></span>
            <span className="ohlc-item"><span className="ohlc-label">安値</span><span className="ohlc-value ohlc-low">{hoveredCandle.low.toFixed(2)}</span></span>
            <span className="ohlc-item"><span className="ohlc-label">終値</span><span className="ohlc-value">{hoveredCandle.close.toFixed(2)}</span></span>
            <span className="ohlc-item"><span className="ohlc-label">変動率</span><span className={`ohlc-value ${isUp ? 'ohlc-up' : 'ohlc-down'}`}>{changeRate! >= 0 ? '+' : ''}{changeRate!.toFixed(2)}%</span></span>
            <span className="ohlc-item"><span className="ohlc-label">幅</span><span className="ohlc-value">{spread!.toFixed(2)}</span></span>
          </>
        ) : (
          <span className="ohlc-placeholder">ローソクにマウスを合わせると詳細が表示されます</span>
        )}
      </div>

      {/* 時間足セレクター */}
      <div className="timeframe-selector">
        {TIMEFRAMES.map((tf) => (
          <button
            key={tf.sec}
            className={`tf-btn${tfSec === tf.sec ? ' active' : ''}`}
            onClick={() => setTfSec(tf.sec)}
          >
            {tf.label}
          </button>
        ))}
      </div>

      {/* チャート本体 */}
      <div
        ref={containerRef}
        className="candle-chart-container"
        style={{ position: 'relative', width: '100%', height: 320 }}
      >
        {loading && executions.length === 0 && (
          <div className="chart-loading">読み込み中...</div>
        )}
        {!loading && executions.length === 0 && pairId && (
          <div className="chart-loading">約定データがありません</div>
        )}
        {!pairId && (
          <div className="chart-loading">ペアを選択してください</div>
        )}
      </div>
    </div>
  )
}
