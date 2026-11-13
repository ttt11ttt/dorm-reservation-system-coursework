const assert = require('node:assert/strict')

const stored = new Map()
const pending = []
const app = { globalData: { profile: { old: true } } }
global.getApp = () => app
global.wx = {
  login(options) { options.success({ code: 'fixture-wx-code' }) },
  getStorageSync(key) { return stored.get(key) || '' },
  setStorageSync(key, value) { stored.set(key, value) },
  removeStorageSync(key) { stored.delete(key) },
  request(options) { pending.push(options) }
}

const { apiBaseUrl } = require('../config/environment')
const api = require('../utils/api')
const operations = require('../utils/write-operations')
const { formatShanghaiDateTime, isZeroAmount } = require('../utils/date')
const { generateCandidates } = require('../utils/availability')
const followupPlans = require('../utils/followup')
let registeredHome
global.Page = (definition) => { registeredHome = definition }
require('../pages/home/index.js')
let registeredDetail
global.Page = (definition) => { registeredDetail = definition }
require('../pages/appointment/index.js')
let registeredBooking
global.Page = (definition) => { registeredBooking = definition }
require('../pages/booking/index.js')
let registeredFollowups
global.Page = (definition) => { registeredFollowups = definition }
require('../pages/followups/index.js')
let registeredFollowupDetail
global.Page = (definition) => { registeredFollowupDetail = definition }
require('../pages/followups/detail.js')
const redirects = []
wx.redirectTo = (options) => redirects.push(options)
const navigations = []
wx.navigateTo = (options) => navigations.push(options)

function respond(index, statusCode, data) {
  const request = pending[index]
  assert.ok(request, `request ${index} was issued`)
  if (request.url.endsWith('/booking/wechat-sessions') && statusCode === 200 && data.token) {
    data = { tokenType: 'Bearer', subjectKind: 'PATIENT', subjectKey: 'P-812', expiresAt: '2035-01-01T00:00:00Z', ...data }
  }
  request.success({ statusCode, data })
}

function flush() { return new Promise((resolve) => setImmediate(resolve)) }

