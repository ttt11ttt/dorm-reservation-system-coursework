export type QuarterMode = 'finance' | 'headquarters'
export type QuarterRequest = { endpoint: string; year: number; quarter: number; clinicId: string | null; dentistId: string | null; limit: number }
export type QuarterAmounts = { revenue: string; supplies: string; operations: string; profit: string }
export type QuarterReport = {
  year: number; quarter: number; startDate: string; endDateExclusive: string; utcStart: string; utcEndExclusive: string; readStartedAt: string; computedAt: string
  scopeMode: string; displayScope: { clinicId: string | null; dentistId: string | null }; integrityClinicIds: string[]; scopeTotals: QuarterAmounts; chainTotals: QuarterAmounts | null
  contributionCount: string; sourceCounts: Record<string, string>; integrity: { blockingSources: string; knownSourcesComplete: boolean; status: string }
  anomalyCounts: Array<{ source_type: string; reason: string; source_count: string }>; companyFund: string | null; fundReason: string
  contributions: { items: Array<{ clinicId: string; dentistId: string; amounts: QuarterAmounts }>; hasMore: boolean; limit: number }
  anomalies: { items: Array<{ sourceType: string; sourceId: string; clinicId: string; completedAt: string | null; occurredOn: string | null; reason: string }>; hasMore: boolean; limit: number }
  pagination: 'FIRST_PAGE_ONLY'; laterPageConsistency: 'NEW_REALTIME_VIEW'; basis: 'REGISTERED_VALID_SOURCES_REALTIME_ONLY'
}
export function quarterReadAccess(identity: unknown): { finance: boolean; headquarters: boolean }
export function quarterReadRequest(mode: QuarterMode, options: { year: string | number; quarter: string | number; clinicId?: string; dentistId?: string; limit?: number }): QuarterRequest
export function preserveQuarterReport(raw: unknown, request: QuarterRequest, mode: QuarterMode): QuarterReport
export function quarterRequestMatches(captured: { scopeKey: string; accountId: string; token: string } | null, current: { scopeKey: string; accountId: string; token: string } | null): boolean
export function quarterScopeKey(mode: QuarterMode, request: QuarterRequest): string
