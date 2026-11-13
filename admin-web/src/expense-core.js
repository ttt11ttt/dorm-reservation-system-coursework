const MAX_ID = 0x7fffffffffffffffn
const SOURCE_FIELDS = ['expenseId', 'documentNo', 'lineNo', 'kind', 'clinicId', 'occurredOn', 'amount', 'status', 'adjustsExpenseId', 'reason', 'recordedBy', 'recordedAt', 'confirmedBy', 'confirmedAt']
const ALLOCATION_FIELDS = ['allocationId', 'dentistId', 'treatmentId', 'amount', 'allocationBasis']

function id(value, label) {
  const text = String(value ?? '')
  if (!/^\d+$/.test(text) || BigInt(text) <= 0n || BigInt(text) > MAX_ID) throw new Error(`${label}必须是正63位以内十进制字符串。`)
  return text
}

export function expenseMoneyCents(value, label = '金额') {
  if (typeof value !== 'string' || !/^(0|[1-9]\d{0,11})\.\d{2}$/.test(value)) throw new Error(`${label}必须是正数且恰有两位小数的十进制字符串。`)
  const [whole, cents] = value.split('.')
  const amount = BigInt(whole) * 100n + BigInt(cents)
  if (amount <= 0n || amount > 99999999999999n) throw new Error(`${label}必须大于0且不超过999999999999.99。`)
  return amount
}

function signedExpenseMoneyCents(value, label = '金额') {
  if (typeof value !== 'string' || !/^-?(0|[1-9]\d{0,11})\.\d{2}$/.test(value)) throw new Error(`${label}必须是非零且恰有两位小数的十进制字符串。`)
  const negative = value.startsWith('-')
  const unsigned = negative ? value.slice(1) : value
  const [whole, cents] = unsigned.split('.')
  const amount = BigInt(whole) * 100n + BigInt(cents)
  if (amount <= 0n || amount > 99999999999999n) throw new Error(`${label}绝对值必须大于0且不超过999999999999.99。`)
  return negative ? -amount : amount
}

export function makeExpenseCreationPayload({ expenseId, documentNo, lineNo, kind, occurredOn, amount, reason }) {
  const number = Number(lineNo)
  const doc = String(documentNo ?? '')
  const date = String(occurredOn ?? '')
  if (!/^[1-9]\d*$/.test(String(lineNo)) || !Number.isSafeInteger(number) || number > 2147483647) throw new Error('单据行号必须是正JSON整数。')
  if (!doc || doc.length > 64 || doc.trim() !== doc) throw new Error('原始单据号必填、不超过64字符且不得带首尾空白。')
  if (!['医疗用品', '运营费'].includes(kind)) throw new Error('费用类别仅支持医疗用品或运营费。')
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date) || Number(date.slice(0, 4)) < 1000 || Number.isNaN(Date.parse(`${date}T00:00:00.000Z`)) || new Date(`${date}T00:00:00.000Z`).toISOString().slice(0, 10) !== date) throw new Error('费用发生日期必须是有效的YYYY-MM-DD。')
  expenseMoneyCents(amount, '原始费用金额')
  let note = reason == null || String(reason) === '' ? null : String(reason)
  if (note !== null && (!note.trim() || note.length > 1000)) throw new Error('登记原因提供时必须非空且不超过1000字符。')
  return { expenseId: id(expenseId, '费用ID'), documentNo: doc, lineNo: number, kind, occurredOn: date, amount, reason: note }
}

export function makeExpenseAllocation({ allocationId, dentistId, treatmentId, amount, allocationBasis }) {
  const basis = String(allocationBasis ?? '').trim()
  if (!basis || basis.length > 255) throw new Error('归属依据必填且不超过255字符。')
  expenseMoneyCents(amount, '归属金额')
  return {
    allocationId: id(allocationId, '归属ID'), dentistId: id(dentistId, '牙医ID'),
    treatmentId: treatmentId == null || String(treatmentId).trim() === '' ? null : id(treatmentId, '实际治疗ID'),
    amount, allocationBasis: basis,
  }
}

function sortedAllocations(rows) {
  if (!Array.isArray(rows) || rows.length > 100) throw new Error('归属行数最多100项。')
  const result = rows.map((row) => makeExpenseAllocation(row)).sort((a, b) => BigInt(a.allocationId) < BigInt(b.allocationId) ? -1 : BigInt(a.allocationId) > BigInt(b.allocationId) ? 1 : 0)
  if (new Set(result.map((row) => row.allocationId)).size !== result.length) throw new Error('归属ID重复。')
  return result
}