async function main() {
  assert.equal(apiBaseUrl, 'https://api.know11know.top/api/v1')

  const login = api.wechatLogin()
  assert.equal(pending[0].url, `${apiBaseUrl}/booking/wechat-sessions`)
  assert.equal(pending[0].method, 'POST')
  assert.deepEqual(pending[0].data, { code: 'fixture-wx-code' })
  respond(0, 200, { token: 'fixture-token', tokenType: 'Bearer' })
  await flush()
  assert.equal(pending[1].url, `${apiBaseUrl}/patient/profile`)
  assert.equal(pending[1].header.Authorization, 'Bearer fixture-token')
  respond(1, 200, { patient: { patient_no: 'FIXTURE' }, bookingStatus: { restricted: false } })
  const profile = await login
  assert.equal(profile.patient.patient_no, 'FIXTURE')
  assert.equal(api.sessionToken(), 'fixture-token')

  const oldRequest = api.request('/patient/profile')
  const logout = api.logout()
  assert.equal(api.sessionToken(), '')
  const loginAgain = api.wechatLogin()
  respond(4, 200, { token: 'fixture-token-2' })
  await flush()
  respond(5, 200, { patient: { patient_no: 'FIXTURE' }, bookingStatus: { restricted: false } })
  await loginAgain
  respond(2, 200, [])
  await assert.rejects(oldRequest, (error) => error.code === 'SESSION_CHANGED')
  respond(3, 401, { code: 'UNAUTHENTICATED', message: 'old logout session expired' })
  await assert.rejects(logout, (error) => error.statusCode === 401)
  assert.equal(api.sessionToken(), 'fixture-token-2')
  assert.equal(app.globalData.profile.patient.patient_no, 'FIXTURE')

  const expired = api.request('/patient/profile')
  respond(6, 401, { code: 'UNAUTHENTICATED', message: 'expired' })
  await assert.rejects(expired, (error) => error.statusCode === 401)
  assert.equal(api.sessionToken(), '')

  const pageState = {
    _requestGeneration: 4,
    _sessionToken: 'expired-token',
    data: { profile: { old: true }, password: 'typed', appointments: [{ appointment_id: '1' }] },
    setData(next) { Object.assign(this.data, next) },
    syncPendingOperation() {}
  }
  registeredHome.showError.call(pageState, { statusCode: 401, message: 'expired' }, 4)
  assert.equal(pageState.data.profile, null)
  assert.equal(pageState.data.password, '')
  assert.deepEqual(pageState.data.appointments, [])
  registeredHome.showError.call(pageState, { message: 'stale response' }, 3)
  assert.equal(pageState.data.error, '登录已失效，请重新登录。')

  const detailState = {
    _requestGeneration: 0,
    _sessionToken: 'expired-token',
    data: {
      appointmentId: '71', appointment: { appointment_no: 'OLD' },
      treatments: [{ treatment_id: '1' }], invoice: { invoice_no: 'OLD' },
      loading: false, error: ''
    },
    setData(next) { Object.assign(this.data, next) },
    clearDetail: registeredDetail.clearDetail,
    loadDetail: registeredDetail.loadDetail,
    onShow: registeredDetail.onShow
  }
  await registeredDetail.loadDetail.call(detailState)
  assert.equal(detailState.data.appointment, null)
  assert.deepEqual(detailState.data.treatments, [])
  assert.equal(detailState.data.invoice, null)
  detailState.data.appointment = { appointment_no: 'STALE' }
  registeredDetail.onShow.call(detailState)
  assert.equal(detailState.data.appointment, null)

  const detailLogin = api.wechatLogin()
  respond(7, 200, { token: 'fixture-detail-token' })
  await flush()
  respond(8, 200, { patient: { patient_no: 'FIXTURE' }, bookingStatus: { restricted: false } })
  await detailLogin
  detailState._sessionToken = api.sessionToken()
  detailState.data.appointment = { appointment_no: 'OLD' }
  const expiredDetail = registeredDetail.loadDetail.call(detailState)
  respond(9, 401, { code: 'UNAUTHENTICATED', message: 'expired' })
  respond(10, 401, { code: 'UNAUTHENTICATED', message: 'expired' })
  respond(11, 200, [])
  await expiredDetail
  assert.equal(detailState.data.appointment, null)
  assert.deepEqual(detailState.data.treatments, [])
  assert.equal(detailState.data.invoice, null)

  const detailLoginAgain = api.wechatLogin()
  respond(12, 200, { token: 'fixture-detail-token-2' })
  await flush()
  respond(13, 200, { patient: { patient_no: 'FIXTURE' }, bookingStatus: { restricted: false } })
  await detailLoginAgain
  detailState._sessionToken = api.sessionToken()
  detailState.data.appointment = { appointment_no: 'OLD' }
  const lateDetail = registeredDetail.loadDetail.call(detailState)
  const setDataCount = (detailState.setDataCount || 0)
  const originalSetData = detailState.setData
  detailState.setData = function (next) { this.setDataCount = (this.setDataCount || 0) + 1; originalSetData.call(this, next) }
  const countAfterLoadStarted = detailState.setDataCount || setDataCount
  registeredDetail.onUnload.call(detailState)
  respond(14, 200, { appointment: { appointment_no: 'NEW' }, treatments: [], invoice: null })
  respond(15, 200, { restricted: false, frontDeskAvailable: true })
  respond(16, 200, [])
  await lateDetail
  assert.equal(detailState.data.appointment.appointment_no, 'OLD')
  assert.equal(detailState.setDataCount || 0, countAfterLoadStarted)

  const availability = {
    asOf: '2026-10-22T07:00:00Z',
    windows: [{
      schedule_id: '9007199254740993', clinic_id: '2', dentist_id: '7',
      start_at: '2026-10-22T08:00:00Z', end_at: '2026-10-22T10:00:00Z'
    }],
    occupied: [
      { dentist_id: '7', scheduled_start: '2026-10-22T08:30:00Z', scheduled_end: '2026-10-22T09:00:00Z' },
      { dentist_id: '8', scheduled_start: '2026-10-22T08:00:00Z', scheduled_end: '2026-10-22T10:00:00Z' }
    ],
    patientOccupied: [{ scheduled_start: '2026-10-22T09:30:00Z', scheduled_end: '2026-10-22T10:00:00Z' }]
  }
  const halfHourCandidates = generateCandidates({ availability, durationMinutes: 30, nowMs: Date.parse('2026-10-22T07:00:00Z') })
  assert.deepEqual(halfHourCandidates.map((slot) => slot.scheduledStart), ['2026-10-22T08:00:00.000Z', '2026-10-22T09:00:00.000Z'])
  assert.equal(halfHourCandidates[0].scheduleId, '9007199254740993')
  assert.equal(generateCandidates({ availability, durationMinutes: 60, nowMs: Date.parse('2026-10-22T07:00:00Z') }).length, 0)
  assert.deepEqual(generateCandidates({ availability, durationMinutes: 30, nowMs: Date.parse('2026-10-22T08:45:00Z') }).map((slot) => slot.scheduledStart), ['2026-10-22T09:00:00.000Z'])
  const rescheduleAvailability = {
    ...availability,
    patientOccupied: [{ scheduled_start: '2026-10-22T08:00:00Z', scheduled_end: '2026-10-22T08:30:00Z' }]
  }
  assert.deepEqual(generateCandidates({ availability: rescheduleAvailability, durationMinutes: 30, nowMs: Date.parse('2026-10-22T07:00:00Z') }).map((slot) => slot.scheduledStart), ['2026-10-22T09:00:00.000Z', '2026-10-22T09:30:00.000Z'])

  const operation = operations.makeOperation('预约新增', 'POST', '/patient/appointments', {
    clinicId: '2', dentistId: '7', scheduleId: '9007199254740993',
    scheduledStart: '2026-10-22T09:00:00Z', scheduledEnd: '2026-10-22T09:30:00Z',
    requestedTreatments: 'fixture only'
  }, true)
  const pendingIndex = pending.length
  const uncertain = operations.start(operation)
  assert.match(operation.payload.appointmentNo, /^[A-Za-z0-9-]{1,32}$/)
  assert.equal(operations.readPending().operationNo, operation.operationNo)
  pending[pendingIndex].fail()
  await assert.rejects(uncertain, (error) => error.writeOutcomeUncertain === true)
  const savedOperation = operations.readPending()
  const unauthorizedRetryIndex = pending.length
  const unauthorizedRetry = operations.retryPending()
  pending[unauthorizedRetryIndex].success({ statusCode: 401, data: { code: 'UNAUTHENTICATED' } })
  await assert.rejects(unauthorizedRetry, (error) => error.statusCode === 401 && error.writeOutcomeUncertain === true)
  assert.equal(operations.readPending().operationNo, savedOperation.operationNo)
  const retryIndex = pending.length
  const retried = operations.retryPending()
  assert.equal(pending[retryIndex].url, `${apiBaseUrl}/patient/appointments`)
  assert.deepEqual(pending[retryIndex].data, savedOperation.payload)
  pending[retryIndex].success({ statusCode: 200, data: { replayed: true, appointment: { appointment_id: '9000000000000001' } } })
  await retried
  assert.equal(operations.readPending(), null)

  assert.equal(formatShanghaiDateTime('2026-10-21T16:00:00Z'), '2026-10-22 00:00')
  assert.equal(isZeroAmount('0.00'), true)
  assert.equal(isZeroAmount('0'), true)
  assert.equal(isZeroAmount('0.01'), false)

  const makePage = (definition) => {
    const page = Object.create(definition)
    page.data = JSON.parse(JSON.stringify(definition.data))
    page._alive = true
    page._requestGeneration = 0
    page._resourceGeneration = 0
    page.setData = function (next) { Object.assign(this.data, next) }
    return page
  }
  const resolveBookingLoad = async (start, patientId) => {
    respond(start, 200, [])
    respond(start + 1, 200, { restricted: false })
    respond(start + 2, 200, { patient: { patient_id: patientId } })
    respond(start + 3, 200, [])
    await flush()
    await flush()
  }

  api.clearSession()
  const noSessionPage = makePage(registeredBooking)
  noSessionPage._alive = true
  noSessionPage._sessionToken = 'old-token'
  noSessionPage._patientId = 'patient-old'
  Object.assign(noSessionPage.data, {
    mode: 'reschedule', sourceAppointment: { appointment_no: 'OLD' }, sourceTreatments: [{ treatment_id: '1' }],
    sourceInvoice: { invoice_no: 'OLD' }, bookingStatus: { restricted: false }, clinics: [{ clinic_id: '1' }],
    requestedTreatments: 'old patient request', specialNeeds: 'old patient note', reason: 'old reason',
    pendingOperation: { kind: '预约改约', matchesPatient: true }
  })
  registeredBooking.onShow.call(noSessionPage)
  assert.equal(noSessionPage.data.sourceAppointment, null)
  assert.deepEqual(noSessionPage.data.sourceTreatments, [])
  assert.equal(noSessionPage.data.sourceInvoice, null)
  assert.deepEqual(noSessionPage.data.clinics, [])
  assert.equal(noSessionPage.data.requestedTreatments, '')
  assert.equal(noSessionPage.data.specialNeeds, '')
  assert.equal(noSessionPage.data.reason, '')
  assert.equal(noSessionPage.data.pendingOperation, null)

  const bookingPage = makePage(registeredBooking)
  bookingPage._alive = true
  bookingPage._sessionToken = 'patient-a-token'
  bookingPage._patientId = 'patient-a'
  bookingPage.data.mode = 'new'
  wx.setStorageSync('hean-rebuild-20261026:patientSession', 'patient-a-token')
  const pendingEnvelope = {
    operationNo: 'MP-fixture-private', kind: '预约改约', patientId: 'patient-a', method: 'POST',
    path: '/patient/appointments/10/reschedule', payload: { requestedTreatments: 'patient-a private request' }, savedAt: 'fixture'
  }
  wx.setStorageSync('hean-rebuild-20261026:patientPendingWrite', pendingEnvelope)
  bookingPage.data.requestedTreatments = 'patient-a typed request'
  bookingPage.data.specialNeeds = 'patient-a typed note'
  bookingPage.data.reason = 'patient-a reason'
  const firstBookingLoad = pending.length
  registeredBooking.loadWorkflow.call(bookingPage, true)
  await resolveBookingLoad(firstBookingLoad, 'patient-a')
  assert.equal(bookingPage._patientId, 'patient-a')
  assert.equal(bookingPage.data.requestedTreatments, 'patient-a typed request')

  bookingPage.data.requestedTreatments = 'must not transfer'
  bookingPage.data.specialNeeds = 'must not transfer either'
  bookingPage.data.reason = 'must not transfer reason'
  wx.setStorageSync('hean-rebuild-20261026:patientSession', 'patient-b-token')
  const secondBookingLoad = pending.length
  registeredBooking.onShow.call(bookingPage)
  assert.equal(bookingPage.data.requestedTreatments, '')
  assert.equal(bookingPage.data.specialNeeds, '')
  assert.equal(bookingPage.data.reason, '')
  assert.equal(bookingPage.data.sourceAppointment, null)
  assert.equal(bookingPage.data.pendingOperation, null)
  await resolveBookingLoad(secondBookingLoad, 'patient-b')
  assert.equal(bookingPage._patientId, 'patient-b')
  assert.deepEqual(bookingPage.data.pendingOperation, { matchesPatient: false })
  assert.equal(Object.hasOwn(bookingPage.data.pendingOperation, 'kind'), false)
  const beforeWrongPatientRetry = pending.length
  registeredBooking.retryPendingOperation.call(bookingPage)
  assert.equal(pending.length, beforeWrongPatientRetry)
  assert.deepEqual(wx.getStorageSync('hean-rebuild-20261026:patientPendingWrite'), pendingEnvelope)
  wx.removeStorageSync('hean-rebuild-20261026:patientPendingWrite') // The fixture owns this in-memory envelope; production code never clears it here.

  const unloadPage = makePage(registeredBooking)
  unloadPage._alive = true
  unloadPage._sessionToken = 'patient-b-token'
  unloadPage.data.mode = 'new'
  unloadPage.data.sourceAppointment = { appointment_no: 'must remain unchanged after unload' }
  const beforeUnloadLoad = pending.length
  const unloadLoad = registeredBooking.loadWorkflow.call(unloadPage, false)
  const writesBeforeUnload = unloadPage.setDataCount || 0
  const unloadOriginalSetData = unloadPage.setData
  unloadPage.setData = function (next) { this.setDataCount = (this.setDataCount || 0) + 1; unloadOriginalSetData.call(this, next) }
  const writesAfterLoad = unloadPage.setDataCount || writesBeforeUnload
  registeredBooking.onUnload.call(unloadPage)
  await resolveBookingLoad(beforeUnloadLoad, 'patient-b')
  await unloadLoad
  assert.equal(unloadPage.data.sourceAppointment.appointment_no, 'must remain unchanged after unload')
  assert.equal(unloadPage.setDataCount || 0, writesAfterLoad)

  const resourcePage = makePage(registeredBooking)
  resourcePage._alive = true
  resourcePage._requestGeneration = 3
  resourcePage._resourceGeneration = 0
  resourcePage._sessionToken = 'patient-b-token'
  resourcePage.data.selectedClinicId = '1'
  wx.setStorageSync('hean-rebuild-20261026:patientSession', 'patient-b-token')
  const beforeResources = pending.length
  const resourceLoad = registeredBooking.loadClinicResources.call(resourcePage, 3, 'patient-b-token')
  const resourceWritesBefore = resourcePage.setDataCount || 0
  const resourceSetData = resourcePage.setData
  resourcePage.setData = function (next) { this.setDataCount = (this.setDataCount || 0) + 1; resourceSetData.call(this, next) }
  const resourceWritesAfterLoad = resourcePage.setDataCount || resourceWritesBefore
  registeredBooking.onUnload.call(resourcePage)
  respond(beforeResources, 200, [{ dentist_id: '9', name: 'fixture dentist' }])
  respond(beforeResources + 1, 200, { windows: [] })
  await resourceLoad
  assert.equal(resourcePage._availability, null)
  assert.equal(resourcePage.setDataCount || 0, resourceWritesAfterLoad)

  wx.setStorageSync('hean-rebuild-20261026:patientSession', 'patient-c-token')
  const switchedResourcePage = makePage(registeredBooking)
  switchedResourcePage._sessionToken = 'patient-c-token'
  switchedResourcePage._requestGeneration = 5
  switchedResourcePage.data.selectedClinicId = '1'
  const beforeSwitchedResources = pending.length
  const switchedResourceLoad = registeredBooking.loadClinicResources.call(switchedResourcePage, 5, 'patient-c-token')
  wx.setStorageSync('hean-rebuild-20261026:patientSession', 'patient-d-token')
  respond(beforeSwitchedResources, 200, [{ dentist_id: '9', name: 'stale dentist' }])
  respond(beforeSwitchedResources + 1, 200, { windows: [] })
  await switchedResourceLoad
  assert.equal(switchedResourcePage._availability, null)
  assert.deepEqual(switchedResourcePage.data.dentists, [])
  assert.equal(switchedResourcePage.data.selectedClinicId, '')

  wx.setStorageSync('hean-rebuild-20261026:patientSession', 'patient-401-token')
  const unauthorizedPage = makePage(registeredBooking)
  unauthorizedPage._alive = true
  unauthorizedPage._sessionToken = 'patient-401-token'
  unauthorizedPage._patientId = 'patient-401'
  unauthorizedPage.data.mode = 'reschedule'
  Object.assign(unauthorizedPage.data, {
    sourceAppointment: { appointment_no: 'OLD', patient_id: 'patient-401' },
    sourceTreatments: [{ treatment_id: '1' }], sourceInvoice: { invoice_no: 'OLD' },
    bookingStatus: { restricted: false }, requestedTreatments: 'old request', specialNeeds: 'old note', reason: 'old reason'
  })
  const unauthorizedOperation = operations.makeOperation('预约改约', 'POST', '/patient/appointments/10/reschedule', { reason: 'fixture' }, false, 'patient-401')
  const unauthorizedIndex = pending.length
  registeredBooking.sendOperation.call(unauthorizedPage, unauthorizedOperation)
  respond(unauthorizedIndex, 401, { code: 'UNAUTHENTICATED' })
  await new Promise((resolve) => setImmediate(resolve))
  assert.equal(unauthorizedPage.data.sourceAppointment, null)
  assert.deepEqual(unauthorizedPage.data.sourceTreatments, [])
  assert.equal(unauthorizedPage.data.sourceInvoice, null)
  assert.equal(unauthorizedPage.data.bookingStatus, null)
  assert.equal(unauthorizedPage.data.requestedTreatments, '')
  assert.equal(unauthorizedPage.data.specialNeeds, '')
  assert.equal(unauthorizedPage.data.reason, '')
  assert.equal(operations.readPending(), null)

  wx.setStorageSync('hean-rebuild-20261026:patientSession', 'patient-write-token')
  const lateWritePage = makePage(registeredBooking)
  lateWritePage._alive = true
  lateWritePage._sessionToken = 'patient-write-token'
  lateWritePage._patientId = 'patient-write'
  const beforeLateWrite = pending.length
  const lateOperation = operations.makeOperation('预约新增', 'POST', '/patient/appointments', { requestedTreatments: 'fixture only' }, true, 'patient-write')
  registeredBooking.sendOperation.call(lateWritePage, lateOperation)
  const setDataBeforeLateUnload = lateWritePage.setDataCount || 0
  const lateSetData = lateWritePage.setData
  lateWritePage.setData = function (next) { this.setDataCount = (this.setDataCount || 0) + 1; lateSetData.call(this, next) }
  const setDataAfterStartingLateWrite = lateWritePage.setDataCount || setDataBeforeLateUnload
  registeredBooking.onUnload.call(lateWritePage)
  respond(beforeLateWrite, 200, { appointment: { appointment_id: '9000000000000002' } })
  await new Promise((resolve) => setImmediate(resolve))
  assert.equal(lateWritePage.setDataCount || 0, setDataAfterStartingLateWrite)
  assert.equal(redirects.length, 0)
  assert.equal(operations.readPending(), null)

  const makePlan = (id, status, overdue, suggestedDate = '2026-10-01') => ({
    followup_plan_id: id, patient_id: '7001', source_appointment_id: '8001', dentist_id: '9001', clinic_id: '6001',
    suggested_date: suggestedDate, recommendation: `fixture plan ${id}`, status,
    completed_appointment_id: null, completed_at: null, cancel_reason: status === '已取消' ? 'fixture cancel' : null,
    created_at: '2026-10-01T01:00:00+08:00', updated_at: '2026-10-01T01:00:00+08:00', overdue
  })
  const makeFollowupPage = (definition) => {
    const page = makePage(definition)
    page.onLoad()
    return page
  }
  const followupResponse = (items, asOfDate, hasMore, nextAfterId) => ({ items, asOfDate, hasMore, nextAfterId })
  assert.equal(followupPlans.isId('1'), true)
  assert.equal(followupPlans.isId('9223372036854775807'), true)
  assert.equal(followupPlans.isId('0'), false)
  assert.equal(followupPlans.isId('092'), false)
  assert.equal(followupPlans.isId('9223372036854775808'), false)

  wx.setStorageSync('hean-rebuild-20261026:patientSession', 'followup-token-a')
  const followupPage = makeFollowupPage(registeredFollowups)
  followupPage._sessionToken = 'followup-token-a'
  const firstFollowupIndex = pending.length
  const firstFollowupLoad = registeredFollowups.refreshList.call(followupPage)
  assert.match(pending[firstFollowupIndex].url, /\/patient\/followup-plans\?status=all&limit=30$/)
  respond(firstFollowupIndex, 200, followupResponse([makePlan('9007199254740993001', '已完成', false)], '2026-10-22', true, '9007199254740993001'))
  await firstFollowupLoad
  assert.equal(followupPage.data.items[0].status, '已完成')
  assert.equal(followupPage.data.items[0].overdue, false)
  assert.equal(followupPage.data.items[0].suggested_date, '2026-10-01')
  assert.equal(followupPage.data.asOfDate, '2026-10-22')

  const secondFollowupIndex = pending.length
  const secondFollowupLoad = registeredFollowups.loadNextPage.call(followupPage)
  assert.match(pending[secondFollowupIndex].url, /afterId=9007199254740993001$/)
  respond(secondFollowupIndex, 200, followupResponse([makePlan('9007199254740993002', '待复诊', true)], '2026-10-22', false, null))
  await secondFollowupLoad
  assert.deepEqual(followupPage.data.items.map((plan) => [plan.status, plan.overdue]), [['已完成', false], ['待复诊', true]])
  assert.equal(followupPage.data.hasMore, false)
  assert.equal(followupPage.data.nextAfterId, '')

  const filterPage = makeFollowupPage(registeredFollowups)
  filterPage._sessionToken = 'followup-token-a'
  const filterIndex = pending.length
  const filterLoad = registeredFollowups.onFilterChange.call(filterPage, { detail: { value: 1 } })
  assert.equal(filterPage.data.status, '待复诊')
  assert.match(pending[filterIndex].url, /status=%E5%BE%85%E5%A4%8D%E8%AF%8A&limit=30$/)
  respond(filterIndex, 200, followupResponse([makePlan('101', '待复诊', false)], '2026-10-22', true, '101'))
  await filterLoad

  const failedPage = makeFollowupPage(registeredFollowups)
  failedPage._sessionToken = 'followup-token-a'
  const failedFirstIndex = pending.length
  const failedFirstLoad = registeredFollowups.refreshList.call(failedPage)
  respond(failedFirstIndex, 200, followupResponse([makePlan('201', '待复诊', false)], '2026-10-22', true, '201'))
  await failedFirstLoad
  const failedNextIndex = pending.length
  const failedNext = registeredFollowups.loadNextPage.call(failedPage)
  const duplicateNext = registeredFollowups.loadNextPage.call(failedPage)
  assert.equal(pending.length, failedNextIndex + 1)
  assert.match(pending[failedNextIndex].url, /afterId=201$/)
  respond(failedNextIndex, 503, { code: 'SERVICE_UNAVAILABLE', message: 'fixture failure' })
  await failedNext
  await duplicateNext
  assert.equal(failedPage.data.nextAfterId, '201')
  assert.equal(failedPage.data.hasMore, true)
  assert.equal(failedPage.data.loadingMore, false)
  assert.match(failedPage.data.error, /fixture failure/)
  const retryNextIndex = pending.length
  const retryNext = registeredFollowups.loadNextPage.call(failedPage)
  assert.match(pending[retryNextIndex].url, /afterId=201$/)
  respond(retryNextIndex, 200, followupResponse([makePlan('202', '已取消', false)], '2026-10-22', false, null))
  await retryNext
  assert.equal(failedPage.data.items[1].status, '已取消')

  const unauthorizedNextPage = makeFollowupPage(registeredFollowups)
  unauthorizedNextPage._sessionToken = 'followup-next-401-token'
  wx.setStorageSync('hean-rebuild-20261026:patientSession', 'followup-next-401-token')
  const unauthorizedNextFirstIndex = pending.length
  const unauthorizedNextFirst = registeredFollowups.refreshList.call(unauthorizedNextPage)
  respond(unauthorizedNextFirstIndex, 200, followupResponse([makePlan('251', '待复诊', true)], '2026-10-22', true, '251'))
  await unauthorizedNextFirst
  const unauthorizedNextIndex = pending.length
  const unauthorizedNext = registeredFollowups.loadNextPage.call(unauthorizedNextPage)
  respond(unauthorizedNextIndex, 401, { code: 'UNAUTHENTICATED' })
  await unauthorizedNext
  assert.deepEqual(unauthorizedNextPage.data.items, [])
  assert.equal(unauthorizedNextPage.data.nextAfterId, '')

  wx.setStorageSync('hean-rebuild-20261026:patientSession', 'followup-token-a')
  const crossDayPage = makeFollowupPage(registeredFollowups)
  crossDayPage._sessionToken = 'followup-token-a'
  const crossDayFirstIndex = pending.length
  const crossDayFirst = registeredFollowups.refreshList.call(crossDayPage)
  respond(crossDayFirstIndex, 200, followupResponse([makePlan('301', '待复诊', false)], '2026-10-22', true, '301'))
  await crossDayFirst
  const crossDayNextIndex = pending.length
  const crossDayNext = registeredFollowups.loadNextPage.call(crossDayPage)
  assert.match(pending[crossDayNextIndex].url, /afterId=301$/)
  respond(crossDayNextIndex, 200, followupResponse([makePlan('302', '待复诊', false)], '2026-10-23', false, null))
  await new Promise((resolve) => setImmediate(resolve))
  const crossDayRefreshIndex = pending.length - 1
  assert.match(pending[crossDayRefreshIndex].url, /status=all&limit=30$/)
  assert.equal(crossDayPage.data.items.length, 0)
  assert.equal(crossDayPage.data.nextAfterId, '')
  respond(crossDayRefreshIndex, 200, followupResponse([makePlan('301', '待复诊', false)], '2026-10-23', true, '301'))
  await crossDayNext
  assert.equal(crossDayPage.data.asOfDate, '2026-10-23')
  assert.equal(crossDayPage.data.items.length, 1)

  wx.setStorageSync('hean-rebuild-20261026:patientSession', 'followup-old-token')
  const switchedFollowupPage = makeFollowupPage(registeredFollowups)
  switchedFollowupPage._sessionToken = 'followup-old-token'
  const oldFollowupIndex = pending.length
  const oldFollowupLoad = registeredFollowups.refreshList.call(switchedFollowupPage)
  wx.setStorageSync('hean-rebuild-20261026:patientSession', 'followup-new-token')
  const newFollowupIndex = pending.length
  registeredFollowups.onShow.call(switchedFollowupPage)
  respond(oldFollowupIndex, 200, followupResponse([makePlan('401', '已取消', false)], '2026-10-22', false, null))
  respond(newFollowupIndex, 200, followupResponse([makePlan('402', '待复诊', true)], '2026-10-22', false, null))
  await oldFollowupLoad
  await new Promise((resolve) => setImmediate(resolve))
  assert.deepEqual(switchedFollowupPage.data.items.map((plan) => plan.followup_plan_id), ['402'])

  wx.setStorageSync('hean-rebuild-20261026:patientSession', 'followup-unload-token')
  const unloadedFollowupPage = makeFollowupPage(registeredFollowups)
  unloadedFollowupPage._sessionToken = 'followup-unload-token'
  unloadedFollowupPage.data.items = [makePlan('451', '已取消', false)]
  const unloadedListIndex = pending.length
  const unloadedListLoad = registeredFollowups.refreshList.call(unloadedFollowupPage)
  registeredFollowups.onUnload.call(unloadedFollowupPage)
  respond(unloadedListIndex, 200, followupResponse([makePlan('452', '已完成', false)], '2026-10-22', false, null))
  await unloadedListLoad
  assert.deepEqual(unloadedFollowupPage.data.items, [])

  const unauthorizedFollowupPage = makeFollowupPage(registeredFollowups)
  wx.setStorageSync('hean-rebuild-20261026:patientSession', 'followup-401-token')
  unauthorizedFollowupPage._sessionToken = 'followup-401-token'
  unauthorizedFollowupPage.data.items = [makePlan('501', '已完成', false)]
  unauthorizedFollowupPage.data.asOfDate = '2026-10-22'
  unauthorizedFollowupPage.data.hasMore = true
  unauthorizedFollowupPage.data.nextAfterId = '501'
  const unauthorizedFollowupIndex = pending.length
  const unauthorizedFollowupLoad = registeredFollowups.refreshList.call(unauthorizedFollowupPage)
  respond(unauthorizedFollowupIndex, 401, { code: 'UNAUTHENTICATED' })
  await unauthorizedFollowupLoad
  assert.deepEqual(unauthorizedFollowupPage.data.items, [])
  assert.equal(unauthorizedFollowupPage.data.asOfDate, '')
  assert.equal(unauthorizedFollowupPage.data.nextAfterId, '')
  assert.match(unauthorizedFollowupPage.data.error, /重新登录/)

  wx.setStorageSync('hean-rebuild-20261026:patientSession', 'followup-detail-token')
  const followupDetailPage = makeFollowupPage(registeredFollowupDetail)
  followupDetailPage.data.planId = '601'
  followupDetailPage._sessionToken = 'followup-detail-token'
  const detailIndex = pending.length
  const detailLoad = registeredFollowupDetail.loadDetail.call(followupDetailPage)
  assert.equal(pending[detailIndex].url, `${apiBaseUrl}/patient/followup-plans/601`)
  const cancelledPlan = makePlan('601', '已取消', false)
  respond(detailIndex, 200, cancelledPlan)
  await detailLoad
  assert.equal(followupDetailPage.data.plan.status, '已取消')
  assert.equal(followupDetailPage.data.plan.overdue, false)
  assert.equal(followupDetailPage.data.plan.recommendation, 'fixture plan 601')
  followupDetailPage.data.plan = makePlan('601', '待复诊', true)
  followupDetailPage._sessionToken = api.sessionToken()
  const navigationCountBeforeLink = navigations.length
  followupDetailPage.data.plan.status = '已取消'
  registeredFollowupDetail.arrangeFollowup.call(followupDetailPage)
  assert.equal(navigations.length, navigationCountBeforeLink)
  followupDetailPage.data.plan.status = '待复诊'
  registeredFollowupDetail.arrangeFollowup.call(followupDetailPage)
  assert.equal(navigations.length, navigationCountBeforeLink + 1)
  assert.equal(navigations.at(-1).url, '/pages/booking/index?mode=new&planId=601')
  assert.equal(navigations.at(-1).url.includes('patientId'), false)

  wx.setStorageSync('hean-rebuild-20261026:patientSession', 'followup-detail-old-token')
  const switchedDetailPage = makeFollowupPage(registeredFollowupDetail)
  switchedDetailPage.data.planId = '604'
  switchedDetailPage._sessionToken = 'followup-detail-old-token'
  const oldDetailIndex = pending.length
  const oldDetailLoad = registeredFollowupDetail.loadDetail.call(switchedDetailPage)
  wx.setStorageSync('hean-rebuild-20261026:patientSession', 'followup-detail-new-token')
  const newDetailIndex = pending.length
  registeredFollowupDetail.onShow.call(switchedDetailPage)
  assert.equal(switchedDetailPage.data.plan, null)
  respond(oldDetailIndex, 200, makePlan('604', '已取消', false))
  respond(newDetailIndex, 200, makePlan('604', '待复诊', true))
  await oldDetailLoad
  await new Promise((resolve) => setImmediate(resolve))
  assert.equal(switchedDetailPage.data.plan.status, '待复诊')

  const lateFollowupDetailPage = makeFollowupPage(registeredFollowupDetail)
  lateFollowupDetailPage.data.planId = '602'
  lateFollowupDetailPage._sessionToken = 'followup-detail-token'
  lateFollowupDetailPage.data.plan = makePlan('602', '待复诊', true)
  const lateDetailIndex = pending.length
  const lateFollowupDetail = registeredFollowupDetail.loadDetail.call(lateFollowupDetailPage)
  registeredFollowupDetail.onUnload.call(lateFollowupDetailPage)
  respond(lateDetailIndex, 200, makePlan('602', '已完成', false))
  await lateFollowupDetail
  assert.equal(lateFollowupDetailPage.data.plan.status, '待复诊')

  wx.setStorageSync('hean-rebuild-20261026:patientSession', 'followup-detail-401-token')
  const unauthorizedDetailPage = makeFollowupPage(registeredFollowupDetail)
  unauthorizedDetailPage.data.planId = '603'
  unauthorizedDetailPage._sessionToken = 'followup-detail-401-token'
  unauthorizedDetailPage.data.plan = makePlan('603', '待复诊', true)
  const unauthorizedDetailIndex = pending.length
  const unauthorizedDetail = registeredFollowupDetail.loadDetail.call(unauthorizedDetailPage)
  respond(unauthorizedDetailIndex, 401, { code: 'UNAUTHENTICATED' })
  await unauthorizedDetail
  assert.equal(unauthorizedDetailPage.data.plan, null)
  assert.match(unauthorizedDetailPage.data.error, /重新登录/)

  const createLinkedBookingPage = async (planId, status = '待复诊') => {
    wx.setStorageSync('hean-rebuild-20261026:patientSession', 'linked-booking-token')
    const page = makePage(registeredBooking)
    const start = pending.length
    registeredBooking.onLoad.call(page, { mode: 'new', planId })
    assert.equal(pending[start].url, `${apiBaseUrl}/patient/clinics`)
    assert.equal(pending[start + 1].url, `${apiBaseUrl}/patient/booking-status`)
    assert.equal(pending[start + 2].url, `${apiBaseUrl}/patient/profile`)
    assert.equal(pending[start + 3].url, `${apiBaseUrl}/patient/treatment-types`)
    assert.equal(pending[start + 4].url, `${apiBaseUrl}/patient/followup-plans/${planId}`)
    respond(start, 200, [])
    respond(start + 1, 200, { restricted: false })
    respond(start + 2, 200, { patient: { patient_id: '7001' } })
    respond(start + 3, 200, [])
    respond(start + 4, 200, makePlan(planId, status, false))
    await new Promise((resolve) => setImmediate(resolve))
    return page
  }
  const linkedPlanPage = await createLinkedBookingPage('701')
  assert.equal(linkedPlanPage.data.followupPlan.followup_plan_id, '701')
  assert.equal(linkedPlanPage.data.canCreateLinked, true)
  assert.equal(linkedPlanPage.data.requestedTreatments, '')
  const bookingSlot = {
    clinicId: '8101', dentistId: '8102', scheduleId: '8103',
    scheduledStart: '2026-10-23T02:00:00Z', scheduledEnd: '2026-10-23T02:30:00Z'
  }
  Object.assign(linkedPlanPage.data, { requestedTreatments: '本次患者自填需求', specialNeeds: '', selectedSlotKey: 'slot-1' })
  const linkedPostIndex = pending.length
  registeredBooking.submitBooking.call(linkedPlanPage, bookingSlot)
  const linkedPost = pending[linkedPostIndex]
  assert.equal(linkedPost.url, `${apiBaseUrl}/patient/followup-plans/701/appointments`)
  assert.equal(linkedPost.method, 'POST')
  assert.deepEqual(Object.keys(linkedPost.data).sort(), ['appointmentNo', 'clinicId', 'dentistId', 'requestedTreatments', 'scheduleId', 'scheduledEnd', 'scheduledStart'].sort())
  assert.equal(Object.hasOwn(linkedPost.data, 'planId'), false)
  assert.equal(Object.hasOwn(linkedPost.data, 'patientId'), false)
  assert.equal(Object.hasOwn(linkedPost.data, 'bookingChannel'), false)
  linkedPost.fail()
  await new Promise((resolve) => setImmediate(resolve))
  const savedLinkedOperation = operations.readPending()
  assert.equal(savedLinkedOperation.path, '/patient/followup-plans/701/appointments')
  const linkedRetryIndex = pending.length
  registeredBooking.retryPendingOperation.call(linkedPlanPage)
  assert.equal(pending[linkedRetryIndex].url, linkedPost.url)
  assert.deepEqual(pending[linkedRetryIndex].data, linkedPost.data)
  assert.equal(pending[linkedRetryIndex].data.appointmentNo, savedLinkedOperation.payload.appointmentNo)
  const linkedRedirectsBefore = redirects.length
  respond(linkedRetryIndex, 200, { replayed: true, appointment: { appointment_id: '9201', followup_plan_id: '701' } })
  await new Promise((resolve) => setImmediate(resolve))
  assert.equal(redirects.length, linkedRedirectsBefore + 1)
  assert.equal(redirects.at(-1).url, '/pages/appointment/index?id=9201')
  assert.equal(operations.readPending(), null)

  const terminalLinkedPage = await createLinkedBookingPage('702', '已取消')
  assert.equal(terminalLinkedPage.data.followupPlan.status, '已取消')
  assert.equal(terminalLinkedPage.data.canCreateLinked, false)
  Object.assign(terminalLinkedPage.data, { requestedTreatments: 'should not post', selectedSlotKey: 'slot-1' })
  const beforeTerminalSubmit = pending.length
  registeredBooking.startSubmit.call(terminalLinkedPage)
  assert.equal(pending.length, beforeTerminalSubmit)

  wx.setStorageSync('hean-rebuild-20261026:patientSession', 'forged-param-token')
  const forgedPlanPage = makePage(registeredBooking)
  const beforeForgedPlan = pending.length
  registeredBooking.onLoad.call(forgedPlanPage, { mode: 'new', planId: '703', patientId: '7002' })
  assert.equal(pending.length, beforeForgedPlan)
  assert.match(forgedPlanPage.data.error, /入口参数无效/)
  assert.equal(forgedPlanPage.data.entryInvalid, true)
  registeredBooking.onShow.call(forgedPlanPage)
  assert.equal(forgedPlanPage.data.entryInvalid, true)
  assert.equal(pending.length, beforeForgedPlan)
  const zeroPlanPage = makePage(registeredBooking)
  registeredBooking.onLoad.call(zeroPlanPage, { mode: 'new', planId: '0' })
  assert.equal(zeroPlanPage.data.entryInvalid, true)
  assert.equal(pending.length, beforeForgedPlan)

  wx.setStorageSync('hean-rebuild-20261026:patientSession', 'forbidden-linked-plan-token')
  const forbiddenLinkedPage = makePage(registeredBooking)
  const forbiddenLinkedIndex = pending.length
  registeredBooking.onLoad.call(forbiddenLinkedPage, { mode: 'new', planId: '708' })
  respond(forbiddenLinkedIndex, 200, [])
  respond(forbiddenLinkedIndex + 1, 200, { restricted: false })
  respond(forbiddenLinkedIndex + 2, 200, { patient: { patient_id: '7001' } })
  respond(forbiddenLinkedIndex + 3, 200, [])
  respond(forbiddenLinkedIndex + 4, 403, { code: 'FORBIDDEN', message: 'fixture plan is not owned by this account' })
  await new Promise((resolve) => setImmediate(resolve))
  assert.match(forbiddenLinkedPage.data.error, /not owned by this account/)
  const beforeForbiddenSubmit = pending.length
  Object.assign(forbiddenLinkedPage.data, { selectedSlotKey: 'slot-1', requestedTreatments: 'must not submit' })
  registeredBooking.startSubmit.call(forbiddenLinkedPage)
  assert.equal(pending.length, beforeForbiddenSubmit)

  const switchedLinkedPage = await createLinkedBookingPage('704')
  Object.assign(switchedLinkedPage.data, { requestedTreatments: 'private before switch', specialNeeds: 'private note' })
  wx.setStorageSync('hean-rebuild-20261026:patientSession', 'another-patient-token')
  const beforeLinkedAccountSwitch = pending.length
  registeredBooking.onShow.call(switchedLinkedPage)
  assert.equal(switchedLinkedPage.data.followupPlan, null)
  assert.equal(switchedLinkedPage.data.followupPlanId, '')
  assert.equal(switchedLinkedPage.data.requestedTreatments, '')
  assert.equal(switchedLinkedPage.data.specialNeeds, '')
  assert.equal(switchedLinkedPage._followupPlanId, '')
  assert.equal(pending.length, beforeLinkedAccountSwitch)
  switchedLinkedPage.data.selectedSlotKey = 'slot-1'
  switchedLinkedPage.data.requestedTreatments = 'should remain blocked'
  const beforeSwitchedSubmit = pending.length
  registeredBooking.startSubmit.call(switchedLinkedPage)
  assert.equal(pending.length, beforeSwitchedSubmit)

  const ordinaryBookingPage = makePage(registeredBooking)
  const ordinaryLoadIndex = pending.length
  registeredBooking.onLoad.call(ordinaryBookingPage, { mode: 'new' })
  respond(ordinaryLoadIndex, 200, [])
  respond(ordinaryLoadIndex + 1, 200, { restricted: false })
  respond(ordinaryLoadIndex + 2, 200, { patient: { patient_id: '7001' } })
  respond(ordinaryLoadIndex + 3, 200, [])
  await new Promise((resolve) => setImmediate(resolve))
  assert.equal(ordinaryBookingPage._followupPlanId, '')
  assert.equal(ordinaryBookingPage.data.followupPlan, null)
  ordinaryBookingPage.data.selectedSlotKey = 'slot-1'
  ordinaryBookingPage.data.requestedTreatments = 'ordinary booking'
  const ordinaryPostIndex = pending.length
  registeredBooking.submitBooking.call(ordinaryBookingPage, bookingSlot)
  assert.equal(pending[ordinaryPostIndex].url, `${apiBaseUrl}/patient/appointments`)
  assert.deepEqual(Object.keys(pending[ordinaryPostIndex].data).sort(), ['appointmentNo', 'clinicId', 'dentistId', 'requestedTreatments', 'scheduleId', 'scheduledEnd', 'scheduledStart'].sort())
  assert.equal(Object.hasOwn(pending[ordinaryPostIndex].data, 'planId'), false)
  assert.equal(Object.hasOwn(pending[ordinaryPostIndex].data, 'followupPlanId'), false)
  assert.equal(Object.hasOwn(pending[ordinaryPostIndex].data, 'patientId'), false)
  assert.equal(Object.hasOwn(pending[ordinaryPostIndex].data, 'bookingChannel'), false)
  respond(ordinaryPostIndex, 200, { appointment: { appointment_id: '9202' } })
  await new Promise((resolve) => setImmediate(resolve))
  assert.equal(operations.readPending(), null)

  const reschedulePage = makePage(registeredBooking)
  reschedulePage._sessionToken = api.sessionToken()
  reschedulePage._patientId = '7001'
  reschedulePage.data.mode = 'reschedule'
  reschedulePage.data.appointmentId = '9203'
  reschedulePage.data.canRescheduleSource = true
  reschedulePage.data.sourceAppointment = { row_version: 4 }
  reschedulePage.data.reason = 'fixture reason'
  reschedulePage.data.requestedTreatments = 'reschedule request'
  const reschedulePostIndex = pending.length
  registeredBooking.submitReschedule.call(reschedulePage, bookingSlot)
  assert.equal(pending[reschedulePostIndex].url, `${apiBaseUrl}/patient/appointments/9203/reschedule`)
  respond(reschedulePostIndex, 200, { appointment: { appointment_id: '9203' } })
  await new Promise((resolve) => setImmediate(resolve))

  const mismatchLinkedPage = await createLinkedBookingPage('705')
  Object.assign(mismatchLinkedPage.data, { requestedTreatments: 'mismatch fixture', selectedSlotKey: 'slot-1' })
  const mismatchPostIndex = pending.length
  registeredBooking.submitBooking.call(mismatchLinkedPage, bookingSlot)
  respond(mismatchPostIndex, 200, { appointment: { appointment_id: '9204', followup_plan_id: '706' } })
  await new Promise((resolve) => setImmediate(resolve))
  assert.equal(mismatchLinkedPage.data.linkedResultNeedsReview, true)
  const redirectsAfterMismatch = redirects.length
  assert.equal(redirects.length, redirectsAfterMismatch)
  assert.equal(operations.readPending(), null)
  assert.equal(followupPlans.hasLinkReview('7001', '705'), true)
  assert.equal(followupPlans.hasLinkReview('7002', '705'), false)
  const beforeMismatchResubmit = pending.length
  registeredBooking.startSubmit.call(mismatchLinkedPage)
  assert.equal(pending.length, beforeMismatchResubmit)
  const reopenedReviewPage = await createLinkedBookingPage('705')
  assert.equal(reopenedReviewPage.data.linkedResultNeedsReview, true)
  assert.equal(reopenedReviewPage.data.canCreateLinked, false)
  const beforeReopenedReviewSubmit = pending.length
  registeredBooking.startSubmit.call(reopenedReviewPage)
  assert.equal(pending.length, beforeReopenedReviewSubmit)

  wx.setStorageSync('hean-rebuild-20261026:patientSession', 'home-linked-retry-token')
  const homeLinkedOperation = operations.makeOperation('复诊预约新增', 'POST', '/patient/followup-plans/707/appointments', { requestedTreatments: 'fixture' }, true, '7001')
  wx.setStorageSync('hean-rebuild-20261026:patientPendingWrite', homeLinkedOperation)
  const homeRetryPage = Object.create(registeredHome)
  homeRetryPage.data = { profile: { patient: { patient_id: '7001' } }, busy: false, error: '', pendingOperation: null }
  homeRetryPage.setData = function (next) { Object.assign(this.data, next) }
  homeRetryPage.refreshData = () => { throw new Error('linked mismatch must not navigate into a compensating refresh') }
  const homeRetryIndex = pending.length
  registeredHome.retryPendingOperation.call(homeRetryPage)
  assert.equal(pending[homeRetryIndex].url, `${apiBaseUrl}/patient/followup-plans/707/appointments`)
  const redirectsBeforeHomeMismatch = redirects.length
  respond(homeRetryIndex, 200, { appointment: { appointment_id: '9205' } })
  await new Promise((resolve) => setImmediate(resolve))
  assert.match(homeRetryPage.data.error, /不要重复提交/)
  assert.equal(redirects.length, redirectsBeforeHomeMismatch)
  assert.equal(operations.readPending(), null)
  assert.equal(followupPlans.hasLinkReview('7001', '707'), true)

  process.stdout.write('wx.request fixture checks passed; no real API or WeChat UI was exercised.\n')
}

main().catch((error) => {
  process.stderr.write(`${error.stack || error.message}\n`)
  process.exitCode = 1
})

