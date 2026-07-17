<script setup lang="ts">
import { buttonVariants, type ButtonSize, type ButtonVariant } from '@/lib/variants'

withDefaults(
  defineProps<{
    variant?: ButtonVariant
    size?: ButtonSize
    type?: 'button' | 'submit' | 'reset'
  }>(),
  {
    variant: 'primary',
    size: 'md',
    type: 'button',
  },
)
</script>

<template>
  <button :type="type" :class="buttonVariants({ variant, size })">
    <slot />
  </button>
</template>
