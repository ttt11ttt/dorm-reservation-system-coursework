package cn.adc.dental;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;

@Service
class Identity {
    record Session(long accountId,String kind,Long patientId,Long dentistId,IdentitySessionRules.Version version,Instant expires,
                   WechatSessionLifecycle.Source source,WechatSessionLifecycle.Issued wechat,boolean readOnly) {}
    record Actor(long id,String kind,Long patientId,Long dentistId,List<Map<String,Object>> grants) {
        boolean readOnly(){return grants.stream().anyMatch(g->"只读访客".equals(g.get("role_code")));}
        void front(long clinic) { role("前台",clinic); }
        void dentist(long clinic,long responsibleDentist) {
            role("牙医",clinic);
            // Unified staff workspace: the appointment retains its actual dentist.
        }
        void role(String role,long clinic) {
            if(!hasRole(role,clinic))throw ApiError.denied();
        }
        boolean hasRole(String role,long clinic) { return kind.equals("员工") && grants.stream().anyMatch(g->role.equals(g.get("role_code")) && ("连锁".equals(g.get("scope_kind")) || g.get("clinic_id") instanceof Number n && n.longValue()==clinic)); }
        void patient() { if(!kind.equals("患者") || patientId==null) throw ApiError.denied(); }
    }
    private final Store db;
    private final ObjectProvider<WechatBindingStore> wechatBindings;
    private final Map<String,Session> sessions=new ConcurrentHashMap<>();
    @Autowired
    Identity(Store db,ObjectProvider<WechatBindingStore> wechatBindings) { this.db=db;this.wechatBindings=wechatBindings; }
    Identity(Store db) { this.db=db;this.wechatBindings=null; }
    static String digest(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch(GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
    static Map<String,Object> chainGrant(String role) {
        var grant=new LinkedHashMap<String,Object>();
        grant.put("role_code",role);grant.put("scope_kind","连锁");grant.put("clinic_id",null);
        return grant;
    }
    @Transactional
    public Map<String,Object> login(String name,String password,String kind) {
        Store.required(name,64); Store.required(password,256);
        // The shared account lock holds the verified credential generation through token issuance.
        // Credential reset and state changes acquire this row FOR UPDATE.
        var rows=db.jdbc.queryForList("SELECT account_id,credential_ref FROM accounts WHERE login_name=? AND account_kind=? AND active=1 FOR SHARE",name,kind);
        if(rows.size()!=1 || !verify(password,rows.getFirst().get("credential_ref").toString()))throw new ApiError(401,"INVALID_CREDENTIALS","账号或密码无效");
        long id=((Number)rows.getFirst().get("account_id")).longValue();
        var actor=load(id); if(kind.equals("员工") && actor.grants.isEmpty() && !"访客".equals(actor.kind()))throw ApiError.denied();
        var version=IdentitySessionRules.version(rows.getFirst().get("credential_ref").toString(),stateGeneration(id));
        byte[] bytes=new byte[32]; db.random.nextBytes(bytes);
        String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expiry=db.clock.instant().plus(Duration.ofHours(8));
        sessions.entrySet().removeIf(e->!e.getValue().expires.isAfter(db.clock.instant()));
        sessions.put(digest(token),new Session(id,actor.kind(),actor.patientId(),actor.dentistId(),version,expiry,
                "员工".equals(kind)?WechatSessionLifecycle.Source.STAFF_PASSWORD:WechatSessionLifecycle.Source.DEVELOPMENT_PATIENT_PASSWORD,null,actor.readOnly()));
        return Map.of("token",token,"tokenType","Bearer","expiresAt",expiry.atOffset(ZoneOffset.UTC).toString());
    }
    Map<String,Object> issueWechat(WechatBindingStore.Snapshot verified) {
        if(wechatBindings==null || verified==null)throw WechatIdentityExchange.unavailable();
        var stamp=WechatPatientLoginRules.bound(verified.binding(),verified.exactMatches());
        byte[] bytes=new byte[32];db.random.nextBytes(bytes);
        String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expiry=db.clock.instant().plus(Duration.ofHours(8));
        var version=IdentitySessionRules.version(verified.credentialRef(),stateGeneration(verified.accountId()));
        var metadata=new WechatSessionLifecycle.Issued(WechatSessionLifecycle.Source.WECHAT_PATIENT,verified.appId(),stamp,version.credentialFingerprint(),expiry);
        sessions.entrySet().removeIf(e->!e.getValue().expires.isAfter(db.clock.instant()));
        sessions.put(digest(token),new Session(verified.accountId(),"患者",verified.patientId(),null,version,expiry,WechatSessionLifecycle.Source.WECHAT_PATIENT,metadata,false));
        return Map.of("token",token,"tokenType","Bearer","expiresAt",expiry.atOffset(ZoneOffset.UTC).toString());
    }
    boolean visitorSession(String header) {
        if(header==null||!header.matches("Bearer [A-Za-z0-9_-]{43}"))return false;
        var session=sessions.get(digest(token(header)));
        return session!=null&&session.readOnly();
    }
    Actor require(String header) {
        String key=digest(token(header));var session=sessions.get(key);
        if(session==null || !session.expires.isAfter(db.clock.instant())) { sessions.remove(key); throw unauthenticated(); }
        try {
            var actor=load(session.accountId);
            var credential=db.jdbc.queryForList("SELECT credential_ref FROM accounts WHERE account_id=? AND active=1",session.accountId);
            if(credential.size()!=1 || !IdentitySessionRules.matches(session.version,IdentitySessionRules.version(credential.getFirst().get("credential_ref").toString(),stateGeneration(session.accountId)))
                || actor.readOnly()!=session.readOnly() || !Objects.equals(actor.kind(),session.kind) || !Objects.equals(actor.patientId(),session.patientId) || !Objects.equals(actor.dentistId(),session.dentistId))throw unauthenticated();
            if(session.source()==WechatSessionLifecycle.Source.WECHAT_PATIENT) {
                try {
                    if(wechatBindings==null)throw unauthenticated();
                    var current=wechatBindings.getObject().current(session.accountId());
                    if(!WechatSessionLifecycle.current(session.wechat(),db.clock.instant(),current.appId(),current.binding(),current.exactMatches(),Identity.digest(current.credentialRef()))) throw unauthenticated();
                } catch(ApiError rejected) {throw unauthenticated();}
            } else if(session.wechat()!=null)throw unauthenticated();
            return actor;
        } catch(ApiError error) {sessions.remove(key);throw error;}
    }
    void logout(String header) { require(header); sessions.remove(digest(token(header))); }
    private String token(String h) { if(h==null || !h.matches("Bearer [A-Za-z0-9_-]{43}")) throw unauthenticated(); return h.substring(7); }
    private ApiError unauthenticated() { return new ApiError(401,"UNAUTHENTICATED","会话无效或已过期"); }
    private long stateGeneration(long id){return db.jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE object_type='staff_account' AND object_id=? AND action='员工账号启停'",Long.class,Long.toString(id));}
    private Actor load(long id) {
        var rows=db.jdbc.queryForList("SELECT account_id,account_kind,patient_id,dentist_id FROM accounts WHERE account_id=? AND active=1",id);
        if(rows.isEmpty())throw unauthenticated();
        var a=rows.getFirst(); Long patient=(Long)a.get("patient_id"),dentist=(Long)a.get("dentist_id");
        if(patient!=null && db.jdbc.queryForObject("SELECT COUNT(*) FROM patients WHERE patient_id=? AND active=1",Integer.class,patient)!=1)throw unauthenticated();
        if(dentist!=null && db.jdbc.queryForObject("SELECT COUNT(*) FROM dentists WHERE dentist_id=? AND active=1",Integer.class,dentist)!=1)throw unauthenticated();
        var grants=new ArrayList<Map<String,Object>>();
        if("员工".equals(a.get("account_kind"))) {
            var assigned=db.jdbc.queryForList("SELECT grant_id FROM account_grants WHERE account_id=? AND active=1 LIMIT 1",id);
            // Zero stored grants permits business reads only; the gate rejects mutations.
            if(assigned.isEmpty())grants.add(chainGrant("只读访客"));
            for(String role:List.of("账号管理","运营人员／店长","总部管理","财务"))grants.add(chainGrant(role));
            for(var clinic:db.jdbc.queryForList("SELECT clinic_id FROM clinics WHERE active=1"))for(String role:List.of("前台","牙医"))grants.add(Map.of("role_code",role,"scope_kind","门店","clinic_id",clinic.get("clinic_id")));
        }
        return new Actor(id,a.get("account_kind").toString(),patient,dentist,grants);
    }
    static boolean verify(String password,String encoded) {
        try {
            String[] p=encoded.split("\\$"); if(p.length!=4 || !p[0].equals("pbkdf2-sha256") || !p[1].equals("210000")) return false;
            var spec=new PBEKeySpec(password.toCharArray(),Base64.getDecoder().decode(p[2]),210000,256);
            byte[] actual=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); spec.clearPassword();
            return MessageDigest.isEqual(actual,Base64.getDecoder().decode(p[3]));
        } catch(Exception e) { return false; }
    }
}
