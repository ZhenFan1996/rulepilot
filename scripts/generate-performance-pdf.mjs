import { mkdir, writeFile } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'

const output = resolve(process.argv[2] ?? '.local/performance-rulebook.pdf')
const pages = Number.parseInt(process.argv[3] ?? '5', 10)

if (!Number.isInteger(pages) || pages < 1 || pages > 50) {
  throw new Error('page count must be between 1 and 50')
}

const objects = []
const pageObjectIds = []

objects[1] = '<< /Type /Catalog /Pages 2 0 R >>'
objects[3] = '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>'

for (let page = 1; page <= pages; page += 1) {
  const pageId = 4 + ((page - 1) * 2)
  const contentId = pageId + 1
  pageObjectIds.push(`${pageId} 0 R`)
  const lines = [
    `RulePilot performance rulebook page ${page}.`,
    'Setup: Give every player three coins and place the score board in the center.',
    'Turn: The active player takes one action, pays its cost, and resolves its effect.',
    'Scoring: Each remaining coin scores one point. The player with most points wins.',
    'Tie breaker: The tied player with the most remaining cards wins.',
  ]
  const commands = lines.map((line, index) =>
    `BT /F1 12 Tf 54 ${738 - (index * 24)} Td (${line}) Tj ET`).join('\n')
  objects[pageId] = `<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 3 0 R >> >> /Contents ${contentId} 0 R >>`
  objects[contentId] = `<< /Length ${Buffer.byteLength(commands, 'ascii')} >>\nstream\n${commands}\nendstream`
}

objects[2] = `<< /Type /Pages /Kids [${pageObjectIds.join(' ')}] /Count ${pages} >>`

let pdf = '%PDF-1.4\n%RulePilot benchmark\n'
const offsets = [0]
for (let id = 1; id < objects.length; id += 1) {
  offsets[id] = Buffer.byteLength(pdf, 'ascii')
  pdf += `${id} 0 obj\n${objects[id]}\nendobj\n`
}

const xrefOffset = Buffer.byteLength(pdf, 'ascii')
pdf += `xref\n0 ${objects.length}\n`
pdf += '0000000000 65535 f \n'
for (let id = 1; id < objects.length; id += 1) {
  pdf += `${String(offsets[id]).padStart(10, '0')} 00000 n \n`
}
pdf += `trailer\n<< /Size ${objects.length} /Root 1 0 R >>\nstartxref\n${xrefOffset}\n%%EOF\n`

await mkdir(dirname(output), { recursive: true })
await writeFile(output, pdf, { encoding: 'ascii' })
process.stdout.write(`${output}\n`)
