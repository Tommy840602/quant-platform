/** 後端回 { notionalUsdt: 6 }；舊版可能直接回數字。兩種都吃，讀不到回 0。 */
export function notionalOf(data) {
  const v = Number(data && typeof data === 'object' ? data.notionalUsdt : data)
  return Number.isFinite(v) && v > 0 ? v : 0
}
