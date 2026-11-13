import { isChainScopeGrant } from './expense-core.js'

const MAX_ID = 0x7fffffffffffffffn
export const ORGANIZATION_CREATE_MARKER_KEY = 'adc.organization.create.pending'

const CLINIC_FORM_FIELDS = ['clinicId', 'clinicCode', 'name', 'postalAddress', 'managerName', 'phone', 'email', 'areaM2']
const DENTIST_FORM_FIELDS = ['dentistId', 'employeeNo', 'name', 'birthDate', 'homeAddress', 'phone', 'email']
const CLINIC_RECEIPT_FIELDS = ['clinicId', 'clinicCode', 'name', 'active', 'mutationCount']
const DENTIST_RECEIPT_FIELDS = ['dentistId', 'employeeNo', 'name', 'active', 'mutationCount']

export function organizationCreateAccess(identity) {
  const grants = identity?.kind === '员工' && Array.isArray(identity.grants) ? identity.grants : []
  const canCreate = grants.some((grant) => grant?.role_code === '运营人员／店长'
    && isChainScopeGrant(grant))
  return { employee: identity?.kind === '员工', canCreate }
}

function requireExactFields(value, fields, label) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error(`${label}格式无效。`)
  if (Object.keys(value).some((key) => !fields.includes(key))) throw new Error(`${label}包含未知字段。`)
  if (fields.some((key) => !Object.hasOwn(value, key))) throw new Error(`${label}缺少字段。`)
}

export function normalizeOrganizationId(value, label = '资源ID') {
  if (typeof value !== 'string' || !/^[1-9]\d*$/.test(value)) throw new Error(`${label}必须是正63位范围内的十进制字符串。`)
  try { if (BigInt(value) <= MAX_ID) return value } catch { /* invalid integer text */ }
  throw new Error(`${label}必须是正63位范围内的十进制字符串。`)
}

function javaTrim(value) {
  return value.replace(/^[\u0000-\u0020]+|[\u0000-\u0020]+$/g, '')
}

function label(value, max, field) {
  if (typeof value !== 'string') throw new Error(`${field}必须填写。`)
  const result = javaTrim(value)
  if (!result || result.length > max) throw new Error(`${field}不能为空且不能超过${max}个字符。`)
  return result
}

function area(value) {
  if (typeof value !== 'string' || !/^[0-9]{1,8}(?:\.[0-9]{1,2})?$/.test(value)) throw new Error('门店面积最多8位整数、2位小数。')
  const [integerPart, fractionPart = ''] = value.split('.')
  const integer = integerPart.replace(/^0+(?=\d)/, '')
  const fraction = fractionPart.padEnd(2, '0')
  if (integer === '0' && fraction === '00') throw new Error('门店面积必须大于0。')
  return `${integer}.${fraction}`
}

function shanghaiToday(now = new Date()) {
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(now)
  const get = (type) => parts.find((part) => part.type === type)?.value ?? ''
  return `${get('year')}-${get('month')}-${get('day')}`
}

function isoBirthDate(value, now) {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) throw new Error('生日必须使用YYYY-MM-DD格式。')
  const [year, month, day] = value.split('-').map(Number)
  const actual = new Date(Date.UTC(year, month - 1, day))
  if (year < 1000 || actual.getUTCFullYear() !== year || actual.getUTCMonth() !== month - 1 || actual.getUTCDate() !== day || value > shanghaiToday(now)) {
    throw new Error('生日必须是1000-01-01至上海当前日期之间的有效日期。')
  }
  return value
}

export function normalizeOrganizationCreateBody(kind, input, { now = new Date() } = {}) {
  if (kind === 'clinic') {
    requireExactFields(input, CLINIC_FORM_FIELDS, '门店资料')
    return {
      clinicId: normalizeOrganizationId(input.clinicId, '门店ID'),
      clinicCode: label(input.clinicCode, 32, '门店编号'),
      name: label(input.name, 100, '门店名称'),
      postalAddress: label(input.postalAddress, 255, '邮政地址'),
      managerName: label(input.managerName, 80, '负责人'),
      phone: label(input.phone, 32, '联系电话'),
      email: label(input.email, 254, '邮箱'),
      areaM2: area(input.areaM2),
    }
  }
  if (kind === 'dentist') {
    requireExactFields(input, DENTIST_FORM_FIELDS, '牙医资料')
    return {
      dentistId: normalizeOrganizationId(input.dentistId, '牙医ID'),
      employeeNo: label(input.employeeNo, 32, '员工编号'),
      name: label(input.name, 80, '牙医姓名'),
      birthDate: isoBirthDate(input.birthDate, now),
      homeAddress: label(input.homeAddress, 255, '家庭住址'),
      phone: label(input.phone, 32, '联系电话'),
      email: label(input.email, 254, '邮箱'),
    }
  }
  throw new Error('不支持的组织创建类型。')
}

export function organizationCreateRoute(kind, resourceId) {
  const id = normalizeOrganizationId(resourceId)
  if (kind === 'clinic') return { resourceId: id, route: '/org/clinics' }
  if (kind === 'dentist') return { resourceId: id, route: '/org/dentists' }
  throw new Error('不支持的组织创建类型。')
}

