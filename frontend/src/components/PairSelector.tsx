/**
 * ペア選択コンポーネント（カスタムドロップダウン）
 */

import { useState, useRef, useEffect } from 'react'
import type { PairSummary } from '@/types/api'

const ITEM_ICON_BASE = 'https://assets.mcasset.cloud/1.21.4/assets/minecraft/textures/item'
const BLOCK_ICON_BASE = 'https://assets.mcasset.cloud/1.21.4/assets/minecraft/textures/block'

function normalizeItemKey(raw: string): string {
  return raw.replace(/^minecraft:/i, '').trim().toLowerCase()
}

function iconUrl(itemKey: string, kind: 'item' | 'block'): string {
  const base = kind === 'item' ? ITEM_ICON_BASE : BLOCK_ICON_BASE
  return `${base}/${encodeURIComponent(normalizeItemKey(itemKey))}.png`
}

function ItemIconImage({
  itemKey,
  className,
}: {
  itemKey: string
  className: string
}) {
  const normalizedKey = normalizeItemKey(itemKey)
  const [kind, setKind] = useState<'item' | 'block'>('item')
  const [hidden, setHidden] = useState(false)

  useEffect(() => {
    setKind('item')
    setHidden(false)
  }, [normalizedKey])

  if (hidden) {
    return null
  }

  return (
    <img
      className={className}
      src={iconUrl(normalizedKey, kind)}
      alt=""
      loading="lazy"
      onError={() => {
        if (kind === 'item') {
          setKind('block')
          return
        }
        setHidden(true)
      }}
    />
  )
}

function PairIcon({ base, quote }: { base: string; quote: string }) {
  const baseKey = normalizeItemKey(base)
  const quoteKey = normalizeItemKey(quote)

  return (
    <span className="pair-icon-stack" aria-hidden="true">
      <ItemIconImage className="pair-icon-main" itemKey={baseKey} />
      <ItemIconImage className="pair-icon-overlay" itemKey={quoteKey} />
      <span className="pair-icon-fallback">{baseKey.slice(0, 1).toUpperCase()}</span>
    </span>
  )
}

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
          {selected && <PairIcon base={selected.base} quote={selected.quote} />}
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
                <PairIcon base={p.base} quote={p.quote} />
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
