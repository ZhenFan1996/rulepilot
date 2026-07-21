import assert from 'node:assert/strict'
import test from 'node:test'
import { mkdtemp, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { preflightPublicRulebook } from './preflight-public-rulebook.mjs'

async function fixture(content) {
  const directory = await mkdtemp(join(tmpdir(), 'rulepilot-public-rulebook-'))
  const path = join(directory, 'rulebook.pdf')
  await writeFile(path, content)
  return path
}

const publisher = {
  sourceUrl: 'https://publisher.example/rules.pdf',
  coverUrl: 'https://publisher.example/cover.png',
}

test('accepts a complete publisher PDF and reports a stable digest', async () => {
  const path = await fixture(Buffer.concat([
    Buffer.from('%PDF-1.7\n1 0 obj << /Type /Page >> endobj\n'),
    Buffer.alloc(100_000, 1),
    Buffer.from('\n%%EOF\n'),
  ]))

  const report = await preflightPublicRulebook({ pdfPath: path, ...publisher })

  assert.equal(report.detectedPageObjects, 1)
  assert.equal(report.officialSourceUrl, publisher.sourceUrl)
  assert.equal(report.officialCoverUrl, publisher.coverUrl)
  assert.match(report.sha256, /^[a-f0-9]{64}$/)
})

test('rejects a partial PDF even when its header and page object exist', async () => {
  const path = await fixture(Buffer.concat([
    Buffer.from('%PDF-1.7\n1 0 obj << /Type /Page >> endobj\n'),
    Buffer.alloc(100_000, 1),
  ]))

  await assert.rejects(
    () => preflightPublicRulebook({ pdfPath: path, ...publisher }),
    /missing final %%EOF marker/,
  )
})

test('rejects a non-HTTPS official source', async () => {
  const path = await fixture(Buffer.concat([
    Buffer.from('%PDF-1.7\n1 0 obj << /Type /Page >> endobj\n'),
    Buffer.alloc(100_000, 1),
    Buffer.from('\n%%EOF\n'),
  ]))

  await assert.rejects(
    () => preflightPublicRulebook({ pdfPath: path, sourceUrl: 'http://publisher.example/rules.pdf', coverUrl: publisher.coverUrl }),
    /HTTPS URL/,
  )
})
