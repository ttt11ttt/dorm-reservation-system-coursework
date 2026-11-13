import { organizationOpeningAccess, organizationOpeningClinicRequest, organizationOpeningDetailRequest, preserveOrganizationOpeningDetail } from './organization-opening-core.js'

const MAX_ID = 0x7fffffffffffffffn
export const ORGANIZATION_SCHEDULE_MARKER_KEY = 'adc.organization.schedule.pending'
const SNAPSHOT_FIELDS = ['scheduleId', 'dentistId', 'clinicId', 'openingId', 'startAt', 'endAt', 'status', 'rowVersion', 'mutationCount']
const MARKER_FIELDS = ['actorAccountId', 'kind', 'clinicId', 'scheduleId', 'dentistId', 'openingId', 'route', 'scope', 'state']

export function normalizeScheduleId(value, label = '出诊ID') {
  if (typeof value !== 'string' || !/^[1-9]\d*$/.test(value)) throw new Error(`${label}必须是正63位范围内的十进制字符串。`)
  try { if (BigInt(value) <= MAX_ID) return value } catch { /* invalid */ }
  throw new Error(`${label}必须是正63位范围内的十进制字符串。`)
}

export function organizationScheduleAccess(identity) { return organizationOpeningAccess(identity) }
export function organizationScheduleClinicRequest(access, clinicId) { return organizationOpeningClinicRequest(access, clinicId) }
export function organizationScheduleOpeningRequest(access, openingId) { return organizationOpeningDetailRequest(access, openingId) }

export function organizationScheduleListRequest(access, clinicId, { afterId = null, limit = 30 } = {}) {
  const clinic = organizationOpeningClinicRequest(access, clinicId)
  if (!Number.isInteger(limit) || limit < 1 || limit > 50) throw new Error('每页数量必须在1至50之间。')
  const query = new URLSearchParams()
  if (afterId !== null) query.set('afterId', normalizeScheduleId(afterId, '出诊分页游标'))
  query.set('limit', String(limit))
  return { endpoint: `/org/clinics/${clinic.clinicId}/schedules?${query.toString()}`, clinicId: clinic.clinicId, afterId, limit }
}

export function organizationScheduleDetailRequest(access, scheduleId) {
  const id = normalizeScheduleId(scheduleId)
  if (!access?.employee || !access.allowed) throw new Error('当前员工没有运营人员／店长出诊读取授权。')
  return { endpoint: `/org/schedules/${id}`, scheduleId: id }
}

function exactObject(value, fields, label) {
  if (!value || typeof value !== 'object' || Array.isArray(value) || Object.keys(value).some((key) => !fields.includes(key)) || fields.some((key) => !Object.hasOwn(value, key))) throw new Error(`${label}格式无效。`)
}

function time(value, label) {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\+08:00$/.test(value)) throw new Error(`${label}必须是上海时区整秒时间。`)
  const local = value.slice(0, 19)
  const millis = Date.parse(`${local}Z`)
  if (!Number.isFinite(millis) || new Date(millis).toISOString().slice(0, 19) !== local) throw new Error(`${label}不是有效日期时间。`)
  return { value, millis: millis - 8 * 3600 * 1000 }
}

function readInterval(startAt, endAt) {
  const start = time(startAt, '出诊开始时间')
  const end = time(endAt, '出诊结束时间')
  const duration = end.millis - start.millis
  if (duration <= 0) throw new Error('出诊结束时间须晚于开始时间。')
  return { startAt: start.value, endAt: end.value, startMillis: start.millis, endMillis: end.millis, duration }
}

function createInterval(startAt, endAt) {
  const interval = readInterval(startAt, endAt)
  if (interval.duration > 86400000) throw new Error('出诊时长不能超过24小时。')
  return interval
}

function localInput(value, label) {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2})?$/.test(value)) throw new Error(`${label}须填写到整秒。`)
  const local = value.length === 16 ? `${value}:00` : value
  time(`${local}+08:00`, label)
  return `${local}+08:00`
}

