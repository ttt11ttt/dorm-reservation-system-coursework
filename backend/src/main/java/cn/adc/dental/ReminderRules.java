package cn.adc.dental;

import java.time.*;
import java.util.*;
import com.fasterxml.jackson.annotation.JsonAnySetter;

/** Deterministic planning only: never delivers or marks a message sent. */
final class ReminderRules {
    enum Kind { APPOINTMENT, FOLLOWUP }
    record Settings(Integer appointmentLeadMinutes,Integer followupLeadDays,String followupSendTime,Boolean inAppEnabled,Boolean smsEnabled) {}
    record Snapshot(String ruleId,String clinicId,Integer appointmentLeadMinutes,Integer followupLeadDays,String followupSendTime,Boolean inAppEnabled,Boolean smsEnabled,String updatedBy,String updatedAt,String mutationCount) { @JsonAnySetter void unknown(String key,Object value){throw ApiError.bad();} }
    record Save(String ruleId,Snapshot expected,Integer appointmentLeadMinutes,Integer followupLeadDays,String followupSendTime,Boolean inAppEnabled,Boolean smsEnabled,String reason) { @JsonAnySetter void unknown(String key,Object value){throw ApiError.bad();} }
    record Business(Kind kind,long id,long patient,long clinic,String invalidReason,Instant appointmentStart,LocalDate suggestedDate,String phone) {}
    record Draft(String channel,Instant plannedAt,String recipient,String content,boolean immediate) {}
    record History(long id,boolean related,String channel,Instant plannedAt,String recipient,String content,String status,Instant sentAt) {}
    record Action(String operation,Long messageId,String channel,Instant plannedAt,String status,Draft draft) {}
    record Result(List<String> issues,List<Action> actions,boolean reconciliation) {}
    private ReminderRules() {}
    static String appointmentValidity(boolean active,String status,boolean attended,String treatment,Instant start,Instant now){return !active?"RESOURCE_INACTIVE":!"待到诊".equals(status)||attended||!"未开始".equals(treatment)||start==null||!start.isAfter(now)?"APPOINTMENT_STARTED_OR_INVALID":null;}
    static String followupValidity(boolean active,String status,boolean actualLinkedCompletion){return !active?"RESOURCE_INACTIVE":!"待复诊".equals(status)?"FOLLOWUP_TERMINAL":actualLinkedCompletion?"FOLLOWUP_COMPLETION_RECONCILIATION_REQUIRED":null;}
    static void authorize(Identity.Actor actor,long clinic,boolean manage) {
        if(!actor.hasRole("运营人员／店长",clinic)&&(manage||!actor.hasRole("前台",clinic)))throw ApiError.denied();
    }
    static LocalTime time(String value) {
        try{if(value==null||!value.matches("[0-9]{2}:[0-9]{2}(:[0-9]{2})?"))throw ApiError.bad();return LocalTime.parse(value);}catch(RuntimeException e){throw ApiError.bad();}
    }
    static String canonicalTime(String value) {return value==null?null:time(value).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));}
    static void settings(Settings s) {
        if(s==null||s.inAppEnabled()==null||s.smsEnabled()==null||s.appointmentLeadMinutes()!=null&&s.appointmentLeadMinutes()<0||s.followupLeadDays()!=null&&s.followupLeadDays()<0||(s.followupLeadDays()==null)!=(s.followupSendTime()==null))throw ApiError.bad();if(s.followupSendTime()!=null)time(s.followupSendTime());
    }
    static Settings settings(Save s) {return new Settings(s.appointmentLeadMinutes(),s.followupLeadDays(),canonicalTime(s.followupSendTime()),s.inAppEnabled(),s.smsEnabled());}
    static boolean ruleChangeSafe(List<String> statuses){return statuses.stream().allMatch(s->Set.of("已发送","已取消").contains(s));}
    static void compare(Snapshot expected,Snapshot current){expected(expected);if(!Objects.equals(expected,current))throw ApiError.conflict("RULE_SNAPSHOT_CONFLICT");}
    static void expected(Snapshot s) {
        if(s==null)return;Store.parseId(s.ruleId());Store.parseId(s.clinicId());Store.parseId(s.updatedBy());Store.offset(s.updatedAt());settings(new Settings(s.appointmentLeadMinutes(),s.followupLeadDays(),s.followupSendTime(),s.inAppEnabled(),s.smsEnabled()));
        if(s.mutationCount()==null||!s.mutationCount().matches("0|[1-9][0-9]{0,18}"))throw ApiError.bad();try{Long.parseLong(s.mutationCount());}catch(NumberFormatException e){throw ApiError.bad();}
    }
    static String phone(String value) {if(value==null)return null;String normalized=value.trim().replaceAll("[ ()-]","");return normalized.matches("\\+?[0-9]{6,20}")?normalized:null;}
    static String mask(String recipient,String channel) {if(recipient==null)return null;if(channel.equals("站内"))return recipient;return "***"+recipient.substring(Math.max(0,recipient.length()-4));}
    static boolean pending(History h){return h.related()&&h.status().equals("待发送")&&h.sentAt()==null;}
    static boolean cancelled(History h){return h.related()&&h.status().equals("已取消")&&h.sentAt()==null;}
    static boolean sent(History h){return h.related()&&h.status().equals("已发送")&&h.sentAt()!=null;}
    static boolean unknown(History h){return !pending(h)&&!cancelled(h)&&!sent(h);}
    private static Draft at(Draft d,Instant planned){return new Draft(d.channel(),planned,d.recipient(),d.content(),d.immediate());}
    private static boolean match(History h,Draft d,Instant now){return Objects.equals(h.content(),d.content())&&Objects.equals(h.recipient(),d.recipient())&&(h.plannedAt().equals(d.plannedAt())||d.immediate()&&!h.plannedAt().isAfter(now));}
    private static List<Draft> desired(Business b,Settings s,Instant now,List<String> issues){
        if(b.invalidReason()!=null){issues.add(b.invalidReason());return List.of();}if(s==null){issues.add("RULE_MISSING");return List.of();}settings(s);
        if(!s.inAppEnabled()&&!s.smsEnabled()){issues.add("CHANNELS_DISABLED");return List.of();}
        Instant planned;
        try{
            if(b.kind()==Kind.APPOINTMENT){if(b.appointmentStart()==null||!b.appointmentStart().isAfter(now)){issues.add("APPOINTMENT_STARTED_OR_INVALID");return List.of();}if(s.appointmentLeadMinutes()==null){issues.add("APPOINTMENT_LEAD_MISSING");return List.of();}planned=b.appointmentStart().minusSeconds(Math.multiplyExact(s.appointmentLeadMinutes().longValue(),60));}
            else {if(s.followupLeadDays()==null||s.followupSendTime()==null){issues.add("FOLLOWUP_CONFIG_MISSING");return List.of();}planned=b.suggestedDate().minusDays(s.followupLeadDays()).atTime(time(s.followupSendTime())).atZone(ZoneId.of("Asia/Shanghai")).toInstant();}
            if(planned.getEpochSecond()<1||planned.getEpochSecond()>2147483647L||planned.getNano()!=0||now.getEpochSecond()<1||now.getEpochSecond()>2147483647L)throw new ArithmeticException();
        }catch(DateTimeException|ArithmeticException e){issues.add("PLANNED_TIME_OUT_OF_RANGE");return List.of();}
        boolean immediate=!planned.isAfter(now);if(immediate)planned=now;
        String content=b.kind()==Kind.APPOINTMENT?"到诊提醒：预约时间为"+b.appointmentStart().atOffset(ZoneOffset.ofHours(8)).format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)+"，请按已确认的门店安排到诊；如需调整请联系前台。":"复诊提示：建议复诊日期为"+b.suggestedDate()+"。如已有后续安排，请按确认时间到诊；如需协助请联系门店。";
        var result=new ArrayList<Draft>();if(s.inAppEnabled())result.add(new Draft("站内",planned,"patient:"+b.patient(),content,immediate));
        if(s.smsEnabled()){String phone=phone(b.phone());if(phone==null)issues.add("SMS_RECIPIENT_MISSING");else result.add(new Draft("短信",planned,phone,content,immediate));}return result;
    }
    static Result plan(Business b,Settings settings,Instant now,List<History> history){
        var issues=new ArrayList<String>();var desired=desired(b,settings,now,issues);var actions=new LinkedHashMap<Long,Action>();var creations=new ArrayList<Action>();var channels=new TreeSet<String>();
        for(var h:history){channels.add(h.channel());actions.put(h.id(),new Action(unknown(h)?"RECONCILE":"KEEP",h.id(),h.channel(),h.plannedAt(),h.status(),null));}for(var d:desired)channels.add(d.channel());
        boolean reconciliation=history.stream().anyMatch(ReminderRules::unknown);
        for(String channel:channels){
            var rows=history.stream().filter(h->h.channel().equals(channel)).sorted(Comparator.comparingLong(History::id)).toList();var d=desired.stream().filter(x->x.channel().equals(channel)).findFirst().orElse(null);
            boolean blocked=rows.stream().anyMatch(ReminderRules::unknown);History selected=null;String operation=null;
            if(blocked){issues.add("RECONCILIATION_REQUIRED:"+channel);reconciliation=true;}
            else if(d!=null){
                var delivered=rows.stream().filter(h->sent(h)&&h.content().equals(d.content())).findFirst();
                if(delivered.isPresent()){var h=delivered.get();actions.put(h.id(),new Action("SENT_SUPPRESSED",h.id(),channel,h.plannedAt(),h.status(),null));}
                else {
                    selected=rows.stream().filter(h->pending(h)&&match(h,d,now)).findFirst().orElse(null);operation="REUSE";
                    if(selected==null){selected=rows.stream().filter(h->cancelled(h)&&match(h,d,now)).findFirst().orElse(null);operation="RESTORE";}
                    if(selected==null){var occupied=rows.stream().filter(h->h.plannedAt().equals(d.plannedAt())).findFirst();if(occupied.isPresent()){reconciliation=true;issues.add("NATURAL_KEY_REVIEW_REQUIRED:"+channel);var h=occupied.get();actions.put(h.id(),new Action("RECONCILE",h.id(),channel,h.plannedAt(),h.status(),null));}else creations.add(new Action("CREATE",null,channel,d.plannedAt(),"待发送",d));}
                }
            }
            for(var h:rows)if(pending(h)&&(selected==null||h.id()!=selected.id()))actions.put(h.id(),new Action("CANCEL",h.id(),channel,h.plannedAt(),"已取消",null));
            if(selected!=null)actions.put(selected.id(),new Action(operation,selected.id(),channel,selected.plannedAt(),"待发送",at(d,selected.plannedAt())));
        }
        var result=new ArrayList<>(actions.values());result.addAll(creations);return new Result(issues.stream().distinct().toList(),result,reconciliation);
    }
}
