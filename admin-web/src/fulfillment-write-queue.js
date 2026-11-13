import {
  loadScopedPendingWrites,
  markPendingWriteState,
  removePendingWrite,
  savePendingWrite,
} from './fulfillment-core.js'
import { followupScope } from './followup-core.js'

// This coordinator is the write path used by FulfillmentPanel and is injectable for in-memory request tests.
export function createFulfillmentWriteQueue(storageProvider, dispatch) {
  return createScopedWriteQueue(storageProvider, dispatch, {
    actions: ['reschedule', 'cancel', 'no-show', 'review'],
  })
}

export function createFollowupWriteQueue(storageProvider, dispatch) {
  return createScopedWriteQueue(storageProvider, dispatch, {
    namespace: 'adc.followup.pending',
    actions: ['followup-create', 'followup-update', 'followup-cancel', 'followup-contact', 'followup-appointment'],
  })
}

export function createClinicalCompletionWriteQueue(storageProvider, dispatch) {
  return createScopedWriteQueue(storageProvider, dispatch, {
    namespace: 'adc.clinical.completion.pending',
    actions: ['clinical-completion'],
  })
}

export function createReminderWriteQueue(storageProvider, dispatch) {
  return createScopedWriteQueue(storageProvider, dispatch, {
    namespace: 'adc.reminder.pending',
    actions: ['reminder-rule', 'reminder-rebuild', 'reminder-migration-preview', 'reminder-migration-commit'],
  })
}

export function createExpenseWriteQueue(storageProvider, dispatch) {
  return createScopedWriteQueue(storageProvider, dispatch, {
    namespace: 'adc.expense.pending',
    actions: ['expense-create', 'expense-confirm'],
  })
}

export { followupScope }

function createScopedWriteQueue(storageProvider, dispatch, { namespace = 'adc.fulfillment.pending', actions }) {
  let locked = false
  const storage = () => typeof storageProvider === 'function' ? storageProvider() : storageProvider
  const preparedScope = (scope) => ({ ...scope, namespace })

  async function run(record, previousOutcomeUnknown) {
    let attempted
    try {
      attempted = markPendingWriteState(storage(), record, 'attempting')
    } catch (error) {
      return { kind: 'storage-error', record, error }
    }

    let result
    try {
      result = await dispatch(attempted)
    } catch (error) {
      if (previousOutcomeUnknown) return { kind: 'preserved', record: attempted, error }
      if (isUnknownOutcome(error)) {
        try {
          const unknown = markPendingWriteState(storage(), attempted, 'unknown')
          return { kind: 'unknown', record: unknown, error }
        } catch (storageError) {
          // The persisted "attempting" state is itself treated as unresolved after reload.
          return { kind: 'preserved', record: attempted, error, storageError }
        }
      }
      try {
        removePendingWrite(storage(), attempted)
        return { kind: 'rejected', record: attempted, error }
      } catch (storageError) {
        return { kind: 'preserved', record: attempted, error, storageError }
      }
    }

    try {
      removePendingWrite(storage(), attempted)
      return { kind: 'success', record: attempted, result }
    } catch (error) {
      // The server confirmed success but local cleanup failed; retain the exact envelope for a safe replay.
      return { kind: 'preserved', record: attempted, result, error }
    }
  }

  async function submit(scope, request) {
    if (locked) return { kind: 'busy' }
    locked = true
    try {
      const queueScope = preparedScope(scope)
      const existing = loadScopedPendingWrites(storage(), queueScope, actions)
      if (existing.length) return { kind: 'pending-exists', records: existing }
      const record = savePendingWrite(storage(), queueScope, request)
      const verified = loadScopedPendingWrites(storage(), queueScope, actions).find((item) => item.key === record.key)
      if (!verified || !sameRequest(verified, record)) return { kind: 'storage-error', record }
      return await run(verified, false)
    } catch (error) {
      return { kind: 'storage-error', error }
    } finally {
      locked = false
    }
  }

  async function retry(scope, record) {
    if (locked) return { kind: 'busy' }
    locked = true
    try {
      const queueScope = preparedScope(scope)
      const current = loadScopedPendingWrites(storage(), queueScope, actions).find((item) => item.key === record.key)
      if (!current || !sameRequest(current, record)) return { kind: 'missing' }
      const previousOutcomeUnknown = current.state === 'unknown' || current.state === 'attempting'
      return await run(current, previousOutcomeUnknown)
    } catch (error) {
      return { kind: 'storage-error', record, error }
    } finally {
      locked = false
    }
  }

  return { submit, retry }
}

function sameRequest(left, right) {
  return left.key === right.key
    && left.accountId === right.accountId
    && String(left.sourceClinicId) === String(right.sourceClinicId)
    && String(left.appointmentId) === String(right.appointmentId)
    && left.action === right.action
    && left.endpoint === right.endpoint
    && left.method === right.method
    && JSON.stringify(left.payload) === JSON.stringify(right.payload)
}

function isUnknownOutcome(error) {
  const status = Number(error?.status)
  return !Number.isInteger(status) || status >= 500 || error?.code === 'IDEMPOTENCY_CONFLICT'
}
