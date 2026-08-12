import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it } from 'vitest'

import { setLocale } from '@/lib/locale'
import ConversationResetDialog from './ConversationResetDialog.vue'

describe('ConversationResetDialog', () => {
  afterEach(() => {
    setLocale('zh-CN')
    document.body.innerHTML = ''
  })

  it('states the unrecoverable browser-only scope without claiming saved resources are deleted', () => {
    const wrapper = mount(ConversationResetDialog, {
      attachTo: document.body,
      props: { kind: 'private-browser', open: true, turnCount: 3 },
    })

    expect(document.body.textContent).toContain('当前浏览器会话中的 3 条问答会被移除')
    expect(document.body.textContent).toContain('规则书、讲解和已保存的裁决不会删除')
    expect(document.body.textContent).toContain('尚未发送的问题会保留')
    wrapper.unmount()
  })

  it('distinguishes a new server session from deletion and exposes retry copy in English', () => {
    setLocale('en')
    const wrapper = mount(ConversationResetDialog, {
      attachTo: document.body,
      props: {
        error: 'The new session could not be created.',
        gameTitle: 'Wingspan',
        kind: 'server-session',
        open: true,
        turnCount: 2,
      },
    })

    expect(document.body.textContent).toContain('Start a new Q&A for Wingspan?')
    expect(document.body.textContent).toContain('will not be deleted from the server')
    expect(document.body.textContent).toContain('this page has no way back')
    expect(document.body.textContent).toContain('Try creating it again')
    wrapper.unmount()
  })

  it('keeps recommendation background work outside the reset consequence', () => {
    const wrapper = mount(ConversationResetDialog, {
      attachTo: document.body,
      props: { kind: 'recommendation', open: true },
    })

    expect(document.body.textContent).toContain('后台任务都不会删除或停止')
    expect(document.body.textContent).toContain('服务器中的答疑记录')
    expect(document.body.textContent).toContain('推荐输入框里尚未发送的文字会保留')
    wrapper.unmount()
  })
})
