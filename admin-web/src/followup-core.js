export const FOLLOWUP_SNAPSHOT_FIELDS = [
  'patientId', 'sourceAppointmentId', 'dentistId', 'clinicId', 'suggestedDate', 'recommendation',
  'status', 'completedAppointmentId', 'completedAt', 'cancelReason', 'createdAt', 'updatedAt', 'mutationCount',
]

export function preserveFollowupSnapshot(snapshot) {
  if (!snapshot || typeof snapshot !== 'object') throw new Error('缺少服务端复诊快照。')
  const expected = {}
  for (const field of FOLLOWUP_SNAPSHOT_FIELDS) {
    if (!Object.hasOwn(snapshot, field)) throw new Error(`服务端复诊快照缺少字段 ${field}，不能提交维护。`)
    const value = snapshot[field]
    if (value !== null && typeof value !== 'string') throw new Error(`快照字段 ${field} 必须按服务端原字符串保留。`)
    expected[field] = value
  }
  return expected
}

export function generatePositive63BitId(randomWords = crypto.getRandomValues(new Uint32Array(2))) {
  const id = ((BigInt(randomWords[0]) << 32n) | BigInt(randomWords[1])) & 0x7fffffffffffffffn
  return String(id === 0n ? 1n : id)
}

export function shanghaiInputValue(date = new Date()) {
  return new Date(date.getTime() + 8 * 60 * 60 * 1000).toISOString().slice(0, 16)
}

export function makeFollowupContactPayload({ contactId, contactedAtLocal, result, note, nextContactAtLocal = '', nowEpochMs = Date.now() }) {
  if (!/^\d+$/.test(String(contactId)) || BigInt(contactId) <= 0n || BigInt(contactId) > 0x7fffffffffffffffn) throw new Error('联系ID必须是正63位以内字符串。')
  if (!contactedAtLocal || !result || !String(note).trim()) throw new Error('请填写实际联系时间、联系结果及说明。')
  const occurredAtMs = Date.parse(`${contactedAtLocal}:00+08:00`)
  if (!Number.isFinite(occurredAtMs) || occurredAtMs > nowEpochMs) throw new Error('实际电话联系时间不能晚于当前时间。')
  let nextContactAt = null
  if (nextContactAtLocal) {
    const nextAtMs = Date.parse(`${nextContactAtLocal}:00+08:00`)
    if (!Number.isFinite(nextAtMs) || nextAtMs <= occurredAtMs) throw new Error('下次联系时间应晚于本次实际联系时间。')
    nextContactAt = `${nextContactAtLocal}:00+08:00`
  }
  return { contactId: String(contactId), contactedAt: `${contactedAtLocal}:00+08:00`, result: String(result), note: String(note).trim(), nextContactAt }
}

export function cursorQuery(params, afterId) {
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null && value !== '') search.set(key, String(value))
  }
  if (afterId !== undefined && afterId !== null && String(afterId) !== '') search.set('afterId', String(afterId))
  return search.toString()
}

export function mergeCursorPage(existing, response, append) {
  if (!response || !Array.isArray(response.items)) throw new Error('复诊名单分页响应格式无效。')
  const hasMore = response.hasMore === true
  const nextAfterId = response.nextAfterId == null ? null : String(response.nextAfterId)
  if (hasMore && (!nextAfterId || !/^\d+$/.test(nextAfterId))) throw new Error('服务端表示还有后页，但未返回有效字符串游标；停止翻页以免静默漏项。')
  if (!hasMore && nextAfterId !== null) throw new Error('服务端终页游标与hasMore状态不一致；请重新读取名单。')
  const asOfDate = String(response.asOfDate ?? '')
  if (!/^\d{4}-\d{2}-\d{2}$/.test(asOfDate)) throw new Error('服务端未返回有效上海业务日期。')
  return { items: append ? [...existing, ...response.items] : [...response.items], hasMore, nextAfterId, asOfDate }
}

export function mergeIdCursorPage(existing, response, append) {
  if (!response || !Array.isArray(response.items)) throw new Error('联系历史分页响应格式无效。')
  const hasMore = response.hasMore === true
  const nextAfterId = response.nextAfterId == null ? null : String(response.nextAfterId)
  if (hasMore && (!nextAfterId || !/^\d+$/.test(nextAfterId))) throw new Error('服务端表示还有联系历史，但游标无效；请重试本页。')
  if (!hasMore && nextAfterId !== null) throw new Error('联系历史终页游标与hasMore状态不一致。')
  return { items: append ? [...existing, ...response.items] : [...response.items], hasMore, nextAfterId }
}

export function cursorPageChangedBusinessDate(previousDate, nextDate) {
  return !!previousDate && !!nextDate && previousDate !== nextDate
}

export function followupScope(accountId, clinicId, recordId, action) {
  return { accountId: String(accountId), sourceClinicId: String(clinicId), appointmentId: String(recordId), action, namespace: 'adc.followup.pending' }
}

