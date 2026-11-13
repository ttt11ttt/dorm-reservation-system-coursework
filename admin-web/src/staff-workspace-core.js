import { canOpenExpenseWorkspace, isValidGrantScope } from './expense-core.js'
import { isChainScopeGrant, isClinicScopeGrant } from './expense-core.js'
import { quarterReadAccess } from './quarter-core.js'

export function staffWorkspaceAccess(identity) {
  const employee = identity?.kind === '员工'
  const grants = employee && Array.isArray(identity.grants) ? identity.grants : []
  const hasRole = (role) => grants.some((grant) => grant.role_code === role && isValidGrantScope(grant))
  const operations = grants.filter((grant) => grant.role_code === '运营人员／店长')
  const organizationChain = operations.some(isChainScopeGrant)
  const organizationClinicIds = [...new Set(operations.filter(isClinicScopeGrant).map((grant) => String(grant.clinic_id)))].sort((a, b) => BigInt(a) < BigInt(b) ? -1 : BigInt(a) > BigInt(b) ? 1 : 0)
  const quarter = quarterReadAccess(identity)
  return {
    employee,
    accountManager: hasRole('账号管理'),
    organization: organizationChain || organizationClinicIds.length > 0,
    organizationChain,
    organizationClinicIds,
    front: hasRole('前台'),
    dentist: hasRole('牙医'),
    reminders: hasRole('前台') || hasRole('运营人员／店长'),
    expenses: employee && canOpenExpenseWorkspace(identity),
    quarter: quarter.finance || quarter.headquarters,
  }
}

export function chooseStaffLanding(identity, clinicCount) {
  const access = staffWorkspaceAccess(identity)
  if (!access.employee) throw new Error('当前身份不是员工账号，不能进入员工工作台。')
  if (!access.front && !access.dentist && !access.reminders && !access.expenses && !access.quarter && !access.accountManager && !access.organization) throw new Error('当前员工账号没有本工作台支持的岗位授权。')
  if (clinicCount === 0) {
    if (access.accountManager) return 'account-admin'
    if (access.quarter) return 'quarter'
    if (access.organization) return 'organization'
    if (access.dentist) return 'dentist'
    if (access.front) return 'front'
    if (access.reminders) return 'reminders'
    if (access.expenses) return 'expenses'
    throw new Error('当前员工账号没有可用工作台。')
  }
  if (access.organization && !access.front && !access.dentist && !access.expenses && !access.quarter && !access.accountManager) return 'organization'
  if (access.dentist) return 'dentist'
  if (access.front) return 'front'
  if (access.reminders) return 'reminders'
  if (access.expenses) return 'expenses'
  if (access.quarter) return 'quarter'
  if (access.accountManager) return 'account-admin'
  throw new Error('当前员工账号没有可用工作台。')
}
