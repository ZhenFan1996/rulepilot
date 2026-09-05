import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import PlayerWorkStatusText from './PlayerWorkStatusText.vue'
import { playerWorkStatus } from '@/lib/playerWorkStatus'

describe('PlayerWorkStatusText', () => {
  it('renders the shared label and all orthogonal work facts on the requested element', () => {
    const status = playerWorkStatus('GUIDE_READABLE', {
      capability: 'guide', readiness: 'usable', terminality: 'terminal', outcome: 'needs-action',
    }, 'zh-CN')
    const wrapper = mount(PlayerWorkStatusText, {
      props: { status, as: 'strong' },
      attrs: { class: 'player-status', role: 'status' },
    })

    expect(wrapper.element.tagName).toBe('STRONG')
    expect(wrapper.text()).toBe('已有章节可读')
    expect(wrapper.attributes()).toMatchObject({
      'data-player-work-stage': 'GUIDE_READABLE',
      'data-player-work-capability': 'guide',
      'data-player-work-readiness': 'usable',
      'data-player-work-terminality': 'terminal',
      'data-player-work-outcome': 'needs-action',
      class: 'player-status',
      role: 'status',
    })
  })
})
