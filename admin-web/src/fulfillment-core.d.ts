export type FulfillmentAction = 'reschedule' | 'cancel' | 'no-show' | 'review' | 'followup-create' | 'followup-update' | 'followup-cancel' | 'followup-contact' | 'followup-appointment' | 'clinical-completion' | 'reminder-rule' | 'reminder-rebuild' | 'reminder-migration-preview' | 'reminder-migration-commit' | 'expense-create' | 'expense-confirm'
export type FulfillmentScope = { accountId: string; sourceClinicId: string; appointmentId: string; action: FulfillmentAction; namespace?: string }
export type PendingWrite = FulfillmentScope & { key: string; createdAt: string; operationId: string; state: 'queued' | 'attempting' | 'unknown'; endpoint: string; method: string; payload: Record<string, unknown> }
export function isEnabled(value: unknown): boolean
export function hasActualVisitRecord(appointment: Record<string, unknown> | null, invoice: Record<string, unknown> | null): boolean
export function isPendingAppointment(appointment: Record<string, unknown> | null): boolean
export function canCancelOrReschedule(appointment: Record<string, unknown> | null, invoice: Record<string, unknown> | null): boolean
export function canVerifyNoShow(appointment: Record<string, unknown> | null, invoice: Record<string, unknown> | null, nowEpochMs: number): boolean
export function hasFrontDeskGrant(identity: { grants?: Array<{ role_code: string; scope_kind: string; clinic_id: string | number | null }> } | null, clinicId: string): boolean
export function isExplicitFrontDeskTarget(sourceClinicId: string, targetClinicId: string | null, allowedClinicIds: string[]): boolean
export function fulfillmentRequestKey(scope: FulfillmentScope): string
export function scopedRequestKey(scope: FulfillmentScope): string
export function savePendingWrite(storage: Storage, scope: FulfillmentScope, request: { endpoint: string; method?: string; payload: Record<string, unknown>; createdAt?: string }): PendingWrite
export function loadPendingWrite(storage: Storage, scope: FulfillmentScope): PendingWrite | null
export function removePendingWrite(storage: Storage, scope: FulfillmentScope): void
export function markPendingWriteState(storage: Storage, record: PendingWrite, state: PendingWrite['state']): PendingWrite
export function loadFulfillmentPendingWrites(storage: Storage, scope: Omit<FulfillmentScope, 'action'>): PendingWrite[]
export function loadFollowupPendingWrites(storage: Storage, scope: Omit<FulfillmentScope, 'action' | 'namespace'>): PendingWrite[]
export function loadClinicalCompletionPendingWrite(storage: Storage, scope: Omit<FulfillmentScope, 'action' | 'namespace'>): PendingWrite | null
export function loadReminderPendingWrites(storage: Storage, scope: Omit<FulfillmentScope, 'action' | 'namespace'>): PendingWrite[]
export function loadExpensePendingWrites(storage: Storage, scope: Omit<FulfillmentScope, 'action' | 'namespace'>): PendingWrite[]
export function loadFollowupPendingWrites(storage: Storage, scope: Omit<FulfillmentScope, 'action' | 'namespace'>): PendingWrite[]
export function loadScopedPendingWrites(storage: Storage, scope: Omit<FulfillmentScope, 'action'>, actions: FulfillmentAction[]): PendingWrite[]
export function isUnknownWriteOutcome(error: unknown): boolean
export function isRequestScopeCurrent(expected: { generation: number; accountId: string; sourceClinicId: string; sessionToken: string | null }, current: { generation: number; accountId: string; sourceClinicId: string; sessionToken: string | null }): boolean
export function makeReviewPayload(input: { valid: boolean; exempt: boolean; reason: string }): { valid: boolean; exempt: boolean; reason: string }
