import { isChainScopeGrant, isClinicScopeGrant } from './expense-core.js'

const MAX_ID = 0x7fffffffffffffffn
const MAX_COUNT = 0x7fffffffffffffffn
export const STAFF_ADMIN_ROLES = ['前台', '牙医', '财务', '运营人员／店长', '总部管理', '账号管理']
const SCOPE_KINDS = ['门店', '连锁']
const SNAPSHOT_FIELDS = ['accountId', 'loginName', 'displayName', 'active', 'dentistId', 'grants', 'mutationCount']

export function staffAdminId(value, label = 'ID') {
  if (typeof value !== 'string' || !/^[1-9]\d*$/.test(value)) throw new Error(`${label}必须是正63位以内十进制字符串。`)
  try { if (BigInt(value) <= MAX_ID) return value } catch { /* invalid integer text */ }
  throw new Error(`${label}必须是正63位以内十进制字符串。`)
}

function employeeGrants(identity) {
  return identity?.kind === '员工' && Array.isArray(identity.grants) ? identity.grants : []
}

function grantClinicId(grant) {
  if (typeof grant?.clinic_id === 'number' && Number.isSafeInteger(grant.clinic_id) && grant.clinic_id > 0) return String(grant.clinic_id)
  if (typeof grant?.clinic_id === 'string') {
    try { return staffAdminId(grant.clinic_id, '授权门店ID') } catch { return null }
  }
  return null
}

export function staffAdminAccess(identity) {
  const accountGrants = employeeGrants(identity).filter((grant) => grant.role_code === '账号管理')
  const chain = accountGrants.some(isChainScopeGrant)
  const clinicIds = [...new Set(accountGrants.filter(isClinicScopeGrant).map(grantClinicId).filter(Boolean))].sort((a, b) => BigInt(a) < BigInt(b) ? -1 : BigInt(a) > BigInt(b) ? 1 : 0)
  return { employee: identity?.kind === '员工', accountId: typeof identity?.accountId === 'string' ? identity.accountId : '', allowed: chain || clinicIds.length > 0, chain, clinicIds }
}

function currentScope(access, clinicId = null) {
  if (!access?.employee || !access.allowed) throw new Error('当前身份没有有效员工账号管理授权。')
  if (access.chain) {
    const clinic = clinicId == null || String(clinicId).trim() === '' ? null : staffAdminId(String(clinicId).trim(), '门店ID')
    return { chain: true, clinicId: clinic }
  }
  const clinic = clinicId == null || String(clinicId).trim() === '' ? null : staffAdminId(String(clinicId).trim(), '门店ID')
  if (!clinic || !access.clinicIds.includes(clinic)) throw new Error('门店账号管理员只能选择自己获权的门店。')
  return { chain: false, clinicId: clinic }
}

function queryFor({ clinicId = null, afterId = null, limit = 30 } = {}) {
  const query = new URLSearchParams()
  if (clinicId !== null) query.set('clinicId', staffAdminId(clinicId, '门店ID'))
  if (afterId !== null) query.set('afterId', staffAdminId(afterId, '分页游标'))
  if (!Number.isInteger(limit) || limit < 1 || limit > 50) throw new Error('每页数量必须在1至50之间。')
  query.set('limit', String(limit))
  return query.toString()
}

export function staffAdminListRequest(access, { clinicId = null, afterId = null, limit = 30 } = {}) {
  const scope = currentScope(access, clinicId)
  return { method: 'GET', endpoint: `/staff-admin/accounts?${queryFor({ clinicId: scope.clinicId, afterId, limit })}`, scope, afterId, limit }
}

export function staffAdminDetailRequest(access, accountId, clinicId = null) {
  const scope = currentScope(access, clinicId)
  const query = new URLSearchParams()
  if (scope.clinicId !== null) query.set('clinicId', scope.clinicId)
  const suffix = query.size ? `?${query.toString()}` : ''
  return { method: 'GET', endpoint: `/staff-admin/accounts/${staffAdminId(accountId, '员工账号ID')}${suffix}`, accountId: staffAdminId(accountId, '员工账号ID'), scope }
}

