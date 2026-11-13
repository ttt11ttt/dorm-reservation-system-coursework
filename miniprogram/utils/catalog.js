const api = require('./api')
const id = (v) => typeof v === 'string' && /^[1-9]\d{0,18}$/.test(v) && (v.length < 19 || v <= '9223372036854775807')
function url(mode, values = {}) {
  return '/pages/catalog/index?' + Object.entries({ mode, ...values }).filter(([, v]) => v !== '' && v !== undefined).map(([k, v]) => `${k}=${encodeURIComponent(v)}`).join('&')
}
function get(path) { return api.request('/catalog' + path, { sessionToken: '', guardSession: false }) }
function items(response) {
  if (!response || !Array.isArray(response.items)) throw new Error('目录响应格式异常，请重试。')
  return response.items
}
function decorate(row) {
  if (!row || !id(row.dentistId) || typeof row.name !== 'string') throw new Error('医生资料格式异常。')
  return { ...row, initial: Array.from(row.name)[0] || '医', statusLabel: row.status === 'AVAILABLE' ? '有号' : row.status === 'FULL' ? '约满' : '暂无排班' }
}
function dates(first = new Date(), count = 14) {
  const day = new Date(first.getTime() + 8 * 3600000).toISOString().slice(0, 10)
  const start = Date.parse(day + 'T00:00:00+08:00')
  return Array.from({ length: count }, (_, n) => {
    const d = new Date(start + n * 86400000 + 8 * 3600000)
    const value = d.toISOString().slice(0, 10)
    return { date: value, short: value.slice(5), week: ['周日', '周一', '周二', '周三', '周四', '周五', '周六'][d.getUTCDay()] }
  })
}
function selection(options) {
  if (!options || !id(options.clinicId) || !id(options.dentistId) || !id(options.scheduleId)) return null
  let startText, endText
  try { startText = decodeURIComponent(options.start); endText = decodeURIComponent(options.end) } catch { return null }
  const start = Date.parse(startText), end = Date.parse(endText)
  if (!Number.isFinite(start) || end - start !== 1800000) return null
  return { clinicId: options.clinicId, dentistId: options.dentistId, scheduleId: options.scheduleId,
    start: new Date(start).toISOString(), end: new Date(end).toISOString(), date: new Date(start + 8 * 3600000).toISOString().slice(0, 10) }
}
function matchingSlot(choice, candidates) {
  if (!choice) return null
  return candidates.find((s) => s.clinicId === choice.clinicId && s.dentistId === choice.dentistId && s.scheduleId === choice.scheduleId &&
    Date.parse(s.scheduledStart) === Date.parse(choice.start) && Date.parse(s.scheduledEnd) === Date.parse(choice.end)) || null
}
function bookingUrl(kind, choice) {
  const path = kind === 'GUEST' ? '/pages/guest-booking/index' : '/pages/booking/index'
  return path + '?' + Object.entries({ mode: 'new', clinicId: choice.clinicId, dentistId: choice.dentistId, scheduleId: choice.scheduleId, start: choice.start, end: choice.end }).map(([k, v]) => `${k}=${encodeURIComponent(v)}`).join('&')
}
module.exports = { id, url, get, items, decorate, dates, selection, matchingSlot, bookingUrl }