export function normalizeOrganizationScheduleBody(input, { now = new Date(), allowPast = false } = {}) {
  exactObject(input, ['clinicId', 'scheduleId', 'dentistId', 'openingId', 'startLocal', 'endLocal'], '出诊资料')
  const clinicId = normalizeScheduleId(input.clinicId, '门店ID')
  const scheduleId = normalizeScheduleId(input.scheduleId, '出诊ID')
  const dentistId = normalizeScheduleId(input.dentistId, '牙医ID')
  const openingId = normalizeScheduleId(input.openingId, '开放ID')
  const interval = createInterval(localInput(input.startLocal, '开始时间'), localInput(input.endLocal, '结束时间'))
  if (!allowPast && interval.startMillis <= now.getTime()) throw new Error('新建出诊开始时间须晚于当前时间。')
  return { clinicId, scheduleId, dentistId, openingId, startAt: interval.startAt, endAt: interval.endAt }
}

export function preserveExactOrganizationScheduleRetryBody(body) {
  exactObject(body, ['clinicId', 'scheduleId', 'dentistId', 'openingId', 'startAt', 'endAt'], '原始出诊请求')
  const normalized = {
    clinicId: normalizeScheduleId(body.clinicId, '门店ID'),
    scheduleId: normalizeScheduleId(body.scheduleId, '出诊ID'),
    dentistId: normalizeScheduleId(body.dentistId, '牙医ID'),
    openingId: normalizeScheduleId(body.openingId, '开放ID'),
  }
  const interval = createInterval(body.startAt, body.endAt)
  return { ...normalized, startAt: interval.startAt, endAt: interval.endAt }
}

export function organizationScheduleOpeningCovers(opening, body) {
  if (!opening || opening.clinicId !== body.clinicId || opening.status !== '有效') return false
  const open = readInterval(opening.startAt, opening.endAt)
  const schedule = createInterval(body.startAt, body.endAt)
  return open.startMillis <= schedule.startMillis && open.endMillis >= schedule.endMillis
}

function responseSize(raw, label) {
  let size
  try { size = new TextEncoder().encode(JSON.stringify(raw)).byteLength } catch { throw new Error(`${label}响应格式无效。`) }
  if (size > 48 * 1024) throw new Error(`${label}响应超出48KiB限制。`)
}

function counter(value, label) {
  if (typeof value !== 'string' || !/^(0|[1-9]\d{0,18})$/.test(value)) throw new Error(`${label}格式无效。`)
  return value
}

function snapshot(raw, expected = {}) {
  exactObject(raw, SNAPSHOT_FIELDS, '出诊快照')
  const result = { ...raw }
  for (const field of ['scheduleId', 'dentistId', 'clinicId', 'openingId']) result[field] = normalizeScheduleId(result[field], field)
  if (expected.scheduleId && result.scheduleId !== expected.scheduleId) throw new Error('出诊ID与请求不一致。')
  if (expected.clinicId && result.clinicId !== expected.clinicId) throw new Error('出诊所属门店与请求不一致。')
  readInterval(result.startAt, result.endAt)
  if (!['有效', '撤销'].includes(result.status)) throw new Error('出诊状态无效。')
  result.rowVersion = counter(result.rowVersion, 'rowVersion')
  result.mutationCount = counter(result.mutationCount, 'mutationCount')
  return result
}

