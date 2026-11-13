package cn.adc.dental;

import java.time.Instant;
import java.util.*;

/** Pure association, lock-resource and clinical completion decisions; no database or network. */
final class FollowupLinkRules {
    record Plan(long id,FollowupRules.Owner owner,String status,Long completedId,Instant completedAt) {}
    record Resources(List<Long> clinics,List<Long> dentists) {}
    record BookingIdentity(Long requestedPlanId,Long previousId,long actor,long patient,long clinic,long dentist,long schedule,Instant start,Instant end,String channel,String requested,String special) {}
    record Clinical(long id,long patient,Long planId,boolean attended,String appointmentStatus,String treatmentStatus,Instant completedAt) {}
    record Decision(boolean updatePlan,String status,String outcome,Long completedId,Instant completedAt,boolean reconciliation) {}
    record NotificationDecision(boolean cancel,boolean reconcile) {}
    private FollowupLinkRules() {}
    static Resources resources(Collection<Long> clinics,Collection<Long> dentists) { return new Resources(new TreeSet<>(clinics).stream().toList(),new TreeSet<>(dentists).stream().toList()); }
    static void authorize(Identity.Actor actor,Plan plan,long targetClinic,boolean self) {
        if(self) { actor.patient();if(actor.patientId()!=plan.owner().patient())throw ApiError.denied(); }
        else { actor.front(plan.owner().clinic());actor.front(targetClinic); }
    }
    static void newAssociation(Plan plan,long patient) { if(plan.owner().patient()!=patient)throw ApiError.conflict("FOLLOWUP_SOURCE_INVALID");if(!"待复诊".equals(plan.status()))throw ApiError.conflict("INVALID_STATE"); }
    static boolean sameCreation(BookingIdentity requested,BookingIdentity stored,Long currentPlanId) { return requested.equals(stored)&&(requested.requestedPlanId()==null||Objects.equals(requested.requestedPlanId(),currentPlanId)); }
    static NotificationDecision notification(String status,boolean sentAt,boolean related,boolean allowNew) {
        var action=FollowupRules.reminder(status,sentAt);
        return new NotificationDecision(related&&allowNew&&action==FollowupRules.ReminderAction.CANCEL,!related||action==FollowupRules.ReminderAction.RECONCILE||!allowNew&&action==FollowupRules.ReminderAction.CANCEL);
    }
    static Decision completion(Plan plan,Clinical clinical,boolean allowNew) {
        if(!Objects.equals(clinical.planId(),plan.id())||clinical.patient()!=plan.owner().patient()||clinical.id()==plan.owner().source()||!clinical.attended()||!"已到诊".equals(clinical.appointmentStatus())||!"已完成".equals(clinical.treatmentStatus())||clinical.completedAt()==null)throw ApiError.conflict("FOLLOWUP_SOURCE_INVALID");
        return switch(plan.status()) {
            case "待复诊" -> allowNew?new Decision(true,"已完成","COMPLETED_NOW",clinical.id(),clinical.completedAt(),false):new Decision(false,plan.status(),"LEGACY_RECONCILIATION_REQUIRED",null,null,true);
            case "已取消" -> new Decision(false,plan.status(),"CANCELLED_PRESERVED",plan.completedId(),plan.completedAt(),false);
            case "已完成" -> new Decision(false,plan.status(),Objects.equals(plan.completedId(),clinical.id())?"ALREADY_COMPLETED_BY_THIS":"COMPLETED_BY_OTHER",plan.completedId(),plan.completedAt(),!Objects.equals(plan.completedId(),clinical.id())||!Objects.equals(plan.completedAt(),clinical.completedAt()));
            default -> throw ApiError.conflict("INVALID_STATE");
        };
    }
}
