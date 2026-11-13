package cn.adc.dental;

import java.security.GeneralSecurityException;
import java.util.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Bounded staff account governance; patient and organization mutations are separate. */
@Service
class StaffAdminService {
    private static final String ACCOUNT="account_id,login_name,account_kind,patient_id,dentist_id,display_name,active";
    private static final String GRANT="grant_id,account_id,role_code,scope_kind,clinic_id,active,granted_by,granted_at";
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final Store db;
    StaffAdminService(Store db){this.db=db;}
    record GrantInput(String roleCode,String scopeKind,String clinicId) {}
    record Create(String accountId,String grantId,String loginName,String displayName,String dentistId,GrantInput initialGrant,String password) {public String toString(){return "Create[credential redacted]";}}
    record AddGrant(String grantId,JsonNode expected,String roleCode,String scopeKind,String clinicId,String reason) {}
    record Revoke(String operationId,JsonNode expected,String reason) {}
    record State(String operationId,JsonNode expected,Boolean active,String reason) {}
    record CredentialReset(String operationId,JsonNode expected,String newPassword,String reason) {public String toString(){return "CredentialReset[credential redacted]";}}
    record Closure(SortedMap<Long,Map<String,Object>> accounts,SortedMap<Long,Map<String,Object>> grants,List<Long> admins,List<Long> clinics,List<Long> dentists) {}
    private static String marks(int size){return String.join(",",Collections.nCopies(size,"?"));}
    private Closure discover(long actor,long target,Long newClinic,Long newDentist){
        var adminRows=db.jdbc.queryForList("SELECT DISTINCT a.account_id FROM accounts a JOIN account_grants g ON g.account_id=a.account_id WHERE a.account_kind='员工' AND a.active=1 AND g.active=1 AND g.role_code='账号管理' AND g.scope_kind='连锁' ORDER BY a.account_id LIMIT 65");
        var admins=adminRows.stream().map(r->Store.number(r,"account_id")).toList();StaffAdminRules.bound(admins.size(),64);
        var accountIds=new TreeSet<Long>(admins);accountIds.add(actor);accountIds.add(target);StaffAdminRules.bound(accountIds.size(),66);
        var accounts=new TreeMap<Long,Map<String,Object>>();for(var a:db.jdbc.queryForList("SELECT "+ACCOUNT+" FROM accounts WHERE account_id IN ("+marks(accountIds.size())+") ORDER BY account_id",accountIds.toArray()))accounts.put(Store.number(a,"account_id"),a);
        var grants=new TreeMap<Long,Map<String,Object>>();for(var g:db.jdbc.queryForList("SELECT "+GRANT+" FROM account_grants WHERE account_id IN ("+marks(accountIds.size())+") ORDER BY grant_id LIMIT 513",accountIds.toArray()))grants.put(Store.number(g,"grant_id"),g);StaffAdminRules.bound(grants.size(),512);
        var clinics=new TreeSet<Long>();if(newClinic!=null)clinics.add(newClinic);
        for(var g:grants.values())if(Store.enabled(g,"active")&&g.get("clinic_id")!=null)clinics.add(Store.number(g,"clinic_id"));StaffAdminRules.bound(clinics.size(),128);
        var dentists=new TreeSet<Long>();if(newDentist!=null)dentists.add(newDentist);for(var a:accounts.values())if(a.get("dentist_id")!=null)dentists.add(Store.number(a,"dentist_id"));StaffAdminRules.bound(dentists.size(),66);
        return new Closure(accounts,grants,admins,List.copyOf(clinics),List.copyOf(dentists));
    }
    private static boolean sameClosure(Closure a,Closure b){return a.accounts().equals(b.accounts())&&a.grants().equals(b.grants())&&a.admins().equals(b.admins())&&a.clinics().equals(b.clinics())&&a.dentists().equals(b.dentists());}
    private Closure lock(long actor,long target,Long clinic,Long dentist){
        var before=discover(actor,target,clinic,dentist);
        for(long id:before.clinics())db.one("SELECT clinic_id,active FROM clinics WHERE clinic_id=? FOR UPDATE",id);
        for(long id:before.dentists())db.one("SELECT dentist_id,active FROM dentists WHERE dentist_id=? FOR UPDATE",id);
        for(long id:before.accounts().keySet())db.one("SELECT "+ACCOUNT+" FROM accounts WHERE account_id=? FOR UPDATE",id);
        for(long id:before.grants().keySet())db.one("SELECT "+GRANT+" FROM account_grants WHERE grant_id=? FOR UPDATE",id);
        var after=discover(actor,target,clinic,dentist);if(!sameClosure(before,after))throw ApiError.conflict("RETRY_TRANSACTION");
        StaffAdminRules.lastAdmin(after.admins().size());return after;
    }
    private static List<Map<String,Object>> activeGrants(Closure c,long account){return c.grants().values().stream().filter(g->Store.number(g,"account_id")==account&&Store.enabled(g,"active")).toList();}
    private static boolean activeStaff(Closure c,long id){var a=c.accounts().get(id);return a!=null&&"员工".equals(a.get("account_kind"))&&Store.enabled(a,"active")&&a.get("patient_id")==null;}
    private boolean chain(Closure c,long actor){return StaffAdminRules.chain(activeGrants(c,actor));}
    private void authorize(Closure c,long actor,Long clinic){if(!activeStaff(c,actor))throw ApiError.denied();StaffAdminRules.authorize("员工",activeGrants(c,actor),clinic);}
    private static boolean visible(Closure c,long target,Long clinic,boolean chain){return chain||clinic!=null&&activeGrants(c,target).stream().anyMatch(g->"门店".equals(g.get("scope_kind"))&&g.get("clinic_id") instanceof Number n&&n.longValue()==clinic);}
    private long count(long id){return db.jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE object_type='staff_account' AND object_id=? AND action IN ('员工账号创建','员工授权','员工撤权','员工账号启停','员工凭据重置')",Long.class,Long.toString(id));}
    private Map<String,Object> snapshot(Map<String,Object> a,List<Map<String,Object>> grants,Long clinic,boolean chain,long count){return StaffAdminRules.snapshot(a,grants,clinic,chain,count);}
    private Map<String,Object> current(long id,Long clinic,boolean chain){var a=db.one("SELECT "+ACCOUNT+" FROM accounts WHERE account_id=?",id);var grants=db.jdbc.queryForList("SELECT "+GRANT+" FROM account_grants WHERE account_id=? AND active=1 ORDER BY grant_id LIMIT 513",id);StaffAdminRules.bound(grants.size(),512);return snapshot(a,grants,clinic,chain,count(id));}
    private static Map<String,Object> canonical(long actor,String kind,long account,long grant,Object identity,Object extra){var m=new LinkedHashMap<String,Object>();m.put("actorId",Long.toString(actor));m.put("kind",kind);m.put("accountId",Long.toString(account));m.put("grantId",Long.toString(grant));m.put("identity",identity);m.put("extra",extra);return m;}
    private JsonNode receipt(long id,String action,String key,String value){
        String sql="SELECT after_value FROM audit_logs WHERE object_type='staff_account' AND object_id=? AND action=?"+(key==null?"":" AND JSON_UNQUOTE(JSON_EXTRACT(after_value,'$.request."+key+"'))=?")+" ORDER BY audit_id DESC LIMIT 2";
        var rows=key==null?db.jdbc.queryForList(sql,Long.toString(id),action):db.jdbc.queryForList(sql,Long.toString(id),action,value);
        if(rows.isEmpty())return null;if(rows.size()!=1||rows.getFirst().get("after_value")==null)throw ApiError.conflict("IDEMPOTENCY_CONFLICT");
        var node=JSON.readTree(rows.getFirst().get("after_value").toString());StaffAdminRules.safePayload(node);return node;
    }
    private Object fixed(JsonNode receipt,Object request){
        StaffAdminRules.sameReceipt(receipt.get("request"),request);if(receipt.get("result")==null)throw ApiError.conflict("IDEMPOTENCY_CONFLICT");
        var fixed=JSON.treeToValue(receipt.get("result"),Map.class);var result=new LinkedHashMap<String,Object>();fixed.forEach((k,v)->result.put(k.toString(),v));result.put("replayed",true);StaffAdminRules.safePayload(result);return Store.json(result);
    }
    private Object replay(long id,String action,String key,String value,Object request){var original=receipt(id,action,key,value);if(original==null)throw ApiError.conflict("IDEMPOTENCY_CONFLICT");return fixed(original,request);}
    private void audit(long actor,String action,long account,String reason,Object request,Object result,Object before){var after=Map.of("request",request,"result",result);StaffAdminRules.safePayload(request);StaffAdminRules.safePayload(result);StaffAdminRules.safePayload(before);StaffAdminRules.safePayload(after);db.audit(actor,action,"staff_account",account,reason,before,after);}
    static String passwordHash(String password,Store db){
        if(password==null||password.length()<16||password.length()>256)throw ApiError.bad();
        try{byte[] salt=new byte[16];db.random.nextBytes(salt);var spec=new PBEKeySpec(password.toCharArray(),salt,210000,256);byte[] hash=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();spec.clearPassword();return "pbkdf2-sha256$210000$"+Base64.getEncoder().encodeToString(salt)+"$"+Base64.getEncoder().encodeToString(hash);}catch(GeneralSecurityException e){throw new IllegalStateException("credential hash unavailable");}
    }
    private static String login(String value){if(value==null||!value.matches("[A-Za-z][A-Za-z0-9_-]{2,47}"))throw ApiError.bad();return value;}
    private static String label(String value){return Store.required(value,80).trim();}
    private static String reason(String value){return Store.required(value,1000);}
    private static Long optionalId(String value){return value==null?null:Store.parseId(value);}
    private static Map<String,Object> grantIdentity(String role,String scope,Long clinic){var g=new LinkedHashMap<String,Object>();g.put("roleCode",role);g.put("scopeKind",scope);g.put("clinicId",clinic==null?null:Long.toString(clinic));return g;}
    @Transactional
    public Object list(Identity.Actor actor,String clinicText,String afterText,Integer requestedLimit){
        Long clinic=optionalId(clinicText);StaffAdminRules.authorize(actor.kind(),actor.grants(),clinic);boolean chain=StaffAdminRules.chain(actor.grants());if(!chain&&clinic==null)throw ApiError.denied();
        long after=afterText==null?0:Store.parseId(afterText);int limit=requestedLimit==null?30:requestedLimit;if(limit<1||limit>50)throw ApiError.bad();
        String select="SELECT "+ACCOUNT+" FROM accounts a WHERE a.account_kind='员工' AND a.account_id>?";
        var args=new ArrayList<Object>();args.add(after);
        if(clinic!=null){select+=" AND EXISTS(SELECT 1 FROM account_grants g WHERE g.account_id=a.account_id AND g.active=1 AND g.scope_kind='门店' AND g.clinic_id=?)";args.add(clinic);}
        select+=" ORDER BY a.account_id LIMIT ?";args.add(limit+1);var rows=db.jdbc.queryForList(select,args.toArray());boolean more=rows.size()>limit;var items=new ArrayList<Map<String,Object>>();
        for(var row:rows.stream().limit(limit).toList())items.add(current(Store.number(row,"account_id"),clinic,chain));
        db.audit(actor.id(),"员工账号列表读取","staff_account",clinic==null?actor.id():clinic);var out=new LinkedHashMap<String,Object>();out.put("items",items);out.put("hasMore",more);out.put("nextAfterId",more?items.getLast().get("accountId"):null);StaffAdminRules.safePayload(out);return Store.json(out);
    }
    @Transactional
    public Object detail(Identity.Actor actor,long id,String clinicText){
        Long clinic=optionalId(clinicText);StaffAdminRules.authorize(actor.kind(),actor.grants(),clinic);boolean chain=StaffAdminRules.chain(actor.grants());var a=db.one("SELECT "+ACCOUNT+" FROM accounts WHERE account_id=?",id);if(!"员工".equals(a.get("account_kind")))throw ApiError.denied();
        if(!chain){var grants=db.jdbc.queryForList("SELECT "+GRANT+" FROM account_grants WHERE account_id=? AND active=1 ORDER BY grant_id LIMIT 513",id);StaffAdminRules.bound(grants.size(),512);if(grants.stream().noneMatch(g->"门店".equals(g.get("scope_kind"))&&Objects.equals(g.get("clinic_id"),clinic)))throw ApiError.denied();}
        var result=current(id,clinic,chain);var body=Map.of("account",result,"snapshot",result);StaffAdminRules.safePayload(body);db.audit(actor.id(),"员工账号读取","staff_account",id);return Store.json(body);
    }
    @Transactional
    public Object create(Identity.Actor actor,Create input){
        if(input==null||input.initialGrant()==null)throw ApiError.bad();long id=Store.parseId(input.accountId()),grantId=Store.parseId(input.grantId());String name=login(input.loginName()),display=label(input.displayName());Long dentist=optionalId(input.dentistId());var initial=input.initialGrant();Long clinic=StaffAdminRules.scope(initial.roleCode(),initial.scopeKind(),initial.clinicId());
        var c=lock(actor.id(),id,clinic,dentist);if(!activeStaff(c,actor.id()))throw ApiError.denied();boolean chain=chain(c,actor.id());
        var identity=new LinkedHashMap<String,Object>();identity.put("loginName",name);identity.put("displayName",display);identity.put("dentistId",dentist==null?null:Long.toString(dentist));var request=canonical(actor.id(),"create",id,grantId,identity,grantIdentity(initial.roleCode(),initial.scopeKind(),clinic));
        if(c.accounts().containsKey(id))return replay(id,"员工账号创建",null,null,request);
        authorize(c,actor.id(),clinic);if(dentist!=null&&!chain)throw ApiError.denied();if(initial.roleCode().equals("牙医")&&dentist==null)throw ApiError.conflict("DENTIST_BINDING_REQUIRED");
        if(dentist!=null&&!Store.enabled(db.one("SELECT active FROM dentists WHERE dentist_id=?",dentist),"active"))throw ApiError.conflict("DENTIST_INACTIVE");
        if(clinic!=null&&!Store.enabled(db.one("SELECT active FROM clinics WHERE clinic_id=?",clinic),"active"))throw ApiError.conflict("CLINIC_INACTIVE");
        StaffAdminRules.grant("员工",activeGrants(c,actor.id()),actor.id(),id,initial.roleCode(),initial.scopeKind(),clinic,dentist);
        try{
            db.jdbc.update("INSERT INTO accounts(account_id,login_name,account_kind,dentist_id,display_name,credential_ref,active,created_at) VALUES(?,?,'员工',?,?,?,1,?)",id,name,dentist,display,passwordHash(input.password(),db),db.now());
            db.jdbc.update("INSERT INTO account_grants(grant_id,account_id,role_code,scope_kind,clinic_id,active,granted_by,granted_at) VALUES(?,?,?,?,?,1,?,?)",grantId,id,initial.roleCode(),initial.scopeKind(),clinic,actor.id(),db.now());
        }catch(DataIntegrityViolationException error){throw ApiError.conflict("IDEMPOTENCY_CONFLICT");}
        var account=current(id,clinic,chain);account.put("mutationCount","1");var result=Map.of("replayed",false,"account",account,"snapshot",account);audit(actor.id(),"员工账号创建",id,null,request,result,null);return Store.json(result);
    }
    @Transactional
    public Object addGrant(Identity.Actor actor,long id,AddGrant input){
        if(input==null)throw ApiError.bad();long grantId=Store.parseId(input.grantId());Long clinic=StaffAdminRules.scope(input.roleCode(),input.scopeKind(),input.clinicId());String reason=reason(input.reason());var c=lock(actor.id(),id,clinic,null);if(!activeStaff(c,actor.id()))throw ApiError.denied();boolean chain=chain(c,actor.id());
        var extra=new LinkedHashMap<String,Object>();extra.put("reason",reason);extra.put("expected",input.expected());var request=canonical(actor.id(),"grant",id,grantId,grantIdentity(input.roleCode(),input.scopeKind(),clinic),extra);
        if(c.grants().containsKey(grantId))return replay(id,"员工授权","grantId",Long.toString(grantId),request);
        authorize(c,actor.id(),clinic);if(!activeStaff(c,id)||!visible(c,id,clinic,chain))throw ApiError.denied();var target=c.accounts().get(id);StaffAdminRules.grant("员工",activeGrants(c,actor.id()),actor.id(),id,input.roleCode(),input.scopeKind(),clinic,(Long)target.get("dentist_id"));
        var before=current(id,clinic,chain);StaffAdminRules.expected(input.expected(),before);
        if(activeGrants(c,id).stream().anyMatch(g->input.roleCode().equals(g.get("role_code"))&&input.scopeKind().equals(g.get("scope_kind"))&&Objects.equals(g.get("clinic_id"),clinic)))throw ApiError.conflict("GRANT_EXISTS");
        if(clinic!=null&&!Store.enabled(db.one("SELECT active FROM clinics WHERE clinic_id=?",clinic),"active"))throw ApiError.conflict("CLINIC_INACTIVE");
        try{db.jdbc.update("INSERT INTO account_grants(grant_id,account_id,role_code,scope_kind,clinic_id,active,granted_by,granted_at) VALUES(?,?,?,?,?,1,?,?)",grantId,id,input.roleCode(),input.scopeKind(),clinic,actor.id(),db.now());}catch(DataIntegrityViolationException error){throw ApiError.conflict("IDEMPOTENCY_CONFLICT");}
        var after=current(id,clinic,chain);after.put("mutationCount",Long.toString(count(id)+1));var result=Map.of("replayed",false,"account",after,"snapshot",after);audit(actor.id(),"员工授权",id,reason,request,result,before);return Store.json(result);
    }
    @Transactional
    public Object revoke(Identity.Actor actor,long id,long grantId,Revoke input){
        if(input==null)throw ApiError.bad();long op=Store.parseId(input.operationId());String reason=reason(input.reason());
        var candidate=db.jdbc.queryForList("SELECT "+GRANT+" FROM account_grants WHERE grant_id=?",grantId);Long clinic=candidate.isEmpty()||candidate.getFirst().get("clinic_id")==null?null:Store.number(candidate.getFirst(),"clinic_id");
        var c=lock(actor.id(),id,clinic,null);if(!activeStaff(c,actor.id()))throw ApiError.denied();boolean chain=chain(c,actor.id());
        var request=new LinkedHashMap<String,Object>();request.put("actorId",Long.toString(actor.id()));request.put("operationId",Long.toString(op));request.put("accountId",Long.toString(id));request.put("grantId",Long.toString(grantId));request.put("reason",reason);request.put("expected",input.expected());
        // Operation identity belongs to the target account, never to one grant's current status.
        var original=receipt(id,"员工撤权","operationId",Long.toString(op));if(original!=null)return fixed(original,request);
        if(candidate.isEmpty())throw new ApiError(404,"NOT_FOUND","授权不存在");var g0=candidate.getFirst();if(Store.number(g0,"account_id")!=id)throw ApiError.conflict("RETRY_TRANSACTION");
        var target=c.accounts().get(id);if(target==null||!"员工".equals(target.get("account_kind"))||target.get("patient_id")!=null)throw ApiError.denied();var grant=c.grants().get(grantId);if(grant==null||Store.number(grant,"account_id")!=id||!Objects.equals(grant.get("clinic_id"),clinic))throw ApiError.conflict("RETRY_TRANSACTION");
        if(!Store.enabled(grant,"active"))throw ApiError.conflict("IDEMPOTENCY_CONFLICT");
        authorize(c,actor.id(),clinic);if(!chain&&(!"门店".equals(grant.get("scope_kind"))||!StaffAdminRules.local(activeGrants(c,actor.id()),clinic)))throw ApiError.denied();
        var before=current(id,clinic,chain);StaffAdminRules.expected(input.expected(),before);
        boolean last=activeStaff(c,id)&&"账号管理".equals(grant.get("role_code"))&&"连锁".equals(grant.get("scope_kind"));StaffAdminRules.lastAdmin(c.admins().size()-(last?1:0));
        int changed=db.jdbc.update("UPDATE account_grants SET active=0 WHERE grant_id=? AND active=1",grantId);if(changed!=1)throw ApiError.conflict("RETRY_TRANSACTION");
        var after=current(id,clinic,chain);after.put("mutationCount",Long.toString(count(id)+1));var result=Map.of("replayed",false,"account",after,"snapshot",after);audit(actor.id(),"员工撤权",id,reason,request,result,before);return Store.json(result);
    }
    private static Map<String,Object> operationRequest(long actor,long id,long op,JsonNode expected,String reason,Object change){
        var request=new LinkedHashMap<String,Object>();request.put("actorId",Long.toString(actor));request.put("accountId",Long.toString(id));request.put("operationId",Long.toString(op));request.put("expected",expected);request.put("reason",reason);request.put("change",change);return request;
    }
    private Long mutationScope(Closure c,long actor,long id){
        var target=c.accounts().get(id);
        if(target==null||!"员工".equals(target.get("account_kind"))||target.get("patient_id")!=null)throw ApiError.denied();
        return StaffAdminRules.mutationScope(activeGrants(c,actor),activeGrants(c,id));
    }
    @Transactional
    public Object state(Identity.Actor actor,long id,State input){
        if(input==null||input.active()==null)throw ApiError.bad();long op=Store.parseId(input.operationId());String reason=reason(input.reason());
        var c=lock(actor.id(),id,null,null);if(!activeStaff(c,actor.id()))throw ApiError.denied();
        var request=operationRequest(actor.id(),id,op,input.expected(),reason,Map.of("active",input.active()));
        var original=receipt(id,"员工账号启停","operationId",Long.toString(op));if(original!=null)return fixed(original,request);
        Long clinic=mutationScope(c,actor.id(),id);boolean chain=chain(c,actor.id());var before=current(id,clinic,chain);StaffAdminRules.expected(input.expected(),before);
        var target=c.accounts().get(id);boolean wasActive=Store.enabled(target,"active");StaffAdminRules.stateChange(wasActive,input.active());
        if(!input.active()&&c.admins().contains(id))StaffAdminRules.lastAdmin(c.admins().size()-1);
        if(input.active()&&target.get("dentist_id")!=null){long dentist=Store.number(target,"dentist_id");if(!Store.enabled(db.one("SELECT active FROM dentists WHERE dentist_id=?",dentist),"active"))throw ApiError.conflict("DENTIST_INACTIVE");}
        int changed=db.jdbc.update("UPDATE accounts SET active=? WHERE account_id=? AND active=?",input.active()?1:0,id,wasActive?1:0);if(changed!=1)throw ApiError.conflict("RETRY_TRANSACTION");
        var after=current(id,clinic,chain);after.put("mutationCount",Long.toString(count(id)+1));var result=Map.of("replayed",false,"account",after,"snapshot",after);audit(actor.id(),"员工账号启停",id,reason,request,result,before);return Store.json(result);
    }
    @Transactional
    public Object credentialReset(Identity.Actor actor,long id,CredentialReset input){
        if(input==null)throw ApiError.bad();long op=Store.parseId(input.operationId());String reason=reason(input.reason());
        var c=lock(actor.id(),id,null,null);if(!activeStaff(c,actor.id()))throw ApiError.denied();
        var request=operationRequest(actor.id(),id,op,input.expected(),reason,Map.of("credentialReset",true));
        var original=receipt(id,"员工凭据重置","operationId",Long.toString(op));if(original!=null)return fixed(original,request);
        Long clinic=mutationScope(c,actor.id(),id);boolean chain=chain(c,actor.id());var before=current(id,clinic,chain);StaffAdminRules.expected(input.expected(),before);
        String hashed=passwordHash(input.newPassword(),db);
        int changed=db.jdbc.update("UPDATE accounts SET credential_ref=? WHERE account_id=?",hashed,id);if(changed!=1)throw ApiError.conflict("RETRY_TRANSACTION");
        var after=current(id,clinic,chain);after.put("mutationCount",Long.toString(count(id)+1));var result=Map.of("replayed",false,"account",after,"snapshot",after);audit(actor.id(),"员工凭据重置",id,reason,request,result,before);return Store.json(result);
    }
}