function exactObject(value, allowedKeys, label) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error(`${label}格式无效。`)
  if (Object.keys(value).some((key) => !allowedKeys.includes(key))) throw new Error(`${label}包含未授权字段。`)
}

function optionalId(value, label) {
  return value === null ? null : staffAdminId(value, label)
}

export function preserveStaffAdminSnapshot(raw) {
  exactObject(raw, SNAPSHOT_FIELDS, '员工账号快照')
  for (const key of SNAPSHOT_FIELDS) if (!Object.hasOwn(raw, key)) throw new Error(`员工账号快照缺少${key}。`)
  const accountId = staffAdminId(raw.accountId, '员工账号ID')
  if (typeof raw.loginName !== 'string' || !raw.loginName || raw.loginName.length > 64 || typeof raw.displayName !== 'string' || !raw.displayName || raw.displayName.length > 80) throw new Error('员工登录名或显示名格式无效。')
  if (typeof raw.active !== 'boolean') throw new Error('员工启停状态格式无效。')
  const dentistId = optionalId(raw.dentistId, '牙医ID')
  if (!Array.isArray(raw.grants) || raw.grants.length > 512) throw new Error('员工授权集合格式无效。')
  const grants = raw.grants.map((grant) => {
    exactObject(grant, ['grantId', 'roleCode', 'scopeKind', 'clinicId'], '员工授权')
    for (const key of ['grantId', 'roleCode', 'scopeKind', 'clinicId']) if (!Object.hasOwn(grant, key)) throw new Error(`员工授权缺少${key}。`)
    const grantId = staffAdminId(grant.grantId, '授权ID')
    if (!STAFF_ADMIN_ROLES.includes(grant.roleCode) || !SCOPE_KINDS.includes(grant.scopeKind)) throw new Error('员工授权角色或范围未知。')
    const clinicId = optionalId(grant.clinicId, '授权门店ID')
    if ((grant.scopeKind === '门店' && clinicId === null) || (grant.scopeKind === '连锁' && clinicId !== null) || (grant.roleCode === '总部管理' && grant.scopeKind !== '连锁')) throw new Error('员工授权scope与门店ID不匹配。')
    return { grantId, roleCode: grant.roleCode, scopeKind: grant.scopeKind, clinicId }
  })
  for (let index = 1; index < grants.length; index += 1) if (BigInt(grants[index - 1].grantId) >= BigInt(grants[index].grantId)) throw new Error('员工授权必须按唯一grantId升序返回。')
  if (typeof raw.mutationCount !== 'string' || !/^\d+$/.test(raw.mutationCount) || BigInt(raw.mutationCount) > MAX_COUNT) throw new Error('员工mutationCount格式无效。')
  return { accountId, loginName: raw.loginName, displayName: raw.displayName, active: raw.active, dentistId, grants, mutationCount: raw.mutationCount }
}

function assertSnapshotScope(snapshot, scope) {
  if (!scope.chain) {
    if (!scope.clinicId) throw new Error('门店管理员请求缺少获权门店范围。')
    if (snapshot.grants.some((grant) => grant.scopeKind !== '门店' || grant.clinicId !== scope.clinicId)) throw new Error('门店视图响应暴露了范围外授权。')
  }
  return snapshot
}

function stable(value) {
  if (Array.isArray(value)) return value.map(stable)
  if (value && typeof value === 'object') return Object.fromEntries(Object.keys(value).sort().map((key) => [key, stable(value[key])]))
  return value
}

export function staffAdminSnapshotsEqual(a, b) {
  return JSON.stringify(stable(a)) === JSON.stringify(stable(b))
}

