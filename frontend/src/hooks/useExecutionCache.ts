/**
 * 差分ポーリングで約定履歴を蓄積するフック
 *
 * - 初回フェッチ: 全履歴を取得してキャッシュに格納する
 * - 以降のポーリング: 最後の timestamp を `since` に渡して差分のみ取得し、キャッシュに追記する
 * - pairId が変わるとキャッシュをリセットして初回フェッチをやり直す
 */

import { useEffect, useRef, useState, useCallback } from 'react'
import { fetchExecutions } from '@/lib/api'
import type { Execution } from '@/types/api'
import { useDebugMode } from '@/hooks/useDebugMode'
import { DEBUG_EXECUTIONS } from '@/lib/debugData'

interface UseExecutionCacheResult {
  executions: Execution[]
  loading: boolean
}

export function useExecutionCache(
  pairId: string | null,
  intervalMs = 3000,
): UseExecutionCacheResult {
  const isDebug = useDebugMode()
  const [executions, setExecutions] = useState<Execution[]>([])
  const [loading, setLoading] = useState(false)
  const cacheRef = useRef<Execution[]>([])
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  // デバッグモード: フェイク約定を即座にセットしてポーリングしない
  useEffect(() => {
    if (!isDebug) return
    setExecutions(DEBUG_EXECUTIONS)
    setLoading(false)
  }, [isDebug])

  const doFetch = useCallback(
    async (since?: number) => {
      if (!pairId) return
      try {
        const fresh = await fetchExecutions(pairId, since)
        if (fresh.length > 0) {
          cacheRef.current = [...cacheRef.current, ...fresh]
          setExecutions([...cacheRef.current])
        }
      } catch {
        // 静かに無視
      } finally {
        setLoading(false)
      }
    },
    [pairId],
  )

  useEffect(() => {
    if (isDebug || !pairId) return

    // リセット
    cacheRef.current = []
    setExecutions([])
    setLoading(true)

    // 初回フェッチ（全件）
    doFetch()

    // 差分ポーリング
    timerRef.current = setInterval(() => {
      const last = cacheRef.current[cacheRef.current.length - 1]
      doFetch(last ? last.timestamp : undefined)
    }, intervalMs)

    return () => {
      if (timerRef.current) clearInterval(timerRef.current)
    }
  }, [isDebug, pairId, intervalMs, doFetch])

  return { executions, loading }
}
