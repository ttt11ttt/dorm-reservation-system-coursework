package cn.adc.dental;

import java.util.*;
import java.nio.charset.StandardCharsets;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Pure authorization and public-result policy shared by staff governance endpoints. */
final class StaffAdminRules {
    private StaffAdminRules() {}
    static final Set<String> ROLES=Set.of("前台","牙医","财务","运营人员／店长","总部管理","账号管理");
    private static final JsonMapper JSON=JsonMapper.builder().build();
    static final int AUDIT_BYTES=48*1024;
    static void bound(int size,int max){if(size>max)throw new ApiError(413,"STAFF_SCOPE_TOO_LARGE","员工治理范围超出单次限制");}
    static void safePayload(Object value){if(value!=null&&Store.encode(value).getBytes(StandardCharsets.UTF_8).length>AUDIT_BYTES)throw new ApiError(413,"STAFF_PAYLOAD_TOO_LARGE","员工治理内容超过48KiB安全上限");}
    static Long scope(String role,String kind,String clinic){
        if(role==null||kind==null||!ROLES.contains(role)||!Set.of("门店","连锁").contains(kind))throw ApiError.bad();
        if(kind.equals("连锁")){if(clinic!=null)throw ApiError.bad();return null;}
        if(role.equals("总部管理"))throw ApiError.bad();
        return Store.parseId(clinic);
    }
    static boolean chain(List<Map<String,Object>> grants){return grants.stream().anyMatch(g->"账号管理".equals(g.get("role_code"))&&"连锁".equals(g.get("scope_kind")));}
    static boolean local(List<Map<String,Object>> grants,long clinic){return grants.stream().anyMatch(g->"账号管理".equals(g.get("role_code"))&&"门店".equals(g.get("scope_kind"))&&g.get("clinic_id") instanceof Number n&&n.longValue()==clinic);}
    static void authorize(String kind,List<Map<String,Object>> grants,Long clinic){
        if(!"员工".equals(kind)||(!chain(grants)&&(clinic==null||!local(grants,clinic))))throw ApiError.denied();
    }
    static Long mutationScope(List<Map<String,Object>> actorGrants,List<Map<String,Object>> targetGrants){
        if(chain(actorGrants))return null;
        if(targetGrants.isEmpty())throw ApiError.denied();
        Long clinic=null;
        for(var grant:targetGrants){
            if(!"门店".equals(grant.get("scope_kind"))||!(grant.get("clinic_id") instanceof Number number))throw ApiError.denied();
            long candidate=number.longValue();
            if(clinic!=null&&clinic!=candidate)throw ApiError.denied();
            clinic=candidate;
        }
        if(clinic==null||!local(actorGrants,clinic))throw ApiError.denied();
        return clinic;
    }
    static void grant(String kind,List<Map<String,Object>> actorGrants,long actor,long target,String role,String scope,Long clinic,Long dentist){
        authorize(kind,actorGrants,clinic);
        if(actor==target)throw ApiError.denied();
        if(scope.equals("连锁")&&!chain(actorGrants))throw ApiError.denied();
        if(role.equals("牙医")&&dentist==null)throw ApiError.conflict("DENTIST_BINDING_REQUIRED");
    }
    static List<Map<String,Object>> visible(List<Map<String,Object>> grants,Long clinic,boolean chain){
        return grants.stream().filter(g->chain||clinic!=null&&"门店".equals(g.get("scope_kind"))&&g.get("clinic_id") instanceof Number n&&n.longValue()==clinic).sorted(Comparator.comparingLong(g->Store.number(g,"grant_id"))).toList();
    }
    static Map<String,Object> publicGrant(Map<String,Object> g){
        var v=new LinkedHashMap<String,Object>();v.put("grantId",Long.toString(Store.number(g,"grant_id")));v.put("roleCode",g.get("role_code"));v.put("scopeKind",g.get("scope_kind"));v.put("clinicId",g.get("clinic_id")==null?null:Long.toString(Store.number(g,"clinic_id")));return v;
    }
    static Map<String,Object> snapshot(Map<String,Object> account,List<Map<String,Object>> grants,Long clinic,boolean chain,long count){
        var out=new LinkedHashMap<String,Object>();out.put("accountId",Long.toString(Store.number(account,"account_id")));out.put("loginName",account.get("login_name"));out.put("displayName",account.get("display_name"));out.put("active",Store.enabled(account,"active"));out.put("dentistId",account.get("dentist_id")==null?null:Long.toString(Store.number(account,"dentist_id")));out.put("grants",visible(grants,clinic,chain).stream().map(StaffAdminRules::publicGrant).toList());out.put("mutationCount",Long.toString(count));safePayload(out);return out;
    }
    static void expected(JsonNode supplied,Map<String,Object> current){safePayload(supplied);if(supplied==null||!supplied.equals(JSON.valueToTree(current)))throw ApiError.conflict("STAFF_ACCOUNT_CONFLICT");}
    static void lastAdmin(long activeAfter){if(activeAfter<1)throw ApiError.conflict("LAST_CHAIN_ACCOUNT_ADMIN");}
    static void stateChange(boolean current,boolean requested){if(current==requested)throw ApiError.conflict("STATE_UNCHANGED");}
    static boolean same(Object a,Object b){return JSON.valueToTree(a).equals(JSON.valueToTree(b));}
    static void sameReceipt(Object savedRequest,Object request){safePayload(savedRequest);safePayload(request);if(!same(savedRequest,request))throw ApiError.conflict("IDEMPOTENCY_CONFLICT");}
}
