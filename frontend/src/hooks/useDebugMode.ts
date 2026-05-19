import { useSearchParams } from 'react-router-dom'

/** URL に `?debug` クエリが存在する場合 true を返す */
export function useDebugMode(): boolean {
  const [searchParams] = useSearchParams()
  return searchParams.get('debug') !== null
}
