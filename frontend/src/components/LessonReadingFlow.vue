<script setup lang="ts">
import LessonVisualEvidence from '@/components/LessonVisualEvidence.vue'
import type { LessonReadingBlock, VisualFocus } from '@/composables/lessonSupportingContent'

const props = defineProps<{
  blocks: LessonReadingBlock[]
  narrationId: string
  openPage: (page: number) => void
  pageImageUrl: (page: number) => string
  focusedPageImageUrl: (focus: VisualFocus) => string
}>()
</script>

<template>
  <div class="lesson-reading-flow text-base leading-8 text-ink sm:text-[1.0625rem]" data-testid="lesson-authored-flow">
    <template v-for="(block, index) in props.blocks" :key="index">
      <p v-if="block.kind === 'PARAGRAPH'" class="reading-paragraph whitespace-pre-line">{{ block.text }}</p>
      <ul v-else class="reading-figures" :class="block.flow === 'ASIDE' ? 'reading-aside' : 'reading-wide'">
        <li v-for="(figure, figureIndex) in block.figures" :key="figureIndex" class="min-w-0">
          <LessonVisualEvidence
            :focus="figure.focus"
            :caption="figure.caption"
            :ordinal="figureIndex + 1"
            :total="block.figures.length"
            :narration-id="narrationId"
            :open-page="openPage"
            :page-image-url="pageImageUrl"
            :focused-page-image-url="focusedPageImageUrl"
          />
        </li>
      </ul>
    </template>
  </div>
</template>

<style scoped>
.lesson-reading-flow {
  display: flow-root;
  container-type: inline-size;
  max-width: 70ch;
}
.lesson-reading-flow:lang(en) {
  max-width: 65ch;
  line-height: 1.7;
}
.reading-paragraph {
  margin-block: 0 1em;
}
.reading-figures {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 16rem), 1fr));
  align-items: start;
  gap: 1.5rem;
  margin-block: 1.25rem;
}
.reading-wide {
  clear: both;
}
@container (min-width: 36rem) {
  .reading-aside {
    clear: both;
    float: inline-end;
    width: 42%;
    margin-block: 0.25rem 1.25rem;
    margin-inline-start: 2rem;
  }
}
</style>
