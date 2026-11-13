import type { Identity } from './api.js'
export type OrganizationReadAccess = { employee: boolean; allowed: boolean; chain: boolean; clinicIds: string[] }
export type OrganizationClinicSummary = { clinicId: string; clinicCode: string; name: string; active: boolean; mutationCount: string }
export type OrganizationDentistSummary = { dentistId: string; employeeNo: string; name: string; active: boolean; mutationCount: string }
export type OrganizationClinicDetail = OrganizationClinicSummary & { postalAddress: string; managerName: string; phone: string; email: string; areaM2: string }
export type OrganizationDentistDetail = OrganizationDentistSummary & { birthDate: string; homeAddress: string; phone: string; email: string }
export function clearOrganizationPrivateDetail(detailRef: { value: OrganizationDentistDetail | null }): void
export function organizationReadAccess(identity: Identity | null): OrganizationReadAccess
export function organizationClinicListRequest(access: OrganizationReadAccess, input?: { afterId?: string | null; limit?: number }): { endpoint: string; afterId: string | null; limit: number; scope: { chain: boolean; clinicIds: string[] } }
export function organizationDentistListRequest(access: OrganizationReadAccess, input?: { afterId?: string | null; limit?: number }): { endpoint: string; afterId: string | null; limit: number; scope: { chain: boolean; clinicIds: string[] } }
export function organizationClinicDetailRequest(access: OrganizationReadAccess, clinicId: string): { endpoint: string; clinicId: string; scope: { chain: boolean; clinicIds: string[] } }
export function organizationDentistDetailRequest(access: OrganizationReadAccess, dentistId: string): { endpoint: string; dentistId: string; scope: { chain: boolean; clinicIds: string[] } }
export function preserveOrganizationClinicList(raw: unknown, request: ReturnType<typeof organizationClinicListRequest>): { items: OrganizationClinicSummary[]; hasMore: boolean; nextAfterId: string | null }
export function preserveOrganizationDentistList(raw: unknown, request: ReturnType<typeof organizationDentistListRequest>): { items: OrganizationDentistSummary[]; hasMore: boolean; nextAfterId: string | null }
export function preserveOrganizationClinicDetail(raw: unknown, request: ReturnType<typeof organizationClinicDetailRequest>): { resource: OrganizationClinicDetail; snapshot: OrganizationClinicDetail }
export function preserveOrganizationDentistDetail(raw: unknown, request: ReturnType<typeof organizationDentistDetailRequest>): { resource: OrganizationDentistDetail; snapshot: OrganizationDentistDetail }