function markerShape(raw) {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) throw new Error('待核对标识格式无效。')
  const allowed = ['actorAccountId', 'kind', 'resourceId', 'route', 'state', 'scope']
  if (Object.keys(raw).some((key) => !allowed.includes(key)) || allowed.some((key) => !Object.hasOwn(raw, key))) throw new Error('待核对标识包含无效字段。')
  if (raw.kind !== 'clinic' && raw.kind !== 'dentist') throw new Error('待核对类型无效。')
  const { resourceId, route } = organizationCreateRoute(raw.kind, raw.resourceId)
  if (raw.route !== route) throw new Error('待核对路径无效。')
  const actorAccountId = normalizeOrganizationId(raw.actorAccountId, '原操作账号')
  if (!['sending', 'unknown', 'conflict'].includes(raw.state) || raw.scope !== 'chain') throw new Error('待核对状态或范围无效。')
  return { actorAccountId, kind: raw.kind, resourceId, route, state: raw.state === 'sending' ? 'unknown' : raw.state, scope: 'chain' }
}

function markerError(code) {
  const error = new Error('待核对标识无法安全读取或已发生变化，原标识已保留。')
  error.code = code
  return error
}

function inspectMarker(storage) {
  let raw
  try { raw = storage.getItem(ORGANIZATION_CREATE_MARKER_KEY) }
  catch { return { status: 'unreadable' }
  }
  if (raw === null) return { status: 'empty' }
  try { return { status: 'valid', marker: markerShape(JSON.parse(raw)) } }
  catch { return { status: 'invalid' } }
}

export function safeOrganizationCreateMarker(marker) {
  return markerShape(marker)
}

export function loadOrganizationCreateMarker(storage) {
  const inspected = inspectMarker(storage)
  if (inspected.status === 'unreadable') throw markerError('ORGANIZATION_MARKER_UNREADABLE')
  if (inspected.status === 'invalid') throw markerError('ORGANIZATION_MARKER_INVALID')
  return inspected.status === 'valid' ? inspected.marker : null
}

export function persistOrganizationCreateMarker(storage, marker) {
  const safe = markerShape(marker)
  storage.setItem(ORGANIZATION_CREATE_MARKER_KEY, JSON.stringify(safe))
  return safe
}

export function clearOrganizationCreateMarker(storage) {
  storage.removeItem(ORGANIZATION_CREATE_MARKER_KEY)
}

export function clearInvalidOrganizationCreateMarker(storage, { manuallyReconciled = false, confirmed = false } = {}) {
  if (manuallyReconciled !== true || confirmed !== true) throw markerError('ORGANIZATION_MARKER_CONFIRMATION_REQUIRED')
  const inspected = inspectMarker(storage)
  if (inspected.status === 'unreadable') throw markerError('ORGANIZATION_MARKER_UNREADABLE')
  if (inspected.status === 'valid') return { status: 'valid', marker: inspected.marker }
  if (inspected.status === 'empty') return { status: 'empty' }
  try { storage.removeItem(ORGANIZATION_CREATE_MARKER_KEY) }
  catch { throw markerError('ORGANIZATION_MARKER_UNREADABLE') }
  return { status: 'cleared' }
}

export function organizationCreateRetryAllowed({ pendingMarker, actorAccountId, kind, resourceId, exactRetry = false, manualRetypeReady = false, markerInspected = false }) {
  if (!pendingMarker) return !exactRetry
  const matchesOriginal = pendingMarker.actorAccountId === String(actorAccountId)
    && pendingMarker.kind === kind && pendingMarker.resourceId === resourceId
  if (exactRetry) return matchesOriginal && pendingMarker.state === 'unknown'
  return matchesOriginal && pendingMarker.state !== 'sending' && manualRetypeReady === true && markerInspected === true
}

function exactResponseObject(raw, fields, labelText) {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw) || Object.keys(raw).some((key) => !fields.includes(key)) || fields.some((key) => !Object.hasOwn(raw, key))) {
    throw new Error(`${labelText}响应格式无效。`)
  }
}

function receiptSummary(raw, fields, idField, expected, labelText) {
  exactResponseObject(raw, fields, labelText)
  if (typeof raw[idField] !== 'string' || normalizeOrganizationId(raw[idField], `${labelText}ID`) !== expected[idField]) throw new Error(`${labelText}ID与原请求不一致。`)
  if (typeof raw.name !== 'string' || raw.name !== expected.name) throw new Error(`${labelText}名称与原请求不一致。`)
  const codeField = idField === 'clinicId' ? 'clinicCode' : 'employeeNo'
  if (typeof raw[codeField] !== 'string' || raw[codeField] !== expected[codeField]) throw new Error(`${labelText}编号与原请求不一致。`)
  if (raw.active !== true || typeof raw.mutationCount !== 'string' || raw.mutationCount !== '1') throw new Error(`${labelText}状态或创建计数无效。`)
  return { ...raw }
}

function stable(value) {
  if (Array.isArray(value)) return value.map(stable)
  if (value && typeof value === 'object') return Object.fromEntries(Object.keys(value).sort().map((key) => [key, stable(value[key])]))
  return value
}

