package cn.adc.dental;

import java.time.*;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** No Spring context, database fixtures/mocks, HTTP, sockets or message delivery. */
public class ReminderRulesCheck {
    private static final List<Map<String,Object>> checks=new ArrayList<>();
    private static final Instant NOW=Instant.parse("2026-10-01T01:00:00Z");
    private static void check(String name,boolean ok){checks.add(Map.of("name",name,"passed",ok));if(!ok)throw new AssertionError(name);}
    private static void reject(String name,int status,String code,Runnable run){try{run.run();throw new AssertionError(name+" accepted");}catch(ApiError error){check(name,error.status==status&&error.code.equals(code));}}
    private static Identity.Actor actor(String kind,String role,long clinic){return new Identity.Actor(1,kind,kind.equals("患者")?10L:null,null,List.of(Map.of("role_code",role,"scope_kind","门店","clinic_id",clinic)));}
    private static ReminderRules.Settings settings(Integer minutes,Integer days,String time,boolean inApp,boolean sms){return new ReminderRules.Settings(minutes,days,time,inApp,sms);}
    private static ReminderRules.Business appointment(String invalid,String phone){return new ReminderRules.Business(ReminderRules.Kind.APPOINTMENT,20,10,1,invalid,NOW.plusSeconds(7200),null,phone);}
    private static ReminderRules.Business followup(String invalid,LocalDate date){return new ReminderRules.Business(ReminderRules.Kind.FOLLOWUP,30,10,1,invalid,null,date,"10000000000");}
    private static long count(ReminderRules.Result r,String operation){return r.actions().stream().filter(a->a.operation().equals(operation)).count();}
    private static ReminderRules.Draft draft(ReminderRules.Result r,String channel){return r.actions().stream().filter(a->a.operation().equals("CREATE")&&a.channel().equals(channel)).findFirst().orElseThrow().draft();}
    private static ReminderRules.History history(long id,ReminderRules.Draft d,String status,Instant sent){return new ReminderRules.History(id,true,d.channel(),d.plannedAt(),d.recipient(),d.content(),status,sent);}
    private static ReminderRules.Snapshot snapshot(String count){return new ReminderRules.Snapshot("1","1",60,1,"09:00:00",true,false,"1","2026-10-01T01:00Z",count);}
    private static void jsonReject(String name,JsonMapper mapper,String value){try{mapper.readValue(value,ReminderRules.Save.class);throw new AssertionError(name+" accepted");}catch(RuntimeException error){check(name,true);}}
    public static void main(String[] args){var report=new LinkedHashMap<String,Object>();report.put("mode","no-db-no-http");report.put("startedAt",Instant.now().toString());boolean passed=false;try{run();passed=true;}catch(Throwable error){report.put("error",error.getClass().getSimpleName()+": "+error.getMessage());}report.put("status",passed?"passed":"failed");report.put("checks",checks);report.put("finishedAt",Instant.now().toString());System.out.println(Store.encode(report));if(!passed)System.exit(1);}
    private static void run(){
        var station=settings(60,1,"09:00:00",true,false);var both=settings(60,1,"09:00:00",true,true);var b=appointment(null,"10000000000");
        ReminderRules.authorize(actor("员工","运营人员／店长",1),1,true);check("exact operations role may maintain own clinic rules",true);
        ReminderRules.authorize(actor("员工","前台",1),1,false);check("front desk may preview own clinic",true);
        reject("front desk cannot maintain configuration",403,"FORBIDDEN",()->ReminderRules.authorize(actor("员工","前台",1),1,true));
        reject("half-width invented role code rejected",403,"FORBIDDEN",()->ReminderRules.authorize(actor("员工","运营人员/店长",1),1,true));
        reject("other clinic operations denied",403,"FORBIDDEN",()->ReminderRules.authorize(actor("员工","运营人员／店长",2),1,true));
        reject("patient cannot use staff planning even with fake grant",403,"FORBIDDEN",()->ReminderRules.authorize(actor("患者","运营人员／店长",1),1,false));
        reject("headquarters has no implicit operations permission",403,"FORBIDDEN",()->ReminderRules.authorize(actor("员工","总部管理",1),1,true));
        ReminderRules.settings(settings(null,null,null,false,false));check("all nullable leads remain unset without defaults",true);
        ReminderRules.settings(settings(0,0,"00:00",true,false));check("zero leads and midnight are legal",true);
        check("time normalizes to whole seconds",ReminderRules.canonicalTime("09:05").equals("09:05:00"));
        reject("negative appointment lead rejected",400,"INVALID_INPUT",()->ReminderRules.settings(settings(-1,1,"09:00",true,false)));
        reject("negative followup lead rejected",400,"INVALID_INPUT",()->ReminderRules.settings(settings(1,-1,"09:00",true,false)));
        reject("followup days require paired time",400,"INVALID_INPUT",()->ReminderRules.settings(settings(1,1,null,true,false)));
        reject("followup time requires paired days",400,"INVALID_INPUT",()->ReminderRules.settings(settings(1,null,"09:00",true,false)));
        reject("invalid Beijing time rejected",400,"INVALID_INPUT",()->ReminderRules.time("24:01"));
        reject("seconds fraction rejected",400,"INVALID_INPUT",()->ReminderRules.time("09:00:00.1"));
        reject("channel flags cannot be defaulted",400,"INVALID_INPUT",()->ReminderRules.settings(new ReminderRules.Settings(60,1,"09:00",null,false)));
        check("initial empty message set allows rule change",ReminderRules.ruleChangeSafe(List.of()));
        check("sent cancelled history does not block safe rule change",ReminderRules.ruleChangeSafe(List.of("已发送","已取消")));
        for(String status:List.of("待发送","发送中","失败"))check("rule change blocks "+status,!ReminderRules.ruleChangeSafe(List.of(status)));
        ReminderRules.compare(snapshot("1"),snapshot("1"));check("complete configuration snapshot accepted",true);
        reject("same-second config audit count rejects stale snapshot",409,"RULE_SNAPSHOT_CONFLICT",()->ReminderRules.compare(snapshot("1"),snapshot("2")));
        reject("missing expected cannot overwrite existing config",409,"RULE_SNAPSHOT_CONFLICT",()->ReminderRules.compare(null,snapshot("0")));
        var first=ReminderRules.plan(b,station,NOW,List.of());var d=draft(first,"站内");
        check("appointment start minus configured lead",d.plannedAt().equals(NOW.plusSeconds(3600)));
        check("in-app recipient is patient master not account",d.recipient().equals("patient:10"));
        check("no sender or clinical content in generated plan",!d.content().contains("过敏")&&!d.content().contains("治疗需求")&&first.actions().getFirst().status().equals("待发送"));
        check("missing configuration explicitly reported",ReminderRules.plan(b,null,NOW,List.of()).issues().contains("RULE_MISSING"));
        check("missing appointment lead does not invent default",ReminderRules.plan(b,settings(null,null,null,true,false),NOW,List.of()).issues().contains("APPOINTMENT_LEAD_MISSING"));
        check("no enabled channel produces no new message",count(ReminderRules.plan(b,settings(60,1,"09:00",false,false),NOW,List.of()),"CREATE")==0);
        check("missing followup config explicitly reported",ReminderRules.plan(followup(null,LocalDate.of(2026,10,5)),settings(60,null,null,true,false),NOW,List.of()).issues().contains("FOLLOWUP_CONFIG_MISSING"));
        var f=ReminderRules.plan(followup(null,LocalDate.of(2026,10,5)),station,NOW,List.of());check("followup DATE minus days plus Shanghai09 becomes UTC01",draft(f,"站内").plannedAt().equals(Instant.parse("2026-10-04T01:00:00Z")));
        var midnight=ReminderRules.plan(followup(null,LocalDate.of(2026,10,5)),settings(60,0,"00:30",true,false),NOW,List.of());check("Shanghai midnight crosses UTC date correctly",draft(midnight,"站内").plannedAt().equals(Instant.parse("2026-10-04T16:30:00Z")));
        check("timestamp calculation range violation reported",ReminderRules.plan(b,settings(Integer.MAX_VALUE,1,"09:00",true,false),NOW,List.of()).issues().contains("PLANNED_TIME_OUT_OF_RANGE"));
        check("followup arithmetic outside TIMESTAMP rejected",ReminderRules.plan(followup(null,LocalDate.of(1000,1,1)),settings(60,Integer.MAX_VALUE,"09:00",true,false),NOW,List.of()).issues().contains("PLANNED_TIME_OUT_OF_RANGE"));
        check("appointment at exact start is no longer valid",ReminderRules.appointmentValidity(true,"待到诊",false,"未开始",NOW,NOW).equals("APPOINTMENT_STARTED_OR_INVALID"));
        check("early actual attendance suppresses appointment reminder",ReminderRules.appointmentValidity(true,"已到诊",true,"未开始",NOW.plusSeconds(7200),NOW)!=null);
        for(String status:List.of("患者取消","已改约","待核实"))check(status+" never becomes eligible from attended false",ReminderRules.appointmentValidity(true,status,false,"未开始",NOW.plusSeconds(7200),NOW)!=null);
        check("inactive source produces no reminder",count(ReminderRules.plan(appointment("RESOURCE_INACTIVE","10000000000"),station,NOW,List.of()),"CREATE")==0);
        for(String status:List.of("已完成","已取消"))check("followup "+status+" is not backfilled",ReminderRules.followupValidity(true,status,false).equals("FOLLOWUP_TERMINAL"));
        check("legacy linked completion blocks stale followup notice",ReminderRules.followupValidity(true,"待复诊",true).equals("FOLLOWUP_COMPLETION_RECONCILIATION_REQUIRED"));
        var missingPhone=ReminderRules.plan(appointment(null,"DEMO-PHONE-FAMILY"),both,NOW,List.of());check("synthetic phone is not a real-format SMS target",missingPhone.issues().contains("SMS_RECIPIENT_MISSING")&&count(missingPhone,"CREATE")==1);
        check("blank phone not fabricated",ReminderRules.phone(" ")==null);
        check("phone normalization is explicit format only",ReminderRules.phone("+86 (100) 0000-0000").equals("+8610000000000"));
        check("masked SMS never returns full phone",ReminderRules.mask("10000000000","短信").equals("***0000"));
        var bothPlan=ReminderRules.plan(b,both,NOW,List.of());check("only explicitly enabled two channels planned",count(bothPlan,"CREATE")==2);
        var queued=history(100,d,"待发送",null);var repeat=ReminderRules.plan(b,station,NOW.plusSeconds(1),List.of(queued));check("future plan retry reuses original natural identity",count(repeat,"CREATE")==0&&count(repeat,"REUSE")==1&&repeat.actions().getFirst().messageId()==100);
        var immediate=ReminderRules.plan(b,settings(180,1,"09:00",true,false),NOW,List.of());var immediateDraft=draft(immediate,"站内");check("past desired while appointment remains valid proposes immediate once",immediateDraft.immediate()&&immediateDraft.plannedAt().equals(NOW));
        var nextImmediate=ReminderRules.plan(b,settings(180,1,"09:00",true,false),NOW.plusSeconds(60),List.of(history(101,immediateDraft,"待发送",null)));
        check("immediate retries do not allocate a message every now",count(nextImmediate,"CREATE")==0&&count(nextImmediate,"REUSE")==1&&nextImmediate.actions().getFirst().plannedAt().equals(NOW));
        var duplicate=history(102,new ReminderRules.Draft("站内",NOW.minusSeconds(1),immediateDraft.recipient(),immediateDraft.content(),true),"待发送",null);
        var dedup=ReminderRules.plan(b,settings(180,1,"09:00",true,false),NOW.plusSeconds(60),List.of(history(101,immediateDraft,"待发送",null),duplicate));check("duplicate pending content reduced to one deterministic identity",count(dedup,"REUSE")==1&&count(dedup,"CANCEL")==1&&count(dedup,"CREATE")==0);
        var alreadySent=history(103,new ReminderRules.Draft("站内",NOW.minusSeconds(3600),d.recipient(),d.content(),false),"已发送",NOW.minusSeconds(3500));var delivered=ReminderRules.plan(b,station,NOW,List.of(alreadySent,queued));
        check("same sent content suppressed despite different current planned time",count(delivered,"SENT_SUPPRESSED")==1&&count(delivered,"CREATE")==0&&count(delivered,"CANCEL")==1);
        check("sent in-app does not suppress separate SMS channel",count(ReminderRules.plan(b,both,NOW,List.of(alreadySent)),"CREATE")==1);
        var sms=draft(bothPlan,"短信");var oldSms=history(104,new ReminderRules.Draft("短信",sms.plannedAt(),"20000000000",sms.content(),false),"已发送",NOW.minusSeconds(1));check("phone change does not resend same delivered content",count(ReminderRules.plan(b,both,NOW,List.of(oldSms)),"SENT_SUPPRESSED")==1);
        var restored=ReminderRules.plan(b,station,NOW,List.of(history(105,d,"已取消",null)));check("known unsent cancelled matching identity restored not recreated",count(restored,"RESTORE")==1&&count(restored,"CREATE")==0&&restored.actions().getFirst().messageId()==105);
        var immediateRestored=ReminderRules.plan(b,settings(180,1,"09:00",true,false),NOW.plusSeconds(60),List.of(history(106,immediateDraft,"已取消",null)));check("cancelled immediate retains original message and planned time",count(immediateRestored,"RESTORE")==1&&immediateRestored.actions().getFirst().plannedAt().equals(NOW));
        for(String status:List.of("发送中","失败")){
            var unknown=ReminderRules.plan(b,station,NOW,List.of(history(107,d,status,null),queued));check(status+" retained and prevents blind new identity",unknown.reconciliation()&&count(unknown,"CREATE")==0&&count(unknown,"RESTORE")==0&&count(unknown,"RECONCILE")==1);
            check(status+" channel blocks known queued duplicates safely",count(unknown,"CANCEL")==1);
        }
        check("unknown one channel does not fabricate other channel success",ReminderRules.plan(b,both,NOW,List.of(history(107,d,"发送中",null))).reconciliation()&&count(ReminderRules.plan(b,both,NOW,List.of(history(107,d,"发送中",null))),"CREATE")==1);
        var inconsistent=ReminderRules.plan(b,station,NOW,List.of(history(108,d,"待发送",NOW.minusSeconds(1))));check("pending with sent time is never treated as unsent",inconsistent.reconciliation()&&count(inconsistent,"CANCEL")==0&&count(inconsistent,"CREATE")==0);
        var wrong=new ReminderRules.History(109,false,d.channel(),d.plannedAt(),d.recipient(),d.content(),"待发送",null);var foreign=ReminderRules.plan(b,station,NOW,List.of(wrong));check("foreign recipient or clinic record is kept for reconciliation",foreign.reconciliation()&&count(foreign,"CANCEL")==0&&count(foreign,"CREATE")==0);
        var different=history(110,new ReminderRules.Draft("站内",d.plannedAt(),d.recipient(),"旧内容",false),"待发送",null);var collision=ReminderRules.plan(b,station,NOW,List.of(different));check("natural key collision cancels obsolete pending but never rewrites history",collision.reconciliation()&&count(collision,"CANCEL")==1&&count(collision,"CREATE")==0);
        check("cancelled key with different content requires manual review",ReminderRules.plan(b,station,NOW,List.of(history(110,new ReminderRules.Draft("站内",d.plannedAt(),d.recipient(),"旧内容",false),"已取消",null))).reconciliation());
        var changedLead=ReminderRules.plan(b,settings(30,1,"09:00",true,false),NOW,List.of(queued));check("changed desired future time cancels and replans one record",count(changedLead,"CANCEL")==1&&count(changedLead,"CREATE")==1);
        var invalid=ReminderRules.plan(appointment("APPOINTMENT_STARTED_OR_INVALID","10000000000"),station,NOW,List.of(queued,alreadySent));check("invalid business cancels only known unsent and keeps sent history",count(invalid,"CANCEL")==1&&count(invalid,"KEEP")==1&&count(invalid,"CREATE")==0);
        var noChannels=ReminderRules.plan(b,settings(60,1,"09:00",false,false),NOW,List.of(queued));check("disabled channels retire old pending instead of silently using it",count(noChannels,"CANCEL")==1&&count(noChannels,"CREATE")==0);
        var builder=JsonMapper.builder();new JsonConfiguration().strictJson().customize(builder);var mapper=builder.build();String value="{\"ruleId\":\"1\",\"expected\":null,\"appointmentLeadMinutes\":null,\"followupLeadDays\":null,\"followupSendTime\":null,\"inAppEnabled\":true,\"smsEnabled\":false,\"reason\":\"显式配置\"}";
        var request=mapper.readValue(value,ReminderRules.Save.class);ReminderRules.settings(ReminderRules.settings(request));check("configuration DTO preserves null leads",request.appointmentLeadMinutes()==null&&request.followupLeadDays()==null);
        jsonReject("numeric rule ID rejected",mapper,value.replace("\"ruleId\":\"1\"","\"ruleId\":1"));
        jsonReject("fractional lead rejected",mapper,value.replace("\"appointmentLeadMinutes\":null","\"appointmentLeadMinutes\":1.5"));
        jsonReject("arbitrary patient binding field rejected",mapper,value.replace("}",",\"patientId\":\"99\"}"));
        var snapshot=mapper.readTree(Store.encode(snapshot("2")));check("configuration audit count serialized as string",snapshot.get("mutationCount").isString()&&snapshot.get("mutationCount").asString().equals("2"));
    }
}
