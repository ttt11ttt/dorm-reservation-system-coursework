package cn.adc.dental;

import java.time.*;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.*;
import org.springframework.web.bind.annotation.*;

/** Executed only by the isolated test runner; neither class nor clock route is packaged in the application JAR. */
public class VerificationServer {
    public static void main(String[] args) { new SpringApplicationBuilder(DentalApplication.class,ClockConfiguration.class).run(args); }
    static class MutableClock extends Clock {
        final AtomicReference<Instant> value=new AtomicReference<>(Instant.parse("2029-01-01T01:59:00Z"));
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(),zone); }
        @Override public Instant instant() { return value.get(); }
    }
    @Configuration(proxyBeanMethods=false)
    static class ClockConfiguration {
        @Bean @Primary MutableClock verificationClock() { return new MutableClock(); }
    }
    @RestController
    static class ClockApi {
        private final MutableClock clock;
        ClockApi(MutableClock clock) { this.clock=clock; }
        @PutMapping("/api/v1/__test/clock") Map<String,String> change(@RequestBody Map<String,String> body) { clock.value.set(Instant.parse(body.get("instant")));return Map.of("instant",clock.instant().toString()); }
    }
}
