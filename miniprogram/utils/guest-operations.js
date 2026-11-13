const api = require('./api')
const { storageNamespace } = require('../config/environment')

const STORAGE_KEY = `${storageNamespace}:guestPendingWrite`

function storageKey(subjectKey = api.sessionSubjectKey()) {
  if (typeof subjectKey !== 'string' || !/^G-[1-9]\d*$/.test(subjectKey)) return ''
  return `${STORAGE_KEY}:${subjectKey}`
}

function readPending(subjectKey = api.sessionSubjectKey()) {
  const key = storageKey(subjectKey)
  return key ? (wx.getStorageSync(key) || null) : null
}

function reservationNumber() {
  return new Promise((resolve, reject) => {
    if (typeof wx.getRandomValues !== 'function' || typeof wx.arrayBufferToBase64 !== 'function') {
      reject(new Error('当前微信基础库不支持安全预约号生成，请更新微信后重试。'))
      return
    }
    wx.getRandomValues({
      length: 18,
      success(result) {
        if (!result || !(result.randomValues instanceof ArrayBuffer) || result.randomValues.byteLength !== 18) {
          reject(new Error('安全预约号生成失败，请重试。'))
          return
        }
        const value = wx.arrayBufferToBase64(result.randomValues).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '')
        if (value.length !== 24 || !/^[A-Za-z0-9_-]{24}$/.test(value)) {
          reject(new Error('安全预约号生成失败，请重试。'))
          return
        }
        resolve(`G-${value}`)
      },
      fail() { reject(new Error('安全预约号生成失败，请重试。')) }
    })
  })
}

function clearIfSame(operation) {
  const key = storageKey(operation.subjectKey)
  const current = key ? wx.getStorageSync(key) : null
  if (current && current.reservationNo === operation.reservationNo && current.subjectKey === operation.subjectKey) {
    wx.removeStorageSync(key)
  }
}

function uncertain(error, retrying) {
  return !error.statusCode || error.statusCode >= 500 || error.code === 'IDEMPOTENCY_CONFLICT' ||
    error.code === 'SESSION_CHANGED' || (retrying && [401, 403].includes(error.statusCode))
}

function assertCurrentSubject(operation) {
  const reschedule = operation && /^\/guest\/reservations\/[1-9]\d*\/reschedule$/.test(operation.path || '')
  const booking = reschedule ? operation.payload?.replacement : operation?.payload
  if (!operation || operation.subjectKind !== 'GUEST' || !/^G-[1-9]\d*$/.test(operation.subjectKey || '') ||
    operation.method !== 'POST' || (operation.path !== '/guest/reservations' && !reschedule) ||
    typeof operation.reservationNo !== 'string' || !/^G-[A-Za-z0-9_-]{22,30}$/.test(operation.reservationNo) ||
    !booking || booking.reservationNo !== operation.reservationNo ||
    api.sessionKind() !== operation.subjectKind || api.sessionSubjectKey() !== operation.subjectKey || !api.sessionToken()) {
    const error = new Error('此预约只能由创建它的同一微信预约主体恢复，当前主体不能重发。')
    error.code = 'SUBJECT_MISMATCH'
    throw error
  }
}

function execute(operation, retrying = false) {
  assertCurrentSubject(operation)
  wx.setStorageSync(storageKey(operation.subjectKey), operation)
  return api.request(operation.path, { method: operation.method, data: operation.payload })
    .then((response) => {
      const reservation = response && response.reservation
      if (!reservation || reservation.reservation_no !== operation.reservationNo || reservation.reservation_status !== '待到诊') {
        const error = new Error('服务端已收到请求，但返回内容不足以确认预约结果。请恢复原预约，不要再次创建。')
        error.code = 'UNKNOWN_RESERVATION_RESULT'
        error.writeOutcomeUncertain = true
        throw error
      }
      clearIfSame(operation)
      return reservation
    }).catch((error) => {
      if (error.code === 'UNKNOWN_RESERVATION_RESULT') throw error
      const isUncertain = uncertain(error, retrying)
      if (!isUncertain) clearIfSame(operation)
      error.writeOutcomeUncertain = isUncertain
      throw error
    })
}

function start(payload, original = null) {
  const subjectKind = api.sessionKind()
  const subjectKey = api.sessionSubjectKey()
  if (subjectKind !== 'GUEST' || !/^G-[1-9]\d*$/.test(subjectKey) || !api.sessionToken()) {
    const error = new Error('访客预约会话已失效，请重新登录后继续。')
    error.statusCode = 401
    throw error
  }
  if (readPending(subjectKey)) {
    const error = new Error('有一笔访客预约结果待核实，请先恢复原预约，不要创建新预约。')
    error.code = 'PENDING_WRITE_EXISTS'
    throw error
  }
  return reservationNumber().then((number) => {
    const replacement = { ...payload, reservationNo: number }
    return execute({
    subjectKind,
    subjectKey,
    method: 'POST',
    path: original ? `/guest/reservations/${original.guest_reservation_id}/reschedule` : '/guest/reservations',
    reservationNo: number,
    payload: original ? { expectedRowVersion: original.row_version, replacement } : replacement,
    savedAt: new Date().toISOString()
    })
  })
}

function recover() {
  const operation = readPending()
  if (!operation) return Promise.reject(Object.assign(new Error('没有待恢复的访客预约。'), { code: 'NO_PENDING_WRITE' }))
  try {
    assertCurrentSubject(operation)
  } catch (error) {
    return Promise.reject(error)
  }
  const path = `/guest/reservations/by-number/${encodeURIComponent(operation.reservationNo)}`
  return api.request(path).then((reservation) => {
    if (!reservation || reservation.reservation_no !== operation.reservationNo) {
      throw Object.assign(new Error('预约号查询未返回对应的本人记录，原请求仍保留。'), { code: 'UNKNOWN_RESERVATION_RESULT', writeOutcomeUncertain: true })
    }
    clearIfSame(operation)
    return reservation
  }).catch((error) => {
    if (error.statusCode !== 404) {
      if (error.code === 'UNKNOWN_RESERVATION_RESULT') error.writeOutcomeUncertain = true
      throw error
    }
    return execute(operation, true)
  })
}

module.exports = { readPending, reservationNumber, start, recover }
