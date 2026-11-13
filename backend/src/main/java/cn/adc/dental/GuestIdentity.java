package cn.adc.dental;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Guest credentials have no account or patient authority. Only their digest is persisted. */
@Service
class GuestIdentity {
    private final Store db;
    private final WechatRuntime runtime;
    private final WechatBindingStore bindings;
    private final Identity identity;
    private final Deque<Instant> requests=new ArrayDeque<>();
    GuestIdentity(Store db,WechatRuntime runtime,WechatBindingStore bindings,Identity identity) {
        this.db=db;this.runtime=runtime;this.bindings=bindings;this.identity=identity;
    }
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private synchronized void admit() {
        Instant now=db.clock.instant();
        while(!requests.isEmpty()&&!requests.peekFirst().isAfter(now.minusSeconds(60)))requests.removeFirst();
        if(requests.size()>=120)throw new ApiError(429,"WECHAT_LOGIN_THROTTLED","登录请求较多，请稍后重新登录");
        requests.addLast(now);
    }
    @Transactional
    public Object login(String code) {
        admit();
        var subject=runtime.exchange(code);
        byte[] app=bytes(subject.appId()),open=bytes(subject.openId());
        var matched=db.jdbc.queryForList("SELECT account_id FROM accounts WHERE wechat_app_id=? AND wechat_open_id=? FOR SHARE",app,open);
        int matches=matched.size();
        if(matches>1)throw WechatIdentityExchange.unavailable();
        if(matches==1) {
            var result=identity.issueWechat(bindings.resolve(subject));
            return Map.of("subjectKind","PATIENT","subjectKey","P-"+Store.number(matched.getFirst(),"account_id"),"token",result.get("token"),"tokenType",result.get("tokenType"),"expiresAt",result.get("expiresAt"));
        }
        var rows=db.jdbc.queryForList("SELECT guest_subject_id,active,ever_verified_patient_id FROM guest_subjects WHERE wechat_app_id=? AND wechat_open_id=? FOR UPDATE",app,open);
        long id;
        if(rows.isEmpty()) {
            id=db.id();db.jdbc.update("INSERT INTO guest_subjects(guest_subject_id,wechat_app_id,wechat_open_id,active,created_at) VALUES(?,?,?,1,?) ON DUPLICATE KEY UPDATE guest_subject_id=guest_subject_id",id,app,open,db.now());
            var stored=db.one("SELECT guest_subject_id,active,ever_verified_patient_id FROM guest_subjects WHERE wechat_app_id=? AND wechat_open_id=? FOR UPDATE",app,open);
            if(!GuestPolicy.guestEligible(Store.enabled(stored,"active"),false,stored.get("ever_verified_patient_id")!=null))throw new ApiError(403,"PATIENT_BINDING_REQUIRED","请联系诊所线下核验绑定");
            id=Store.number(stored,"guest_subject_id");
        } else {
            var row=rows.getFirst();
            if(!GuestPolicy.guestEligible(Store.enabled(row,"active"),false,row.get("ever_verified_patient_id")!=null))throw new ApiError(403,"PATIENT_BINDING_REQUIRED","请联系诊所线下核验绑定");
            id=Store.number(row,"guest_subject_id");
        }
        byte[] random=new byte[32];db.random.nextBytes(random);
        String token=Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        var expiry=db.clock.instant().plus(Duration.ofHours(8));
        db.jdbc.update("INSERT INTO guest_sessions(token_digest,guest_subject_id,expires_at,created_at) VALUES(?,?,?,?)",Identity.digest(token),id,java.sql.Timestamp.from(expiry),db.now());
        return Map.of("subjectKind","GUEST","subjectKey","G-"+id,"token",token,"tokenType","Bearer","expiresAt",expiry.atOffset(ZoneOffset.UTC).toString());
    }
    long require(String header) {
        if(header==null || !header.matches("Bearer [A-Za-z0-9_-]{43}"))throw unauthenticated();
        var rows=db.jdbc.queryForList("SELECT s.guest_subject_id FROM guest_sessions s JOIN guest_subjects g ON g.guest_subject_id=s.guest_subject_id WHERE s.token_digest=? AND s.revoked_at IS NULL AND s.expires_at>? AND g.active=1 AND g.ever_verified_patient_id IS NULL AND NOT EXISTS(SELECT 1 FROM accounts a WHERE a.wechat_app_id=g.wechat_app_id AND a.wechat_open_id=g.wechat_open_id)",Identity.digest(header.substring(7)),db.now());
        if(rows.size()!=1)throw unauthenticated();
        return Store.number(rows.getFirst(),"guest_subject_id");
    }
    void logout(String header) {
        require(header);
        db.jdbc.update("UPDATE guest_sessions SET revoked_at=? WHERE token_digest=?",db.now(),Identity.digest(header.substring(7)));
    }
    private ApiError unauthenticated() { return new ApiError(401,"UNAUTHENTICATED","会话无效或已过期"); }
}