export function preserveStaffAdminList(raw, request) {
  exactObject(raw, ['items', 'hasMore', 'nextAfterId'], '员工账号列表响应')
  for (const key of ['items', 'hasMore', 'nextAfterId']) if (!Object.hasOwn(raw, key)) throw new Error(`员工账号列表响应缺少${key}。`)
  if (!Array.isArray(raw.items) || typeof raw.hasMore !== 'boolean') throw new Error('员工账号列表响应格式无效。')
  const items = raw.items.map((item) => assertSnapshotScope(preserveStaffAdminSnapshot(item), request.scope))
  for (let index = 1; index < items.length; index += 1) if (BigInt(items[index - 1].accountId) >= BigInt(items[index].accountId)) throw new Error('员工账号列表必须按accountId严格升序。')
  if (request.afterId !== null && items.some((item) => BigInt(item.accountId) <= BigInt(request.afterId))) throw new Error('员工账号列表包含分页游标之前的账号。')
  const nextAfterId = raw.nextAfterId === null ? null : staffAdminId(raw.nextAfterId, '下一页游标')
  if (raw.hasMore && (!items.length || nextAfterId !== items.at(-1).accountId)) throw new Error('员工账号下一页游标与页面末项不一致。')
  if (!raw.hasMore && nextAfterId !== null) throw new Error('员工账号末页仍带下一页游标。')
  return { items, hasMore: raw.hasMore, nextAfterId }
}

export function preserveStaffAdminDetail(raw, request) {
  exactObject(raw, ['account', 'snapshot'], '员工账号详情响应')
  for (const key of ['account', 'snapshot']) if (!Object.hasOwn(raw, key)) throw new Error(`员工账号详情响应缺少${key}。`)
  const account = assertSnapshotScope(preserveStaffAdminSnapshot(raw.account), request.scope)
  const snapshot = assertSnapshotScope(preserveStaffAdminSnapshot(raw.snapshot), request.scope)
  if (account.accountId !== request.accountId || snapshot.accountId !== request.accountId || !staffAdminSnapshotsEqual(account, snapshot)) throw new Error('员工账号详情与原账号ID或完整快照不一致。')
  if (!request.scope.chain && !account.grants.length) throw new Error('门店管理员详情未返回本店有效授权。')
  return { account, snapshot }
}

function validateGrantScope(access, roleCode, scopeKind, clinicId) {
  if (!STAFF_ADMIN_ROLES.includes(roleCode) || !SCOPE_KINDS.includes(scopeKind)) throw new Error('请选择契约支持的员工角色和scope。')
  if (roleCode === '总部管理' && scopeKind !== '连锁') throw new Error('总部管理授权只能使用连锁scope。')
  if (scopeKind === '连锁') {
    if (clinicId !== null && clinicId !== '') throw new Error('连锁授权的门店ID必须为空。')
    if (!access.chain) throw new Error('门店管理员不能授予连锁范围。')
    return { roleCode, scopeKind, clinicId: null }
  }
  const clinic = staffAdminId(String(clinicId ?? '').trim(), '授权门店ID')
  if (!access.chain && !access.clinicIds.includes(clinic)) throw new Error('门店管理员只能授予自己的获权门店。')
  return { roleCode, scopeKind, clinicId: clinic }
}

function reasonText(value) {
  const reason = String(value ?? '').trim()
  if (!reason || reason.length > 1000) throw new Error('操作原因必填且不超过1000字符。')
  return reason
}

function operationId(value) { return staffAdminId(value, '操作ID') }

export function createStaffAdminOperationId(cryptoSource = globalThis.crypto) {
  if (!cryptoSource?.getRandomValues) throw new Error('当前环境不能安全生成固定操作ID。')
  const bytes = cryptoSource.getRandomValues(new Uint32Array(2))
  const value = ((BigInt(bytes[0]) << 32n) | BigInt(bytes[1])) & MAX_ID
  return (value === 0n ? 1n : value).toString()
}

function makeMutation(action, method, endpoint, body, targetAccountId, scope, extra = {}) {
  return { action, method, endpoint, body, targetAccountId, scope: { chain: scope.chain, clinicId: scope.clinicId }, ...extra }
}

