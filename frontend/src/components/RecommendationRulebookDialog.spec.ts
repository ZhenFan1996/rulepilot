import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'

import RecommendationRulebookDialog from './RecommendationRulebookDialog.vue'

describe('RecommendationRulebookDialog', () => {
  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('opens downloaded pages in place while the generated guide continues in the background', async () => {
    const fetchMock = vi.fn().mockResolvedValue(Response.json([
      { pageNumber: 1, text: 'Setup', characterCount: 1200 },
      { pageNumber: 2, text: 'Turn order', characterCount: 900 },
    ]))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(RecommendationRulebookDialog, {
      props: { open: true, versionId: 'document-1', title: '展翅翱翔' },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('你可以先阅读原规则书')
    expect(wrapper.text()).toContain('讲解仍在后台生成')
    expect(wrapper.get('img').attributes('src')).toBe('/api/v1/document-versions/document-1/pages/1/image')
    await wrapper.findAll('button').find(button => button.text().includes('第 2 页'))!.trigger('click')
    expect(wrapper.get('img').attributes('src')).toBe('/api/v1/document-versions/document-1/pages/2/image')
    expect(fetchMock).toHaveBeenCalledWith('/api/v1/document-versions/document-1/pages', { credentials: 'include' })

    await wrapper.get('button[aria-label="关闭规则书"]').trigger('click')
    expect(wrapper.emitted('close')).toHaveLength(1)
  })
})
