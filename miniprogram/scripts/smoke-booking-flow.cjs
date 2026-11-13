const fs = require('node:fs')
const path = require('node:path')
const { apiBaseUrl } = require('../config/environment')
const { generateCandidates, overlaps } = require('../utils/availability')
const operations = require('../utils/write-operations')

const credentialsPath = path.resolve(__dirname, '..', '..', 'admin-web', '.runtime', 'credentials.json')
const storage = new Map()

global.getApp = () => ({ globalData: {} })
global.wx = {
  getStorageSync(key) { return storage.get(key) || '' },
  setStorageSync(key, value) { storage.set(key, value) },
  removeStorageSync(key) { storage.delete(key) },
  request(options) {
    const requestOptions = { method: options.method || 'GET', headers: options.header || {} }
    if (options.data !== undefined) requestOptions.body = JSON.stringify(options.data)
    fetch(options.url, requestOptions).then(async (response) => {
      const data = await response.json().catch(() => ({}))
      options.success({ statusCode: response.status, data })
    }).catch(() => options.fail())
  }
}

const api = require('../utils/api')
let activeAppointment = null

function check(condition, stage) {
  if (!condition) throw Object.assign(new Error('smoke assertion failed'), { stage })
}

async function requestJson(path, method = 'GET', data) {
  return api.request(path, { method, data })
}

async function runWrite(operation) {
  const existing = operations.readPending()
  if (existing) {
    check(existing.operationNo === operation.operationNo && existing.path === operation.path, 'matching-pending-operation')
    return operations.retryPending()
  }
  try {
    return await operations.start(operation)
  } catch (error) {
    if (!error.writeOutcomeUncertain) throw error
    // Retry the exact saved envelope once. The operation number and body are unchanged.
    return operations.retryPending()
  }
}

async function cancelCurrentAppointment() {
  if (!activeAppointment) return
  const payload = {
    expectedRowVersion: activeAppointment.row_version,
    reason: '开发联调测试完成（非诊疗记录）'
  }
  const path = `/patient/appointments/${encodeURIComponent(activeAppointment.appointment_id)}/cancel`
  const operation = operations.makeOperation('预约取消', 'POST', path, payload, false, activeAppointment.patient_id)
  const result = await runWrite(operation)
  check(result.appointment && result.appointment.appointment_status === '患者取消', 'cleanup-cancel')
  activeAppointment = null
  return { path, payload, result }
}

