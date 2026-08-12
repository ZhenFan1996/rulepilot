<script setup lang="ts">
import { computed } from 'vue'

import DestructiveActionDialog from '@/components/DestructiveActionDialog.vue'
import { useLocale } from '@/lib/locale'

const props = withDefaults(defineProps<{
  error?: string
  gameTitle?: string
  kind: 'private-browser' | 'public-browser' | 'recommendation' | 'server-session'
  open: boolean
  pending?: boolean
  restoreFocus?: () => HTMLElement | null
  turnCount?: number
}>(), {
  error: '',
  gameTitle: '',
  pending: false,
  restoreFocus: undefined,
  turnCount: 0,
})

const emit = defineEmits<{
  cancel: []
  confirm: []
}>()

const { locale } = useLocale()

const copy = computed(() => {
  const turns = Math.max(0, props.turnCount)
  if (locale.value === 'zh-CN') {
    if (props.kind === 'private-browser') return {
      title: '清空这次答疑？',
      description: `当前浏览器会话中的 ${turns} 条问答会被移除，之后无法找回。规则书、讲解和已保存的裁决不会删除；输入框里尚未发送的问题会保留。`,
      cancel: '保留答疑', confirm: '清空答疑', pending: '正在清空…', retry: '重新尝试清空',
    }
    if (props.kind === 'public-browser') return {
      title: '清空这次公开答疑？',
      description: `这台浏览器当前会话中的 ${turns} 条问答会被移除，服务器没有可供恢复的副本。公开讲解不会改变；输入框里尚未发送的问题会保留。`,
      cancel: '保留答疑', confirm: '清空答疑', pending: '正在清空…', retry: '重新尝试清空',
    }
    if (props.kind === 'recommendation') return {
      title: '重新开始推荐对话？',
      description: '本次推荐对话、偏好、候选和已选桌游的页面上下文会被清空。当前规则答疑界面及其中尚未发送的问题也会关闭；服务器中的答疑记录、已经加入“我的游戏”的内容，以及已经开始的规则书或讲解后台任务都不会删除或停止。推荐输入框里尚未发送的文字会保留。',
      cancel: '继续当前对话', confirm: '重新开始', pending: '正在重新开始…', retry: '重新尝试',
    }
    return {
      title: `为《${props.gameTitle}》开始新的答疑？`,
      description: `将创建并切换到新的服务器答疑会话。原来的 ${turns} 条问答不会从服务器删除，但当前页面没有返回旧会话的入口。规则书、讲解和已保存的裁决不受影响；输入框里尚未发送的问题会保留。`,
      cancel: '继续当前答疑', confirm: '开始新答疑', pending: '正在建立新答疑…', retry: '重新尝试新建',
    }
  }

  const turnLabel = `${turns} ${turns === 1 ? 'turn' : 'turns'}`
  if (props.kind === 'private-browser') return {
    title: 'Clear this Q&A?',
    description: `${turnLabel} from this browser session will be removed and cannot be recovered. Your rulebook, guide, and saved rulings stay intact; an unsubmitted question in the input stays too.`,
    cancel: 'Keep Q&A', confirm: 'Clear Q&A', pending: 'Clearing…', retry: 'Try clearing again',
  }
  if (props.kind === 'public-browser') return {
    title: 'Clear this public Q&A?',
    description: `${turnLabel} from this browser session will be removed, and the server has no copy to restore. The public guide stays unchanged; an unsubmitted question in the input stays too.`,
    cancel: 'Keep Q&A', confirm: 'Clear Q&A', pending: 'Clearing…', retry: 'Try clearing again',
  }
  if (props.kind === 'recommendation') return {
    title: 'Start the recommendation conversation over?',
    description: 'This conversation, its preferences, candidates, and selected-game page context will be cleared. The current rules Q&A surface and any unsubmitted question there will close; server-side Q&A history, anything already added to My Games, and rulebook or guide work already running in the background will remain. Unsubmitted text in the recommendation input stays too.',
    cancel: 'Keep conversation', confirm: 'Start over', pending: 'Starting over…', retry: 'Try again',
  }
  return {
    title: `Start a new Q&A for ${props.gameTitle}?`,
    description: `A new server Q&A session will be created and opened. The previous ${turnLabel} will not be deleted from the server, but this page has no way back to that session. Your rulebook, guide, and saved rulings stay intact; an unsubmitted question in the input stays too.`,
    cancel: 'Keep current Q&A', confirm: 'Start new Q&A', pending: 'Starting new Q&A…', retry: 'Try creating it again',
  }
})
</script>

<template>
  <DestructiveActionDialog
    :open="open"
    :pending="pending"
    :error="error"
    :title="copy.title"
    :description="copy.description"
    :cancel-label="copy.cancel"
    :confirm-label="copy.confirm"
    :pending-label="copy.pending"
    :retry-label="copy.retry"
    :restore-focus="restoreFocus"
    @cancel="emit('cancel')"
    @confirm="emit('confirm')"
  />
</template>
