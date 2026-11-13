package cn.adc.dental;

import java.time.*;
import java.util.*;

/** Executes the actual grouping/fact builder and shared batch/A policy, no SQL or transaction model. */
public class ReminderLinkedFactsCheck {
    private static final List<Map<String,Object>> checks=new ArrayList<>();
    private static final Instant NOW=Instant.parse("2026-10-22T12:00:00Z");
    private static final ReminderRules.Settings SETTINGS=new ReminderRules.Settings(60,1,"09:00:00",true,true);
    private static void check(String name,boolean ok){checks.add(Map.of("name",name,"passed",ok));if(!ok)throw new AssertionError(name);}
    private static ReminderLifecycleRules.Link link(long id,Long previous,String status){return new ReminderLifecycleRules.Link(id,10,previous,40L,status);}
    private static ReminderRules.Business plan(){return new ReminderRules.Business(ReminderRules.Kind.FOLLOWUP,40,10,1,null,null,LocalDate.of(2026,10,25),"10000000000");}
    private static ReminderLinkedFacts.Appointment appointment(ReminderLifecycleRules.Link link){var b=new ReminderRules.Business(ReminderRules.Kind.APPOINTMENT,link.id(),10,2,"已改约".equals(link.status())?"APPOINTMENT_STARTED_OR_INVALID":null,NOW.plusSeconds(7200+link.id()),null,"10000000000");return new ReminderLinkedFacts.Appointment(link,b);}
    private static ReminderRules.History history(long id,String channel,String status,String content){return new ReminderRules.History(id,true,channel,NOW.plusSeconds(3600),"patient:10",content,status,null);}
    private static ReminderBatchRules.Batch evaluate(List<ReminderLinkedFacts.Appointment> appointments,List<ReminderLifecycleRules.Scoped> history){return evaluate(plan(),appointments,history,List.of());}
    private static ReminderBatchRules.Batch evaluate(ReminderRules.Business plan,List<ReminderLinkedFacts.Appointment> appointments,List<ReminderLifecycleRules.Scoped> history,List<ReminderRules.History> planHistory){
        var facts=ReminderLinkedFacts.facts(plan,appointments,history);var inputs=new ArrayList<ReminderBatchRules.Input>();
        for(var fact:facts){var own=fact.business().kind()==ReminderRules.Kind.FOLLOWUP?planHistory:history.stream().filter(h->h.appointmentId()==fact.business().id()).map(ReminderLifecycleRules.Scoped::history).toList();inputs.add(new ReminderBatchRules.Input(fact.business(),SETTINGS,own,fact.chain(),fact.complete()));}
        return ReminderBatchRules.evaluate(inputs,NOW);
    }
    private static long creates(ReminderBatchRules.Batch batch,long id,String channel){return batch.items().stream().filter(i->i.business().kind()==ReminderRules.Kind.APPOINTMENT&&i.business().id()==id).flatMap(i->i.result().actions().stream()).filter(a->a.operation().equals("CREATE")&&a.channel().equals(channel)).count();}
    private static long actions(ReminderBatchRules.Batch batch,ReminderRules.Kind kind,long id,String operation){return batch.items().stream().filter(i->i.business().kind()==kind&&i.business().id()==id).flatMap(i->i.result().actions().stream()).filter(a->a.operation().equals(operation)).count();}
    private static ReminderLinkedFacts.Appointment changed(long id,Long previous,String status,boolean attended,String treatment){var start=NOW.plusSeconds(7200);var invalid=ReminderRules.appointmentValidity(true,status,attended,treatment,start,NOW);var business=new ReminderRules.Business(ReminderRules.Kind.APPOINTMENT,id,10,2,invalid,start,null,"10000000000");return new ReminderLinkedFacts.Appointment(link(id,previous,status),business);}
    private static Map<String,Object> row(long id,Long previous,Long plan){var row=new LinkedHashMap<String,Object>();row.put("appointment_id",id);row.put("patient_id",10L);row.put("clinic_id",1L);row.put("dentist_id",2L);row.put("schedule_id",3L);row.put("previous_appointment_id",previous);row.put("followup_plan_id",plan);return row;}
    private static void rejected(String name,List<ReminderLifecycleRules.Link> links){try{ReminderLinkedFacts.groups(10,40,links);throw new AssertionError(name);}catch(ApiError error){check(name,error.code.equals("RETRY_TRANSACTION"));}}
    public static void main(String[] args){var report=new LinkedHashMap<String,Object>();report.put("mode","no-db-no-http");report.put("startedAt",Instant.now().toString());boolean passed=false;try{run();passed=true;}catch(Throwable e){report.put("error",e.getClass().getSimpleName()+": "+e.getMessage());}report.put("status",passed?"passed":"failed");report.put("checks",checks);report.put("finishedAt",Instant.now().toString());System.out.println(Store.encode(report));if(!passed)System.exit(1);}
    private static void run(){
        var old=link(20,null,"已改约");var successor=link(21,20L,"待到诊");var independent=link(30,null,"待到诊");
        var groups=ReminderLinkedFacts.groups(10,40,List.of(independent,successor,old));
        check("same-plan independent root stays separate from complete previous chain",groups.get(21L).equals(List.of(20L,21L))&&groups.get(30L).equals(List.of(30L)));
        check("every member receives same whole ordered chain",groups.get(20L).equals(groups.get(21L)));
        var appointments=List.of(appointment(old),appointment(successor),appointment(independent));
        for(String status:List.of("发送中","失败")){
            var unknown=new ReminderLifecycleRules.Scoped(20,history(101,"站内",status,"old"));
            var result=evaluate(appointments,List.of(unknown));
            check(status+" suppresses same-chain successor in affected channel",creates(result,21,"站内")==0);
            check(status+" leaves same-chain other channel enabled",creates(result,21,"短信")==1);
            check(status+" cannot globally suppress independent appointment",creates(result,30,"站内")==1&&creates(result,30,"短信")==1);
            check(status+" keeps reconciliation and one unresolved original identity",Boolean.TRUE.equals(result.outcome().get("reconciliationRequired"))&&result.outcome().get("unresolvedCount").equals(1));
        }
        var pending=new ReminderLifecycleRules.Scoped(20,history(102,"站内","待发送","obsolete"));var mixed=evaluate(appointments,List.of(pending,new ReminderLifecycleRules.Scoped(20,history(103,"短信","发送中","old"))));
        check("known pending predecessor is retired despite other-channel unknown",mixed.items().stream().filter(i->i.business().id()==20).flatMap(i->i.result().actions().stream()).anyMatch(a->a.operation().equals("CANCEL")&&a.messageId()==102));
        check("batch includes exactly one followup fact plus all linked appointments",mixed.items().size()==4&&mixed.items().stream().filter(i->i.business().kind()==ReminderRules.Kind.FOLLOWUP).count()==1);
        check("empty linked collection still permits followup-only fact",ReminderLinkedFacts.facts(plan(),List.of(),List.of()).size()==1);
        rejected("missing predecessor cannot be treated as independent root",List.of(successor));
        rejected("foreign plan cannot enter locked batch",List.of(new ReminderLifecycleRules.Link(20,10,null,41L,"待到诊")));
        rejected("different patient cannot enter locked batch",List.of(new ReminderLifecycleRules.Link(20,11,null,40L,"待到诊")));
        rejected("duplicate appointment rejected",List.of(independent,independent));
        rejected("branching successor rejected",List.of(old,successor,link(22,20L,"待到诊")));
        rejected("non-rescheduled predecessor rejected",List.of(link(20,null,"待到诊"),successor));
        rejected("cycle rejected",List.of(link(20,21L,"已改约"),link(21,20L,"已改约")));
        var many=new ArrayList<ReminderLifecycleRules.Link>();for(long id=1;id<=64;id++)many.add(link(id,id==1?null:id-1,id==64?"待到诊":"已改约"));
        check("64-row complete chain supported",ReminderLinkedFacts.groups(10,40,many).get(64L).size()==64);
        many.set(63,link(64,63L,"已改约"));many.add(link(65,64L,"待到诊"));rejected("over-limit lineage refused without partial facts",many);
        var independentRoots=new ArrayList<ReminderLifecycleRules.Link>();for(long id=1;id<=65;id++)independentRoots.add(link(id,null,"待到诊"));check("chain limit is per lineage not global plan count",ReminderLinkedFacts.groups(10,40,independentRoots).size()==65);
        var unrelated=new ReminderLifecycleRules.Scoped(999,history(104,"站内","发送中","foreign"));var isolated=evaluate(appointments,List.of(unrelated));check("outside-group history never contaminates chain guard",creates(isolated,21,"站内")==1&&creates(isolated,30,"站内")==1);
        try{ReminderLinkedFacts.facts(plan(),List.of(new ReminderLinkedFacts.Appointment(independent,new ReminderRules.Business(ReminderRules.Kind.APPOINTMENT,31,10,2,null,NOW.plusSeconds(7200),null,"10000000000"))),List.of());throw new AssertionError("mismatch");}catch(ApiError expected){check("fact business identity must equal chain row",expected.code.equals("RETRY_TRANSACTION"));}
        var owner=Map.<String,Object>of("followup_plan_id",40L,"patient_id",10L,"clinic_id",1L,"dentist_id",2L,"source_appointment_id",11L);var source=row(11,null,null);var linked=row(20,null,40L);var beforeRows=new TreeMap<Long,Map<String,Object>>();beforeRows.put(11L,source);beforeRows.put(20L,linked);var candidate=new ReminderFollowupContext.Candidate(owner,beforeRows);
        check("locked identity accepts same complete rows",ReminderFollowupContext.lockedIdentityMatches(candidate,owner,List.of(source,linked)));
        check("new associated appointment observed in current locked query forces retry",!ReminderFollowupContext.lockedIdentityMatches(candidate,owner,List.of(source,linked,row(30,null,40L))));
        check("missing associated row cannot be hidden by pre-lock snapshot",!ReminderFollowupContext.lockedIdentityMatches(candidate,owner,List.of(source)));
        var moved=new LinkedHashMap<>(linked);moved.put("clinic_id",9L);check("changed resource identity forces retry before messages",!ReminderFollowupContext.lockedIdentityMatches(candidate,owner,List.of(source,moved)));
        var relinked=new LinkedHashMap<>(linked);relinked.put("previous_appointment_id",19L);check("changed previous appointment forces retry",!ReminderFollowupContext.lockedIdentityMatches(candidate,owner,List.of(source,relinked)));
        check("unrelated overlap row does not alter plan closure",ReminderFollowupContext.lockedIdentityMatches(candidate,owner,List.of(source,linked,row(90,null,null))));
        var fresh=evaluate(List.of(changed(50,null,"待到诊",false,"未开始")),List.of());
        check("new linked booking plans appointment and plan via same batch",actions(fresh,ReminderRules.Kind.APPOINTMENT,50,"CREATE")==2&&actions(fresh,ReminderRules.Kind.FOLLOWUP,40,"CREATE")==2);
        var predecessor=new ReminderLifecycleRules.Scoped(50,history(150,"站内","待发送","obsolete"));
        var movedBatch=evaluate(List.of(changed(50,null,"已改约",false,"未开始"),changed(51,50L,"待到诊",false,"未开始")),List.of(predecessor));
        check("reschedule retires predecessor and plans successor without ending followup",actions(movedBatch,ReminderRules.Kind.APPOINTMENT,50,"CANCEL")==1&&actions(movedBatch,ReminderRules.Kind.APPOINTMENT,51,"CREATE")==2&&actions(movedBatch,ReminderRules.Kind.FOLLOWUP,40,"CREATE")==2);
        var cancelBatch=evaluate(List.of(changed(50,null,"患者取消",false,"未开始")),List.of(predecessor));
        check("cancel retires only appointment while pending followup can remain",actions(cancelBatch,ReminderRules.Kind.APPOINTMENT,50,"CANCEL")==1&&actions(cancelBatch,ReminderRules.Kind.FOLLOWUP,40,"CREATE")==2);
        var noShowBatch=evaluate(List.of(changed(50,null,"未到诊",false,"未开始")),List.of(predecessor));
        check("no-show is terminal for arrival reminder but not clinical completion",actions(noShowBatch,ReminderRules.Kind.APPOINTMENT,50,"CANCEL")==1&&actions(noShowBatch,ReminderRules.Kind.FOLLOWUP,40,"CREATE")==2);
        var arrivalBatch=evaluate(List.of(changed(50,null,"已到诊",true,"未开始")),List.of(predecessor));
        check("arrival retires appointment and keeps followup pending",actions(arrivalBatch,ReminderRules.Kind.APPOINTMENT,50,"CANCEL")==1&&actions(arrivalBatch,ReminderRules.Kind.FOLLOWUP,40,"CREATE")==2);
        var terminalPlan=new ReminderRules.Business(ReminderRules.Kind.FOLLOWUP,40,10,1,"FOLLOWUP_TERMINAL",null,LocalDate.of(2026,10,25),"10000000000");
        var completed=evaluate(terminalPlan,List.of(changed(50,null,"已到诊",true,"已完成")),List.of(predecessor),List.of(history(151,"站内","待发送","obsolete")));
        check("actual completion retires both appointment and plan pending messages",actions(completed,ReminderRules.Kind.APPOINTMENT,50,"CANCEL")==1&&actions(completed,ReminderRules.Kind.FOLLOWUP,40,"CANCEL")==1&&actions(completed,ReminderRules.Kind.FOLLOWUP,40,"CREATE")==0);
        var planUnknown=evaluate(terminalPlan,List.of(changed(50,null,"已到诊",true,"已完成")),List.of(predecessor),List.of(history(152,"短信","发送中","old")));
        check("completed fact keeps unknown plan result for reconciliation",actions(planUnknown,ReminderRules.Kind.FOLLOWUP,40,"RECONCILE")==1&&Boolean.TRUE.equals(planUnknown.outcome().get("reconciliationRequired")));
        var original=Map.of("scheduling","PARTIAL","issues",List.of("RECONCILIATION_REQUIRED:站内"),"reconciliationRequired",true);
        check("same request replay returns original outcome unchanged",ReminderLifecycleRules.originalOutcome(original)==original);
        check("legacy audit without result stays explicit",((Map<?,?>)ReminderLifecycleRules.originalOutcome(null)).get("scheduling").equals("LEGACY_NOT_APPLIED"));
    }
}
