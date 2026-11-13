package cn.adc.dental;

import java.math.*;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class EncounterService {
    record AllergyItem(String substance,String reactionNote) {}
    record AllergyChange(Integer expectedRowVersion,Integer expectedAllergyVersion,String declaration,List<AllergyItem> items,String reason) {}
    record AllergyCheck(Integer expectedRowVersion,Integer allergyVersion) {}
    record TreatmentLine(String treatmentId,String treatmentTypeId,String units,String unitPrice,String performedAt,String status,String note) {}
    record TreatmentSet(Integer expectedRowVersion,Integer allergyVersion,List<TreatmentLine> lines) {}
    record VoidTreatment(Integer expectedRowVersion,Integer allergyVersion,String reason) {}
    record Completion(Integer expectedRowVersion,Integer allergyVersion,String completionNote) {}
    private final Store db;
    private final EncounterAccess access;
    private final FollowupHooks hooks;
    private final ReminderLifecycle lifecycle;
    private final ReminderLinkedMaintenance linkedReminders;
    EncounterService(Store db,EncounterAccess access,FollowupHooks hooks,ReminderLifecycle lifecycle,ReminderLinkedMaintenance linkedReminders) { this.db=db; this.access=access;this.hooks=hooks;this.lifecycle=lifecycle;this.linkedReminders=linkedReminders; }
    private void integer(Integer v) { if(v==null || v<0)throw ApiError.bad(); }
    private void bump(long id) { db.jdbc.update("UPDATE appointments SET row_version=row_version+1 WHERE appointment_id=?",id); }
    private void writable(EncounterAccess.Locked c,long id,Integer version) { access.active(c);access.arrived(c.appointment());access.unfinished(c.appointment());access.version(c.appointment(),version);access.noInvoice(id); }
    @Transactional
    public Object arrive(Identity.Actor actor,long id) {
        var c=access.lockLifecycle(actor,id,EncounterAccess.Mode.FRONT);var a=c.appointment();
        if("已到诊".equals(a.get("appointment_status"))&&Store.enabled(a,"attended"))return lifecycle.attach(access.reply(id,true,false),lifecycle.original(id,"到诊"));
        access.active(c);
        if(!Set.of("待到诊","待核实").contains(a.get("appointment_status")))throw ApiError.conflict("INVALID_STATE");
        db.jdbc.update("UPDATE appointments SET appointment_status='已到诊',attended=1,arrived_at=?,row_version=row_version+1 WHERE appointment_id=?",db.now(),id);
        Map<String,Object> outcome;if(c.followupPlan()==null)outcome=lifecycle.apply(actor,c.reminderChain(),id,null,null,true);
        else {var rows=new ArrayList<>(c.lockedAppointments());rows.removeIf(row->Store.number(row,"appointment_id")==id);rows.add(db.one("SELECT * FROM appointments WHERE appointment_id=?",id));outcome=linkedReminders.apply(actor,id,c.followupPlan(),rows,false,c.planResourcesActive(),(String)c.patient().get("phone")).outcome();}
        var after=new LinkedHashMap<String,Object>();after.put("appointment",access.administrative(id).get("appointment"));after.put("reminderOutcome",outcome);db.audit(actor.id(),"到诊","appointment",id,null,a,after);return lifecycle.attach(access.reply(id,false,false),outcome);
    }
    @Transactional
    public Object administrative(Identity.Actor actor,long id) { access.lock(actor,id,EncounterAccess.Mode.FRONT);db.audit(actor.id(),"读取","encounter_admin",id);return Store.json(access.administrative(id)); }
    @Transactional
    public Object context(Identity.Actor actor,long id) { access.lock(actor,id,EncounterAccess.Mode.DENTIST);db.audit(actor.id(),"读取","encounter",id);return Store.json(access.clinical(id)); }
    @Transactional
    public Object history(Identity.Actor actor,long id,String beforeId){
        var context=access.lock(actor,id,EncounterAccess.Mode.DENTIST);
        access.arrived(context.appointment());
        long patient=Store.number(context.patient(),"patient_id");
        var args=new ArrayList<Object>(List.of(patient,id));
        String cursor="";
        if(beforeId!=null){
            long previous=Store.parseId(beforeId);
            var anchor=db.one("SELECT completed_at FROM appointments WHERE appointment_id=? AND patient_id=? AND treatment_status='已完成'",previous,patient);
            cursor=" AND (a.completed_at<? OR (a.completed_at=? AND a.appointment_id<?))";
            args.add(anchor.get("completed_at"));args.add(anchor.get("completed_at"));args.add(previous);
        }
        var visits=db.jdbc.queryForList("""
            SELECT a.appointment_id,a.appointment_no,a.completed_at,a.completion_note,c.name AS clinic_name,d.name AS dentist_name
            FROM appointments a JOIN clinics c ON c.clinic_id=a.clinic_id JOIN dentists d ON d.dentist_id=a.dentist_id
            WHERE a.patient_id=? AND a.appointment_id<>? AND a.treatment_status='已完成'
            """+cursor+" ORDER BY a.completed_at DESC,a.appointment_id DESC LIMIT 21",args.toArray());
        var items=visits.stream().limit(20).toList();
        for(var visit:items){
            var lines=db.jdbc.queryForList("""
                SELECT treatment_id,name_snapshot,unit_snapshot,units,unit_price,note
                FROM treatments WHERE appointment_id=? AND status='确认' ORDER BY treatment_id
                """,visit.get("appointment_id"));
            visit.put("treatments",lines);
        }
        db.audit(actor.id(),"查看本人接诊患者既往治疗","patient_history",patient);
        var result=new LinkedHashMap<String,Object>();
        result.put("items",items);
        result.put("nextBeforeId",visits.size()>20?items.getLast().get("appointment_id"):null);
        return Store.json(result);
    }
    @Transactional
    public Object worklist(Identity.Actor actor,long clinic,String from,String to) {
        actor.dentist(clinic,Objects.requireNonNullElse(actor.dentistId(),-1L));
        Timestamp start=Store.offset(from),end=Store.offset(to);
        if(!end.after(start)||Duration.between(start.toInstant(),end.toInstant()).compareTo(Duration.ofDays(31))>0)throw ApiError.bad();
        var rows=db.jdbc.queryForList("SELECT a.appointment_id,a.appointment_no,a.patient_id,a.clinic_id,a.dentist_id,a.schedule_id,a.scheduled_start,a.scheduled_end,a.booking_channel,a.requested_treatments,a.special_needs,a.appointment_status,a.treatment_status,a.row_version,p.patient_no,p.name AS patient_name,a.arrived_at,a.completed_at FROM appointments a JOIN patients p ON p.patient_id=a.patient_id WHERE a.clinic_id=? AND a.scheduled_start>=? AND a.scheduled_start<? ORDER BY a.scheduled_start LIMIT 100",clinic,start,end);
        db.audit(actor.id(),"读取","dentist_worklist",clinic);return Store.json(rows);
    }
    Object catalogue(Identity.Actor actor,long clinic) {
        actor.dentist(clinic,Objects.requireNonNullElse(actor.dentistId(),-1L));
        return Store.json(db.jdbc.queryForList("SELECT treatment_type_id,treatment_code,name,unit_name,standard_unit_price,allows_fraction FROM treatment_types WHERE active=1 ORDER BY treatment_type_id"));
    }
    static List<Map<String,Object>> normalized(List<AllergyItem> items,String declaration) {
        if(items==null || items.size()>50 || !Set.of("无已知过敏","已报告过敏").contains(Objects.toString(declaration,"")))throw ApiError.bad();
        if(items.isEmpty()!=declaration.equals("无已知过敏"))throw ApiError.bad();
        var result=new ArrayList<Map<String,Object>>();var seen=new HashSet<String>();
        for(var item:items) {
            if(item==null)throw ApiError.bad();String substance=Store.required(item.substance(),100).trim().toLowerCase(Locale.ROOT);
            if(!seen.add(substance))throw ApiError.bad();if(item.reactionNote()!=null && item.reactionNote().length()>255)throw ApiError.bad();
            var value=new LinkedHashMap<String,Object>();value.put("substance",substance);value.put("reaction_note",item.reactionNote());result.add(value);
        }
        result.sort(Comparator.comparing(x->x.get("substance").toString()));return result;
    }
    @Transactional
    public Object allergies(Identity.Actor actor,long id,AllergyChange change) {
        integer(change.expectedRowVersion());integer(change.expectedAllergyVersion());Store.required(change.reason(),8000);
        var requested=normalized(change.items(),change.declaration());var c=access.lock(actor,id,EncounterAccess.Mode.DENTIST);access.arrived(c.appointment());access.unfinished(c.appointment());
        var beforeItems=access.allergies(Store.number(c.patient(),"patient_id"));
        var current=beforeItems.stream().map(x->{var m=new LinkedHashMap<String,Object>();m.put("substance",x.get("substance").toString().trim().toLowerCase(Locale.ROOT));m.put("reaction_note",x.get("reaction_note"));return m;}).sorted(Comparator.comparing(x->x.get("substance").toString())).toList();
        if(change.declaration().equals(c.patient().get("allergy_declaration"))&&current.equals(requested))return access.reply(id,true,true);
        writable(c,id,change.expectedRowVersion());
        if(Store.number(c.patient(),"allergy_version")!=change.expectedAllergyVersion())throw ApiError.conflict("ALLERGY_VERSION_CONFLICT");
        long patient=Store.number(c.patient(),"patient_id");
        db.jdbc.update("UPDATE patient_allergies SET active=0,updated_at=? WHERE patient_id=? AND active=1",db.now(),patient);
        for(var item:requested)db.jdbc.update("INSERT INTO patient_allergies(allergy_id,patient_id,substance,reaction_note,active,verification_status,verified_by,verified_at,updated_at) VALUES(?,?,?,?,1,'已核实',?,?,?)",db.id(),patient,item.get("substance"),item.get("reaction_note"),actor.id(),db.now(),db.now());
        db.jdbc.update("UPDATE patients SET allergy_declaration=?,allergy_version=allergy_version+1,row_version=row_version+1 WHERE patient_id=?",change.declaration(),patient);bump(id);
        db.audit(actor.id(),"过敏更正","patient",patient,change.reason(),Map.of("patient",c.patient(),"items",beforeItems),Map.of("declaration",change.declaration(),"version",Store.number(c.patient(),"allergy_version")+1,"items",requested));
        return access.reply(id,false,true);
    }
    private void declared(EncounterAccess.Locked c) {
        var items=access.allergies(Store.number(c.patient(),"patient_id"));String declaration=c.patient().get("allergy_declaration").toString();
        if(declaration.equals("未询问") || declaration.equals("无已知过敏")&&!items.isEmpty() || declaration.equals("已报告过敏")&&items.isEmpty())throw ApiError.conflict("ALLERGY_UNRESOLVED");
    }
    private void checked(EncounterAccess.Locked c,Integer allergyVersion) {
        integer(allergyVersion);declared(c);
        if(Store.number(c.patient(),"allergy_version")!=allergyVersion)throw ApiError.conflict("ALLERGY_VERSION_CONFLICT");
        if(c.appointment().get("allergy_checked_version")==null || Store.number(c.appointment(),"allergy_checked_version")!=allergyVersion)throw ApiError.conflict("ALLERGY_RECHECK_REQUIRED");
    }
    @Transactional
    public Object check(Identity.Actor actor,long id,AllergyCheck check) {
        integer(check.expectedRowVersion());integer(check.allergyVersion());var c=access.lock(actor,id,EncounterAccess.Mode.DENTIST);access.arrived(c.appointment());access.unfinished(c.appointment());declared(c);
        if(Store.number(c.patient(),"allergy_version")!=check.allergyVersion())throw ApiError.conflict("ALLERGY_VERSION_CONFLICT");
        if(c.appointment().get("allergy_checked_version")!=null && Store.number(c.appointment(),"allergy_checked_version")==check.allergyVersion() && Objects.equals(c.appointment().get("checked_by"),actor.id()))return access.reply(id,true,true);
        writable(c,id,check.expectedRowVersion());
        String snapshot=Store.encode(Map.of("declaration",c.patient().get("allergy_declaration"),"version",check.allergyVersion(),"items",access.allergies(Store.number(c.patient(),"patient_id"))));
        db.jdbc.update("UPDATE appointments SET allergy_checked_version=?,allergy_snapshot=?,checked_by=?,checked_at=?,row_version=row_version+1 WHERE appointment_id=?",check.allergyVersion(),snapshot,actor.id(),db.now(),id);
        db.audit(actor.id(),"过敏核对","appointment",id,null,c.appointment(),db.one("SELECT "+EncounterAccess.APPOINTMENT_COLUMNS+" FROM appointments WHERE appointment_id=?",id));return access.reply(id,false,true);
    }
    static BigDecimal decimal(String value,int precision,boolean positive) {
        if(value==null || !value.matches("[0-9]{1,"+(precision-2)+"}(\\.[0-9]{1,2})?"))throw ApiError.bad();
        BigDecimal result=new BigDecimal(value).setScale(2);if(positive&&result.signum()<=0)throw ApiError.bad();return result;
    }
    private boolean same(Map<String,Object> existing,TreatmentLine line) {
        return Store.number(existing,"treatment_type_id")==Store.parseId(line.treatmentTypeId()) && ((BigDecimal)existing.get("units")).compareTo(decimal(line.units(),10,true))==0 && ((BigDecimal)existing.get("unit_price")).compareTo(decimal(line.unitPrice(),12,false))==0
            && existing.get("performed_at").equals(Store.offset(line.performedAt())) && Objects.equals(existing.get("note"),line.note()) && existing.get("status").equals(line.status());
    }
    @Transactional
    public Object treatments(Identity.Actor actor,long id,TreatmentSet set) {
        integer(set.expectedRowVersion());integer(set.allergyVersion());
        if(set.lines()==null||set.lines().size()>100)throw ApiError.bad();var ids=new HashSet<Long>();
        for(var line:set.lines()) {
            if(line==null || !ids.add(Store.parseId(line.treatmentId())) || !Set.of("草稿","确认").contains(Objects.toString(line.status(),"")))throw ApiError.bad();
            Store.parseId(line.treatmentTypeId());decimal(line.units(),10,true);decimal(line.unitPrice(),12,false);Store.offset(line.performedAt());if(line.note()!=null&&line.note().length()>8000)throw ApiError.bad();
        }
        var c=access.lock(actor,id,EncounterAccess.Mode.DENTIST);access.arrived(c.appointment());
        db.jdbc.queryForList("SELECT invoice_id FROM invoices WHERE appointment_id=? FOR UPDATE",id);
        var all=access.treatments(id,true);var current=new HashMap<Long,Map<String,Object>>();
        for(var line:all)if(!"作废".equals(line.get("status")))current.put(Store.number(line,"treatment_id"),line);
        if(!ids.containsAll(current.keySet()))throw ApiError.conflict("TREATMENT_SET_CONFLICT");
        boolean unchanged=ids.equals(current.keySet())&&set.lines().stream().allMatch(line->same(current.get(Store.parseId(line.treatmentId())),line));
        if(unchanged) {
            if("已完成".equals(c.appointment().get("treatment_status"))) { if(c.appointment().get("allergy_checked_version")==null || Store.number(c.appointment(),"allergy_checked_version")!=set.allergyVersion())throw ApiError.conflict("ALLERGY_VERSION_CONFLICT"); }
            else checked(c,set.allergyVersion());
            return access.reply(id,true,true);
        }
        writable(c,id,set.expectedRowVersion());checked(c,set.allergyVersion());
        for(var line:set.lines()) {
            long treatmentId=Store.parseId(line.treatmentId());var existing=current.get(treatmentId);
            if(existing!=null && same(existing,line))continue;
            if(existing!=null&&"确认".equals(existing.get("status")))throw ApiError.conflict("TREATMENT_IMMUTABLE");
            if(existing==null&&!db.jdbc.queryForList("SELECT treatment_id FROM treatments WHERE treatment_id=?",treatmentId).isEmpty())throw ApiError.conflict("TREATMENT_SET_CONFLICT");
            var type=db.one("SELECT name,unit_name,allows_fraction,active FROM treatment_types WHERE treatment_type_id=?",Store.parseId(line.treatmentTypeId()));
            if(!Store.enabled(type,"active"))throw ApiError.conflict("RESOURCE_INACTIVE");
            BigDecimal units=decimal(line.units(),10,true),price=decimal(line.unitPrice(),12,false);
            if(!Store.enabled(type,"allows_fraction") && units.stripTrailingZeros().scale()>0)throw ApiError.bad();
            Timestamp performed=Store.offset(line.performedAt());if(performed.before((Timestamp)c.appointment().get("arrived_at"))||performed.after(db.now()))throw ApiError.bad();
            boolean confirmed="确认".equals(line.status());
            if(existing==null)db.jdbc.update("INSERT INTO treatments(treatment_id,appointment_id,treatment_type_id,created_at,performer_dentist_id,name_snapshot,unit_snapshot,units,unit_price,performed_at,status,confirmed_by,confirmed_at,note) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",treatmentId,id,Store.parseId(line.treatmentTypeId()),db.now(),Store.number(c.appointment(),"dentist_id"),type.get("name"),type.get("unit_name"),units,price,performed,line.status(),confirmed?actor.id():null,confirmed?db.now():null,line.note());
            else db.jdbc.update("UPDATE treatments SET treatment_type_id=?,name_snapshot=?,unit_snapshot=?,units=?,unit_price=?,performed_at=?,status=?,confirmed_by=?,confirmed_at=?,note=? WHERE treatment_id=? AND appointment_id=?",Store.parseId(line.treatmentTypeId()),type.get("name"),type.get("unit_name"),units,price,performed,line.status(),confirmed?actor.id():null,confirmed?db.now():null,line.note(),treatmentId,id);
            db.audit(actor.id(),confirmed?"确认":"草稿","treatment",treatmentId,null,existing,db.one("SELECT "+EncounterAccess.TREATMENT_COLUMNS+" FROM treatments WHERE treatment_id=?",treatmentId));
        }
        db.jdbc.update("UPDATE appointments SET treatment_status='进行中',row_version=row_version+1 WHERE appointment_id=?",id);return access.reply(id,false,true);
    }
    @Transactional
    public Object voidTreatment(Identity.Actor actor,long id,long treatmentId,VoidTreatment request) {
        integer(request.expectedRowVersion());integer(request.allergyVersion());Store.required(request.reason(),8000);
        var c=access.lock(actor,id,EncounterAccess.Mode.DENTIST);access.arrived(c.appointment());access.unfinished(c.appointment());access.noInvoice(id);
        var line=db.one("SELECT "+EncounterAccess.TREATMENT_COLUMNS+" FROM treatments WHERE treatment_id=? AND appointment_id=? FOR UPDATE",treatmentId,id);
        if("作废".equals(line.get("status"))) {
            var reasons=db.jdbc.queryForList("SELECT reason FROM audit_logs WHERE object_type='treatment' AND object_id=? AND action='作废' ORDER BY happened_at DESC,audit_id DESC LIMIT 1",Long.toString(treatmentId));
            if(reasons.isEmpty()||!Objects.equals(reasons.getFirst().get("reason"),request.reason()))throw ApiError.conflict("IDEMPOTENCY_CONFLICT");return access.reply(id,true,true);
        }
        access.active(c);access.version(c.appointment(),request.expectedRowVersion());checked(c,request.allergyVersion());
        db.jdbc.update("UPDATE treatments SET status='作废' WHERE treatment_id=?",treatmentId);bump(id);
        db.audit(actor.id(),"作废","treatment",treatmentId,request.reason(),line,db.one("SELECT "+EncounterAccess.TREATMENT_COLUMNS+" FROM treatments WHERE treatment_id=?",treatmentId));return access.reply(id,false,true);
    }
    static BigDecimal total(List<Map<String,Object>> lines,long dentist,long signer) {
        BigDecimal total=BigDecimal.ZERO.setScale(2);
        for(var line:lines) {
            if("草稿".equals(line.get("status")))throw ApiError.conflict("DRAFTS_REMAIN");
            if(!"确认".equals(line.get("status")))continue;
            if(Store.number(line,"performer_dentist_id")!=dentist || line.get("confirmed_by")==null)throw ApiError.conflict("INVALID_STATE");
            BigDecimal subtotal=((BigDecimal)line.get("units")).multiply((BigDecimal)line.get("unit_price")).setScale(2,RoundingMode.HALF_UP);
            if(subtotal.precision()>14)throw ApiError.bad();total=total.add(subtotal);
        }
        if(total.precision()>14)throw ApiError.bad();return total;
    }
    @Transactional
    public Object complete(Identity.Actor actor,long id,Completion request) {
        integer(request.expectedRowVersion());integer(request.allergyVersion());Store.required(request.completionNote(),8000);
        var c=access.lockLifecycle(actor,id,EncounterAccess.Mode.DENTIST);var a=c.appointment();access.arrived(a);
        if("已完成".equals(a.get("treatment_status"))) {
            if(!request.completionNote().equals(a.get("completion_note")) || a.get("allergy_checked_version")==null || Store.number(a,"allergy_checked_version")!=request.allergyVersion())throw ApiError.conflict("COMPLETION_CONFLICT");
            Object followup=null;if(c.followupPlan()!=null){followup=lifecycle.originalDetail(id,"诊疗完成","followup");if(followup==null)followup=hooks.replayCompletion(actor,c.followupPlan(),a);}
            return lifecycle.attach(completionReply(id,true,followup),lifecycle.original(id,"诊疗完成"));
        }
        writable(c,id,request.expectedRowVersion());checked(c,request.allergyVersion());
        var lines=access.treatments(id,true);total(lines,Store.number(a,"dentist_id"),actor.id());
        for(var line:lines)if("确认".equals(line.get("status")) && ((Timestamp)line.get("performed_at")).after(db.now()))throw ApiError.conflict("INVALID_STATE");
        var completedAt=db.now();db.jdbc.update("UPDATE appointments SET treatment_status='已完成',completed_at=?,completion_note=?,row_version=row_version+1 WHERE appointment_id=?",completedAt,request.completionNote(),id);
        var after=db.one("SELECT "+EncounterAccess.APPOINTMENT_COLUMNS+",followup_plan_id FROM appointments WHERE appointment_id=?",id);
        Map<String,Object> followup=null;Map<String,Object> outcome;
        if(c.followupPlan()==null){after.remove("followup_plan_id");outcome=lifecycle.apply(actor,c.reminderChain(),id,null,null,true);}
        else {
            var transition=hooks.prepareCompletion(c.followupPlan(),after,true);
            var planAfter=hooks.applyCompletionState(transition);
            var rows=new ArrayList<>(c.lockedAppointments());rows.removeIf(row->Store.number(row,"appointment_id")==id);rows.add(db.one("SELECT * FROM appointments WHERE appointment_id=?",id));
            var detailed=linkedReminders.apply(actor,id,planAfter,rows,false,c.planResourcesActive(),(String)c.patient().get("phone"));outcome=detailed.outcome();
            followup=hooks.finishCompletion(actor,transition,planAfter,new FollowupHooks.MessageCleanup(detailed.followupCancelled(),detailed.followupUnresolved()),outcome);
        }
        var auditAfter=new LinkedHashMap<String,Object>();auditAfter.put("appointment",after);if(followup!=null)auditAfter.put("followup",followup);auditAfter.put("reminderOutcome",outcome);db.audit(actor.id(),"诊疗完成","appointment",id,request.completionNote(),a,auditAfter);return lifecycle.attach(completionReply(id,false,followup),outcome);
    }
    private Object completionReply(long id,boolean replayed,Object followup) { return followup==null?access.reply(id,replayed,true):Store.json(Map.of("replayed",replayed,"encounter",access.clinical(id),"followup",followup)); }
}
