import { isChainScopeGrant, isValidGrantScope } from './expense-core.js'

const MAX_ID = 0x7fffffffffffffffn
const MONEY = /^-?(0|[1-9]\d*)\.\d{2}$/

export function quarterReadAccess(identity) {
  const grants = identity?.kind === '员工' && Array.isArray(identity.grants) ? identity.grants : []
  return {
    finance: grants.some((grant) => grant.role_code === '财务' && isValidGrantScope(grant)),
    headquarters: grants.some((grant) => grant.role_code === '总部管理' && isChainScopeGrant(grant)),
  }
}

function reportId(value, label) {
  if (typeof value !== 'string' || !/^\d+$/.test(value) || BigInt(value) <= 0n || BigInt(value) > MAX_ID) throw new Error(`${label}必须是正63位以内十进制字符串。`)
  return value
}

export function quarterReadRequest(mode, { year, quarter, clinicId = '', dentistId = '', limit = 20 } = {}) {
  if (!['finance', 'headquarters'].includes(mode)) throw new Error('季度报表入口无效。')
  const yearText = String(year ?? '')
  const quarterText = String(quarter ?? '')
  if (!/^\d{4}$/.test(yearText) || !Number.isInteger(Number(yearText))) throw new Error('年份必须是四位整数。')
  if (!/^[1-4]$/.test(quarterText)) throw new Error('季度必须是1至4。')
  const pageSize = Number(limit)
  if (!Number.isInteger(pageSize) || pageSize < 1 || pageSize > 100) throw new Error('每页数量必须在1至100之间。')
  const params = new URLSearchParams({ year: yearText, quarter: quarterText })
  const clinic = String(clinicId ?? '').trim()
  const dentist = String(dentistId ?? '').trim()
  if (clinic) params.set('clinicId', reportId(clinic, '门店ID'))
  if (dentist) params.set('dentistId', reportId(dentist, '牙医ID'))
  params.set('limit', String(pageSize))
  return { endpoint: `/${mode === 'finance' ? 'finance' : 'headquarters'}/quarter?${params.toString()}`, year: Number(yearText), quarter: Number(quarterText), clinicId: clinic || null, dentistId: dentist || null, limit: pageSize }
}

function count(value, label) {
  if (typeof value === 'number' && Number.isSafeInteger(value) && value >= 0) return String(value)
  if (typeof value === 'string' && /^\d+$/.test(value)) return value
  throw new Error(`${label}计数格式无效。`)
}

function money(value, label) {
  if (typeof value !== 'string' || !MONEY.test(value)) throw new Error(`${label}必须是精确两位小数字符串。`)
  return value
}

function amounts(value, label) {
  if (!value || typeof value !== 'object') throw new Error(`${label}金额集合无效。`)
  return {
    revenue: money(value.revenue, `${label}收入`),
    supplies: money(value.supplies, `${label}医疗用品`),
    operations: money(value.operations, `${label}运营费`),
    profit: money(value.profit, `${label}利润`),
  }
}

function nullableId(value, label) {
  return value === null ? null : reportId(value, label)
}

