#!/usr/bin/env node

import { randomUUID } from 'node:crypto'
import { chmod, lstat, readFile, rename, stat, writeFile } from 'node:fs/promises'
import { basename, dirname, join } from 'node:path'

const COOKIE_NAME = /^[!#$%&'*+\-.^_`|~0-9A-Za-z]+$/

class ClientError extends Error {
  constructor(message, exitCode) {
    super(message)
    this.exitCode = exitCode
  }
}

function requireValue(argumentsList, index, option) {
  const value = argumentsList[index + 1]
  if (value == null) throw new ClientError(`${option} requires a value`, 2)
  return value
}

function positiveNumber(value, option) {
  const parsed = Number(value)
  if (!Number.isFinite(parsed) || parsed <= 0) {
    throw new ClientError(`${option} must be a positive number`, 2)
  }
  return parsed
}

function parseArguments(argumentsList) {
  const request = {
    connectTimeoutSeconds: null,
    cookieInput: null,
    cookieOutput: null,
    data: [],
    encodedData: [],
    fail: false,
    failWithBody: false,
    forms: [],
    headers: [],
    headerOutput: null,
    location: false,
    maxFileSize: null,
    maxTimeSeconds: null,
    method: null,
    output: null,
    protocolPolicy: null,
    url: null,
    writeOut: null,
  }
  const valueOptions = new Set([
    '--connect-timeout', '--cookie', '--cookie-jar', '--data', '--data-urlencode',
    '--dump-header', '--form', '--header', '--max-filesize', '--max-time', '--output',
    '--proto', '--request', '--write-out',
  ])
  for (let index = 0; index < argumentsList.length; index += 1) {
    const argument = argumentsList[index]
    if (!argument.startsWith('--')) {
      if (request.url != null) throw new ClientError('exactly one request URL is required', 2)
      request.url = argument
      continue
    }
    if (['--silent', '--show-error'].includes(argument)) continue
    if (argument === '--fail') {
      request.fail = true
      continue
    }
    if (argument === '--fail-with-body') {
      request.failWithBody = true
      continue
    }
    if (argument === '--location') {
      request.location = true
      continue
    }
    if (!valueOptions.has(argument)) throw new ClientError(`unsupported HTTP option: ${argument}`, 2)
    const value = requireValue(argumentsList, index, argument)
    index += 1
    switch (argument) {
      case '--connect-timeout':
        request.connectTimeoutSeconds = positiveNumber(value, argument)
        break
      case '--cookie':
        request.cookieInput = value
        break
      case '--cookie-jar':
        request.cookieOutput = value
        break
      case '--data':
        request.data.push(value)
        break
      case '--data-urlencode':
        request.encodedData.push(value)
        break
      case '--dump-header':
        request.headerOutput = value
        break
      case '--form':
        request.forms.push(value)
        break
      case '--header':
        request.headers.push(value)
        break
      case '--max-filesize':
        request.maxFileSize = positiveNumber(value, argument)
        break
      case '--max-time':
        request.maxTimeSeconds = positiveNumber(value, argument)
        break
      case '--output':
        request.output = value
        break
      case '--proto':
        request.protocolPolicy = value
        break
      case '--request':
        request.method = value.toUpperCase()
        break
      case '--write-out':
        request.writeOut = value
        break
    }
  }
  if (request.url == null) throw new ClientError('one request URL is required', 2)
  return request
}

async function loadCookieJar(path, url) {
  if (path == null) return new Map()
  let metadata
  try {
    metadata = await lstat(path)
  } catch (error) {
    if (error.code === 'ENOENT') return new Map()
    throw error
  }
  if (!metadata.isFile() || metadata.isSymbolicLink() || metadata.size > 65_536) {
    throw new ClientError('cookie jar must be one bounded regular file', 2)
  }
  const value = JSON.parse(await readFile(path, 'utf8'))
  if (value?.version !== 1 || value.origin !== url.origin || typeof value.cookies !== 'object'
      || value.cookies == null || Array.isArray(value.cookies)) {
    throw new ClientError('cookie jar does not belong to this request origin', 2)
  }
  const cookies = new Map()
  for (const [name, cookieValue] of Object.entries(value.cookies)) {
    if (!COOKIE_NAME.test(name) || typeof cookieValue !== 'string' || /[;\r\n]/.test(cookieValue)) {
      throw new ClientError('cookie jar contains an invalid cookie', 2)
    }
    cookies.set(name, cookieValue)
  }
  return cookies
}

function captureCookies(response, cookies) {
  const values = typeof response.headers.getSetCookie === 'function'
    ? response.headers.getSetCookie()
    : [response.headers.get('set-cookie')].filter(Boolean)
  for (const value of values) {
    const firstSegment = value.split(';', 1)[0]
    const separator = firstSegment.indexOf('=')
    if (separator <= 0) continue
    const name = firstSegment.slice(0, separator).trim()
    const cookieValue = firstSegment.slice(separator + 1).trim()
    if (!COOKIE_NAME.test(name) || /[;\r\n]/.test(cookieValue)) continue
    if (/;\s*max-age=0(?:;|$)/i.test(value) || /;\s*expires=Thu, 01 Jan 1970/i.test(value)) {
      cookies.delete(name)
    } else {
      cookies.set(name, cookieValue)
    }
  }
}

async function saveCookieJar(path, url, cookies) {
  if (path == null) return
  const temporary = join(dirname(path), `.${basename(path)}.${process.pid}.${randomUUID()}.tmp`)
  const value = JSON.stringify({ version: 1, origin: url.origin, cookies: Object.fromEntries(cookies) })
  await writeFile(temporary, `${value}\n`, { encoding: 'utf8', flag: 'wx', mode: 0o600 })
  await rename(temporary, path)
  await chmod(path, 0o600)
}

function splitAssignment(value, option) {
  const separator = value.indexOf('=')
  if (separator <= 0) throw new ClientError(`${option} requires name=value`, 2)
  return [value.slice(0, separator), value.slice(separator + 1)]
}

async function requestBody(request) {
  const bodyKinds = Number(request.forms.length > 0)
    + Number(request.data.length > 0)
    + Number(request.encodedData.length > 0)
  if (bodyKinds > 1) throw new ClientError('request body modes cannot be combined', 2)
  if (request.forms.length > 0) {
    const form = new FormData()
    for (const entry of request.forms) {
      const [name, value] = splitAssignment(entry, '--form')
      if (!value.startsWith('@')) {
        form.append(name, value)
        continue
      }
      const fileMatch = /^@(.+?)(?:;type=([^;]+))?$/.exec(value)
      if (fileMatch == null) throw new ClientError('file form must be @path with an optional type', 2)
      const filePath = fileMatch[1]
      const fileMetadata = await stat(filePath)
      if (!fileMetadata.isFile()) throw new ClientError('multipart upload must reference a regular file', 2)
      const content = await readFile(filePath)
      const blob = new Blob([content], { type: fileMatch[2] ?? 'application/octet-stream' })
      form.append(name, blob, basename(filePath))
    }
    return form
  }
  if (request.encodedData.length > 0) {
    const parameters = new URLSearchParams()
    for (const entry of request.encodedData) {
      const [name, value] = splitAssignment(entry, '--data-urlencode')
      parameters.append(name, value)
    }
    return parameters.toString()
  }
  if (request.data.length > 0) return request.data.join('&')
  return undefined
}

function responseHeaders(response) {
  let value = `HTTP/1.1 ${response.status} ${response.statusText}\r\n`
  for (const [name, headerValue] of response.headers) value += `${name}: ${headerValue}\r\n`
  return `${value}\r\n`
}

function renderWriteOut(template, response, elapsedSeconds) {
  return template
    .replaceAll('%{http_code}', String(response.status).padStart(3, '0'))
    .replaceAll('%{time_total}', elapsedSeconds.toFixed(6))
    .replaceAll('\\t', '\t')
    .replaceAll('\\n', '\n')
    .replaceAll('\\r', '\r')
}

function validateUrlPolicy(url, protocolPolicy) {
  if (!['http:', 'https:'].includes(url.protocol)) {
    throw new ClientError('HTTP client accepts only HTTP or HTTPS URLs', 2)
  }
  if (protocolPolicy != null && (protocolPolicy !== '=https' || url.protocol !== 'https:')) {
    throw new ClientError('request URL violates the HTTPS-only protocol policy', 2)
  }
}

function isRedirect(response) {
  return [301, 302, 303, 307, 308].includes(response.status)
    && response.headers.has('location')
}

async function fetchWithRedirects({ body, cookies, headers, method, request, signal, url }) {
  let currentBody = body
  let currentMethod = method
  let currentUrl = url
  for (let redirectCount = 0; redirectCount <= 10; redirectCount += 1) {
    const hopHeaders = new Headers(headers)
    if (currentUrl.origin === url.origin && cookies.size > 0) {
      hopHeaders.set('Cookie', [...cookies].map(([name, value]) => `${name}=${value}`).join('; '))
    } else {
      hopHeaders.delete('Cookie')
    }
    const response = await fetch(currentUrl, {
      body: currentBody,
      headers: hopHeaders,
      method: currentMethod,
      redirect: 'manual',
      signal,
    })
    if (currentUrl.origin === url.origin) captureCookies(response, cookies)
    if (!request.location || !isRedirect(response)) return response
    if (redirectCount === 10) throw new ClientError('HTTP redirect limit was exceeded', 47)
    const nextUrl = new URL(response.headers.get('location'), currentUrl)
    await response.body?.cancel()
    validateUrlPolicy(nextUrl, request.protocolPolicy)
    if ((request.cookieInput != null || request.cookieOutput != null) && nextUrl.origin !== url.origin) {
      throw new ClientError('cookie-bearing requests cannot follow a cross-origin redirect', 47)
    }
    if (currentBody != null) {
      const switchesToGet = response.status === 303
        || ([301, 302].includes(response.status) && currentMethod === 'POST')
      if (!switchesToGet) {
        throw new ClientError('a redirected request body cannot be replayed safely', 47)
      }
      currentBody = undefined
      currentMethod = 'GET'
      headers.delete('Content-Type')
      headers.delete('Content-Length')
    }
    currentUrl = nextUrl
  }
  throw new ClientError('HTTP redirect limit was exceeded', 47)
}

async function readResponseBody(response, maximumSize) {
  const declaredLength = Number(response.headers.get('content-length'))
  if (maximumSize != null && Number.isFinite(declaredLength) && declaredLength > maximumSize) {
    await response.body?.cancel()
    throw new ClientError('HTTP response exceeded the allowed file size', 63)
  }
  if (response.body == null) return Buffer.alloc(0)
  const reader = response.body.getReader()
  const chunks = []
  let size = 0
  try {
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      size += value.byteLength
      if (maximumSize != null && size > maximumSize) {
        await reader.cancel()
        throw new ClientError('HTTP response exceeded the allowed file size', 63)
      }
      chunks.push(Buffer.from(value))
    }
  } finally {
    reader.releaseLock()
  }
  return Buffer.concat(chunks, size)
}

