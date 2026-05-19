/**
 * GekiyabaFX API レスポンス型定義
 *
 * 数値フィールドはすべてサーバーから文字列（例: "4.3000"）で届く。
 * フロントエンドでは BigDecimal ラッパーを通じて演算し、
 * 表示時は toPlainString() / toFixed() を用いる。
 */

// ─── 共通 ─────────────────────────────────────────────────────────────────────

/** API エラーレスポンス */
export interface ApiError {
  error: string
}

// ─── 公開 API ─────────────────────────────────────────────────────────────────

export interface PairSummary {
  id: string
  base: string
  quote: string
  enabled: boolean
  min_amount: string
  min_price: string
  last_price: string | null
}

export interface OrderBookEntry {
  order_id: string
  uuid: string
  type: 'LIMIT' | 'MARKET'
  side: 'BUY' | 'SELL'
  price: string | null
  amount: string
  filled: string
  status: 'OPEN' | 'PARTIALLY_FILLED' | 'FILLED' | 'CANCELLED'
  created_at: number
}

export interface OrderBookResponse {
  pair_id: string
  bids: OrderBookEntry[]
  asks: OrderBookEntry[]
}

export interface Execution {
  execution_id: string
  buyer_uuid: string
  seller_uuid: string
  price: string
  amount: string
  timestamp: number
}

// ─── プレイヤー API ───────────────────────────────────────────────────────────

export interface PlayerStateResponse {
  uuid: string
  name: string
  /** { "diamond": "132.0000", "emerald": "50.0000" } */
  hot_storage: Record<string, string>
  /** { "DIAMOND/EMERALD": { "diamond": "1.0000", "emerald": "4.2000" } } */
  locked_balance: Record<string, Record<string, string>>
  open_orders: OpenOrder[]
  /** オフライン中のデポジット保留数 { "diamond": 5 } */
  pending_deposit: Record<string, number>
  /** オフライン中のウィズドロー保留数 { "diamond": 3 } */
  pending_withdraw: Record<string, number>
}

export interface OpenOrder {
  order_id: string
  pair_id: string
  type: 'LIMIT' | 'MARKET'
  side: 'BUY' | 'SELL'
  price: string | null
  amount: string
  filled: string
  status: 'OPEN' | 'PARTIALLY_FILLED'
  created_at: number
}

export interface PlaceOrderRequest {
  pair_id: string
  side: 'BUY' | 'SELL'
  type: 'LIMIT' | 'MARKET'
  price?: string
  amount?: string
  max_spend?: string
}

export interface PlaceOrderResponse {
  order_id: string
  executions: ExecutionResult[]
}

export interface ExecutionResult {
  execution_id: string
  price: string
  amount: string
  timestamp: number
}

export interface CancelOrderResponse {
  cancelled: boolean
}

// ─── 入出金 API ───────────────────────────────────────────────────────────────

export interface DepositRequest {
  item: string
  amount: number
}

export interface DepositResponse {
  deposited?: number
  hot_balance?: string
  pending?: number
}

export interface WithdrawRequest {
  item: string
  amount: number
}

export interface WithdrawResponse {
  withdrawn?: number
  hot_balance?: string
  pending?: number
}

// ─── 管理者 API ───────────────────────────────────────────────────────────────

export interface AdminPair {
  id: string
  base: string
  quote: string
  enabled: boolean
  min_amount: string
  min_price: string
  last_price: string | null
}

export interface CreatePairRequest {
  id: string
  base: string
  quote: string
  enabled: boolean
  min_amount: string
  min_price: string
}

export interface PatchPairRequest {
  enabled?: boolean
  min_amount?: string
  min_price?: string
}
