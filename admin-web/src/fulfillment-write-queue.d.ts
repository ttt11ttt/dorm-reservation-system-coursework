import type { FulfillmentScope, PendingWrite } from './fulfillment-core.js'
export type FulfillmentWriteResult = {
  kind: 'success' | 'rejected' | 'unknown' | 'preserved' | 'busy' | 'pending-exists' | 'storage-error' | 'missing'
  record?: PendingWrite
  records?: PendingWrite[]
  result?: Record<string, unknown>
  error?: unknown
  storageError?: unknown
}
export function createFulfillmentWriteQueue(storage: Storage | (() => Storage), dispatch: (record: PendingWrite) => Promise<Record<string, unknown>>): {
  submit(scope: FulfillmentScope, request: { endpoint: string; method?: string; payload: Record<string, unknown>; createdAt?: string; operationId?: string }): Promise<FulfillmentWriteResult>
  retry(scope: FulfillmentScope, record: PendingWrite): Promise<FulfillmentWriteResult>
}
export function createFollowupWriteQueue(storage: Storage | (() => Storage), dispatch: (record: PendingWrite) => Promise<Record<string, unknown>>): {
  submit(scope: FulfillmentScope, request: { endpoint: string; method?: string; payload: Record<string, unknown>; createdAt?: string; operationId?: string }): Promise<FulfillmentWriteResult>
  retry(scope: FulfillmentScope, record: PendingWrite): Promise<FulfillmentWriteResult>
}
export function createClinicalCompletionWriteQueue(storage: Storage | (() => Storage), dispatch: (record: PendingWrite) => Promise<Record<string, unknown>>): {
  submit(scope: FulfillmentScope, request: { endpoint: string; method?: string; payload: Record<string, unknown>; createdAt?: string; operationId?: string }): Promise<FulfillmentWriteResult>
  retry(scope: FulfillmentScope, record: PendingWrite): Promise<FulfillmentWriteResult>
}
export function createReminderWriteQueue(storage: Storage | (() => Storage), dispatch: (record: PendingWrite) => Promise<Record<string, unknown>>): {
  submit(scope: FulfillmentScope, request: { endpoint: string; method?: string; payload: Record<string, unknown>; createdAt?: string; operationId?: string }): Promise<FulfillmentWriteResult>
  retry(scope: FulfillmentScope, record: PendingWrite): Promise<FulfillmentWriteResult>
}
export function createExpenseWriteQueue(storage: Storage | (() => Storage), dispatch: (record: PendingWrite) => Promise<Record<string, unknown>>): {
  submit(scope: FulfillmentScope, request: { endpoint: string; method?: string; payload: Record<string, unknown>; createdAt?: string; operationId?: string }): Promise<FulfillmentWriteResult>
  retry(scope: FulfillmentScope, record: PendingWrite): Promise<FulfillmentWriteResult>
}