function transportClientError(error) {
  if (error instanceof ClientError) return error
  if (error?.name === 'TimeoutError' || error?.name === 'AbortError') {
    return new ClientError('HTTP request exhausted its total timeout', 28)
  }
  return new ClientError('HTTP transport failed before a complete response', 35)
}

async function performRequest(argumentsList) {
  const request = parseArguments(argumentsList)
  const url = new URL(request.url)
  validateUrlPolicy(url, request.protocolPolicy)
  const cookies = await loadCookieJar(request.cookieInput, url)
  const headers = new Headers()
  for (const rawHeader of request.headers) {
    const separator = rawHeader.indexOf(':')
    if (separator <= 0) throw new ClientError('request header must be name: value', 2)
    headers.append(rawHeader.slice(0, separator).trim(), rawHeader.slice(separator + 1).trim())
  }
  const body = await requestBody(request)
  const method = request.method ?? (body == null ? 'GET' : 'POST')
  const timeoutSeconds = request.maxTimeSeconds ?? request.connectTimeoutSeconds
  const signal = timeoutSeconds == null
    ? undefined
    : AbortSignal.timeout(Math.ceil(timeoutSeconds * 1000))
  const startedAt = performance.now()
  let response
  try {
    response = await fetchWithRedirects({ body, cookies, headers, method, request, signal, url })
  } catch (error) {
    throw transportClientError(error)
  }
  await saveCookieJar(request.cookieOutput, url, cookies)
  if (request.headerOutput != null) {
    await writeFile(request.headerOutput, responseHeaders(response), { encoding: 'utf8', mode: 0o600 })
    await chmod(request.headerOutput, 0o600)
  }
  let content
  try {
    content = await readResponseBody(response, request.maxFileSize)
  } catch (error) {
    throw transportClientError(error)
  }
  const elapsedSeconds = (performance.now() - startedAt) / 1000
  const failedStatus = response.status >= 400
  const publishBody = !failedStatus || request.failWithBody || (!request.fail && !request.failWithBody)
  if (publishBody) {
    if (request.output == null || request.output === '-') {
      process.stdout.write(content)
    } else if (request.output !== '/dev/null') {
      await writeFile(request.output, content, { mode: 0o600 })
      await chmod(request.output, 0o600)
    }
  }
  if (request.writeOut != null) process.stdout.write(renderWriteOut(request.writeOut, response, elapsedSeconds))
  if (failedStatus && (request.fail || request.failWithBody)) {
    throw new ClientError(`HTTP response status was ${response.status}`, 22)
  }
}

try {
  await performRequest(process.argv.slice(2))
} catch (error) {
  const message = error instanceof ClientError ? error.message : 'HTTP client failed unexpectedly'
  process.stderr.write(`${message}\n`)
  process.exitCode = error instanceof ClientError ? error.exitCode : 1
}
