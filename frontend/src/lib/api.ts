/**
 * GekiyabaFX API クライアント
 *
 * すべての API 呼び出しをここに集約する。
 *
 * ## 共通仕様
 * - `Authorization: Bearer <token>` ヘッダーを自動付与する。
 * - レスポンスの数値フィールドはすべて文字列で届く（BigDecimal 安全転送）。
 * - HTTP エラー時は {@link ApiException} をスローする。
 *
 * ## ベース URL
 * - 本番（jar内配信）: 同一オリジン `/api/...`
 * - 開発（Vite dev server）: vite.config.ts のプロキシ設定により `/api/...` を 3010 番へ転送
 */

import { getPlayerToken, getAdminToken } from '@/lib/auth'
import type {
  PairSummary,
  OrderBookResponse,
  Execution,
  PlayerStateResponse,
  PlaceOrderRequest,
  PlaceOrderResponse,
  CancelOrderResponse,
  DepositRequest,
  DepositResponse,
  WithdrawRequest,
  WithdrawResponse,
  AtmSessionResponse,
  AdminPair,
  CreatePairRequest,
  PatchPairRequest,
} from '@/types/api'

// ─────────────────────────────────────────────────────────────────────────────
//  エラー型
// ─────────────────────────────────────────────────────────────────────────────

/** API から返ってきたエラーレスポンスを表す例外 */
export class ApiException extends Error {
  readonly status: number
  readonly code: string

  constructor(status: number, code: string) {
    super(`API error ${status}: ${code}`)
    this.name = 'ApiException'
    this.status = status
    this.code = code
  }
}

// ─────────────────────────────────────────────────────────────────────────────
//  fetch ラッパー
// ─────────────────────────────────────────────────────────────────────────────

type Method = 'GET' | 'POST' | 'PATCH' | 'DELETE'

/**
 * 内部 fetch ヘルパー。
 *
 * @param method    HTTP メソッド
 * @param path      `/api/...` 形式のパス
 * @param token     Bearer トークン（認証不要なエンドポイントは null）
 * @param body      リクエストボディ（GET / DELETE では undefined）
 */
async function request<T>(
  method: Method,
  path: string,
  token: string | null,
  body?: unknown,
): Promise<T> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
  }
  if (token) {
    headers['Authorization'] = `Bearer ${token}`
  }

  const res = await fetch(path, {
    method,
    headers,
    body: body !== undefined ? JSON.stringify(body) : undefined,
  })

  const json = await res.json().catch(() => ({}))

  if (!res.ok) {
    const code = (json as { error?: string }).error ?? String(res.status)
    throw new ApiException(res.status, code)
  }

  return json as T
}

// ─────────────────────────────────────────────────────────────────────────────
//  公開 API（認証不要）
// ─────────────────────────────────────────────────────────────────────────────

/** 全ペアの一覧を取得する */
export const fetchPairs = (): Promise<PairSummary[]> =>
  request('GET', '/api/pairs', null)

export interface PairFeeResponse {
  pair:        string
  maker_base:  string
  taker_base:  string
  maker_quote: string
  taker_quote: string
}

/** 指定ペアの手数料率を取得する */
export const fetchPairFee = (pairId: string): Promise<PairFeeResponse> =>
  request('GET', `/api/pairs/${encodeURIComponent(pairId)}/fee`, null)

/** 指定ペアのオーダーブック（板）を取得する */
export const fetchOrderBook = (pairId: string): Promise<OrderBookResponse> =>
  request('GET', `/api/orderbook?pair=${encodeURIComponent(pairId)}`, null)

/** 指定ペアの約定履歴を取得する */
export const fetchExecutions = async (
  pairId: string,
  since?: number,
): Promise<Execution[]> => {
  const params = new URLSearchParams({ pair: pairId })
  if (since !== undefined) params.set('since', String(since))
  const res = await request<{ pair: string; executions: Execution[] }>(
    'GET',
    `/api/executions?${params}`,
    null,
  )
  return res.executions ?? []
}

// ─────────────────────────────────────────────────────────────────────────────
//  認証 API（プレイヤー）
// ─────────────────────────────────────────────────────────────────────────────

/** OTP でプレイヤーセッションを開始する */
export const loginPlayer = (otp: string): Promise<{ token: string }> =>
  request('POST', '/api/auth', null, { otp })

// ─────────────────────────────────────────────────────────────────────────────
//  プレイヤー API（要認証）
// ─────────────────────────────────────────────────────────────────────────────

/** プレイヤーの現在状態（残高・ロック・板上注文）を取得する */
export const fetchPlayerState = (): Promise<PlayerStateResponse> =>
  request('GET', '/api/state', getPlayerToken())

