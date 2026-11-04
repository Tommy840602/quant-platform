import { ref, onMounted, onUnmounted } from 'vue'

export const MOBILE_QUERY = '(max-width: 768px)'

/** 是否手機寬度（≤768px），隨視窗變化更新。 */
export function useIsMobile() {
  const mq = typeof window !== 'undefined' && window.matchMedia ? window.matchMedia(MOBILE_QUERY) : null
  const isMobile = ref(!!mq?.matches)
  const on = e => { isMobile.value = e.matches }
  onMounted(() => mq?.addEventListener?.('change', on))
  onUnmounted(() => mq?.removeEventListener?.('change', on))
  return isMobile
}