export function expenseAllocationsMatchTotal(amount, rows) {
  const allocations = sortedAllocations(rows)
  return allocations.length > 0 && allocations.reduce((sum, row) => sum + expenseMoneyCents(row.amount, '归属金额'), 0n) === expenseMoneyCents(amount, '原始费用金额')
}

export function makeExpenseConfirmationPayload(snapshot, rows, reason) {
  const expected = preserveExpenseSnapshot(snapshot)
  if (expected.source.adjustsExpenseId !== null) throw new Error('本页仅确认原始费用，调整来源保持只读。')
  const allocations = expected.allocations.length
    ? rows.map((row) => preserveExpenseAllocation(row)).sort((a, b) => BigInt(a.allocationId) < BigInt(b.allocationId) ? -1 : BigInt(a.allocationId) > BigInt(b.allocationId) ? 1 : 0)
    : sortedAllocations(rows)
  if (new Set(allocations.map((row) => row.allocationId)).size !== allocations.length) throw new Error('归属ID重复。')
  if (!allocations.length) throw new Error('至少需要一项完整费用归属。')
  if (!expenseAllocationsMatchTotal(expected.source.amount, allocations)) throw new Error('归属金额必须与原始费用按分精确合计相等。')
  const status = expected.source.status
  if (!['待分摊', '待确认'].includes(status) || expected.source.confirmedBy !== null || expected.source.confirmedAt !== null) throw new Error('该费用已确认或处于终态，只读。')
  if (expected.allocations.length && JSON.stringify(stable(expected.allocations)) !== JSON.stringify(stable(allocations))) throw new Error('已有归属必须整笔原样确认，不能编辑、删行或改挂。')
  if (status === '待确认' && !expected.allocations.length) throw new Error('待确认费用缺少原归属；不能借确认入口补行。')
  const note = String(reason ?? '').trim()
  if (!note || note.length > 1000) throw new Error('确认原因必填且不超过1000字符。')
  return { expected, allocations, reason: note }
}

export function canConfirmExpense(snapshot) {
  if (!snapshot || snapshot.source.adjustsExpenseId !== null || !['待分摊', '待确认'].includes(snapshot.source.status)) return false
  if (snapshot.source.confirmedBy !== null || snapshot.source.confirmedAt !== null || !snapshot.allocations.length) return false
  return snapshot.source.status !== '待确认' || snapshot.allocations.length > 0
}

function onlyKnownFields(value, fields, label) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error(`${label}格式无效。`)
  if (Object.keys(value).some((key) => !fields.includes(key))) throw new Error(`${label}包含未授权字段。`)
  for (const field of fields) if (!Object.hasOwn(value, field)) throw new Error(`${label}缺少字段${field}。`)
}

export function preserveExpenseSource(raw) {
  onlyKnownFields(raw, SOURCE_FIELDS, '费用来源')
  const source = { ...raw }
  source.expenseId = id(source.expenseId, '费用ID')
  source.clinicId = id(source.clinicId, '门店ID')
  source.recordedBy = id(source.recordedBy, '登记人ID')
  if (source.confirmedBy !== null) source.confirmedBy = id(source.confirmedBy, '确认人ID')
  if (source.adjustsExpenseId !== null) source.adjustsExpenseId = id(source.adjustsExpenseId, '调整来源ID')
  if (!Number.isInteger(source.lineNo) || source.lineNo < 1 || source.lineNo > 2147483647) throw new Error('来源单据行号格式无效。')
  if (typeof source.documentNo !== 'string' || !source.documentNo || source.documentNo.length > 64 || source.documentNo.trim() !== source.documentNo) throw new Error('来源单据号格式无效。')
  if (!['医疗用品', '运营费'].includes(source.kind) || !['待分摊', '待确认', '确认', '作废'].includes(source.status)) throw new Error('费用来源类别或状态未知。')
  if (typeof source.occurredOn !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(source.occurredOn)) throw new Error('费用发生日期格式无效。')
  if (source.adjustsExpenseId === null) expenseMoneyCents(source.amount, '原始费用金额')
  else signedExpenseMoneyCents(source.amount, '调整费用金额')
  if (source.reason !== null && typeof source.reason !== 'string') throw new Error('登记原因格式无效。')
  for (const field of ['recordedAt', 'confirmedAt']) if (source[field] !== null && typeof source[field] !== 'string') throw new Error(`${field}格式无效。`)
  return source
}

