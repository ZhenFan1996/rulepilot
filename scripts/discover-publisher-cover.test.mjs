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

test('uses a publisher product page Open Graph cover only when its title identifies the game', () => {
  const productPage = `
    <meta property="og:title" content="Fort Card Game | Leder Games">
    <meta property="og:image" content="https://cdn.shopify.com/s/files/1/0000/fort-box.png?v=1">
  `

  assert.deepEqual(selectPublisherCover(productPage, 'https://ledergames.com/products/fort', 'Fort'), {
    url: 'https://cdn.shopify.com/s/files/1/0000/fort-box.png?v=1', score: 91, tokenMatches: 1,
  })

  const unrelatedPage = productPage.replace('Fort Card Game', 'Root Card Game')
  assert.equal(selectPublisherCover(unrelatedPage, 'https://ledergames.com/products/fort', 'Fort'), null)
})

test('rejects a tiny publisher social image even when the page title matches the game', () => {
  const componentPage = `
    <meta property="og:title" content="Dice City | AEG">
    <meta property="og:image" content="https://cdn.publisher.example/die.png?resize=100%2C105">
  `

  assert.equal(selectPublisherCover(componentPage, 'https://www.publisher.example/dice-city/', 'Dice City'), null)
})

test('parses the bounded command interface', () => {
  assert.deepEqual(parseArguments(['--source', 'https://www.publisher.example/calico/', '--title', 'Calico']), {
    source: 'https://www.publisher.example/calico/', title: 'Calico',
  })
  assert.throws(() => parseArguments(['--unknown']), /Unknown argument/)
})
