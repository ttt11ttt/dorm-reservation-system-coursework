import type { Identity } from './api.js'
export type StaffWorkspace = 'front' | 'dentist' | 'reminders' | 'expenses' | 'quarter' | 'account-admin' | 'organization'
export function staffWorkspaceAccess(identity: Identity | null): { employee: boolean; accountManager: boolean; organization: boolean; organizationChain: boolean; organizationClinicIds: string[]; front: boolean; dentist: boolean; reminders: boolean; expenses: boolean; quarter: boolean }
export function chooseStaffLanding(identity: Identity, clinicCount: number): StaffWorkspace
