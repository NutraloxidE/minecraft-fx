/**
 * ペア選択コンポーネント（カスタムドロップダウン）
 */

import { useState, useRef, useEffect } from 'react'
import type { PairSummary } from '@/types/api'

interface Props {
  pairs: PairSummary[]
  selectedId: string | null
  onChange: (id: string) => void
}

export default function PairSelector({ pairs, selectedId, onChange }: Props) {
  const enabled = pairs.filter((p) => p.enabled)
  const [open, setOpen] = useState(false)
  const ref = useRef<HTMLDivElement>(null)

  // 外側クリックで閉じる
  useEffect(() => {
    const handler = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) {
        setOpen(false)
      }
    }
    document.addEventListener('mousedown', handler)
    return () => document.removeEventListener('mousedown', handler)
  }, [])

  if (enabled.length === 0) {
    return (
      <div className="pair-selector">
        <span className="pair-selector-empty">取引可能なペアがありません</span>
      </div>
    )
  }

  const selected = enabled.find((p) => p.id === selectedId)

  return (
    <div className="pair-selector">
      <div className="pair-dropdown" ref={ref}>
        <button
          className={`pair-dropdown-trigger${open ? ' open' : ''}`}
          onClick={() => setOpen((v) => !v)}
          type="button"
        >
          <span className="pair-dropdown-label">{selected?.id ?? '—'}</span>
          {selected?.last_price && (
            <span className="pair-dropdown-price">{selected.last_price}</span>
          )}
          <svg className="pair-dropdown-arrow" viewBox="0 0 10 6" width="10" height="6">
            <path d="M0 0l5 6 5-6z" />
          </svg>
        </button>

        {open && (
          <div className="pair-dropdown-menu">
            {enabled.map((p) => (
              <button
                key={p.id}
                className={`pair-dropdown-item${p.id === selectedId ? ' active' : ''}`}
                type="button"
                onClick={() => { onChange(p.id); setOpen(false) }}
              >
                <span className="pair-dropdown-item-id">{p.id}</span>
                {p.last_price && (
                  <span className="pair-dropdown-item-price">{p.last_price}</span>
                )}
              </button>
            ))}
          </div>
        )}
      </div>
    </div>
  )
}
