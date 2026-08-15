import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import RulebookStatusCard from './RulebookStatusCard.vue'
import type { OfficialImportCopy, OfficialRulebookImportJob } from './types'

const copy = {
  title: 'Preparing', safe: 'Safe to leave',
  QUEUED: 'Queued', CONNECTING: 'Connecting', DOWNLOADING: 'Downloading', COMPRESSING: 'Compressing',
  VERIFYING_FILE: 'Verifying', SAVING: 'Saving', COMPLETED: 'Completed', FAILED: 'Failed',
  WAITING_FOR_DOCUMENT: 'Reading', LAUNCHING: 'Launching', LAUNCHED: 'Launched',
  TEACHING_FAILED: 'Teaching failed', DOCUMENT_FAILED: 'Document failed', background: 'Background audit retained',
  failureTitle: 'Import needs attention',
  failureDetail: {
    TEMPORARY_SOURCE: 'The source is temporarily unavailable.',
    BROWSER_HANDOFF: 'Continue in the browser.',
    INVALID_SOURCE: 'The source was not a safe rulebook file.',
    CAPACITY: 'The queue is temporarily full.',
    INTERRUPTED: 'The app restarted during import.',
    OTHER: 'Choose another source.',
    NONE: 'Choose another source.',
  },
  chooseAnotherSource: 'Choose another source',
  useLocalUpload: 'Use local upload',
  retryOriginalSource: 'Retry original source',
} as OfficialImportCopy

function failedJob(retryable: boolean): OfficialRulebookImportJob {
  return {
    id: 'failed-job', title: 'Opaque Game', rulebookTitle: 'Opaque Rules', editionId: 'edition-1',
    editionName: 'First Edition', sourceDomain: 'publisher.example', sourceType: 'BASE_RULEBOOK',
    learningGoal: 'Teach setup first', stage: 'FAILED', downloadedBytes: 0, totalBytes: null,
    documentVersionId: null, duplicate: false, errorCode: retryable ? 'SOURCE_UNAVAILABLE' : 'INVALID_PDF_SOURCE',
    teachingHandoffState: 'FAILED', teachingPreparationRunId: null, teachingErrorCode: 'IMPORT_FAILED', reused: false,
    recovery: {
      state: 'FAILED', failureKind: retryable ? 'TEMPORARY_SOURCE' : 'INVALID_SOURCE', busy: false,
      canChooseAnotherSource: true, canUseLocalUpload: true,
      canRetryOriginalSource: retryable, canOpenSourceInBrowser: false,
    },
  }
}

describe('RulebookStatusCard import recovery', () => {
  it('turns a terminal failure into explicit source and local-upload actions', async () => {
    const wrapper = mount(RulebookStatusCard, {
      props: {
        officialImportJob: failedJob(true), officialImportCopy: copy, message: '', preparingVersionId: '',
        preparationElapsedLabel: '', errorMessage: '', processingVersionId: '', processingPercentage: 0,
      },
    })

    expect(wrapper.text()).toContain('Import needs attention')
    expect(wrapper.text()).toContain('The source is temporarily unavailable.')
    const buttons = wrapper.findAll('button')
    expect(buttons.map(button => button.text())).toEqual([
      'Choose another source', 'Use local upload', 'Retry original source',
    ])

    await buttons[0]!.trigger('click')
    await buttons[1]!.trigger('click')
    await buttons[2]!.trigger('click')
    expect(wrapper.emitted('choose-source')).toHaveLength(1)
    expect(wrapper.emitted('use-local-upload')).toHaveLength(1)
    expect(wrapper.emitted('retry-original')).toHaveLength(1)
  })

  it('does not offer an original-source retry for a safety rejection', () => {
    const wrapper = mount(RulebookStatusCard, {
      props: {
        officialImportJob: failedJob(false), officialImportCopy: copy, message: '', preparingVersionId: '',
        preparationElapsedLabel: '', errorMessage: '', processingVersionId: '', processingPercentage: 0,
      },
    })

    expect(wrapper.findAll('button').map(button => button.text())).toEqual([
      'Choose another source', 'Use local upload',
    ])
  })
})
