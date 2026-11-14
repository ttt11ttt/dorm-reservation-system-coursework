package cn.adc.dental;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Standalone teaching fixtures. Visitor requests never query live business tables. */
final class VisitorWorkspace {
    private VisitorWorkspace() {}

    static List<Map<String,Object>> clinics() {
        return List.of(Map.of("clinic_id","1","clinic_code","DEMO","name","和安口腔·教学门店"));
    }

    static Map<String,Object> dashboard(Long clinic,LocalDate today) {
        if(clinic!=null&&clinic!=1)throw ApiError.denied();
        var visit=Map.ofEntries(
            Map.entry("appointment_id","1"),Map.entry("appointment_no","DEMO-001"),
            Map.entry("patient_id","1"),Map.entry("patient_no","DEMO-P001"),
            Map.entry("patient_name","演示患者"),Map.entry("dentist_name","演示牙医"),
            Map.entry("clinic_id","1"),Map.entry("clinic_name","和安口腔·教学门店"),
            Map.entry("scheduled_start",today+"T09:00:00+08:00"),
            Map.entry("scheduled_end",today+"T09:30:00+08:00"),
            Map.entry("booking_channel","自助"),Map.entry("appointment_status","待到诊"),
            Map.entry("treatment_status","未开始"));
        var patient=Map.of("patient_id","1","patient_no","DEMO-P001","name","演示患者",
            "allergy_declaration","未询问","active",true);
        var project=Map.of("treatment_type_id","1","treatment_code","DEMO-CLEAN","name","洁牙护理",
            "unit_name","次","standard_unit_price","198.00");
        return Map.of("today",today.toString(),"clinics",clinics(),"visits",List.of(visit),
            "stats",Map.of("today",1,"guestToday",0,"arrived",0,"unbilled",0,"followups",0),
            "followups",List.of(),"patients",List.of(patient),"projects",List.of(project),"dentists",List.of());
    }
}
