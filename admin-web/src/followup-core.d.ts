export type FollowupSnapshot = {
  patientId: string; sourceAppointmentId: string; dentistId: string; clinicId: string; suggestedDate: string
  recommendation: string; status: string; completedAppointmentId: string | null; completedAt: string | null
  cancelReason: string | null; createdAt: string; updatedAt: string; mutationCount: string
}
export function preserveFollowupSnapshot(snapshot: unknown): FollowupSnapshot
export function generatePositive63BitId(randomWords?: Uint32Array): string
export function shanghaiInputValue(date?: Date): string
export function makeFollowupContactPayload(input: { contactId: string; contactedAtLocal: string; result: string; note: string; nextContactAtLocal?: string; nowEpochMs?: number }): { contactId: string; contactedAt: string; result: string; note: string; nextContactAt: string | null }
export function cursorQuery(params: Record<string, unknown>, afterId?: string | null): string
export function mergeCursorPage<T>(existing: T[], response: { items: T[]; hasMore: boolean; nextAfterId: string | null; asOfDate: string }, append: boolean): { items: T[]; hasMore: boolean; nextAfterId: string | null; asOfDate: string }
export function mergeIdCursorPage<T>(existing: T[], response: { items: T[]; hasMore: boolean; nextAfterId: string | null }, append: boolean): { items: T[]; hasMore: boolean; nextAfterId: string | null }
export function cursorPageChangedBusinessDate(previousDate: string | null, nextDate: string): boolean
export function followupScope(accountId: string, clinicId: string, recordId: string, action: 'followup-create' | 'followup-update' | 'followup-cancel' | 'followup-contact' | 'followup-appointment'): import('./fulfillment-core.js').FulfillmentScope
export function makeFollowupAppointmentPayload(input: { appointmentNo: string; clinicId: string; dentistId: string; scheduleId: string; scheduledStart: string; scheduledEnd: string; requestedTreatments: string; specialNeeds?: string; bookingChannel: '电话' | '柜台' }): Record<string, string>
export function canArrangeFollowupAppointment(identity: import('./api.js').Identity, planStatus: string, sourceClinicId: string, targetClinicId: string): boolean
export function assertFollowupAppointmentAssociation<T extends Record<string, unknown>>(expectedPlanId: string, response: T): T
export function parseFollowupCompletionFeedback(followup: unknown): null | { outcome: string; outcomeText: string; planId: string; status: string; completedAppointmentId: string; completedAt: string; planReconciliationRequired: boolean; reminderReconciliationRequired: boolean; unresolvedReminderCount: string; reminderScheduling: string }
export function isCompletionResponseCurrent(expected: { contextRevision: number; appointmentId: string; accountId: string; clinicId: string; sessionToken: string | null }, current: { contextRevision: number; appointmentId: string; accountId: string; clinicId: string; sessionToken: string | null; disposed: boolean; workMode: string }): boolean
export function clinicalCompletionScope(accountId: string, clinicId: string, appointmentId: string): import('./fulfillment-core.js').FulfillmentScope
export function hasRoleAtClinic(identity: import('./api.js').Identity, roleCode: string, clinicId: string): boolean
export function formatFollowupError(error: unknown): string
