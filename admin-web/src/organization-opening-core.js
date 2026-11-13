import { organizationReadAccess, organizationClinicDetailRequest, preserveOrganizationClinicDetail } from './organization-readonly-core.js'

const MAX_ID = 0x7fffffffffffffffn
export const ORGANIZATION_OPENING_MARKER_KEY = 'adc.organization.opening.pending'
const SNAPSHOT_FIELDS = ['openingId', 'clinicId', 'startAt', 'endAt', 'status', 'note', 'updatedBy', 'updatedAt', 'mutationCount']

export function normalizeOpeningId(value, label = '开放ID') {
  if (typeof value !== 'string' || !/^[1-9]\d*$/.test(value)) throw new Error(`${label}必须是正63位范围内的十进制字符串。`)
  try { if (BigInt(value) <= MAX_ID) return value } catch { /* invalid */ }
  throw new Error(`${label}必须是正63位范围内的十进制字符串。`)
}

export function organizationOpeningAccess(identity) {
  return organizationReadAccess(identity)
}

export function organizationOpeningClinicRequest(access, clinicId) {
  const clinic = organizationClinicDetailRequest(access, clinicId)
  return { ...clinic, scopeKey: JSON.stringify([clinic.scope.chain, clinic.scope.clinicIds, clinic.clinicId]) }
}

export function organizationOpeningListRequest(access, clinicId, { afterId = null, limit = 30 } = {}) {
  const clinic = organizationOpeningClinicRequest(access, clinicId)
  if (!Number.isInteger(limit) || limit < 1 || limit > 50) throw new Error('每页数量必须在1至50之间。')
  const query = new URLSearchParams()
  if (afterId !== null) query.set('afterId', normalizeOpeningId(afterId, '开放分页游标'))
  query.set('limit', String(limit))
  return { endpoint: `/org/clinics/${clinic.clinicId}/openings?${query.toString()}`, clinicId: clinic.clinicId, afterId, limit, scopeKey: clinic.scopeKey }
}

export function organizationOpeningDetailRequest(access, openingId) {
  const id = normalizeOpeningId(openingId)
  if (!access?.employee || !access.allowed) throw new Error('当前员工没有运营人员／店长开放读取授权。')
  return { endpoint: `/org/openings/${id}`, openingId: id, scope: { chain: access.chain, clinicIds: [...access.clinicIds] } }
}

function exactObject(value, fields, label) {
  if (!value || typeof value !== 'object' || Array.isArray(value) || Object.keys(value).some((key) => !fields.includes(key)) || fields.some((key) => !Object.hasOwn(value, key))) throw new Error(`${label}响应格式无效。`)
}

function canonicalTime(value, label) {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\+08:00$/.test(value)) throw new Error(`${label}必须是上海时区整秒时间。`)
  const local = value.slice(0, 19)
  const ms = Date.parse(`${local}Z`)
  if (!Number.isFinite(ms) || new Date(ms).toISOString().slice(0, 19) !== local) throw new Error(`${label}不是有效日期时间。`)
  return { value, instantSeconds: Math.floor(ms / 1000) - 8 * 3600 }
}

function validateInterval(startAt, endAt) {
  const interval = validateReadInterval(startAt, endAt)
  const seconds = interval.endSeconds - interval.startSeconds
  if (seconds > 86400) throw new Error('开放结束时间须晚于开始时间，持续时间不超过24小时。')
  return { startAt: interval.startAt, endAt: interval.endAt }
}

function validateReadInterval(startAt, endAt) {
  const start = canonicalTime(startAt, '开放开始时间')
  const end = canonicalTime(endAt, '开放结束时间')
  const seconds = end.instantSeconds - start.instantSeconds
  if (seconds <= 0) throw new Error('开放结束时间须晚于开始时间。')
  return { startAt: start.value, endAt: end.value, startSeconds: start.instantSeconds, endSeconds: end.instantSeconds }
}

function localTime(value, label) {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2})?$/.test(value)) throw new Error(`${label}须填写到整秒。`)
  const local = value.length === 16 ? `${value}:00` : value
  canonicalTime(`${local}+08:00`, label)
  return `${local}+08:00`
}

export function normalizeOrganizationOpeningBody(input) {
  exactObject(input, ['openingId', 'clinicId', 'startLocal', 'endLocal', 'note'], '实际开放资料')
  const openingId = normalizeOpeningId(input.openingId)
  const clinicId = normalizeOpeningId(input.clinicId, '门店ID')
  const { startAt, endAt } = validateInterval(localTime(input.startLocal, '开始时间'), localTime(input.endLocal, '结束时间'))
  if (typeof input.note !== 'string') throw new Error('备注格式无效。')
  const trimmed = input.note.replace(/^[\u0000-\u0020]+|[\u0000-\u0020]+$/g, '')
  if (trimmed.length > 8000) throw new Error('备注不能超过8000个字符。')
  const blank = trimmed.length === 0 || [...trimmed].every((char) => /[\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]/u.test(char))
  return { openingId, clinicId, startAt, endAt, note: blank ? null : trimmed }
}

