export function createRequestLane() {
  let generation = 0
  let busyOwner = null
  return {
    begin() {
      const ticket = ++generation
      busyOwner = ticket
      return ticket
    },
    invalidate() {
      generation += 1
      busyOwner = null
      return generation
    },
    isCurrent(ticket) { return ticket === generation },
    finish(ticket) {
      if (busyOwner !== ticket) return false
      busyOwner = null
      return true
    },
    ownsBusy(ticket) { return busyOwner === ticket },
  }
}

export function createIdentityRefreshLane() {
  let generation = 0
  return {
    begin(token) { return { generation: ++generation, token: String(token ?? '') } },
    invalidate() { generation += 1; return generation },
    isCurrent(captured, token) {
      return !!captured && captured.generation === generation && captured.token === String(token ?? '')
    },
  }
}

export function staffAdminWriteContextMatches(captured, current) {
  return !!captured && !!current && !captured.disposed && !current.disposed
    && captured.accountId === current.accountId && captured.token === current.token
    && captured.scopeKey === current.scopeKey && captured.actorGeneration === current.actorGeneration
    && captured.writeGeneration === current.writeGeneration
}

export function staffAdminCanApplyWriteDetail(targetAccountId, selectedAccountId) {
  return typeof targetAccountId === 'string' && targetAccountId === selectedAccountId
}

export function clearStaffAdminSecrets(createFormRef, resetPasswordRef, retrySecretsRef) {
  if (createFormRef?.value && Object.hasOwn(createFormRef.value, 'password')) createFormRef.value.password = ''
  if (resetPasswordRef && 'value' in resetPasswordRef) resetPasswordRef.value = ''
  if (retrySecretsRef && 'value' in retrySecretsRef) retrySecretsRef.value = {}
}

export function requestOwnsCurrentSession(requestToken, currentToken, authRequired = true) {
  return authRequired && typeof requestToken === 'string' && requestToken.length > 0 && requestToken === currentToken
}
