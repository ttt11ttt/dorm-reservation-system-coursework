package cn.adc.dental;

import java.time.*;
import java.util.*;

/** Pure memory: no Spring, DB, network, patient or guest records. */
public final class CatalogPolicyCheck {
    private static int checks;
    private static void yes(boolean value) { if(!value)throw new AssertionError("catalog check "+(++checks));checks++; }
    public static void main(String[] args) {
        var day=LocalDate.of(2026,10,26);var zone=CatalogPolicy.ZONE;
        var nine=day.atTime(9,0).atZone(zone).toInstant();var noon=day.atTime(12,0).atZone(zone).toInstant();
        var one=day.atTime(13,0).atZone(zone).toInstant();
        var windows=List.of(new CatalogPolicy.Window(11,nine,noon),new CatalogPolicy.Window(12,nine,noon),new CatalogPolicy.Window(13,one,one.plusSeconds(3600)));
        var busy=List.of(new CatalogPolicy.Busy(nine.plusSeconds(1800),nine.plusSeconds(3600)));
        var slots=CatalogPolicy.slots(day,nine.minusSeconds(1),windows,busy);
        yes(slots.size()==8);yes(slots.stream().filter(CatalogPolicy.Slot::available).count()==7);
        yes(slots.getFirst().scheduleId()==11);yes("AM".equals(slots.getFirst().period()));
        yes("PM".equals(slots.getLast().period()));
        yes(!CatalogPolicy.overlaps(nine,nine.plusSeconds(1800),nine.plusSeconds(1800),nine.plusSeconds(3600)));
        yes(CatalogPolicy.status(false,List.of()).equals("NOT_SCHEDULED"));
        yes(CatalogPolicy.status(true,List.of()).equals("FULL"));
        yes(CatalogPolicy.slots(day,nine.plusSeconds(1),List.of(new CatalogPolicy.Window(1,nine,nine.plusSeconds(1800))),List.of()).isEmpty());
        System.out.println("CatalogPolicyCheck: "+checks+" passed (pure memory only)");
    }
}
