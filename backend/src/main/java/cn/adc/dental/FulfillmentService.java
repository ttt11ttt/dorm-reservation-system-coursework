package cn.adc.dental;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Service;

/** All mutating methods require the caller to hold patients FOR UPDATE in its business transaction. */
@Service
class FulfillmentService {
    private final Store db;
    FulfillmentService(Store db) { this.db=db; }
    Map<String,Object> status(long patient) {
        Timestamp now=db.now();var active=db.jdbc.queryForList("SELECT restriction_id,starts_at,ends_at,status,reason FROM booking_restrictions WHERE patient_id=? AND status='active' AND starts_at<=? AND ends_at>? ORDER BY starts_at,restriction_id LIMIT 1",patient,now,now);
        var result=new LinkedHashMap<String,Object>();result.put("asOf",now);result.put("restricted",!active.isEmpty());result.put("restriction",active.isEmpty()?null:active.getFirst());result.put("eligibleEventCount",db.jdbc.queryForObject("SELECT COUNT(*) FROM appointment_breaches WHERE appointment_id IN (SELECT appointment_id FROM appointments WHERE patient_id=?) AND valid=1 AND exempt=0 AND restriction_id IS NULL AND occurred_at>=? AND occurred_at<=?",Integer.class,patient,Timestamp.from(now.toInstant().minus(Duration.ofDays(30))),now));result.put("frontDeskAvailable",true);return result;
    }
    void allow(long patient) {
        var current=status(patient);
        if(Boolean.TRUE.equals(current.get("restricted"))) {
            @SuppressWarnings("unchecked") var restriction=(Map<String,Object>)current.get("restriction");
            String until=((Timestamp)restriction.get("ends_at")).toInstant().atOffset(java.time.ZoneOffset.ofHours(8)).format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            throw new ApiError(409,"BOOKING_RESTRICTED","自助新增/改约暂停至"+until+"；请联系前台电话办理，仍可查询及告知取消");
        }
    }
    void event(Identity.Actor actor,Map<String,Object> a,String kind,Timestamp happened,String responsibility,boolean exempt,String reason) {
        long appointment=Store.number(a,"appointment_id");
        if(!db.jdbc.queryForList("SELECT breach_id FROM appointment_breaches WHERE appointment_id=? FOR UPDATE",appointment).isEmpty())return;
        long id=db.id();boolean valid="患者".equals(responsibility);
        db.jdbc.update("INSERT INTO appointment_breaches(breach_id,appointment_id,breach_kind,occurred_at,valid,exempt,responsibility_reason,recorded_by,updated_by,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)",id,appointment,kind,happened,valid,exempt,responsibility+"："+reason,actor.id(),actor.id(),db.now());
        db.audit(actor.id(),"违约登记","appointment_breach",id,reason,null,db.one("SELECT * FROM appointment_breaches WHERE breach_id=?",id));
    }
    void trigger(Identity.Actor actor,long patient) {
        Timestamp now=db.now();
        var expired=db.jdbc.queryForList("SELECT * FROM booking_restrictions WHERE patient_id=? AND status='active' AND ends_at<=? FOR UPDATE",patient,now);
        for(var old:expired) { long id=Store.number(old,"restriction_id");db.jdbc.update("UPDATE booking_restrictions SET status='expired' WHERE restriction_id=?",id);db.audit(actor.id(),"限制到期","booking_restriction",id,null,old,db.one("SELECT * FROM booking_restrictions WHERE restriction_id=?",id)); }
        if(Boolean.TRUE.equals(status(patient).get("restricted")))return;
        var events=db.jdbc.queryForList("SELECT * FROM appointment_breaches WHERE appointment_id IN (SELECT appointment_id FROM appointments WHERE patient_id=?) AND valid=1 AND exempt=0 AND restriction_id IS NULL AND occurred_at>=? AND occurred_at<=? ORDER BY occurred_at,breach_id LIMIT 2 FOR UPDATE",patient,Timestamp.from(now.toInstant().minus(Duration.ofDays(30))),now);
        if(events.size()!=2)return;
        long id=db.id();Timestamp end=Timestamp.from(now.toInstant().plus(Duration.ofDays(14)));
        db.jdbc.update("INSERT INTO booking_restrictions(restriction_id,patient_id,starts_at,ends_at,status,reason,created_at) VALUES(?,?,?,?,'active',?,?)",id,patient,now,end,"近30天两次有效未豁免且未消费违约",now);
        for(var event:events) {db.jdbc.update("UPDATE appointment_breaches SET restriction_id=? WHERE breach_id=?",id,event.get("breach_id"));db.audit(actor.id(),"违约消费","appointment_breach",Store.number(event,"breach_id"),null,event,db.one("SELECT * FROM appointment_breaches WHERE breach_id=?",event.get("breach_id")));}
        db.audit(actor.id(),"限制生效","booking_restriction",id,null,null,db.one("SELECT * FROM booking_restrictions WHERE restriction_id=?",id));
    }
    void revokeIfInsufficient(Identity.Actor actor,Long restriction,String reason) {
        if(restriction==null)return;
        var old=db.one("SELECT * FROM booking_restrictions WHERE restriction_id=? FOR UPDATE",restriction);
        int count=db.jdbc.queryForObject("SELECT COUNT(*) FROM appointment_breaches WHERE restriction_id=? AND valid=1 AND exempt=0",Integer.class,restriction);
        if(count<2&&"active".equals(old.get("status"))) {
            db.jdbc.update("UPDATE booking_restrictions SET status='revoked',revoked_by=?,revoked_at=?,reason=? WHERE restriction_id=?",actor.id(),db.now(),reason,restriction);
            db.audit(actor.id(),"限制撤销","booking_restriction",restriction,reason,old,db.one("SELECT * FROM booking_restrictions WHERE restriction_id=?",restriction));
        }
    }
}
