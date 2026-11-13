export type RequestLane = {
  begin(): number
  invalidate(): number
  isCurrent(ticket: number): boolean
  finish(ticket: number): boolean
  ownsBusy(ticket: number): boolean
}
export type IdentityRefreshTicket = { generation: number; token: string }
export function createRequestLane(): RequestLane
export function createIdentityRefreshLane(): {
  begin(token: string | null): IdentityRefreshTicket
  invalidate(): number
  isCurrent(captured: IdentityRefreshTicket, token: string | null): boolean
}
export function staffAdminWriteContextMatches(captured: Record<string, any>, current: Record<string, any>): boolean
export function staffAdminCanApplyWriteDetail(targetAccountId: string, selectedAccountId: string): boolean
export function clearStaffAdminSecrets(createFormRef: { value: any }, resetPasswordRef: { value: string }, retrySecretsRef: { value: Record<string, string> }): void
export function requestOwnsCurrentSession(requestToken: string | null, currentToken: string | null, authRequired?: boolean): boolean
