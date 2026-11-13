const { apiBaseUrl, storageNamespace } = require('../config/environment')
const SESSION_KEY = `${storageNamespace}:patientSession`
const GUEST_SESSION_KEY = `${storageNamespace}:guestSession`
const SUBJECT_KIND_KEY = `${storageNamespace}:bookingSubjectKind`
const SUBJECT_KEY_KEY = `${storageNamespace}:bookingSubjectKey`
const SESSION_EXPIRY_KEY = `${storageNamespace}:bookingSessionExpiresAt`
let authRevision = 0
let loginPromise = null

function sessionKind() {
  const kind = wx.getStorageSync(SUBJECT_KIND_KEY)
  if (kind === 'GUEST' || kind === 'PATIENT') return kind
  return wx.getStorageSync(SESSION_KEY) ? 'PATIENT' : ''
}

function sessionToken() {
  return sessionKind() === 'GUEST'
    ? (wx.getStorageSync(GUEST_SESSION_KEY) || '')
    : (wx.getStorageSync(SESSION_KEY) || '')
}

function sessionSubjectKey() {
  return wx.getStorageSync(SUBJECT_KEY_KEY) || ''
}

function sessionInfo() {
  return { subjectKind: sessionKind(), subjectKey: sessionSubjectKey(), token: sessionToken(), expiresAt: wx.getStorageSync(SESSION_EXPIRY_KEY) || '' }
}

function clearSession() {
  authRevision += 1
  wx.removeStorageSync(SESSION_KEY)
  wx.removeStorageSync(GUEST_SESSION_KEY)
  wx.removeStorageSync(SUBJECT_KIND_KEY)
  wx.removeStorageSync(SUBJECT_KEY_KEY)
  wx.removeStorageSync(SESSION_EXPIRY_KEY)
  const app = getApp()
  if (app && app.globalData) app.globalData.profile = null
}

function installBookingSession(session) {
  if (!session || !['PATIENT', 'GUEST'].includes(session.subjectKind) || typeof session.subjectKey !== 'string' || !session.subjectKey ||
    typeof session.token !== 'string' || !session.token || session.tokenType !== 'Bearer' || typeof session.expiresAt !== 'string') {
    throw new Error('预约会话响应格式异常，请稍后重试。')
  }
  wx.removeStorageSync(SESSION_KEY)
  wx.removeStorageSync(GUEST_SESSION_KEY)
  wx.setStorageSync(session.subjectKind === 'GUEST' ? GUEST_SESSION_KEY : SESSION_KEY, session.token)
  wx.setStorageSync(SUBJECT_KIND_KEY, session.subjectKind)
  wx.setStorageSync(SUBJECT_KEY_KEY, session.subjectKey)
  wx.setStorageSync(SESSION_EXPIRY_KEY, session.expiresAt)
  authRevision += 1
  return sessionInfo()
}

function request(path, options = {}) {
  const token = Object.prototype.hasOwnProperty.call(options, 'sessionToken')
    ? options.sessionToken
    : sessionToken()
  const activeKind = sessionKind()
  if ((activeKind === 'GUEST' && (path.startsWith('/patient/') || path === '/sessions/current')) ||
    (activeKind === 'PATIENT' && (path.startsWith('/guest/') || path === '/guest/sessions/current'))) {
    const error = new Error('当前预约会话不能访问另一类账号的服务。')
    error.code = 'SUBJECT_ROUTE_FORBIDDEN'
    return Promise.reject(error)
  }
  const revision = authRevision
  const guardSession = options.guardSession !== false
  const header = { 'content-type': 'application/json' }
  if (token) header.Authorization = `Bearer ${token}`

  return new Promise((resolve, reject) => {
    wx.request({
      url: `${apiBaseUrl}${path}`,
      method: options.method || 'GET',
      data: options.data,
      header,
      success(response) {
        const body = response.data || {}
        if (guardSession && (revision !== authRevision || token !== sessionToken())) {
          const error = new Error('会话已切换，请重新读取资料。')
          error.code = 'SESSION_CHANGED'
          reject(error)
          return
        }
        if (response.statusCode >= 200 && response.statusCode < 300) {
          resolve(body)
          return
        }
        if (response.statusCode === 401 && revision === authRevision && token === sessionToken()) clearSession()
        const error = new Error(body.message || `请求失败（${response.statusCode}）`)
        error.statusCode = response.statusCode
        error.code = body.code || 'REQUEST_FAILED'
        error.requestId = body.requestId || ''
        reject(error)
      },
      fail() {
        reject(new Error('暂时无法连接诊所服务，请检查网络后重试。'))
      }
    })
  })
}

function bookingLogin() {
  if (loginPromise) return loginPromise
  clearSession()
  const attemptRevision = authRevision
  const changed = () => Object.assign(new Error('登录请求已失效，请重试。'), { code: 'SESSION_CHANGED' })
  loginPromise = new Promise((resolve, reject) => {
    wx.login({
      success(result) {
        if (attemptRevision !== authRevision) { reject(changed()); return }
        if (!result || typeof result.code !== 'string' || !result.code.trim()) {
          reject(new Error('微信未返回登录凭证，请重试。')); return
        }
        resolve(request('/booking/wechat-sessions', {
          method: 'POST', data: { code: result.code }, sessionToken: '', guardSession: false
        }))
      },
      fail() { reject(new Error('微信登录未完成，请重试。')) }
    })
  }).then((session) => {
    if (attemptRevision !== authRevision) throw changed()
    if (!session || !['PATIENT', 'GUEST'].includes(session.subjectKind) || typeof session.subjectKey !== 'string' || !session.subjectKey ||
      typeof session.token !== 'string' || !session.token || session.tokenType !== 'Bearer' || typeof session.expiresAt !== 'string') {
      throw new Error('登录响应异常，请重试。')
    }
    const identityPath = session.subjectKind === 'PATIENT' ? '/patient/profile' : '/guest/booking-status'
    return request(identityPath, { sessionToken: session.token, guardSession: false }).then((profile) => {
      if (attemptRevision !== authRevision) throw changed()
      installBookingSession(session)
      if (session.subjectKind === 'PATIENT') {
        const app = getApp()
        if (app && app.globalData) app.globalData.profile = profile
      }
      return { ...sessionInfo(), profile: session.subjectKind === 'PATIENT' ? profile : null }
    })
  }).finally(() => { loginPromise = null })
  return loginPromise
}

function ensureBookingSession() {
  if (loginPromise) return loginPromise
  if (sessionToken()) return Promise.resolve(sessionInfo())
  return bookingLogin()
}

function wechatLogin() {
  return bookingLogin().then((session) => session.subjectKind === 'PATIENT'
    ? session.profile
    : Promise.reject(Object.assign(new Error('访客预约请使用访客预约页面。'), { code: 'GUEST_SESSION' })))
}

function logout() {
  const token = sessionToken()
  const path = sessionKind() === 'GUEST' ? '/guest/sessions/current' : '/sessions/current'
  const pending = token ? request(path, { method: 'DELETE', sessionToken: token, guardSession: false }) : Promise.resolve()
  clearSession()
  return pending
}

module.exports = { request, wechatLogin, bookingLogin, ensureBookingSession, logout, clearSession, sessionToken, sessionKind, sessionSubjectKey, sessionInfo }
