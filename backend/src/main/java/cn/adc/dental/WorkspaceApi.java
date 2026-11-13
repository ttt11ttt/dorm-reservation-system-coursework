package cn.adc.dental;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;

/** Bounded read model for the approved dashboard. Never grants clinical mutation rights. */
@RestController
@RequestMapping("/api/v1/staff/workspace")
class WorkspaceApi {
    private final Store db;
    private final Identity identity;
    WorkspaceApi(Store db,Identity identity){this.db=db;this.identity=identity;}
    @GetMapping
    @Transactional
    Object read(@RequestHeader(value="Authorization",required=false) String header,@RequestParam(required=false) String clinicId){
        var actor=identity.require(header);
        if(!"员工".equals(actor.kind()))throw ApiError.denied();
        Long selected=clinicId==null||clinicId.equals("0")?null:Store.parseId(clinicId);
        var clinics=visibleClinics(actor,selected);
        var today=db.clock.instant().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
        var begin=Timestamp.from(today.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant());
        var end=Timestamp.from(today.plusDays(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant());
        long todayCount=0,guestToday=0,arrived=0,unbilled=0,pendingFollowups=0;
        var visits=new ArrayList<Map<String,Object>>();var plans=new ArrayList<Map<String,Object>>();var patients=new ArrayList<Map<String,Object>>();var dentists=new LinkedHashMap<Long,Map<String,Object>>();
        for(var c:clinics){long cid=Store.number(c,"clinic_id");boolean front=actor.hasRole("前台",cid),dentist=actor.dentistId()!=null&&actor.hasRole("牙医",cid);boolean operations=canReadStats(actor,cid);
            if(operations){
                todayCount+=db.jdbc.queryForObject("SELECT COUNT(*) FROM appointments WHERE clinic_id=? AND scheduled_start>=? AND scheduled_start<? AND appointment_status<>'误录作废'",Long.class,cid,begin,end);
                guestToday+=db.jdbc.queryForObject("SELECT COUNT(*) FROM guest_reservations WHERE clinic_id=? AND scheduled_start>=? AND scheduled_start<? AND reservation_status='待到诊'",Long.class,cid,begin,end);
                arrived+=db.jdbc.queryForObject("SELECT COUNT(*) FROM appointments WHERE clinic_id=? AND appointment_status='已到诊' AND treatment_status<>'已完成'",Long.class,cid);
                unbilled+=db.jdbc.queryForObject("SELECT COUNT(*) FROM appointments a WHERE a.clinic_id=? AND a.treatment_status='已完成' AND NOT EXISTS(SELECT 1 FROM invoices i WHERE i.appointment_id=a.appointment_id AND i.status='发出')",Long.class,cid);
                pendingFollowups+=db.jdbc.queryForObject("SELECT COUNT(*) FROM followup_plans WHERE clinic_id=? AND status='待复诊'",Long.class,cid);
            }
            if(front||dentist){
                String clinicalFilter=dentist&&!front?" AND a.dentist_id="+actor.dentistId():"";
                visits.addAll(db.jdbc.queryForList("SELECT a.appointment_id,a.appointment_no,a.patient_id,p.patient_no,p.name AS patient_name,a.dentist_id,d.name AS dentist_name,a.clinic_id,c.name AS clinic_name,a.scheduled_start,a.scheduled_end,a.booking_channel,a.appointment_status,a.treatment_status,a.row_version FROM appointments a JOIN patients p ON p.patient_id=a.patient_id JOIN dentists d ON d.dentist_id=a.dentist_id JOIN clinics c ON c.clinic_id=a.clinic_id WHERE a.clinic_id=?"+clinicalFilter+" ORDER BY a.scheduled_start DESC LIMIT 100",cid));
            }
            if(front){
                plans.addAll(db.jdbc.queryForList("SELECT f.followup_plan_id,f.patient_id,p.name AS patient_name,f.dentist_id,d.name AS dentist_name,f.clinic_id,c.name AS clinic_name,f.suggested_date,f.recommendation,f.status FROM followup_plans f JOIN patients p ON p.patient_id=f.patient_id JOIN dentists d ON d.dentist_id=f.dentist_id JOIN clinics c ON c.clinic_id=f.clinic_id WHERE f.clinic_id=? ORDER BY f.suggested_date LIMIT 100",cid));
                patients.addAll(db.jdbc.queryForList("SELECT DISTINCT p.patient_id,p.patient_no,p.name,p.allergy_declaration,p.active FROM patients p JOIN appointments a ON a.patient_id=p.patient_id WHERE a.clinic_id=? ORDER BY p.patient_id LIMIT 100",cid));
            }
            for(var d:db.jdbc.queryForList("SELECT DISTINCT d.dentist_id,d.name,pp.specialty_label,pp.professional_title,pp.expertise FROM dentists d JOIN dentist_schedules s ON s.dentist_id=d.dentist_id LEFT JOIN dentist_public_profiles pp ON pp.dentist_id=d.dentist_id WHERE s.clinic_id=? AND d.active=1 ORDER BY d.dentist_id LIMIT 100",cid))dentists.put(Store.number(d,"dentist_id"),d);
        }
        var out=new LinkedHashMap<String,Object>();out.put("today",today.toString());out.put("asOf",db.clock.instant().toString());out.put("clinics",clinics);out.put("stats",Map.of("today",todayCount,"guestToday",guestToday,"arrived",arrived,"unbilled",unbilled,"followups",pendingFollowups));out.put("visits",visits);out.put("followups",plans);out.put("patients",patients.stream().collect(java.util.stream.Collectors.toMap(p->Store.number(p,"patient_id"),p->p,(a,b)->a,LinkedHashMap::new)).values().stream().toList());out.put("dentists",new ArrayList<>(dentists.values()));out.put("projects",db.jdbc.queryForList("SELECT treatment_type_id,treatment_code,name,unit_name,standard_unit_price FROM treatment_types WHERE active=1 ORDER BY treatment_type_id LIMIT 100"));
        // Contact details and clinical notes are intentionally absent from this summary read model.
        db.audit(actor.id(),"读取","staff_workspace",selected==null?actor.id():selected);
        return Store.json(out);
    }

    @GetMapping("/statistics")
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    Object statistics(@RequestHeader(value="Authorization",required=false) String header,
                      @RequestParam(required=false) String clinicId,
                      @RequestParam String from, @RequestParam String to){
        var actor=identity.require(header);
        if(!"员工".equals(actor.kind()))throw ApiError.denied();
        Long selected=clinicId==null||clinicId.equals("0")?null:Store.parseId(clinicId);
        var clinics=visibleClinics(actor,selected).stream()
            .map(c->Store.number(c,"clinic_id")).filter(id->canReadStats(actor,id)).toList();
        if(clinics.isEmpty())throw ApiError.denied();
        return new WorkspaceStatistics(db).read(clinics,from,to);
    }

    private List<Map<String,Object>> visibleClinics(Identity.Actor actor,Long selected){
        var clinics=db.jdbc.queryForList("SELECT clinic_id,name,phone,postal_address FROM clinics WHERE active=1 ORDER BY clinic_id LIMIT 65");
        if(clinics.size()>64&&selected==null)throw ApiError.bad();
        clinics.removeIf(c->{long id=Store.number(c,"clinic_id");return selected!=null&&id!=selected||actor.grants().stream().noneMatch(g->actor.hasRole(String.valueOf(g.get("role_code")),id));});
        if(selected!=null&&clinics.isEmpty())throw ApiError.denied();
        return clinics;
    }

    private boolean canReadStats(Identity.Actor actor,long clinic){
        return actor.hasRole("运营人员／店长",clinic)||actor.hasRole("总部管理",clinic)||actor.hasRole("前台",clinic);
    }
}
