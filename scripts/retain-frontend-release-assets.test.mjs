import assert from 'node:assert/strict'
import { execFileSync, spawnSync } from 'node:child_process'
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import test from 'node:test'

const script = new URL('./retain-frontend-release-assets.sh', import.meta.url).pathname

function withAssetDirectories(run) {
  const root = mkdtempSync(join(tmpdir(), 'rulepilot-retained-assets-'))
  const destination = join(root, 'current')
  const previous = join(root, 'previous')
  mkdirSync(destination)
  mkdirSync(previous)
  try {
    run({ destination, previous })
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
}

test('retains missing hashed assets without overwriting the current release', () => {
  withAssetDirectories(({ destination, previous }) => {
    writeFileSync(join(destination, 'route-current.js'), 'current')
    writeFileSync(join(previous, 'route-current.js'), 'stale')
    writeFileSync(join(previous, 'route-previous.js'), 'previous')

    execFileSync('bash', [script, destination, previous])

    assert.equal(readFileSync(join(destination, 'route-current.js'), 'utf8'), 'current')
    assert.equal(readFileSync(join(destination, 'route-previous.js'), 'utf8'), 'previous')
  })
})

test('rejects an unsafe asset name', () => {
  withAssetDirectories(({ destination, previous }) => {
    writeFileSync(join(previous, 'unsafe asset.js'), 'unsafe')

    const result = spawnSync('bash', [script, destination, previous], { encoding: 'utf8' })

    assert.notEqual(result.status, 0)
    assert.match(result.stderr, /Refusing unsafe previous frontend asset name/)
  })
})
