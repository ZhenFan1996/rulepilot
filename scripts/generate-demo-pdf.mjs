import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'

const source = resolve(process.argv[2] ?? 'examples/lantern-relay-rules.txt')
const output = resolve(process.argv[3] ?? '.local/demo/lantern-relay-rulebook.pdf')
const text = await readFile(source, 'utf8')
const pages = text
  .split(/^=== PAGE \d+ ===$/m)
  .slice(1)
  .map((page) => page.trim().split('\n').map((line) => line.trim()).filter(Boolean))

if (pages.length === 0 || pages.some((page) => page.length > 28)) {
  throw new Error('demo rule source must contain non-empty page markers with at most 28 lines per page')
}

function escapePdfText(value) {
  if (!/^[\x20-\x7E]*$/.test(value)) throw new Error('demo PDF currently accepts printable ASCII only')
  return value.replaceAll('\\', '\\\\').replaceAll('(', '\\(').replaceAll(')', '\\)')
}

const objects = []
const pageObjectIds = []
objects[1] = '<< /Type /Catalog /Pages 2 0 R >>'
objects[3] = '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>'

pages.forEach((lines, index) => {
  const pageId = 4 + (index * 2)
  const contentId = pageId + 1
  pageObjectIds.push(`${pageId} 0 R`)
  const commands = lines.map((line, lineIndex) => {
    const fontSize = lineIndex === 0 ? 15 : 11
    return `BT /F1 ${fontSize} Tf 54 ${744 - (lineIndex * 25)} Td (${escapePdfText(line)}) Tj ET`
  }).join('\n')
  objects[pageId] = `<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 3 0 R >> >> /Contents ${contentId} 0 R >>`
  objects[contentId] = `<< /Length ${Buffer.byteLength(commands, 'ascii')} >>\nstream\n${commands}\nendstream`
})

objects[2] = `<< /Type /Pages /Kids [${pageObjectIds.join(' ')}] /Count ${pages.length} >>`

let pdf = '%PDF-1.4\n% RulePilot original CC0 demo rulebook\n'
const offsets = [0]
for (let id = 1; id < objects.length; id += 1) {
  offsets[id] = Buffer.byteLength(pdf, 'ascii')
  pdf += `${id} 0 obj\n${objects[id]}\nendobj\n`
}

const xrefOffset = Buffer.byteLength(pdf, 'ascii')
pdf += `xref\n0 ${objects.length}\n0000000000 65535 f \n`
for (let id = 1; id < objects.length; id += 1) {
  pdf += `${String(offsets[id]).padStart(10, '0')} 00000 n \n`
}
pdf += `trailer\n<< /Size ${objects.length} /Root 1 0 R >>\nstartxref\n${xrefOffset}\n%%EOF\n`

await mkdir(dirname(output), { recursive: true })
await writeFile(output, pdf, { encoding: 'ascii' })
process.stdout.write(`${output}\n`)
