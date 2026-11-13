package cn.adc.dental;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.Date;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** First organization slice: bounded reads and fixed-identity creates. */
@Service
class OrganizationService {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final String CLINIC="clinic_id,clinic_code,name,postal_address,manager_name,phone,email,area_m2,active";
    private static final String DENTIST="dentist_id,employee_no,name,birth_date,home_address,phone,email,active";
    private static final String OPENING="opening_id,clinic_id,start_at,end_at,status,note,updated_by,updated_at";
    private static final String SCHEDULE="schedule_id,dentist_id,clinic_id,opening_id,start_at,end_at,status,row_version";
    private final Store db;
    OrganizationService(Store db){this.db=db;}
    private static int limit(Integer value){int n=value==null?30:value;if(n<1||n>50)throw ApiError.bad();return n;}
    private static long after(String value){return value==null?0:Store.parseId(value);}
    private long count(String type,long id){return db.jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE object_type=? AND object_id=? AND action IN ('门店创建','牙医人员创建','实际开放创建','既定出诊创建')",Long.class,type,Long.toString(id));}
    static String fingerprint(String domain,Map<String,Object> normalized){
        try{var bytes=MessageDigest.getInstance("SHA-256").digest((domain+"\n"+Store.encode(normalized)).getBytes(StandardCharsets.UTF_8));return HexFormat.of().formatHex(bytes);}catch(GeneralSecurityException e){throw new IllegalStateException("organization fingerprint unavailable");}
    }
    private Identity.Actor lockOperator(long id){
        var row=db.one("SELECT account_id,account_kind,patient_id,dentist_id,active FROM accounts WHERE account_id=? FOR UPDATE",id);
        if(!"员工".equals(row.get("account_kind"))||!Store.enabled(row,"active")||row.get("patient_id")!=null)throw ApiError.denied();
        var ids=db.jdbc.queryForList("SELECT grant_id FROM account_grants WHERE account_id=? ORDER BY grant_id LIMIT 513",id);OrganizationRules.bound(ids.size(),512);
        var grants=new ArrayList<Map<String,Object>>();for(var item:ids){var grant=db.one("SELECT grant_id,role_code,scope_kind,clinic_id,active FROM account_grants WHERE grant_id=? FOR UPDATE",Store.number(item,"grant_id"));if(Store.enabled(grant,"active"))grants.add(grant);}
        return new Identity.Actor(id,"员工",null,(Long)row.get("dentist_id"),grants);
    }
    private Object fixed(String type,long id,String action,long actor,String hash){
        var receipts=db.jdbc.queryForList("SELECT actor_account_id,after_value FROM audit_logs WHERE object_type=? AND object_id=? AND action=? ORDER BY audit_id DESC LIMIT 2",type,Long.toString(id),action);
        if(receipts.size()!=1||receipts.getFirst().get("after_value")==null)throw ApiError.conflict("IDEMPOTENCY_CONFLICT");
        var row=receipts.getFirst();var saved=JSON.readTree(row.get("after_value").toString());OrganizationRules.size(saved);
        OrganizationRules.sameReceipt(Store.number(row,"actor_account_id"),saved.path("requestFingerprint").asText(),actor,hash);if(saved.get("result")==null)throw ApiError.conflict("IDEMPOTENCY_CONFLICT");
        var result=JSON.treeToValue(saved.get("result"),Map.class);var out=new LinkedHashMap<String,Object>();result.forEach((k,v)->out.put(k.toString(),v));out.put("replayed",true);OrganizationRules.size(out);return Store.json(out);
    }
    private Object created(long actor,String type,long id,String action,String hash,Map<String,Object> summary){
        var result=Map.of("replayed",false,"resource",summary,"snapshot",summary);
        var after=Map.of("requestFingerprint",hash,"result",result);OrganizationRules.size(result);OrganizationRules.size(after);
        db.audit(actor,action,type,id,null,null,after);return Store.json(result);
    }
    private Map<String,Object> clinicDetail(Map<String,Object> row,long count){
        var out=new LinkedHashMap<String,Object>(OrganizationRules.clinicSummary(row,count));out.put("postalAddress",row.get("postal_address"));out.put("managerName",row.get("manager_name"));out.put("phone",row.get("phone"));out.put("email",row.get("email"));out.put("areaM2",((BigDecimal)row.get("area_m2")).toPlainString());OrganizationRules.size(out);return out;
    }
    private Map<String,Object> dentistDetail(Map<String,Object> row,long count){
        var out=new LinkedHashMap<String,Object>(OrganizationRules.dentistSummary(row,count));out.put("birthDate",row.get("birth_date") instanceof Date date?date.toLocalDate().toString():row.get("birth_date").toString());out.put("homeAddress",row.get("home_address"));out.put("phone",row.get("phone"));out.put("email",row.get("email"));OrganizationRules.size(out);return out;
    }
    @Transactional
    public Object clinics(Identity.Actor actor,String afterText,Integer requested){
        if(!"员工".equals(actor.kind())||!OrganizationRules.chain(actor)&&actor.grants().stream().noneMatch(g->"运营人员／店长".equals(g.get("role_code"))&&"门店".equals(g.get("scope_kind"))))throw ApiError.denied();
        long after=after(afterText);int limit=limit(requested);boolean chain=OrganizationRules.chain(actor);
        String sql="SELECT "+CLINIC+" FROM clinics c WHERE c.clinic_id>?"+(chain?"":" AND EXISTS(SELECT 1 FROM account_grants g WHERE g.account_id=? AND g.active=1 AND g.role_code='运营人员／店长' AND g.scope_kind='门店' AND g.clinic_id=c.clinic_id)")+" ORDER BY c.clinic_id LIMIT ?";
        var rows=chain?db.jdbc.queryForList(sql.replace(CLINIC,"clinic_id,clinic_code,name,active"),after,limit+1):db.jdbc.queryForList(sql.replace(CLINIC,"clinic_id,clinic_code,name,active"),after,actor.id(),limit+1);var items=new ArrayList<Map<String,Object>>();for(var row:rows.stream().limit(limit).toList())items.add(OrganizationRules.clinicSummary(row,count("org_clinic",Store.number(row,"clinic_id"))));
        var out=new LinkedHashMap<String,Object>();out.put("items",items);out.put("hasMore",rows.size()>limit);out.put("nextAfterId",rows.size()>limit?items.getLast().get("clinicId"):null);OrganizationRules.size(out);db.audit(actor.id(),"门店列表读取","org_clinic",actor.id());return Store.json(out);
    }
    @Transactional
    public Object clinic(Identity.Actor actor,long id){
        var row=db.one("SELECT "+CLINIC+" FROM clinics WHERE clinic_id=?",id);OrganizationRules.clinicRead(actor,id);
        var summary=OrganizationRules.clinicSummary(row,count("org_clinic",id));var detail=clinicDetail(row,count("org_clinic",id));db.audit(actor.id(),"门店读取","org_clinic",id,null,null,Map.of("summary",summary));return Store.json(Map.of("resource",detail,"snapshot",detail));
    }
    @Transactional
    public Object dentists(Identity.Actor actor,String afterText,Integer requested){
        OrganizationRules.chainOnly(actor);long after=after(afterText);int limit=limit(requested);
        var rows=db.jdbc.queryForList("SELECT dentist_id,employee_no,name,active FROM dentists WHERE dentist_id>? ORDER BY dentist_id LIMIT ?",after,limit+1);var items=new ArrayList<Map<String,Object>>();for(var row:rows.stream().limit(limit).toList())items.add(OrganizationRules.dentistSummary(row,count("org_dentist",Store.number(row,"dentist_id"))));
        var out=new LinkedHashMap<String,Object>();out.put("items",items);out.put("hasMore",rows.size()>limit);out.put("nextAfterId",rows.size()>limit?items.getLast().get("dentistId"):null);OrganizationRules.size(out);db.audit(actor.id(),"牙医人员列表读取","org_dentist",actor.id());return Store.json(out);
    }
    @Transactional
    public Object dentist(Identity.Actor actor,long id){
        OrganizationRules.chainOnly(actor);var row=db.one("SELECT "+DENTIST+" FROM dentists WHERE dentist_id=?",id);long count=count("org_dentist",id);var summary=OrganizationRules.dentistSummary(row,count);var detail=dentistDetail(row,count);db.audit(actor.id(),"牙医人员读取","org_dentist",id,null,null,Map.of("summary",summary));return Store.json(Map.of("resource",detail,"snapshot",detail));
    }
    @Transactional
    public Object createClinic(Identity.Actor actor,OrganizationApi.ClinicCreate input){
        long id=Store.parseId(input.clinicId());var normalized=new LinkedHashMap<String,Object>();normalized.put("clinicId",Long.toString(id));normalized.put("clinicCode",OrganizationRules.label(input.clinicCode(),32));normalized.put("name",OrganizationRules.label(input.name(),100));normalized.put("postalAddress",OrganizationRules.label(input.postalAddress(),255));normalized.put("managerName",OrganizationRules.label(input.managerName(),80));normalized.put("phone",OrganizationRules.label(input.phone(),32));normalized.put("email",OrganizationRules.label(input.email(),254));normalized.put("areaM2",OrganizationRules.area(input.areaM2()));OrganizationRules.size(normalized);String hash=fingerprint("org_clinic.create.v1",normalized);
        var found=db.jdbc.queryForList("SELECT "+CLINIC+" FROM clinics WHERE clinic_id=?",id);if(!found.isEmpty())db.one("SELECT clinic_id FROM clinics WHERE clinic_id=? FOR UPDATE",id);
        var current=lockOperator(actor.id());if(found.isEmpty()&&!db.jdbc.queryForList("SELECT clinic_id FROM clinics WHERE clinic_id=?",id).isEmpty())throw ApiError.conflict("RETRY_TRANSACTION");
        if(!found.isEmpty())return fixed("org_clinic",id,"门店创建",actor.id(),hash);
        OrganizationRules.chainOnly(current);
        try{db.jdbc.update("INSERT INTO clinics(clinic_id,clinic_code,name,postal_address,manager_name,phone,email,area_m2,active) VALUES(?,?,?,?,?,?,?,?,1)",id,normalized.get("clinicCode"),normalized.get("name"),normalized.get("postalAddress"),normalized.get("managerName"),normalized.get("phone"),normalized.get("email"),new BigDecimal((String)normalized.get("areaM2")));}catch(DataIntegrityViolationException e){throw ApiError.conflict("IDEMPOTENCY_CONFLICT");}
        var row=db.one("SELECT "+CLINIC+" FROM clinics WHERE clinic_id=?",id);var summary=OrganizationRules.clinicSummary(row,count("org_clinic",id)+1);return created(actor.id(),"org_clinic",id,"门店创建",hash,summary);
    }
    @Transactional
    public Object createDentist(Identity.Actor actor,OrganizationApi.DentistCreate input){
        long id=Store.parseId(input.dentistId());var normalized=new LinkedHashMap<String,Object>();normalized.put("dentistId",Long.toString(id));normalized.put("employeeNo",OrganizationRules.label(input.employeeNo(),32));normalized.put("name",OrganizationRules.label(input.name(),80));normalized.put("birthDate",OrganizationRules.birthDate(input.birthDate()));normalized.put("homeAddress",OrganizationRules.label(input.homeAddress(),255));normalized.put("phone",OrganizationRules.label(input.phone(),32));normalized.put("email",OrganizationRules.label(input.email(),254));OrganizationRules.size(normalized);String hash=fingerprint("org_dentist.create.v1",normalized);
        var found=db.jdbc.queryForList("SELECT dentist_id FROM dentists WHERE dentist_id=?",id);if(!found.isEmpty())db.one("SELECT dentist_id FROM dentists WHERE dentist_id=? FOR UPDATE",id);
        var current=lockOperator(actor.id());if(found.isEmpty()&&!db.jdbc.queryForList("SELECT dentist_id FROM dentists WHERE dentist_id=?",id).isEmpty())throw ApiError.conflict("RETRY_TRANSACTION");
        if(!found.isEmpty())return fixed("org_dentist",id,"牙医人员创建",actor.id(),hash);
        OrganizationRules.chainOnly(current);
        try{db.jdbc.update("INSERT INTO dentists(dentist_id,employee_no,name,birth_date,home_address,phone,email,active) VALUES(?,?,?,?,?,?,?,1)",id,normalized.get("employeeNo"),normalized.get("name"),Date.valueOf((String)normalized.get("birthDate")),normalized.get("homeAddress"),normalized.get("phone"),normalized.get("email"));}catch(DataIntegrityViolationException e){throw ApiError.conflict("IDEMPOTENCY_CONFLICT");}
        var row=db.one("SELECT "+DENTIST+" FROM dentists WHERE dentist_id=?",id);var summary=OrganizationRules.dentistSummary(row,count("org_dentist",id)+1);return created(actor.id(),"org_dentist",id,"牙医人员创建",hash,summary);
    }
    @Transactional
    public Object openings(Identity.Actor actor,long clinic,String afterText,Integer requested){
        OrganizationRules.clinicRead(actor,clinic);db.one("SELECT clinic_id FROM clinics WHERE clinic_id=?",clinic);
        long after=after(afterText);int limit=limit(requested);
        var rows=db.jdbc.queryForList("SELECT "+OPENING+" FROM clinic_openings WHERE clinic_id=? AND opening_id>? ORDER BY opening_id LIMIT ?",clinic,after,limit+1);
        var items=new ArrayList<Map<String,Object>>();for(var row:rows.stream().limit(limit).toList())items.add(OpeningRules.snapshot(row,count("org_opening",Store.number(row,"opening_id"))));
        var out=new LinkedHashMap<String,Object>();out.put("items",items);out.put("hasMore",rows.size()>limit);out.put("nextAfterId",rows.size()>limit?items.getLast().get("openingId"):null);OrganizationRules.size(out);
        db.audit(actor.id(),"实际开放列表读取","org_clinic",clinic);return Store.json(out);
    }
    @Transactional
    public Object opening(Identity.Actor actor,long id){
        var candidate=db.one("SELECT opening_id,clinic_id FROM clinic_openings WHERE opening_id=?",id);long clinic=Store.number(candidate,"clinic_id");OrganizationRules.clinicRead(actor,clinic);
        var row=db.one("SELECT "+OPENING+" FROM clinic_openings WHERE opening_id=?",id);if(Store.number(row,"clinic_id")!=clinic)throw ApiError.conflict("RETRY_TRANSACTION");
        var result=OpeningRules.snapshot(row,count("org_opening",id));var body=Map.of("resource",result,"snapshot",result);OrganizationRules.size(body);db.audit(actor.id(),"实际开放读取","org_opening",id);return Store.json(body);
    }
    @Transactional
    public Object createOpening(Identity.Actor actor,long clinic,OrganizationApi.OpeningCreate input){
        long id=Store.parseId(input.openingId());var window=OpeningRules.window(input.startAt(),input.endAt());String note=OpeningRules.note(input.note());
        var normalized=new LinkedHashMap<String,Object>();normalized.put("openingId",Long.toString(id));normalized.put("clinicId",Long.toString(clinic));normalized.put("startAt",window.start().toInstant().toString());normalized.put("endAt",window.end().toInstant().toString());normalized.put("note",note);OrganizationRules.size(normalized);
        String hash=fingerprint("org_opening.create.v1",normalized);
        var found=db.jdbc.queryForList("SELECT opening_id,clinic_id FROM clinic_openings WHERE opening_id=?",id);
        long parent=found.isEmpty()?clinic:Store.number(found.getFirst(),"clinic_id");
        var clinicRow=db.one("SELECT clinic_id,active FROM clinics WHERE clinic_id=? FOR UPDATE",parent);
        if(!found.isEmpty()){var locked=db.one("SELECT opening_id,clinic_id FROM clinic_openings WHERE opening_id=? FOR UPDATE",id);if(Store.number(locked,"clinic_id")!=parent)throw ApiError.conflict("RETRY_TRANSACTION");}
        else if(!db.jdbc.queryForList("SELECT opening_id FROM clinic_openings WHERE opening_id=?",id).isEmpty())throw ApiError.conflict("RETRY_TRANSACTION");
        var current=lockOperator(actor.id());
        if(!found.isEmpty())return fixed("org_opening",id,"实际开放创建",actor.id(),hash);
        OrganizationRules.clinicRead(current,clinic);if(!Store.enabled(clinicRow,"active"))throw ApiError.conflict("CLINIC_INACTIVE");
        OpeningRules.future(window,db.clock.instant());
        var overlap=db.jdbc.queryForList("SELECT opening_id FROM clinic_openings WHERE clinic_id=? AND status='有效' AND start_at<? AND end_at>? LIMIT 1",clinic,window.end(),window.start());
        if(!overlap.isEmpty())throw ApiError.conflict("OPENING_OVERLAP");
        try{db.jdbc.update("INSERT INTO clinic_openings(opening_id,clinic_id,start_at,end_at,status,note,updated_by,updated_at) VALUES(?,?,?,?,'有效',?,?,?)",id,clinic,window.start(),window.end(),note,actor.id(),db.now());}catch(DataIntegrityViolationException e){throw ApiError.conflict("IDEMPOTENCY_CONFLICT");}
        var row=db.one("SELECT "+OPENING+" FROM clinic_openings WHERE opening_id=?",id);var snapshot=OpeningRules.snapshot(row,count("org_opening",id)+1);
        return created(actor.id(),"org_opening",id,"实际开放创建",hash,snapshot);
    }
    @Transactional
    public Object schedules(Identity.Actor actor,long clinic,String afterText,Integer requested){
        OrganizationRules.clinicRead(actor,clinic);db.one("SELECT clinic_id FROM clinics WHERE clinic_id=?",clinic);
        long after=after(afterText);int limit=limit(requested);
        var rows=db.jdbc.queryForList("SELECT "+SCHEDULE+" FROM dentist_schedules WHERE clinic_id=? AND schedule_id>? ORDER BY schedule_id LIMIT ?",clinic,after,limit+1);
        var items=new ArrayList<Map<String,Object>>();for(var row:rows.stream().limit(limit).toList())items.add(ScheduleRules.snapshot(row,count("org_schedule",Store.number(row,"schedule_id"))));
        var out=new LinkedHashMap<String,Object>();out.put("items",items);out.put("hasMore",rows.size()>limit);out.put("nextAfterId",rows.size()>limit?items.getLast().get("scheduleId"):null);OrganizationRules.size(out);
        db.audit(actor.id(),"既定出诊列表读取","org_clinic",clinic);return Store.json(out);
    }
    @Transactional
    public Object schedule(Identity.Actor actor,long id){
        var candidate=db.one("SELECT schedule_id,clinic_id FROM dentist_schedules WHERE schedule_id=?",id);long clinic=Store.number(candidate,"clinic_id");OrganizationRules.clinicRead(actor,clinic);
        var row=db.one("SELECT "+SCHEDULE+" FROM dentist_schedules WHERE schedule_id=?",id);if(Store.number(row,"clinic_id")!=clinic)throw ApiError.conflict("RETRY_TRANSACTION");
        var snapshot=ScheduleRules.snapshot(row,count("org_schedule",id));var body=Map.of("resource",snapshot,"snapshot",snapshot);OrganizationRules.size(body);
        db.audit(actor.id(),"既定出诊读取","org_schedule",id);return Store.json(body);
    }
    @Transactional
    public Object createSchedule(Identity.Actor actor,long clinic,OrganizationApi.ScheduleCreate input){
        long id=Store.parseId(input.scheduleId()),dentist=Store.parseId(input.dentistId()),opening=Store.parseId(input.openingId());
        var window=OpeningRules.window(input.startAt(),input.endAt());var normalized=new LinkedHashMap<String,Object>();
        normalized.put("scheduleId",Long.toString(id));normalized.put("clinicId",Long.toString(clinic));normalized.put("dentistId",Long.toString(dentist));normalized.put("openingId",Long.toString(opening));
        normalized.put("startAt",window.start().toInstant().toString());normalized.put("endAt",window.end().toInstant().toString());OrganizationRules.size(normalized);
        String hash=fingerprint("org_schedule.create.v1",normalized);
        var found=db.jdbc.queryForList("SELECT schedule_id,clinic_id,dentist_id,opening_id FROM dentist_schedules WHERE schedule_id=?",id);
        long parentClinic=found.isEmpty()?clinic:Store.number(found.getFirst(),"clinic_id");
        long parentDentist=found.isEmpty()?dentist:Store.number(found.getFirst(),"dentist_id");
        long parentOpening=found.isEmpty()?opening:Store.number(found.getFirst(),"opening_id");
        if(found.isEmpty()){var openingCandidate=db.one("SELECT opening_id,clinic_id FROM clinic_openings WHERE opening_id=?",opening);if(Store.number(openingCandidate,"clinic_id")!=clinic)throw ApiError.conflict("OUTSIDE_COVERAGE");}
        var clinicRow=db.one("SELECT clinic_id,active FROM clinics WHERE clinic_id=? FOR UPDATE",parentClinic);
        var dentistRow=db.one("SELECT dentist_id,active FROM dentists WHERE dentist_id=? FOR UPDATE",parentDentist);
        var openingRow=db.one("SELECT "+OPENING+" FROM clinic_openings WHERE opening_id=? FOR UPDATE",parentOpening);
        if(Store.number(openingRow,"clinic_id")!=parentClinic)throw ApiError.conflict("RETRY_TRANSACTION");
        if(!found.isEmpty()){
            var locked=db.one("SELECT schedule_id,clinic_id,dentist_id,opening_id FROM dentist_schedules WHERE schedule_id=? FOR UPDATE",id);
            if(Store.number(locked,"clinic_id")!=parentClinic||Store.number(locked,"dentist_id")!=parentDentist||Store.number(locked,"opening_id")!=parentOpening)throw ApiError.conflict("RETRY_TRANSACTION");
        }else if(!db.jdbc.queryForList("SELECT schedule_id FROM dentist_schedules WHERE schedule_id=?",id).isEmpty())throw ApiError.conflict("RETRY_TRANSACTION");
        var current=lockOperator(actor.id());
        if(!found.isEmpty())return fixed("org_schedule",id,"既定出诊创建",actor.id(),hash);
        OrganizationRules.clinicRead(current,clinic);if(!Store.enabled(clinicRow,"active")||!Store.enabled(dentistRow,"active"))throw ApiError.conflict("RESOURCE_INACTIVE");
        ScheduleRules.future(window,db.clock.instant());ScheduleRules.covered(openingRow,clinic,window);
        var overlap=db.jdbc.queryForList("SELECT schedule_id FROM dentist_schedules WHERE dentist_id=? AND status='有效' AND start_at<? AND end_at>? LIMIT 1",dentist,window.end(),window.start());
        if(!overlap.isEmpty())throw ApiError.conflict("SCHEDULE_OVERLAP");
        try{db.jdbc.update("INSERT INTO dentist_schedules(schedule_id,dentist_id,clinic_id,opening_id,start_at,end_at,status,row_version) VALUES(?,?,?,?,?,?,'有效',0)",id,dentist,clinic,opening,window.start(),window.end());}catch(DataIntegrityViolationException e){throw ApiError.conflict("IDEMPOTENCY_CONFLICT");}
        var row=db.one("SELECT "+SCHEDULE+" FROM dentist_schedules WHERE schedule_id=?",id);var snapshot=ScheduleRules.snapshot(row,count("org_schedule",id)+1);
        return created(actor.id(),"org_schedule",id,"既定出诊创建",hash,snapshot);
    }
}
