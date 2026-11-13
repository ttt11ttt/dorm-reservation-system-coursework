import type { Identity } from './api.js'
export type OpeningAccess = { employee: boolean; allowed: boolean; chain: boolean; clinicIds: string[] }
export type OrganizationOpeningSnapshot = { openingId: string; clinicId: string; startAt: string; endAt: string; status: '有效' | '撤销'; note: string | null; updatedBy: string; updatedAt: string; mutationCount: string }
export type OrganizationOpeningBody = { openingId: string; clinicId: string; startAt: string; endAt: string; note: string | null }
export type OrganizationOpeningMarker = { actorAccountId: string; kind: 'opening'; clinicId: string; openingId: string; route: string; state: 'sending' | 'unknown' | 'conflict'; scope: 'operational' }
export function normalizeOpeningId(value: unknown, label?: string): string
export function organizationOpeningAccess(identity: Identity | null): OpeningAccess
export function organizationOpeningClinicRequest(access: OpeningAccess, clinicId: string): { endpoint: string; clinicId: string; scope: { chain: boolean; clinicIds: string[] }; scopeKey: string }
export function organizationOpeningListRequest(access: OpeningAccess, clinicId: string, input?: { afterId?: string | null; limit?: number }): { endpoint: string; clinicId: string; afterId: string | null; limit: number; scopeKey: string }
export function organizationOpeningDetailRequest(access: OpeningAccess, openingId: string): { endpoint: string; openingId: string; scope: { chain: boolean; clinicIds: string[] } }
export function normalizeOrganizationOpeningBody(input: Record<string, unknown>): OrganizationOpeningBody
export function preserveExactOpeningRetryBody(body: OrganizationOpeningBody): OrganizationOpeningBody
export function openingNeedsActiveClinicCheck(input: { pendingMarker: OrganizationOpeningMarker | null; exactRetry?: boolean }): boolean
export function organizationOpeningPostReadOutcome(postConfirmed: boolean, readsSucceeded: boolean): { writeState: 'confirmed' | 'unknown'; readFailed: boolean }
export function organizationOpeningContextMatches(captured: Record<string, unknown>, current: Record<string, unknown>): boolean
export function preserveOrganizationOpeningList(raw: unknown, request: ReturnType<typeof organizationOpeningListRequest>): { items: OrganizationOpeningSnapshot[]; hasMore: boolean; nextAfterId: string | null }
export function preserveOrganizationOpeningDetail(raw: unknown, request: ReturnType<typeof organizationOpeningDetailRequest>): { resource: OrganizationOpeningSnapshot; snapshot: OrganizationOpeningSnapshot }
export function preserveOrganizationOpeningReceipt(raw: unknown, expectedBody: OrganizationOpeningBody, actorAccountId: string): { replayed: boolean; resource: OrganizationOpeningSnapshot; snapshot: OrganizationOpeningSnapshot }
export function safeOrganizationOpeningMarker(raw: OrganizationOpeningMarker): OrganizationOpeningMarker
export function loadOrganizationOpeningMarker(storage: Storage): OrganizationOpeningMarker | null
export function persistOrganizationOpeningMarker(storage: Storage, marker: OrganizationOpeningMarker): OrganizationOpeningMarker
export function clearOrganizationOpeningMarker(storage: Storage, expected: Pick<OrganizationOpeningMarker, 'actorAccountId' | 'clinicId' | 'openingId'>): boolean
export function clearInvalidOrganizationOpeningMarker(storage: Storage, input: { manuallyReconciled: boolean; confirmed: boolean }): { status: 'cleared' | 'empty' } | { status: 'valid'; marker: OrganizationOpeningMarker }
export function openingManualRetryAllowed(input: { marker: OrganizationOpeningMarker | null; actorAccountId: string; clinicId: string; openingId: string; clinicInspected: boolean; openingInspected: boolean }): boolean
export function classifyOrganizationOpeningError(error: unknown): 'conflict' | 'rejected' | 'unknown'
export function preserveOpeningClinic(raw: unknown, request: ReturnType<typeof organizationOpeningClinicRequest>): Record<string, unknown>
