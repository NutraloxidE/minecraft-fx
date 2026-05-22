/**
 * デバッグモード（?debug=1）用フェイクデータ
 *
 * API サーバーが不要な状態でUIの全項目を確認できるよう、
 * 板・約定・プレイヤー状態・ペア一覧をすべてモックとして提供する。
 */

import type {
  PairSummary,
  AdminPair,
  OrderBookResponse,
  Execution,
  PlayerStateResponse,
} from '@/types/api'
import type { ServiceAccount, ArbitrageStatusResponse } from '@/lib/api'

// ─── ペア一覧 ──────────────────────────────────────────────────────────────────

export const DEBUG_PAIRS: PairSummary[] = [
  {
    id: 'DIAMOND/EMERALD',
    base: 'diamond',
    quote: 'emerald',
    enabled: true,
    min_amount: '1',
    min_price: '0.0001',
    last_price: '4.3200',
  },
  {
    id: 'GOLD/EMERALD',
    base: 'gold',
    quote: 'emerald',
    enabled: true,
    min_amount: '1',
    min_price: '0.0001',
    last_price: '0.8500',
  },
]

// ─── 板情報 ────────────────────────────────────────────────────────────────────

export function makeDebugOrderBook(pairId: string): OrderBookResponse {
  return {
    pair_id: pairId,
    bids: [
      { order_id: 'b1', uuid: 'u1', type: 'LIMIT', side: 'BUY',  price: '4.3000', amount:  '5.0000', filled: '0.0000', status: 'OPEN', created_at: 1748000000 },
      { order_id: 'b2', uuid: 'u2', type: 'LIMIT', side: 'BUY',  price: '4.2800', amount: '10.0000', filled: '0.0000', status: 'OPEN', created_at: 1748000000 },
      { order_id: 'b3', uuid: 'u3', type: 'LIMIT', side: 'BUY',  price: '4.2500', amount:  '3.0000', filled: '0.0000', status: 'OPEN', created_at: 1748000000 },
      { order_id: 'b4', uuid: 'u4', type: 'LIMIT', side: 'BUY',  price: '4.2000', amount:  '8.0000', filled: '0.0000', status: 'OPEN', created_at: 1748000000 },
      { order_id: 'b5', uuid: 'u5', type: 'LIMIT', side: 'BUY',  price: '4.1500', amount: '15.0000', filled: '2.0000', status: 'PARTIALLY_FILLED', created_at: 1748000000 },
    ],
    asks: [
      { order_id: 'a1', uuid: 'u6',  type: 'LIMIT', side: 'SELL', price: '4.3400', amount:  '4.0000', filled: '0.0000', status: 'OPEN', created_at: 1748000000 },
      { order_id: 'a2', uuid: 'u7',  type: 'LIMIT', side: 'SELL', price: '4.3600', amount:  '7.0000', filled: '0.0000', status: 'OPEN', created_at: 1748000000 },
      { order_id: 'a3', uuid: 'u8',  type: 'LIMIT', side: 'SELL', price: '4.3800', amount:  '2.0000', filled: '1.0000', status: 'PARTIALLY_FILLED', created_at: 1748000000 },
      { order_id: 'a4', uuid: 'u9',  type: 'LIMIT', side: 'SELL', price: '4.4000', amount:  '9.0000', filled: '0.0000', status: 'OPEN', created_at: 1748000000 },
      { order_id: 'a5', uuid: 'u10', type: 'LIMIT', side: 'SELL', price: '4.4500', amount: '12.0000', filled: '0.0000', status: 'OPEN', created_at: 1748000000 },
    ],
  }
}

// ─── 約定履歴（チャート用） ────────────────────────────────────────────────────

/** 直近2時間分・80件のランダムウォーク約定履歴を生成する */
function generateFakeExecutions(): Execution[] {
  const now = Math.floor(Date.now() / 1000)
  const spanSec = 7200 // 2時間
  const count = 80
  const execs: Execution[] = []
  let price = 4.30

  for (let i = 0; i < count; i++) {
    const timestamp = now - spanSec + Math.floor((i / count) * spanSec)
    price += (Math.random() - 0.48) * 0.06
    price = Math.max(3.90, Math.min(4.80, price))
    execs.push({
      execution_id: `debug-ex-${i}`,
      buyer_uuid:   'debug-uuid',
      seller_uuid:  'other-uuid',
      price:        price.toFixed(4),
      amount:       (1 + Math.random() * 4).toFixed(4),
      timestamp,
    })
  }
  return execs
}

