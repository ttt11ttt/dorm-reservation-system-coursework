package cn.adc.dental;

import java.math.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;

/** Pure input, authorization and public projection rules for organization creation. */
final class OrganizationRules {
    private OrganizationRules() {}
    static final int MAX_BYTES=48*1024;
    static void size(Object value){if(Store.encode(value).getBytes(StandardCharsets.UTF_8).length>MAX_BYTES)throw new ApiError(413,"ORG_PAYLOAD_TOO_LARGE","组织资源内容超出48KiB限制");}
    static void bound(int found,int max){if(found>max)throw new ApiError(413,"ORG_SCOPE_TOO_LARGE","组织治理范围超出单次限制");}
    static boolean chain(Identity.Actor actor){return "员工".equals(actor.kind());}
    static boolean local(Identity.Actor actor,long clinic){return "员工".equals(actor.kind())&&actor.grants().stream().anyMatch(g->"运营人员／店长".equals(g.get("role_code"))&&"门店".equals(g.get("scope_kind"))&&g.get("clinic_id") instanceof Number n&&n.longValue()==clinic);}
    static void chainOnly(Identity.Actor actor){if(!chain(actor))throw ApiError.denied();}
    static void clinicRead(Identity.Actor actor,long clinic){if(!chain(actor)&&!local(actor,clinic))throw ApiError.denied();}
    static void sameReceipt(long savedActor,String savedFingerprint,long actor,String requestedFingerprint){if(savedActor!=actor||!Objects.equals(savedFingerprint,requestedFingerprint))throw ApiError.conflict("IDEMPOTENCY_CONFLICT");}
    static String label(String value,int max){return Store.required(value,max).trim();}
    static String area(String value){
        if(value==null||!value.matches("[0-9]{1,8}(\\.[0-9]{1,2})?"))throw ApiError.bad();
        BigDecimal n=new BigDecimal(value).setScale(2,RoundingMode.UNNECESSARY);
        if(n.signum()<=0||n.precision()>10)throw ApiError.bad();return n.toPlainString();
    }
    static String birthDate(String value){try{var day=LocalDate.parse(value);if(day.isBefore(LocalDate.of(1000,1,1))||day.isAfter(LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))))throw ApiError.bad();return day.toString();}catch(Exception e){throw ApiError.bad();}}
    static Map<String,Object> clinicSummary(Map<String,Object> row,long count){var out=new LinkedHashMap<String,Object>();out.put("clinicId",Long.toString(Store.number(row,"clinic_id")));out.put("clinicCode",row.get("clinic_code"));out.put("name",row.get("name"));out.put("active",Store.enabled(row,"active"));out.put("mutationCount",Long.toString(count));size(out);return out;}
    static Map<String,Object> dentistSummary(Map<String,Object> row,long count){var out=new LinkedHashMap<String,Object>();out.put("dentistId",Long.toString(Store.number(row,"dentist_id")));out.put("employeeNo",row.get("employee_no"));out.put("name",row.get("name"));out.put("active",Store.enabled(row,"active"));out.put("mutationCount",Long.toString(count));size(out);return out;}
}
