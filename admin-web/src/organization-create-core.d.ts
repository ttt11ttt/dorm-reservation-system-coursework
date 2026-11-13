export type OrganizationCreateKind = 'clinic' | 'dentist'
export type OrganizationCreateMarker = {
  actorAccountId: string
  kind: OrganizationCreateKind
  resourceId: string
  route: '/org/clinics' | '/org/dentists'
  state: 'sending' | 'unknown' | 'conflict'
  scope: 'chain'
}
export type OrganizationClinicCreateBody = {
  clinicId: string; clinicCode: string; name: string; postalAddress: string
  managerName: string; phone: string; email: string; areaM2: string
}
export type OrganizationDentistCreateBody = {
  dentistId: string; employeeNo: string; name: string; birthDate: string
  homeAddress: string; phone: string; email: string
}
export type OrganizationCreateBody = OrganizationClinicCreateBody | OrganizationDentistCreateBody
export type OrganizationCreateReceipt = { replayed: boolean; resource: Record<string, unknown>; snapshot: Record<string, unknown> }
export function organizationCreateAccess(identity: unknown): { employee: boolean; canCreate: boolean }
export function normalizeOrganizationId(value: unknown, label?: string): string
export function normalizeOrganizationCreateBody(kind: OrganizationCreateKind, input: Record<string, unknown>, options?: { now?: Date }): OrganizationCreateBody
export function organizationCreateRoute(kind: OrganizationCreateKind, resourceId: string): { resourceId: string; route: '/org/clinics' | '/org/dentists' }
export function safeOrganizationCreateMarker(marker: OrganizationCreateMarker): OrganizationCreateMarker
export function loadOrganizationCreateMarker(storage: Storage): OrganizationCreateMarker | null
export function persistOrganizationCreateMarker(storage: Storage, marker: OrganizationCreateMarker): OrganizationCreateMarker
export function clearOrganizationCreateMarker(storage: Storage): void
export function clearInvalidOrganizationCreateMarker(storage: Storage, confirmation: { manuallyReconciled: boolean; confirmed: boolean }): { status: 'cleared' | 'empty' } | { status: 'valid'; marker: OrganizationCreateMarker }
export function organizationCreateRetryAllowed(args: {
  pendingMarker: OrganizationCreateMarker | null; actorAccountId: string; kind: OrganizationCreateKind; resourceId: string
  exactRetry?: boolean; manualRetypeReady?: boolean; markerInspected?: boolean
}): boolean
export function preserveOrganizationCreateReceipt(kind: OrganizationCreateKind, raw: unknown, expectedBody: OrganizationCreateBody): OrganizationCreateReceipt
export function classifyOrganizationCreateError(error: unknown): 'conflict' | 'rejected' | 'unknown'
export function organizationWriteContextMatches(captured: Record<string, unknown>, current: Record<string, unknown>): boolean
export function createOrganizationWriteState(): {
  begin(): number; invalidate(): number; isCurrent(ticket: number): boolean
  finish(ticket: number): boolean; ownsBusy(ticket: number): boolean
}
export function runOrganizationCreateAttempt(args: {
  storage: Storage; marker: OrganizationCreateMarker; body: OrganizationCreateBody
  post: (route: string, body: OrganizationCreateBody) => Promise<unknown>
  readCurrent?: (kind: OrganizationCreateKind, resourceId: string) => Promise<unknown>
  isCurrent: () => boolean
  onPhase?: (phase: 'blocked' | 'sending' | 'unknown' | 'conflict' | 'rejected' | 'confirmed', detail?: unknown, marker?: OrganizationCreateMarker | null) => void
  onReadFailure?: (error: unknown) => void
}): Promise<{ status: 'blocked' | 'stale' | 'unknown' | 'conflict' | 'rejected' | 'confirmed'; receipt?: OrganizationCreateReceipt; markerCleared?: boolean }>