export function preserveExpenseAllocation(raw, { allowNegative = false } = {}) {
  onlyKnownFields(raw, ALLOCATION_FIELDS, '费用归属')
  const basis = raw.allocationBasis
  if (typeof basis !== 'string' || basis.length === 0 || basis.length > 255) throw new Error('归属依据格式无效。')
  if (allowNegative) signedExpenseMoneyCents(raw.amount, '调整归属金额')
  else expenseMoneyCents(raw.amount, '归属金额')
  return {
    allocationId: id(raw.allocationId, '归属ID'), dentistId: id(raw.dentistId, '牙医ID'),
    treatmentId: raw.treatmentId == null || String(raw.treatmentId) === '' ? null : id(raw.treatmentId, '实际治疗ID'),
    amount: raw.amount, allocationBasis: basis,
  }
}

export function preserveExpenseSnapshot(raw) {
  onlyKnownFields(raw, ['source', 'allocations', 'mutationCount'], '费用完整快照')
  if (!Array.isArray(raw.allocations) || raw.allocations.length > 100 || typeof raw.mutationCount !== 'string' || !/^\d+$/.test(raw.mutationCount)) throw new Error('费用快照归属行或mutationCount无效。')
  const source = preserveExpenseSource(raw.source)
  const allocations = raw.allocations.map((row) => preserveExpenseAllocation(row, { allowNegative: source.adjustsExpenseId !== null })).sort((a, b) => BigInt(a.allocationId) < BigInt(b.allocationId) ? -1 : BigInt(a.allocationId) > BigInt(b.allocationId) ? 1 : 0)
  if (new Set(allocations.map((item) => item.allocationId)).size !== allocations.length) throw new Error('费用快照存在重复归属ID。')
  return { source, allocations, mutationCount: raw.mutationCount }
}

function stable(value) {
  if (Array.isArray(value)) return value.map(stable)
  if (value && typeof value === 'object') return Object.fromEntries(Object.keys(value).sort().map((key) => [key, stable(value[key])]))
  return value
}

export function parseExpenseResponse(raw) {
  if (!raw || typeof raw !== 'object') throw new Error('费用响应格式无效。')
  const expense = preserveExpenseSource(raw.expense)
  const allocations = raw.allocations?.map((row) => preserveExpenseAllocation(row, { allowNegative: expense.adjustsExpenseId !== null }))
  if (!Array.isArray(allocations) || allocations.length > 100) throw new Error('费用响应归属集合无效。')
  const snapshot = preserveExpenseSnapshot(raw.snapshot)
  const sorted = allocations.slice().sort((a, b) => BigInt(a.allocationId) < BigInt(b.allocationId) ? -1 : BigInt(a.allocationId) > BigInt(b.allocationId) ? 1 : 0)
  if (JSON.stringify(stable(expense)) !== JSON.stringify(stable(snapshot.source)) || JSON.stringify(stable(sorted)) !== JSON.stringify(stable(snapshot.allocations))) throw new Error('费用详情与完整expected快照不一致。')
  return { expense, allocations: sorted, snapshot, replayed: raw.replayed === true, quarterReport: String(raw.quarterReport ?? '') }
}

export function assertExpenseResponse(clinicId, expenseId, actorId, raw, { creation = null, confirmation = null } = {}) {
  const result = parseExpenseResponse(raw)
  if (result.expense.expenseId !== String(expenseId) || result.expense.clinicId !== String(clinicId) || result.snapshot.source.expenseId !== String(expenseId)) throw new Error('费用响应与原费用ID或门店不匹配，原请求保留待核对。')
  if (creation) {
    const source = result.expense
    if (source.recordedBy !== String(actorId) || source.documentNo !== creation.documentNo || source.lineNo !== creation.lineNo || source.kind !== creation.kind || source.occurredOn !== creation.occurredOn || source.amount !== creation.amount || source.reason !== creation.reason || source.adjustsExpenseId !== null) throw new Error('登记响应与原录入人、单据行或原始payload不匹配，原请求保留待核对。')
  }
  if (confirmation) {
    const expected = preserveExpenseSnapshot(confirmation.expected)
    if (expected.source.expenseId !== String(expenseId) || expected.source.clinicId !== String(clinicId)) throw new Error('确认请求expected与费用ID或门店不匹配。')
    if (result.expense.status !== '确认' || result.expense.confirmedBy !== String(actorId) || result.expense.confirmedAt === null) throw new Error('确认响应未证明原财务身份完成整笔确认，原请求保留待核对。')
    const stableSource = (source) => Object.fromEntries(SOURCE_FIELDS.filter((field) => !['status', 'confirmedBy', 'confirmedAt'].includes(field)).map((field) => [field, source[field]]))
    if (JSON.stringify(stable(stableSource(result.expense))) !== JSON.stringify(stable(stableSource(expected.source))) || BigInt(result.snapshot.mutationCount) <= BigInt(expected.mutationCount)) throw new Error('确认响应来源字段或新快照版本未证明对应原expected，原请求保留待核对。')
    if (JSON.stringify(stable(result.allocations)) !== JSON.stringify(stable(confirmation.allocations))) throw new Error('确认响应归属集合与原完整请求不一致，原请求保留待核对。')
  }
  return result
}

