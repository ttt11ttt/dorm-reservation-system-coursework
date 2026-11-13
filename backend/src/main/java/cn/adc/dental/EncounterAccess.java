package cn.adc.dental;

import java.util.*;
import org.springframework.stereotype.Component;

/** Common parent lock order for encounter and billing writes; never takes a detail lock first. */
@Component
class EncounterAccess {
    enum Mode { FRONT, DENTIST, PATIENT, STAFF_INVOICE }
    record Locked(Map<String,Object> appointment,Map<String,Object> patient,boolean resourcesActive,Map<String,Object> followupPlan,ReminderLifecycle.Chain reminderChain,ReminderFollowupContext.Candidate followupReminderClosure,List<Map<String,Object>> lockedAppointments,boolean planResourcesActive) {}
    static final String APPOINTMENT_COLUMNS=BookingService.COLUMNS+",attended,arrived_at,allergy_checked_version,allergy_snapshot,checked_by,checked_at,completed_at,completion_note";
    static final String TREATMENT_COLUMNS="treatment_id,appointment_id,treatment_type_id,created_at,performer_dentist_id,name_snapshot,unit_snapshot,units,unit_price,performed_at,status,confirmed_by,confirmed_at,note";
    private final Store db;
    private final FollowupHooks hooks;
    private final ReminderLifecycle lifecycle;
    private final ReminderFollowupContext reminderContexts;
    EncounterAccess(Store db,FollowupHooks hooks,ReminderLifecycle lifecycle,ReminderFollowupContext reminderContexts) { this.db=db;this.hooks=hooks;this.lifecycle=lifecycle;this.reminderContexts=reminderContexts; }
    void authorize(Identity.Actor actor,Map<String,Object> a,Mode mode) {
        long clinic=Store.number(a,"clinic_id"),dentist=Store.number(a,"dentist_id");
        switch(mode) {
            case FRONT -> actor.front(clinic);
            case DENTIST -> actor.dentist(clinic,dentist);
            case PATIENT -> { actor.patient(); if(actor.patientId()!=Store.number(a,"patient_id"))throw ApiError.denied(); }
            case STAFF_INVOICE -> {
                if(actor.hasRole("前台",clinic))actor.front(clinic);
                else actor.dentist(clinic,dentist);
            }
        }
    }
    Locked lock(Identity.Actor actor,long id,Mode mode) {
        return lock(actor,id,mode,false);
    }
    Locked lockLifecycle(Identity.Actor actor,long id,Mode mode){return lock(actor,id,mode,true);}
    private Locked lock(Identity.Actor actor,long id,Mode mode,boolean reminders) {
        var candidate=db.one("SELECT clinic_id,dentist_id,patient_id,followup_plan_id FROM appointments WHERE appointment_id=?",id);
        authorize(actor,candidate,mode);
        var reminderChain=reminders?lifecycle.candidate(id):null;
        var planCandidate=candidate.get("followup_plan_id")==null?null:hooks.candidate(Store.number(candidate,"followup_plan_id"));
        var reminderClosure=reminders&&planCandidate!=null?reminderContexts.candidate(planCandidate.id()):null;
        if(planCandidate!=null&&(planCandidate.owner().patient()!=Store.number(candidate,"patient_id")||planCandidate.owner().source()==id))throw ApiError.conflict("FOLLOWUP_SOURCE_INVALID");
        long clinic=Store.number(candidate,"clinic_id"),dentist=Store.number(candidate,"dentist_id");
        var clinics=new ArrayList<>(planCandidate==null?List.of(clinic):List.of(clinic,planCandidate.owner().clinic()));var dentists=new ArrayList<>(planCandidate==null?List.of(dentist):List.of(dentist,planCandidate.owner().dentist()));if(reminderChain!=null){clinics.addAll(reminderChain.clinics());dentists.addAll(reminderChain.dentists());}
        if(reminderClosure!=null){clinics.addAll(reminderClosure.clinics());dentists.addAll(reminderClosure.dentists());}
        var resources=FollowupLinkRules.resources(clinics,dentists);var clinicActive=new HashMap<Long,Boolean>();var dentistActive=new HashMap<Long,Boolean>();
        for(long clinicId:resources.clinics()){var row=db.one("SELECT active FROM clinics WHERE clinic_id=? FOR UPDATE",clinicId);clinicActive.put(clinicId,Store.enabled(row,"active"));}
        for(long dentistId:resources.dentists()){var row=db.one("SELECT active FROM dentists WHERE dentist_id=? FOR UPDATE",dentistId);dentistActive.put(dentistId,Store.enabled(row,"active"));}
        var patient=db.one("SELECT patient_id,patient_no,name,birth_date,phone,allergy_declaration,allergy_version,active FROM patients WHERE patient_id=? FOR UPDATE",candidate.get("patient_id"));
        var followupPlan=planCandidate==null?null:hooks.lock(planCandidate);
        var appointmentArgs=new ArrayList<Object>(List.of(id,planCandidate==null?id:planCandidate.owner().source()));String extra=lifecycle.include(reminderChain,appointmentArgs);if(reminderClosure!=null)extra+=reminderContexts.include(reminderClosure,appointmentArgs);
        var appointments=db.jdbc.queryForList("SELECT "+APPOINTMENT_COLUMNS+",followup_plan_id,previous_appointment_id FROM appointments WHERE appointment_id IN (?,?)"+extra+" ORDER BY appointment_id FOR UPDATE",appointmentArgs.toArray());lifecycle.verify(reminderChain);if(reminderClosure!=null)reminderContexts.verify(reminderClosure,followupPlan,appointments);
        var a=appointments.stream().filter(row->Store.number(row,"appointment_id")==id).findFirst().orElseThrow(()->ApiError.conflict("RETRY_TRANSACTION"));
        for(String key:List.of("clinic_id","dentist_id","patient_id","followup_plan_id"))if(!Objects.equals(candidate.get(key),a.get(key)))throw ApiError.conflict("RETRY_TRANSACTION");
        if(followupPlan!=null){var source=appointments.stream().filter(row->Store.number(row,"appointment_id")==planCandidate.owner().source()).findFirst().orElseThrow(()->ApiError.conflict("FOLLOWUP_SOURCE_INVALID"));hooks.source(followupPlan,source);}else a.remove("followup_plan_id");
        authorize(actor,a,mode);
        boolean patientActive=Store.enabled(patient,"active");boolean focusActive=Boolean.TRUE.equals(clinicActive.get(clinic))&&Boolean.TRUE.equals(dentistActive.get(dentist))&&patientActive;
        boolean planActive=planCandidate!=null&&Boolean.TRUE.equals(clinicActive.get(planCandidate.owner().clinic()))&&Boolean.TRUE.equals(dentistActive.get(planCandidate.owner().dentist()))&&patientActive;
        return new Locked(a,patient,focusActive,followupPlan,reminderChain,reminderClosure,appointments,planActive);
    }
    void active(Locked c) { if(!c.resourcesActive())throw ApiError.conflict("RESOURCE_INACTIVE"); }
    void arrived(Map<String,Object> a) { if(!"已到诊".equals(a.get("appointment_status")) || !Store.enabled(a,"attended"))throw ApiError.conflict("INVALID_STATE"); }
    void unfinished(Map<String,Object> a) { if("已完成".equals(a.get("treatment_status")))throw ApiError.conflict("CORRECTION_REQUIRED"); }
    void version(Map<String,Object> a,Integer expected) { if(expected==null || expected<0)throw ApiError.bad(); if(Store.number(a,"row_version")!=expected)throw ApiError.conflict("ROW_VERSION_CONFLICT"); }
    void noInvoice(long id) { if(!db.jdbc.queryForList("SELECT invoice_id FROM invoices WHERE appointment_id=? FOR UPDATE",id).isEmpty())throw ApiError.conflict("CORRECTION_REQUIRED"); }
    List<Map<String,Object>> treatments(long id,boolean lock) { return db.jdbc.queryForList("SELECT "+TREATMENT_COLUMNS+" FROM treatments WHERE appointment_id=? ORDER BY treatment_id"+(lock?" FOR UPDATE":""),id); }
    List<Map<String,Object>> allergies(long patientId) { return db.jdbc.queryForList("SELECT substance,reaction_note,verification_status FROM patient_allergies WHERE patient_id=? AND active=1 ORDER BY substance",patientId); }
    Map<String,Object> invoiceSummary(long id) { var rows=db.jdbc.queryForList("SELECT invoice_id,status,total_amount FROM invoices WHERE appointment_id=?",id);return rows.isEmpty()?null:rows.getFirst(); }
    Map<String,Object> administrative(long id) {
        var result=new LinkedHashMap<String,Object>();
        result.put("appointment",db.one("SELECT "+BookingService.COLUMNS+",attended,arrived_at,completed_at FROM appointments WHERE appointment_id=?",id));result.put("invoice",invoiceSummary(id));return result;
    }
    Map<String,Object> clinical(long id) {
        var a=db.one("SELECT "+APPOINTMENT_COLUMNS+",followup_plan_id FROM appointments WHERE appointment_id=?",id);if(a.get("followup_plan_id")==null)a.remove("followup_plan_id");
        var result=new LinkedHashMap<String,Object>();result.put("appointment",a);
        result.put("patient",db.one("SELECT patient_id,patient_no,name,birth_date,allergy_declaration,allergy_version FROM patients WHERE patient_id=?",a.get("patient_id")));
        result.put("allergies",allergies(Store.number(a,"patient_id")));result.put("treatments",treatments(id,false));result.put("invoice",invoiceSummary(id));return result;
    }
    Object reply(long id,boolean replayed,boolean clinical) { return Store.json(Map.of("replayed",replayed,"encounter",clinical?clinical(id):administrative(id))); }
}
