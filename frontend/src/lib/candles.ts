/**
 * ローソク足集計ユーティリティ
 *
 * executions の配列を指定した時間足（秒単位）に集計し、
 * lightweight-charts の CandlestickData 形式に変換する。
 */

import type { Execution } from '@/types/api'

export interface Candle {
  time: number   // Unix 秒（lightweight-charts の UTCTimestamp）
  open: number
  high: number
  low: number
  close: number
}

/**
 * 約定履歴をローソク足に集計する。
 *
 * @param executions 約定履歴（古い順・新しい順どちらでも可）
 * @param intervalSec 時間足の秒数（例: 60 = 1分足、3600 = 1時間足）
 * @returns 時刻昇順に並んだローソク足配列
 */
export function aggregateCandles(
  executions: Execution[],
  intervalSec: number,
): Candle[] {
  if (executions.length === 0) return []

  const map = new Map<number, Candle>()

  for (const ex of executions) {
    // 足の開始時刻（切り捨て）
    const barTime = Math.floor(ex.timestamp / intervalSec) * intervalSec
    const price = parseFloat(ex.price)

    const existing = map.get(barTime)
    if (!existing) {
      map.set(barTime, {
        time: barTime,
        open: price,
        high: price,
        low: price,
        close: price,
      })
    } else {
      existing.high  = Math.max(existing.high, price)
      existing.low   = Math.min(existing.low, price)
      existing.close = price  // 最後の約定が終値
    }
  }

  return [...map.values()].sort((a, b) => a.time - b.time)
}

/** 利用可能な時間足の定義 */
export const TIMEFRAMES = [
  { label: '20秒', sec: 20        },
  { label: '1分',  sec: 60        },
  { label: '5分',  sec: 300       },
  { label: '15分', sec: 900       },
  { label: '1時間', sec: 3600     },
  { label: '4時間', sec: 14400    },
  { label: '1日',  sec: 86400     },
] as const

export type Timeframe = typeof TIMEFRAMES[number]
