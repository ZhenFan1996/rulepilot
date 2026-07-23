<script setup lang="ts">
withDefaults(defineProps<{
  name: string
  size?: number
}>(), { size: 24 })
</script>

<template>
  <svg
    :width="size"
    :height="size"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="1.8"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
  >
    <template v-if="name === 'rulebook'">
      <path d="M4.5 5.5a3 3 0 0 1 3-3H20v17.5H7.5a3 3 0 0 0-3 3Z" />
      <path d="M4.5 5.5v15.5a3 3 0 0 1 3-3H20" />
      <path d="M10 7h6M10 11h6" />
    </template>
    <template v-else-if="name === 'meeple'">
      <circle cx="12" cy="5.5" r="2.3" />
      <path d="M9.8 8.2 7 11.3l1.6 2.1-2.5 5.7h3.4l1.1-3.2h2.8l1.1 3.2h3.4l-2.5-5.7 1.6-2.1-2.8-3.1" />
    </template>
    <template v-else-if="name === 'cards'">
      <rect x="4" y="5" width="12" height="15" rx="2" />
      <path d="M8 3h10a2 2 0 0 1 2 2v12" />
      <path d="m8 10 2 2 3-4 2 2" />
    </template>
    <template v-else-if="name === 'dice'">
      <rect x="4" y="4" width="16" height="16" rx="3" />
      <path d="M8 8h.01M16 8h.01M12 12h.01M8 16h.01M16 16h.01" stroke-width="3" />
    </template>
    <template v-else-if="name === 'players'">
      <circle cx="9" cy="8" r="3" />
      <circle cx="17" cy="9" r="2" />
      <path d="M3.5 20a5.5 5.5 0 0 1 11 0M15 15.5a4.3 4.3 0 0 1 5.5 4.1" />
    </template>
    <template v-else-if="name === 'timer'">
      <circle cx="12" cy="13" r="7" />
      <path d="M12 9v4l2.7 1.6M9 3h6M12 3v3" />
    </template>
    <template v-else-if="name === 'compass'">
      <circle cx="12" cy="12" r="8" />
      <path d="m15.5 8.5-2.2 4.8-4.8 2.2 2.2-4.8Z" />
    </template>
    <template v-else-if="name === 'spark'">
      <path d="m12 2 1.5 6.5L20 10l-6.5 1.5L12 18l-1.5-6.5L4 10l6.5-1.5Z" />
    </template>
    <template v-else-if="name === 'plus'">
      <path d="M12 5v14M5 12h14" />
    </template>
    <template v-else-if="name === 'arrow'">
      <path d="M5 12h13M13 6l6 6-6 6" />
    </template>
    <template v-else-if="name === 'library'">
      <path d="M5 4.5h4v15H5zM10 4.5h4v15h-4zM15 4.5h4v15h-4z" />
      <path d="M5 8h4M10 8h4M15 8h4" />
    </template>
    <template v-else>
      <circle cx="12" cy="12" r="8" />
    </template>
  </svg>
</template>
