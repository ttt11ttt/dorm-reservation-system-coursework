import type { Identity } from './api.js'
import type { FulfillmentScope } from './fulfillment-core.js'
export type ReminderRuleSnapshot = {
  ruleId: string; clinicId: string; appointmentLeadMinutes: number | null; followupLeadDays: number | null
  followupSendTime: string | null; inAppEnabled: boolean; smsEnabled: boolean; updatedBy: string
  updatedAt: string; mutationCount: string
}
export type ReminderPlanAction = { operation: string; messageId: string | null; channel: string; plannedAt: string; status: string; content: string; recipientMasked: string }
export type ReminderPlanResult = { businessType: string; businessId: string; clinicId: string; asOf: string; issues: string[]; actions: ReminderPlanAction[]; actionCount: string; actionsTruncated: boolean; requiresReconciliation: boolean; applied: boolean; delivery: string; batchRebuild: string }
export function canReadReminderAdmin(identity: Identity, clinicId: string): boolean
export function canManageReminderRules(identity: Identity, clinicId: string): boolean
export function preserveReminderRuleSnapshot(snapshot: unknown): ReminderRuleSnapshot
export function makeReminderRulePayload(input: { ruleId: string; expected: ReminderRuleSnapshot | null; appointmentLeadMinutes: string | number | null; followupLeadDays: string | number | null; followupSendTime: string | null; inAppEnabled: boolean; smsEnabled: boolean; reason: string }): { ruleId: string; expected: ReminderRuleSnapshot | null; appointmentLeadMinutes: number | null; followupLeadDays: number | null; followupSendTime: string | null; inAppEnabled: boolean; smsEnabled: boolean; reason: string }
export function reminderScope(accountId: string, clinicId: string, entityId: string, action: 'reminder-rule' | 'reminder-rebuild' | 'reminder-migration-preview' | 'reminder-migration-commit'): FulfillmentScope
export function reminderMigrationScope(accountId: string, clinicId: string, action: 'reminder-migration-preview' | 'reminder-migration-commit'): FulfillmentScope
export function makeReminderMigrationPreviewRequest(migrationId: string, rulePayload: ReturnType<typeof makeReminderRulePayload>): { migrationId: string; ruleId: string; expected: ReminderRuleSnapshot; appointmentLeadMinutes: number | null; followupLeadDays: number | null; followupSendTime: string | null; inAppEnabled: boolean; smsEnabled: boolean; reason: string }
export function reminderMigrationEndpoint(clinicId: string, migrationId?: string | null): string
export function makeReminderMigrationCommitPayload(previewDigest: string): { previewDigest: string }
export function reminderMigrationFingerprint(migrationId: string, rulePayload: Record<string, unknown>): string
export function isReminderMigrationPreviewExpired(preview: { expiresAt: string } | null, now?: number): boolean
export function describeReminderMigrationError(code: string): string
export function parseReminderMigrationResponse(raw: unknown): {
  migrationId: string
  preview: {
    migrationId: string; binding: { clinicId: string; actorId: string; migrationId: string; request: Record<string, unknown> }
    createdAt: string; expiresAt: string; previewDigest: string
    summary: { businessCount: number; historyCount: number; resourceCount: number; actionCount: number; actions: Array<{ businessType: string; businessId: string; operation: string; messageId: string | null; channel: string; status: string; plannedAt: string; timeMode: string }>; actionsTruncated: boolean; issues: Array<{ businessType: string; businessId: string; issues: string[]; blocking: boolean }>; commitAllowed: boolean }
  }
  receipt: Record<string, unknown> | null; replayed: boolean; currentSnapshot: ReminderRuleSnapshot | null; delivery: string
}
export function assertReminderMigrationAssociation<T>(clinicId: string, migrationId: string, actorId: string, raw: T, expectedRequest?: Record<string, unknown> | null, expectedDigest?: string | null): ReturnType<typeof parseReminderMigrationResponse>
export function reminderDispatchOptions(record: { method: string; payload: Record<string, unknown> }): { method: string; body?: Record<string, unknown> }
export function createScopedBusyState(initialScope: string): {
  reset(nextScope: string): void
  begin(channel: string): { scope: string; channel: string; sequence: number }
  finish(ticket: { scope: string; channel: string; sequence: number }): boolean
  isBusy(channel: string): boolean
}
export function assertReminderRuleConfirmation<T extends Record<string, unknown>>(clinicId: string, expectedRuleId: string, response: T): T
export function parseReminderPlanResult(raw: unknown): ReminderPlanResult
export function assertReminderPlanAssociation(expectedType: 'appointment' | 'followup', expectedBusinessId: string, expectedClinicId: string, raw: unknown): ReminderPlanResult
