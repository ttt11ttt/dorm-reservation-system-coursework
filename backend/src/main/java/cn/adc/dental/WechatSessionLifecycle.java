package cn.adc.dental;

import java.time.Instant;
import java.util.Objects;

/** Proposed session metadata comparison only; not wired into Identity or token issuance. */
final class WechatSessionLifecycle {
    enum Source { STAFF_PASSWORD, DEVELOPMENT_PATIENT_PASSWORD, WECHAT_PATIENT }
    // appId is a configured public identifier. No raw OpenID/session_key/code/secret is retained.
    record Issued(Source source, String appId, WechatPatientLoginRules.Stamp binding,
                  String credentialFingerprint, Instant expiresAt) {}

    static boolean current(Issued issued, Instant now, String configuredAppId,
                           WechatPatientLoginRules.Binding binding, int exactMatches,
                           String currentCredentialFingerprint) {
        return issued != null && issued.source() == Source.WECHAT_PATIENT && now != null
                && issued.expiresAt() != null && issued.expiresAt().isAfter(now)
                && configuredAppId != null && !configuredAppId.isBlank()
                && configuredAppId.equals(issued.appId())
                && issued.credentialFingerprint() != null && !issued.credentialFingerprint().isBlank()
                && Objects.equals(issued.credentialFingerprint(), currentCredentialFingerprint)
                && WechatPatientLoginRules.current(issued.binding(), binding, exactMatches);
    }
}
