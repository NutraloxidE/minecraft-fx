/**
 * 管理者認証フック
 *
 * ## 3ステート
 * - `loading`         — OTP 検証中
 * - `authenticated`   — 有効な管理者トークンが localStorage に存在する
 * - `unauthenticated` — トークンなし、または OTP 検証失敗
 *
 * ## 認証フロー
 * プレイヤー認証と同様。使用する API は POST /api/admin/auth。
 * トークン検証は GET /api/admin/pairs の成否で行う。
 */

import { useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { loginAdmin, adminFetchPairs } from '@/lib/api'
import { getAdminToken, setAdminToken, clearAdminToken } from '@/lib/auth'
import { ApiException } from '@/lib/api'

export type AdminAuthState = 'loading' | 'authenticated' | 'unauthenticated'

export interface UseAdminAuthResult {
  authState: AdminAuthState
  error: string | null
  logout: () => void
}

export function useAdminAuth(): UseAdminAuthResult {
  const [searchParams, setSearchParams] = useSearchParams()
  const [authState, setAuthState] = useState<AdminAuthState>('loading')
  const [error, setError] = useState<string | null>(null)

  const validateToken = async () => {
    try {
      await adminFetchPairs()
      setAuthState('authenticated')
    } catch (e) {
      clearAdminToken()
      setAuthState('unauthenticated')
      if (e instanceof ApiException) setError(e.code)
    }
  }

  useEffect(() => {
    const otp = searchParams.get('otp')

    if (otp) {
      ;(async () => {
        try {
          const { token } = await loginAdmin(otp)
          setAdminToken(token)
          setSearchParams({}, { replace: true })
          await validateToken()
        } catch (e) {
          setAuthState('unauthenticated')
          if (e instanceof ApiException) setError(e.code)
          else setError('unknown_error')
        }
      })()
    } else if (getAdminToken()) {
      validateToken()
    } else {
      setAuthState('unauthenticated')
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const logout = () => {
    clearAdminToken()
    setAuthState('unauthenticated')
    setError(null)
  }

  return { authState, error, logout }
}