export function preserveOrganizationCreateReceipt(kind, raw, expectedBody) {
  const fields = kind === 'clinic' ? CLINIC_RECEIPT_FIELDS : kind === 'dentist' ? DENTIST_RECEIPT_FIELDS : null
  if (!fields) throw new Error('不支持的组织创建类型。')
  exactResponseObject(raw, ['replayed', 'resource', 'snapshot'], '创建回执')
  if (typeof raw.replayed !== 'boolean') throw new Error('创建回执重放标记无效。')
  const idField = kind === 'clinic' ? 'clinicId' : 'dentistId'
  const resource = receiptSummary(raw.resource, fields, idField, expectedBody, '创建回执resource')
  const snapshot = receiptSummary(raw.snapshot, fields, idField, expectedBody, '创建回执snapshot')
  if (JSON.stringify(stable(resource)) !== JSON.stringify(stable(snapshot))) throw new Error('创建回执resource与snapshot不一致。')
  return { replayed: raw.replayed, resource, snapshot }
}

export function classifyOrganizationCreateError(error) {
  const status = Number(error?.status)
  if (status === 409) return 'conflict'
  if (status === 400 || status === 401 || status === 403 || (status >= 400 && status < 500)) return 'rejected'
  if (!Number.isFinite(status) || status >= 500) return 'unknown'
  return 'rejected'
}

export function organizationWriteContextMatches(captured, current) {
  return !!captured && !!current && !captured.disposed && !current.disposed
    && captured.accountId === current.accountId && captured.token === current.token
    && captured.scopeKey === current.scopeKey && captured.actorGeneration === current.actorGeneration
    && captured.kind === current.kind && captured.laneTicket === current.laneTicket
}

export function createOrganizationWriteState() {
  let generation = 0
  let busyOwner = null
  return {
    begin() { const ticket = ++generation; busyOwner = ticket; return ticket },
    invalidate() { generation += 1; busyOwner = null; return generation },
    isCurrent(ticket) { return ticket === generation },
    finish(ticket) { if (busyOwner !== ticket) return false; busyOwner = null; return true },
    ownsBusy(ticket) { return busyOwner === ticket },
  }
}

export async function runOrganizationCreateAttempt({ storage, marker, body, post, readCurrent, isCurrent, onPhase, onReadFailure }) {
  let sending
  try {
    const current = inspectMarker(storage)
    if (current.status === 'unreadable') throw markerError('ORGANIZATION_MARKER_UNREADABLE')
    if (current.status === 'invalid') throw markerError('ORGANIZATION_MARKER_INVALID')
    if (current.status === 'valid' && (current.marker.actorAccountId !== marker.actorAccountId || current.marker.kind !== marker.kind || current.marker.resourceId !== marker.resourceId || current.marker.route !== marker.route)) {
      throw markerError('ORGANIZATION_MARKER_PENDING')
    }
    sending = persistOrganizationCreateMarker(storage, { ...marker, state: 'sending' })
  }
  catch (error) { onPhase?.('blocked', error); return { status: 'blocked' } }
  onPhase?.('sending', null, sending)

  let raw
  try { raw = await post(sending.route, body) }
  catch (error) {
    if (!isCurrent()) return { status: 'stale' }
    const classification = classifyOrganizationCreateError(error)
    if (classification === 'unknown') {
      const next = { ...sending, state: 'unknown' }
      try { persistOrganizationCreateMarker(storage, next) } catch { /* prior sending marker remains conservatively unknown after refresh */ }
      onPhase?.('unknown', error, next)
      return { status: 'unknown' }
    }
    if (classification === 'conflict') {
      const next = { ...sending, state: 'conflict' }
      try { persistOrganizationCreateMarker(storage, next) } catch { /* retain the original safe marker */ }
      onPhase?.('conflict', error, next)
      return { status: 'conflict' }
    }
    try { clearOrganizationCreateMarker(storage) } catch { /* only safe metadata can remain */ }
    onPhase?.('rejected', error)
    return { status: 'rejected' }
  }

  let receipt
  try { receipt = preserveOrganizationCreateReceipt(marker.kind, raw, body) }
  catch (error) {
    if (!isCurrent()) return { status: 'stale' }
    const next = { ...sending, state: 'unknown' }
    try { persistOrganizationCreateMarker(storage, next) } catch { /* prior sending marker remains conservatively unknown after refresh */ }
    onPhase?.('unknown', error, next)
    return { status: 'unknown' }
  }
  if (!isCurrent()) return { status: 'stale' }

  let markerCleared = true
  try { clearOrganizationCreateMarker(storage) }
  catch {
    markerCleared = false
    const next = { ...sending, state: 'unknown' }
    try { persistOrganizationCreateMarker(storage, next) } catch { /* sending will restore as unknown */ }
  }
  onPhase?.('confirmed', receipt, markerCleared ? null : { ...sending, state: 'unknown' })
  try { await readCurrent?.(sending.kind, sending.resourceId) }
  catch (error) { onReadFailure?.(error) }
  return { status: 'confirmed', receipt, markerCleared }
}