export const DEBUG_EXECUTIONS: Execution[] = generateFakeExecutions()

// ─── プレイヤー状態 ────────────────────────────────────────────────────────────

export const DEBUG_PLAYER_STATE: PlayerStateResponse = {
  uuid: 'debug-uuid',
  name: 'DebugPlayer',
  hot_storage: {
    diamond: '132.0000',
    emerald: '500.0000',
    gold:    '80.0000',
  },
  locked_balance: {
    'DIAMOND/EMERALD': { diamond: '5.0000', emerald: '0.0000' },
    'GOLD/EMERALD':    { gold:    '10.0000', emerald: '0.0000' },
  },
  open_orders: [
    {
      order_id:   'debug-order-1',
      pair_id:    'DIAMOND/EMERALD',
      type:       'LIMIT',
      side:       'BUY',
      price:      '4.2000',
      amount:     '10.0000',
      filled:     '3.0000',
      status:     'PARTIALLY_FILLED',
      created_at: 1748000000,
    },
    {
      order_id:   'debug-order-2',
      pair_id:    'DIAMOND/EMERALD',
      type:       'LIMIT',
      side:       'SELL',
      price:      '4.5000',
      amount:     '5.0000',
      filled:     '0.0000',
      status:     'OPEN',
      created_at: 1748000000,
    },
    {
      order_id:   'debug-order-3',
      pair_id:    'GOLD/EMERALD',
      type:       'LIMIT',
      side:       'BUY',
      price:      '0.8200',
      amount:     '20.0000',
      filled:     '0.0000',
      status:     'OPEN',
      created_at: 1748000000,
    },
  ],
  pending_deposit:  { diamond: 2 },
  pending_withdraw: { emerald: 1 },
}

// ─── Transfer ページ用 ─────────────────────────────────────────────────────

export const DEBUG_TRANSFER_TARGET = {
  found: true,
  uuid:  'aaaabbbb-cccc-dddd-eeee-ffff00001111',
  name:  'SamplePlayer',
}

// ─── Admin ページ用 ────────────────────────────────────────────────────────

export const DEBUG_ADMIN_PAIRS: AdminPair[] = [
  {
    id: 'DIAMOND/EMERALD',
    base: 'diamond',
    quote: 'emerald',
    enabled: true,
    min_amount: '1',
    min_price: '0.0001',
    last_price: '4.3200',
  },
  {
    id: 'GOLD/EMERALD',
    base: 'gold',
    quote: 'emerald',
    enabled: true,
    min_amount: '1',
    min_price: '0.0001',
    last_price: '0.8500',
  },
  {
    id: 'IRON/EMERALD',
    base: 'iron_ingot',
    quote: 'emerald',
    enabled: false,
    min_amount: '1',
    min_price: '0.0001',
    last_price: null,
  },
]

export const DEBUG_SERVICE_ACCOUNTS: ServiceAccount[] = [
  {
    name: 'treasury-fee',
    id: 'svc:treasury-fee',
    hot_storage: {
      emerald: '1234.5600',
      diamond: '98.0000',
    },
  },
  {
    name: 'intervention-main',
    id: 'svc:intervention-main',
    hot_storage: {
      emerald: '5000.0000',
      gold: '300.0000',
    },
  },
  {
    name: 'market-bot-1',
    id: 'svc:market-bot-1',
    hot_storage: {
      diamond: '42.0000',
      emerald: '850.0000',
      gold: '120.0000',
    },
  },
  {
    name: 'arbitrage',
    id: 'svc:arbitrage',
    hot_storage: {
      emerald: '3000.0000',
      diamond: '25.0000',
    },
  },
]

export const DEBUG_ARBITRAGE_STATUS: ArbitrageStatusResponse = {
  enabled: false,
  service_account: 'svc:arbitrage',
  pairs_under_watch: ['DIAMOND/EMERALD', 'GOLD/EMERALD'],
  last_check: null,
  last_execution: null,
  recent_skips: [
    {
      pair: 'DIAMOND/EMERALD',
      reason: 'low_spread',
      timestamp: new Date(Date.now() - 60_000).toISOString(),
    },
  ],
}
