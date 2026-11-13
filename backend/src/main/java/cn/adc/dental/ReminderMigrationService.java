package cn.adc.dental;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Atomic bounded clinic migration. No sender and no partially committed migration state. */
@Service
class ReminderMigrationService {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final String APP="appointment_id,patient_id,clinic_id,dentist_id,schedule_id,scheduled_start,scheduled_end,appointment_status,treatment_status,attended,arrived_at,completed_at,followup_plan_id,previous_appointment_id,row_version";
    private static final String MSG="message_id,patient_id,clinic_id,appointment_id,followup_plan_id,reminder_kind,planned_at,channel,recipient_snapshot,content,send_status,sent_at";
    private static final String BUSY="(send_status NOT IN ('已发送','已取消') OR (send_status='已发送' AND sent_at IS NULL) OR (send_status='已取消' AND sent_at IS NOT NULL))";
    private static final String TYPE="reminder_rule_migration";
    private final Store db;
    private final ReminderService reminders;
    private final BookingRules coverage;
    private final FollowupHooks hooks;
    private final ReminderChainReader chains;
    private final ReminderMessageWriter writer;
    ReminderMigrationService(Store db,ReminderService reminders,BookingRules coverage,FollowupHooks hooks,ReminderChainReader chains,ReminderMessageWriter writer){this.db=db;this.reminders=reminders;this.coverage=coverage;this.hooks=hooks;this.chains=chains;this.writer=writer;}
    record Ref(ReminderRules.Kind kind,long id) implements Comparable<Ref>{public int compareTo(Ref other){int c=kind.compareTo(other.kind);return c!=0?c:Long.compare(id,other.id);}}
    private static final class Graph {
        final SortedSet<Ref> scope=new TreeSet<>();
        final SortedSet<Long> guards=new TreeSet<>();
        final SortedMap<Long,ReminderLifecycle.Chain> chains=new TreeMap<>();
        final Map<String,SortedMap<Long,Map<String,Object>>> rows=new LinkedHashMap<>();
        Graph(){for(String type:ReminderMigrationRules.LOCK_CATEGORIES)rows.put(type,new TreeMap<>());}
        SortedMap<Long,Map<String,Object>> table(String name){return rows.get(name);}
        int businesses(){return ReminderMigrationRules.businessCount(scope.stream().map(r->r.kind().name()+":"+r.id()).toList(),guards);}
        Object fingerprint(){var lineage=new TreeMap<String,Object>();chains.forEach((id,c)->lineage.put(Long.toString(id),Map.of("complete",c.complete(),"linked",c.linked(),"identity",c.identity())));return Map.of("scope",scope.stream().map(r->List.of(r.kind().name(),Long.toString(r.id()))).toList(),"guards",guards.stream().map(Object::toString).toList(),"chains",lineage,"rows",rows);}
        int resources(){return rows.entrySet().stream().filter(e->!e.getKey().equals("reminder_messages")).mapToInt(e->e.getValue().size()).sum();}
    }
    private static String key(String table){return switch(table){case "clinics"->"clinic_id";case "dentists"->"dentist_id";case "patients"->"patient_id";case "clinic_openings"->"opening_id";case "dentist_schedules"->"schedule_id";case "followup_plans"->"followup_plan_id";case "appointments"->"appointment_id";case "reminder_messages"->"message_id";default->throw new IllegalArgumentException();};}
    private static String columns(String table){return switch(table){case "clinics"->"clinic_id,active";case "dentists"->"dentist_id,active";case "patients"->"patient_id,phone,active,row_version";case "clinic_openings"->"opening_id,clinic_id,start_at,end_at,status";case "dentist_schedules"->"schedule_id,opening_id,clinic_id,dentist_id,start_at,end_at,status";case "followup_plans"->FollowupService.PLAN;case "appointments"->APP;case "reminder_messages"->MSG;default->throw new IllegalArgumentException();};}
    private static void bounds(Graph g){g.businesses();ReminderMigrationRules.limit("关联历史",g.table("reminder_messages").size(),500);ReminderMigrationRules.limit("父资源",g.resources(),200);}
    private Map<String,Object> add(Graph g,String table,long id){var existing=g.table(table).get(id);if(existing!=null)return existing;var row=db.one("SELECT "+columns(table)+" FROM "+table+" WHERE "+key(table)+"=?",id);g.table(table).put(id,row);bounds(g);return row;}
    private void scope(Graph g,Ref ref){g.scope.add(ref);bounds(g);}
    private Ref messageRef(Map<String,Object> m){boolean appointment=m.get("appointment_id")!=null,followup=m.get("followup_plan_id")!=null;if(appointment==followup)throw ApiError.conflict("MIGRATION_RECONCILIATION_REQUIRED");if(!Objects.equals(m.get("reminder_kind"),appointment?"到诊提醒":"复诊提醒"))throw ApiError.conflict("MIGRATION_RECONCILIATION_REQUIRED");return new Ref(appointment?ReminderRules.Kind.APPOINTMENT:ReminderRules.Kind.FOLLOWUP,Store.number(m,appointment?"appointment_id":"followup_plan_id"));}
    private static String marks(int count){return String.join(",",Collections.nCopies(count,"?"));}
    private Graph discover(long clinic,Instant now){
        var g=new Graph();add(g,"clinics",clinic);
        var appts=db.jdbc.queryForList("SELECT appointment_id FROM appointments WHERE clinic_id=? AND appointment_status='待到诊' AND scheduled_start>? ORDER BY appointment_id LIMIT 51",clinic,Timestamp.from(now));
        for(var row:appts)scope(g,new Ref(ReminderRules.Kind.APPOINTMENT,Store.number(row,"appointment_id")));
        var plans=db.jdbc.queryForList("SELECT followup_plan_id FROM followup_plans WHERE clinic_id=? AND status='待复诊' ORDER BY followup_plan_id LIMIT 51",clinic);
        for(var row:plans)scope(g,new Ref(ReminderRules.Kind.FOLLOWUP,Store.number(row,"followup_plan_id")));
        var busy=db.jdbc.queryForList("SELECT "+MSG+" FROM reminder_messages WHERE clinic_id=? AND "+BUSY+" ORDER BY message_id LIMIT 501",clinic);
        ReminderMigrationRules.limit("关联历史",busy.size(),500);
        for(var row:busy){scope(g,messageRef(row));g.table("reminder_messages").put(Store.number(row,"message_id"),row);}
        for(var r:g.scope){String table=r.kind()==ReminderRules.Kind.APPOINTMENT?"appointments":"followup_plans";var row=add(g,table,r.id());if(Store.number(row,"clinic_id")!=clinic)throw ApiError.conflict("MIGRATION_RECONCILIATION_REQUIRED");}
        // Guard closure is not the writable scope: cross-clinic ancestors contribute facts, never actions.
        for(var r:g.scope)if(r.kind()==ReminderRules.Kind.APPOINTMENT&&g.table("appointments").get(r.id()).get("followup_plan_id")==null){
            var chain=chains.candidate(r.id());if(chain==null||!chain.complete()||chain.linked())throw ApiError.conflict("MIGRATION_CHAIN_INCOMPLETE");g.chains.put(r.id(),chain);
            for(long id:chain.rows().keySet()){g.guards.add(id);bounds(g);add(g,"appointments",id);}
        }
        // Only scope appointments need their linked plan; sources need their own resource parents, not recursive ancestor plans.
        for(var a:new ArrayList<>(g.table("appointments").values()))if(a.get("followup_plan_id")!=null)add(g,"followup_plans",Store.number(a,"followup_plan_id"));
        for(var p:new ArrayList<>(g.table("followup_plans").values())){
            add(g,"appointments",Store.number(p,"source_appointment_id"));
            var linked=db.jdbc.queryForList("SELECT "+APP+" FROM appointments WHERE followup_plan_id=? ORDER BY appointment_id LIMIT 201",p.get("followup_plan_id"));
            for(var a:linked){g.table("appointments").put(Store.number(a,"appointment_id"),a);bounds(g);}
        }
        // Take all history for each scoped business, even a row labelled as another clinic/kind.
        var historyRefs=new TreeSet<>(g.scope);g.guards.forEach(id->historyRefs.add(new Ref(ReminderRules.Kind.APPOINTMENT,id)));
        for(var r:historyRefs){var history=db.jdbc.queryForList("SELECT "+MSG+" FROM reminder_messages WHERE "+(r.kind()==ReminderRules.Kind.APPOINTMENT?"appointment_id":"followup_plan_id")+"=? ORDER BY message_id LIMIT 501",r.id());for(var m:history){g.table("reminder_messages").put(Store.number(m,"message_id"),m);bounds(g);}}
        for(var a:g.table("appointments").values()){
            add(g,"clinics",Store.number(a,"clinic_id"));add(g,"dentists",Store.number(a,"dentist_id"));add(g,"patients",Store.number(a,"patient_id"));var s=add(g,"dentist_schedules",Store.number(a,"schedule_id"));add(g,"clinic_openings",Store.number(s,"opening_id"));
        }
        for(var p:g.table("followup_plans").values()){add(g,"clinics",Store.number(p,"clinic_id"));add(g,"dentists",Store.number(p,"dentist_id"));add(g,"patients",Store.number(p,"patient_id"));}
        return g;
    }
    private List<ReminderMigrationRules.Lock> lockOrder(Graph g){var ids=new LinkedHashMap<String,Collection<Long>>();g.rows.forEach((table,rows)->ids.put(table,rows.keySet()));return ReminderMigrationRules.lockOrder(ids);}
    private void lockClinics(Graph g){for(var lock:lockOrder(g))if(lock.table().equals("clinics"))db.one("SELECT "+columns("clinics")+" FROM clinics WHERE clinic_id=? FOR UPDATE",lock.id());}
    private Graph lockRest(Graph candidate,long clinic,String conflict){
        // Never call the single-object lock routines here: take every category before the next.
        for(var lock:lockOrder(candidate))if(!lock.table().equals("clinics"))db.one("SELECT "+columns(lock.table())+" FROM "+lock.table()+" WHERE "+key(lock.table())+"=? FOR UPDATE",lock.id());
        var locked=discover(clinic,db.now().toInstant());ReminderMigrationRules.same(candidate.fingerprint(),locked.fingerprint(),conflict);return locked;
    }
    private List<Map<String,Object>> mutations(Graph g){
        var conditions=new ArrayList<String>();var args=new ArrayList<Object>();
        for(var e:g.rows.entrySet())if(!e.getValue().isEmpty()){
            var types=switch(e.getKey()){case "clinics"->List.of("clinic");case "dentists"->List.of("dentist");case "patients"->List.of("patient","patient_allergies");case "clinic_openings"->List.of("clinic_opening");case "dentist_schedules"->List.of("dentist_schedule");case "followup_plans"->List.of("followup_plan");case "appointments"->List.of("appointment");case "reminder_messages"->List.of("reminder_message","followup_reminder");default->throw new IllegalArgumentException();};
            conditions.add("(object_type IN ("+marks(types.size())+") AND object_id IN ("+marks(e.getValue().size())+"))");args.addAll(types);e.getValue().keySet().forEach(id->args.add(Long.toString(id)));
        }
        return db.jdbc.queryForList("SELECT object_type,object_id,COUNT(*) AS mutation_count FROM audit_logs WHERE ("+String.join(" OR ",conditions)+") AND action NOT IN ('读取','导出','预览提醒','重建提醒') GROUP BY object_type,object_id ORDER BY object_type,object_id",args.toArray());
    }
    private ReminderRules.Business business(Graph g,Ref r,Instant now){
        var row=g.table(r.kind()==ReminderRules.Kind.APPOINTMENT?"appointments":"followup_plans").get(r.id());long patient=Store.number(row,"patient_id"),clinic=Store.number(row,"clinic_id");var p=g.table("patients").get(patient);boolean active=Store.enabled(p,"active")&&Store.enabled(g.table("clinics").get(clinic),"active");
        if(r.kind()==ReminderRules.Kind.APPOINTMENT){
            long dentist=Store.number(row,"dentist_id");active&=Store.enabled(g.table("dentists").get(dentist),"active");var start=((Timestamp)row.get("scheduled_start")).toInstant();String invalid=ReminderRules.appointmentValidity(active,row.get("appointment_status").toString(),Store.enabled(row,"attended"),row.get("treatment_status").toString(),start,now);
            var schedule=g.table("dentist_schedules").get(Store.number(row,"schedule_id"));try{coverage.coverage(new BookingRules.Coverage(schedule,g.table("clinic_openings").get(Store.number(schedule,"opening_id"))),clinic,dentist,(Timestamp)row.get("scheduled_start"),(Timestamp)row.get("scheduled_end"));}catch(ApiError e){if(!e.code.equals("OUTSIDE_COVERAGE"))throw e;if(invalid==null)invalid="SCHEDULE_COVERAGE_INVALID";}
            if(row.get("followup_plan_id")!=null){var plan=g.table("followup_plans").get(Store.number(row,"followup_plan_id"));if(Store.number(plan,"patient_id")!=patient||Store.number(plan,"source_appointment_id")==r.id())throw ApiError.conflict("MIGRATION_RECONCILIATION_REQUIRED");hooks.source(plan,g.table("appointments").get(Store.number(plan,"source_appointment_id")));}
            return new ReminderRules.Business(r.kind(),r.id(),patient,clinic,invalid,start,null,(String)p.get("phone"));
        }
        long source=Store.number(row,"source_appointment_id");hooks.source(row,g.table("appointments").get(source));var linked=g.table("appointments").values().stream().filter(a->Objects.equals(a.get("followup_plan_id"),r.id())).toList();
        if(linked.stream().anyMatch(a->Store.number(a,"patient_id")!=patient))throw ApiError.conflict("MIGRATION_RECONCILIATION_REQUIRED");boolean completed=linked.stream().anyMatch(a->Store.enabled(a,"attended")&&"已到诊".equals(a.get("appointment_status"))&&"已完成".equals(a.get("treatment_status"))&&a.get("completed_at")!=null);
        return new ReminderRules.Business(r.kind(),r.id(),patient,clinic,ReminderRules.followupValidity(active,row.get("status").toString(),completed),null,LocalDate.parse(row.get("suggested_date").toString()),(String)p.get("phone"));
    }
    private List<ReminderRules.History> history(Graph g,ReminderRules.Business b){
        var result=new ArrayList<ReminderRules.History>();String fk=b.kind()==ReminderRules.Kind.APPOINTMENT?"appointment_id":"followup_plan_id";
        for(var m:g.table("reminder_messages").values())if(Objects.equals(m.get(fk),b.id())){
            boolean related=Store.number(m,"patient_id")==b.patient()&&Store.number(m,"clinic_id")==b.clinic()&&messageRef(m).equals(new Ref(b.kind(),b.id()));
            result.add(new ReminderRules.History(Store.number(m,"message_id"),related,(String)m.get("channel"),((Timestamp)m.get("planned_at")).toInstant(),(String)m.get("recipient_snapshot"),(String)m.get("content"),(String)m.get("send_status"),m.get("sent_at")==null?null:((Timestamp)m.get("sent_at")).toInstant()));
        }
        return result;
    }
    record Item(ReminderRules.Business business,ReminderMigrationRules.Evaluation evaluation) {}
    private List<ReminderLifecycleRules.Scoped> chainHistory(Graph g,ReminderLifecycle.Chain chain){var result=new ArrayList<ReminderLifecycleRules.Scoped>();for(var m:g.table("reminder_messages").values())if(m.get("appointment_id")!=null&&chain.rows().containsKey(Store.number(m,"appointment_id"))){long id=Store.number(m,"appointment_id");var a=g.table("appointments").get(id);var owner=new ReminderRules.Business(ReminderRules.Kind.APPOINTMENT,id,Store.number(a,"patient_id"),Store.number(a,"clinic_id"),null,null,null,null);result.add(new ReminderLifecycleRules.Scoped(id,writer.history(owner,m)));}return result;}
    record Plan(List<Item> items,String dependencyDigest,String planDigest,Map<String,Object> summary,boolean blocked) {}
    private Plan plan(Graph g,ReminderRules.Snapshot current,ReminderRules.Settings desired,Instant now){
        var items=new ArrayList<Item>();var semantics=new ArrayList<Object>();var visible=new ArrayList<Map<String,Object>>();var issues=new ArrayList<Map<String,Object>>();int count=0;boolean blocked=false;
        var writableAppointments=new HashSet<Long>();g.scope.stream().filter(r->r.kind()==ReminderRules.Kind.APPOINTMENT).forEach(r->writableAppointments.add(r.id()));
        for(var ref:g.scope){var b=business(g,ref,now);var chain=g.chains.get(ref.id());var e=ref.kind()==ReminderRules.Kind.APPOINTMENT&&chain!=null?ReminderMigrationRules.evaluateChain(b,desired,now,history(g,b),chainHistory(g,chain),chain.complete(),writableAppointments):ReminderMigrationRules.evaluate(b,desired,now,history(g,b));items.add(new Item(b,e));
            boolean lineage=ref.kind()==ReminderRules.Kind.APPOINTMENT&&g.table("appointments").get(ref.id()).get("followup_plan_id")!=null&&g.table("appointments").get(ref.id()).get("previous_appointment_id")!=null;var itemIssues=new ArrayList<>(e.result().issues());if(lineage)itemIssues.add("MIGRATION_LINKED_CHAIN_NOT_SUPPORTED");boolean itemBlocked=e.blocked()||lineage;blocked|=itemBlocked;semantics.add(Map.of("kind",ref.kind().name(),"id",Long.toString(ref.id()),"issues",itemIssues,"actions",e.semantic(),"blocked",itemBlocked));
            if(!itemIssues.isEmpty())issues.add(Map.of("businessType",ref.kind().name(),"businessId",Long.toString(ref.id()),"issues",itemIssues,"blocking",itemBlocked));
            for(var action:e.semantic()){count++;if(visible.size()<100){var output=new LinkedHashMap<String,Object>();output.put("businessType",ref.kind().name());output.put("businessId",Long.toString(ref.id()));for(String k:List.of("operation","messageId","channel","status","plannedAt","timeMode"))output.put(k,action.get(k));visible.add(output);}}
        }
        var summary=new LinkedHashMap<String,Object>();summary.put("businessCount",g.businesses());summary.put("writableBusinessCount",g.scope.size());summary.put("guardBusinessCount",g.businesses()-g.scope.size());summary.put("historyCount",g.table("reminder_messages").size());summary.put("resourceCount",g.resources());summary.put("actionCount",count);summary.put("actions",visible);summary.put("actionsTruncated",count>100);summary.put("issues",issues);summary.put("commitAllowed",!blocked);summary.put("delivery","NOT_IMPLEMENTED");
        return new Plan(items,ReminderMigrationRules.digest(Map.of("graph",g.fingerprint(),"snapshot",current,"mutations",mutations(g))),ReminderMigrationRules.digest(semantics),summary,blocked);
    }
    private Map<String,Object> operation(long clinic,String id,String action){var rows=db.jdbc.queryForList("SELECT audit_id,actor_account_id,after_value FROM audit_logs WHERE object_type=? AND object_id=? AND action=? AND JSON_UNQUOTE(JSON_EXTRACT(after_value,'$.migrationId'))=? ORDER BY audit_id LIMIT 2",TYPE,Long.toString(clinic),action,id);if(rows.size()>1)throw ApiError.conflict("IDEMPOTENCY_CONFLICT");return rows.isEmpty()?null:rows.getFirst();}
    private JsonNode payload(Map<String,Object> row,Identity.Actor actor){if(Store.number(row,"actor_account_id")!=actor.id())throw ApiError.denied();return JSON.readTree(row.get("after_value").toString());}
    private Map<String,Object> response(long clinic,JsonNode preview,JsonNode receipt,boolean replayed){var result=new LinkedHashMap<String,Object>();result.put("migrationId",preview.get("migrationId").asString());result.put("preview",preview);result.put("receipt",receipt);result.put("replayed",replayed);result.put("currentSnapshot",reminders.snapshot(reminders.config(clinic)));result.put("delivery","NOT_IMPLEMENTED");return result;}
    private void audit(Identity.Actor actor,String action,String type,long id,String reason,Object before,Object after){ReminderMigrationRules.auditSize(before);ReminderMigrationRules.auditSize(after);db.audit(actor.id(),action,type,id,reason,before,after);}
    private Object replayPreview(long clinic,Identity.Actor actor,ReminderMigrationRules.Binding binding,Map<String,Object> prior){var p=payload(prior,actor);ReminderMigrationRules.same(p.get("binding"),binding,"IDEMPOTENCY_CONFLICT");var committed=operation(clinic,binding.migrationId(),"迁移提交");return response(clinic,p,committed==null?null:payload(committed,actor),true);}
    @Transactional
    public Object preview(Identity.Actor actor,long clinic,ReminderMigrationRules.Request request){
        ReminderRules.authorize(actor,clinic,true);var binding=ReminderMigrationRules.bind(clinic,actor.id(),request);var prior=operation(clinic,binding.migrationId(),"迁移预览");
        if(prior!=null){db.one("SELECT clinic_id FROM clinics WHERE clinic_id=? FOR UPDATE",clinic);return replayPreview(clinic,actor,binding,operation(clinic,binding.migrationId(),"迁移预览"));}
        var candidate=discover(clinic,db.now().toInstant());lockClinics(candidate);prior=operation(clinic,binding.migrationId(),"迁移预览");if(prior!=null)return replayPreview(clinic,actor,binding,prior);
        var g=lockRest(candidate,clinic,"RETRY_TRANSACTION");var current=reminders.snapshot(reminders.config(clinic));ReminderRules.compare(binding.request().expected(),current);var now=db.now().toInstant();var plan=plan(g,current,ReminderRules.settings(binding.request()),now);
        var p=new LinkedHashMap<String,Object>();p.put("migrationId",binding.migrationId());p.put("binding",binding);p.put("createdAt",now.toString());p.put("expiresAt",now.plus(ReminderMigrationRules.TTL).toString());p.put("dependencyDigest",plan.dependencyDigest());p.put("planDigest",plan.planDigest());p.put("summary",plan.summary());p.put("previewDigest",ReminderMigrationRules.digest(p));
        audit(actor,"迁移预览",TYPE,clinic,binding.request().reason(),null,p);return response(clinic,JSON.valueToTree(p),null,false);
    }
    private Object replayCommit(long clinic,Identity.Actor actor,JsonNode p,Map<String,Object> preview,Map<String,Object> committed,String digest){
        var receipt=payload(committed,actor);ReminderMigrationRules.same(receipt.get("previewAuditId").asString(),Long.toString(Store.number(preview,"audit_id")),"IDEMPOTENCY_CONFLICT");ReminderMigrationRules.same(receipt.get("previewDigest").asString(),digest,"IDEMPOTENCY_CONFLICT");return response(clinic,p,receipt,true);
    }
    @Transactional
    public Object commit(Identity.Actor actor,long clinic,String id,ReminderMigrationRules.Commit request){
        ReminderRules.authorize(actor,clinic,true);Store.parseId(id);ReminderMigrationRules.digestInput(request.previewDigest());var preview=operation(clinic,id,"迁移预览");if(preview==null)throw new ApiError(404,"NOT_FOUND","迁移预览不存在");var p=payload(preview,actor);ReminderMigrationRules.same(p.get("previewDigest").asString(),request.previewDigest(),"IDEMPOTENCY_CONFLICT");
        var committed=operation(clinic,id,"迁移提交");if(committed!=null){db.one("SELECT clinic_id FROM clinics WHERE clinic_id=? FOR UPDATE",clinic);return replayCommit(clinic,actor,p,preview,operation(clinic,id,"迁移提交"),request.previewDigest());}
        var binding=JSON.treeToValue(p.get("binding"),ReminderMigrationRules.Binding.class);var candidate=discover(clinic,db.now().toInstant());lockClinics(candidate);committed=operation(clinic,id,"迁移提交");if(committed!=null)return replayCommit(clinic,actor,p,preview,committed,request.previewDigest());
        var g=lockRest(candidate,clinic,"MIGRATION_PREVIEW_STALE");var now=db.now().toInstant();ReminderMigrationRules.fresh(Instant.parse(p.get("createdAt").asString()),now);var current=reminders.snapshot(reminders.config(clinic));ReminderMigrationRules.same(binding.request().expected(),current,"MIGRATION_PREVIEW_STALE");var desired=ReminderRules.settings(binding.request());var plan=plan(g,current,desired,now);
        ReminderMigrationRules.same(p.get("dependencyDigest").asString(),plan.dependencyDigest(),"MIGRATION_PREVIEW_STALE");ReminderMigrationRules.same(p.get("planDigest").asString(),plan.planDigest(),"MIGRATION_PREVIEW_STALE");if(plan.blocked())throw ApiError.conflict("MIGRATION_RECONCILIATION_REQUIRED");
        int changed=db.jdbc.update("UPDATE clinic_booking_rules SET appointment_lead_minutes=?,followup_lead_days=?,followup_send_time=?,in_app_enabled=?,sms_enabled=?,updated_by=?,updated_at=? WHERE clinic_id=? AND rule_id=?",desired.appointmentLeadMinutes(),desired.followupLeadDays(),desired.followupSendTime()==null?null:java.sql.Time.valueOf(desired.followupSendTime()),desired.inAppEnabled(),desired.smsEnabled(),actor.id(),Timestamp.from(now),clinic,Store.parseId(binding.request().ruleId()));if(changed!=1)throw ApiError.conflict("MIGRATION_PREVIEW_STALE");
        var mapping=new ArrayList<Map<String,Object>>();var processed=new ArrayList<Object>();var actionCounts=new TreeMap<String,Integer>();
        for(var item:plan.items())for(var a:item.evaluation().result().actions()){
            actionCounts.merge(a.operation(),1,Integer::sum);Long messageId=a.messageId();var b=item.business();var stored=messageId==null?null:g.table("reminder_messages").get(messageId);ReminderMigrationRules.localAction(clinic,b,a,stored==null?null:Store.number(stored,"clinic_id"),stored!=null&&writer.history(b,stored).related());
            if(a.operation().equals("CREATE")){messageId=db.id();var d=a.draft();db.jdbc.update("INSERT INTO reminder_messages(message_id,patient_id,clinic_id,appointment_id,followup_plan_id,reminder_kind,planned_at,channel,recipient_snapshot,content,send_status) VALUES(?,?,?,?,?,?,?,?,?,?,'待发送')",messageId,b.patient(),clinic,b.kind()==ReminderRules.Kind.APPOINTMENT?b.id():null,b.kind()==ReminderRules.Kind.FOLLOWUP?b.id():null,b.kind()==ReminderRules.Kind.APPOINTMENT?"到诊提醒":"复诊提醒",Timestamp.from(d.plannedAt()),d.channel(),d.recipient(),d.content());audit(actor,"迁移新增待发","reminder_message",messageId,binding.request().reason(),null,db.one("SELECT "+MSG+" FROM reminder_messages WHERE message_id=?",messageId));mapping.add(Map.of("businessType",b.kind().name(),"businessId",Long.toString(b.id()),"channel",a.channel(),"messageId",Long.toString(messageId),"plannedAt",d.plannedAt().toString()));
            }else if(Set.of("CANCEL","RESTORE").contains(a.operation())){var before=g.table("reminder_messages").get(messageId);int updated=db.jdbc.update("UPDATE reminder_messages SET send_status=? WHERE message_id=? AND send_status=? AND sent_at IS NULL",a.status(),messageId,a.operation().equals("CANCEL")?"待发送":"已取消");if(updated!=1)throw ApiError.conflict("RETRY_TRANSACTION");var after=new LinkedHashMap<>(before);after.put("send_status",a.status());audit(actor,a.operation().equals("CANCEL")?"迁移撤销待发":"迁移恢复待发","reminder_message",messageId,binding.request().reason(),before,after);}
            processed.add(List.of(b.kind().name(),Long.toString(b.id()),a.operation(),messageId==null?"":Long.toString(messageId)));
        }
        var configRequest=new LinkedHashMap<String,Object>();configRequest.put("actorId",binding.actorId());configRequest.put("ruleId",binding.request().ruleId());configRequest.put("expected",current);configRequest.put("settings",desired);configRequest.put("reason",binding.request().reason());configRequest.put("migrationId",id);
        audit(actor,"提醒规则维护","reminder_rule",clinic,binding.request().reason(),current,Map.of("request",configRequest,"rule",reminders.config(clinic)));var after=reminders.snapshot(reminders.config(clinic));
        var receipt=new LinkedHashMap<String,Object>();receipt.put("migrationId",id);receipt.put("actorId",binding.actorId());receipt.put("previewAuditId",Long.toString(Store.number(preview,"audit_id")));receipt.put("previewDigest",request.previewDigest());receipt.put("committedAt",now.toString());receipt.put("snapshot",after);receipt.put("requestDigest",ReminderMigrationRules.digest(binding));receipt.put("dependencyDigest",plan.dependencyDigest());receipt.put("actionCounts",actionCounts);receipt.put("processedDigest",ReminderMigrationRules.digest(processed));receipt.put("createdMessages",mapping);receipt.put("delivery","NOT_IMPLEMENTED");
        audit(actor,"迁移提交",TYPE,clinic,binding.request().reason(),current,receipt);return response(clinic,p,JSON.valueToTree(receipt),false);
    }
    @Transactional
    public Object read(Identity.Actor actor,long clinic,String id){ReminderRules.authorize(actor,clinic,true);Store.parseId(id);db.one("SELECT clinic_id FROM clinics WHERE clinic_id=? FOR UPDATE",clinic);var preview=operation(clinic,id,"迁移预览");if(preview==null)throw new ApiError(404,"NOT_FOUND","迁移预览不存在");var p=payload(preview,actor);var committed=operation(clinic,id,"迁移提交");db.audit(actor.id(),"读取",TYPE,clinic);return response(clinic,p,committed==null?null:payload(committed,actor),false);}
}
