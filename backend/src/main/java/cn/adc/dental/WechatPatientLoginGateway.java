package cn.adc.dental;

import java.util.Map;

/** Fail-closed boundary; a verified transport + transactional binding/session flow is not installed yet. */
final class WechatPatientLoginGateway {
    interface VerifiedFlow {
        // Implementations must exchange code server-side, resolve exact AppID/OpenID, and issue under row lock.
        Map<String, Object> login(String code);
    }
    private final boolean enabled, appIdPresent, secretPresent, migrationVerified;
    private final VerifiedFlow flow;
    WechatPatientLoginGateway(boolean enabled, boolean appIdPresent, boolean secretPresent,
                              boolean migrationVerified, VerifiedFlow flow) {
        this.enabled = enabled; this.appIdPresent = appIdPresent; this.secretPresent = secretPresent;
        this.migrationVerified = migrationVerified; this.flow = flow;
    }
    Map<String, Object> login(Map<String, ?> request, int requestBytes) {
        String code = WechatPatientLoginRules.code(request, requestBytes);
        WechatPatientLoginRules.available(enabled, appIdPresent, secretPresent, flow != null, migrationVerified);
        return flow.login(code);
    }
}
