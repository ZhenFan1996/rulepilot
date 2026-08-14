import { nextTick, onBeforeUnmount, watch, type Ref, type WatchSource } from 'vue'

const FOCUSABLE_SELECTOR = [
  'a[href]',
  'button:not([disabled])',
  'input:not([disabled]):not([type="hidden"])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  '[contenteditable="true"]',
  '[tabindex]:not([tabindex="-1"])',
].join(',')

interface ModalEntry {
  dialog: Ref<HTMLElement | null>
  initialFocus?: () => HTMLElement | null
  opener: HTMLElement | null
  requestClose: () => void
  restoreFocus?: () => HTMLElement | null
}

interface ScrollLockSnapshot {
  bodyOverflow: string
  bodyPaddingRight: string
  rootOverflow: string
}

interface ModalFocusOptions {
  dialog: Ref<HTMLElement | null>
  initialFocus?: () => HTMLElement | null
  open: WatchSource<boolean>
  requestClose: () => void
  restoreFocus?: () => HTMLElement | null
}

const modalStack: ModalEntry[] = []
let scrollLockSnapshot: ScrollLockSnapshot | null = null

export function useModalFocus(options: ModalFocusOptions) {
  const entry: ModalEntry = {
    dialog: options.dialog,
    initialFocus: options.initialFocus,
    opener: null,
    requestClose: options.requestClose,
    restoreFocus: options.restoreFocus,
  }

  function handleKeydown(event: KeyboardEvent) {
    if (modalStack.at(-1) !== entry || event.isComposing) return
    if (event.key === 'Escape') {
      event.preventDefault()
      event.stopImmediatePropagation()
      entry.requestClose()
      return
    }
    if (event.key !== 'Tab') return

    const dialog = entry.dialog.value
    if (!dialog) return
    const focusable = focusableElements(dialog)
    if (!focusable.length) {
      event.preventDefault()
      focusWithoutScroll(dialog)
      return
    }

    const active = document.activeElement
    const first = focusable[0]!
    const last = focusable.at(-1)!
    if (!dialog.contains(active)) {
      event.preventDefault()
      focusWithoutScroll(event.shiftKey ? last : first)
    } else if (event.shiftKey && active === first) {
      event.preventDefault()
      focusWithoutScroll(last)
    } else if (!event.shiftKey && active === last) {
      event.preventDefault()
      focusWithoutScroll(first)
    }
  }

  function activate() {
    if (modalStack.includes(entry)) return
    const active = document.activeElement
    entry.opener = active instanceof HTMLElement && active !== document.body ? active : null
    modalStack.push(entry)
    if (modalStack.length === 1) lockDocumentScroll()
    document.addEventListener('keydown', handleKeydown)
    void nextTick(() => {
      if (modalStack.at(-1) === entry) focusInitial(entry)
    })
  }

  function deactivate(restore = true) {
    const index = modalStack.indexOf(entry)
    if (index < 0) return
    const wasTopmost = index === modalStack.length - 1
    modalStack.splice(index, 1)
    document.removeEventListener('keydown', handleKeydown)
    if (!modalStack.length) unlockDocumentScroll()
    if (!restore || !wasTopmost) return

    void nextTick(() => {
      const activeModal = modalStack.at(-1)
      const target = [entry.restoreFocus?.(), entry.opener].find(candidate => candidate
        && isAvailable(candidate)
        && (!activeModal || activeModal.dialog.value?.contains(candidate)))
      if (target) {
        focusWithoutScroll(target)
        return
      }
      if (activeModal) focusInitial(activeModal)
    })
  }

  const stopWatching = watch(options.open, open => {
    if (open) activate()
    else deactivate()
  }, { immediate: true, flush: 'post' })

  onBeforeUnmount(() => {
    stopWatching()
    deactivate()
  })
}

function focusInitial(entry: ModalEntry) {
  const dialog = entry.dialog.value
  if (!dialog || !isAvailable(dialog)) return
  const requested = entry.initialFocus?.()
  const marked = dialog.querySelector<HTMLElement>('[data-modal-initial-focus]')
  let target = requested && isFocusable(requested) ? requested : null
  if (!target && marked && isFocusable(marked)) target = marked
  target ??= focusableElements(dialog)[0] ?? dialog
  focusWithoutScroll(target)
}

function focusableElements(container: HTMLElement) {
  return [...container.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR)].filter(isFocusable)
}

function isFocusable(element: HTMLElement) {
  return element.tabIndex >= 0 && !element.matches(':disabled') && isAvailable(element)
}

function isAvailable(element: HTMLElement) {
  if (!element.isConnected || element.closest('[hidden], [inert], [aria-hidden="true"]')) return false
  let current: HTMLElement | null = element
  while (current) {
    const style = getComputedStyle(current)
    if (style.display === 'none' || style.visibility === 'hidden') return false
    current = current.parentElement
  }
  return true
}

function focusWithoutScroll(element: HTMLElement) {
  try {
    element.focus({ preventScroll: true })
  } catch {
    element.focus()
  }
}

function lockDocumentScroll() {
  const root = document.documentElement
  const body = document.body
  scrollLockSnapshot = {
    bodyOverflow: body.style.overflow,
    bodyPaddingRight: body.style.paddingRight,
    rootOverflow: root.style.overflow,
  }
  const scrollbarWidth = root.clientWidth > 0 ? Math.max(0, window.innerWidth - root.clientWidth) : 0
  if (scrollbarWidth) {
    const currentPadding = Number.parseFloat(getComputedStyle(body).paddingRight) || 0
    body.style.paddingRight = `${currentPadding + scrollbarWidth}px`
  }
  root.classList.add('modal-scroll-locked')
  root.style.overflow = 'hidden'
  body.style.overflow = 'hidden'
}

function unlockDocumentScroll() {
  const snapshot = scrollLockSnapshot
  if (!snapshot) return
  document.documentElement.classList.remove('modal-scroll-locked')
  document.documentElement.style.overflow = snapshot.rootOverflow
  document.body.style.overflow = snapshot.bodyOverflow
  document.body.style.paddingRight = snapshot.bodyPaddingRight
  scrollLockSnapshot = null
}
