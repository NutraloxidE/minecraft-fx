import { useMemo } from 'react'
import { useExecutionCache } from '@/hooks/useExecutionCache'
import type { Execution } from '@/types/api'

interface Props {
  pairId: string | null
  playerUuid: string | null
  maxRows?: number
}

function formatTs(ts: number) {
  return new Date(ts * 1000).toLocaleString('ja-JP', { hour12: false })
}

function normalizeSide(ex: Execution, playerUuid: string | null): 'BUY' | 'SELL' | 'UNKNOWN' {
  if (!playerUuid) return 'UNKNOWN'
  if (ex.buyer_uuid && ex.buyer_uuid === playerUuid) return 'BUY'
  if (ex.seller_uuid && ex.seller_uuid === playerUuid) return 'SELL'
  return 'UNKNOWN'
}

export default function ExecutionHistory({ pairId, playerUuid, maxRows = 50 }: Props) {
  const { executions, loading } = useExecutionCache(pairId)

  const rows = useMemo(() => {
    const own = executions.filter((ex) => {
      if (!playerUuid) return false
      if (ex.buyer_uuid || ex.seller_uuid) {
        return ex.buyer_uuid === playerUuid || ex.seller_uuid === playerUuid
      }
      return false
    })

    return [...own]
      .sort((a, b) => b.timestamp - a.timestamp)
      .slice(0, maxRows)
  }, [executions, maxRows, playerUuid])

  const hasLegacyRows = useMemo(
    () => executions.some((ex) => !ex.buyer_uuid && !ex.seller_uuid),
    [executions],
  )

  return (
    <div className="execution-history">
      <h2 className="execution-history-title">約定履歴</h2>

      {loading && rows.length === 0 && (
        <p className="execution-history-empty">読み込み中...</p>
      )}

      {!loading && rows.length === 0 && (
        <p className="execution-history-empty">このペアで自分の約定履歴はまだありません</p>
      )}

      {rows.length > 0 && (
        <table className="execution-history-table">
          <thead>
            <tr>
              <th>時刻</th>
              <th>売買</th>
              <th>価格</th>
              <th>数量</th>
              <th>注文ID</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((ex, idx) => {
              const side = normalizeSide(ex, playerUuid)
              const sideClass = side === 'BUY' ? 'bid' : side === 'SELL' ? 'ask' : ''
              const orderId = side === 'BUY' ? ex.buy_order_id : side === 'SELL' ? ex.sell_order_id : null
              const key = ex.execution_id ?? `${ex.timestamp}-${ex.price}-${ex.amount}-${idx}`

              return (
                <tr key={key}>
                  <td>{formatTs(ex.timestamp)}</td>
                  <td className={sideClass}>{side === 'UNKNOWN' ? '-' : side}</td>
                  <td>{ex.price}</td>
                  <td>{ex.amount}</td>
                  <td className="execution-history-order-id">{orderId ?? '-'}</td>
                </tr>
              )
            })}
          </tbody>
        </table>
      )}

      {hasLegacyRows && (
        <p className="execution-history-note">
          旧データには注文IDや売買当事者が未記録のため、この一覧には表示されない場合があります。
        </p>
      )}
    </div>
  )
}
