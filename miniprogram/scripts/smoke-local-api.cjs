const fs = require('node:fs')
const path = require('node:path')
const { generateCandidates } = require('../utils/availability')

const apiBaseUrl = 'http://127.0.0.1:18083/api/v1'
const credentialsPath = path.resolve(__dirname, '..', '..', 'admin-web', '.runtime', 'credentials.json')
const storage = new Map()

global.getApp = () => ({ globalData: {} })
global.wx = {
  getStorageSync(key) { return storage.get(key) || '' },
  setStorageSync(key, value) { storage.set(key, value) },
  removeStorageSync(key) { storage.delete(key) },
  request(options) {
    const requestOptions = {
      method: options.method || 'GET',
      headers: options.header || {}
    }
    if (options.data !== undefined) requestOptions.body = JSON.stringify(options.data)
    fetch(options.url, requestOptions).then(async (response) => {
      const data = await response.json().catch(() => ({}))
      options.success({ statusCode: response.status, data })
    }).catch(() => options.fail())
  }
}

const api = require('../utils/api')

function check(condition, stage) {
  if (!condition) throw Object.assign(new Error('smoke assertion failed'), { stage })
}

async function main() {
  check(apiBaseUrl === require('../config/environment').apiBaseUrl, 'api-base-url')
  const credentials = JSON.parse(fs.readFileSync(credentialsPath, 'utf8'))
  check(typeof credentials.demo?.password === 'string' && credentials.demo.password.length > 0, 'local-demo-credential')

  const profile = await api.patientLogin('demo-patient', credentials.demo.password)
  check(profile && profile.patient && profile.bookingStatus, 'patient-login-profile')
  process.stdout.write('PASS dev-test patient login and own profile\n')

  const bookingStatus = await api.request('/patient/booking-status')
  check(typeof bookingStatus.restricted === 'boolean', 'booking-status')
  process.stdout.write('PASS own booking status\n')

  const clinics = await api.request('/patient/clinics')
  check(Array.isArray(clinics), 'clinics')
  const treatmentTypes = await api.request('/patient/treatment-types')
  check(Array.isArray(treatmentTypes), 'treatment-types')
  check(treatmentTypes.every((item) => typeof item.treatment_type_id === 'string' && typeof item.standard_unit_price === 'string'), 'directory-string-fields')
  if (clinics.length > 0) {
    const clinicId = clinics[0].clinic_id
    check(typeof clinicId === 'string', 'clinic-id-string')
    const [dentists, availability] = await Promise.all([
      api.request(`/patient/dentists?clinicId=${encodeURIComponent(clinicId)}`),
      api.request(`/patient/availability?clinicId=${encodeURIComponent(clinicId)}`)
    ])
    check(Array.isArray(dentists) && availability && Array.isArray(availability.windows), 'availability-contract')
    check(Array.isArray(availability.occupied) && Array.isArray(availability.patientOccupied), 'availability-occupancy-arrays')
    const candidates = generateCandidates({ availability, durationMinutes: 30 })
    check(candidates.every((slot) => typeof slot.scheduleId === 'string' && typeof slot.dentistId === 'string'), 'candidate-string-ids')
    process.stdout.write(`PASS real clinics, dentists, availability (${candidates.length} safe 30-minute candidates) and directory (${treatmentTypes.length} items)\n`)
  } else {
    process.stdout.write(`PASS empty clinic list and directory (${treatmentTypes.length} items)\n`)
  }

  const appointments = await api.request('/patient/appointments')
  check(Array.isArray(appointments), 'patient-appointments')
  process.stdout.write(`PASS own appointment list (${appointments.length} records)\n`)

  if (appointments.length > 0) {
    const appointmentId = appointments[0].appointment_id
    check(typeof appointmentId === 'string' && /^\d+$/.test(appointmentId), 'appointment-id-string')
    const detail = await api.request(`/patient/appointments/${encodeURIComponent(appointmentId)}`)
    check(detail.appointment && Array.isArray(detail.treatments), 'appointment-detail')
    check(Object.prototype.hasOwnProperty.call(detail, 'invoice'), 'invoice-property')
    check(detail.invoice === null || (detail.invoice.status === '发出' && typeof detail.invoice.total_amount === 'string' && Array.isArray(detail.invoice.lines)), 'issued-invoice-only')
    process.stdout.write(`PASS own appointment detail; issued invoice sample ${detail.invoice ? 'present' : 'absent'}\n`)
  } else {
    process.stdout.write('SKIP appointment detail: no existing appointment records\n')
  }

  const oldToken = api.sessionToken()
  check(oldToken.length > 0, 'session-created')
  await api.logout()
  check(api.sessionToken() === '', 'local-session-cleared')
  let unauthorized = false
  try {
    await api.request('/patient/profile', { sessionToken: oldToken, guardSession: false })
  } catch (error) {
    unauthorized = error.statusCode === 401
  }
  check(unauthorized && api.sessionToken() === '', 'logout-revoked-token')
  process.stdout.write('PASS logout revokes session; subsequent profile request returns 401\n')
}

main().catch((error) => {
  process.stderr.write(`FAIL ${error.stage || 'http-request'}${error.statusCode ? ` (HTTP ${error.statusCode})` : ''}\n`)
  process.exitCode = 1
})
