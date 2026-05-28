/**
 * 保有注文一覧コンポーネント
 *
 * プレイヤーの open_orders を表示し、キャンセルボタンを提供する。
 */

import { useState } from 'react'
import { cancelOrder } from '@/lib/api'
import { ApiException } from '@/lib/api'
import type { OpenOrder } from '@/types/api'

interface Props {
  orders: OpenOrder[]
  onCancelled: (orderId: string) => void
}

export default function OpenOrders({ orders, onCancelled }: Props) {
  const [cancelling, setCancelling] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  const handleCancel = async (orderId: string) => {
    setError(null)
    setCancelling(orderId)
    try {
      await cancelOrder(orderId)
      onCancelled(orderId)
    } catch (e) {
      if (e instanceof ApiException) setError(e.code)
      else setError('unknown_error')
    } finally {
      setCancelling(null)
    }
  }

  return (
    <div className="open-orders">
      <h3 className="open-orders-title">保有注文</h3>
      {error && <p className="order-error">{error}</p>}
      {orders.length === 0 ? (
        <p className="open-orders-empty">保有注文はありません</p>
      ) : (
        <table className="open-orders-table">
          <thead>
            <tr>
              <th>ペア</th>
              <th>種別</th>
              <th>売買</th>
              <th>価格</th>
              <th>トリガー</th>
              <th>数量</th>
              <th>約定済</th>
              <th>状態</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {orders.map((o) => (
              <tr key={o.order_id}>
                <td>{o.pair_id}</td>
                <td>
                  {o.type === 'LIMIT'
                    ? '指値'
                    : o.type === 'MARKET'
                      ? '成行'
                      : o.type === 'STOP_MARKET'
                        ? '逆指値成行'
                        : '利確成行'}
                </td>
                <td className={o.side === 'BUY' ? 'bid' : 'ask'}>
                  {o.side === 'BUY' ? '買' : '売'}
                </td>
                <td>{o.price ?? '—'}</td>
                <td>{o.trigger_price ?? '—'}</td>
                <td>{o.amount}</td>
                <td>{o.filled}</td>
                <td>{o.status}</td>
                <td>
                  <button
                    className="cancel-btn"
                    onClick={() => handleCancel(o.order_id)}
                    disabled={cancelling === o.order_id}
                  >
                    {cancelling === o.order_id ? '...' : 'キャンセル'}
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  )
}
