package cn.adc.dental;

import java.sql.Timestamp;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Pure actual-opening input and public interval rules. */
final class OpeningRules {
    private OpeningRules() {}
    private static final DateTimeFormatter OUTPUT=DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX").withZone(ZoneId.of("Asia/Shanghai"));
    record Window(Timestamp start,Timestamp end) {}
    static Window window(String start,String end){
        Timestamp a=Store.offset(start),b=Store.offset(end);
        long seconds=Duration.between(a.toInstant(),b.toInstant()).getSeconds();
        if(seconds<=0||seconds>86400)throw ApiError.bad();
        return new Window(a,b);
    }
    static void future(Window w,Instant now){if(!w.start().toInstant().isAfter(now))throw ApiError.conflict("OPENING_NOT_FUTURE");}
    static boolean overlaps(Instant a,Instant b,Instant c,Instant d){return a.isBefore(d)&&b.isAfter(c);}
    static String note(String value){if(value==null||value.isBlank())return null;String trimmed=value.trim();if(trimmed.length()>8000)throw ApiError.bad();return trimmed;}
    static String time(Object value){return OUTPUT.format(((Timestamp)value).toInstant());}
    static Map<String,Object> snapshot(Map<String,Object> row,long count){
        var out=new LinkedHashMap<String,Object>();out.put("openingId",Long.toString(Store.number(row,"opening_id")));out.put("clinicId",Long.toString(Store.number(row,"clinic_id")));
        out.put("startAt",time(row.get("start_at")));out.put("endAt",time(row.get("end_at")));out.put("status",row.get("status"));out.put("note",row.get("note"));
        out.put("updatedBy",Long.toString(Store.number(row,"updated_by")));out.put("updatedAt",time(row.get("updated_at")));out.put("mutationCount",Long.toString(count));OrganizationRules.size(out);return out;
    }
}
