export type PublicProfile = { dentistId: string; name: string; exists: boolean; specialtyLabel: string; professionalTitle: string; introduction: string; expertise: string; avatarAssetKey: null; published: boolean; rowVersion: number }
export type PublicProfileBody = { operationId: string; expectedRowVersion: number; specialtyLabel: string; professionalTitle: string; introduction: string; expertise: string; avatarAssetKey: null; published: boolean }
export type PublicProfileMarker = { actorAccountId: string; dentistId: string; operationId: string; state: 'sending' | 'unknown' | 'conflict' }
export function preservePublicProfile(raw: unknown, dentistId: string): PublicProfile
export function publicProfileBody(form: { specialtyLabel: string; professionalTitle: string; introduction: string; expertise: string; published: boolean }, snapshot: PublicProfile, operationId: string): PublicProfileBody
export function preservePublicProfileReceipt(raw: unknown, dentistId: string): { replayed: boolean; resource: PublicProfile }
export function parsePublicProfileMarker(raw: string | null): PublicProfileMarker | null
export function publicProfileContextMatches(captured: Record<string, unknown>, current: Record<string, unknown>): boolean
