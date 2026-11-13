package cn.adc.dental;

import java.sql.Timestamp;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class Store {
    final JdbcTemplate jdbc;
    final Clock clock;
    final SecureRandom random=new SecureRandom();
    Store(JdbcTemplate jdbc,Clock clock) { this.jdbc=jdbc; this.clock=clock; }
    long id() { return random.nextLong(1L<<50,Long.MAX_VALUE); }
    Timestamp now() { return Timestamp.from(clock.instant().truncatedTo(ChronoUnit.SECONDS)); }
    Map<String,Object> one(String sql,Object... args) {
        var rows=jdbc.queryForList(sql,args);
        if(rows.isEmpty()) throw new ApiError(404,"NOT_FOUND","资源不存在");
        return rows.getFirst();
    }
    void audit(long actor,String action,String type,long id) {
        jdbc.update("INSERT INTO audit_logs(audit_id,actor_account_id,action,object_type,object_id,happened_at) VALUES(?,?,?,?,?,?)",id(),actor,action,type,Long.toString(id),now());
    }
    void audit(long actor,String action,String type,long objectId,String reason,Object before,Object after) {
        jdbc.update("INSERT INTO audit_logs(audit_id,actor_account_id,action,object_type,object_id,reason,before_value,after_value,happened_at) VALUES(?,?,?,?,?,?,?,?,?)",id(),actor,action,type,Long.toString(objectId),reason,before==null?null:encode(before),after==null?null:encode(after),now());
    }
    private static final JsonMapper JSON=JsonMapper.builder().build();
    static String encode(Object value) { return JSON.writeValueAsString(json(value)); }
    static long number(Map<String,Object> row,String key) { return ((Number)row.get(key)).longValue(); }
    static boolean enabled(Map<String,Object> row,String key) { return Boolean.TRUE.equals(row.get(key)) || row.get(key) instanceof Number n && n.intValue()==1; }
    static Timestamp offset(String value) {
        try { var instant=OffsetDateTime.parse(value).toInstant();
            if(instant.getNano()!=0 || instant.getEpochSecond()<1 || instant.getEpochSecond()>2147483647L)throw ApiError.bad();
            return Timestamp.from(instant);
        } catch(Exception e) { throw ApiError.bad(); }
    }
    static Object json(Object value) {
        if(value instanceof Map<?,?> m) {
            var result=new LinkedHashMap<String,Object>();
            m.forEach((k,v)->result.put(k.toString(),json(v))); return result;
        }
        if(value instanceof List<?> l) return l.stream().map(Store::json).toList();
        if(value instanceof Long || value instanceof BigDecimal) return value.toString();
        if(value instanceof Timestamp t) return t.toInstant().atOffset(ZoneOffset.UTC).toString();
        if(value instanceof java.sql.Date d) return d.toLocalDate().toString();
        return value;
    }
    static String required(String s,int max) { if(s==null || s.isBlank() || s.length()>max) throw ApiError.bad(); return s; }
    static long parseId(String s) { if(s==null || !s.matches("[1-9][0-9]{0,18}"))throw ApiError.bad(); try{return Long.parseLong(s);}catch(NumberFormatException e){throw ApiError.bad();} }
}
