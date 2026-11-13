import type { FulfillmentScope } from './fulfillment-core.js'
export type ExpenseSource = {
  expenseId: string; documentNo: string; lineNo: number; kind: '医疗用品' | '运营费'; clinicId: string; occurredOn: string; amount: string
  status: '待分摊' | '待确认' | '确认' | '作废'; adjustsExpenseId: string | null; reason: string | null; recordedBy: string
  recordedAt: string | null; confirmedBy: string | null; confirmedAt: string | null
}
export type ExpenseAllocation = { allocationId: string; dentistId: string; treatmentId: string | null; amount: string; allocationBasis: string }
export type ExpenseSnapshot = { source: ExpenseSource; allocations: ExpenseAllocation[]; mutationCount: string }
export type ExpenseEnvelope = { expense: ExpenseSource; allocations: ExpenseAllocation[]; snapshot: ExpenseSnapshot; replayed: boolean; quarterReport: string }
export function expenseMoneyCents(value: unknown, label?: string): bigint
export function makeExpenseCreationPayload(input: { expenseId: string; documentNo: string; lineNo: string | number; kind: string; occurredOn: string; amount: unknown; reason?: string | null }): { expenseId: string; documentNo: string; lineNo: number; kind: string; occurredOn: string; amount: string; reason: string | null }
export function makeExpenseAllocation(input: { allocationId: string; dentistId: string; treatmentId?: string | null; amount: string; allocationBasis: string }): ExpenseAllocation
export function expenseAllocationsMatchTotal(amount: string, rows: ExpenseAllocation[]): boolean
export function makeExpenseConfirmationPayload(snapshot: ExpenseSnapshot, rows: ExpenseAllocation[], reason: string): { expected: ExpenseSnapshot; allocations: ExpenseAllocation[]; reason: string }
export function canConfirmExpense(snapshot: ExpenseSnapshot | null): boolean
export function preserveExpenseSource(raw: unknown): ExpenseSource
export function preserveExpenseAllocation(raw: unknown, options?: { allowNegative?: boolean }): ExpenseAllocation
export function preserveExpenseSnapshot(raw: unknown): ExpenseSnapshot
export function parseExpenseResponse(raw: unknown): ExpenseEnvelope
export function assertExpenseResponse(clinicId: string, expenseId: string, actorId: string, raw: unknown, expected?: { creation?: Record<string, unknown> | null; confirmation?: Record<string, unknown> | null }): ExpenseEnvelope
export function canReadExpenses(identity: { kind?: string; grants?: Array<{ role_code: string; scope_kind: string; clinic_id: string | number | null }> } | null, clinicId: string | number): boolean
export function canWriteExpenses(identity: { kind?: string; grants?: Array<{ role_code: string; scope_kind: string; clinic_id: string | number | null }> } | null, clinicId: string | number): boolean
export function canOpenExpenseWorkspace(identity: { kind?: string; grants?: Array<{ role_code: string; scope_kind: string; clinic_id: string | number | null }> } | null): boolean
export function isChainScopeGrant(grant: { scope_kind?: string; clinic_id?: string | number | null } | null): boolean
export function isClinicScopeGrant(grant: { scope_kind?: string; clinic_id?: string | number | null } | null): boolean
export function isValidGrantScope(grant: { scope_kind?: string; clinic_id?: string | number | null } | null): boolean
export function grantMatchesClinicScope(grant: { scope_kind?: string; clinic_id?: string | number | null } | null, clinicId: string | number): boolean
export function expensePendingScope(accountId: string, clinicId: string, action: 'expense-create' | 'expense-confirm'): FulfillmentScope
export function expenseListEndpoint(clinicId: string, options?: { status?: string; afterId?: string | null; limit?: number }): string
export function assertExpenseList(clinicId: string, raw: unknown): { items: ExpenseSource[]; hasMore: boolean; nextAfterId: string | null }
