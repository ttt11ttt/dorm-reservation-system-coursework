package cn.adc.dental;

import java.sql.Timestamp;
import org.springframework.stereotype.Component;

/** Call after locking the dentist parent row, shared with ordinary appointment creation. */
@Component
class GuestOccupancy {
    private final Store db;
    GuestOccupancy(Store db) { this.db=db; }
    void available(long dentist,long patient,Timestamp start,Timestamp end) {
        int count=db.jdbc.queryForObject("SELECT COUNT(*) FROM guest_reservations r JOIN guest_subjects g ON g.guest_subject_id=r.guest_subject_id WHERE (r.dentist_id=? OR g.conversion_verified_patient_id=? OR g.ever_verified_patient_id=?) AND r.reservation_status='待到诊' AND r.scheduled_start<? AND r.scheduled_end>?",Integer.class,dentist,patient,patient,end,start);
        if(count!=0)throw ApiError.conflict("TIME_CONFLICT");
    }
    void availableForGuest(long dentist,long subject,Long verifiedPatient,Timestamp start,Timestamp end) {
        int count=db.jdbc.queryForObject("SELECT COUNT(*) FROM guest_reservations r JOIN guest_subjects g ON g.guest_subject_id=r.guest_subject_id WHERE (r.dentist_id=? OR r.guest_subject_id=? OR g.conversion_verified_patient_id=? OR g.ever_verified_patient_id=?) AND r.reservation_status='待到诊' AND r.scheduled_start<? AND r.scheduled_end>?",Integer.class,dentist,subject,verifiedPatient,verifiedPatient,end,start);
        if(count!=0)throw ApiError.conflict("TIME_CONFLICT");
    }
    void availableForConversion(long dentist,long patient,long excluded,Timestamp start,Timestamp end) {
        int count=db.jdbc.queryForObject("SELECT COUNT(*) FROM guest_reservations r JOIN guest_subjects g ON g.guest_subject_id=r.guest_subject_id WHERE r.guest_reservation_id<>? AND (r.dentist_id=? OR g.conversion_verified_patient_id=? OR g.ever_verified_patient_id=?) AND r.reservation_status='待到诊' AND r.scheduled_start<? AND r.scheduled_end>?",Integer.class,excluded,dentist,patient,patient,end,start);
        if(count!=0)throw ApiError.conflict("TIME_CONFLICT");
    }
}
