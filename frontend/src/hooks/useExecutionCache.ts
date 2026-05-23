/**
 * 差分ポーリングで約定履歴を蓄積するフック
 *
 * - 初回アクセス時のみ全履歴を取得して localStorage / メモリキャッシュへ格納する
 * - 以降のポーリング: 最後の timestamp を `since` に渡して差分のみ取得し、キャッシュに追記する
 * - タブが表示中のときだけ 3 秒ごとにポーリングする
 * - pairId ごとにキャッシュを持ち、明示的に削除してフル再取得できる
 */

import { useEffect, useRef, useState, useCallback } from 'react'
import { fetchExecutions } from '@/lib/api'
import type { Execution } from '@/types/api'
import { useDebugMode } from '@/hooks/useDebugMode'
import { DEBUG_EXECUTIONS } from '@/lib/debugData'

interface UseExecutionCacheResult {
  executions: Execution[]
  loading: boolean
  resetCacheAndReload: () => Promise<void>
}

const POLL_INTERVAL_MS = 3000

function getExecutionCacheKey(pairId: string) {
  return `gekiyabafx:executions:${pairId}`
}

function readExecutionCache(pairId: string): Execution[] {
  try {
    const raw = window.localStorage.getItem(getExecutionCacheKey(pairId))
    if (!raw) return []
    const parsed = JSON.parse(raw) as Execution[]
    return Array.isArray(parsed) ? parsed : []
  } catch {
    return []
  }
}

function writeExecutionCache(pairId: string, executions: Execution[]) {
  try {
    window.localStorage.setItem(getExecutionCacheKey(pairId), JSON.stringify(executions))
  } catch {
    // localStorage 容量超過などは静かに無視
  }
}

function clearExecutionCache(pairId: string) {
  try {
    window.localStorage.removeItem(getExecutionCacheKey(pairId))
  } catch {
    // noop
  }
}

export function useExecutionCache(
  pairId: string | null,
  intervalMs = POLL_INTERVAL_MS,
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

  const persistCache = useCallback((next: Execution[]) => {
    cacheRef.current = next
    setExecutions(next)
    if (pairId) {
      writeExecutionCache(pairId, next)
    }
  }, [pairId])

  const doFetch = useCallback(
    async (since?: number) => {
      if (!pairId) return
      try {
        const fresh = await fetchExecutions(pairId, since)
        if (since === undefined) {
          persistCache(fresh)
          return
        }
        if (fresh.length > 0) {
          persistCache([...cacheRef.current, ...fresh])
        }
      } catch {
        // 静かに無視
      } finally {
        setLoading(false)
      }
    },
    [pairId, persistCache],
  )

  const startPolling = useCallback(() => {
    if (timerRef.current) {
      clearInterval(timerRef.current)
    }
    timerRef.current = setInterval(() => {
      if (document.visibilityState !== 'visible') {
        return
      }
      const last = cacheRef.current[cacheRef.current.length - 1]
      void doFetch(last ? last.timestamp : undefined)
    }, intervalMs)
  }, [intervalMs, doFetch])

  const resetCacheAndReload = useCallback(async () => {
    if (!pairId) return
    clearExecutionCache(pairId)
    persistCache([])
    setLoading(true)
    await doFetch()
  }, [pairId, persistCache, doFetch])

  useEffect(() => {
    if (isDebug || !pairId) return

    const cached = readExecutionCache(pairId)
    persistCache(cached)

    if (cached.length === 0 && document.visibilityState === 'visible') {
      setLoading(true)
      void doFetch()
    }

    const handleVisibilityChange = () => {
      if (document.visibilityState !== 'visible') {
        return
      }
      const last = cacheRef.current[cacheRef.current.length - 1]
      if (cacheRef.current.length === 0) {
        setLoading(true)
      }
      void doFetch(last ? last.timestamp : undefined)
    }

    document.addEventListener('visibilitychange', handleVisibilityChange)
    startPolling()

    return () => {
      if (timerRef.current) clearInterval(timerRef.current)
      document.removeEventListener('visibilitychange', handleVisibilityChange)
    }
  }, [isDebug, pairId, intervalMs, doFetch, persistCache, startPolling])

  return { executions, loading, resetCacheAndReload }
}
