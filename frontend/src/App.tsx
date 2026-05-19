/**
 * アプリケーションルート
 *
 * ルーティング:
 * - `/trade` — トレードページ（プレイヤー認証）
 * - `/admin` — 管理者ページ（管理者認証）
 * - `*`      — `/trade` へリダイレクト
 */

import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import TradePage from '@/pages/TradePage'
import AdminPage from '@/pages/AdminPage'
import './index.css'

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/trade" element={<TradePage />} />
        <Route path="/admin" element={<AdminPage />} />
        <Route path="*" element={<Navigate to="/trade" replace />} />
      </Routes>
    </BrowserRouter>
  )
}