/** 注文を発注する */
export const placeOrder = (req: PlaceOrderRequest): Promise<PlaceOrderResponse> =>
  request('POST', '/api/order', getPlayerToken(), req)

/** 注文をキャンセルする */
export const cancelOrder = (orderId: string): Promise<CancelOrderResponse> =>
  request('DELETE', `/api/order/${orderId}`, getPlayerToken())

/** アイテムを預け入れる */
export const deposit = (req: DepositRequest): Promise<DepositResponse> =>
  request('POST', '/api/deposit', getPlayerToken(), req)

/** アイテムを引き出す */
export const withdraw = (req: WithdrawRequest): Promise<WithdrawResponse> =>
  request('POST', '/api/withdraw', getPlayerToken(), req)

/** ATM セッション状態を取得する */
export const fetchAtmSession = (): Promise<AtmSessionResponse> =>
  request('GET', '/api/atm-session', getPlayerToken())

export interface ResolveTransferTargetResponse {
  found: boolean
  uuid?: string
  name?: string
}

export interface TransferRequest {
  to_uuid: string
  item: string
  amount: string
}

export interface TransferResponse {
  ok: boolean
  item: string
  amount: string
  from_uuid: string
  to_uuid: string
  from_balance_after: string
  to_balance_after: string
}

/** 振込先を UUID またはユーザー名で検索する */
export const resolveTransferTarget = (q: string): Promise<ResolveTransferTargetResponse> =>
  request('GET', `/api/transfer/resolve?q=${encodeURIComponent(q)}`, getPlayerToken())

/** ホット口座残高を他アカウントへ振り込む */
export const transfer = (req: TransferRequest): Promise<TransferResponse> =>
  request('POST', '/api/transfer', getPlayerToken(), req)

// ─────────────────────────────────────────────────────────────────────────────
//  認証 API（管理者）
// ─────────────────────────────────────────────────────────────────────────────

/** OTP で管理者セッションを開始する */
export const loginAdmin = (otp: string): Promise<{ token: string }> =>
  request('POST', '/api/admin/auth', null, { otp })

// ─────────────────────────────────────────────────────────────────────────────
//  管理者 API（要認証）
// ─────────────────────────────────────────────────────────────────────────────

/** 全ペアを取得する（管理者用、無効ペア含む） */
export interface ServiceAccount {
  name:        string
  id:          string
  hot_storage: Record<string, string>
}

/** 全サービスアカウントのホット残高を取得する（管理者認証必要） */
export const adminFetchServiceAccounts = (): Promise<ServiceAccount[]> =>
  request('GET', '/api/admin/service-accounts', getAdminToken())

export const adminFetchPairs = (): Promise<AdminPair[]> =>
  request('GET', '/api/admin/pairs', getAdminToken())

/** ペアを新規作成する */
export const adminCreatePair = (req: CreatePairRequest): Promise<{ id: string; created: boolean }> =>
  request('POST', '/api/admin/pairs', getAdminToken(), req)

/** ペアを部分更新する */
export const adminPatchPair = (id: string, req: PatchPairRequest): Promise<AdminPair> =>
  request('PATCH', `/api/admin/pairs/${encodeURIComponent(id)}`, getAdminToken(), req)

/** ペアを削除する */
export const adminDeletePair = (id: string): Promise<{ id: string; deleted: boolean }> =>
  request('DELETE', `/api/admin/pairs/${encodeURIComponent(id)}`, getAdminToken())

export interface ArbitrageSkipRecord {
  pair: string
  reason: string
  timestamp: string
}

export interface ArbitrageLastExecution {
  pair: string
  timestamp: string
  status: string
  order_ids: string[]
}

export interface ArbitrageStatusResponse {
  enabled: boolean
  service_account: string
  check_interval_ticks: number
  pairs_under_watch: string[]
  last_check: string | null
  last_execution: ArbitrageLastExecution | null
  recent_skips: ArbitrageSkipRecord[]
}

export interface ArbitrageToggleRequest {
  enabled?: boolean
  service_account?: string
  check_interval_ticks?: number
}

export interface ArbitrageToggleResponse {
  enabled: boolean
  current_service_account: string
  current_check_interval_ticks: number
  timestamp: string
}

/** 裁定取引の現在状態を取得する */
export const adminFetchArbitrageStatus = (): Promise<ArbitrageStatusResponse> =>
  request('GET', '/api/admin/arbitrage/status', getAdminToken())

/** 裁定取引の実行ON/OFF・サービスアカウントを更新する */
export const adminToggleArbitrage = (req: ArbitrageToggleRequest): Promise<ArbitrageToggleResponse> =>
  request('PATCH', '/api/admin/arbitrage/toggle', getAdminToken(), req)
