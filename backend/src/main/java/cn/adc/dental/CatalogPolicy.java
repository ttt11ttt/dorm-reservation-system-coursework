package cn.adc.dental;

import java.time.*;
import java.util.*;

/** Pure public candidate projection. A count is never a reserved queue ticket. */
final class CatalogPolicy {
    private CatalogPolicy() {}
    static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    record Window(long scheduleId,Instant start,Instant end) {}
    record Busy(Instant start,Instant end) {}
    record Slot(long scheduleId,Instant start,Instant end,boolean available,String period) {}
    static boolean overlaps(Instant a,Instant b,Instant c,Instant d) { return a.isBefore(d)&&b.isAfter(c); }
    static List<Slot> slots(LocalDate day,Instant now,List<Window> windows,List<Busy> busy) {
        var ordered=windows.stream().sorted(Comparator.comparingLong(Window::scheduleId)).toList();
        var out=new ArrayList<Slot>();
        for(int minute=0;minute<1440;minute+=30) {
            var start=day.atStartOfDay(ZONE).plusMinutes(minute).toInstant();var end=start.plusSeconds(1800);
            if(start.isBefore(now))continue;
            Window owner=null;
            for(var w:ordered)if(!w.start().isAfter(start)&&!w.end().isBefore(end)){owner=w;break;}
            if(owner==null)continue;
            boolean free=busy.stream().noneMatch(b->overlaps(start,end,b.start(),b.end()));
            out.add(new Slot(owner.scheduleId(),start,end,free,minute<720?"AM":"PM"));
        }
        return List.copyOf(out);
    }
    static String status(boolean hasWindow,List<Slot> slots) {
        if(!hasWindow)return "NOT_SCHEDULED";
        return slots.stream().anyMatch(Slot::available)?"AVAILABLE":"FULL";
    }
}