// Exact request contract for booking directly from an existing follow-up plan.
// Patient and plan association are server-bound by the route and are deliberately absent.
export function makeFollowupAppointmentPayload({ appointmentNo, clinicId, dentistId, scheduleId, scheduledStart, scheduledEnd, requestedTreatments, specialNeeds = '', bookingChannel }) {
  if (!appointmentNo || !clinicId || !dentistId || !scheduleId || !scheduledStart || !scheduledEnd || !String(requestedTreatments).trim() || !bookingChannel) throw new Error('请完整填写并核对目标门店、牙医、时段、办理渠道与预约需求。')
  const payload = { appointmentNo: String(appointmentNo), clinicId: String(clinicId), dentistId: String(dentistId), scheduleId: String(scheduleId), scheduledStart: String(scheduledStart), scheduledEnd: String(scheduledEnd), requestedTreatments: String(requestedTreatments).trim() }
  if (String(specialNeeds).trim()) payload.specialNeeds = String(specialNeeds).trim()
  payload.bookingChannel = bookingChannel
  return payload
}

export function canArrangeFollowupAppointment(identity, planStatus, sourceClinicId, targetClinicId) {
  return planStatus === '待复诊' && hasRoleAtClinic(identity, '前台', sourceClinicId)
    && hasRoleAtClinic(identity, '前台', targetClinicId)
}

export function assertFollowupAppointmentAssociation(expectedPlanId, response) {
  if (String(response?.followup_plan_id ?? '') !== String(expectedPlanId)) {
    const error = new Error('预约请求返回成功，但服务端未确认其关联到本复诊计划。原请求已保留，需人工核对。')
    error.code = 'FOLLOWUP_ASSOCIATION_UNCONFIRMED'
    throw error
  }
  return response
}

export function parseFollowupCompletionFeedback(followup) {
  if (!followup || typeof followup !== 'object' || Array.isArray(followup)) return null
  const outcomes = {
    COMPLETED_NOW: '服务端确认：复诊计划已由本次就诊完成。',
    ALREADY_COMPLETED_BY_THIS: '服务端确认：该复诊计划已由本次就诊完成。',
    CANCELLED_PRESERVED: '本次诊疗已完成；关联复诊计划原已取消，取消状态保留。',
    COMPLETED_BY_OTHER: '本次诊疗已完成；复诊计划由其他就诊完成，原完成身份保持不变，请核对。',
    LEGACY_RECONCILIATION_REQUIRED: '本次诊疗已完成；复诊计划需要遗留数据核对，当前不能确认计划已完成。',
  }
  const outcome = String(followup.outcome ?? '')
  return {
    outcome,
    outcomeText: outcomes[outcome] ?? '收到复诊完成反馈；计划状态需按服务端结果核对。',
    planId: String(followup.planId ?? ''),
    status: String(followup.status ?? ''),
    completedAppointmentId: followup.completedAppointmentId == null ? '' : String(followup.completedAppointmentId),
    completedAt: followup.completedAt == null ? '' : String(followup.completedAt),
    planReconciliationRequired: followup.planReconciliationRequired === true,
    reminderReconciliationRequired: followup.reminderReconciliationRequired === true,
    unresolvedReminderCount: followup.unresolvedReminderCount == null ? '0' : String(followup.unresolvedReminderCount),
    reminderScheduling: String(followup.reminderScheduling ?? ''),
  }
}

export function isCompletionResponseCurrent(expected, current) {
  return !current.disposed
    && expected.contextRevision === current.contextRevision
    && expected.appointmentId === current.appointmentId
    && expected.accountId === current.accountId
    && String(expected.clinicId) === String(current.clinicId)
    && expected.sessionToken === current.sessionToken
    && current.workMode === 'care'
}

export function clinicalCompletionScope(accountId, clinicId, appointmentId) {
  return { namespace: 'adc.clinical.completion.pending', accountId: String(accountId), sourceClinicId: String(clinicId), appointmentId: String(appointmentId), action: 'clinical-completion' }
}

export function hasRoleAtClinic(identity, roleCode, clinicId) {
  return (identity?.grants ?? []).some((grant) => grant.role_code === roleCode
    && (grant.scope_kind === '连锁' || String(grant.clinic_id) === String(clinicId)))
}

export function formatFollowupError(error) {
  const code = String(error?.code ?? '')
  const messages = {
    FOLLOWUP_CONFLICT: '服务端完整快照已变化。已保留您输入的内容；请核对最新计划、联系记录与状态后再提交。',
    FOLLOWUP_COMPLETION_PENDING: '已有真实关联就诊需要后续完成核对；本段不能替计划结案，计划与提醒均未被本次操作更改。',
    REMINDER_RECONCILIATION_REQUIRED: '存在待核实的提醒发送状态，服务端未更改计划。请先由负责人核实提醒状态。',
    NOT_IMPLEMENTED: '提醒重建与发送尚未实现；本次不代表提醒已排程或已发送。',
    IDEMPOTENCY_CONFLICT: '固定操作号对应的内容与既有请求不同。原请求已保留，请先核对服务端记录。',
  }
  return messages[code] ?? (error instanceof Error ? error.message : '复诊操作未完成。')
}
