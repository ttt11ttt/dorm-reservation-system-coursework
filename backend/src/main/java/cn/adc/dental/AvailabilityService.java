package cn.adc.dental;

import java.util.*;
import org.springframework.stereotype.Service;

@Service
class AvailabilityService {
    private final Store db;
    AvailabilityService(Store db) { this.db=db; }
    Object query(long clinic,Long patient) {
        return query(clinic,patient,0L);
    }
    Object query(long clinic,Long patient,long excludedAppointment) {
        var now=db.now();
        var windows=db.jdbc.queryForList("SELECT s.schedule_id,s.dentist_id,s.clinic_id,s.opening_id,s.start_at,s.end_at FROM dentist_schedules s JOIN clinic_openings o ON o.opening_id=s.opening_id AND o.clinic_id=s.clinic_id JOIN clinics c ON c.clinic_id=s.clinic_id JOIN dentists d ON d.dentist_id=s.dentist_id WHERE s.clinic_id=? AND c.active=1 AND d.active=1 AND s.status='有效' AND o.status='有效' AND o.start_at<=s.start_at AND o.end_at>=s.end_at AND s.end_at>? ORDER BY s.start_at LIMIT 100",clinic,now);
        var occupied=new ArrayList<Map<String,Object>>();
        for(var window:windows) {
            occupied.addAll(db.jdbc.queryForList("SELECT DISTINCT dentist_id,scheduled_start,scheduled_end FROM appointments WHERE dentist_id=? AND appointment_id<>? AND appointment_status IN ('待到诊','已到诊','待核实') AND scheduled_start<? AND scheduled_end>? AND scheduled_end>?",window.get("dentist_id"),excludedAppointment,window.get("end_at"),window.get("start_at"),now));
            occupied.addAll(db.jdbc.queryForList("SELECT DISTINCT dentist_id,scheduled_start,scheduled_end FROM guest_reservations WHERE dentist_id=? AND reservation_status='待到诊' AND scheduled_start<? AND scheduled_end>? AND scheduled_end>?",window.get("dentist_id"),window.get("end_at"),window.get("start_at"),now));
        }
        var result=new LinkedHashMap<String,Object>();result.put("asOf",now);result.put("windows",windows);result.put("occupied",occupied.stream().distinct().toList());
        if(patient!=null)result.put("patientOccupied",db.jdbc.queryForList("SELECT scheduled_start,scheduled_end FROM appointments WHERE patient_id=? AND appointment_id<>? AND appointment_status IN ('待到诊','已到诊','待核实') AND scheduled_end>? ORDER BY scheduled_start",patient,excludedAppointment,now));
        return Store.json(result);
    }
}