export function preserveOrganizationScheduleList(raw, request) {
  responseSize(raw, '出诊列表')
  exactObject(raw, ['items', 'hasMore', 'nextAfterId'], '出诊列表')
  if (!Array.isArray(raw.items) || typeof raw.hasMore !== 'boolean') throw new Error('出诊列表格式无效。')
  const items = raw.items.map((item) => snapshot(item, { clinicId: request.clinicId }))
  for (let i = 1; i < items.length; i += 1) if (BigInt(items[i - 1].scheduleId) >= BigInt(items[i].scheduleId)) throw new Error('出诊列表必须按scheduleId严格升序。')
  if (request.afterId !== null && items.some((item) => BigInt(item.scheduleId) <= BigInt(request.afterId))) throw new Error('出诊列表包含游标之前的资源。')
  const nextAfterId = raw.nextAfterId === null ? null : normalizeScheduleId(raw.nextAfterId, '下一页游标')
  if (raw.hasMore && (!items.length || nextAfterId !== items.at(-1).scheduleId) || !raw.hasMore && nextAfterId !== null) throw new Error('出诊分页游标无效。')
  return { items, hasMore: raw.hasMore, nextAfterId }
}

function stable(value) { return Array.isArray(value) ? value.map(stable) : value && typeof value === 'object' ? Object.fromEntries(Object.keys(value).sort().map((key) => [key, stable(value[key])])) : value }
function envelope(raw, expected, label) {
  responseSize(raw, label)
  exactObject(raw, ['resource', 'snapshot'], `${label}响应`)
  const resource = snapshot(raw.resource, expected)
  const current = snapshot(raw.snapshot, expected)
  if (JSON.stringify(stable(resource)) !== JSON.stringify(stable(current))) throw new Error(`${label}resource与snapshot不一致。`)
  return { resource, snapshot: current }
}
export function preserveOrganizationScheduleDetail(raw, request) { return envelope(raw, { scheduleId: request.scheduleId }, '出诊详情') }

export function preserveOrganizationScheduleReceipt(raw, expectedBody) {
  responseSize(raw, '出诊创建回执')
  exactObject(raw, ['replayed', 'resource', 'snapshot'], '出诊创建回执')
  if (typeof raw.replayed !== 'boolean') throw new Error('创建回执重放标记无效。')
  const { resource, snapshot: current } = envelope({ resource: raw.resource, snapshot: raw.snapshot }, { scheduleId: expectedBody.scheduleId, clinicId: expectedBody.clinicId }, '出诊创建回执')
  createInterval(resource.startAt, resource.endAt)
  if (resource.status !== '有效' || resource.dentistId !== expectedBody.dentistId || resource.openingId !== expectedBody.openingId || resource.startAt !== expectedBody.startAt || resource.endAt !== expectedBody.endAt || resource.rowVersion !== '0' || resource.mutationCount !== '1') throw new Error('出诊创建回执与固定请求不一致。')
  return { replayed: raw.replayed, resource, snapshot: current }
}

export function preserveScheduleOpening(raw, request) {
  return preserveOrganizationOpeningDetail(raw, request).resource
}

export function safeOrganizationScheduleMarker(raw) {
  exactObject(raw, MARKER_FIELDS, '待核对标识')
  if (raw.kind !== 'schedule' || !['chain', 'clinic'].includes(raw.scope) || !['sending', 'unknown', 'conflict'].includes(raw.state)) throw new Error('待核对标识格式无效。')
  const actorAccountId = normalizeScheduleId(raw.actorAccountId, '原操作账号ID')
  const clinicId = normalizeScheduleId(raw.clinicId, '门店ID')
  const scheduleId = normalizeScheduleId(raw.scheduleId, '出诊ID')
  const dentistId = normalizeScheduleId(raw.dentistId, '牙医ID')
  const openingId = normalizeScheduleId(raw.openingId, '开放ID')
  const route = `/org/clinics/${clinicId}/schedules`
  if (raw.route !== route) throw new Error('待核对标识路径无效。')
  return { actorAccountId, kind: 'schedule', clinicId, scheduleId, dentistId, openingId, route, scope: raw.scope, state: raw.state === 'sending' ? 'unknown' : raw.state }
}

