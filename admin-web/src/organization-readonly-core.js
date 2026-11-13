import { isChainScopeGrant, isClinicScopeGrant } from './expense-core.js'

const MAX_ID = 0x7fffffffffffffffn
const CLINIC_SUMMARY_FIELDS = ['clinicId', 'clinicCode', 'name', 'active', 'mutationCount']
const DENTIST_SUMMARY_FIELDS = ['dentistId', 'employeeNo', 'name', 'active', 'mutationCount']
const CLINIC_DETAIL_FIELDS = [...CLINIC_SUMMARY_FIELDS, 'postalAddress', 'managerName', 'phone', 'email', 'areaM2']
const DENTIST_DETAIL_FIELDS = [...DENTIST_SUMMARY_FIELDS, 'birthDate', 'homeAddress', 'phone', 'email']

export function clearOrganizationPrivateDetail(detailRef) {
  detailRef.value = null
}

export function organizationReadAccess(identity) {
  const grants = identity?.kind === '员工' && Array.isArray(identity.grants) ? identity.grants : []
  const operational = grants.filter((grant) => grant.role_code === '运营人员／店长')
  const chain = operational.some(isChainScopeGrant)
  const clinicIds = [...new Set(operational.filter(isClinicScopeGrant).map((grant) => String(grant.clinic_id)))].sort((a, b) => BigInt(a) < BigInt(b) ? -1 : BigInt(a) > BigInt(b) ? 1 : 0)
  return { employee: identity?.kind === '员工', allowed: chain || clinicIds.length > 0, chain, clinicIds }
}

function id(value, label) {
  if (typeof value !== 'string' || !/^[1-9]\d*$/.test(value)) throw new Error(`${label}格式无效。`)
  try { if (BigInt(value) <= MAX_ID) return value } catch { /* invalid integer text */ }
  throw new Error(`${label}格式无效。`)
}

function exactObject(value, fields, label) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error(`${label}格式无效。`)
  if (Object.keys(value).some((key) => !fields.includes(key))) throw new Error(`${label}包含未知字段。`)
  for (const field of fields) if (!Object.hasOwn(value, field)) throw new Error(`${label}缺少字段${field}。`)
}

function count(value) {
  if (typeof value !== 'string' || !/^(0|[1-9]\d{0,18})$/.test(value)) throw new Error('组织mutationCount格式无效。')
  return value
}

function summary(raw, fields, idField, label) {
  exactObject(raw, fields, label)
  const result = { ...raw }
  result[idField] = id(result[idField], label + 'ID')
  if (typeof result.active !== 'boolean') throw new Error(`${label}状态格式无效。`)
  if (typeof result.name !== 'string' || !result.name || result.name.length > 100) throw new Error(`${label}名称格式无效。`)
  const otherLabel = idField === 'clinicId' ? 'clinicCode' : 'employeeNo'
  if (typeof result[otherLabel] !== 'string' || !result[otherLabel] || result[otherLabel].length > 100) throw new Error(`${label}编号格式无效。`)
  result.mutationCount = count(result.mutationCount)
  return result
}

function stable(value) {
  if (Array.isArray(value)) return value.map(stable)
  if (value && typeof value === 'object') return Object.fromEntries(Object.keys(value).sort().map((key) => [key, stable(value[key])]))
  return value
}

export function organizationClinicListRequest(access, { afterId = null, limit = 30 } = {}) {
  requireOrganizationAccess(access)
  if (!Number.isInteger(limit) || limit < 1 || limit > 50) throw new Error('每页数量必须在1至50之间。')
  const query = new URLSearchParams()
  if (afterId !== null) query.set('afterId', id(afterId, '门店分页游标'))
  query.set('limit', String(limit))
  return { endpoint: `/org/clinics?${query.toString()}`, afterId, limit, scope: { chain: access.chain, clinicIds: [...access.clinicIds] } }
}

export function organizationDentistListRequest(access, { afterId = null, limit = 30 } = {}) {
  requireChainOrganizationAccess(access)
  if (!Number.isInteger(limit) || limit < 1 || limit > 50) throw new Error('每页数量必须在1至50之间。')
  const query = new URLSearchParams()
  if (afterId !== null) query.set('afterId', id(afterId, '牙医分页游标'))
  query.set('limit', String(limit))
  return { endpoint: `/org/dentists?${query.toString()}`, afterId, limit, scope: { chain: access.chain, clinicIds: [...access.clinicIds] } }
}

export function organizationClinicDetailRequest(access, clinicId) {
  requireOrganizationAccess(access)
  const target = id(clinicId, '门店ID')
  if (!access.chain && !access.clinicIds.includes(target)) throw new Error('门店运营只能读取自己获权门店详情。')
  return { endpoint: `/org/clinics/${target}`, clinicId: target, scope: { chain: access.chain, clinicIds: [...access.clinicIds] } }
}

