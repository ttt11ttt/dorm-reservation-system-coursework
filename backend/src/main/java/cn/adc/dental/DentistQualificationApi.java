package cn.adc.dental;

import java.sql.Date;
import java.util.Map;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/org/dentists/{id}/qualifications")
class DentistQualificationApi {
    private final Store db;
    private final Identity identity;

    DentistQualificationApi(Store db, Identity identity){
        this.db=db;
        this.identity=identity;
    }

    record Certificate(String name,String certificateNo,String validFrom,String validUntil) {}
    record Verification(String status,String reason) {}

    @GetMapping
    Object list(@RequestHeader(value="Authorization",required=false) String header,@PathVariable String id){
        OrganizationRules.chainOnly(identity.require(header));
        long dentist=Store.parseId(id);
        var entries=db.jdbc.queryForList("""
            SELECT h.*,q.name FROM dentist_qualifications h JOIN qualifications q ON q.qualification_id=h.qualification_id
            WHERE h.dentist_id=? ORDER BY h.valid_from DESC,h.holding_id DESC LIMIT 100
            """,dentist);
        var schedules=db.jdbc.queryForList("""
            SELECT s.schedule_id,s.start_at,s.end_at,c.name AS clinic_name
            FROM dentist_schedules s JOIN clinics c ON c.clinic_id=s.clinic_id
            WHERE s.dentist_id=? AND s.status='有效' AND s.end_at>?
            ORDER BY s.start_at LIMIT 20
            """,dentist,db.now());
        return Store.json(Map.of("certificates",entries,"upcomingSchedules",schedules));
    }

    @PostMapping
    @Transactional
    Object register(@RequestHeader(value="Authorization",required=false) String header,
                    @PathVariable String id,@RequestBody Certificate request){
        var actor=identity.require(header);
        OrganizationRules.chainOnly(actor);
        long dentist=Store.parseId(id);
        db.one("SELECT dentist_id FROM dentists WHERE dentist_id=? FOR UPDATE",dentist);
        String name=OrganizationRules.label(request.name(),100);
        String number=OrganizationRules.label(request.certificateNo(),80);
        var from=ExpenseRules.date(request.validFrom());
        var until=request.validUntil()==null||request.validUntil().isBlank()?null:ExpenseRules.date(request.validUntil());
        if(until!=null&&until.isBefore(from))throw ApiError.bad();
        db.jdbc.update("INSERT INTO qualifications(qualification_id,name) VALUES(?,?) ON DUPLICATE KEY UPDATE name=qualifications.name",db.id(),name);
        var type=db.one("SELECT qualification_id,active FROM qualifications WHERE name=? FOR UPDATE",name);
        if(!Store.enabled(type,"active"))throw ApiError.conflict("RESOURCE_INACTIVE");
        var old=db.jdbc.queryForList("""
            SELECT * FROM dentist_qualifications WHERE dentist_id=? AND qualification_id=? AND valid_from=?
            """,dentist,type.get("qualification_id"),Date.valueOf(from));
        Date end=until==null?null:Date.valueOf(until);
        if(!old.isEmpty()){
            var entry=old.getFirst();
            if(number.equals(entry.get("certificate_no"))&&java.util.Objects.equals(end,entry.get("valid_until")))return Store.json(entry);
            throw ApiError.conflict("DATA_CONFLICT");
        }
        long holding=db.id();
        db.jdbc.update("""
            INSERT INTO dentist_qualifications(holding_id,dentist_id,qualification_id,certificate_no,valid_from,valid_until,status)
            VALUES(?,?,?,?,?,?,'待核实')
            """,holding,dentist,type.get("qualification_id"),number,Date.valueOf(from),end);
        var result=holding(dentist,holding,false);
        db.audit(actor.id(),"登记牙医资质","dentist_qualification",holding,null,null,result);
        return Store.json(result);
    }

    @PostMapping("/{holdingId}/verification")
    @Transactional
    Object verify(@RequestHeader(value="Authorization",required=false) String header,
                  @PathVariable String id,@PathVariable String holdingId,@RequestBody Verification request){
        var actor=identity.require(header);
        OrganizationRules.chainOnly(actor);
        long dentist=Store.parseId(id),holding=Store.parseId(holdingId);
        db.one("SELECT dentist_id FROM dentists WHERE dentist_id=? FOR UPDATE",dentist);
        var before=holding(dentist,holding,true);
        String status=Store.required(request.status(),8),reason=OrganizationRules.label(request.reason(),500);
        if(!java.util.Set.of("有效","失效").contains(status))throw ApiError.bad();
        if(status.equals(before.get("status")))return Store.json(before);
        if("失效".equals(before.get("status")))throw ApiError.conflict("INVALID_STATE");
        db.jdbc.update("UPDATE dentist_qualifications SET status=?,verified_by=?,verified_at=? WHERE holding_id=?",status,actor.id(),db.now(),holding);
        var result=holding(dentist,holding,false);
        db.audit(actor.id(),"核实牙医资质","dentist_qualification",holding,reason,before,result);
        return Store.json(result);
    }

    private Map<String,Object> holding(long dentist,long id,boolean lock){
        return db.one("SELECT * FROM dentist_qualifications WHERE dentist_id=? AND holding_id=?"+(lock?" FOR UPDATE":""),dentist,id);
    }
}
