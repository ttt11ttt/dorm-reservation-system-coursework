package cn.adc.dental;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Existing patient accounts only. All mutations require current chain account-administration authority. */
@Service
class WechatBindingStore {
    record Snapshot(long accountId,long patientId,long generation,String appId,String credentialRef,
                    WechatPatientLoginRules.Binding binding,int exactMatches) {
        public String toString() { return "Snapshot[identity redacted]"; }
    }
    private final Store db;
    private final ObjectProvider<Identity> identity;
    private final WechatRuntime runtime;
    WechatBindingStore(Store db,ObjectProvider<Identity> identity,WechatRuntime runtime) { this.db=db;this.identity=identity;this.runtime=runtime; }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String text(Object value) { return value instanceof byte[] b ? new String(b,StandardCharsets.UTF_8) : null; }
    void authorize(String header) {
        var actor=identity.getObject().require(header);
        StaffAdminRules.authorize(actor.kind(),actor.grants(),null);
    }
    private Identity.Actor lockActor(String header,long target) {
        var actor=identity.getObject().require(header);
        // Account locks first, ascending; grants then patient row. Credential/state changes use these account locks.
        db.jdbc.queryForList("SELECT account_id FROM accounts WHERE account_id IN (?,?) ORDER BY account_id FOR UPDATE",actor.id(),target);
        actor=identity.getObject().require(header);
        var grants=db.jdbc.queryForList("SELECT role_code,scope_kind,clinic_id FROM account_grants WHERE account_id=? AND active=1 ORDER BY grant_id FOR SHARE",actor.id());
        StaffAdminRules.authorize(actor.kind(),grants,null);
        return actor;
    }
    private Map<String,Object> target(long account) {
        return db.one("SELECT a.account_id,a.patient_id,a.account_kind,a.active,a.credential_ref,a.wechat_app_id,a.wechat_open_id,a.wechat_binding_generation,p.name,p.patient_no,p.active AS patient_active FROM accounts a JOIN patients p ON p.patient_id=a.patient_id WHERE a.account_id=? AND a.account_kind='患者'",account);
    }
    private boolean verified(long account,long generation) {
        return db.jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE object_type='wechat_binding' AND object_id=? AND action='微信患者绑定' AND JSON_UNQUOTE(JSON_EXTRACT(CASE WHEN JSON_VALID(after_value) THEN after_value ELSE '{}' END,'$.generation'))=?",Integer.class,Long.toString(account),Long.toString(generation))==1;
    }
    private Snapshot snapshot(Map<String,Object> row,int matches) {
        long account=Store.number(row,"account_id"),patient=Store.number(row,"patient_id"),generation=Store.number(row,"wechat_binding_generation");
        var binding=new WechatPatientLoginRules.Binding(account,patient,row.get("account_kind").toString(),Store.enabled(row,"active"),Store.enabled(row,"patient_active"),generation,verified(account,generation));
        return new Snapshot(account,patient,generation,text(row.get("wechat_app_id")),row.get("credential_ref").toString(),binding,matches);
    }
    @Transactional
    public Snapshot resolve(WechatIdentityExchange.Subject subject) {
        runtime.requireReady();
        var rows=db.jdbc.queryForList("SELECT account_id FROM accounts WHERE wechat_app_id=? AND wechat_open_id=? FOR SHARE",bytes(subject.appId()),bytes(subject.openId()));
        if(rows.size()!=1) { WechatPatientLoginRules.bound(null,rows.size()); throw WechatIdentityExchange.unavailable(); }
        long account=Store.number(rows.getFirst(),"account_id");
        var row=target(account);
        db.jdbc.queryForList("SELECT patient_id FROM patients WHERE patient_id=? FOR SHARE",row.get("patient_id"));
        row=target(account);
        var result=snapshot(row,1); WechatPatientLoginRules.bound(result.binding(),1); return result;
    }
    Snapshot current(long account) {
        runtime.requireReady();
        var row=target(account);
        if(row.get("wechat_app_id")==null || row.get("wechat_open_id")==null) throw ApiError.denied();
        if(!runtime.appId.equals(text(row.get("wechat_app_id")))) throw ApiError.denied();
        int matches=db.jdbc.queryForObject("SELECT COUNT(*) FROM accounts WHERE wechat_app_id=? AND wechat_open_id=?",Integer.class,row.get("wechat_app_id"),row.get("wechat_open_id"));
        return snapshot(row,matches);
    }
    Object search(String header,String query) {
        authorize(header); runtime.requireReady(); Store.required(query,80);
        var rows=db.jdbc.queryForList("SELECT a.account_id,p.patient_id,p.patient_no,p.name FROM accounts a JOIN patients p ON p.patient_id=a.patient_id WHERE a.account_kind='患者' AND (p.patient_no=? OR p.name=? OR CAST(a.account_id AS CHAR)=?) ORDER BY a.account_id LIMIT 21",query,query,query);
        if(rows.size()>20) throw ApiError.bad();
        return Store.json(rows);
    }
    Object patients(String header,String query) {
        authorize(header);runtime.requireReady();Store.required(query,80);
        var rows=db.jdbc.queryForList("SELECT p.patient_id,p.patient_no,p.name,p.active,a.account_id FROM patients p LEFT JOIN accounts a ON a.patient_id=p.patient_id AND a.account_kind='患者' WHERE p.patient_no=? OR p.name=? ORDER BY p.patient_id LIMIT 21",query,query);
        if(rows.size()>20)throw ApiError.bad();return Store.json(rows);
    }
    Object receipt(String header,long account,long operation) {
        authorize(header);runtime.requireReady();
        var rows=db.jdbc.queryForList("SELECT action,object_type,object_id,after_value FROM audit_logs WHERE audit_id=?",operation);
        if(rows.isEmpty())return Map.of("found",false);
        var row=rows.getFirst();
        if(!Long.toString(account).equals(row.get("object_id"))||!Set.of("wechat_binding","wechat_patient_account").contains(row.get("object_type")))throw ApiError.denied();
        Map<?,?> after;
        try{after=JsonMapper.builder().build().readValue(row.get("after_value").toString(),Map.class);}catch(RuntimeException invalid){throw WechatIdentityExchange.unavailable();}
        return Map.of("found",true,"operationId",Long.toString(operation),"accountId",Long.toString(account),"generation",after.get("generation").toString(),"bound",after.get("bound"),"action",row.get("action"));
    }
    @Transactional
    public Object openAccount(String header,long account,long patient,long operation,String evidence) {
        runtime.requireReady();
        if(evidence==null||!evidence.matches("[A-Za-z0-9_.:/-]{1,80}"))throw ApiError.bad();
        var actor=lockActor(header,account);
        String digest=requestDigest("open",account,patient,"",evidence);
        var prior=db.jdbc.queryForList("SELECT object_type,object_id,after_value FROM audit_logs WHERE audit_id=?",operation);
        if(!prior.isEmpty()) {
            var row=prior.getFirst();
            if(!"wechat_patient_account".equals(row.get("object_type"))||!Long.toString(account).equals(row.get("object_id")))throw ApiError.conflict("IDEMPOTENCY_CONFLICT");
            Map<?,?> saved=JsonMapper.builder().build().readValue(row.get("after_value").toString(),Map.class);
            if(!digest.equals(saved.get("requestDigest")))throw ApiError.conflict("IDEMPOTENCY_CONFLICT");
            return Map.of("accountId",Long.toString(account),"patientId",Long.toString(patient),"replayed",true);
        }
        var patientRow=db.one("SELECT name,active FROM patients WHERE patient_id=? FOR UPDATE",patient);
        if(!Store.enabled(patientRow,"active"))throw ApiError.denied();
        if(db.jdbc.queryForObject("SELECT COUNT(*) FROM accounts WHERE patient_id=?",Integer.class,patient)!=0)throw ApiError.conflict("PATIENT_ACCOUNT_EXISTS");
        byte[] entropy=new byte[32];db.random.nextBytes(entropy);
        // No usable password is issued. Only a verified WeChat mapping can subsequently authenticate this account.
        String disabledCredential="wechat-only$"+Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
        db.jdbc.update("INSERT INTO accounts(account_id,login_name,account_kind,patient_id,display_name,credential_ref,binding_method,bound_by,bound_at,active,created_at) VALUES(?,?,'患者',?,?,?,'本人到店核验',?,?,1,?)",account,"WX-P-"+account,patient,patientRow.get("name"),disabledCredential,actor.id(),db.now(),db.now());
        var after=Map.of("patientId",patient,"generation",0,"bound",false,"requestDigest",digest);
        db.jdbc.update("INSERT INTO audit_logs(audit_id,actor_account_id,action,object_type,object_id,reason,after_value,happened_at) VALUES(?,?,'患者微信账号开通','wechat_patient_account',?,?,?,?)",operation,actor.id(),Long.toString(account),"本人到店核验；依据编号:"+evidence,Store.encode(after),db.now());
        return Map.of("accountId",Long.toString(account),"patientId",Long.toString(patient),"replayed",false);
    }
    Object detail(String header,long account) {
        authorize(header); runtime.requireReady(); var row=target(account);
        return Store.json(Map.of("accountId",account,"patientId",row.get("patient_id"),"patientNo",row.get("patient_no"),"name",row.get("name"),"active",Store.enabled(row,"active")&&Store.enabled(row,"patient_active"),"generation",row.get("wechat_binding_generation"),"bound",row.get("wechat_app_id")!=null&&row.get("wechat_open_id")!=null));
    }
    @SuppressWarnings("unchecked")
    private Map<String,Object> replay(long operation,long account,String action,String digest) {
        var rows=db.jdbc.queryForList("SELECT object_type,object_id,action,after_value FROM audit_logs WHERE audit_id=?",operation);
        if(rows.isEmpty()) return null;
        var row=rows.getFirst();
        if(!"wechat_binding".equals(row.get("object_type")) || !Long.toString(account).equals(row.get("object_id")) || !action.equals(row.get("action"))) throw ApiError.conflict("IDEMPOTENCY_CONFLICT");
        Map<String,Object> after;
        try { after=(Map<String,Object>)JsonMapper.builder().build().readValue(row.get("after_value").toString(),Map.class); }
        catch(RuntimeException malformed) { throw WechatIdentityExchange.unavailable(); }
        if(!digest.equals(after.get("requestDigest"))) throw ApiError.conflict("IDEMPOTENCY_CONFLICT");
        return Map.of("accountId",Long.toString(account),"patientId",after.get("patientId").toString(),"generation",after.get("generation").toString(),"bound",after.get("bound"),"replayed",true);
    }
    static String requestDigest(String action,long account,long expected,String ticketDigest,String evidence) {
        return Identity.digest(Store.encode(new TreeMap<>(Map.of("action",action,"account",account,"expected",expected,"ticket",ticketDigest,"evidence",evidence))));
    }
    @Transactional
    public Map<String,Object> mutate(String header,long account,long operation,long expected,String evidence,boolean bind,
                                     String code,WechatBindingTickets tickets) {
        runtime.requireReady();
        if(expected<0 || evidence==null || !evidence.matches("[A-Za-z0-9_.:/-]{1,80}")) throw ApiError.bad();
        var actor=lockActor(header,account);
        String action=bind?"微信患者绑定":"微信患者解绑";
        String digest=requestDigest(action,account,expected,bind?WechatBindingTickets.digest(code):"",evidence);
        var prior=replay(operation,account,action,digest); if(prior!=null) return prior;
        var row=target(account); long old=Store.number(row,"wechat_binding_generation");
        if(old!=expected) throw ApiError.conflict("ROW_VERSION_CONFLICT");
        long next=WechatPatientLoginRules.nextGeneration(old);
        var patient=db.one("SELECT active FROM patients WHERE patient_id=? FOR SHARE",row.get("patient_id"));
        if(bind) {
            if(!Store.enabled(row,"active") || !Store.enabled(patient,"active")) throw ApiError.denied();
            if(row.get("wechat_app_id")!=null || row.get("wechat_open_id")!=null) throw ApiError.conflict("PATIENT_ALREADY_BOUND");
            var subject=tickets.reserve(code,operation,account);
            if(!runtime.appId.equals(subject.appId())) throw WechatIdentityExchange.unavailable();
            var guest=db.jdbc.queryForList("SELECT guest_subject_id,conversion_verified_patient_id,ever_verified_patient_id FROM guest_subjects WHERE wechat_app_id=? AND wechat_open_id=? FOR UPDATE",bytes(subject.appId()),bytes(subject.openId()));
            if(!guest.isEmpty()){
                var owner=guest.getFirst();
                long patientId=Store.number(row,"patient_id");
                for(String field:List.of("conversion_verified_patient_id","ever_verified_patient_id")){
                    if(owner.get(field)!=null&&Store.number(owner,field)!=patientId)throw ApiError.conflict("VERIFIED_IDENTITY_CONFLICT");
                }
                if(db.jdbc.queryForObject("SELECT COUNT(*) FROM guest_reservations WHERE guest_subject_id=? AND reservation_status='待到诊'",
                    Long.class,owner.get("guest_subject_id"))>0){
                    throw new ApiError(409,"GUEST_RESERVATIONS_PENDING","请先由门店处理该微信尚未结束的访客预约，再办理绑定。");
                }
            }
            db.jdbc.update("UPDATE accounts SET wechat_app_id=?,wechat_open_id=?,wechat_binding_generation=? WHERE account_id=?",bytes(subject.appId()),bytes(subject.openId()),next,account);
            // Retain verified history across later unbinding; a former patient cannot become an anonymous guest.
            db.jdbc.update("INSERT INTO guest_subjects(guest_subject_id,wechat_app_id,wechat_open_id,ever_verified_patient_id,active,created_at) VALUES(?,?,?,?,1,?) ON DUPLICATE KEY UPDATE ever_verified_patient_id=COALESCE(ever_verified_patient_id,VALUES(ever_verified_patient_id))",db.id(),bytes(subject.appId()),bytes(subject.openId()),row.get("patient_id"),db.now());
            db.jdbc.update("UPDATE guest_sessions s JOIN guest_subjects g ON g.guest_subject_id=s.guest_subject_id SET s.revoked_at=? WHERE g.wechat_app_id=? AND g.wechat_open_id=? AND s.revoked_at IS NULL",db.now(),bytes(subject.appId()),bytes(subject.openId()));
        } else {
            if(row.get("wechat_app_id")==null && row.get("wechat_open_id")==null) throw ApiError.conflict("PATIENT_NOT_BOUND");
            db.jdbc.update("UPDATE accounts SET wechat_app_id=NULL,wechat_open_id=NULL,wechat_binding_generation=? WHERE account_id=?",next,account);
        }
        var after=Map.of("patientId",row.get("patient_id"),"generation",next,"bound",bind,"requestDigest",digest);
        db.jdbc.update("INSERT INTO audit_logs(audit_id,actor_account_id,action,object_type,object_id,reason,before_value,after_value,happened_at) VALUES(?,?,?,?,?,?,?,?,?)",operation,actor.id(),action,"wechat_binding",Long.toString(account),(bind?"本人到店核验":"员工核对解绑")+"；依据编号:"+evidence,Store.encode(Map.of("generation",old)),Store.encode(after),db.now());
        return Map.of("accountId",Long.toString(account),"patientId",row.get("patient_id").toString(),"generation",Long.toString(next),"bound",bind,"replayed",false);
    }
}
