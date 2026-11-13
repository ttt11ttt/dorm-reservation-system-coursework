const assert = require('node:assert/strict')
const stored = new Map([['patientSession', 'old-dev-token'], ['patientPendingWrite', { legacy: true }]])
const logins = [], requests = []
global.getApp = () => ({ globalData: {} })
global.wx = {
  getStorageSync: (key) => stored.get(key) || '',
  setStorageSync: (key, value) => stored.set(key, value),
  removeStorageSync: (key) => stored.delete(key),
  getRandomValues: ({ length, success }) => {
    const bytes = Uint8Array.from({ length }, (_, index) => (randomSeed + index) & 255)
    randomSeed += 1
    success({ randomValues: bytes.buffer })
  },
  arrayBufferToBase64: (buffer) => Buffer.from(buffer).toString('base64'),
  login: (options) => logins.push(options),
  request: (options) => requests.push(options)
}
let randomSeed = 1
const api = require('../utils/api')
const operations = require('../utils/write-operations')
const flush = () => new Promise((resolve) => setImmediate(resolve))
const reply = (index, statusCode, data) => requests[index].success({ statusCode, data })

async function main() {
  assert.equal(api.sessionToken(), '')
  assert.equal(operations.readPending(), null)
  const first = api.bookingLogin()
  logins[0].success({ code: 'fixture-once' })
  assert.equal(requests[0].url, 'https://api.know11know.top/api/v1/booking/wechat-sessions')
  assert.deepEqual(requests[0].data, { code: 'fixture-once' })
  assert.equal(requests[0].header.Authorization, undefined)
  reply(0, 200, { subjectKind: 'PATIENT', subjectKey: 'P-101', token: 'fixture-token', tokenType: 'Bearer', expiresAt: '2035-01-01T08:00:00Z' })
  await flush()
  assert.equal(api.sessionToken(), '') // Candidate token is not installed before identity read.
  assert.equal(requests[1].header.Authorization, 'Bearer fixture-token')
  reply(1, 200, { patient: { patient_id: '101' }, bookingStatus: { restricted: false } })
  await first
  assert.equal(api.sessionToken(), 'fixture-token')
  assert.equal(api.sessionKind(), 'PATIENT')
  assert.equal(api.sessionSubjectKey(), 'P-101')
  assert.ok(!JSON.stringify([...stored.values()]).includes('fixture-once'))

  const unavailable = api.bookingLogin()
  logins[1].success({ code: 'fixture-fresh' })
  reply(2, 503, { code: 'WECHAT_LOGIN_UNAVAILABLE', message: '微信登录暂不可用' })
  await assert.rejects(unavailable, (error) => error.statusCode === 503)
  assert.equal(api.sessionToken(), '')
  assert.equal(requests.length, 3) // No password/dev fallback or automatic code retry.

  const empty = api.bookingLogin()
  logins[2].success({ code: '' })
  await assert.rejects(empty, /未返回登录凭证/)
  const failed = api.bookingLogin()
  logins[3].fail({ errMsg: 'private-platform-details' })
  await assert.rejects(failed, (error) => !error.message.includes('private-platform-details'))
  assert.equal(requests.length, 3)

  const profileFailure = api.bookingLogin()
  logins[4].success({ code: 'fixture-profile' })
  reply(3, 200, { subjectKind: 'PATIENT', subjectKey: 'P-101', token: 'fixture-candidate', tokenType: 'Bearer', expiresAt: '2035-01-01T08:00:00Z' })
  await flush()
  reply(4, 401, { code: 'UNAUTHENTICATED' })
  await assert.rejects(profileFailure, (error) => error.statusCode === 401)
  assert.equal(api.sessionToken(), '')
  assert.ok(requests.every((request) => !request.url.includes('/dev/')))
  const unbound = api.bookingLogin()
  logins[5].success({ code: 'fixture-unbound-login' })
  reply(5, 200, { subjectKind: 'GUEST', subjectKey: 'G-812', token: 'fixture-guest-token', tokenType: 'Bearer', expiresAt: '2035-01-01T08:00:00Z' })
  await flush()
  assert.equal(requests[6].url, 'https://api.know11know.top/api/v1/guest/booking-status')
  reply(6, 200, { restricted: false, restrictedUntil: '' })
  const guestSession = await unbound
  assert.equal(guestSession.subjectKind, 'GUEST')
  assert.equal(api.sessionToken(), 'fixture-guest-token')
  assert.equal(api.sessionKind(), 'GUEST')
  assert.equal(api.sessionSubjectKey(), 'G-812')
  assert.equal(stored.get('hean-rebuild-20261026:patientSession'), undefined)

  const guestOperations = require('../utils/guest-operations')
  const payload = {
    clinicId: '1', dentistId: '2', scheduleId: '3',
    scheduledStart: '2035-01-01T01:00:00.000Z', scheduledEnd: '2035-01-01T01:30:00.000Z',
    contactName: 'Fixture guest', contactPhone: '13800000000', requestedTreatments: 'Fixture request'
  }
  const failedCreate = guestOperations.start(payload)
  const failedCreateResult = assert.rejects(failedCreate, (error) => error.writeOutcomeUncertain === true)
  await flush()
  const createRequestIndex = requests.length - 1
  assert.equal(requests[createRequestIndex].url, 'https://api.know11know.top/api/v1/guest/reservations')
  requests[createRequestIndex].fail({ errMsg: 'network timeout' })
  await failedCreateResult
  const pending = guestOperations.readPending()
  assert.equal(pending.subjectKind, 'GUEST')
  assert.equal(pending.subjectKey, 'G-812')
  assert.equal(pending.method, 'POST')
  assert.equal(pending.path, '/guest/reservations')
  assert.match(pending.reservationNo, /^G-[A-Za-z0-9_-]{22,30}$/)
  assert.equal(pending.payload.reservationNo, pending.reservationNo)
  assert.ok(!JSON.stringify(pending).includes('fixture-guest-token'))

  stored.set('hean-rebuild-20261026:bookingSubjectKey', 'G-999')
  const mismatch = guestOperations.recover()
  await assert.rejects(mismatch, (error) => error.code === 'NO_PENDING_WRITE')
  assert.equal(requests.length, createRequestIndex + 1)
  assert.equal(guestOperations.readPending(), null)
  stored.set('hean-rebuild-20261026:bookingSubjectKey', 'G-812')
  assert.equal(guestOperations.readPending().reservationNo, pending.reservationNo)
  const recovered = guestOperations.recover()
  await flush()
  const lookupIndex = requests.length - 1
  assert.equal(requests[lookupIndex].url, `https://api.know11know.top/api/v1/guest/reservations/by-number/${encodeURIComponent(pending.reservationNo)}`)
  const rawReservation = {
    guest_reservation_id: '91', reservation_no: pending.reservationNo, clinic_id: '1', dentist_id: '2', schedule_id: '3',
    scheduled_start: payload.scheduledStart, scheduled_end: payload.scheduledEnd, contact_name: payload.contactName,
    contact_phone: payload.contactPhone, requested_treatments: payload.requestedTreatments, special_needs: null,
    reservation_status: '待到诊', row_version: 0
  }
  reply(lookupIndex, 200, rawReservation)
  assert.equal((await recovered).reservation_no, pending.reservationNo)
  assert.equal(guestOperations.readPending(), null)

  const retryAfterNotFound = guestOperations.start(payload)
  const retryAfterNotFoundError = assert.rejects(retryAfterNotFound, (error) => error.writeOutcomeUncertain === true)
  await flush()
  const secondCreateIndex = requests.length - 1
  requests[secondCreateIndex].fail({ errMsg: 'network timeout' })
  await retryAfterNotFoundError
  const secondPending = guestOperations.readPending()
  const secondRecovery = guestOperations.recover()
  await flush()
  const secondLookupIndex = requests.length - 1
  reply(secondLookupIndex, 404, { code: 'NOT_FOUND', message: 'Not found' })
  await flush()
  const exactRetryIndex = requests.length - 1
  assert.equal(requests[exactRetryIndex].url, 'https://api.know11know.top/api/v1/guest/reservations')
  assert.deepEqual(requests[exactRetryIndex].data, secondPending.payload)
  reply(exactRetryIndex, 200, { replayed: false, verificationStatus: '医疗身份待到店核验', reservation: { ...rawReservation, reservation_no: secondPending.reservationNo } })
  assert.equal((await secondRecovery).reservation_no, secondPending.reservationNo)
  assert.equal(guestOperations.readPending(), null)

  const routeFence = api.request('/patient/profile')
  await assert.rejects(routeFence, (error) => error.code === 'SUBJECT_ROUTE_FORBIDDEN')
  console.log('Unified WeChat auth + guest idempotency fixtures passed; no network, patient data, or WeChat UI')
}
main().catch(() => { console.error('WeChat auth fixture check failed'); process.exitCode = 1 })