export function buildStaffAdminCreate(access, input) {
  const actorId = staffAdminId(access.accountId, '操作者账号ID')
  if (!access.allowed) throw new Error('当前身份没有账号管理授权。')
  const accountId = staffAdminId(String(input.accountId ?? '').trim(), '员工账号ID')
  const grantId = staffAdminId(String(input.grantId ?? '').trim(), '初始授权ID')
  const loginName = String(input.loginName ?? '').trim()
  const displayName = String(input.displayName ?? '').trim()
  if (!loginName || loginName.length > 64 || !displayName || displayName.length > 80) throw new Error('登录名必填且不超过64字符，显示名必填且不超过80字符。')
  const password = String(input.password ?? '')
  if (password.length < 16 || password.length > 256) throw new Error('初始密码需为16至256字符。')
  const dentistId = input.dentistId == null || String(input.dentistId).trim() === '' ? null : staffAdminId(String(input.dentistId).trim(), '牙医ID')
  if (dentistId !== null && !access.chain) throw new Error('门店管理员不能首次绑定牙医账号。')
  const initialGrant = validateGrantScope(access, input.roleCode, input.scopeKind, input.clinicId)
  if (initialGrant.roleCode === '牙医' && dentistId === null) throw new Error('牙医账号需由连锁管理员显式输入已有牙医ID。')
  if (accountId === actorId) throw new Error('不能使用账号管理者本人账号ID创建员工。')
  const body = { accountId, grantId, loginName, displayName, initialGrant, password }
  if (dentistId !== null) body.dentistId = dentistId
  return makeMutation('create', 'POST', '/staff-admin/accounts', body, accountId, currentScope(access, access.chain ? input.viewClinicId ?? null : initialGrant.clinicId), { grantId })
}

export function buildStaffAdminGrant(access, target, input) {
  const snapshot = preserveStaffAdminSnapshot(target)
  if (snapshot.accountId === staffAdminId(access.accountId, '操作者账号ID')) throw new Error('不能向自己新增或扩张授权。')
  const scope = currentScope(access, access.chain ? input.viewClinicId ?? null : input.clinicId)
  assertSnapshotScope(snapshot, scope)
  const grantId = staffAdminId(String(input.grantId ?? '').trim(), '授权ID')
  const grant = validateGrantScope(access, input.roleCode, input.scopeKind, input.clinicId)
  if (grant.roleCode === '牙医' && snapshot.dentistId === null) throw new Error('目标员工没有已绑定牙医ID；本页不办理首次牙医绑定。')
  const body = { grantId, expected: snapshot, ...grant, reason: reasonText(input.reason) }
  return makeMutation('grant', 'POST', `/staff-admin/accounts/${snapshot.accountId}/grants`, body, snapshot.accountId, scope, { grantId })
}

export function buildStaffAdminRevoke(access, target, grantIdInput, operationIdInput, reasonInput, viewClinicId = null) {
  const snapshot = preserveStaffAdminSnapshot(target)
  const scope = currentScope(access, access.chain ? viewClinicId : viewClinicId ?? access.clinicIds.find((id) => snapshot.grants.some((grant) => grant.clinicId === id)) ?? null)
  assertSnapshotScope(snapshot, scope)
  const grantId = staffAdminId(String(grantIdInput ?? '').trim(), '授权ID')
  const grant = snapshot.grants.find((item) => item.grantId === grantId)
  if (!grant) throw new Error('只能撤销当前详情快照中可见的有效授权。')
  if (!scope.chain && (grant.scopeKind !== '门店' || grant.clinicId !== scope.clinicId)) throw new Error('门店管理员只能撤销当前授权门店的授权。')
  const operation = operationId(operationIdInput)
  const body = { operationId: operation, expected: snapshot, reason: reasonText(reasonInput) }
  return makeMutation('revoke', 'POST', `/staff-admin/accounts/${snapshot.accountId}/grants/${grantId}/revocation`, body, snapshot.accountId, scope, { operationId: operation, grantId })
}

export function buildStaffAdminState(access, target, active, operationIdInput, reasonInput, viewClinicId = null) {
  const snapshot = preserveStaffAdminSnapshot(target)
  if (!access.chain) throw new Error('门店管理员不能全局启停员工；请由连锁管理员办理。')
  if (typeof active !== 'boolean') throw new Error('启停状态必须明确选择。')
  const scope = currentScope(access, viewClinicId)
  const operation = operationId(operationIdInput)
  const body = { operationId: operation, expected: snapshot, active, reason: reasonText(reasonInput) }
  return makeMutation('state', 'PUT', `/staff-admin/accounts/${snapshot.accountId}/state`, body, snapshot.accountId, scope, { operationId: operation })
}

