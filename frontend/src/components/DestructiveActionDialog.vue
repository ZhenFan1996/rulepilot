<script setup lang="ts">
import { computed, ref, useId } from 'vue'

import { useModalFocus } from '@/composables/useModalFocus'

const props = withDefaults(defineProps<{
  cancelLabel: string
  confirmLabel: string
  description: string
  error?: string
  open: boolean
  pending?: boolean
  pendingLabel: string
  retryLabel?: string
  restoreFocus?: () => HTMLElement | null
  title: string
}>(), {
  error: '',
  pending: false,
  restoreFocus: undefined,
  retryLabel: '',
})

const emit = defineEmits<{
  cancel: []
  confirm: []
}>()

const dialog = ref<HTMLElement | null>(null)
const titleId = useId()
const descriptionId = useId()
const actionLabel = computed(() => props.error && props.retryLabel ? props.retryLabel : props.confirmLabel)

useModalFocus({
  dialog,
  open: () => props.open,
  requestClose: requestCancel,
  restoreFocus: () => props.restoreFocus?.() ?? null,
})

function requestCancel() {
  if (!props.pending) emit('cancel')
}

function requestConfirm() {
  if (!props.pending) emit('confirm')
}
</script>

<template>
  <Teleport to="body">
    <div
      v-if="open"
      class="fixed inset-0 z-[100] grid place-items-center bg-ink/45 p-6 backdrop-blur-[2px]"
      @click.self="requestCancel"
    >
      <section
        ref="dialog"
        tabindex="-1"
        class="w-full max-w-md rounded-3xl bg-paper p-6 text-ink elevation-2xl outline-none"
        role="alertdialog"
        aria-modal="true"
        :aria-labelledby="titleId"
        :aria-describedby="descriptionId"
      >
        <div class="min-w-0">
          <h2 :id="titleId" class="font-display text-2xl font-semibold text-red-700">{{ title }}</h2>
          <p :id="descriptionId" class="mt-2 text-sm leading-6 text-ink/60">{{ description }}</p>
        </div>

        <p v-if="error" class="mt-5 rounded-xl bg-red-50 px-4 py-3 text-sm leading-6 text-red-800" role="alert">
          {{ error }}
        </p>

        <div class="mt-6 flex flex-wrap justify-end gap-3">
          <button
            type="button"
            data-modal-initial-focus
            :disabled="pending"
            class="min-h-11 rounded-xl border border-ink/15 px-5 py-3 text-sm font-semibold text-ink/70 hover:border-ink/35 disabled:opacity-50"
            @click="requestCancel"
          >
            {{ cancelLabel }}
          </button>
          <button
            type="button"
            :disabled="pending"
            :aria-busy="pending"
            class="min-h-11 rounded-xl bg-copper px-5 py-3 text-sm font-semibold text-on-accent disabled:opacity-50"
            @click="requestConfirm"
          >
            {{ pending ? pendingLabel : actionLabel }}
          </button>
        </div>
      </section>
    </div>
  </Teleport>
</template>
