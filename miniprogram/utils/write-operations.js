const api = require('./api')

const { storageNamespace } = require('../config/environment')
const STORAGE_KEY = `${storageNamespace}:patientPendingWrite`

function readPending() {
  return wx.getStorageSync(STORAGE_KEY) || null
}

function randomOperationNo() {
  return `MP-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
}

function makeOperation(kind, method, path, payload, needsAppointmentNo = false, patientId = '') {
  const operationNo = randomOperationNo()
  const requestPayload = needsAppointmentNo ? { ...payload, appointmentNo: operationNo } : { ...payload }
  return { operationNo, kind, patientId: String(patientId), method, path, payload: requestPayload, savedAt: new Date().toISOString() }
}

function clearIfSame(operation) {
  const current = readPending()
  if (current && current.operationNo === operation.operationNo) wx.removeStorageSync(STORAGE_KEY)
}

function execute(operation, retrying = false) {
  wx.setStorageSync(STORAGE_KEY, operation)
  return api.request(operation.path, { method: operation.method, data: operation.payload })
    .then((result) => {
      clearIfSame(operation)
      return result
    })
    .catch((error) => {
      const uncertain = !error.statusCode || error.statusCode >= 500 || error.code === 'IDEMPOTENCY_CONFLICT' || error.code === 'SESSION_CHANGED' || (retrying && [401, 403].includes(error.statusCode))
      if (!uncertain) clearIfSame(operation)
      error.writeOutcomeUncertain = uncertain
      throw error
    })
}

function start(operation) {
  if (readPending()) {
    const error = new Error('有一笔预约操作结果待核实。请先重试原请求，不要创建新操作。')
    error.code = 'PENDING_WRITE_EXISTS'
    throw error
  }
  return execute(operation)
}

function retryPending() {
  const operation = readPending()
  if (!operation) {
    const error = new Error('没有待重试的预约操作。')
    error.code = 'NO_PENDING_WRITE'
    throw error
  }
  return execute(operation, true)
}

module.exports = { readPending, makeOperation, start, retryPending }