export function buildStaffAdminCredentialReset(access, target, newPassword, operationIdInput, reasonInput, viewClinicId = null) {
  const snapshot = preserveStaffAdminSnapshot(target)
  if (!access.chain) throw new Error('门店管理员不能全局重置凭据；请由连锁管理员办理。')
  if (typeof newPassword !== 'string' || newPassword.length < 16 || newPassword.length > 256) throw new Error('新密码需为16至256字符。')
  const scope = currentScope(access, viewClinicId)
  const operation = operationId(operationIdInput)
  const body = { operationId: operation, expected: snapshot, newPassword, reason: reasonText(reasonInput) }
  return makeMutation('reset', 'POST', `/staff-admin/accounts/${snapshot.accountId}/credential-reset`, body, snapshot.accountId, scope, { operationId: operation })
}

export function preserveStaffAdminMutation(raw, mutation) {
  exactObject(raw, ['replayed', 'account', 'snapshot'], '员工治理写入响应')
  for (const key of ['replayed', 'account', 'snapshot']) if (!Object.hasOwn(raw, key)) throw new Error(`员工治理写入响应缺少${key}。`)
  if (typeof raw.replayed !== 'boolean') throw new Error('员工治理写入响应缺少回放标志。')
  const account = assertSnapshotScope(preserveStaffAdminSnapshot(raw.account), mutation.scope)
  const snapshot = assertSnapshotScope(preserveStaffAdminSnapshot(raw.snapshot), mutation.scope)
  if (account.accountId !== mutation.targetAccountId || snapshot.accountId !== mutation.targetAccountId || !staffAdminSnapshotsEqual(account, snapshot)) throw new Error('员工治理响应与原目标账号或快照不一致。')
  if (mutation.action !== 'create') {
    const expected = preserveStaffAdminSnapshot(mutation.body.expected)
    if (BigInt(snapshot.mutationCount) <= BigInt(expected.mutationCount)) throw new Error('员工治理响应未证明快照计数推进。')
  }
  if (mutation.action === 'create') {
    const input = mutation.body
    if (account.loginName !== input.loginName || account.displayName !== input.displayName || account.dentistId !== (input.dentistId ?? null)) throw new Error('创建响应与原非敏感账号身份不一致。')
    const initial = account.grants.find((grant) => grant.grantId === input.grantId)
    if (!initial || initial.roleCode !== input.initialGrant.roleCode || initial.scopeKind !== input.initialGrant.scopeKind || initial.clinicId !== input.initialGrant.clinicId) throw new Error('创建响应未证明原初始授权。')
  } else if (mutation.action === 'grant') {
    const grant = account.grants.find((item) => item.grantId === mutation.grantId)
    if (!grant || grant.roleCode !== mutation.body.roleCode || grant.scopeKind !== mutation.body.scopeKind || grant.clinicId !== mutation.body.clinicId) throw new Error('授权响应与原grantId/范围不一致。')
  } else if (mutation.action === 'revoke') {
    if (account.grants.some((grant) => grant.grantId === mutation.grantId)) throw new Error('撤权响应仍包含原有效grantId。')
  } else if (mutation.action === 'state' && account.active !== mutation.body.active) throw new Error('启停响应与原请求状态不一致。')
  return { replayed: raw.replayed, account, snapshot }
}

export function staffAdminPendingKey(access, clinicId = null) {
  const accountId = staffAdminId(access.accountId, '操作者账号ID')
  const scope = currentScope(access, clinicId)
  const keyScope = scope.chain ? (scope.clinicId ? `chain-${scope.clinicId}` : 'chain') : `clinic-${scope.clinicId}`
  return `adc.staff-admin.pending.v1.${accountId}.${keyScope}`
}

function safeSnapshot(snapshot) { return preserveStaffAdminSnapshot(snapshot) }

