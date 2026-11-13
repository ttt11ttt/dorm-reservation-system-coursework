package cn.adc.dental;

import java.time.Instant;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Pure organization policy and DTO checks; no SQL, Spring, mocks or HTTP. */
public class OrganizationRulesCheck {
    private static final List<Map<String,Object>> checks=new ArrayList<>();
    private static void check(String name,boolean okay){checks.add(Map.of("name",name,"passed",okay));if(!okay)throw new AssertionError(name);}
    private static void reject(String name,String code,Runnable work){try{work.run();throw new AssertionError(name+" accepted");}catch(ApiError e){check(name,code.equals(e.code));}}
    private static Identity.Actor actor(String kind,String role,String scope,Long clinic){var g=new LinkedHashMap<String,Object>();g.put("role_code",role);g.put("scope_kind",scope);g.put("clinic_id",clinic);return new Identity.Actor(1,kind,null,null,List.of(g));}
    public static void main(String[] args){var report=new LinkedHashMap<String,Object>();report.put("mode","pure-memory-no-db-no-http");report.put("startedAt",Instant.now().toString());boolean okay=false;try{run();okay=true;}catch(Throwable e){report.put("error",e.getClass().getSimpleName()+": "+e.getMessage());}report.put("status",okay?"passed":"failed");report.put("checks",checks);report.put("finishedAt",Instant.now().toString());System.out.println(Store.encode(report));if(!okay)System.exit(1);}
    private static void run(){
        var chain=actor("员工","运营人员／店长","连锁",null);var local=actor("员工","运营人员／店长","门店",10L);
        check("chain operations grant authorizes global create",OrganizationRules.chain(chain));
        check("local operations grant does not authorize global create",!OrganizationRules.chain(local));
        OrganizationRules.clinicRead(local,10);check("local operations may read own clinic",true);
        reject("local operations cannot read other clinic","FORBIDDEN",()->OrganizationRules.clinicRead(local,11));
        reject("account admin cannot create organization","FORBIDDEN",()->OrganizationRules.chainOnly(actor("员工","账号管理","连锁",null)));
        reject("headquarters cannot create organization","FORBIDDEN",()->OrganizationRules.chainOnly(actor("员工","总部管理","连锁",null)));
        reject("patient cannot use copied operations grant","FORBIDDEN",()->OrganizationRules.chainOnly(actor("患者","运营人员／店长","连锁",null)));
        check("area decimal canonicalizes without floating point",OrganizationRules.area("12").equals("12.00")&&OrganizationRules.area("12.5").equals("12.50"));
        reject("zero area denied","INVALID_INPUT",()->OrganizationRules.area("0"));
        reject("overprecision area denied","INVALID_INPUT",()->OrganizationRules.area("1.234"));
        reject("oversize area denied","INVALID_INPUT",()->OrganizationRules.area("100000000"));
        check("birth date canonicalizes",OrganizationRules.birthDate("1990-01-02").equals("1990-01-02"));
        reject("malformed birth date denied","INVALID_INPUT",()->OrganizationRules.birthDate("1990-02-30"));
        reject("date outside MySQL DATE range denied","INVALID_INPUT",()->OrganizationRules.birthDate("0999-12-31"));
        var row=new LinkedHashMap<String,Object>();row.put("dentist_id",20L);row.put("employee_no","D20");row.put("name","李医生");row.put("active",1);row.put("home_address","PRIVATE_HOME");row.put("birth_date","1990-01-02");row.put("phone","PRIVATE_PHONE");row.put("email","PRIVATE_EMAIL");
        var summary=OrganizationRules.dentistSummary(row,1);check("dentist summary excludes private fields",!Store.encode(summary).contains("PRIVATE_")&&!summary.containsKey("birthDate"));
        check("summary IDs and count are strings",summary.get("dentistId").equals("20")&&summary.get("mutationCount").equals("1"));
        var canonical=new LinkedHashMap<String,Object>();canonical.put("dentistId","20");canonical.put("employeeNo","D20");canonical.put("name","李医生");canonical.put("birthDate","1990-01-02");canonical.put("homeAddress","PRIVATE_HOME");canonical.put("phone","PRIVATE_PHONE");canonical.put("email","PRIVATE_EMAIL");
        String first=OrganizationService.fingerprint("org_dentist.create.v1",canonical);check("canonical fingerprint deterministic",first.equals(OrganizationService.fingerprint("org_dentist.create.v1",canonical)));
        var changed=new LinkedHashMap<>(canonical);changed.put("phone","OTHER_PHONE");check("private content changes fingerprint",!first.equals(OrganizationService.fingerprint("org_dentist.create.v1",changed)));
        check("fingerprint domain separates resources",!first.equals(OrganizationService.fingerprint("org_clinic.create.v1",canonical)));
        OrganizationRules.sameReceipt(1,first,1,first);check("same actor and content accepts original receipt",true);
        reject("different actor cannot replay receipt","IDEMPOTENCY_CONFLICT",()->OrganizationRules.sameReceipt(1,first,2,first));
        reject("changed content cannot replay receipt","IDEMPOTENCY_CONFLICT",()->OrganizationRules.sameReceipt(1,first,1,OrganizationService.fingerprint("org_dentist.create.v1",changed)));
        var strict=JsonMapper.builder();new JsonConfiguration().strictJson().customize(strict);var mapper=strict.build();
        String body="{\"dentistId\":\"20\",\"employeeNo\":\"D20\",\"name\":\"李医生\",\"birthDate\":\"1990-01-02\",\"homeAddress\":\"PRIVATE_HOME\",\"phone\":\"PRIVATE_PHONE\",\"email\":\"PRIVATE_EMAIL\"}";
        var dto=mapper.readValue(body,OrganizationApi.DentistCreate.class);check("dentist DTO accepts required personal fields",dto.homeAddress().equals("PRIVATE_HOME"));
        check("dentist DTO serialization and toString redact personal fields",!mapper.writeValueAsString(dto).contains("PRIVATE_")&&!dto.toString().contains("PRIVATE_"));
        try{mapper.readValue(body.replace("\"email\"","\"actorId\":\"9\",\"email\""),OrganizationApi.DentistCreate.class);throw new AssertionError("unknown accepted");}catch(RuntimeException expected){check("unknown DTO field rejected",true);}
        OrganizationRules.bound(512,512);check("exact closure bound accepted",true);
        reject("oversize closure denied","ORG_SCOPE_TOO_LARGE",()->OrganizationRules.bound(513,512));
        reject("oversize serialized response denied","ORG_PAYLOAD_TOO_LARGE",()->OrganizationRules.size("牙".repeat(OrganizationRules.MAX_BYTES/3)));
        var w=OpeningRules.window("2027-06-01T23:30:00+08:00","2027-06-02T00:30:00+08:00");
        check("actual opening may cross Shanghai midnight",w.start().toInstant().equals(java.time.Instant.parse("2027-06-01T15:30:00Z"))&&w.end().toInstant().equals(java.time.Instant.parse("2027-06-01T16:30:00Z")));
        check("equivalent offsets normalize to same instant",w.start().toInstant().equals(OpeningRules.window("2027-06-01T15:30:00Z","2027-06-01T16:30:00Z").start().toInstant()));
        check("exact 24 hour opening accepted",OpeningRules.window("2027-06-01T00:00:00+08:00","2027-06-02T00:00:00+08:00")!=null);
        reject("over 24 hours denied","INVALID_INPUT",()->OpeningRules.window("2027-06-01T00:00:00+08:00","2027-06-02T00:00:01+08:00"));
        reject("zero length opening denied","INVALID_INPUT",()->OpeningRules.window("2027-06-01T00:00:00+08:00","2027-06-01T00:00:00+08:00"));
        reject("fractional second denied","INVALID_INPUT",()->OpeningRules.window("2027-06-01T00:00:00.100+08:00","2027-06-01T01:00:00+08:00"));
        reject("offset required","INVALID_INPUT",()->OpeningRules.window("2027-06-01T00:00:00","2027-06-01T01:00:00"));
        var a=java.time.Instant.parse("2027-01-01T00:00:00Z");var b=a.plusSeconds(3600);var c=b.plusSeconds(3600);
        check("half-open adjacent windows allowed",!OpeningRules.overlaps(a,b,b,c));
        check("half-open intersecting windows conflict",OpeningRules.overlaps(a,b,b.minusSeconds(1),c));
        reject("opening start must be strictly future","OPENING_NOT_FUTURE",()->OpeningRules.future(new OpeningRules.Window(java.sql.Timestamp.from(a),java.sql.Timestamp.from(b)),a));
        OpeningRules.future(new OpeningRules.Window(java.sql.Timestamp.from(a.plusSeconds(1)),java.sql.Timestamp.from(b)),a);check("opening after server instant accepted",true);
        check("null and blank notes canonicalize equally",OpeningRules.note(null)==null&&OpeningRules.note("  ")==null);
        check("note trims without losing content",OpeningRules.note("  周末开放  ").equals("周末开放"));
        reject("note above 8000 characters denied","INVALID_INPUT",()->OpeningRules.note("x".repeat(8001)));
        var openingRow=new LinkedHashMap<String,Object>();openingRow.put("opening_id",30L);openingRow.put("clinic_id",10L);openingRow.put("start_at",w.start());openingRow.put("end_at",w.end());openingRow.put("status","有效");openingRow.put("note",null);openingRow.put("updated_by",1L);openingRow.put("updated_at",java.sql.Timestamp.from(a));
        var opening=OpeningRules.snapshot(openingRow,1);check("opening snapshot includes complete public DDL state",opening.get("openingId").equals("30")&&opening.get("clinicId").equals("10")&&opening.get("mutationCount").equals("1")&&opening.containsKey("updatedAt")&&opening.containsKey("note"));
        check("opening time renders explicit Shanghai offset",opening.get("startAt").equals("2027-06-01T23:30:00+08:00"));
        var largeNote=Map.of("note","牙".repeat(8300));reject("whole detail response enforces 48KiB despite duplicated snapshot","ORG_PAYLOAD_TOO_LARGE",()->OrganizationRules.size(Map.of("resource",largeNote,"snapshot",largeNote)));
        var openingCanonical=new LinkedHashMap<String,Object>();openingCanonical.put("openingId","30");openingCanonical.put("clinicId","10");openingCanonical.put("startAt",w.start().toInstant().toString());openingCanonical.put("endAt",w.end().toInstant().toString());openingCanonical.put("note",null);
        String openingHash=OrganizationService.fingerprint("org_opening.create.v1",openingCanonical);OrganizationRules.sameReceipt(1,openingHash,1,openingHash);check("fixed opening identity replays original",true);
        var otherClinic=new LinkedHashMap<>(openingCanonical);otherClinic.put("clinicId","11");reject("same opening ID with different parent conflicts","IDEMPOTENCY_CONFLICT",()->OrganizationRules.sameReceipt(1,openingHash,1,OrganizationService.fingerprint("org_opening.create.v1",otherClinic)));
        var otherNote=new LinkedHashMap<>(openingCanonical);otherNote.put("note","不同说明");reject("same opening ID with changed note conflicts","IDEMPOTENCY_CONFLICT",()->OrganizationRules.sameReceipt(1,openingHash,1,OrganizationService.fingerprint("org_opening.create.v1",otherNote)));
        var openingDto=mapper.readValue("{\"openingId\":\"30\",\"startAt\":\"2027-06-01T23:30:00+08:00\",\"endAt\":\"2027-06-02T00:30:00+08:00\"}",OrganizationApi.OpeningCreate.class);check("strict opening DTO accepts optional null note",openingDto.note()==null);
        try{mapper.readValue("{\"openingId\":\"30\",\"startAt\":\"2027-06-01T23:30:00+08:00\",\"endAt\":\"2027-06-02T00:30:00+08:00\",\"updatedBy\":\"99\"}",OrganizationApi.OpeningCreate.class);throw new AssertionError("unknown accepted");}catch(RuntimeException expected){check("client cannot supply updatedBy",true);}
        var scheduleWindow=OpeningRules.window("2027-06-01T23:45:00+08:00","2027-06-02T00:15:00+08:00");
        ScheduleRules.covered(openingRow,10,scheduleWindow);check("same-clinic effective opening covers cross-midnight schedule",true);
        reject("other clinic opening cannot cover schedule","OUTSIDE_COVERAGE",()->ScheduleRules.covered(openingRow,11,scheduleWindow));
        reject("schedule outside opening denied","OUTSIDE_COVERAGE",()->ScheduleRules.covered(openingRow,10,OpeningRules.window("2027-06-01T23:00:00+08:00","2027-06-02T00:15:00+08:00")));
        var revokedOpening=new LinkedHashMap<>(openingRow);revokedOpening.put("status","撤销");reject("revoked opening cannot cover new schedule","OUTSIDE_COVERAGE",()->ScheduleRules.covered(revokedOpening,10,scheduleWindow));
        reject("schedule start must be strictly future","SCHEDULE_NOT_FUTURE",()->ScheduleRules.future(scheduleWindow,scheduleWindow.start().toInstant()));
        ScheduleRules.future(scheduleWindow,scheduleWindow.start().toInstant().minusSeconds(1));check("future schedule accepted",true);
        check("cross-clinic dentist overlap uses shared half-open policy",OpeningRules.overlaps(a,b,b.minusSeconds(1),c)&&!OpeningRules.overlaps(a,b,b,c));
        var scheduleRow=new LinkedHashMap<String,Object>();scheduleRow.put("schedule_id",40L);scheduleRow.put("dentist_id",20L);scheduleRow.put("clinic_id",10L);scheduleRow.put("opening_id",30L);scheduleRow.put("start_at",w.start());scheduleRow.put("end_at",java.sql.Timestamp.from(w.end().toInstant().plusSeconds(90000)));scheduleRow.put("status","撤销");scheduleRow.put("row_version",7);
        var historicSchedule=ScheduleRules.snapshot(scheduleRow,0);check("historical revoked long schedule remains readable",historicSchedule.get("status").equals("撤销")&&historicSchedule.get("mutationCount").equals("0")&&historicSchedule.get("rowVersion").equals("7"));
        check("schedule IDs and times are safe strings",historicSchedule.get("scheduleId").equals("40")&&historicSchedule.get("dentistId").equals("20")&&historicSchedule.get("startAt").equals("2027-06-01T23:30:00+08:00"));
        var scheduleCanonical=new LinkedHashMap<String,Object>();scheduleCanonical.put("scheduleId","40");scheduleCanonical.put("clinicId","10");scheduleCanonical.put("dentistId","20");scheduleCanonical.put("openingId","30");scheduleCanonical.put("startAt",scheduleWindow.start().toInstant().toString());scheduleCanonical.put("endAt",scheduleWindow.end().toInstant().toString());
        String scheduleHash=OrganizationService.fingerprint("org_schedule.create.v1",scheduleCanonical);OrganizationRules.sameReceipt(1,scheduleHash,1,scheduleHash);check("schedule original identity replays",true);
        for(String field:List.of("clinicId","dentistId","openingId","startAt")){var altered=new LinkedHashMap<>(scheduleCanonical);altered.put(field,"999");reject("schedule changed "+field+" conflicts","IDEMPOTENCY_CONFLICT",()->OrganizationRules.sameReceipt(1,scheduleHash,1,OrganizationService.fingerprint("org_schedule.create.v1",altered)));}
        var scheduleDto=mapper.readValue("{\"scheduleId\":\"40\",\"dentistId\":\"20\",\"openingId\":\"30\",\"startAt\":\"2027-06-01T23:45:00+08:00\",\"endAt\":\"2027-06-02T00:15:00+08:00\"}",OrganizationApi.ScheduleCreate.class);check("strict schedule DTO accepts actual resource IDs",scheduleDto.openingId().equals("30"));
        try{mapper.readValue("{\"scheduleId\":\"40\",\"dentistId\":\"20\",\"openingId\":\"30\",\"startAt\":\"2027-06-01T23:45:00+08:00\",\"endAt\":\"2027-06-02T00:15:00+08:00\",\"rowVersion\":\"9\"}",OrganizationApi.ScheduleCreate.class);throw new AssertionError("unknown accepted");}catch(RuntimeException expected){check("client cannot set new schedule rowVersion",true);}
    }
}
