package cn.adc.dental;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** Anonymous, column-allowlisted directory. Reservation writes remain in existing services. */
@Service
class CatalogService {
    private final Store db;
    CatalogService(Store db) { this.db=db; }
    private static final ZoneOffset OFFSET=ZoneOffset.ofHours(8);
    private static String stamp(Instant instant) { return instant.atOffset(OFFSET).toString(); }
    private static String text(Object value) { return value==null?"":value.toString(); }
    private static String query(String value) {
        if(value==null)return "";String q=value.strip();
        if(q.length()>40||q.chars().anyMatch(Character::isISOControl))throw ApiError.bad();return q;
    }
    private static int limit(Integer value) { int n=value==null?20:value;if(n<1||n>50)throw ApiError.bad();return n; }
    private static long after(String value) { return value==null?0:Store.parseId(value); }
    private LocalDate day(String value) {
        try {var date=LocalDate.parse(value);var today=LocalDate.now(db.clock.withZone(CatalogPolicy.ZONE));if(date.isBefore(today)||date.isAfter(today.plusDays(30)))throw ApiError.bad();return date;}
        catch(RuntimeException invalid) {throw ApiError.bad();}
    }
    private static Timestamp begin(LocalDate day) {return Timestamp.from(day.atStartOfDay(CatalogPolicy.ZONE).toInstant());}
    private static Timestamp end(LocalDate day) {return begin(day.plusDays(1));}
    private static Object bounded(Object value) {OrganizationRules.size(value);return Store.json(value);}
    private Map<String,Object> clinic(Map<String,Object> row) {
        return Map.of("clinicId",Long.toString(Store.number(row,"clinic_id")),"name",row.get("name"),"address",row.get("postal_address"));
    }
    private Map<String,Object> dentist(Map<String,Object> row,boolean full) {
        var out=new LinkedHashMap<String,Object>();out.put("dentistId",Long.toString(Store.number(row,"dentist_id")));out.put("name",row.get("name"));
        out.put("specialtyLabel",text(row.get("specialty_label")));out.put("professionalTitle",text(row.get("professional_title")));
        var intro=text(row.get("introduction"));out.put(full?"introduction":"introductionPreview",full?intro:intro.length()>120?intro.substring(0,120):intro);
        if(full)out.put("expertise",text(row.get("expertise")));out.put("avatarUrl",PublicProfilePolicy.avatarUrl(row.get("avatar_asset_key")));return out;
    }
    Object clinics(String qText,String afterText,Integer requested) {
        String q=query(qText);long cursor=after(afterText);int take=limit(requested);Instant asOf=db.now().toInstant();
        var rows=db.jdbc.queryForList("SELECT c.clinic_id,c.name,c.postal_address FROM clinics c WHERE c.active=1 AND c.clinic_id>? AND (?='' OR LOCATE(?,c.name)>0 OR LOCATE(?,c.postal_address)>0 OR EXISTS(SELECT 1 FROM dentist_schedules s JOIN dentists d ON d.dentist_id=s.dentist_id JOIN clinic_openings o ON o.opening_id=s.opening_id AND o.clinic_id=s.clinic_id WHERE s.clinic_id=c.clinic_id AND d.active=1 AND s.status='有效' AND o.status='有效' AND o.start_at<=s.start_at AND o.end_at>=s.end_at AND s.end_at>? AND LOCATE(?,d.name)>0)) ORDER BY c.clinic_id LIMIT ?",cursor,q,q,q,Timestamp.from(asOf),q,take+1);
        var items=rows.stream().limit(take).map(this::clinic).toList();var out=new LinkedHashMap<String,Object>();out.put("items",items);out.put("hasMore",rows.size()>take);out.put("nextAfterId",rows.size()>take?items.getLast().get("clinicId"):null);out.put("asOf",stamp(asOf));out.put("zone","Asia/Shanghai");return bounded(out);
    }
    private List<CatalogPolicy.Window> windows(long dentist,Long clinic,LocalDate first,LocalDate last) {
        var args=new ArrayList<Object>();args.add(dentist);args.add(begin(last.plusDays(1)));args.add(begin(first));
        String scope="";if(clinic!=null){scope=" AND s.clinic_id=?";args.add(clinic);}
        var rows=db.jdbc.queryForList("SELECT s.schedule_id,s.start_at,s.end_at FROM dentist_schedules s JOIN clinic_openings o ON o.opening_id=s.opening_id AND o.clinic_id=s.clinic_id JOIN clinics c ON c.clinic_id=s.clinic_id JOIN dentists d ON d.dentist_id=s.dentist_id WHERE s.dentist_id=? AND c.active=1 AND d.active=1 AND s.status='有效' AND o.status='有效' AND o.start_at<=s.start_at AND o.end_at>=s.end_at AND s.start_at<? AND s.end_at>?"+scope+" ORDER BY s.start_at,s.schedule_id LIMIT 101",args.toArray());
        if(rows.size()>100)throw new ApiError(413,"CATALOG_SCOPE_TOO_LARGE","出诊范围超出单次读取限制");
        return rows.stream().map(r->new CatalogPolicy.Window(Store.number(r,"schedule_id"),((Timestamp)r.get("start_at")).toInstant(),((Timestamp)r.get("end_at")).toInstant())).toList();
    }
    private List<CatalogPolicy.Busy> busy(long dentist,LocalDate first,LocalDate last) {
        var to=end(last);var from=begin(first);var result=new ArrayList<CatalogPolicy.Busy>();
        var appointments=db.jdbc.queryForList("SELECT scheduled_start,scheduled_end FROM appointments WHERE dentist_id=? AND appointment_status IN ('待到诊','已到诊','待核实') AND scheduled_start<? AND scheduled_end>? LIMIT 2001",dentist,to,from);
        var guests=db.jdbc.queryForList("SELECT scheduled_start,scheduled_end FROM guest_reservations WHERE dentist_id=? AND reservation_status='待到诊' AND scheduled_start<? AND scheduled_end>? LIMIT 2001",dentist,to,from);
        if(appointments.size()>2000||guests.size()>2000)throw new ApiError(413,"CATALOG_SCOPE_TOO_LARGE","占用范围超出单次读取限制");
        for(var r:appointments)result.add(new CatalogPolicy.Busy(((Timestamp)r.get("scheduled_start")).toInstant(),((Timestamp)r.get("scheduled_end")).toInstant()));
        for(var r:guests)result.add(new CatalogPolicy.Busy(((Timestamp)r.get("scheduled_start")).toInstant(),((Timestamp)r.get("scheduled_end")).toInstant()));
        return result;
    }
    private record Summary(String status,int remaining,List<CatalogPolicy.Slot> slots) {}
    private Summary summary(long dentist,long clinic,LocalDate date,Instant now) {
        var windows=windows(dentist,clinic,date,date);var slots=CatalogPolicy.slots(date,now,windows,busy(dentist,date,date));
        return new Summary(CatalogPolicy.status(!windows.isEmpty(),slots),(int)slots.stream().filter(CatalogPolicy.Slot::available).count(),slots);
    }
    Object clinicDentists(long clinic,String dateText,String qText) {
        var date=day(dateText);String q=query(qText);Instant asOf=db.now().toInstant();db.one("SELECT clinic_id FROM clinics WHERE clinic_id=? AND active=1",clinic);
        var rows=db.jdbc.queryForList("SELECT DISTINCT d.dentist_id,d.name,p.specialty_label,p.professional_title,p.introduction,p.avatar_asset_key FROM dentist_schedules s JOIN clinic_openings o ON o.opening_id=s.opening_id AND o.clinic_id=s.clinic_id JOIN dentists d ON d.dentist_id=s.dentist_id LEFT JOIN dentist_public_profiles p ON p.dentist_id=d.dentist_id AND p.published=1 WHERE s.clinic_id=? AND s.status='有效' AND o.status='有效' AND o.start_at<=s.start_at AND o.end_at>=s.end_at AND d.active=1 AND s.start_at<? AND s.end_at>? AND s.end_at>? AND (?='' OR LOCATE(?,d.name)>0 OR LOCATE(?,p.specialty_label)>0) ORDER BY d.dentist_id LIMIT 51",clinic,end(date),begin(date),Timestamp.from(asOf),q,q,q);
        if(rows.size()>50)throw new ApiError(413,"CATALOG_SCOPE_TOO_LARGE","医生范围超出单次读取限制");
        var items=new ArrayList<Map<String,Object>>();
        for(var row:rows){long dentist=Store.number(row,"dentist_id");var s=summary(dentist,clinic,date,asOf);var item=dentist(row,false);item.put("status",s.status());item.put("remainingCandidateCount",s.remaining());items.add(item);}
        return bounded(Map.of("clinicId",Long.toString(clinic),"date",date.toString(),"asOf",stamp(asOf),"zone","Asia/Shanghai","items",items));
    }
    Object experts(String afterText,Integer requested) {
        long cursor=after(afterText);int take=limit(requested);Instant asOf=db.now().toInstant();
        var rows=db.jdbc.queryForList("SELECT d.dentist_id,d.name,p.specialty_label,p.professional_title,p.introduction,p.avatar_asset_key FROM dentists d JOIN dentist_public_profiles p ON p.dentist_id=d.dentist_id AND p.published=1 WHERE d.active=1 AND d.dentist_id>? ORDER BY d.dentist_id LIMIT ?",cursor,take+1);
        var items=rows.stream().limit(take).map(r->dentist(r,false)).toList();var out=new LinkedHashMap<String,Object>();out.put("items",items);out.put("hasMore",rows.size()>take);out.put("nextAfterId",rows.size()>take?items.getLast().get("dentistId"):null);out.put("asOf",stamp(asOf));out.put("zone","Asia/Shanghai");return bounded(out);
    }
    Object detail(long dentist,Long clinic) {
        Instant asOf=db.now().toInstant();
        var rows=db.jdbc.queryForList("SELECT d.dentist_id,d.name,p.specialty_label,p.professional_title,p.introduction,p.expertise,p.avatar_asset_key FROM dentists d LEFT JOIN dentist_public_profiles p ON p.dentist_id=d.dentist_id AND p.published=1 WHERE d.dentist_id=? AND d.active=1 AND (p.dentist_id IS NOT NULL OR EXISTS(SELECT 1 FROM dentist_schedules s JOIN clinic_openings o ON o.opening_id=s.opening_id AND o.clinic_id=s.clinic_id JOIN clinics c ON c.clinic_id=s.clinic_id WHERE s.dentist_id=d.dentist_id AND s.status='有效' AND o.status='有效' AND c.active=1 AND o.start_at<=s.start_at AND o.end_at>=s.end_at AND s.end_at>?))",dentist,Timestamp.from(asOf));
        if(rows.isEmpty())throw new ApiError(404,"NOT_FOUND","资源不存在");
        var locations=db.jdbc.queryForList("SELECT DISTINCT c.clinic_id,c.name,c.postal_address FROM dentist_schedules s JOIN clinic_openings o ON o.opening_id=s.opening_id AND o.clinic_id=s.clinic_id JOIN clinics c ON c.clinic_id=s.clinic_id WHERE s.dentist_id=? AND s.status='有效' AND o.status='有效' AND o.start_at<=s.start_at AND o.end_at>=s.end_at AND c.active=1 AND s.end_at>?"+(clinic==null?"":" AND c.clinic_id=?")+" ORDER BY c.clinic_id LIMIT 21",clinic==null?new Object[]{dentist,Timestamp.from(asOf)}:new Object[]{dentist,Timestamp.from(asOf),clinic});
        if(locations.size()>20)throw new ApiError(413,"CATALOG_SCOPE_TOO_LARGE","门店范围超出单次读取限制");
        if(clinic!=null&&locations.isEmpty())throw new ApiError(404,"NOT_FOUND","资源不存在");
        var first=LocalDate.ofInstant(asOf,CatalogPolicy.ZONE);var last=first.plusDays(30);var allWindows=windows(dentist,clinic,first,last);var occupied=busy(dentist,first,last);
        var dates=new ArrayList<Map<String,Object>>();
        for(var d=first;!d.isAfter(last);d=d.plusDays(1)) {
            var dateWindows=new ArrayList<CatalogPolicy.Window>();for(var w:allWindows)if(w.start().isBefore(end(d).toInstant())&&w.end().isAfter(begin(d).toInstant()))dateWindows.add(w);
            if(dateWindows.isEmpty())continue;
            var slots=CatalogPolicy.slots(d,asOf,dateWindows,occupied);dates.add(Map.of("date",d.toString(),"status",CatalogPolicy.status(true,slots)));
        }
        var out=dentist(rows.getFirst(),true);out.put("clinics",locations.stream().map(this::clinic).toList());out.put("dates",dates);out.put("asOf",stamp(asOf));out.put("zone","Asia/Shanghai");return bounded(out);
    }
    Object availability(long clinic,long dentist,String dateText) {
        var date=day(dateText);Instant asOf=db.now().toInstant();db.one("SELECT clinic_id FROM clinics WHERE clinic_id=? AND active=1",clinic);
        db.one("SELECT dentist_id FROM dentists WHERE dentist_id=? AND active=1",dentist);
        var s=summary(dentist,clinic,date,asOf);
        var periods=new ArrayList<Map<String,Object>>();
        Map<String,Object> current=null;String key="";Instant previousEnd=null;
        for(var slot:s.slots()) {
            String next=slot.period()+":"+slot.scheduleId();
            if(current==null||!next.equals(key)||!slot.start().equals(previousEnd)) {
                current=new LinkedHashMap<>();current.put("period",slot.period());current.put("start",stamp(slot.start()));current.put("end",stamp(slot.end()));current.put("totalCandidateCount",0);current.put("remainingCandidateCount",0);current.put("slots",new ArrayList<Map<String,Object>>());periods.add(current);key=next;
            }
            current.put("end",stamp(slot.end()));current.put("totalCandidateCount",(int)current.get("totalCandidateCount")+1);
            if(slot.available())current.put("remainingCandidateCount",(int)current.get("remainingCandidateCount")+1);
            @SuppressWarnings("unchecked") var shown=(List<Map<String,Object>>)current.get("slots");shown.add(Map.of("scheduleId",Long.toString(slot.scheduleId()),"start",stamp(slot.start()),"end",stamp(slot.end()),"available",slot.available()));previousEnd=slot.end();
        }
        return bounded(Map.of("asOf",stamp(asOf),"zone","Asia/Shanghai","clinicId",Long.toString(clinic),"dentistId",Long.toString(dentist),"date",date.toString(),"status",s.status(),"periods",periods));
    }
}
