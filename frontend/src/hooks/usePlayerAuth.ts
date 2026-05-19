/**
 * プレイヤー認証フック
 *
 * ## 3ステート
 * - `loading`       — OTP 検証中（ページ初回マウント時）
 * - `authenticated` — 有効なトークンが localStorage に存在する
 * - `unauthenticated` — トークンなし、または OTP 検証失敗
 *
 * ## 認証フロー
 * 1. URL に `?otp=XXXXXX` が含まれる場合、POST /api/auth でトークンを取得する。
 * 2. 取得成功 → トークンを localStorage に保存、URL から otp パラメーターを除去する。
 * 3. 取得失敗 → `unauthenticated` に遷移し、`error` にエラーコードを格納する。
 * 4. URL に otp がない場合は localStorage のトークンを検証（GET /api/state の成否で判定）する。
 */

import { useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { loginPlayer, fetchPlayerState } from '@/lib/api'
import { getPlayerToken, setPlayerToken, clearPlayerToken } from '@/lib/auth'
import type { PlayerStateResponse } from '@/types/api'
import { ApiException } from '@/lib/api'
import { DEBUG_PLAYER_STATE } from '@/lib/debugData'

export type AuthState = 'loading' | 'authenticated' | 'unauthenticated'

export interface UsePlayerAuthResult {
  authState: AuthState
  playerState: PlayerStateResponse | null
  error: string | null
  /** 手動でログアウトする（トークンを削除して unauthenticated に遷移） */
  logout: () => void
  /** 認証後に playerState を再フェッチする */
  refresh: () => Promise<void>
}

export function usePlayerAuth(): UsePlayerAuthResult {
  const [searchParams, setSearchParams] = useSearchParams()
  const [authState, setAuthState] = useState<AuthState>('loading')
  const [playerState, setPlayerState] = useState<PlayerStateResponse | null>(null)
  const [error, setError] = useState<string | null>(null)

  const loadState = async () => {
    try {
      const state = await fetchPlayerState()
      setPlayerState(state)
      setAuthState('authenticated')
    } catch (e) {
      clearPlayerToken()
      setAuthState('unauthenticated')
      if (e instanceof ApiException) setError(e.code)
    }
  }

  useEffect(() => {
    const otp   = searchParams.get('otp')
    const debug = searchParams.get('debug')

    if (debug !== null) {
      // デバッグモード: トークンがあれば実APIを叩く、なければモックで表示
      if (getPlayerToken()) {
        loadState()
      } else {
        setPlayerState(DEBUG_PLAYER_STATE)
        setAuthState('authenticated')
      }
      return
    }

    if (otp) {
      // OTP フロー: トークンを取得してから状態をロード
      ;(async () => {
        try {
          const { token } = await loginPlayer(otp)
          setPlayerToken(token)
          // OTP を URL から除去（ブラウザ履歴を汚さないよう replace）
          setSearchParams({}, { replace: true })
          await loadState()
        } catch (e) {
          setAuthState('unauthenticated')
          if (e instanceof ApiException) setError(e.code)
          else setError('unknown_error')
        }
      })()
    } else if (getPlayerToken()) {
      // 既存トークンの検証
      loadState()
    } else {
      setAuthState('unauthenticated')
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const logout = () => {
    clearPlayerToken()
    setPlayerState(null)
    setAuthState('unauthenticated')
    setError(null)
  }

  const refresh = async () => {
    await loadState()
  }

  return { authState, playerState, error, logout, refresh }
}
