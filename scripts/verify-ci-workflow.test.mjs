import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const ciWorkflow = await readFile(new URL('../.github/workflows/ci.yml', import.meta.url), 'utf8')
const deploymentWorkflow = await readFile(
  new URL('../.github/workflows/deploy-production.yml', import.meta.url),
  'utf8',
)
const playwrightConfig = await readFile(new URL('../frontend/playwright.config.ts', import.meta.url), 'utf8')

test('E2E CI uses a Node 24 artifact action and produces the uploaded HTML report', () => {
  assert.match(ciWorkflow, /uses:\s*actions\/upload-artifact@v(?:6|7)\b/)
  assert.match(ciWorkflow, /path:\s*frontend\/playwright-report\//)
  assert.match(ciWorkflow, /if-no-files-found:\s*error/)
  assert.match(playwrightConfig, /\['html',\s*\{\s*outputFolder:\s*'playwright-report',\s*open:\s*'never'\s*\}\]/)
})

test('E2E CI cannot regress to the deprecated Node 20 artifact action', () => {
  assert.doesNotMatch(ciWorkflow, /uses:\s*actions\/upload-artifact@v[1-5]\b/)
})

test('production deployment synchronizes the protected BGG credential without packaging or logging it', () => {
  assert.match(deploymentWorkflow, /name: Synchronize protected BGG credential/)
  assert.match(deploymentWorkflow, /BGG_API_TOKEN: \$\{\{ secrets\.BGG_API_TOKEN \}\}/)
  assert.match(deploymentWorkflow, /--exclude=\.env/)
  assert.match(deploymentWorkflow, /--exclude='\.env\.\*'/)
  assert.match(deploymentWorkflow, /printf '%s' "\$BGG_API_TOKEN" > "\$local_token_file"/)
  assert.match(deploymentWorkflow, /mv "\$temporary_env" "\$env_file"/)
  assert.doesNotMatch(deploymentWorkflow, /echo "\$BGG_API_TOKEN"/)
  assert.doesNotMatch(deploymentWorkflow, /'bash -s' -- "\$DEPLOY_PATH" "\$BGG_API_TOKEN"/)
})

test('production deployment verifies live BGG recommendations and detail enrichment', () => {
  assert.match(deploymentWorkflow, /\/api\/v1\/bgg\/recommendations/)
  assert.match(deploymentWorkflow, /\/api\/v1\/bgg\/games\/\$\{firstGame\.bggId\}/)
  assert.match(deploymentWorkflow, /Array\.isArray\(game\.categories\)/)
  assert.match(deploymentWorkflow, /Array\.isArray\(game\.mechanics\)/)
})

test('production deployment reclaims only inactive releases and restores current services on failure', () => {
  assert.match(deploymentWorkflow, /current_release=\$\(readlink -f "\$\{application_root\}\/current"/)
  assert.match(deploymentWorkflow, /\[\[ "\$candidate_path" == "\$current_release" \]\] && continue/)
  assert.match(deploymentWorkflow, /\[\[ "\$candidate_path" == "\$release_dir" \]\] && continue/)
  assert.match(deploymentWorkflow, /"\$candidate_path" != "\$\{releases_root\}\/"\*/)
  assert.match(deploymentWorkflow, /rm -rf -- "\$candidate_path"/)
  assert.match(deploymentWorkflow, /Restoring API and worker from the current release after failed activation/)
  assert.match(deploymentWorkflow, /\.yml up -d api worker/)
  assert.doesNotMatch(deploymentWorkflow, /docker volume (?:prune|rm)/)
})
