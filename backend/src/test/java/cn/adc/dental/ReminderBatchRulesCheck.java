package cn.adc.dental;

import java.time.*;
import java.util.*;

/** Actual B batch policy only; no SQL, parent-lock or transaction simulation. */
public class ReminderBatchRulesCheck {
    private static final List<Map<String,Object>> checks=new ArrayList<>();
    private static final Instant NOW=Instant.parse("2026-10-22T12:00:00Z");
    private static final ReminderRules.Settings SETTINGS=new ReminderRules.Settings(60,1,"09:00:00",true,true);
    private static void check(String name,boolean ok){checks.add(Map.of("name",name,"passed",ok));if(!ok)throw new AssertionError(name);}
    private static ReminderRules.Business appointment(String invalid){return new ReminderRules.Business(ReminderRules.Kind.APPOINTMENT,30,10,2,invalid,NOW.plusSeconds(7200),null,"10000000000");}
    private static ReminderRules.Business plan(String invalid){return new ReminderRules.Business(ReminderRules.Kind.FOLLOWUP,40,10,1,invalid,null,LocalDate.of(2026,10,25),"10000000000");}
    private static ReminderRules.Draft draft(ReminderRules.Business b,String channel){return ReminderRules.plan(b,SETTINGS,NOW,List.of()).actions().stream().filter(a->a.channel().equals(channel)&&a.draft()!=null).findFirst().orElseThrow().draft();}
    private static ReminderRules.History h(long id,ReminderRules.Draft d,String status){return new ReminderRules.History(id,true,d.channel(),d.plannedAt(),d.recipient(),d.content(),status,status.equals("已发送")?NOW.minusSeconds(1):null);}
    private static ReminderBatchRules.Input input(ReminderRules.Business b,ReminderRules.Settings s,List<ReminderRules.History> history){var chain=b.kind()==ReminderRules.Kind.APPOINTMENT?history.stream().map(h->new ReminderLifecycleRules.Scoped(b.id(),h)).toList():List.<ReminderLifecycleRules.Scoped>of();return new ReminderBatchRules.Input(b,s,history,chain,true);}
    private static long count(ReminderBatchRules.Batch batch,String action){return batch.items().stream().flatMap(i->i.result().actions().stream()).filter(a->a.operation().equals(action)).count();}
    public static void main(String[] args){var report=new LinkedHashMap<String,Object>();report.put("mode","no-db-no-http");report.put("startedAt",Instant.now().toString());boolean passed=false;try{run();passed=true;}catch(Throwable e){report.put("error",e.getClass().getSimpleName()+": "+e.getMessage());}report.put("status",passed?"passed":"failed");report.put("checks",checks);report.put("finishedAt",Instant.now().toString());System.out.println(Store.encode(report));if(!passed)System.exit(1);}
    private static void run(){
        var a=appointment(null);var p=plan(null);var combined=ReminderBatchRules.evaluate(List.of(input(a,SETTINGS,List.of()),input(p,SETTINGS,List.of())),NOW);check("batch can plan appointment and followup independently",count(combined,"CREATE")==4&&combined.items().size()==2);
        check("batch never marks any message sent",combined.items().stream().flatMap(i->i.result().actions().stream()).allMatch(x->x.status().equals("待发送")));
        var pd=draft(p,"站内");var ad=draft(a,"站内");var sms=draft(p,"短信");
        var changed=ReminderBatchRules.evaluate(List.of(input(p,SETTINGS,List.of(h(101,new ReminderRules.Draft("站内",pd.plannedAt().minusSeconds(86400),pd.recipient(),"旧建议日期",false),"待发送")))),NOW);check("changed followup can retire obsolete pending and create current plans",count(changed,"CANCEL")==1&&count(changed,"CREATE")==2);
        for(String status:List.of("发送中","失败")){var b=ReminderBatchRules.evaluate(List.of(input(p,SETTINGS,List.of(h(102,pd,status))),input(appointment("APPOINTMENT_STARTED_OR_INVALID"),SETTINGS,List.of(h(103,ad,"待发送")))),NOW);check(status+" plan result unknown retained without blocking terminal appointment evaluation",count(b,"RECONCILE")==1&&count(b,"CANCEL")==1&&Boolean.TRUE.equals(b.outcome().get("reconciliationRequired")));check(status+" no duplicate new plan in affected channel",b.items().getFirst().result().actions().stream().noneMatch(x->x.channel().equals("站内")&&Set.of("CREATE","RESTORE").contains(x.operation())));}
        var terminal=ReminderBatchRules.evaluate(List.of(input(plan("FOLLOWUP_TERMINAL"),SETTINGS,List.of(h(104,pd,"待发送"),h(105,sms,"发送中"))),input(appointment("APPOINTMENT_STARTED_OR_INVALID"),SETTINGS,List.of(h(106,ad,"待发送")))),NOW);check("terminal batch retires both business known pending and keeps unknown",count(terminal,"CANCEL")==2&&count(terminal,"RECONCILE")==1&&count(terminal,"CREATE")==0);
        check("same patient different reminder kinds do not wrongly deduplicate",count(combined,"CREATE")==4);
        var missing=ReminderBatchRules.evaluate(List.of(input(p,null,List.of())),NOW);check("missing rule yields no default followup",count(missing,"CREATE")==0&&((List<?>)missing.outcome().get("issues")).contains("RULE_MISSING"));
        var noPhone=new ReminderRules.Business(ReminderRules.Kind.FOLLOWUP,40,10,1,null,null,LocalDate.of(2026,10,25),null);var phone=ReminderBatchRules.evaluate(List.of(input(noPhone,SETTINGS,List.of())),NOW);check("no phone keeps patient master in-app and reports SMS missing",count(phone,"CREATE")==1&&((List<?>)phone.outcome().get("issues")).contains("SMS_RECIPIENT_MISSING"));
        var sent=ReminderBatchRules.evaluate(List.of(input(p,SETTINGS,List.of(h(107,pd,"已发送")))),NOW);check("same content sent followup suppresses corresponding channel",count(sent,"SENT_SUPPRESSED")==1&&count(sent,"CREATE")==1);
        var reused=ReminderBatchRules.evaluate(List.of(input(p,SETTINGS,List.of(h(108,pd,"待发送")))),NOW);check("existing plan natural identity is reused",count(reused,"REUSE")==1);
        try{ReminderBatchRules.evaluate(List.of(input(p,SETTINGS,List.of()),input(p,SETTINGS,List.of())),NOW);throw new AssertionError("duplicate batch accepted");}catch(IllegalArgumentException expected){check("duplicate business cannot cause two write passes",true);}
        var owner=new FollowupRules.Owner(10,2,1,11);var base=new FollowupLinkRules.Plan(40,owner,"待复诊",null,null);var clinical=new FollowupLinkRules.Clinical(30,10,40L,true,"已到诊","已完成",NOW);var decision=FollowupLinkRules.completion(base,clinical,true);check("shared actual completion copies precise identity and time",decision.updatePlan()&&decision.completedId()==30&&decision.completedAt().equals(NOW));
        var canceled=FollowupLinkRules.completion(new FollowupLinkRules.Plan(40,owner,"已取消",null,null),clinical,true);check("canceled plan never restarted by completion",!canceled.updatePlan()&&canceled.status().equals("已取消"));
        var prior=FollowupLinkRules.completion(new FollowupLinkRules.Plan(40,owner,"已完成",29L,NOW.minusSeconds(1)),clinical,true);check("first completion identity remains preserved",!prior.updatePlan()&&prior.completedId()==29&&prior.reconciliation());
        var legacy=FollowupLinkRules.completion(base,clinical,false);check("legacy completion read does not secretly finish plan",!legacy.updatePlan()&&legacy.reconciliation());
        var notification=FollowupLinkRules.notification("发送中",false,true,true);check("compatibility cleanup keeps unknown result and flags reconciliation",!notification.cancel()&&notification.reconcile());
        try{ReminderBatchRules.evaluate(List.of(new ReminderBatchRules.Input(p,SETTINGS,List.of(),List.of(),false)),NOW);throw new AssertionError("unclosed parents accepted");}catch(ApiError expected){check("incomplete resource facts must retry before applying batch",expected.status==409&&expected.code.equals("RETRY_TRANSACTION"));}
        var historical=Map.of("scheduling","PARTIAL","createdCount",1,"cancelledCount",2,"reconciliationRequired",true,"delivery","NOT_IMPLEMENTED");check("followup replay returns fixed historical outcome rather than new planning",ReminderLifecycleRules.originalOutcome(historical)==historical);var absent=ReminderLifecycleRules.originalOutcome(null);check("legacy followup audit without outcome is explicit",absent instanceof Map<?,?> map&&map.get("scheduling").equals("LEGACY_NOT_APPLIED"));
    }
}
