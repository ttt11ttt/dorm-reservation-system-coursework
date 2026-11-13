package cn.adc.dental;

import java.time.*;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Actual shared pure policy/DTO checks. No JDBC mocks, Spring, HTTP or transaction model. */
public class ReminderMigrationRulesCheck {
    private static final List<Map<String,Object>> checks=new ArrayList<>();
    private static final Instant NOW=Instant.parse("2026-10-22T03:00:00Z");
    private static void check(String name,boolean ok){checks.add(Map.of("name",name,"passed",ok));if(!ok)throw new AssertionError(name);}
    private static void reject(String name,int status,String code,Runnable run){try{run.run();throw new AssertionError(name+" accepted");}catch(ApiError error){check(name,error.status==status&&error.code.equals(code));}}
    private static ReminderRules.Settings s(Integer lead,Integer days,String time,boolean inApp,boolean sms){return new ReminderRules.Settings(lead,days,time,inApp,sms);}
    private static ReminderRules.Business b(String phone){return new ReminderRules.Business(ReminderRules.Kind.APPOINTMENT,20,10,1,null,NOW.plusSeconds(7200),null,phone);}
    private static ReminderRules.Business f(String invalid){return new ReminderRules.Business(ReminderRules.Kind.FOLLOWUP,30,10,1,invalid,null,LocalDate.parse("2026-10-25"),null);}
    private static ReminderRules.Snapshot snapshot(String count){return new ReminderRules.Snapshot("9","1",60,1,"09:00:00",true,false,"1",NOW.toString(),count);}
    private static ReminderMigrationRules.Request request(String migrationId,ReminderRules.Snapshot expected,Integer lead,Integer days,String time,boolean inApp,boolean sms){return new ReminderMigrationRules.Request(migrationId,"9",expected,lead,days,time,inApp,sms,"改规则并安全重排");}
    private static ReminderRules.Draft draft(ReminderRules.Business b,ReminderRules.Settings s,String channel){return ReminderRules.plan(b,s,NOW,List.of()).actions().stream().filter(a->a.channel().equals(channel)&&a.draft()!=null).findFirst().orElseThrow().draft();}
    private static ReminderRules.History h(long id,ReminderRules.Draft d,String state,Instant sent){return new ReminderRules.History(id,true,d.channel(),d.plannedAt(),d.recipient(),d.content(),state,sent);}
    private static long count(ReminderMigrationRules.Evaluation e,String op){return e.result().actions().stream().filter(a->a.operation().equals(op)).count();}
    private static void jsonReject(String name,JsonMapper mapper,String value,Class<?> type){try{mapper.readValue(value,type);throw new AssertionError(name+" accepted");}catch(RuntimeException error){check(name,true);}}
    public static void main(String[] args){var report=new LinkedHashMap<String,Object>();report.put("mode","no-db-no-http");report.put("startedAt",Instant.now().toString());boolean passed=false;try{run();passed=true;}catch(Throwable error){report.put("error",error.getClass().getSimpleName()+": "+error.getMessage());}report.put("status",passed?"passed":"failed");report.put("checks",checks);report.put("finishedAt",Instant.now().toString());System.out.println(Store.encode(report));if(!passed)System.exit(1);}
    private static void run(){
        var station=s(60,1,"09:00:00",true,false);var both=s(60,1,"09:00:00",true,true);var business=b("10000000000");
        var binding=ReminderMigrationRules.bind(1,7,request("88",snapshot("0"),30,2,"09:00",true,false));check("fixed binding includes clinic actor operation and complete original snapshot",binding.clinicId().equals("1")&&binding.actorId().equals("7")&&binding.migrationId().equals("88")&&binding.request().expected().equals(snapshot("0")));
        check("proposed time canonicalized before identity registration",binding.request().followupSendTime().equals("09:00:00"));
        check("canonical repeat request has identical identity digest",ReminderMigrationRules.digest(binding).equals(ReminderMigrationRules.digest(ReminderMigrationRules.bind(1,7,request("88",snapshot("0"),30,2,"09:00:00",true,false)))));
        check("different actor changes bound digest",!ReminderMigrationRules.digest(binding).equals(ReminderMigrationRules.digest(ReminderMigrationRules.bind(1,8,request("88",snapshot("0"),30,2,"09:00:00",true,false)))));
        reject("new rule cannot enter existing-rule migration",400,"INVALID_INPUT",()->ReminderMigrationRules.bind(1,7,request("88",null,30,2,"09:00",true,false)));
        reject("other clinic snapshot rejected",400,"INVALID_INPUT",()->ReminderMigrationRules.bind(2,7,request("88",snapshot("0"),30,2,"09:00",true,false)));
        reject("unstable numeric operation identity rejected",400,"INVALID_INPUT",()->ReminderMigrationRules.bind(1,7,request("088",snapshot("0"),30,2,"09:00",true,false)));
        reject("incomplete paired followup setting rejected",400,"INVALID_INPUT",()->ReminderMigrationRules.bind(1,7,request("88",snapshot("0"),30,2,null,true,false)));
        reject("negative lead rejected",400,"INVALID_INPUT",()->ReminderMigrationRules.bind(1,7,request("88",snapshot("0"),-1,2,"09:00",true,false)));
        for(var limit:List.of(Map.entry("business",50),Map.entry("history",500),Map.entry("resource",200))){ReminderMigrationRules.limit(limit.getKey(),limit.getValue(),limit.getValue());check(limit.getKey()+" exact upper bound accepted",true);reject(limit.getKey()+" last extra item rejected instead of truncating",413,"MIGRATION_SCOPE_TOO_LARGE",()->ReminderMigrationRules.limit(limit.getKey(),limit.getValue()+1,limit.getValue()));}
        ReminderMigrationRules.auditSize("x".repeat(49150));check("actual JSON 48KiB exact bound accepted",true);
        reject("JSON encoding overhead counted at audit bound",413,"MIGRATION_AUDIT_TOO_LARGE",()->ReminderMigrationRules.auditSize("x".repeat(49151)));
        reject("UTF8 Chinese bytes not character count",413,"MIGRATION_AUDIT_TOO_LARGE",()->ReminderMigrationRules.auditSize("中".repeat(17000)));
        reject("JSON escaped characters counted",413,"MIGRATION_AUDIT_TOO_LARGE",()->ReminderMigrationRules.auditSize("\n".repeat(25000)));
        ReminderMigrationRules.fresh(NOW,NOW.plusSeconds(299));check("preview within five minutes accepted",true);
        reject("five minute boundary expires",409,"MIGRATION_PREVIEW_EXPIRED",()->ReminderMigrationRules.fresh(NOW,NOW.plusSeconds(300)));
        reject("clock before creation rejected",409,"MIGRATION_PREVIEW_EXPIRED",()->ReminderMigrationRules.fresh(NOW,NOW.minusSeconds(1)));
        ReminderMigrationRules.digestInput("a".repeat(64));check("SHA256 request syntax accepted",true);
        reject("malformed preview digest rejected",400,"INVALID_INPUT",()->ReminderMigrationRules.digestInput("a".repeat(63)));
        check("canonical map key order cannot invalidate digest",ReminderMigrationRules.digest(new LinkedHashMap<>(Map.of("b",2,"a",1))).equals(ReminderMigrationRules.digest(new TreeMap<>(Map.of("a",1,"b",2)))));
        reject("audit count catches same timestamp change back",409,"MIGRATION_PREVIEW_STALE",()->ReminderMigrationRules.same(snapshot("0"),snapshot("2"),"MIGRATION_PREVIEW_STALE"));
        check("changed last dependency detected",!ReminderMigrationRules.digest(List.of(Map.of("id","1","phone","10000000000"),Map.of("id","50","phone","10000000000"))).equals(ReminderMigrationRules.digest(List.of(Map.of("id","1","phone","10000000000"),Map.of("id","50","phone","20000000000")))));
        var locks=ReminderMigrationRules.lockOrder(Map.of("appointments",List.of(9L,2L),"patients",List.of(10L,3L,10L),"clinics",List.of(4L,1L),"dentists",List.of(8L),"followup_plans",List.of(11L),"clinic_openings",List.of(5L),"dentist_schedules",List.of(6L),"reminder_messages",List.of(20L)));
        check("lock order globally categories then numeric ascending and unique",locks.equals(List.of(new ReminderMigrationRules.Lock("clinics",1),new ReminderMigrationRules.Lock("clinics",4),new ReminderMigrationRules.Lock("dentists",8),new ReminderMigrationRules.Lock("patients",3),new ReminderMigrationRules.Lock("patients",10),new ReminderMigrationRules.Lock("clinic_openings",5),new ReminderMigrationRules.Lock("dentist_schedules",6),new ReminderMigrationRules.Lock("followup_plans",11),new ReminderMigrationRules.Lock("appointments",2),new ReminderMigrationRules.Lock("appointments",9),new ReminderMigrationRules.Lock("reminder_messages",20))));
        var old=draft(business,station,"站内");var queued=h(101,old,"待发送",null);var changed=ReminderMigrationRules.evaluate(business,s(30,1,"09:00",true,false),NOW,List.of(queued));
        check("valid pending does not permanently block safe rule migration",!changed.blocked()&&count(changed,"CANCEL")==1&&count(changed,"CREATE")==1);
        var clear=ReminderMigrationRules.evaluate(business,s(null,1,"09:00",true,true),NOW,List.of(queued));check("explicit null appointment lead authorizes unscheduled result",!clear.blocked()&&count(clear,"CANCEL")==1&&count(clear,"CREATE")==0&&clear.result().issues().contains("EXPLICIT_UNSCHEDULED"));
        var fc=ReminderMigrationRules.evaluate(f(null),s(60,null,null,true,true),NOW,List.of());check("explicit paired followup clear is not missing-parameter blocker",!fc.blocked()&&fc.result().issues().equals(List.of("EXPLICIT_UNSCHEDULED")));
        var disabled=ReminderMigrationRules.evaluate(business,s(60,1,"09:00",false,false),NOW,List.of(queued));check("explicit all-channel disable retires known pending",!disabled.blocked()&&count(disabled,"CANCEL")==1&&count(disabled,"CREATE")==0);
        var missing=ReminderMigrationRules.evaluate(b(null),both,NOW,List.of(queued));check("new enabled SMS without master phone blocks whole migration",missing.blocked()&&missing.result().issues().contains("SMS_RECIPIENT_MISSING"));
        check("format-invalid DEMO phone blocks enabled SMS",ReminderMigrationRules.evaluate(b("DEMO"),both,NOW,List.of()).blocked());
        check("unconfigured business kind does not require phone",!ReminderMigrationRules.evaluate(b(null),s(null,1,"09:00",true,true),NOW,List.of()).blocked());
        check("disabled SMS does not require phone",!ReminderMigrationRules.evaluate(b(null),station,NOW,List.of()).blocked());
        var sms=draft(business,both,"短信");var deliveredSms=h(102,sms,"已发送",NOW.minusSeconds(1));var suppressed=ReminderMigrationRules.evaluate(b(null),both,NOW,List.of(deliveredSms));
        check("sent same SMS suppresses even after phone removed",!suppressed.blocked()&&count(suppressed,"SENT_SUPPRESSED")==1&&count(suppressed,"CREATE")==1&&!suppressed.result().issues().contains("SMS_RECIPIENT_MISSING"));
        var deliveredStation=h(103,old,"已发送",NOW.minusSeconds(1));check("sent station cannot satisfy missing SMS recipient",ReminderMigrationRules.evaluate(b(null),both,NOW,List.of(deliveredStation)).blocked());
        check("changed phone does not re-send same sent SMS content",count(ReminderMigrationRules.evaluate(b("20000000000"),both,NOW,List.of(deliveredSms)),"SENT_SUPPRESSED")==1);
        check("new enabled channel creates for business with no prior messages",count(ReminderMigrationRules.evaluate(business,both,NOW,List.of()),"CREATE")==2);
        var restored=ReminderMigrationRules.evaluate(business,station,NOW,List.of(h(104,old,"已取消",null)));check("known unsent canceled natural identity restored",!restored.blocked()&&count(restored,"RESTORE")==1&&count(restored,"CREATE")==0);
        for(String state:List.of("发送中","失败")){var e=ReminderMigrationRules.evaluate(business,both,NOW,List.of(h(105,old,state,null)));check(state+" blocks entire migration despite other channel valid",e.blocked()&&count(e,"RECONCILE")==1);check(state+" blocks even explicit channel disable",ReminderMigrationRules.evaluate(business,s(60,1,"09:00",false,false),NOW,List.of(h(105,old,state,null))).blocked());}
        check("sent-at contradiction is not known unsent",ReminderMigrationRules.evaluate(business,station,NOW,List.of(h(106,old,"待发送",NOW))).blocked());
        check("foreign history blocks without cancellation or new identity",ReminderMigrationRules.evaluate(business,station,NOW,List.of(new ReminderRules.History(107,false,"站内",old.plannedAt(),old.recipient(),old.content(),"待发送",null))).blocked());
        var occupied=new ReminderRules.Draft("站内",old.plannedAt(),old.recipient(),"不同历史内容",false);var collision=ReminderMigrationRules.evaluate(business,station,NOW,List.of(h(108,occupied,"已取消",null)));check("historical key occupation blocks complete migration",collision.blocked()&&count(collision,"CREATE")==0);
        check("pending natural key incompatible content blocks even if policy proposes cancellation",ReminderMigrationRules.evaluate(business,station,NOW,List.of(h(109,occupied,"待发送",null))).blocked());
        var immediate=s(180,1,"09:00",true,false);var first=ReminderMigrationRules.evaluate(business,immediate,NOW,List.of());var later=ReminderMigrationRules.evaluate(business,immediate,NOW.plusSeconds(60),List.of());
        check("immediate create preview explicitly uses AT_COMMIT",first.semantic().getFirst().get("plannedAt").equals("AT_COMMIT"));
        check("AT_COMMIT semantic remains stable while business still valid",ReminderMigrationRules.digest(first.semantic()).equals(ReminderMigrationRules.digest(later.semantic())));
        check("actual immediate draft resolved at locked commit time",later.result().actions().getFirst().plannedAt().equals(NOW.plusSeconds(60)));
        var future=ReminderMigrationRules.evaluate(business,station,NOW,List.of());var crossed=ReminderMigrationRules.evaluate(business,station,NOW.plusSeconds(3600),List.of());check("future to immediate requires new preview",!ReminderMigrationRules.digest(future.semantic()).equals(ReminderMigrationRules.digest(crossed.semantic())));
        var started=ReminderMigrationRules.evaluate(business,immediate,NOW.plusSeconds(7200),List.of(queued));check("start boundary no longer creates and differs from preview",count(started,"CREATE")==0&&count(started,"CANCEL")==1&&!ReminderMigrationRules.digest(first.semantic()).equals(ReminderMigrationRules.digest(started.semantic())));
        var immediateDraft=first.result().actions().getFirst().draft();var oldImmediate=h(110,immediateDraft,"待发送",null);var reuse=ReminderMigrationRules.evaluate(business,immediate,NOW.plusSeconds(60),List.of(oldImmediate));check("immediate retry uses historical identity and original time",count(reuse,"REUSE")==1&&reuse.result().actions().getFirst().plannedAt().equals(NOW));
        check("source completion reconciliation blocks rather than silently clears",ReminderMigrationRules.evaluate(f("FOLLOWUP_COMPLETION_RECONCILIATION_REQUIRED"),station,NOW,List.of()).blocked());
        check("unrepresentable enabled target blocks",ReminderMigrationRules.evaluate(f(null),s(60,Integer.MAX_VALUE,"09:00",true,false),NOW,List.of()).blocked());
        var terminal=new ReminderRules.Business(ReminderRules.Kind.APPOINTMENT,20,10,1,"APPOINTMENT_STARTED_OR_INVALID",NOW.minusSeconds(1),null,null);var retired=ReminderMigrationRules.evaluate(terminal,both,NOW,List.of(queued,deliveredSms));check("invalid business retires only known pending with no recipient blocker",!retired.blocked()&&count(retired,"CANCEL")==1&&count(retired,"CREATE")==0);
        var builder=JsonMapper.builder();new JsonConfiguration().strictJson().customize(builder);var mapper=builder.build();String json=Store.encode(request("88",snapshot("0"),null,null,null,true,false));var decoded=mapper.readValue(json,ReminderMigrationRules.Request.class);check("request DTO preserves explicit null leads",decoded.appointmentLeadMinutes()==null&&decoded.followupLeadDays()==null);
        jsonReject("unknown client action list rejected",mapper,json.substring(0,json.length()-1)+",\"actions\":[]}",ReminderMigrationRules.Request.class);
        jsonReject("caller-supplied actor rejected",mapper,json.substring(0,json.length()-1)+",\"actorId\":\"99\"}",ReminderMigrationRules.Request.class);
        jsonReject("numeric migration identity rejected",mapper,json.replace("\"migrationId\":\"88\"","\"migrationId\":88"),ReminderMigrationRules.Request.class);
        jsonReject("fractional proposed lead rejected",mapper,json.replace("\"appointmentLeadMinutes\":null","\"appointmentLeadMinutes\":1.5"),ReminderMigrationRules.Request.class);
        jsonReject("commit cannot replace approved settings",mapper,"{\"previewDigest\":\""+"a".repeat(64)+"\",\"smsEnabled\":true}",ReminderMigrationRules.Commit.class);
        var parsedBinding=mapper.readValue(Store.encode(binding),ReminderMigrationRules.Binding.class);ReminderMigrationRules.same(binding,parsedBinding,"IDEMPOTENCY_CONFLICT");check("audit binding roundtrip preserves original actor request snapshot",true);
        ReminderMigrationRules.same(mapper.readTree(Store.encode(binding)),binding,"IDEMPOTENCY_CONFLICT");check("persisted JSON node and normalized binding canonicalize equally",true);
    }
}
