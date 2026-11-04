// 容器寬度變了（轉手機橫豎、收合側欄、視窗縮放）就通知圖表重新排版
export function observeWidth(el, cb) {
  if (!el || typeof ResizeObserver === 'undefined') return () => {}
  let last = 0
  const ro = new ResizeObserver(entries => {
    const w = Math.floor(entries[0]?.contentRect?.width || 0)
    if (w > 0 && w !== last) { last = w; cb(w) }
  })
  ro.observe(el)
  return () => ro.disconnect()
}
