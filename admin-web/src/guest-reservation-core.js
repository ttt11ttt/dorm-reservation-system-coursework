const RESERVATION_FIELDS = [
  'guest_reservation_id', 'reservation_no', 'clinic_id', 'dentist_id', 'schedule_id',
  'scheduled_start', 'scheduled_end', 'contact_name', 'contact_phone', 'requested_treatments',
  'special_needs', 'reservation_status', 'row_version', 'converted_appointment_id', 'created_at',
]

function exactObject(value, label) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error(`${label}格式无效。`)
}

function id(value, label, { nullable = false } = {}) {
  if (nullable && value == null) return null
  if (typeof value !== 'string' && !(typeof value === 'number' && Number.isSafeInteger(value))) throw new Error(`${label}格式无效。`)
  const text = String(value)
  if (!/^[1-9]\d*$/.test(text)) throw new Error(`${label}格式无效。`)
  return text
}

function timestamp(value, label) {
  if (typeof value !== 'string' || !Number.isFinite(Date.parse(value))) throw new Error(`${label}格式无效。`)
  return value
}

function reservation(raw, expectedClinicId) {
  exactObject(raw, '访客预约')
  if (RESERVATION_FIELDS.some((field) => !Object.hasOwn(raw, field))) throw new Error('访客预约字段不完整。')
  const reservationId = id(raw.guest_reservation_id, '访客预约ID')
  const clinicId = id(raw.clinic_id, '门店ID')
  if (clinicId !== String(expectedClinicId)) throw new Error('访客预约门店与当前范围不一致。')
  const rowVersion = typeof raw.row_version === 'number' ? raw.row_version : Number(raw.row_version)
  if (!Number.isInteger(rowVersion) || rowVersion < 0) throw new Error('访客预约版本无效。')
  if (typeof raw.reservation_no !== 'string' || !/^G-[A-Za-z0-9_-]{22,30}$/.test(raw.reservation_no)) throw new Error('访客预约号无效。')
  if (typeof raw.contact_name !== 'string' || typeof raw.contact_phone !== 'string' || typeof raw.requested_treatments !== 'string') throw new Error('访客联系人或预约说明格式无效。')
  if (!(raw.special_needs === null || typeof raw.special_needs === 'string')) throw new Error('访客特殊需求格式无效。')
  if (!['待到诊', '患者取消', '诊所撤销', '已改约', '未到诊', '已转换', '待核实'].includes(raw.reservation_status)) throw new Error('访客预约状态无效。')
  const startAt = timestamp(raw.scheduled_start, '预约开始时间')
  const endAt = timestamp(raw.scheduled_end, '预约结束时间')
  if (Date.parse(endAt) <= Date.parse(startAt)) throw new Error('访客预约时间区间无效。')
  return {
    reservationId, reservationNo: raw.reservation_no, clinicId,
    dentistId: id(raw.dentist_id, '牙医ID'), scheduleId: id(raw.schedule_id, '出诊ID'),
    startAt, endAt, contactName: raw.contact_name, contactPhone: raw.contact_phone,
    requestedTreatments: raw.requested_treatments, specialNeeds: raw.special_needs,
    status: raw.reservation_status, rowVersion,
    convertedAppointmentId: id(raw.converted_appointment_id, '关联预约ID', { nullable: true }),
    createdAt: timestamp(raw.created_at, '创建时间'),
  }
}

export function preserveStaffGuestReservations(raw, clinicId) {
  if (!Array.isArray(raw) || !clinicId) throw new Error('访客预约列表格式无效。')
  return raw.map((item) => reservation(item, clinicId))
}

export function makeGuestReservationConversionPayload(row, patientId, evidenceRef, confirmedInPerson) {
  if (!row || row.status !== '待到诊') throw new Error('只有待到诊的访客预约可以发起到店核验转换。')
  const patient = id(patientId, '既有患者ID')
  if (typeof evidenceRef !== 'string' || !/^[A-Za-z0-9_.:/-]{1,80}$/.test(evidenceRef)) throw new Error('请输入非秘密的核验依据引用（1至80位字母、数字或 . _ : / -）。')
  if (confirmedInPerson !== true) throw new Error('必须先在现场完成身份核验。')
  return { expectedRowVersion: row.rowVersion, patientId: patient, evidenceRef, confirmedInPerson: true }
}

export function preserveGuestReservationConversion(raw, expected) {
  exactObject(raw, '访客转换回执')
  if (typeof raw.replayed !== 'boolean' || !raw.reservation) throw new Error('访客转换回执格式无效。')
  const converted = reservation(raw.reservation, expected.clinicId)
  if (converted.reservationId !== expected.reservationId || converted.reservationNo !== expected.reservationNo || converted.status !== '已转换' || !converted.convertedAppointmentId) throw new Error('转换回执与原访客预约号不一致。')
  const appointmentId = raw.appointmentId == null ? converted.convertedAppointmentId : id(raw.appointmentId, '关联预约ID')
  if (appointmentId !== converted.convertedAppointmentId) throw new Error('转换回执关联预约ID不一致。')
  return { replayed: raw.replayed, appointmentId, reservation: converted }
}
