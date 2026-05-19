/**
 * ペア選択コンポーネント
 */

import type { PairSummary } from '@/types/api'

interface Props {
  pairs: PairSummary[]
  selectedId: string | null
  onChange: (id: string) => void
}

export default function PairSelector({ pairs, selectedId, onChange }: Props) {
  const enabled = pairs.filter((p) => p.enabled)

  return (
    <div className="pair-selector">
      {enabled.map((p) => (
        <button
          key={p.id}
          className={`pair-btn${p.id === selectedId ? ' active' : ''}`}
          onClick={() => onChange(p.id)}
        >
          <span className="pair-btn-id">{p.id}</span>
          {p.last_price && (
            <span className="pair-btn-price">{p.last_price}</span>
          )}
        </button>
      ))}
      {enabled.length === 0 && (
        <span className="pair-selector-empty">取引可能なペアがありません</span>
      )}
    </div>
  )
}
