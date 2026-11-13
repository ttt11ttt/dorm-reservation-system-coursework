package cn.adc.dental;

import org.springframework.web.bind.annotation.*;
import com.fasterxml.jackson.annotation.JsonAnySetter;

@RestController
@RequestMapping("/api/v1")
class FollowupApi {
    private final Identity identity;
    private final FollowupService service;
    private final BookingService bookings;
    record StaffAppointment(String appointmentNo,String clinicId,String dentistId,String scheduleId,String scheduledStart,String scheduledEnd,String requestedTreatments,String specialNeeds,String bookingChannel) { @JsonAnySetter void unknown(String key,Object value){throw ApiError.bad();} }
    FollowupApi(Identity identity,FollowupService service,BookingService bookings) { this.identity=identity;this.service=service;this.bookings=bookings; }
    @PostMapping("/patient/followup-plans/{planId}/appointments") Object patientAppointment(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String planId,@RequestBody PatientSchedulingApi.SelfBooking b) { var actor=identity.require(h);actor.patient();return bookings.createLinked(actor,Store.parseId(planId),new BookingService.Booking(b.appointmentNo(),Long.toString(actor.patientId()),b.clinicId(),b.dentistId(),b.scheduleId(),b.scheduledStart(),b.scheduledEnd(),"自助",b.requestedTreatments(),b.specialNeeds()),true); }
    @PostMapping("/staff/followup-plans/{planId}/appointments") Object staffAppointment(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String planId,@RequestBody StaffAppointment b) { return bookings.createLinked(identity.require(h),Store.parseId(planId),new BookingService.Booking(b.appointmentNo(),null,b.clinicId(),b.dentistId(),b.scheduleId(),b.scheduledStart(),b.scheduledEnd(),b.bookingChannel(),b.requestedTreatments(),b.specialNeeds()),false); }
    @PostMapping("/dentist/appointments/{sourceId}/followup-plans") Object create(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String sourceId,@RequestBody FollowupRules.Creation request) { return service.create(identity.require(h),Store.parseId(sourceId),request); }
    @PutMapping("/dentist/followup-plans/{planId}") Object change(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String planId,@RequestBody FollowupRules.Change request) { return service.change(identity.require(h),Store.parseId(planId),request); }
    @PostMapping("/dentist/followup-plans/{planId}/cancellation") Object cancel(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String planId,@RequestBody FollowupRules.Cancellation request) { return service.cancel(identity.require(h),Store.parseId(planId),request); }
    @GetMapping("/dentist/followup-plans/{planId}") Object dentist(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String planId) { return service.read(identity.require(h),Store.parseId(planId),EncounterAccess.Mode.DENTIST); }
    @GetMapping("/dentist/followup-plans") Object dentistList(@RequestHeader(value="Authorization",required=false) String h,@RequestParam String clinicId,@RequestParam(defaultValue="待复诊") String status,@RequestParam(required=false) String afterId,@RequestParam(defaultValue="30") int limit) { return service.dentistList(identity.require(h),Store.parseId(clinicId),status,afterId,limit); }
    @GetMapping("/staff/followup-plans") Object staffList(@RequestHeader(value="Authorization",required=false) String h,@RequestParam String clinicId,@RequestParam(defaultValue="待复诊") String status,@RequestParam(defaultValue="false") boolean overdueOnly,@RequestParam(required=false) String afterId,@RequestParam(defaultValue="30") int limit) { return service.list(identity.require(h),Store.parseId(clinicId),status,overdueOnly,afterId,limit,false); }
    @GetMapping("/staff/followup-plans/{planId}") Object staff(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String planId) { return service.read(identity.require(h),Store.parseId(planId),EncounterAccess.Mode.FRONT); }
    @GetMapping("/staff/followup-plans/{planId}/contacts") Object contacts(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String planId,@RequestParam(required=false) String afterId,@RequestParam(defaultValue="30") int limit) { return service.contacts(identity.require(h),Store.parseId(planId),afterId,limit); }
    @PostMapping("/staff/followup-plans/{planId}/contacts") Object contact(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String planId,@RequestBody FollowupRules.Contact request) { return service.contact(identity.require(h),Store.parseId(planId),request); }
    @GetMapping("/patient/followup-plans") Object patientList(@RequestHeader(value="Authorization",required=false) String h,@RequestParam(defaultValue="all") String status,@RequestParam(required=false) String afterId,@RequestParam(defaultValue="30") int limit) { return service.list(identity.require(h),null,status,false,afterId,limit,true); }
    @GetMapping("/patient/followup-plans/{planId}") Object patient(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String planId) { return service.read(identity.require(h),Store.parseId(planId),EncounterAccess.Mode.PATIENT); }
}