export function preserveExactOpeningRetryBody(body) {
  exactObject(body, ['openingId', 'clinicId', 'startAt', 'endAt', 'note'], '原始开放请求')
  normalizeOpeningId(body.openingId); normalizeOpeningId(body.clinicId, '门店ID')
  validateInterval(body.startAt, body.endAt)
  if (!(body.note === null || typeof body.note === 'string' && body.note.length <= 8000)) throw new Error('原始开放备注格式无效。')
  return { openingId: body.openingId, clinicId: body.clinicId, startAt: body.startAt, endAt: body.endAt, note: body.note }
}

export function openingNeedsActiveClinicCheck({ pendingMarker, exactRetry = false }) {
  return !pendingMarker && exactRetry !== true
}

export function organizationOpeningPostReadOutcome(postConfirmed, readsSucceeded) {
  return postConfirmed ? { writeState: 'confirmed', readFailed: readsSucceeded !== true } : { writeState: 'unknown', readFailed: true }
}

export function organizationOpeningContextMatches(captured, current) {
  return !!captured && !!current && captured.disposed !== true && current.disposed !== true
    && captured.accountId === current.accountId && captured.token === current.token
    && captured.scope === current.scope && captured.clinicId === current.clinicId
    && captured.actorGeneration === current.actorGeneration
}

function ensureResponseSize(raw, label) {
  let bytes
  try { bytes = new TextEncoder().encode(JSON.stringify(raw)).byteLength } catch { throw new Error(`${label}响应格式无效。`) }
  if (bytes > 48 * 1024) throw new Error(`${label}响应超出48KiB限制。`)
}

function snapshot(raw, expected = {}) {
  exactObject(raw, SNAPSHOT_FIELDS, '实际开放快照')
  const result = { ...raw }
  result.openingId = normalizeOpeningId(result.openingId)
  result.clinicId = normalizeOpeningId(result.clinicId, '门店ID')
  result.updatedBy = normalizeOpeningId(result.updatedBy, '更新账号ID')
  if (expected.openingId && result.openingId !== expected.openingId) throw new Error('开放ID与请求不一致。')
  if (expected.clinicId && result.clinicId !== expected.clinicId) throw new Error('开放所属门店与请求不一致。')
  validateReadInterval(result.startAt, result.endAt)
  if (!['有效', '撤销'].includes(result.status) || !(result.note === null || typeof result.note === 'string')) throw new Error('开放状态或备注格式无效。')
  canonicalTime(result.updatedAt, '更新时间')
  if (typeof result.mutationCount !== 'string' || !/^(0|[1-9]\d{0,18})$/.test(result.mutationCount)) throw new Error('开放变更计数格式无效。')
  return result
}

export function preserveOrganizationOpeningList(raw, request) {
  ensureResponseSize(raw, '实际开放列表')
  exactObject(raw, ['items', 'hasMore', 'nextAfterId'], '实际开放列表')
  if (!Array.isArray(raw.items) || typeof raw.hasMore !== 'boolean') throw new Error('实际开放列表格式无效。')
  const items = raw.items.map((item) => snapshot(item, { clinicId: request.clinicId }))
  for (let i = 1; i < items.length; i += 1) if (BigInt(items[i - 1].openingId) >= BigInt(items[i].openingId)) throw new Error('开放列表必须按openingId严格升序。')
  if (request.afterId !== null && items.some((item) => BigInt(item.openingId) <= BigInt(request.afterId))) throw new Error('开放列表包含游标之前的资源。')
  const nextAfterId = raw.nextAfterId === null ? null : normalizeOpeningId(raw.nextAfterId, '下一页游标')
  if (raw.hasMore && (!items.length || nextAfterId !== items.at(-1).openingId) || !raw.hasMore && nextAfterId !== null) throw new Error('开放列表分页游标无效。')
  return { items, hasMore: raw.hasMore, nextAfterId }
}

function stable(value) { return Array.isArray(value) ? value.map(stable) : value && typeof value === 'object' ? Object.fromEntries(Object.keys(value).sort().map((key) => [key, stable(value[key])])) : value }
function envelope(raw, expected, label) {
  ensureResponseSize(raw, label)
  exactObject(raw, ['resource', 'snapshot'], `${label}响应`)
  const resource = snapshot(raw.resource, expected)
  const current = snapshot(raw.snapshot, expected)
  if (JSON.stringify(stable(resource)) !== JSON.stringify(stable(current))) throw new Error(`${label}resource与snapshot不一致。`)
  return { resource, snapshot: current }
}

export function preserveOrganizationOpeningDetail(raw, request) { return envelope(raw, { openingId: request.openingId }, '实际开放详情') }

