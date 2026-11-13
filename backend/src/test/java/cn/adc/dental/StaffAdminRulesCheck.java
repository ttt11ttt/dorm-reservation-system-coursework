package cn.adc.dental;

import java.time.Instant;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Pure shared-policy checks. No Spring context, SQL, mocks, HTTP or patient writes. */
public class StaffAdminRulesCheck {
    private static final List<Map<String,Object>> checks=new ArrayList<>();
    private static void check(String name,boolean ok){checks.add(Map.of("name",name,"passed",ok));if(!ok)throw new AssertionError(name);}
    private static void reject(String name,String code,Runnable action){try{action.run();throw new AssertionError(name+" accepted");}catch(ApiError e){check(name,e.code.equals(code));}}
    private static Map<String,Object> grant(long id,String role,String scope,Long clinic){var g=new LinkedHashMap<String,Object>();g.put("grant_id",id);g.put("role_code",role);g.put("scope_kind",scope);g.put("clinic_id",clinic);return g;}
    public static void main(String[] args){var report=new LinkedHashMap<String,Object>();report.put("mode","no-db-no-http");report.put("startedAt",Instant.now().toString());boolean okay=false;try{run();okay=true;}catch(Throwable error){report.put("error",error.getClass().getSimpleName()+": "+error.getMessage());}report.put("status",okay?"passed":"failed");report.put("checks",checks);report.put("finishedAt",Instant.now().toString());System.out.println(Store.encode(report));if(!okay)System.exit(1);}
    private static void run(){
        var chain=List.of(grant(1,"账号管理","连锁",null));var local=List.of(grant(2,"账号管理","门店",10L));
        check("chain administrator has chain role",StaffAdminRules.chain(chain));check("local administrator is not chain administrator",!StaffAdminRules.chain(local));
        check("local administrator only has exact clinic",StaffAdminRules.local(local,10)&&!StaffAdminRules.local(local,11));
        StaffAdminRules.authorize("员工",chain,null);check("chain staff may manage chain scope",true);
        StaffAdminRules.authorize("员工",local,10L);check("local staff may manage own clinic",true);
        reject("patient with copied grant denied","FORBIDDEN",()->StaffAdminRules.authorize("患者",chain,10L));
        reject("local staff cannot manage chain scope","FORBIDDEN",()->StaffAdminRules.authorize("员工",local,null));
        reject("local staff cannot manage other clinic","FORBIDDEN",()->StaffAdminRules.authorize("员工",local,11L));
        check("clinic scope parses exact clinic",StaffAdminRules.scope("前台","门店","10")==10);
        check("chain scope has no clinic",StaffAdminRules.scope("运营人员／店长","连锁",null)==null);
        reject("scope enum rejects alias","INVALID_INPUT",()->StaffAdminRules.scope("前台","总部",null));
        reject("chain scope rejects clinic","INVALID_INPUT",()->StaffAdminRules.scope("前台","连锁","10"));
        reject("local scope requires clinic","INVALID_INPUT",()->StaffAdminRules.scope("前台","门店",null));
        reject("local headquarters role rejected","INVALID_INPUT",()->StaffAdminRules.scope("总部管理","门店","10"));
        reject("unknown role rejected","INVALID_INPUT",()->StaffAdminRules.scope("超级管理员","连锁",null));
        reject("missing role rejected as input","INVALID_INPUT",()->StaffAdminRules.scope(null,"门店","10"));
        reject("self-grant denied","FORBIDDEN",()->StaffAdminRules.grant("员工",chain,5,5,"前台","门店",10L,null));
        reject("local admin cannot grant chain role","FORBIDDEN",()->StaffAdminRules.grant("员工",local,5,6,"运营人员／店长","连锁",null,null));
        reject("dentist role needs bound dentist","DENTIST_BINDING_REQUIRED",()->StaffAdminRules.grant("员工",chain,5,6,"牙医","门店",10L,null));
        StaffAdminRules.grant("员工",chain,5,6,"运营人员／店长","连锁",null,null);check("separate operations employee may receive explicit chain grant",true);
        var account=new LinkedHashMap<String,Object>();account.put("account_id",6L);account.put("login_name","ops_staff");account.put("display_name","运营员工");account.put("active",1);account.put("dentist_id",null);account.put("credential_ref","secret-must-not-appear");
        var all=List.of(grant(10,"账号管理","门店",10L),grant(11,"财务","门店",11L),grant(12,"运营人员／店长","连锁",null));
        var publicLocal=StaffAdminRules.snapshot(account,all,10L,false,7);var publicChain=StaffAdminRules.snapshot(account,all,null,true,7);
        check("local snapshot contains only own clinic grant",((List<?>)publicLocal.get("grants")).size()==1);
        check("chain snapshot contains all grants",((List<?>)publicChain.get("grants")).size()==3);
        check("global mutation count is visible without foreign grants",publicLocal.get("mutationCount").equals("7"));
        check("public snapshots exclude credentials",!Store.encode(publicLocal).contains("secret-must-not-appear")&&!Store.encode(publicChain).contains("credential_ref"));
        var json=JsonMapper.builder().build();StaffAdminRules.expected(json.valueToTree(publicLocal),publicLocal);check("exact public expected accepted",true);
        var changed=new LinkedHashMap<>(publicLocal);changed.put("mutationCount","8");reject("cross-clinic mutation invalidates local expected","STAFF_ACCOUNT_CONFLICT",()->StaffAdminRules.expected(json.valueToTree(publicLocal),changed));
        var original=Map.of("replayed",false,"snapshot",publicLocal);check("fixed replay compares safe canonical shape",StaffAdminRules.same(json.valueToTree(original),original));
        check("changed canonical replay content conflicts",!StaffAdminRules.same(json.valueToTree(original),Map.of("replayed",true,"snapshot",publicLocal)));
        var revokeOriginal=Map.of("operationId","900","accountId","6","grantId","10","expected","version-1","reason","岗位调整");
        StaffAdminRules.sameReceipt(json.valueToTree(revokeOriginal),revokeOriginal);check("same revocation operation returns original regardless of current grant state",true);
        for(String changedKey:List.of("grantId","expected","reason")){var changedRevoke=new LinkedHashMap<String,Object>(revokeOriginal);changedRevoke.put(changedKey,"different");reject("same operation with changed "+changedKey+" rejected","IDEMPOTENCY_CONFLICT",()->StaffAdminRules.sameReceipt(json.valueToTree(revokeOriginal),changedRevoke));}
        StaffAdminRules.bound(64,64);StaffAdminRules.bound(512,512);check("exact discovery bounds accepted",true);
        reject("65th chain administrator rejects full batch","STAFF_SCOPE_TOO_LARGE",()->StaffAdminRules.bound(65,64));
        reject("513th grant rejects full batch","STAFF_SCOPE_TOO_LARGE",()->StaffAdminRules.bound(513,512));
        StaffAdminRules.safePayload("a".repeat(StaffAdminRules.AUDIT_BYTES-2));check("exact 48KiB serialized payload accepted",true);
        reject("oversized UTF8 payload rejected before response or audit","STAFF_PAYLOAD_TOO_LARGE",()->StaffAdminRules.safePayload("牙".repeat(StaffAdminRules.AUDIT_BYTES/3)));
        var strict=JsonMapper.builder();new JsonConfiguration().strictJson().customize(strict);var mapper=strict.build();
        String body="{\"accountId\":\"20\",\"grantId\":\"21\",\"loginName\":\"ops_user\",\"displayName\":\"运营\",\"initialGrant\":{\"roleCode\":\"运营人员／店长\",\"scopeKind\":\"连锁\"},\"password\":\"private-secret-value\"}";
        var decoded=mapper.readValue(body,StaffAdminApi.Create.class);check("create DTO accepts interactive credential",decoded.password().equals("private-secret-value"));
        check("credential excluded from DTO serialization and toString",!mapper.writeValueAsString(decoded).contains("private-secret-value")&&!decoded.toString().contains("private-secret-value"));
        try{mapper.readValue(body.replace("\"password\"","\"actorId\":\"999\",\"password\""),StaffAdminApi.Create.class);throw new AssertionError("unknown create field accepted");}catch(RuntimeException expected){check("unknown create field denied by schema allowlist",true);}
        reject("last chain administrator cannot be removed","LAST_CHAIN_ACCOUNT_ADMIN",()->StaffAdminRules.lastAdmin(0));StaffAdminRules.lastAdmin(1);check("one remaining active chain administrator accepted",true);
        check("chain admin may change employee globally",StaffAdminRules.mutationScope(chain,all)==null);
        check("local admin may mutate target with only own clinic grants",StaffAdminRules.mutationScope(local,List.of(grant(20,"前台","门店",10L),grant(21,"财务","门店",10L)))==10L);
        reject("local admin cannot mutate target with cross-clinic grant","FORBIDDEN",()->StaffAdminRules.mutationScope(local,all));
        reject("local admin cannot mutate target with chain grant","FORBIDDEN",()->StaffAdminRules.mutationScope(local,List.of(grant(22,"前台","门店",10L),grant(23,"财务","连锁",null))));
        reject("local admin cannot mutate target without grants","FORBIDDEN",()->StaffAdminRules.mutationScope(local,List.of()));
        reject("unchanged state with new operation conflicts","STATE_UNCHANGED",()->StaffAdminRules.stateChange(true,true));
        StaffAdminRules.stateChange(false,true);check("disabled account may be enabled with changed state",true);
        var store=new Store(null,java.time.Clock.systemUTC());String password="an-unchanged-password-123";
        var hashA=StaffAdminService.passwordHash(password,store);var hashB=StaffAdminService.passwordHash(password,store);
        check("same password reset receives fresh salt",!hashA.equals(hashB)&&Identity.verify(password,hashA)&&Identity.verify(password,hashB));
        var v0=IdentitySessionRules.version(hashA,0);var v1=IdentitySessionRules.version(hashB,0);
        check("old token rejects new credential generation",!IdentitySessionRules.matches(v0,v1));
        check("new token accepts its credential generation",IdentitySessionRules.matches(v1,IdentitySessionRules.version(hashB,0)));
        check("disable then enable does not resurrect old token",!IdentitySessionRules.matches(v0,IdentitySessionRules.version(hashA,2)));
        check("token issued after reenable accepts current state",IdentitySessionRules.matches(IdentitySessionRules.version(hashA,2),IdentitySessionRules.version(hashA,2)));
        check("verified old credential cannot bind new generation",!IdentitySessionRules.matches(IdentitySessionRules.version(hashA,0),IdentitySessionRules.version(hashB,0)));
        var stateOriginal=Map.of("operationId","901","accountId","6","expected","v1","reason","岗位调整","change",Map.of("active",false));
        StaffAdminRules.sameReceipt(json.valueToTree(stateOriginal),stateOriginal);check("fixed state operation can replay original receipt",true);
        var stateChanged=new LinkedHashMap<String,Object>(stateOriginal);stateChanged.put("change",Map.of("active",true));reject("fixed state operation changed request conflicts","IDEMPOTENCY_CONFLICT",()->StaffAdminRules.sameReceipt(json.valueToTree(stateOriginal),stateChanged));
        var resetBody="{\"operationId\":\"901\",\"expected\":{},\"reason\":\"轮换\",\"newPassword\":\"private-reset-password\"}";
        var resetDto=mapper.readValue(resetBody,StaffAdminApi.CredentialReset.class);
        check("reset DTO accepts write-only secret",resetDto.newPassword().equals("private-reset-password"));
        check("reset secret excluded from serialization and toString",!mapper.writeValueAsString(resetDto).contains("private-reset-password")&&!resetDto.toString().contains("private-reset-password"));
        var resetCanonical=Map.of("operationId","901","accountId","6","expected","v1","reason","轮换","change",Map.of("credentialReset",true));
        check("reset receipt excludes password",!Store.encode(resetCanonical).contains("private-reset-password")&&!Store.encode(resetCanonical).contains("credential_ref"));
        StaffAdminRules.sameReceipt(json.valueToTree(resetCanonical),resetCanonical);check("fixed reset operation can replay without password comparison",true);
        var resetChanged=new LinkedHashMap<String,Object>(resetCanonical);resetChanged.put("reason","不同原因");reject("fixed reset operation changed nonsecret content conflicts","IDEMPOTENCY_CONFLICT",()->StaffAdminRules.sameReceipt(json.valueToTree(resetCanonical),resetChanged));
    }
}
