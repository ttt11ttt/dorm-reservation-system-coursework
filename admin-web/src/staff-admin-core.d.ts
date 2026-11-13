export type StaffAdminScope = { chain: boolean; clinicId: string | null }
export type StaffAdminAccess = { employee: boolean; accountId: string; allowed: boolean; chain: boolean; clinicIds: string[] }
export type StaffAdminGrant = { grantId: string; roleCode: string; scopeKind: string; clinicId: string | null }
export type StaffAdminSnapshot = { accountId: string; loginName: string; displayName: string; active: boolean; dentistId: string | null; grants: StaffAdminGrant[]; mutationCount: string }
export type StaffAdminPending = {
  version: 1; action: 'create' | 'grant' | 'revoke' | 'state' | 'reset'; targetAccountId: string; scope: StaffAdminScope;
  state: 'sending' | 'unknown' | 'conflict'; operationId: string | null; grantId: string | null; request: Record<string, any>
}
export type StaffAdminMutation = {
  action: StaffAdminPending['action']; method: string; endpoint: string; body: Record<string, any>; targetAccountId: string;
  scope: StaffAdminScope; operationId?: string; grantId?: string
}
export const STAFF_ADMIN_ROLES: string[]
export function staffAdminId(value: unknown, label?: string): string
export function staffAdminAccess(identity: unknown): StaffAdminAccess
export function staffAdminListRequest(access: StaffAdminAccess, input?: { clinicId?: string | null; afterId?: string | null; limit?: number }): { method: string; endpoint: string; scope: StaffAdminScope; afterId: string | null; limit: number }
export function staffAdminDetailRequest(access: StaffAdminAccess, accountId: string, clinicId?: string | null): { method: string; endpoint: string; accountId: string; scope: StaffAdminScope }
export function preserveStaffAdminSnapshot(raw: unknown): StaffAdminSnapshot
export function preserveStaffAdminList(raw: unknown, request: ReturnType<typeof staffAdminListRequest>): { items: StaffAdminSnapshot[]; hasMore: boolean; nextAfterId: string | null }
export function preserveStaffAdminDetail(raw: unknown, request: ReturnType<typeof staffAdminDetailRequest>): { account: StaffAdminSnapshot; snapshot: StaffAdminSnapshot }
export function staffAdminSnapshotsEqual(a: unknown, b: unknown): boolean
export function createStaffAdminOperationId(cryptoSource?: Crypto): string
export function buildStaffAdminCreate(access: StaffAdminAccess, input: Record<string, any>): StaffAdminMutation
export function buildStaffAdminGrant(access: StaffAdminAccess, target: StaffAdminSnapshot, input: Record<string, any>): StaffAdminMutation
export function buildStaffAdminRevoke(access: StaffAdminAccess, target: StaffAdminSnapshot, grantId: string, operationId: string, reason: string, viewClinicId?: string | null): StaffAdminMutation
export function buildStaffAdminState(access: StaffAdminAccess, target: StaffAdminSnapshot, active: boolean, operationId: string, reason: string, viewClinicId?: string | null): StaffAdminMutation
export function buildStaffAdminCredentialReset(access: StaffAdminAccess, target: StaffAdminSnapshot, password: string, operationId: string, reason: string, viewClinicId?: string | null): StaffAdminMutation
export function preserveStaffAdminMutation(raw: unknown, mutation: StaffAdminMutation): { replayed: boolean; account: StaffAdminSnapshot; snapshot: StaffAdminSnapshot }
export function makeStaffAdminPending(mutation: StaffAdminMutation, state?: StaffAdminPending['state']): StaffAdminPending
export function preserveStaffAdminPending(raw: unknown): StaffAdminPending
export function readStaffAdminPending(storage: Storage, key: string): StaffAdminPending[]
export function writeStaffAdminPending(storage: Storage, key: string, entries: StaffAdminPending[]): StaffAdminPending[]
export function replaceStaffAdminPending(entries: StaffAdminPending[], pending: StaffAdminPending): StaffAdminPending[]
export function removeStaffAdminPending(entries: StaffAdminPending[], pending: StaffAdminPending): StaffAdminPending[]
export function staffAdminRetryMutation(access: StaffAdminAccess, pending: StaffAdminPending, secret?: string): StaffAdminMutation
export function staffAdminFailureDisposition(error: unknown): 'unauthorized' | 'conflict' | 'rejected' | 'unknown'
export function staffAdminContextMatches(captured: Record<string, any>, current: Record<string, any>): boolean
export function staffAdminScopeKey(access: StaffAdminAccess, clinicFilterId?: string | null): string
export function staffAdminPendingKey(access: StaffAdminAccess, clinicId?: string | null): string