function pendingScopeSegment(scope) {
  if (scope.chain) return scope.clinicId === null ? 'chain' : `chain-${scope.clinicId}`
  return `clinic-${scope.clinicId}`
}

function assertPendingBucket(key, pending) {
  const match = /^adc\.staff-admin\.pending\.v1\.([1-9]\d*)\.(chain|chain-[1-9]\d*|clinic-[1-9]\d*)$/.exec(String(key))
  if (!match) throw new Error('待核对存储桶身份或范围格式无效。')
  staffAdminId(match[1], '操作者账号ID')
  if (match[2] !== pendingScopeSegment(pending.scope)) throw new Error('待核对记录与账号管理范围存储桶不一致。')
  return pending
}

export function makeStaffAdminPending(mutation, state = 'sending') {
  if (!['sending', 'unknown', 'conflict'].includes(state)) throw new Error('待核对状态无效。')
  const body = mutation.body
  let request
  if (mutation.action === 'create') request = { accountId: body.accountId, grantId: body.grantId, loginName: body.loginName, displayName: body.displayName, dentistId: body.dentistId ?? null, initialGrant: body.initialGrant }
  else if (mutation.action === 'grant') request = { grantId: body.grantId, expected: safeSnapshot(body.expected), roleCode: body.roleCode, scopeKind: body.scopeKind, clinicId: body.clinicId, reason: body.reason }
  else if (mutation.action === 'revoke') request = { operationId: body.operationId, expected: safeSnapshot(body.expected), reason: body.reason }
  else if (mutation.action === 'state') request = { operationId: body.operationId, expected: safeSnapshot(body.expected), active: body.active, reason: body.reason }
  else if (mutation.action === 'reset') request = { operationId: body.operationId, expected: safeSnapshot(body.expected), reason: body.reason }
  else throw new Error('员工治理操作未知。')
  return {
    version: 1, action: mutation.action, targetAccountId: staffAdminId(mutation.targetAccountId, '目标账号ID'),
    scope: { chain: mutation.scope.chain, clinicId: mutation.scope.clinicId }, state,
    operationId: mutation.operationId ?? null, grantId: mutation.grantId ?? null, request,
  }
}