export function canReadExpenses(identity, clinicId) {
  const grants = employeeGrants(identity)
  const target = canonicalScopeId(clinicId)
  if (target === null) return false
  return grants.some((grant) => (grant.role_code === '财务' && (isChainScopeGrant(grant) || grantMatchesClinicScope(grant, target)))
    || (grant.role_code === '总部管理' && isChainScopeGrant(grant)))
}

export function canWriteExpenses(identity, clinicId) {
  const target = canonicalScopeId(clinicId)
  if (target === null) return false
  return employeeGrants(identity).some((grant) => grant.role_code === '财务' && (isChainScopeGrant(grant) || grantMatchesClinicScope(grant, target)))
}

export function canOpenExpenseWorkspace(identity) {
  return employeeGrants(identity).some((grant) => (grant.role_code === '财务' && isValidGrantScope(grant))
    || (grant.role_code === '总部管理' && isChainScopeGrant(grant)))
}

function employeeGrants(identity) {
  return identity?.kind === '员工' && Array.isArray(identity.grants) ? identity.grants : []
}

function canonicalScopeId(value) {
  if (typeof value === 'number' && Number.isSafeInteger(value) && value > 0) return String(value)
  if (typeof value !== 'string' || !/^[1-9]\d*$/.test(value)) return null
  try { return BigInt(value) <= MAX_ID ? value : null } catch { return null }
}

export function isChainScopeGrant(grant) {
  return !!grant && grant.scope_kind === '连锁' && grant.clinic_id === null
}

export function isClinicScopeGrant(grant) {
  return !!grant && grant.scope_kind === '门店' && canonicalScopeId(grant.clinic_id) !== null
}

export function isValidGrantScope(grant) {
  return isChainScopeGrant(grant) || isClinicScopeGrant(grant)
}

export function grantMatchesClinicScope(grant, clinicId) {
  const requested = canonicalScopeId(clinicId)
  return requested !== null && isClinicScopeGrant(grant) && canonicalScopeId(grant.clinic_id) === requested
}

export function expensePendingScope(accountId, clinicId, action) {
  return { namespace: 'adc.expense.pending', accountId: String(accountId), sourceClinicId: String(clinicId), appointmentId: `clinic:${clinicId}`, action }
}

export function expenseListEndpoint(clinicId, { status = '', afterId = null, limit = 20 } = {}) {
  const query = new URLSearchParams()
  if (status) query.set('status', status)
  if (afterId != null) query.set('afterId', id(afterId, '分页游标'))
  query.set('limit', String(limit))
  if (!Number.isInteger(limit) || limit < 1 || limit > 100) throw new Error('费用列表limit必须在1至100之间。')
  return `/finance/clinics/${encodeURIComponent(String(clinicId))}/expenses?${query.toString()}`
}

export function assertExpenseList(clinicId, raw) {
  if (!raw || !Array.isArray(raw.items) || typeof raw.hasMore !== 'boolean') throw new Error('费用分页响应无效。')
  const items = raw.items.map(preserveExpenseSource)
  if (items.some((item) => item.clinicId !== String(clinicId))) throw new Error('费用列表包含其他门店记录。')
  for (let index = 1; index < items.length; index += 1) if (BigInt(items[index - 1].expenseId) >= BigInt(items[index].expenseId)) throw new Error('费用列表ID顺序或唯一性无效。')
  const nextAfterId = raw.nextAfterId == null ? null : id(raw.nextAfterId, '下一页游标')
  if (raw.hasMore && (!items.length || nextAfterId !== items.at(-1).expenseId)) throw new Error('费用分页游标与原页面末项不一致。')
  if (!raw.hasMore && nextAfterId !== null) throw new Error('费用列表结束页仍返回nextAfterId。')
  return { items, hasMore: raw.hasMore, nextAfterId }
}
