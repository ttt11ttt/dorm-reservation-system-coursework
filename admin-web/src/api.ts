export type Json = Record<string, unknown>
export type Identity = {
  accountId: string
  kind: string
  readOnly?: boolean
  patientId: string | null
  dentistId: string | null
  grants: Array<{ role_code: string; scope_kind: string; clinic_id: string | number | null }>
}
export type Clinic = { clinic_id: string; clinic_code: string; name: string }
export type ApiProblem = Error & { status: number; code: string; requestId?: string }
import { requestOwnsCurrentSession } from './staff-admin-ui-coordination.js'

const TOKEN_KEY = 'adc.staff.session'
export const sessionToken = () => sessionStorage.getItem(TOKEN_KEY)
let readOnlySession = false
export const setReadOnlySession = (value: boolean) => { readOnlySession = value }
export const setSessionToken = (token: string | null) => { if(token)sessionStorage.setItem(TOKEN_KEY, token);else{sessionStorage.removeItem(TOKEN_KEY);readOnlySession=false} }

const friendly: Record<string, string> = {
  INVALID_CREDENTIALS: '账号或密码不正确。', UNAUTHENTICATED: '登录已失效，请重新登录。', FORBIDDEN: '当前账号没有办理此操作的权限。',
  ROW_VERSION_CONFLICT: '记录已被其他操作更新。页面内容已保留，请重新读取最新记录后再提交。',
  ALLERGY_VERSION_CONFLICT: '过敏申报版本已变化。请重新读取就诊上下文并核对最新申报。',
  ALLERGY_UNRESOLVED: '请先明确本次过敏申报，再签署核对快照。', ALLERGY_RECHECK_REQUIRED: '过敏申报已变化，请重新签署本次快照。',
  INVALID_STATE: '当前就诊状态不允许此操作。', OUTSIDE_COVERAGE: '所选时间不在真实门店开放及牙医出诊范围内。', TIME_CONFLICT: '患者或牙医在该时间已有有效安排。',
  FOLLOWUP_CONFLICT: '复诊计划版本已变化。请核对最新完整快照后再提交。', FOLLOWUP_SOURCE_INVALID: '只能从本人负责且真实完成的来源就诊登记复诊建议。',
  FOLLOWUP_COMPLETION_PENDING: '已有真实关联就诊需要后续完成核对；当前操作未将计划结案。', REMINDER_RECONCILIATION_REQUIRED: '提醒发送状态待核实；当前计划修改或取消未生效。',
  APPOINTMENT_NOT_ENDED: '预约结束时间尚未过去，不能核实未到诊。请在结束后由前台人工核实。', MANUAL_REVIEW_REQUIRED: '这次预约已有到诊、治疗或结算记录，不能按普通取消、改约或误录作废处理。请保留当前记录并联系负责人复核。',
  BOOKING_RESTRICTED: '患者自助预约受限；前台电话或柜台人工办理仍可继续，请按服务端返回的到期时间向患者说明。', RETRY_TRANSACTION: '记录正在被其他操作更新。请重新读取后核对，不要重复编辑旧版本。',
  IDEMPOTENCY_CONFLICT: '操作编号已用于不同内容。请核对预约记录，不要更换编号重复提交。', DATA_CONFLICT: '编号或资料与已有记录冲突，请检查输入。',
  TREATMENT_IMMUTABLE: '已确认治疗不能直接改写。请填写作废原因并保留原记录，再录入替代项目。', TREATMENT_SET_CONFLICT: '治疗集合与服务端记录不一致。请重新读取上下文。',
  DRAFTS_REMAIN: '仍有未确认或未作废的治疗草稿。', COMPLETION_CONFLICT: '该就诊已完成，但提交内容与已保存记录不同。',
  INVOICE_REVIEW_REQUIRED: '同次已有草稿或作废结算，需要专门处理，不能新开第二张。', INVALID_INPUT: '输入内容不符合服务端字段或精度要求。',
}

export async function api<T>(path: string, options: { method?: string; body?: unknown; auth?: boolean } = {}): Promise<T> {
  const method=(options.method??'GET').toUpperCase()
  if(readOnlySession&&options.auth!==false&&!['GET','HEAD','OPTIONS'].includes(method)&&!(path==='/sessions/current'&&method==='DELETE')){
    const error=new Error('当前为只读访客账号，只能查看，不能新增、修改或删除数据。') as ApiProblem
    error.status=403;error.code='READ_ONLY';throw error
  }
  const token = sessionToken()
  const headers: Record<string, string> = { Accept: 'application/json' }
  if (options.body !== undefined) headers['Content-Type'] = 'application/json'
  if (options.auth !== false && token) headers.Authorization = `Bearer ${token}`
  const response = await fetch(`/api/v1${path}`, { method: options.method ?? 'GET', headers, body: options.body === undefined ? undefined : JSON.stringify(options.body), signal: AbortSignal.timeout(15_000) })
  const text = await response.text()
  let data: Record<string, unknown> = {}
  if (text) { try { data = JSON.parse(text) as Record<string, unknown> } catch { data = { message: text } } }
  if (!response.ok) {
    const code = String(data.code ?? `HTTP_${response.status}`)
    if (response.status === 401 && requestOwnsCurrentSession(token, sessionToken(), options.auth !== false)) {
      setSessionToken(null)
      if (typeof window !== 'undefined') window.dispatchEvent(new Event('adc:session-expired'))
    }
    const error = new Error(friendly[code] ?? String(data.message ?? '请求失败，请稍后重试。')) as ApiProblem
    error.status = response.status
    error.code = code
    if (typeof data.requestId === 'string') error.requestId = data.requestId
    throw error
  }
  return data as T
}

export function shanghaiOffset(local: string): string {
  if (!local) throw new Error('请填写时间。')
  return `${local}:00+08:00`
}

export function formatShanghai(value: unknown): string {
  if (!value) return '—'
  const date = new Date(String(value))
  if (Number.isNaN(date.getTime())) return String(value)
  return new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(date)
}

export function operationId(prefix: string): string {
  const bytes = crypto.getRandomValues(new Uint32Array(2))
  const id = ((BigInt(bytes[0]) << 32n) | BigInt(bytes[1])) & 0x7fffffffffffffffn
  return `${prefix}-${id === 0n ? 1n : id}`
}
