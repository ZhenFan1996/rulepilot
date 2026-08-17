<script setup lang="ts">
import { computed } from 'vue'
import MarkdownIt from 'markdown-it'

const props = defineProps<{ source: string }>()

const markdown = new MarkdownIt({
  breaks: true,
  html: false,
  linkify: true,
  typographer: false,
})

const defaultLinkValidator = markdown.validateLink.bind(markdown)
markdown.validateLink = (href: string) => {
  if (!defaultLinkValidator(href)) return false
  try {
    const destination = new URL(href, 'https://rulepilot.invalid')
    return destination.protocol === 'http:'
      || destination.protocol === 'https:'
      || destination.protocol === 'mailto:'
  } catch {
    return false
  }
}

markdown.renderer.rules.link_open = (tokens, index, options, _environment, renderer) => {
  const token = tokens[index]!
  const href = token.attrGet('href') ?? ''
  const destination = new URL(href, 'https://rulepilot.invalid')
  if (destination.origin !== 'https://rulepilot.invalid') {
    token.attrSet('target', '_blank')
    token.attrSet('rel', 'noopener noreferrer nofollow')
  }
  return renderer.renderToken(tokens, index, options)
}
markdown.renderer.rules.image = (tokens, index) => markdown.utils.escapeHtml(tokens[index]?.content ?? '')

const rendered = computed(() => markdown.render(props.source))
</script>

<template>
  <!-- markdown-it emits an allow-listed element grammar here; raw HTML is disabled and links are protocol-checked. -->
  <!-- eslint-disable-next-line vue/no-v-html -->
  <div class="safe-markdown" v-html="rendered" />
</template>

<style scoped>
.safe-markdown {
  min-width: 0;
  overflow-wrap: anywhere;
}

.safe-markdown :deep(p + p),
.safe-markdown :deep(p + ul),
.safe-markdown :deep(p + ol),
.safe-markdown :deep(ul + p),
.safe-markdown :deep(ol + p) {
  margin-top: 0.55em;
}

.safe-markdown :deep(ul),
.safe-markdown :deep(ol) {
  padding-left: 1.25rem;
}

.safe-markdown :deep(ul) {
  list-style: disc;
}

.safe-markdown :deep(ol) {
  list-style: decimal;
}

.safe-markdown :deep(li + li) {
  margin-top: 0.25em;
}

.safe-markdown :deep(strong) {
  font-weight: 700;
  color: inherit;
}

.safe-markdown :deep(em) {
  font-style: italic;
}

.safe-markdown :deep(a) {
  color: var(--color-indigo);
  font-weight: 600;
  text-decoration: underline;
  text-decoration-color: color-mix(in srgb, var(--color-indigo) 32%, transparent);
  text-underline-offset: 0.18em;
}

.safe-markdown :deep(code) {
  border-radius: 0.25rem;
  background: color-mix(in srgb, var(--color-ink) 7%, transparent);
  padding: 0.08rem 0.25rem;
  font-size: 0.92em;
}

.safe-markdown :deep(pre) {
  margin-top: 0.55em;
  overflow-x: auto;
  border-radius: 0.5rem;
  background: color-mix(in srgb, var(--color-ink) 7%, transparent);
  padding: 0.65rem;
}

.safe-markdown :deep(pre code) {
  background: transparent;
  padding: 0;
}
</style>
