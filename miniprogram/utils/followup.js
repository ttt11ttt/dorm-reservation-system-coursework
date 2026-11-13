const STATUSES = ['待复诊', '已完成', '已取消']
const FILTERS = ['all', ...STATUSES]
const { storageNamespace } = require('../config/environment')
const LINK_REVIEW_KEY = `${storageNamespace}:patientFollowupLinkReview`
const MAX_ID = '9223372036854775807'

function isId(value) {
  return typeof value === 'string' && /^[1-9]\d*$/.test(value) &&
    (value.length < MAX_ID.length || (value.length === MAX_ID.length && value <= MAX_ID))
}

function isDate(value) {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false
  const [year, month, day] = value.split('-').map(Number)
  if (year < 1000 || month < 1 || month > 12 || day < 1) return false
  const leap = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0)
  const days = [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31]
  return day <= days[month - 1]
}

function isPlan(plan) {
  return !!plan && typeof plan === 'object' &&
    isId(plan.followup_plan_id) && isId(plan.patient_id) && isId(plan.source_appointment_id) &&
    isId(plan.dentist_id) && isId(plan.clinic_id) &&
    isDate(plan.suggested_date) && typeof plan.recommendation === 'string' &&
    STATUSES.includes(plan.status) && typeof plan.overdue === 'boolean'
}

function isPage(page) {
  return !!page && Array.isArray(page.items) && page.items.every(isPlan) &&
    isDate(page.asOfDate) && typeof page.hasMore === 'boolean' &&
    (page.hasMore
      ? isId(page.nextAfterId) && page.items.length > 0
      : page.nextAfterId === null)
}

function compareIds(left, right) {
  if (left.length !== right.length) return left.length < right.length ? -1 : 1
  return left === right ? 0 : left < right ? -1 : 1
}

function hasValidNextCursor(page, currentCursor) {
  return !page.hasMore || compareIds(page.nextAfterId, currentCursor || '0') > 0
}

function listUrl(status, afterId) {
  let url = `/patient/followup-plans?status=${encodeURIComponent(status)}&limit=30`
  if (afterId) url += `&afterId=${encodeURIComponent(afterId)}`
  return url
}

function linkReviewKey(patientId, planId) {
  return `${patientId}:${planId}`
}

function hasLinkReview(patientId, planId) {
  if (!isId(patientId) || !isId(planId)) return false
  const records = wx.getStorageSync(LINK_REVIEW_KEY) || {}
  const record = records[linkReviewKey(patientId, planId)]
  return !!record && record.patientId === patientId && record.planId === planId
}

function markLinkReview(patientId, planId, operationNo) {
  if (!isId(patientId) || !isId(planId)) return
  const records = wx.getStorageSync(LINK_REVIEW_KEY) || {}
  wx.setStorageSync(LINK_REVIEW_KEY, {
    ...records,
    [linkReviewKey(patientId, planId)]: { patientId, planId, operationNo: String(operationNo || ''), savedAt: new Date().toISOString() }
  })
}

module.exports = { STATUSES, FILTERS, isId, isDate, isPlan, isPage, compareIds, hasValidNextCursor, listUrl, hasLinkReview, markLinkReview }