export function preserveOrganizationOpeningReceipt(raw, expectedBody, actorAccountId) {
  ensureResponseSize(raw, '实际开放创建回执')
  exactObject(raw, ['replayed', 'resource', 'snapshot'], '实际开放创建回执')
  if (typeof raw.replayed !== 'boolean') throw new Error('创建回执重放标记无效。')
  const { resource, snapshot: current } = envelope({ resource: raw.resource, snapshot: raw.snapshot }, { openingId: expectedBody.openingId, clinicId: expectedBody.clinicId }, '实际开放创建回执')
  validateInterval(resource.startAt, resource.endAt)
  if (resource.status !== '有效' || resource.startAt !== expectedBody.startAt || resource.endAt !== expectedBody.endAt || resource.note !== expectedBody.note || resource.mutationCount !== '1' || resource.updatedBy !== normalizeOpeningId(actorAccountId, '操作账号ID')) throw new Error('创建回执与本次固定请求不一致。')
  return { replayed: raw.replayed, resource, snapshot: current }
}

const MARKER_FIELDS = ['actorAccountId', 'kind', 'clinicId', 'openingId', 'route', 'state', 'scope']
export function safeOrganizationOpeningMarker(raw) {
  exactObject(raw, MARKER_FIELDS, '待核对标识')
  if (raw.kind !== 'opening' || raw.scope !== 'operational' || !['sending', 'unknown', 'conflict'].includes(raw.state)) throw new Error('待核对标识格式无效。')
  const actorAccountId = normalizeOpeningId(raw.actorAccountId, '原操作账号ID')
  const clinicId = normalizeOpeningId(raw.clinicId, '门店ID')
  const openingId = normalizeOpeningId(raw.openingId)
  const route = `/org/clinics/${clinicId}/openings`
  if (raw.route !== route) throw new Error('待核对标识路径无效。')
  return { actorAccountId, kind: 'opening', clinicId, openingId, route, state: raw.state === 'sending' ? 'unknown' : raw.state, scope: 'operational' }
}

function inspectMarker(storage) {
  let raw
  try { raw = storage.getItem(ORGANIZATION_OPENING_MARKER_KEY) } catch { return { status: 'unreadable' } }
  if (raw === null) return { status: 'empty' }
  try { return { status: 'valid', marker: safeOrganizationOpeningMarker(JSON.parse(raw)) } } catch { return { status: 'invalid' } }
}
function markerError(code) { const error = new Error('待核对标识无法安全读取或已发生变化，原标识已保留。'); error.code = code; return error }
export function loadOrganizationOpeningMarker(storage) {
  const inspected = inspectMarker(storage)
  if (inspected.status === 'unreadable') throw markerError('OPENING_MARKER_UNREADABLE')
  if (inspected.status === 'invalid') throw markerError('OPENING_MARKER_INVALID')
  return inspected.status === 'valid' ? inspected.marker : null
}
export function persistOrganizationOpeningMarker(storage, marker) {
  const safe = safeOrganizationOpeningMarker(marker)
  const current = inspectMarker(storage)
  if (current.status === 'unreadable' || current.status === 'invalid') throw markerError('OPENING_MARKER_UNREADABLE')
  if (current.status === 'valid' && (current.marker.actorAccountId !== safe.actorAccountId || current.marker.clinicId !== safe.clinicId || current.marker.openingId !== safe.openingId)) throw markerError('OPENING_MARKER_PENDING')
  storage.setItem(ORGANIZATION_OPENING_MARKER_KEY, JSON.stringify(safe))
  return safe
}
export function clearOrganizationOpeningMarker(storage, expected) {
  const current = inspectMarker(storage)
  if (current.status === 'unreadable' || current.status === 'invalid') throw markerError('OPENING_MARKER_UNREADABLE')
  if (current.status === 'empty') return false
  if (current.marker.actorAccountId !== expected.actorAccountId || current.marker.clinicId !== expected.clinicId || current.marker.openingId !== expected.openingId) throw markerError('OPENING_MARKER_CHANGED')
  storage.removeItem(ORGANIZATION_OPENING_MARKER_KEY)
  return true
}
export function clearInvalidOrganizationOpeningMarker(storage, { manuallyReconciled = false, confirmed = false } = {}) {
  if (manuallyReconciled !== true || confirmed !== true) throw markerError('OPENING_MARKER_CONFIRMATION_REQUIRED')
  const current = inspectMarker(storage)
  if (current.status === 'unreadable') throw markerError('OPENING_MARKER_UNREADABLE')
  if (current.status === 'valid') return { status: 'valid', marker: current.marker }
  if (current.status === 'empty') return { status: 'empty' }
  try { storage.removeItem(ORGANIZATION_OPENING_MARKER_KEY) } catch { throw markerError('OPENING_MARKER_UNREADABLE') }
  return { status: 'cleared' }
}

export function openingManualRetryAllowed({ marker, actorAccountId, clinicId, openingId, clinicInspected, openingInspected }) {
  return !!marker && marker.state !== 'sending' && marker.actorAccountId === String(actorAccountId) && marker.clinicId === clinicId && marker.openingId === openingId && clinicInspected === true && openingInspected === true
}

export function classifyOrganizationOpeningError(error) {
  const status = Number(error?.status)
  if (status === 409) return 'conflict'
  if (status === 400 || status === 401 || status === 403 || status >= 400 && status < 500) return 'rejected'
  return 'unknown'
}

export function preserveOpeningClinic(raw, request) {
  const detail = preserveOrganizationClinicDetail(raw, request)
  return detail.resource
}