export function preserveStaffAdminPending(raw) {
  exactObject(raw, ['version', 'action', 'targetAccountId', 'scope', 'state', 'operationId', 'grantId', 'request'], '待核对记录')
  if (raw.version !== 1 || !['create', 'grant', 'revoke', 'state', 'reset'].includes(raw.action) || !['sending', 'unknown', 'conflict'].includes(raw.state)) throw new Error('待核对记录版本、操作或状态无效。')
  exactObject(raw.scope, ['chain', 'clinicId'], '待核对scope')
  if (typeof raw.scope.chain !== 'boolean') throw new Error('待核对scope格式无效。')
  const clinicId = optionalId(raw.scope.clinicId, '待核对门店ID')
  if (!raw.scope.chain && clinicId === null) throw new Error('门店待核对记录缺少门店ID。')
  const targetAccountId = staffAdminId(raw.targetAccountId, '目标账号ID')
  const operation = optionalId(raw.operationId, '操作ID')
  const grantId = optionalId(raw.grantId, '授权ID')
  let request
  if (raw.action === 'create') {
    exactObject(raw.request, ['accountId', 'grantId', 'loginName', 'displayName', 'dentistId', 'initialGrant'], '创建待核对请求')
    const accountId = staffAdminId(raw.request.accountId, '员工账号ID')
    const initial = preserveGrantInput(raw.request.initialGrant)
    if (accountId !== targetAccountId || staffAdminId(raw.request.grantId, '初始授权ID') !== grantId || typeof raw.request.loginName !== 'string' || typeof raw.request.displayName !== 'string') throw new Error('创建待核对身份无效。')
    request = { accountId, grantId, loginName: raw.request.loginName, displayName: raw.request.displayName, dentistId: optionalId(raw.request.dentistId, '牙医ID'), initialGrant: initial }
  } else if (raw.action === 'grant') {
    exactObject(raw.request, ['grantId', 'expected', 'roleCode', 'scopeKind', 'clinicId', 'reason'], '授权待核对请求')
    request = { grantId: staffAdminId(raw.request.grantId, '授权ID'), expected: safeSnapshot(raw.request.expected), roleCode: raw.request.roleCode, scopeKind: raw.request.scopeKind, clinicId: optionalId(raw.request.clinicId, '授权门店ID'), reason: String(raw.request.reason) }
    if (request.expected.accountId !== targetAccountId || request.grantId !== grantId) throw new Error('授权待核对目标不一致。')
  } else if (raw.action === 'revoke') {
    exactObject(raw.request, ['operationId', 'expected', 'reason'], '撤权待核对请求')
    request = { operationId: staffAdminId(raw.request.operationId, '操作ID'), expected: safeSnapshot(raw.request.expected), reason: String(raw.request.reason) }
    if (request.expected.accountId !== targetAccountId || request.operationId !== operation || grantId === null) throw new Error('撤权待核对目标不一致。')
  } else if (raw.action === 'state') {
    exactObject(raw.request, ['operationId', 'expected', 'active', 'reason'], '启停待核对请求')
    request = { operationId: staffAdminId(raw.request.operationId, '操作ID'), expected: safeSnapshot(raw.request.expected), active: raw.request.active, reason: String(raw.request.reason) }
    if (typeof request.active !== 'boolean' || request.expected.accountId !== targetAccountId || request.operationId !== operation) throw new Error('启停待核对目标不一致。')
  } else {
    exactObject(raw.request, ['operationId', 'expected', 'reason'], '凭据重置待核对请求')
    request = { operationId: staffAdminId(raw.request.operationId, '操作ID'), expected: safeSnapshot(raw.request.expected), reason: String(raw.request.reason) }
    if (request.expected.accountId !== targetAccountId || request.operationId !== operation) throw new Error('凭据重置待核对目标不一致。')
  }
  return { version: 1, action: raw.action, targetAccountId, scope: { chain: raw.scope.chain, clinicId }, state: raw.state, operationId: operation, grantId, request }
}

function preserveGrantInput(raw) {
  exactObject(raw, ['roleCode', 'scopeKind', 'clinicId'], '员工授权scope')
  if (!STAFF_ADMIN_ROLES.includes(raw.roleCode) || !SCOPE_KINDS.includes(raw.scopeKind)) throw new Error('员工授权scope未知。')
  const clinicId = optionalId(raw.clinicId, '授权门店ID')
  if ((raw.scopeKind === '门店' && clinicId === null) || (raw.scopeKind === '连锁' && clinicId !== null) || (raw.roleCode === '总部管理' && raw.scopeKind !== '连锁')) throw new Error('员工授权scope与门店ID不匹配。')
  return { roleCode: raw.roleCode, scopeKind: raw.scopeKind, clinicId }
}

export function readStaffAdminPending(storage, key) {
  const raw = storage.getItem(key)
  if (raw === null) return []
  const parsed = JSON.parse(raw)
  if (!Array.isArray(parsed)) throw new Error('账号治理待核对记录格式损坏。')
  return parsed.map((entry) => {
    const safe = preserveStaffAdminPending(entry)
    assertPendingBucket(key, safe)
    return safe.state === 'sending' ? { ...safe, state: 'unknown' } : safe
  })
}

export function writeStaffAdminPending(storage, key, entries) {
  if (!Array.isArray(entries)) throw new Error('待核对列表格式无效。')
  const safe = entries.map((entry) => assertPendingBucket(key, preserveStaffAdminPending(entry)))
  storage.setItem(key, JSON.stringify(safe))
  return safe
}

export function replaceStaffAdminPending(entries, pending) {
  const safe = preserveStaffAdminPending(pending)
  return [...entries.filter((entry) => !(entry.action === safe.action && entry.targetAccountId === safe.targetAccountId && entry.operationId === safe.operationId && entry.grantId === safe.grantId)), safe]
}

