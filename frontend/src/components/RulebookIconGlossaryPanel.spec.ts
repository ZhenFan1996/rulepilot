import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it } from 'vitest'

import RulebookIconGlossaryPanel from './RulebookIconGlossaryPanel.vue'
import { setLocale } from '@/lib/locale'
import type { RulebookIconGlossary } from '@/lib/rulebookIconGlossary'

const readyGlossary: RulebookIconGlossary = {
  status: 'READY',
  totalPages: 12,
  inspectedPages: 12,
  completePages: 12,
  warnings: ['UNEXPLAINED_ICONS'],
  icons: [
    {
      id: 'icon-1',
      name: '行动图标',
      visualDescription: '白色手掌位于蓝色圆形内',
      explanation: '执行一次行动。',
      evidenceText: '行动：执行一次行动',
      meaningStatus: 'EXPLICIT',
      representativeOccurrenceId: 'occurrence-1',
      occurrences: [
        { id: 'occurrence-1', pageNumber: 3, x: 10, y: 20, width: 40, height: 50 },
        { id: 'occurrence-2', pageNumber: 8, x: 12, y: 24, width: 40, height: 50 },
      ],
    },
    {
      id: 'icon-2',
      name: '红色六边形图标',
      visualDescription: '红色六边形，中央有白点',
      explanation: null,
      evidenceText: null,
      meaningStatus: 'UNEXPLAINED',
      representativeOccurrenceId: 'occurrence-3',
      occurrences: [
        { id: 'occurrence-3', pageNumber: 5, x: 80, y: 20, width: 40, height: 40 },
      ],
    },
  ],
}

function mountPanel(glossary: RulebookIconGlossary | null = readyGlossary) {
  return mount(RulebookIconGlossaryPanel, {
    props: {
      glossary,
      loading: false,
      errorMessage: '',
      canGenerate: true,
      generating: false,
      online: true,
      imageUrl: (id: string) => `/icons/${id}`,
    },
  })
}

describe('RulebookIconGlossaryPanel', () => {
  afterEach(() => setLocale('zh-CN'))

  it('shows evidence-grounded meanings, unexplained icons, crops, and source pages', () => {
    const wrapper = mountPanel()

    expect(wrapper.text()).toContain('图标速查表')
    expect(wrapper.text()).toContain('执行一次行动。')
    expect(wrapper.text()).toContain('行动：执行一次行动')
    expect(wrapper.text()).toContain('规则书中没有找到与这个图标直接对应的解释')
    expect(wrapper.text()).toContain('第 3、8 页')
    expect(wrapper.get('img').attributes('src')).toBe('/icons/occurrence-1')
  })

  it('emits generation and retry intents without owning network behavior', async () => {
    const notStarted = { ...readyGlossary, status: 'NOT_STARTED' as const, icons: [], warnings: [] }
    const wrapper = mountPanel(notStarted)
    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('generate')).toEqual([[]])

    await wrapper.setProps({ errorMessage: 'temporary failure' })
    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('retry')).toEqual([[]])
  })

  it('renders progress, disabled offline action, and English labels', async () => {
    setLocale('en')
    const generating = {
      ...readyGlossary,
      status: 'GENERATING' as const,
      inspectedPages: 4,
      completePages: 3,
    }
    const wrapper = mountPanel(generating)
    await wrapper.setProps({ online: false, generating: true })

    expect(wrapper.text()).toContain('Icon quick reference')
    expect(wrapper.text()).toContain('3 complete · 4 inspected · 12 pages')
    expect(wrapper.text()).toContain('You can leave this page')
    expect(wrapper.get('button').attributes('disabled')).toBeDefined()
  })
})
