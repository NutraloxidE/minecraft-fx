/**
 * 板データのポーリングフック
 *
 * - `intervalMs` ごとに板を再取得する（デフォルト 2000ms）
 * - pairId が変わったら即時再取得する
 */

import { useEffect, useRef, useState, useCallback } from 'react'
import { fetchOrderBook } from '@/lib/api'
import type { OrderBookResponse } from '@/types/api'
import { useDebugMode } from '@/hooks/useDebugMode'
import { makeDebugOrderBook } from '@/lib/debugData'

interface UseMarketDataResult {
  orderBook: OrderBookResponse | null
  loading: boolean
}

export function useMarketData(
  pairId: string | null,
  intervalMs = 2000,
): UseMarketDataResult {
  const isDebug = useDebugMode()
  const [orderBook, setOrderBook] = useState<OrderBookResponse | null>(null)
  const [loading, setLoading] = useState(false)
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  // デバッグモード: フェイクデータを即座にセットしてポーリングしない
  useEffect(() => {
    if (!isDebug || !pairId) return
    setOrderBook(makeDebugOrderBook(pairId))
    setLoading(false)
  }, [isDebug, pairId])

  const fetch_ = useCallback(async () => {
    if (!pairId) return
    try {
      const ob = await fetchOrderBook(pairId)
      setOrderBook(ob)
    } catch {
      // サーバー未起動時など静かに無視する
    } finally {
      setLoading(false)
    }
  }, [pairId])

  useEffect(() => {
    if (isDebug || !pairId) return
    setLoading(true)
    fetch_()
    timerRef.current = setInterval(fetch_, intervalMs)
    return () => {
      if (timerRef.current) clearInterval(timerRef.current)
    }
  }, [isDebug, pairId, intervalMs, fetch_])

  return { orderBook, loading }
}
