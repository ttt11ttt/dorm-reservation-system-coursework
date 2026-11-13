package cn.adc.dental;

import java.math.BigDecimal;
import java.sql.Date;
import java.util.Map;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/org")
class OrganizationMaintenanceApi {
    private final Store db;
    private final Identity identity;

    OrganizationMaintenanceApi(Store db,Identity identity){this.db=db;this.identity=identity;}

    record Clinic(String name,String postalAddress,String managerName,String phone,String email,String areaM2) {}
    record Dentist(String name,String birthDate,String homeAddress,String phone,String email) {}
    record State(Boolean active,String reason) {}
    record OpeningStop(String expectedUpdatedAt,String reason) {}

    @PutMapping("/clinics/{id}")
    @Transactional
    Object clinic(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String id,@RequestBody Clinic request){
        var actor=staff(h);
        long clinic=Store.parseId(id);
        var before=lock("clinics","clinic_id",clinic);
        db.jdbc.update("""
            UPDATE clinics SET name=?,postal_address=?,manager_name=?,phone=?,email=?,area_m2=? WHERE clinic_id=?
            """,label(request.name(),100),label(request.postalAddress(),255),label(request.managerName(),80),
            label(request.phone(),32),label(request.email(),254),new BigDecimal(OrganizationRules.area(request.areaM2())),clinic);
        audit(actor,"门店资料修改","org_clinic",clinic,before,lock("clinics","clinic_id",clinic),null);
        return Map.of("updated",true);
    }

    @PutMapping("/dentists/{id}")
    @Transactional
    Object dentist(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String id,@RequestBody Dentist request){
        var actor=staff(h);
        long dentist=Store.parseId(id);
        var before=lock("dentists","dentist_id",dentist);
        db.jdbc.update("""
            UPDATE dentists SET name=?,birth_date=?,home_address=?,phone=?,email=? WHERE dentist_id=?
            """,label(request.name(),80),Date.valueOf(OrganizationRules.birthDate(request.birthDate())),
            label(request.homeAddress(),255),label(request.phone(),32),label(request.email(),254),dentist);
        audit(actor,"牙医资料修改","org_dentist",dentist,before,lock("dentists","dentist_id",dentist),null);
        return Map.of("updated",true);
    }

    @PostMapping("/clinics/{id}/state")
    @Transactional
    Object clinicState(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String id,@RequestBody State request){
        return state(staff(h),Store.parseId(id),request,true);
    }

    @PostMapping("/dentists/{id}/state")
    @Transactional
    Object dentistState(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String id,@RequestBody State request){
        return state(staff(h),Store.parseId(id),request,false);
    }

    @PostMapping("/openings/{id}/stop")
    @Transactional
    Object openingStop(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String id,@RequestBody OpeningStop request){
        var actor=staff(h);
        long opening=Store.parseId(id);
        String reason=label(request.reason(),500);
        var parent=db.one("SELECT clinic_id FROM clinic_openings WHERE opening_id=?",opening);
        lock("clinics","clinic_id",Store.number(parent,"clinic_id"));
        var before=lock("clinic_openings","opening_id",opening);
        if(!"有效".equals(before.get("status"))||!OpeningRules.time(before.get("updated_at")).equals(request.expectedUpdatedAt())){
            throw ApiError.conflict("ROW_VERSION_CONFLICT");
        }
        if(!((java.sql.Timestamp)before.get("start_at")).after(db.now())
            || exists("SELECT 1 FROM dentist_schedules WHERE opening_id=? AND status='有效' LIMIT 1",opening)){
            throw new ApiError(409,"OPENING_HAS_SCHEDULES","只能撤销尚未开始、且无有效出诊依赖的开放时段。");
        }
        db.jdbc.update("UPDATE clinic_openings SET status='撤销',updated_by=?,updated_at=? WHERE opening_id=?",actor.id(),db.now(),opening);
        audit(actor,"撤销开放时段","org_opening",opening,before,lock("clinic_openings","opening_id",opening),reason);
        return Map.of("updated",true);
    }

    private Object state(Identity.Actor actor,long id,State request,boolean clinic){
        if(request.active()==null)throw ApiError.bad();
        String reason=label(request.reason(),500);
        String table=clinic?"clinics":"dentists",key=clinic?"clinic_id":"dentist_id";
        var before=lock(table,key,id);
        if(request.active()==Store.enabled(before,"active"))return Map.of("updated",false);
        if(!request.active()){
            var now=db.now();
            boolean busy=exists("SELECT 1 FROM dentist_schedules WHERE "+key+"=? AND status='有效' AND end_at>? LIMIT 1",id,now)
                ||exists("SELECT 1 FROM appointments WHERE "+key+"=? AND appointment_status IN ('待到诊','待核实','已到诊') AND treatment_status<>'已完成' LIMIT 1",id)
                ||exists("SELECT 1 FROM guest_reservations WHERE "+key+"=? AND reservation_status='待到诊' LIMIT 1",id)
                ||exists("SELECT 1 FROM followup_plans WHERE "+key+"=? AND status='待复诊' LIMIT 1",id)
                || (clinic ? exists("SELECT 1 FROM clinic_openings WHERE clinic_id=? AND status='有效' AND end_at>? LIMIT 1",id,now)
                    : exists("SELECT 1 FROM accounts WHERE dentist_id=? AND active=1 LIMIT 1",id));
            if(busy)throw new ApiError(409,"RESOURCE_IN_USE","请先处理关联的开放时段、出诊、预约、复诊或在用账号。");
        }
        db.jdbc.update("UPDATE "+table+" SET active=? WHERE "+key+"=?",request.active(),id);
        audit(actor,request.active()?"启用":"停用",clinic?"org_clinic":"org_dentist",id,before,lock(table,key,id),reason);
        return Map.of("updated",true);
    }

    private Identity.Actor staff(String h){
        var actor=identity.require(h);
        OrganizationRules.chainOnly(actor);
        return actor;
    }
    private Map<String,Object> lock(String table,String key,long id){
        return db.one("SELECT * FROM "+table+" WHERE "+key+"=? FOR UPDATE",id);
    }
    private boolean exists(String sql,Object...args){return !db.jdbc.queryForList(sql,args).isEmpty();}
    private String label(String value,int max){return OrganizationRules.label(value,max);}
    private void audit(Identity.Actor actor,String action,String object,long id,Map<String,Object> before,Map<String,Object> after,String reason){
        if(!before.equals(after))db.audit(actor.id(),action,object,id,reason,before,after);
    }
}
