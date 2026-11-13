package cn.adc.dental;

import java.time.*;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** An ordinary Java main: no Spring context, JdbcTemplate, sockets, SQL mocks, or application server. */
public class FollowupRulesCheck {
    private static final List<Map<String,Object>> checks=new ArrayList<>();
    private static void check(String name,boolean passed) { checks.add(Map.of("name",name,"passed",passed));if(!passed)throw new AssertionError(name); }
    private static void reject(String name,int status,String code,Runnable action) {
        try { action.run();throw new AssertionError(name+" unexpectedly accepted"); }
        catch(ApiError error) { check(name,error.status==status&&error.code.equals(code)); }
    }
    private static Identity.Actor actor(long id,String kind,Long patient,Long dentist,String role,long clinic) { return new Identity.Actor(id,kind,patient,dentist,role==null?List.of():List.of(Map.of("role_code",role,"scope_kind","门店","clinic_id",clinic))); }
    private static Clock clock(String time) { return Clock.fixed(Instant.parse(time),ZoneOffset.UTC); }
    private static FollowupRules.Snapshot snapshot(String count,String recommendation,String date) { return new FollowupRules.Snapshot("10","100","20","1",date,recommendation,"待复诊",null,null,null,"2026-10-01T01:00Z","2026-10-01T01:00Z",count); }
    private static void jsonReject(String name,JsonMapper mapper,String json,Class<?> type) {
        try { mapper.readValue(json,type);throw new AssertionError(name+" unexpectedly accepted"); }
        catch(RuntimeException error) { check(name,true); }
    }
    public static void main(String[] args) {
        var report=new LinkedHashMap<String,Object>();report.put("mode","pure-memory-no-database-no-http");report.put("startedAt",Instant.now().toString());boolean passed=false;
        try { run();passed=true; }catch(Throwable error){report.put("error",error.getClass().getSimpleName()+": "+error.getMessage());}
        report.put("status",passed?"passed":"failed");report.put("checks",checks);report.put("finishedAt",Instant.now().toString());System.out.println(Store.encode(report));if(!passed)System.exit(1);
    }
    private static void run() {
        var suggested=FollowupRules.date("2026-10-01");var pending=new FollowupRules.Observation("待复诊",false,null);
        check("Shanghai day before midnight is Oct 4",FollowupRules.today(clock("2026-10-04T15:59:59Z")).equals(LocalDate.of(2026,10,4)));
        check("Shanghai day changes at UTC16",FollowupRules.today(clock("2026-10-04T16:00:00Z")).equals(LocalDate.of(2026,10,5)));
        check("exact suggested date plus three is not overdue",!FollowupRules.overdue(pending,suggested,clock("2026-10-04T15:59:59Z")));
        check("suggested date plus four is overdue",FollowupRules.overdue(pending,suggested,clock("2026-10-04T16:00:00Z")));
        check("captured list business date remains consistent",!FollowupRules.overdueOn(pending,suggested,LocalDate.of(2026,10,4))&&FollowupRules.overdueOn(pending,suggested,LocalDate.of(2026,10,5)));
        check("existing booking does not complete plan or remove overdue",FollowupRules.overdue(new FollowupRules.Observation("待复诊",true,null),suggested,clock("2026-10-05T12:00:00Z")));
        for(String result:FollowupRules.CONTACT_RESULTS) {var observed=new FollowupRules.Observation("待复诊",true,result);check("contact "+result+" keeps plan pending and overdue",FollowupRules.observedStatus(observed).equals("待复诊")&&FollowupRules.overdue(observed,suggested,clock("2026-10-05T12:00:00Z")));}
        check("actually completed plan is not overdue",!FollowupRules.overdue(new FollowupRules.Observation("已完成",true,"已约待诊"),suggested,clock("2026-10-05T12:00:00Z")));
        check("cancelled plan is not overdue",!FollowupRules.overdue(new FollowupRules.Observation("已取消",false,"暂不复诊"),suggested,clock("2026-10-05T12:00:00Z")));
        check("maximum MySQL DATE does not overflow overdue arithmetic",!FollowupRules.overdue(pending,FollowupRules.date("9999-12-31"),clock("2026-10-05T12:00:00Z")));
        reject("invalid calendar date rejected",400,"INVALID_INPUT",()->FollowupRules.date("2026-02-30"));
        reject("date below MySQL DATE range rejected",400,"INVALID_INPUT",()->FollowupRules.date("0999-12-31"));
        var owner=new FollowupRules.Owner(10,20,1,100);var dentist=actor(2,"员工",null,20L,"牙医",1);var front=actor(3,"员工",null,null,"前台",1);var patient=actor(4,"患者",10L,null,null,1);
        FollowupRules.authorize(dentist,owner,EncounterAccess.Mode.DENTIST);check("responsible dentist needs no front-desk role",true);
        FollowupRules.authorize(front,owner,EncounterAccess.Mode.FRONT);check("responsible-clinic front desk permitted",true);
        FollowupRules.authorize(patient,owner,EncounterAccess.Mode.PATIENT);check("session owner permitted",true);
        reject("other dentist denied",403,"FORBIDDEN",()->FollowupRules.authorize(actor(5,"员工",null,21L,"牙医",1),owner,EncounterAccess.Mode.DENTIST));
        reject("other clinic denied",403,"FORBIDDEN",()->FollowupRules.authorize(actor(5,"员工",null,null,"前台",2),owner,EncounterAccess.Mode.FRONT));
        reject("other patient denied",403,"FORBIDDEN",()->FollowupRules.authorize(actor(5,"患者",11L,null,null,1),owner,EncounterAccess.Mode.PATIENT));
        reject("front desk cannot cancel as dentist",403,"FORBIDDEN",()->FollowupRules.authorize(front,owner,EncounterAccess.Mode.DENTIST));
        reject("dentist binding without role denied",403,"FORBIDDEN",()->FollowupRules.authorize(actor(2,"员工",null,20L,null,1),owner,EncounterAccess.Mode.DENTIST));
        reject("patient-supplied snapshot cannot change actual ownership",403,"FORBIDDEN",()->FollowupRules.authorize(patient,new FollowupRules.Owner(11,20,1,100),EncounterAccess.Mode.PATIENT));
        var dentistScope=FollowupRules.dentistListScope(dentist,1,"待复诊",null,30);
        check("dentist list uses session binding without front role",dentistScope.dentist()==20&&dentistScope.clinic()==1&&dentistScope.after()==0&&dentistScope.limit()==30);
        check("dentist list scope includes own responsible plan",dentistScope.includes(owner));
        check("dentist list scope excludes another dentist in same clinic",!dentistScope.includes(new FollowupRules.Owner(10,21,1,101)));
        check("dentist list scope excludes same dentist in other clinic",!dentistScope.includes(new FollowupRules.Owner(10,20,2,101)));
        reject("dentist list requires active dentist role not front role",403,"FORBIDDEN",()->FollowupRules.dentistListScope(actor(6,"员工",null,20L,"前台",1),1,"all",null,30));
        reject("dentist list requires bound dentist",403,"FORBIDDEN",()->FollowupRules.dentistListScope(actor(6,"员工",null,null,"牙医",1),1,"all",null,30));
        reject("dentist list rejects other clinic authorization",403,"FORBIDDEN",()->FollowupRules.dentistListScope(dentist,2,"all",null,30));
        reject("patient cannot use dentist plan list",403,"FORBIDDEN",()->FollowupRules.dentistListScope(patient,1,"all",null,30));
        reject("dentist list rejects zero page size",400,"INVALID_INPUT",()->FollowupRules.dentistListScope(dentist,1,"all",null,0));
        reject("dentist list rejects page size beyond cap",400,"INVALID_INPUT",()->FollowupRules.dentistListScope(dentist,1,"all",null,101));
        reject("dentist list rejects unknown status",400,"INVALID_INPUT",()->FollowupRules.dentistListScope(dentist,1,"未知",null,30));
        reject("dentist list rejects invalid cursor",400,"INVALID_INPUT",()->FollowupRules.dentistListScope(dentist,1,"all","0",30));
        var nextPage=FollowupRules.dentistListScope(dentist,1,"all","501",100);check("dentist list valid cursor and maximum page size",nextPage.after()==501&&nextPage.limit()==100);
        var now=clock("2026-10-05T12:00:00Z");var completed=Instant.parse("2026-10-01T01:00:00Z");
        FollowupRules.source(new FollowupRules.Source(owner,true,"已到诊","已完成",completed),owner,now);check("actual completed source accepted",true);
        reject("arrival alone is not completed source",409,"FOLLOWUP_SOURCE_INVALID",()->FollowupRules.source(new FollowupRules.Source(owner,true,"已到诊","进行中",null),owner,now));
        reject("cross-patient source rejected",409,"FOLLOWUP_SOURCE_INVALID",()->FollowupRules.source(new FollowupRules.Source(new FollowupRules.Owner(11,20,1,100),true,"已到诊","已完成",completed),owner,now));
        reject("future completion evidence rejected",409,"FOLLOWUP_SOURCE_INVALID",()->FollowupRules.source(new FollowupRules.Source(owner,true,"已到诊","已完成",Instant.parse("2026-10-06T01:00:00Z")),owner,now));
        var same=snapshot("1","复查建议","2026-10-01");FollowupRules.compare(same,same);check("complete expected snapshot accepted",true);
        reject("same-second changed audit count rejects stale snapshot",409,"FOLLOWUP_CONFLICT",()->FollowupRules.compare(same,snapshot("2","复查建议","2026-10-01")));
        reject("changed recommendation rejects incomplete expectation",409,"FOLLOWUP_CONFLICT",()->FollowupRules.compare(same,snapshot("1","已改建议","2026-10-01")));
        reject("changed suggested date rejects stale snapshot",409,"FOLLOWUP_CONFLICT",()->FollowupRules.compare(same,snapshot("1","复查建议","2026-10-02")));
        FollowupRules.expected(snapshot("0","旧记录无本协议审计","2026-10-01"));check("legacy plan zero audit count supported",true);
        reject("cancelled plan cannot reopen through maintenance",409,"INVALID_STATE",()->FollowupRules.pending("已取消",false));
        reject("completed plan cannot reopen through maintenance",409,"INVALID_STATE",()->FollowupRules.pending("已完成",false));
        reject("linked actual completion awaits next hook rather than cancellation",409,"FOLLOWUP_COMPLETION_PENDING",()->FollowupRules.pending("待复诊",true));
        check("unsent pending reminder can be cancelled",FollowupRules.reminder("待发送",false)==FollowupRules.ReminderAction.CANCEL);
        check("in-flight reminder cannot be treated as unsent",FollowupRules.reminder("发送中",false)==FollowupRules.ReminderAction.RECONCILE);
        check("failed unknown result requires reconciliation",FollowupRules.reminder("失败",false)==FollowupRules.ReminderAction.RECONCILE);
        check("inconsistent pending with sent timestamp blocks",FollowupRules.reminder("待发送",true)==FollowupRules.ReminderAction.RECONCILE);
        check("sent history preserved",FollowupRules.reminder("已发送",true)==FollowupRules.ReminderAction.KEEP);
        check("already cancelled reminder preserved",FollowupRules.reminder("已取消",false)==FollowupRules.ReminderAction.KEEP);
        var call=new FollowupRules.Contact("300","2026-10-05T11:00:00Z","暂不复诊","人工电话实际结果","2026-10-06T11:00:00Z");FollowupRules.contact(call,now);check("real manual phone contact accepted",true);
        reject("future manual contact rejected",400,"INVALID_INPUT",()->FollowupRules.contact(new FollowupRules.Contact("301","2026-10-05T12:00:01Z","其他","说明",null),now));
        reject("next contact must follow actual contact",400,"INVALID_INPUT",()->FollowupRules.contact(new FollowupRules.Contact("301","2026-10-05T11:00:00Z","其他","说明","2026-10-05T11:00:00Z"),now));
        reject("manual contact requires factual note",400,"INVALID_INPUT",()->FollowupRules.contact(new FollowupRules.Contact("301","2026-10-05T11:00:00Z","其他"," ",null),now));
        reject("manual contact rejects invented result",400,"INVALID_INPUT",()->FollowupRules.contact(new FollowupRules.Contact("301","2026-10-05T11:00:00Z","系统已送达","说明",null),now));
        reject("limit over cap rejected",400,"INVALID_INPUT",()->FollowupRules.page("all",false,101));
        reject("limit zero rejected",400,"INVALID_INPUT",()->FollowupRules.page("all",false,0));
        reject("overdue incompatible status rejected",400,"INVALID_INPUT",()->FollowupRules.page("已完成",true,30));
        reject("null status policy rejects invalid input",400,"INVALID_INPUT",()->FollowupRules.observedStatus(new FollowupRules.Observation(null,false,null)));
        reject("null list status rejected",400,"INVALID_INPUT",()->FollowupRules.page(null,false,30));
        FollowupRules.page("all",true,100);check("bounded pending overdue filter supported",true);
        reject("invalid ID zero rejected",400,"INVALID_INPUT",()->Store.parseId("0"));
        reject("ID beyond signed bigint rejected",400,"INVALID_INPUT",()->Store.parseId("9223372036854775808"));
        reject("oversized audit/request text rejected",400,"INVALID_INPUT",()->FollowupRules.text("字".repeat(2001)));
        var builder=JsonMapper.builder();new JsonConfiguration().strictJson().customize(builder);var mapper=builder.build();
        var snapshotJson=mapper.readTree(Store.encode(same));check("snapshot serialization preserves audit count and string identity",snapshotJson.get("mutationCount").asString().equals("1")&&snapshotJson.get("patientId").isString()&&snapshotJson.get("patientId").asString().equals("10"));
        var create=mapper.readValue("{\"planId\":\"400\",\"suggestedDate\":\"2026-10-06\",\"recommendation\":\"复查\"}",FollowupRules.Creation.class);check("strict creation DTO accepts string ID",create.planId().equals("400"));
        jsonReject("numeric plan ID rejected by actual JSON configuration",mapper,"{\"planId\":400,\"suggestedDate\":\"2026-10-06\",\"recommendation\":\"复查\"}",FollowupRules.Creation.class);
        jsonReject("client patientId rejected",mapper,"{\"planId\":\"400\",\"suggestedDate\":\"2026-10-06\",\"recommendation\":\"复查\",\"patientId\":\"11\"}",FollowupRules.Creation.class);
        jsonReject("client contact channel rejected",mapper,"{\"contactId\":\"401\",\"contactedAt\":\"2026-10-05T11:00:00Z\",\"result\":\"已提醒\",\"note\":\"人工电话\",\"contactChannel\":\"短信\"}",FollowupRules.Contact.class);
        jsonReject("client completion status rejected",mapper,"{\"contactId\":\"401\",\"contactedAt\":\"2026-10-05T11:00:00Z\",\"result\":\"已提醒\",\"note\":\"人工电话\",\"status\":\"已完成\"}",FollowupRules.Contact.class);
    }
}
