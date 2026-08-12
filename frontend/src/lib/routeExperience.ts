import type {
  RouteLocationNormalized,
  RouteLocationNormalizedLoaded,
  RouterScrollBehavior,
} from 'vue-router'

import type { TranslationKey } from '@/lib/locale'

type Translate = (key: TranslationKey, variables?: Record<string, string | number>) => string

export function routeDocumentTitle(
  route: Pick<RouteLocationNormalizedLoaded, 'meta'>,
  translate: Translate,
) {
  const titleKey = route.meta.titleKey
  return titleKey ? `${translate(titleKey)} · RulePilot` : 'RulePilot'
}

export const appScrollBehavior: RouterScrollBehavior = (to, from, savedPosition) => {
  if (savedPosition) return savedPosition
  if (to.hash) return { el: to.hash, top: 80 }
  if (to.path === from.path) return false
  return { left: 0, top: 0 }
}

export function routeNeedsContentFocus(
  to: Pick<RouteLocationNormalized, 'path'>,
  from: Pick<RouteLocationNormalizedLoaded, 'path'>,
) {
  return Boolean(from.path) && to.path !== from.path
}

export function focusMainContent(root: Document = document) {
  const mainContent = root.getElementById('main-content')
  if (!mainContent) return false
  mainContent.focus({ preventScroll: true })
  return root.activeElement === mainContent
}

declare module 'vue-router' {
  interface RouteMeta {
    titleKey?: TranslationKey
  }
}