export function preserveQuarterReport(raw, request, mode) {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) throw new Error('季度报表响应格式无效。')
  if (raw.year !== request.year || raw.quarter !== request.quarter) throw new Error('季度报表响应与所选年月不一致。')
  if (raw.pagination !== 'FIRST_PAGE_ONLY' || raw.laterPageConsistency !== 'NEW_REALTIME_VIEW' || raw.basis !== 'REGISTERED_VALID_SOURCES_REALTIME_ONLY') throw new Error('季度报表分页或归集口径未知。')
  const display = raw.displayScope
  if (!display || typeof display !== 'object') throw new Error('季度报表展示范围缺失。')
  const displayClinicId = nullableId(display.clinicId, '展示门店ID')
  const displayDentistId = nullableId(display.dentistId, '展示牙医ID')
  if (displayClinicId !== request.clinicId || displayDentistId !== request.dentistId) throw new Error('季度报表展示范围与所选筛选条件不一致。')
  const integrity = raw.integrity
  if (!integrity || !['REGISTERED_SOURCES_CHECKED', 'INCOMPLETE'].includes(integrity.status) || typeof integrity.knownSourcesComplete !== 'boolean') throw new Error('季度报表完整性状态未知。')
  const blockingSources = count(integrity.blockingSources, '异常来源')
  const anomalyCounts = raw.anomalyCounts
  if (!Array.isArray(anomalyCounts)) throw new Error('季度报表完整异常计数无效。')
  const preservedAnomalyCounts = anomalyCounts.map((row) => {
    if (!row || typeof row.source_type !== 'string' || typeof row.reason !== 'string') throw new Error('季度异常计数组格式无效。')
    return { source_type: row.source_type, reason: row.reason, source_count: count(row.source_count, '异常来源') }
  })
  const anomalyTotal = preservedAnomalyCounts.reduce((sum, row) => sum + BigInt(row.source_count), 0n)
  if (anomalyTotal !== BigInt(blockingSources) || (blockingSources === '0') !== (integrity.status === 'REGISTERED_SOURCES_CHECKED') || integrity.knownSourcesComplete !== (blockingSources === '0')) throw new Error('季度异常计数与完整性状态不一致。')
  const sourceCountsRaw = raw.sourceCounts
  if (!sourceCountsRaw || typeof sourceCountsRaw !== 'object') throw new Error('季度来源计数缺失。')
  const sourceCounts = Object.fromEntries(['completion_sources', 'valid_invoice_sources', 'expense_sources', 'valid_expense_sources'].map((key) => [key, count(sourceCountsRaw[key], key)]))
  if (!Array.isArray(raw.integrityClinicIds)) throw new Error('季度完整性门店范围无效。')
  const integrityClinicIds = raw.integrityClinicIds.map((value) => reportId(value, '完整性门店ID'))
  const scopeTotals = amounts(raw.scopeTotals, '当前筛选范围')
  const chainTotals = raw.chainTotals === null ? null : amounts(raw.chainTotals, '全链')
  if (mode === 'headquarters' && (raw.scopeMode !== 'HEADQUARTERS_CHAIN' || chainTotals === null)) throw new Error('总部入口未返回全链范围总额。')
  if (mode === 'finance' && (!['FINANCE_AUTHORIZED_CLINICS', 'FINANCE_CHAIN_PARTIAL_FUND'].includes(raw.scopeMode) || chainTotals !== null)) throw new Error('财务入口范围或全链总额语义无效。')
  const fundReason = raw.fundReason
  const companyFund = raw.companyFund === null ? null : money(raw.companyFund, '公司基金')
  if (mode === 'finance' && (companyFund !== null || fundReason !== 'PARTIAL_SCOPE')) throw new Error('财务入口公司基金必须标记为不可用。')
  if (mode === 'headquarters') {
    if (blockingSources !== '0' && (companyFund !== null || fundReason !== 'KNOWN_BLOCKING_SOURCES')) throw new Error('存在阻断异常时公司基金必须为空。')
    if (blockingSources === '0' && (companyFund === null || fundReason !== 'REGISTERED_SOURCES_ESTIMATE')) throw new Error('无已知阻断时总部基金估算字段无效。')
  }
  if (typeof raw.contributions !== 'object' || !Array.isArray(raw.contributions.items) || typeof raw.contributions.hasMore !== 'boolean' || raw.contributions.limit !== request.limit) throw new Error('季度贡献首屏数据无效。')
  const contributions = raw.contributions.items.map((row) => ({
    clinicId: reportId(row.clinicId, '贡献门店ID'), dentistId: reportId(row.dentistId, '贡献牙医ID'), amounts: amounts(row.amounts, '贡献行'),
  }))
  if (typeof raw.anomalies !== 'object' || !Array.isArray(raw.anomalies.items) || typeof raw.anomalies.hasMore !== 'boolean' || raw.anomalies.limit !== request.limit) throw new Error('季度异常首屏数据无效。')
  const anomalies = raw.anomalies.items.map((row) => {
    if (typeof row.sourceType !== 'string' || typeof row.reason !== 'string') throw new Error('季度异常行格式无效。')
    for (const field of ['completedAt', 'occurredOn']) if (row[field] !== null && typeof row[field] !== 'string') throw new Error(`季度异常${field}格式无效。`)
    return { sourceType: row.sourceType, sourceId: reportId(row.sourceId, '异常来源ID'), clinicId: reportId(row.clinicId, '异常门店ID'), completedAt: row.completedAt, occurredOn: row.occurredOn, reason: row.reason }
  })
  return {
    year: raw.year, quarter: raw.quarter, startDate: String(raw.startDate), endDateExclusive: String(raw.endDateExclusive), utcStart: String(raw.utcStart), utcEndExclusive: String(raw.utcEndExclusive),
    readStartedAt: String(raw.readStartedAt), computedAt: String(raw.computedAt), scopeMode: raw.scopeMode,
    displayScope: { clinicId: displayClinicId, dentistId: displayDentistId }, integrityClinicIds, scopeTotals, chainTotals,
    contributionCount: count(raw.contributionCount, '贡献'), sourceCounts,
    integrity: { blockingSources, knownSourcesComplete: integrity.knownSourcesComplete, status: integrity.status }, anomalyCounts: preservedAnomalyCounts,
    companyFund, fundReason, contributions: { items: contributions, hasMore: raw.contributions.hasMore, limit: raw.contributions.limit },
    anomalies: { items: anomalies, hasMore: raw.anomalies.hasMore, limit: raw.anomalies.limit }, pagination: raw.pagination, laterPageConsistency: raw.laterPageConsistency, basis: raw.basis,
  }
}

export function quarterRequestMatches(captured, current) {
  return !!captured && !!current && captured.scopeKey === current.scopeKey && captured.accountId === current.accountId && captured.token === current.token
}

export function quarterScopeKey(mode, request) {
  return JSON.stringify([mode, request.year, request.quarter, request.clinicId, request.dentistId, request.limit])
}
