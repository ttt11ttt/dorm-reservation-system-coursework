const api = require('./utils/api')

App({
  globalData: { profile: null },

  onLaunch() {
    const loadSession = api.sessionToken()
      ? Promise.resolve(api.sessionInfo())
      : api.ensureBookingSession()
    loadSession.then((session) => {
      if (session.subjectKind === 'PATIENT') {
        return session.profile
          ? session.profile
          : api.request('/patient/profile').then((profile) => { this.globalData.profile = profile })
      }
      if (session.subjectKind === 'GUEST') return api.request('/guest/booking-status')
      return undefined
    }).catch(() => {
      // Login/configuration errors are presented on the home page; never log codes or tokens.
    })
  }
})
