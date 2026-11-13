export function isEnabled(value) {
  return value === true || value === 1 || value === '1'
}

export function hasActualVisitRecord(appointment, invoice) {
  if (!appointment) return false
  return isEnabled(appointment.attended)
    || appointment.arrived_at != null
    || appointment.completed_at != null
    || (appointment.treatment_status != null && appointment.treatment_status !== '未开始')
    || invoice?.invoice_id != null
}

export function isPendingAppointment(appointment) {
  return !!appointment && ['待到诊', '待核实'].includes(String(appointment.appointment_status))
}

export function canCancelOrReschedule(appointment, invoice) {
  return isPendingAppointment(appointment) && !hasActualVisitRecord(appointment, invoice)
}

export function canVerifyNoShow(appointment, invoice, nowEpochMs) {
  if (!canCancelOrReschedule(appointment, invoice) || !appointment.scheduled_end) return false
  return nowEpochMs > new Date(String(appointment.scheduled_end)).getTime()
}

export function hasFrontDeskGrant(identity, clinicId) {
  return (identity?.grants ?? []).some((grant) => grant.role_code === '前台'
    && (grant.scope_kind === '连锁' || String(grant.clinic_id) === String(clinicId)))
}

export function isExplicitFrontDeskTarget(sourceClinicId, targetClinicId, allowedClinicIds) {
  if (targetClinicId == null || String(targetClinicId).trim() === '') return false
  // The source is never inferred from the current selector. A same-store target is valid only after an explicit selection.
  return allowedClinicIds.some((clinicId) => String(clinicId) === String(targetClinicId))
}

export function fulfillmentRequestKey({ accountId, sourceClinicId, appointmentId, action }) {
  return ['adc.fulfillment.pending', accountId, sourceClinicId, appointmentId, action]
    .map((part) => encodeURIComponent(String(part)))
    .join(':')
}

export function scopedRequestKey({ namespace = 'adc.fulfillment.pending', accountId, sourceClinicId, appointmentId, action }) {
  return [namespace, accountId, sourceClinicId, appointmentId, action]
    .map((part) => encodeURIComponent(String(part)))
    .join(':')
}

export function savePendingWrite(storage, scope, request) {
  const key = scopedRequestKey(scope)
  const record = {
    ...scope,
    key,
    createdAt: request.createdAt ?? new Date().toISOString(),
    operationId: String(request.operationId ?? request.payload?.operationId ?? request.payload?.appointmentNo ?? request.payload?.planId ?? request.payload?.contactId ?? (request.payload?.expected?.mutationCount != null ? `mutation-${request.payload.expected.mutationCount}` : `row-version-${request.payload?.expectedRowVersion ?? 'fixed-payload'}`)),
    state: 'queued',
    endpoint: request.endpoint,
    method: request.method ?? 'POST',
    payload: JSON.parse(JSON.stringify(request.payload)),
  }
  storage.setItem(key, JSON.stringify(record))
  return record
}

export function loadPendingWrite(storage, scope) {
  const key = scopedRequestKey(scope)
  const raw = storage.getItem(key)
  if (!raw) return null
  try {
    const record = JSON.parse(raw)
    if (record.key !== key || record.accountId !== scope.accountId
      || String(record.sourceClinicId) !== String(scope.sourceClinicId)
      || String(record.appointmentId) !== String(scope.appointmentId)
      || record.action !== scope.action
      || (record.namespace ?? 'adc.fulfillment.pending') !== (scope.namespace ?? 'adc.fulfillment.pending')) return null
    return record
  } catch {
    storage.removeItem(key)
    return null
  }
}

export function removePendingWrite(storage, scope) {
  storage.removeItem(scopedRequestKey(scope))
}

export function markPendingWriteState(storage, record, state) {
  const current = loadPendingWrite(storage, record)
  if (!current) throw new Error('待核对请求已不存在或无法读取。')
  const next = { ...current, state }
  storage.setItem(next.key, JSON.stringify(next))
  return next
}

export function loadFulfillmentPendingWrites(storage, scope) {
  return loadScopedPendingWrites(storage, scope, ['reschedule', 'cancel', 'no-show', 'review'])
}

export function loadFollowupPendingWrites(storage, scope) {
  return loadScopedPendingWrites(storage, { ...scope, namespace: 'adc.followup.pending' }, ['followup-create', 'followup-update', 'followup-cancel', 'followup-contact', 'followup-appointment'])
}

export function loadClinicalCompletionPendingWrite(storage, scope) {
  return loadScopedPendingWrites(storage, { ...scope, namespace: 'adc.clinical.completion.pending' }, ['clinical-completion'])[0] ?? null
}

export function loadReminderPendingWrites(storage, scope) {
  return loadScopedPendingWrites(storage, { ...scope, namespace: 'adc.reminder.pending' }, ['reminder-rule', 'reminder-rebuild', 'reminder-migration-preview', 'reminder-migration-commit'])
}

export function loadExpensePendingWrites(storage, scope) {
  return loadScopedPendingWrites(storage, { ...scope, namespace: 'adc.expense.pending' }, ['expense-create', 'expense-confirm'])
}

export function loadScopedPendingWrites(storage, scope, actions) {
  return actions
    .map((action) => loadPendingWrite(storage, { ...scope, action }))
    .filter(Boolean)
}

export function isUnknownWriteOutcome(error) {
  const status = Number(error?.status)
  return !Number.isInteger(status) || status >= 500 || error?.code === 'IDEMPOTENCY_CONFLICT'
}

export function isRequestScopeCurrent(expected, current) {
  return expected.generation === current.generation
    && expected.accountId === current.accountId
    && String(expected.sourceClinicId) === String(current.sourceClinicId)
    && expected.sessionToken === current.sessionToken
}

export function makeReviewPayload({ valid, exempt, reason }) {
  return { valid: Boolean(valid), exempt: Boolean(exempt), reason: String(reason).trim() }
}
