package cn.adc.dental;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/** Short-lived handoff, not a patient session. Never persisted or logged. Single instance only. */
final class WechatBindingTickets {
    private record Pending(WechatIdentityExchange.Subject subject, Instant expires, Long operation, Long target) {
        public String toString() { return "Pending[redacted]"; }
    }
    private final Clock clock;
    private final SecureRandom random;
    private final Map<String, Pending> pending = new HashMap<>();
    WechatBindingTickets(Clock clock, SecureRandom random) { this.clock=clock; this.random=random; }
    synchronized Map<String,Object> create(WechatIdentityExchange.Subject subject) {
        pending.entrySet().removeIf(e -> !e.getValue().expires().isAfter(clock.instant()));
        if (subject == null || pending.size() >= 512) throw WechatIdentityExchange.unavailable();
        byte[] bytes = new byte[16]; String code;
        do { random.nextBytes(bytes); code=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
        while (pending.containsKey(Identity.digest(code)));
        Instant expires=clock.instant().plus(Duration.ofMinutes(10));
        pending.put(Identity.digest(code), new Pending(subject,expires,null,null));
        return Map.of("bindingCode",code,"expiresAt",expires.toString());
    }
    static String digest(String code) {
        if (code == null || !code.matches("[A-Za-z0-9_-]{22}")) throw ApiError.bad();
        return Identity.digest(code);
    }
    synchronized Instant lookup(String code) { return get(digest(code)).expires(); }
    synchronized WechatIdentityExchange.Subject reserve(String code,long operation,long target) {
        String key=digest(code); Pending found=get(key);
        if (found.operation()!=null && (found.operation()!=operation || found.target()!=target)) {
            throw ApiError.conflict("BINDING_CODE_IN_USE");
        }
        pending.put(key,new Pending(found.subject(),found.expires(),operation,target));
        return found.subject();
    }
    synchronized void complete(String code) { pending.remove(digest(code)); }
    private Pending get(String digest) {
        var found=pending.get(digest);
        if (found==null || !found.expires().isAfter(clock.instant())) {
            pending.remove(digest);
            throw new ApiError(409,"BINDING_CODE_EXPIRED","核验码已失效，请患者重新申请");
        }
        return found;
    }
}
