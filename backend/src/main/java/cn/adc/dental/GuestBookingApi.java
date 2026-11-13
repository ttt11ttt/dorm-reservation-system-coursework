package cn.adc.dental;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
class GuestBookingApi {
    private final Store db;
    private final Identity identity;
    private final GuestIdentity guests;
    private final GuestBookingService booking;
    private final AvailabilityService availability;
    GuestBookingApi(Store db,Identity identity,GuestIdentity guests,GuestBookingService booking,AvailabilityService availability) {
        this.db=db;this.identity=identity;this.guests=guests;this.booking=booking;this.availability=availability;
    }
    @PostMapping(value="/booking/wechat-sessions",consumes="application/json")
    Object login(HttpServletRequest request) { return guests.login(WechatPatientLoginRules.code(WechatPatientLoginApi.readMap(request),0)); }
    @DeleteMapping("/guest/sessions/current")
    void logout(@RequestHeader(value="Authorization",required=false) String h) { guests.logout(h); }
    @GetMapping("/guest/clinics")
    Object clinics(@RequestHeader(value="Authorization",required=false) String h) {
        guests.require(h);return Store.json(db.jdbc.queryForList("SELECT clinic_id,clinic_code,name FROM clinics WHERE active=1 ORDER BY clinic_id"));
    }
    @GetMapping("/guest/dentists")
    Object dentists(@RequestHeader(value="Authorization",required=false) String h,@RequestParam String clinicId) {
        guests.require(h);long clinic=Store.parseId(clinicId);
        return Store.json(db.jdbc.queryForList("SELECT DISTINCT d.dentist_id,d.name FROM dentists d JOIN dentist_schedules s ON s.dentist_id=d.dentist_id WHERE d.active=1 AND s.clinic_id=? AND s.status='有效'",clinic));
    }
    @GetMapping("/guest/availability")
    Object availability(@RequestHeader(value="Authorization",required=false) String h,@RequestParam String clinicId) {
        guests.require(h);return availability.query(Store.parseId(clinicId),null);
    }
    @PostMapping("/guest/reservations")
    Object create(@RequestHeader(value="Authorization",required=false) String h,@RequestBody GuestBookingService.Request b) { return booking.create(guests.require(h),b); }
    @GetMapping("/guest/reservations")
    Object own(@RequestHeader(value="Authorization",required=false) String h) { return booking.own(guests.require(h)); }
    @GetMapping("/guest/booking-status")
    Object status(@RequestHeader(value="Authorization",required=false) String h) { return booking.status(guests.require(h)); }
    @GetMapping("/guest/treatment-types")
    Object prices(@RequestHeader(value="Authorization",required=false) String h) {
        guests.require(h);
        return Store.json(db.jdbc.queryForList("SELECT treatment_type_id,name,unit_name,standard_unit_price FROM treatment_types WHERE active=1 ORDER BY treatment_type_id"));
    }
    @GetMapping("/guest/reservations/by-number/{number}")
    Object byNumber(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String number) { return booking.read(guests.require(h),number); }
    @PostMapping("/guest/reservations/{id}/cancel")
    Object cancel(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String id,@RequestBody GuestBookingService.Cancel b) { return booking.cancel(guests.require(h),Store.parseId(id),b); }
    @PostMapping("/guest/reservations/{id}/reschedule")
    Object reschedule(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String id,@RequestBody GuestBookingService.Reschedule b) { return booking.reschedule(guests.require(h),Store.parseId(id),b); }
    @GetMapping("/staff/guest-reservations")
    Object staff(@RequestHeader(value="Authorization",required=false) String h,@RequestParam String clinicId) { return booking.staff(identity.require(h),Store.parseId(clinicId)); }
    @PostMapping("/staff/guest-reservations/{id}/convert")
    Object convert(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String id,@RequestBody GuestBookingService.Convert b) { return booking.convert(identity.require(h),Store.parseId(id),b); }
    @PostMapping("/staff/guest-reservations/{id}/no-show")
    Object noShow(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String id,@RequestBody GuestBookingService.Cancel b) { return booking.noShow(identity.require(h),Store.parseId(id),b); }
    @PostMapping("/staff/guest-reservations/{id}/clinic-cancel")
    Object clinicCancel(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String id,@RequestBody GuestBookingService.Cancel b) { return booking.clinicCancel(identity.require(h),Store.parseId(id),b); }
    @PostMapping("/staff/guest-breaches/{id}/review")
    Object review(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String id,@RequestBody GuestBookingService.Review b) { return booking.review(identity.require(h),Store.parseId(id),b); }
}
