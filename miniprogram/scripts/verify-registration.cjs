const assert = require('node:assert/strict')
const fs = require('node:fs')
let token = ''
let modal
let pullStops = 0
const navigations = []
const scrolls = []
global.getApp = () => ({ globalData: {} })
global.wx = {
  getStorageSync: (key) => key.endsWith(':patientSession') ? token : '',
  removeStorageSync() {},
  showModal: (options) => { modal = options },
  stopPullDownRefresh: () => { pullStops += 1 },
  navigateTo: (options) => navigations.push(options),
  pageScrollTo: (options) => scrolls.push(options),
  request: () => { throw new Error('No real request allowed in registration fixture') }
}
let definition
global.Page = (value) => { definition = value }
require('../pages/home/index')
const homeDefinition = definition
require('../pages/booking/index')
const bookingDefinition = definition
function page(source) {
  const instance = Object.create(source)
  instance.data = JSON.parse(JSON.stringify(source.data))
  instance.setData = function (value) { Object.assign(this.data, value) }
  return instance
}
async function main() {
const api = require('../utils/api')
api.ensureBookingSession = async () => ({ subjectKind: 'GUEST', subjectKey: 'G-812' })
const home = page(homeDefinition)
home.onLoad()
home.openCatalogClinics()
assert.equal(navigations.at(-1).url, '/pages/catalog/index?mode=clinics')
home.openCatalogExperts()
assert.equal(navigations.at(-1).url, '/pages/catalog/index?mode=experts')
home.openRecordsPage()
assert.equal(navigations.at(-1).url, '/pages/records/index')
// This fixture checks navigation intent; the auth fixture exercises the real login promise.
home.login = function () { this.setData({ needsIdentity: true }); return Promise.resolve() }
assert.equal(home.data.needsIdentity, false)
home.onShow()
home.onPullDownRefresh()
assert.equal(pullStops, 1)
assert.equal(home.data.profile, null)
home.openDentistBooking()
assert.equal(home._loginIntent, 'experts')
assert.equal(home.data.needsIdentity, true)
home.closeIdentity()
home.viewMine()
assert.equal(home._loginIntent, 'mine')
home.closeIdentity()
home.openAppointmentRecords()
assert.equal(home._loginIntent, 'appointments')
home.closeIdentity()
home.data.profile = { patient: {} }
home.scrollToAppointments()
assert.equal(scrolls.at(-1).selector, '.appointments-card')
home.data.profile = null
home.goHome()
assert.equal(home.data.activeTab, 'home')
assert.equal(scrolls.at(-1).scrollTop, 0)
await home.bookNew()
assert.equal(home.data.needsIdentity, false)
assert.equal(navigations.at(-1).url, '/pages/guest-booking/index')
assert.equal(typeof home.requestBinding, 'function')
assert.equal(Object.prototype.hasOwnProperty.call(home.data, 'bindingCode'), false)
home.showError({ code: 'PATIENT_BINDING_REQUIRED' })
assert.equal(home.data.bindingRequired, undefined)
assert.ok(!home.data.error.includes('绑定'))
home.closeIdentity()

const homeWxml = fs.readFileSync(require.resolve('../pages/home/index.wxml'), 'utf8')
assert.equal((homeWxml.match(/class="service-item"/g) || []).length, 3)
for (const label of ['门诊预约', '预约记录', '专家信息']) assert.ok(homeWxml.includes(label))
for (const removedLabel of ['门诊介绍', '在线客服', '一键拨号', '一键导航', '分享有礼', '积分商城', '医院概况']) {
  assert.ok(!homeWxml.includes(removedLabel), `Removed homepage entry remains: ${removedLabel}`)
}
assert.ok(!homeWxml.includes('overview-mask'))
assert.equal(home.data.bindingRequired, undefined)
assert.equal(home.data.needsIdentity, false)
assert.equal(home._loginIntent, '')
home.openFollowups()
assert.equal(home._loginIntent, 'followups')
home.closeIdentity()

const { slotDate } = require('../utils/registration')
assert.equal(slotDate('2035-01-01T16:30:00Z'), '2035-01-02')
assert.equal(slotDate('invalid'), '')
token = 'fixture-session'
const booking = page(bookingDefinition)
booking._alive = true
booking._sessionToken = token
booking._requestGeneration = 1
booking._resourceGeneration = 1
booking._availability = {
  asOf: '2035-01-01T00:00:00Z', occupied: [], patientOccupied: [],
  windows: [
    { schedule_id: '11', dentist_id: '21', clinic_id: '1', start_at: '2035-01-01T01:00:00Z', end_at: '2035-01-01T02:00:00Z' },
    { schedule_id: '12', dentist_id: '22', clinic_id: '1', start_at: '2035-01-01T16:30:00Z', end_at: '2035-01-01T17:30:00Z' }
  ]
}
Object.assign(booking.data, {
  selectedClinicId: '1', selectedClinicName: 'Fixture clinic', selectedDuration: '30',
  dentists: [{ dentist_id: '21', name: 'Fixture A' }, { dentist_id: '22', name: 'Fixture B' }],
  dentistOptions: [{ dentist_id: '', name: '不限医生' }, { dentist_id: '21', name: 'Fixture A' }, { dentist_id: '22', name: 'Fixture B' }],
  requestedTreatments: 'Fixture request', bookingStatus: { restricted: false }
})
booking.recomputeCandidates()
assert.equal(booking.data.dateOptions.length, 2)
assert.equal(booking.data.selectedDate, '2035-01-01')
assert.equal(booking.data.candidates.length, 2)
assert.equal(booking.data.candidates[0].dentistName, 'Fixture A')
assert.equal(booking.currentCandidates(true).length, 4)
booking.onDateChange({ detail: { value: 1 } })
assert.equal(booking.data.selectedDate, '2035-01-02')
assert.equal(booking.data.candidates[0].dentistId, '22')
booking.onDentistChange({ detail: { value: 1 } })
assert.equal(booking.data.selectedDentistId, '21')
assert.equal(booking.data.dentistIndex, 0)
assert.equal(booking.data.selectedDate, '2035-01-01')
booking.onDentistChange({ detail: { value: 0 } })
assert.equal(booking.data.selectedDentistId, '')
booking.selectSlot({ currentTarget: { dataset: { index: 0 } } })
assert.equal(booking.data.selectedSlotDentist, 'Fixture A')
let submits = 0
booking.submitBooking = () => { submits += 1 }
booking.startSubmit()
assert.equal(submits, 0)
assert.equal(booking.data.confirming, true)
modal.success({ confirm: false }); modal.complete()
assert.equal(submits, 0)
booking.startSubmit()
token = 'different-patient-session'
modal.success({ confirm: true }); modal.complete()
assert.equal(submits, 0)
token = 'fixture-session'
booking.startSubmit()
booking.data.requestedTreatments = 'changed after confirmation opened'
modal.success({ confirm: true }); modal.complete()
assert.equal(submits, 0)
booking.startSubmit()
modal.success({ confirm: true }); modal.complete()
assert.equal(submits, 1)
console.log('Registration/home fixture passed: three homepage actions, expert login gate, unauthenticated pull-refresh guard, Shanghai dates, dentist filters, and booking confirmation/cancel/stale-session. No network or database.')
}
main().catch((error) => { console.error(error); process.exitCode = 1 })