export function organizationDentistDetailRequest(access, dentistId) {
  requireChainOrganizationAccess(access)
  return { endpoint: `/org/dentists/${id(dentistId, '牙医ID')}`, dentistId: id(dentistId, '牙医ID'), scope: { chain: access.chain, clinicIds: [...access.clinicIds] } }
}

export function preserveOrganizationClinicList(raw, request) {
  const page = preservePage(raw, CLINIC_SUMMARY_FIELDS, 'clinicId', '门店')
  for (let index = 1; index < page.items.length; index += 1) if (BigInt(page.items[index - 1].clinicId) >= BigInt(page.items[index].clinicId)) throw new Error('门店列表必须按clinicId严格升序。')
  validateCursor(page, request, 'clinicId')
  if (!request.scope.chain && page.items.some((item) => !request.scope.clinicIds.includes(item.clinicId))) throw new Error('门店列表超出当前运营授权范围。')
  return page
}

export function preserveOrganizationDentistList(raw, request) {
  const page = preservePage(raw, DENTIST_SUMMARY_FIELDS, 'dentistId', '牙医')
  for (let index = 1; index < page.items.length; index += 1) if (BigInt(page.items[index - 1].dentistId) >= BigInt(page.items[index].dentistId)) throw new Error('牙医列表必须按dentistId严格升序。')
  validateCursor(page, request, 'dentistId')
  return page
}

function preservePage(raw, fields, idField, label) {
  exactObject(raw, ['items', 'hasMore', 'nextAfterId'], `${label}列表`)
  if (!Array.isArray(raw.items) || typeof raw.hasMore !== 'boolean') throw new Error(`${label}列表格式无效。`)
  const items = raw.items.map((item) => summary(item, fields, idField, label))
  const nextAfterId = raw.nextAfterId === null ? null : id(raw.nextAfterId, `${label}下一页游标`)
  return { items, hasMore: raw.hasMore, nextAfterId }
}

function validateCursor(page, request, idField) {
  if (request.afterId !== null && page.items.some((item) => BigInt(item[idField]) <= BigInt(request.afterId))) throw new Error('列表响应包含分页游标之前的资源。')
  if (page.hasMore && (!page.items.length || page.nextAfterId !== page.items.at(-1)[idField])) throw new Error('下一页游标与本页末项不一致。')
  if (!page.hasMore && page.nextAfterId !== null) throw new Error('末页不应返回下一页游标。')
}

export function preserveOrganizationClinicDetail(raw, request) {
  const { resource, snapshot } = preserveDetailEnvelope(raw, CLINIC_DETAIL_FIELDS, 'clinicId', '门店详情')
  if (resource.clinicId !== request.clinicId) throw new Error('门店详情与请求ID不一致。')
  for (const field of ['postalAddress', 'managerName', 'phone', 'email']) if (typeof resource[field] !== 'string') throw new Error(`门店详情${field}格式无效。`)
  if (typeof resource.areaM2 !== 'string' || !/^(0|[1-9]\d{0,7})\.\d{2}$/.test(resource.areaM2)) throw new Error('门店面积格式无效。')
  return { resource, snapshot }
}

export function preserveOrganizationDentistDetail(raw, request) {
  const { resource, snapshot } = preserveDetailEnvelope(raw, DENTIST_DETAIL_FIELDS, 'dentistId', '牙医详情')
  if (resource.dentistId !== request.dentistId) throw new Error('牙医详情与请求ID不一致。')
  if (typeof resource.birthDate !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(resource.birthDate) || typeof resource.homeAddress !== 'string' || typeof resource.phone !== 'string' || typeof resource.email !== 'string') throw new Error('牙医私密详情格式无效。')
  return { resource, snapshot }
}

function preserveDetailEnvelope(raw, fields, idField, label) {
  exactObject(raw, ['resource', 'snapshot'], label + '响应')
  const resource = detail(raw.resource, fields, idField, label)
  const snapshot = detail(raw.snapshot, fields, idField, label)
  if (JSON.stringify(stable(resource)) !== JSON.stringify(stable(snapshot))) throw new Error(`${label}resource与snapshot不一致。`)
  return { resource, snapshot }
}

function detail(raw, fields, idField, label) {
  const baseFields = idField === 'clinicId' ? CLINIC_SUMMARY_FIELDS : DENTIST_SUMMARY_FIELDS
  exactObject(raw, fields, label)
  const result = summary(Object.fromEntries(baseFields.map((field) => [field, raw[field]])), baseFields, idField, label)
  for (const field of fields) if (!Object.hasOwn(raw, field)) throw new Error(`${label}缺少字段${field}。`)
  return { ...result, ...Object.fromEntries(fields.filter((field) => !baseFields.includes(field)).map((field) => [field, raw[field]])) }
}

function requireOrganizationAccess(access) {
  if (!access?.employee || !access.allowed) throw new Error('当前员工没有运营人员／店长组织读取授权。')
}
function requireChainOrganizationAccess(access) {
  requireOrganizationAccess(access)
  if (!access.chain) throw new Error('全局牙医目录仅对连锁运营开放。')
}
