package cn.adc.dental;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import com.fasterxml.jackson.annotation.JsonAnySetter;

/** Pure policy: no database, application startup, HTTP, or message delivery. */
final class FollowupRules {
    static final ZoneId BUSINESS_ZONE=ZoneId.of("Asia/Shanghai");
    static final Set<String> STATUSES=Set.of("待复诊","已完成","已取消");
    static final Set<String> CONTACT_RESULTS=Set.of("未接通","已提醒","已约待诊","需调整","暂不复诊","其他");
    record Owner(long patient,long dentist,long clinic,long source) {}
    record Source(Owner owner,boolean attended,String appointmentStatus,String treatmentStatus,Instant completedAt) {}
    record Observation(String planStatus,boolean hasActiveAppointment,String contactResult) {}
    record DentistListScope(long clinic,long dentist,long after,int limit) {
        boolean includes(Owner owner) { return clinic==owner.clinic()&&dentist==owner.dentist(); }
    }
    record Snapshot(String patientId,String sourceAppointmentId,String dentistId,String clinicId,String suggestedDate,String recommendation,String status,String completedAppointmentId,String completedAt,String cancelReason,String createdAt,String updatedAt,String mutationCount) {
        @JsonAnySetter void unknown(String key,Object value) { throw ApiError.bad(); }
    }
    record Creation(String planId,String suggestedDate,String recommendation) { @JsonAnySetter void unknown(String key,Object value) { throw ApiError.bad(); } }
    record Change(Snapshot expected,String suggestedDate,String recommendation,String reason) { @JsonAnySetter void unknown(String key,Object value) { throw ApiError.bad(); } }
    record Cancellation(Snapshot expected,String reason) { @JsonAnySetter void unknown(String key,Object value) { throw ApiError.bad(); } }
    record Contact(String contactId,String contactedAt,String result,String note,String nextContactAt) { @JsonAnySetter void unknown(String key,Object value) { throw ApiError.bad(); } }
    enum ReminderAction { CANCEL, KEEP, RECONCILE }
    private FollowupRules() {}
    static LocalDate date(String value) {
        try { if(value==null||!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))throw ApiError.bad();var d=LocalDate.parse(value);if(d.getYear()<1000)throw ApiError.bad();return d; }
        catch(RuntimeException e) { throw ApiError.bad(); }
    }
    static String text(String value) { return Store.required(value,2000); }
    static LocalDate today(Clock clock) { return LocalDate.now(clock.withZone(BUSINESS_ZONE)); }
    static String observedStatus(Observation observation) {
        if(observation==null||!STATUSES.contains(Objects.toString(observation.planStatus(),""))||observation.contactResult()!=null&&!CONTACT_RESULTS.contains(observation.contactResult()))throw ApiError.bad();
        // Neither a booking nor a contact is evidence of actual clinical completion or a dentist's cancellation.
        return observation.planStatus();
    }
    static boolean overdue(Observation observation,LocalDate suggested,Clock clock) { return overdueOn(observation,suggested,today(clock)); }
    static boolean overdueOn(Observation observation,LocalDate suggested,LocalDate today) { return "待复诊".equals(observedStatus(observation))&&suggested.isBefore(today.minusDays(3)); }
    static void authorize(Identity.Actor actor,Owner owner,EncounterAccess.Mode mode) {
        switch(mode) {
            case FRONT -> actor.front(owner.clinic());
            case DENTIST -> actor.dentist(owner.clinic(),owner.dentist());
            case PATIENT -> { actor.patient();if(actor.patientId()!=owner.patient())throw ApiError.denied(); }
            default -> throw ApiError.denied();
        }
    }
    static void source(Source source,Owner expected,Clock clock) {
        if(source==null||!source.owner().equals(expected)||!source.attended()||!"已到诊".equals(source.appointmentStatus())||!"已完成".equals(source.treatmentStatus())||source.completedAt()==null||source.completedAt().isAfter(clock.instant()))throw ApiError.conflict("FOLLOWUP_SOURCE_INVALID");
    }
    static void expected(Snapshot snapshot) {
        if(snapshot==null)throw ApiError.bad();
        Store.parseId(snapshot.patientId());Store.parseId(snapshot.sourceAppointmentId());Store.parseId(snapshot.dentistId());Store.parseId(snapshot.clinicId());
        date(snapshot.suggestedDate());text(snapshot.recommendation());if(!STATUSES.contains(Objects.toString(snapshot.status(),"")))throw ApiError.bad();
        Store.offset(snapshot.createdAt());Store.offset(snapshot.updatedAt());
        if(snapshot.completedAppointmentId()!=null)Store.parseId(snapshot.completedAppointmentId());if(snapshot.completedAt()!=null)Store.offset(snapshot.completedAt());if(snapshot.cancelReason()!=null)text(snapshot.cancelReason());
        if(snapshot.mutationCount()==null||!snapshot.mutationCount().matches("0|[1-9][0-9]{0,18}"))throw ApiError.bad();
        try { Long.parseLong(snapshot.mutationCount()); } catch(NumberFormatException e) { throw ApiError.bad(); }
    }
    static void compare(Snapshot expected,Snapshot actual) { expected(expected);if(!expected.equals(actual))throw ApiError.conflict("FOLLOWUP_CONFLICT"); }
    static void pending(String status,boolean actualCompleted) { if(!"待复诊".equals(status))throw ApiError.conflict("INVALID_STATE");if(actualCompleted)throw ApiError.conflict("FOLLOWUP_COMPLETION_PENDING"); }
    static void contact(Contact contact,Clock clock) {
        if(contact==null)throw ApiError.bad();Store.parseId(contact.contactId());text(contact.note());if(!CONTACT_RESULTS.contains(Objects.toString(contact.result(),"")))throw ApiError.bad();
        Timestamp time=Store.offset(contact.contactedAt());if(time.toInstant().isAfter(clock.instant()))throw ApiError.bad();
        if(contact.nextContactAt()!=null&&!Store.offset(contact.nextContactAt()).after(time))throw ApiError.bad();
    }
    static ReminderAction reminder(String status,boolean hasSentAt) {
        return switch(Objects.toString(status,"")) { case "待发送" -> hasSentAt?ReminderAction.RECONCILE:ReminderAction.CANCEL;case "已发送","已取消" -> ReminderAction.KEEP;default -> ReminderAction.RECONCILE; };
    }
    static void page(String status,boolean overdueOnly,int limit) { if(!STATUSES.contains(Objects.toString(status,""))&&!"all".equals(status)||overdueOnly&&!Set.of("待复诊","all").contains(Objects.toString(status,""))||limit<1||limit>100)throw ApiError.bad(); }
    static DentistListScope dentistListScope(Identity.Actor actor,long clinic,String status,String afterId,int limit) {
        page(status,false,limit);long after=afterId==null?0:Store.parseId(afterId);
        if(actor.dentistId()==null)throw ApiError.denied();actor.dentist(clinic,actor.dentistId());
        return new DentistListScope(clinic,actor.dentistId(),after,limit);
    }
}
