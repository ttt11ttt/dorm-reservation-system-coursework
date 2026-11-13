const assert = require('node:assert/strict')
const calls = []
global.wx = { getStorageSync: () => '', navigateTo: (value) => calls.push(value) }
global.getApp = () => ({ globalData: {} })
let definition
global.Page = (value) => { definition = value }
require('../pages/catalog/index')
const catalogDefinition = definition
const catalog = require('../utils/catalog')
const api = require('../utils/api')
const guest = require('../utils/guest-operations')
function page(source = catalogDefinition) {
  const p = Object.create(source)
  p.data = JSON.parse(JSON.stringify(source.data))
  p.setData = (values) => Object.assign(p.data, values)
  p._alive = true; p._generation = 0; p._slotGeneration = 0; p._cursor = ''
  return p
}
function deferred() { let resolve; const promise = new Promise((r) => { resolve = r }); return { promise, resolve } }
async function main() {
  const choice = catalog.selection({ clinicId: '12', dentistId: '34', scheduleId: '56', start: '2026-10-27T09:00:00+08:00', end: '2026-10-27T09:30:00+08:00' })
  assert.equal(choice.start, '2026-10-27T01:00:00.000Z')
  // Real mini-program onLoad receives escaped query values, unlike URLSearchParams.get().
  const escapedOptions = Object.fromEntries(catalog.bookingUrl('GUEST', choice).split('?')[1].split('&').map(pair => pair.split('=')))
  assert.deepEqual(catalog.selection(escapedOptions), choice, 'encoded navigation must preserve doctor, schedule and time')
  assert.equal(catalog.selection({ ...escapedOptions, start: '%malformed' }), null)
  assert.equal(catalog.selection({ ...choice, dentistId: 'any' }), null)
  assert.equal(catalog.selection({ ...choice, end: '2026-10-27T02:00:00Z' }), null)
  assert.equal(catalog.matchingSlot(choice, [{ clinicId: '12', dentistId: '34', scheduleId: '56', scheduledStart: choice.start, scheduledEnd: choice.end }]).scheduleId, '56')
  assert.equal(catalog.matchingSlot(choice, [{ clinicId: '13', dentistId: '34', scheduleId: '56', scheduledStart: choice.start, scheduledEnd: choice.end }]), null)
  assert.equal(catalog.id('9223372036854775808'), false)
  assert.equal(catalog.dates(new Date('2026-10-25T18:00:00Z'), 2)[0].date, '2026-10-26')
  const old = deferred(), latest = deferred()
  const p = page(); let n = 0
  catalog.get = () => (++n === 1 ? old.promise : latest.promise)
  const first = p.load(false); p.data.q = 'new'; const second = p.load(false)
  latest.resolve({ items: [{ clinicId: '2', name: '新查询门店', address: '虚构演示地址' }], hasMore: false }); await second
  old.resolve({ items: [{ clinicId: '1', name: '旧查询门店', address: '虚构演示地址' }], hasMore: false }); await first
  assert.equal(p.data.rows[0].clinicId, '2', 'older public reads must not overwrite the newer query')
  catalog.get = async () => { throw Object.assign(new Error('服务处理失败'), { statusCode: 500 }) }
  await p.load(false)
  assert.equal(p.data.rows.length, 0); assert.equal(p.data.error, '服务处理失败', 'server errors must not look like an empty directory')
  const b = page(); b.data.mode = 'detail'
  b.data.periods = [{ slots: [{ available: true, choice }] }]
  const login = deferred(); api.ensureBookingSession = () => login.promise
  const pending = b.book({ currentTarget: { dataset: { period: 0, slot: 0 } } })
  b.onUnload(); login.resolve({ subjectKind: 'GUEST', subjectKey: 'G-77', token: 'fixture' }); await pending
  assert.equal(calls.length, 0, 'an unloaded page must not navigate after login completes')
  const c = page(); c.data.periods = [{ slots: [{ available: true, choice }] }]
  api.ensureBookingSession = async () => ({ subjectKind: 'GUEST', subjectKey: 'G-77', token: 'fixture' })
  guest.readPending = () => ({ reservationNo: 'original' })
  await c.book({ currentTarget: { dataset: { period: 0, slot: 0 } } })
  assert.equal(calls.length, 0); assert.match(c.data.error, /原预约/)
  guest.readPending = () => null
  await c.book({ currentTarget: { dataset: { period: 0, slot: 0 } } })
  assert.match(calls[0].url, /^\/pages\/guest-booking\/index\?/)
  assert.equal(new URLSearchParams(calls[0].url.split('?')[1]).get('scheduleId'), '56')
  const incoming = catalog.selection({ clinicId: '12', dentistId: '34', scheduleId: '56', start: '2030-10-27T09:00:00+08:00', end: '2030-10-27T09:30:00+08:00' })
  const availability = { asOf: '2030-10-26T00:00:00Z', windows: [{ clinic_id: '12', dentist_id: '34', schedule_id: '56', start_at: incoming.start, end_at: incoming.end }], occupied: [], patientOccupied: [] }
  require('../pages/guest-booking/index')
  const guestPage = page(definition)
  guestPage._availability = availability; guestPage._catalogChoice = incoming
  Object.assign(guestPage.data, { selectedClinicId: '12', selectedDentistId: '34', selectedDate: incoming.date, dentists: [{ dentist_id: '34', name: '演示医生' }] })
  guestPage.recomputeCandidates()
  assert.equal(guestPage.data.selectedSlotKey, guestPage.data.candidates[0].key)
  assert.equal(guestPage.data.selectionLocked, true, 'exact incoming appointment skips repeated selection')
  guestPage.changeSelection()
  assert.equal(guestPage.data.selectionLocked, false, 'explicit change must allow selecting another arrangement')
  require('../pages/booking/index')
  const patientPage = page(definition)
  patientPage._availability = availability; patientPage._catalogChoice = incoming
  Object.assign(patientPage.data, { selectedDuration: '30', selectedClinicId: '12', selectedDentistId: '34', selectedDate: incoming.date, dentists: [{ dentist_id: '34', name: '演示医生' }] })
  patientPage.recomputeCandidates()
  assert.equal(patientPage.data.selectedSlotKey, patientPage.data.candidates[0].key)
  assert.equal(patientPage.data.selectionLocked, true)
  guestPage._catalogChoice = incoming
  guestPage._availability.occupied = [{ dentist_id: '34', scheduled_start: incoming.start, scheduled_end: incoming.end }]
  guestPage.recomputeCandidates()
  assert.equal(guestPage.data.selectedSlotKey, '', 'a newly occupied selection must not be carried into submission')
  assert.match(guestPage.data.error, /时段已变化/)
  console.log('Catalog fixtures passed: Shanghai dates, exact slot handoff, out-of-order reads, server errors, unloaded login and pending-operation guards. No network/DB/UI.')
}
main().catch((e) => { console.error(e); process.exitCode = 1 })
