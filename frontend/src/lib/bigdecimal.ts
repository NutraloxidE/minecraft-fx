/**
 * BigDecimal ユーティリティ
 *
 * js-big-decimal は CommonJS モジュールのためデフォルトインポートを使う。
 * このモジュールはサーバーから届いた文字列数値を安全に演算するためのヘルパーを提供する。
 *
 * @example
 * ```ts
 * import { bd, add, sub, format4 } from '@/lib/bigdecimal'
 * const result = format4(add("1.2345", "0.0001")) // "1.2346"
 * ```
 */
// eslint-disable-next-line @typescript-eslint/ban-ts-comment
// @ts-ignore
import BigDecimal from 'js-big-decimal'

/** 文字列数値を BigDecimal インスタンスに変換する */
// eslint-disable-next-line @typescript-eslint/no-explicit-any
export const bd = (value: string | number): any =>
  new BigDecimal(String(value))

/** 加算: a + b → 文字列 */
export const add = (a: string, b: string): string =>
  new BigDecimal(a).add(new BigDecimal(b)).getValue()

/** 減算: a - b → 文字列 */
export const sub = (a: string, b: string): string =>
  new BigDecimal(a).subtract(new BigDecimal(b)).getValue()

/** 乗算: a * b → 文字列 */
export const mul = (a: string, b: string): string =>
  new BigDecimal(a).multiply(new BigDecimal(b)).getValue()

/** 比較: a と b を比較（a < b: -1 / a === b: 0 / a > b: 1） */
export const cmp = (a: string, b: string): number =>
  new BigDecimal(a).compareTo(new BigDecimal(b))

/** 小数点以下4桁にフォーマット（四捨五入）して文字列で返す */
export const format4 = (value: string): string =>
  new BigDecimal(value).round(4, BigDecimal.RoundingModes.HALF_UP).getValue()

/** 小数点以下 n 桁にフォーマット（四捨五入）して文字列で返す */
export const formatN = (value: string, digits: number): string =>
  new BigDecimal(value).round(digits, BigDecimal.RoundingModes.HALF_UP).getValue()

/** ゼロかどうかを判定する */
export const isZero = (value: string): boolean =>
  cmp(value, '0') === 0

/** 正の数かどうかを判定する */
export const isPositive = (value: string): boolean =>
  cmp(value, '0') > 0
