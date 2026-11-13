package cn.adc.dental;

import java.util.Map;
import java.util.Objects;

/** Transport-independent project policy. Does not exchange codes, access SQL or issue tokens. */
final class WechatPatientLoginRules {
    private WechatPatientLoginRules() {}

    // Project request limits, not assertions about WeChat's documented code length.
    static final int MAX_REQUEST_BYTES = 8192;
    static final int MAX_CODE_CHARS = 2048;

    static String code(Map<String, ?> request, int requestBytes) {
        if (requestBytes < 0 || requestBytes > MAX_REQUEST_BYTES || request == null
                || request.size() != 1 || !request.containsKey("code")
                || !(request.get("code") instanceof String code)
                || code.isBlank() || code.length() > MAX_CODE_CHARS
                || !code.equals(code.strip()) || code.chars().anyMatch(Character::isISOControl)) {
            throw ApiError.bad();
        }
        return code;
    }

    /** Must run before any upstream call or binding-schema query. */
    static void available(boolean enabled, boolean appIdPresent, boolean secretPresent,
                          boolean officialTransportVerified, boolean migrationVerified) {
        if (!enabled || !appIdPresent || !secretPresent || !officialTransportVerified || !migrationVerified) {
            throw unavailable();
        }
    }

    // No OpenID, code, password, secret or session_key belongs in these public-safe snapshots.
    record Binding(long accountId, long patientId, String accountKind, boolean accountActive,
                   boolean patientActive, long generation, boolean independentVerification) {}
    record Stamp(long accountId, long patientId, long generation) {}

    static Stamp bound(Binding binding, int exactAppIdOpenIdMatches) {
        if (exactAppIdOpenIdMatches < 0 || exactAppIdOpenIdMatches > 1) throw unavailable();
        if (exactAppIdOpenIdMatches == 0 || binding == null || binding.accountId() <= 0
                || binding.patientId() <= 0 || !"患者".equals(binding.accountKind())
                || !binding.accountActive() || !binding.patientActive()
                || !binding.independentVerification()) {
            throw new ApiError(403, "PATIENT_BINDING_REQUIRED", "请联系诊所线下核验绑定");
        }
        if (binding.generation() < 0) throw unavailable();
        return new Stamp(binding.accountId(), binding.patientId(), binding.generation());
    }

    /** Caller must re-resolve the exact configured AppID + upstream OpenID mapping on each request. */
    static boolean current(Stamp issued, Binding current, int exactAppIdOpenIdMatches) {
        if (issued == null) return false;
        try { return Objects.equals(issued, bound(current, exactAppIdOpenIdMatches)); }
        catch (ApiError rejected) { return false; }
    }

    static long nextGeneration(long current) {
        if (current < 0 || current == Long.MAX_VALUE) throw unavailable();
        return current + 1;
    }

    private static ApiError unavailable() {
        return new ApiError(503, "WECHAT_LOGIN_UNAVAILABLE", "微信登录暂不可用");
    }
}