function inspectMarker(storage) {
  let raw
  try { raw = storage.getItem(ORGANIZATION_SCHEDULE_MARKER_KEY) } catch { return { status: 'unreadable' } }
  if (raw === null) return { status: 'empty' }
  try { return { status: 'valid', marker: safeOrganizationScheduleMarker(JSON.parse(raw)) } } catch { return { status: 'invalid' } }
}
function markerError(code) { const error = new Error('待核对标识无法安全读取或已变化，原标识已保留。'); error.code = code; return error }
export function loadOrganizationScheduleMarker(storage) {
  const current = inspectMarker(storage)
  if (current.status === 'unreadable') throw markerError('SCHEDULE_MARKER_UNREADABLE')
  if (current.status === 'invalid') throw markerError('SCHEDULE_MARKER_INVALID')
  return current.status === 'valid' ? current.marker : null
}
export function persistOrganizationScheduleMarker(storage, raw) {
  const marker = safeOrganizationScheduleMarker(raw)
  const current = inspectMarker(storage)
  if (current.status === 'unreadable' || current.status === 'invalid') throw markerError('SCHEDULE_MARKER_UNREADABLE')
  if (current.status === 'valid' && ['actorAccountId', 'clinicId', 'scheduleId', 'dentistId', 'openingId', 'scope'].some((field) => current.marker[field] !== marker[field])) throw markerError('SCHEDULE_MARKER_PENDING')
  storage.setItem(ORGANIZATION_SCHEDULE_MARKER_KEY, JSON.stringify(marker))
  return marker
}
export function clearOrganizationScheduleMarker(storage, expected) {
  const current = inspectMarker(storage)
  if (current.status === 'unreadable' || current.status === 'invalid') throw markerError('SCHEDULE_MARKER_UNREADABLE')
  if (current.status === 'empty') return false
  if (['actorAccountId', 'clinicId', 'scheduleId', 'dentistId', 'openingId', 'scope'].some((field) => current.marker[field] !== expected[field])) throw markerError('SCHEDULE_MARKER_CHANGED')
  storage.removeItem(ORGANIZATION_SCHEDULE_MARKER_KEY)
  return true
}
export function clearInvalidOrganizationScheduleMarker(storage, { manuallyReconciled = false, confirmed = false } = {}) {
  if (manuallyReconciled !== true || confirmed !== true) throw markerError('SCHEDULE_MARKER_CONFIRMATION_REQUIRED')
  const current = inspectMarker(storage)
  if (current.status === 'unreadable') throw markerError('SCHEDULE_MARKER_UNREADABLE')
  if (current.status === 'valid') return { status: 'valid', marker: current.marker }
  if (current.status === 'empty') return { status: 'empty' }
  try { storage.removeItem(ORGANIZATION_SCHEDULE_MARKER_KEY) } catch { throw markerError('SCHEDULE_MARKER_UNREADABLE') }
  return { status: 'cleared' }
}
export function scheduleManualRetryAllowed({ marker, actorAccountId, clinicId, scheduleId, dentistId, openingId, scope, clinicInspected, scheduleInspected, openingInspected }) {
  return !!marker && marker.state !== 'sending' && marker.actorAccountId === String(actorAccountId) && marker.clinicId === clinicId && marker.scheduleId === scheduleId && marker.dentistId === dentistId && marker.openingId === openingId && marker.scope === scope && clinicInspected === true && scheduleInspected === true && openingInspected === true
}
export function classifyOrganizationScheduleError(error) {
  const status = Number(error?.status)
  if (status === 409) return 'conflict'
  if (status >= 400 && status < 500) return 'rejected'
  return 'unknown'
}
export function organizationScheduleContextMatches(captured, current) {
  return !!captured && !!current && captured.disposed !== true && current.disposed !== true && captured.accountId === current.accountId && captured.token === current.token && captured.scope === current.scope && captured.clinicId === current.clinicId && captured.actorGeneration === current.actorGeneration
}
export function organizationSchedulePostReadOutcome(confirmed, readsSucceeded) { return confirmed ? { writeState: 'confirmed', readFailed: readsSucceeded !== true } : { writeState: 'unknown', readFailed: true } }
