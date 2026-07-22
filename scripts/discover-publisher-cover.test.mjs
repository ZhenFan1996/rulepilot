import test from 'node:test'
import assert from 'node:assert/strict'
import { discoverCoverCandidates, parseArguments, selectPublisherCover } from './discover-publisher-cover.mjs'

const page = `
  <meta property="og:image" content="https://www.publisher.example/assets/logo.png">
  <img src="/assets/evergreen_tag.png" alt="Evergreen tag">
  <img src="https://www.publisher.example/uploads/calico_board_game_box.png" alt="Calico board game box">
  <img src="https://cdn.other.example/calico-cover.png" alt="Calico cover">
`

test('selects a title-matching publisher box image and rejects generic or third-party images', () => {
  assert.deepEqual(selectPublisherCover(page, 'https://www.publisher.example/calico/', 'Calico'), {
    url: 'https://www.publisher.example/uploads/calico_board_game_box.png', score: 45, tokenMatches: 1,
  })
  assert.equal(discoverCoverCandidates(page, 'https://www.publisher.example/calico/', 'Calico').length, 1)
})

test('parses the bounded command interface', () => {
  assert.deepEqual(parseArguments(['--source', 'https://www.publisher.example/calico/', '--title', 'Calico']), {
    source: 'https://www.publisher.example/calico/', title: 'Calico',
  })
  assert.throws(() => parseArguments(['--unknown']), /Unknown argument/)
})
