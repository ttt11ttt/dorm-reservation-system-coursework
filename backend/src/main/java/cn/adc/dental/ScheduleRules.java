package cn.adc.dental;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** Pure new-schedule coverage and public snapshot rules. */
final class ScheduleRules {
    private ScheduleRules() {}
    static void future(OpeningRules.Window window,Instant now){if(!window.start().toInstant().isAfter(now))throw ApiError.conflict("SCHEDULE_NOT_FUTURE");}
    static void covered(Map<String,Object> opening,long clinic,OpeningRules.Window window){
        if(Store.number(opening,"clinic_id")!=clinic||!"有效".equals(opening.get("status"))
            ||((Timestamp)opening.get("start_at")).after(window.start())||((Timestamp)opening.get("end_at")).before(window.end()))throw ApiError.conflict("OUTSIDE_COVERAGE");
    }
    static Map<String,Object> snapshot(Map<String,Object> row,long count){
        var out=new LinkedHashMap<String,Object>();out.put("scheduleId",Long.toString(Store.number(row,"schedule_id")));
        out.put("dentistId",Long.toString(Store.number(row,"dentist_id")));out.put("clinicId",Long.toString(Store.number(row,"clinic_id")));
        out.put("openingId",Long.toString(Store.number(row,"opening_id")));out.put("startAt",OpeningRules.time(row.get("start_at")));
        out.put("endAt",OpeningRules.time(row.get("end_at")));out.put("status",row.get("status"));out.put("rowVersion",Long.toString(Store.number(row,"row_version")));
        out.put("mutationCount",Long.toString(count));OrganizationRules.size(out);return out;
    }
}
