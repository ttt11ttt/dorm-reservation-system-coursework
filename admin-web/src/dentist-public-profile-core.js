const positiveId = (value) => typeof value === 'string' && /^[1-9]\d{0,18}$/.test(value) && BigInt(value) <= 0x7fffffffffffffffn
const plain = (value, max) => typeof value === 'string' && value.length <= max && !/[<>\u0000-\u001f\u007f]/.test(value)

export function preservePublicProfile(raw, dentistId) {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw) || !positiveId(String(dentistId))) throw new Error('公开资料响应无效。')
  const fields = ['dentistId', 'name', 'exists', 'specialtyLabel', 'professionalTitle', 'introduction', 'expertise', 'avatarAssetKey', 'published', 'rowVersion']
  if (Object.keys(raw).sort().join(',') !== fields.sort().join(',')) throw new Error('公开资料响应含非公开字段。')
  if (raw.dentistId !== String(dentistId) || typeof raw.name !== 'string' || typeof raw.exists !== 'boolean'
      || typeof raw.published !== 'boolean' || !Number.isInteger(raw.rowVersion) || raw.rowVersion < 0
      || !plain(raw.specialtyLabel, 80) || !plain(raw.professionalTitle, 80)
      || !plain(raw.introduction, 2000) || !plain(raw.expertise, 2000)
      || raw.avatarAssetKey !== null) throw new Error('公开资料响应与所选牙医不一致。')
  return { dentistId: raw.dentistId, name: raw.name, exists: raw.exists, specialtyLabel: raw.specialtyLabel,
    professionalTitle: raw.professionalTitle, introduction: raw.introduction, expertise: raw.expertise,
    avatarAssetKey: null, published: raw.published, rowVersion: raw.rowVersion }
}

export function publicProfileBody(form, snapshot, operationId) {
  if (!positiveId(operationId) || !snapshot || !positiveId(snapshot.dentistId) || !Number.isInteger(snapshot.rowVersion)) throw new Error('请先读取当前资料。')
  const specialtyLabel = String(form.specialtyLabel ?? '').trim()
  const professionalTitle = String(form.professionalTitle ?? '').trim()
  const introduction = String(form.introduction ?? '').trim()
  const expertise = String(form.expertise ?? '').trim()
  const published = form.published === true
  if (![plain(specialtyLabel, 80), plain(professionalTitle, 80), plain(introduction, 2000), plain(expertise, 2000)].every(Boolean)) throw new Error('公开资料含无效字符或超出长度。')
  if (published && [specialtyLabel, professionalTitle, introduction, expertise].some((value) => !value)) throw new Error('公布前请填写真实专业、职称、简介和擅长。')
  return { operationId, expectedRowVersion: snapshot.rowVersion, specialtyLabel, professionalTitle, introduction, expertise, avatarAssetKey: null, published }
}

export function preservePublicProfileReceipt(raw, dentistId) {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw) || typeof raw.replayed !== 'boolean') throw new Error('保存回执无效。')
  return { replayed: raw.replayed, resource: preservePublicProfile(raw.resource, dentistId) }
}

export function parsePublicProfileMarker(raw) {
  if (raw == null) return null
  const marker = JSON.parse(raw)
  if (!marker || typeof marker !== 'object' || Array.isArray(marker)
    || Object.keys(marker).sort().join(',') !== 'actorAccountId,dentistId,operationId,state'
    || !positiveId(marker.actorAccountId) || !positiveId(marker.dentistId) || !positiveId(marker.operationId)
    || !['sending', 'unknown', 'conflict'].includes(marker.state)) throw new Error('待核对标识无效。')
  return marker
}

export function publicProfileContextMatches(captured, current) {
  return captured.accountId === current.accountId && captured.token === current.token
    && captured.grantKey === current.grantKey && captured.dentistId === current.dentistId
    && captured.generation === current.generation
}