async function main() {
  check(apiBaseUrl === 'http://127.0.0.1:18083/api/v1', 'local-api-address')
  const credentials = JSON.parse(fs.readFileSync(credentialsPath, 'utf8'))
  check(typeof credentials.demo?.password === 'string' && credentials.demo.password.length > 0, 'local-demo-credential')
  const profile = await api.patientLogin('demo-patient', credentials.demo.password)
  const patientId = String(profile.patient.patient_id)
  process.stdout.write('PASS demo-patient login and own profile\n')

  const bookingStatus = await requestJson('/patient/booking-status')
  check(typeof bookingStatus.restricted === 'boolean', 'booking-status')
  process.stdout.write('PASS booking status\n')
  if (bookingStatus.restricted) {
    process.stdout.write('SKIP booking writes: account is currently restricted; query and cancellation remain available.\n')
    await api.logout()
    return
  }

  const [clinics, treatmentTypes] = await Promise.all([
    requestJson('/patient/clinics'),
    requestJson('/patient/treatment-types')
  ])
  check(Array.isArray(clinics) && clinics.length > 0, 'clinics')
  check(Array.isArray(treatmentTypes), 'treatment-types')
  check(treatmentTypes.every((item) => typeof item.treatment_type_id === 'string' && typeof item.standard_unit_price === 'string'), 'reference-price-strings')
  process.stdout.write(`PASS clinics and real reference directory (${treatmentTypes.length} items)\n`)

  let choice = null
  for (const clinic of clinics) {
    const clinicId = String(clinic.clinic_id)
    const [dentists, availability] = await Promise.all([
      requestJson(`/patient/dentists?clinicId=${encodeURIComponent(clinicId)}`),
      requestJson(`/patient/availability?clinicId=${encodeURIComponent(clinicId)}`)
    ])
    check(Array.isArray(dentists) && availability && Array.isArray(availability.windows), 'availability-contract')
    for (const dentist of dentists) {
      const dentistId = String(dentist.dentist_id)
      const candidates = generateCandidates({ availability, durationMinutes: 30 })
        .filter((slot) => slot.dentistId === dentistId)
      if (candidates.length >= 2) {
        choice = { clinic, dentist, availability, candidates }
        break
      }
    }
    if (choice) break
  }
  if (!choice) {
    process.stdout.write('SKIP booking writes: no clinic/dentist has two safe 30-minute candidates.\n')
    await api.logout()
    return
  }
  process.stdout.write('PASS real availability produced distinct candidates after occupancy/time subtraction\n')

  const first = choice.candidates[0]
  const create = operations.makeOperation('预约新增', 'POST', '/patient/appointments', {
    clinicId: first.clinicId,
    dentistId: first.dentistId,
    scheduleId: first.scheduleId,
    scheduledStart: first.scheduledStart,
    scheduledEnd: first.scheduledEnd,
    requestedTreatments: '开发联调预约测试（非诊疗记录）'
  }, true, patientId)
  const createResult = await runWrite(create)
  check(createResult.appointment && typeof createResult.appointment.appointment_id === 'string', 'create-appointment')
  activeAppointment = createResult.appointment
  check(createResult.appointment.appointment_status === '待到诊', 'create-pending-status')
  process.stdout.write('PASS real patient appointment create\n')

  const createReplay = await requestJson(create.path, 'POST', create.payload)
  check(createReplay.replayed === true && createReplay.appointment.appointment_id === activeAppointment.appointment_id, 'create-idempotent-replay')
  process.stdout.write('PASS exact create replay returns the same appointment\n')

  const refreshed = await requestJson(`/patient/availability?clinicId=${encodeURIComponent(first.clinicId)}`)
  const nextCandidates = generateCandidates({ availability: refreshed, durationMinutes: 30 })
    .filter((slot) => slot.dentistId === first.dentistId)
    .filter((slot) => !overlaps(Date.parse(slot.scheduledStart), Date.parse(slot.scheduledEnd), Date.parse(first.scheduledStart), Date.parse(first.scheduledEnd)))
  check(nextCandidates.length > 0, 'reschedule-target')
  const target = nextCandidates[0]
  const reschedule = operations.makeOperation('预约改约', 'POST', `/patient/appointments/${encodeURIComponent(activeAppointment.appointment_id)}/reschedule`, {
    expectedRowVersion: activeAppointment.row_version,
    clinicId: target.clinicId,
    dentistId: target.dentistId,
    scheduleId: target.scheduleId,
    scheduledStart: target.scheduledStart,
    scheduledEnd: target.scheduledEnd,
    requestedTreatments: activeAppointment.requested_treatments,
    reason: '开发联调改约测试（非真实患者办理）'
  }, true, patientId)
  const rescheduleResult = await runWrite(reschedule)
  check(rescheduleResult.appointment && typeof rescheduleResult.appointment.appointment_id === 'string', 'reschedule')
  check(rescheduleResult.appointment.appointment_id !== activeAppointment.appointment_id, 'unique-successor')
  activeAppointment = rescheduleResult.appointment
  check(activeAppointment.appointment_status === '待到诊', 'reschedule-successor-status')
  process.stdout.write('PASS real patient reschedule creates one future successor\n')

  const rescheduleReplay = await requestJson(reschedule.path, 'POST', reschedule.payload)
  check(rescheduleReplay.replayed === true && rescheduleReplay.appointment.appointment_id === activeAppointment.appointment_id, 'reschedule-idempotent-replay')
  process.stdout.write('PASS exact reschedule replay returns the same successor\n')

  const cancellation = await cancelCurrentAppointment()
  check(!operations.readPending(), 'cancel-pending-cleared')
  const finalList = await requestJson('/patient/appointments')
  const finalRecord = finalList.find((item) => item.appointment_status === '患者取消' && item.appointment_no === reschedule.payload.appointmentNo)
  check(finalRecord, 'cancelled-successor-visible')
  process.stdout.write('PASS successor cancellation releases the appointment without creating charges\n')

  const cancelReplay = await requestJson(cancellation.path, 'POST', cancellation.payload)
  check(cancelReplay.replayed === true && cancelReplay.appointment.appointment_id === finalRecord.appointment_id, 'cancel-idempotent-replay')
  process.stdout.write('PASS exact cancel replay returns the cancelled appointment\n')

  await api.logout()
  process.stdout.write('PASS dev/test session closed\n')
}

main().catch(async (error) => {
  process.stderr.write(`FAIL ${error.stage || 'booking-flow'}${error.statusCode ? ` (HTTP ${error.statusCode})` : ''}\n`)
  try {
    await cancelCurrentAppointment()
    process.stderr.write('CLEANUP cancelled the current synthetic test appointment.\n')
  } catch {
    if (activeAppointment) process.stderr.write('CLEANUP_REQUIRED: a synthetic test appointment may need cancellation through the patient account.\n')
  }
  try { await api.logout() } catch {}
  process.exitCode = 1
})
