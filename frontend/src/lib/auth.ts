/**
 * localStorage によるセッショントークン管理。
 *
 * プレイヤートークンと管理者トークンをそれぞれ独立したキーで保存する。
 */

const PLAYER_TOKEN_KEY = 'gfx_player_token'
const ADMIN_TOKEN_KEY  = 'gfx_admin_token'

// ─── プレイヤートークン ────────────────────────────────────────────────────────

export const getPlayerToken = (): string | null =>
  localStorage.getItem(PLAYER_TOKEN_KEY)

export const setPlayerToken = (token: string): void =>
  localStorage.setItem(PLAYER_TOKEN_KEY, token)

export const clearPlayerToken = (): void =>
  localStorage.removeItem(PLAYER_TOKEN_KEY)

// ─── 管理者トークン ───────────────────────────────────────────────────────────

export const getAdminToken = (): string | null =>
  localStorage.getItem(ADMIN_TOKEN_KEY)

export const setAdminToken = (token: string): void =>
  localStorage.setItem(ADMIN_TOKEN_KEY, token)

export const clearAdminToken = (): void =>
  localStorage.removeItem(ADMIN_TOKEN_KEY)
