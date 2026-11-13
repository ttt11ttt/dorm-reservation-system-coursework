import type { Identity } from './api.js'
import type { OpeningAccess, OrganizationOpeningSnapshot } from './organization-opening-core.js'
export type ScheduleAccess = OpeningAccess
export type OrganizationScheduleSnapshot = { scheduleId: string; dentistId: string; clinicId: string; openingId: string; startAt: string; endAt: string; status: '有效' | '撤销'; rowVersion: string; mutationCount: string }
export type OrganizationScheduleBody = { clinicId: string; scheduleId: string; dentistId: string; openingId: string; startAt: string; endAt: string }
export type OrganizationScheduleMarker = { actorAccountId: string; kind: 'schedule'; clinicId: string; scheduleId: string; dentistId: string; openingId: string; route: string; scope: 'chain' | 'clinic'; state: 'sending' | 'unknown' | 'conflict' }
export function normalizeScheduleId(value: unknown, label?: string): string
export function organizationScheduleAccess(identity: Identity | null): ScheduleAccess
export function organizationScheduleClinicRequest(access: ScheduleAccess, clinicId: string): { endpoint: string; clinicId: string; scope: { chain: boolean; clinicIds: string[] }; scopeKey: string }
export function organizationScheduleOpeningRequest(access: ScheduleAccess, openingId: string): { endpoint: string; openingId: string; scope: { chain: boolean; clinicIds: string[] } }
export function organizationScheduleListRequest(access: ScheduleAccess, clinicId: string, input?: { afterId?: string | null; limit?: number }): { endpoint: string; clinicId: string; afterId: string | null; limit: number }
export function organizationScheduleDetailRequest(access: ScheduleAccess, scheduleId: string): { endpoint: string; scheduleId: string }
export function normalizeOrganizationScheduleBody(input: Record<string, unknown>, options?: { now?: Date; allowPast?: boolean }): OrganizationScheduleBody
export function preserveExactOrganizationScheduleRetryBody(body: OrganizationScheduleBody): OrganizationScheduleBody
export function organizationScheduleOpeningCovers(opening: OrganizationOpeningSnapshot | null, body: OrganizationScheduleBody): boolean
export function preserveOrganizationScheduleList(raw: unknown, request: ReturnType<typeof organizationScheduleListRequest>): { items: OrganizationScheduleSnapshot[]; hasMore: boolean; nextAfterId: string | null }
export function preserveOrganizationScheduleDetail(raw: unknown, request: ReturnType<typeof organizationScheduleDetailRequest>): { resource: OrganizationScheduleSnapshot; snapshot: OrganizationScheduleSnapshot }
export function preserveOrganizationScheduleReceipt(raw: unknown, body: OrganizationScheduleBody): { replayed: boolean; resource: OrganizationScheduleSnapshot; snapshot: OrganizationScheduleSnapshot }
export function preserveScheduleOpening(raw: unknown, request: ReturnType<typeof organizationScheduleOpeningRequest>): OrganizationOpeningSnapshot
export function safeOrganizationScheduleMarker(raw: OrganizationScheduleMarker): OrganizationScheduleMarker
export function loadOrganizationScheduleMarker(storage: Storage): OrganizationScheduleMarker | null
export function persistOrganizationScheduleMarker(storage: Storage, marker: OrganizationScheduleMarker): OrganizationScheduleMarker
export function clearOrganizationScheduleMarker(storage: Storage, expected: OrganizationScheduleMarker): boolean
export function clearInvalidOrganizationScheduleMarker(storage: Storage, input: { manuallyReconciled: boolean; confirmed: boolean }): { status: 'cleared' | 'empty' } | { status: 'valid'; marker: OrganizationScheduleMarker }
export function scheduleManualRetryAllowed(input: { marker: OrganizationScheduleMarker | null; actorAccountId: string; clinicId: string; scheduleId: string; dentistId: string; openingId: string; scope: 'chain' | 'clinic'; clinicInspected: boolean; scheduleInspected: boolean; openingInspected: boolean }): boolean
export function classifyOrganizationScheduleError(error: unknown): 'conflict' | 'rejected' | 'unknown'
export function organizationScheduleContextMatches(captured: Record<string, unknown>, current: Record<string, unknown>): boolean
export function organizationSchedulePostReadOutcome(confirmed: boolean, readsSucceeded: boolean): { writeState: 'confirmed' | 'unknown'; readFailed: boolean }
