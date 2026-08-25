import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import ProgressiveCatalogCover from './ProgressiveCatalogCover.vue'

describe('ProgressiveCatalogCover', () => {
  it('requests the thumbnail first and mounts the decorative display only after it loads', async () => {
    const wrapper = mount(ProgressiveCatalogCover, {
      props: { bggId: 42, alt: 'Catalog Game cover' },
    })

    const thumbnail = wrapper.get('[data-cover-kind="thumbnail"]')
    expect(thumbnail.attributes('src')).toBe('/api/v1/bgg/catalog/covers/42/thumbnail')
    expect(thumbnail.attributes('alt')).toBe('Catalog Game cover')
    expect(wrapper.find('[data-cover-kind="display"]').exists()).toBe(false)

    await thumbnail.trigger('load')

    const display = wrapper.get('[data-cover-kind="display"]')
    expect(display.attributes('src')).toBe('/api/v1/bgg/catalog/covers/42/image')
    expect(display.attributes('alt')).toBe('')
    expect(display.attributes('aria-hidden')).toBe('true')
    expect(wrapper.findAll('img[alt="Catalog Game cover"]')).toHaveLength(1)
  })

  it('preserves the loaded thumbnail when the display image fails', async () => {
    const wrapper = mount(ProgressiveCatalogCover, {
      props: { bggId: 42, alt: 'Catalog Game cover' },
    })

    await wrapper.get('[data-cover-kind="thumbnail"]').trigger('load')
    await wrapper.get('[data-cover-kind="display"]').trigger('error')

    expect(wrapper.find('[data-cover-kind="display"]').exists()).toBe(false)
    expect(wrapper.get('[data-cover-kind="thumbnail"]').attributes('src'))
      .toBe('/api/v1/bgg/catalog/covers/42/thumbnail')
  })

  it('retries a failed thumbnail once before showing an accessible placeholder', async () => {
    const wrapper = mount(ProgressiveCatalogCover, {
      props: { bggId: 42, alt: 'Catalog Game cover' },
    })

    await wrapper.get('[data-cover-kind="thumbnail"]').trigger('error')

    expect(wrapper.get('[data-cover-kind="thumbnail"]').attributes('src'))
      .toBe('/api/v1/bgg/catalog/covers/42/thumbnail?retry=1')
    expect(wrapper.find('[data-cover-kind="placeholder"]').exists()).toBe(false)

    await wrapper.get('[data-cover-kind="thumbnail"]').trigger('error')

    expect(wrapper.find('[data-cover-kind="thumbnail"]').exists()).toBe(false)
    const placeholder = wrapper.get('[data-cover-kind="placeholder"]')
    expect(placeholder.attributes('role')).toBe('img')
    expect(placeholder.attributes('aria-label')).toBe('Catalog Game cover')
  })

  it('fences late image events when the bggId changes', async () => {
    const wrapper = mount(ProgressiveCatalogCover, {
      props: { bggId: 42, alt: 'Old Game cover' },
    })
    const oldThumbnail = wrapper.get('[data-cover-kind="thumbnail"]').element

    await wrapper.setProps({ bggId: 43, alt: 'New Game cover' })
    oldThumbnail.dispatchEvent(new Event('load'))
    oldThumbnail.dispatchEvent(new Event('error'))
    await wrapper.vm.$nextTick()

    const currentThumbnail = wrapper.get('[data-cover-kind="thumbnail"]')
    expect(currentThumbnail.attributes('src')).toBe('/api/v1/bgg/catalog/covers/43/thumbnail')
    expect(currentThumbnail.attributes('alt')).toBe('New Game cover')
    expect(wrapper.find('[data-cover-kind="display"]').exists()).toBe(false)

    await currentThumbnail.trigger('load')
    expect(wrapper.get('[data-cover-kind="display"]').attributes('src'))
      .toBe('/api/v1/bgg/catalog/covers/43/image')
  })

  it('supports a lazy compact-only card without requesting the display image', async () => {
    const wrapper = mount(ProgressiveCatalogCover, {
      props: {
        bggId: 42,
        alt: 'Catalog Game cover',
        upgrade: false,
        loading: 'lazy',
        fetchPriority: 'auto',
      },
    })

    const thumbnail = wrapper.get('[data-cover-kind="thumbnail"]')
    expect(thumbnail.attributes('loading')).toBe('lazy')
    expect(thumbnail.attributes('fetchpriority')).toBe('auto')
    await thumbnail.trigger('load')
    expect(wrapper.find('[data-cover-kind="display"]').exists()).toBe(false)
  })
})