export function removeStaffAdminPending(entries, pending) {
  return entries.filter((entry) => !(entry.action === pending.action && entry.targetAccountId === pending.targetAccountId && entry.operationId === pending.operationId && entry.grantId === pending.grantId))
}

export function staffAdminRetryMutation(access, pending, secret = '') {
  const safe = preserveStaffAdminPending(pending)
  if (safe.state !== 'unknown') throw new Error('只有结果未知的操作可沿原身份重试。')
  if (!access.allowed || !access.employee) throw new Error('当前身份没有有效员工账号管理授权，不能重试原操作。')
  if (staffAdminId(access.accountId, '操作者账号ID') === safe.targetAccountId && safe.action === 'grant') throw new Error('不能重试对本人新增或扩张授权的操作。')
  const scope = currentScope(access, safe.scope.chain ? safe.scope.clinicId : safe.scope.clinicId)
  if (scope.chain !== safe.scope.chain || scope.clinicId !== safe.scope.clinicId) throw new Error('必须由原账号管理范围继续核对或重试。')
  let body
  let method = 'POST'
  let endpoint
  if (safe.action === 'create') {
    if (typeof secret !== 'string' || secret.length < 16 || secret.length > 256) throw new Error('重试创建需重新输入16至256字符的初始密码。')
    const initialGrant = preserveGrantInput(safe.request.initialGrant)
    validateGrantScope(access, initialGrant.roleCode, initialGrant.scopeKind, initialGrant.clinicId)
    body = { accountId: safe.request.accountId, grantId: safe.request.grantId, loginName: safe.request.loginName, displayName: safe.request.displayName, initialGrant: safe.request.initialGrant, password: secret }
    if (safe.request.dentistId !== null) body.dentistId = safe.request.dentistId
    endpoint = '/staff-admin/accounts'
  } else if (safe.action === 'grant') {
    validateGrantScope(access, safe.request.roleCode, safe.request.scopeKind, safe.request.clinicId)
    body = { ...safe.request }
    endpoint = `/staff-admin/accounts/${safe.targetAccountId}/grants`
  } else if (safe.action === 'revoke') {
    if (safe.grantId === null) throw new Error('原撤权记录缺少grantId。')
    body = { operationId: safe.request.operationId, expected: safe.request.expected, reason: safe.request.reason }
    endpoint = `/staff-admin/accounts/${safe.targetAccountId}/grants/${safe.grantId}/revocation`
  } else if (safe.action === 'state') {
    if (!access.chain) throw new Error('门店管理员不能全局启停员工；请由连锁管理员核对。')
    method = 'PUT'; body = { ...safe.request }; endpoint = `/staff-admin/accounts/${safe.targetAccountId}/state`
  } else {
    if (!access.chain) throw new Error('门店管理员不能重置凭据；请由连锁管理员核对。')
    if (typeof secret !== 'string' || secret.length < 16 || secret.length > 256) throw new Error('重试凭据重置需重新输入16至256字符的新密码。')
    body = { operationId: safe.request.operationId, expected: safe.request.expected, newPassword: secret, reason: safe.request.reason }
    endpoint = `/staff-admin/accounts/${safe.targetAccountId}/credential-reset`
  }
  return makeMutation(safe.action, method, endpoint, body, safe.targetAccountId, scope, { operationId: safe.operationId, grantId: safe.grantId })
}

export function staffAdminFailureDisposition(error) {
  const status = Number(error?.status ?? 0)
  if (status === 401) return 'unauthorized'
  if (status === 409) return 'conflict'
  if (status >= 400 && status < 500) return 'rejected'
  return 'unknown'
}

export function staffAdminContextMatches(captured, current) {
  return !!captured && !!current && !captured.disposed && !current.disposed && captured.accountId === current.accountId && captured.token === current.token
    && captured.scopeKey === current.scopeKey && captured.generation === current.generation && captured.targetAccountId === current.targetAccountId
}

export function staffAdminScopeKey(access, clinicFilterId = null) {
  const scope = currentScope(access, access.chain ? clinicFilterId : clinicFilterId)
  return JSON.stringify([access.accountId, scope.chain ? 'chain' : scope.clinicId, scope.clinicId])
}
