package cn.adc.dental;

import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/** No sockets, real credentials, SQL, session issuance or production writes. */
public final class WechatTransportLifecycleCheck {
    private static int checks;
    private static void check(boolean condition) { checks++; if (!condition) throw new AssertionError("check " + checks); }
    private static final class Subscription implements Flow.Subscription {
        boolean cancelled; public void request(long n) {} public void cancel() { cancelled = true; }
    }
    private static void reject(String uri) {
        try { WechatHttpsTransport.request(URI.create(uri), Map.of("fixture", "opaque")); throw new AssertionError(); }
        catch (ApiError error) { check(error.status == 503 && error.getCause() == null); }
    }
    public static void main(String[] args) {
        var request = WechatHttpsTransport.request(URI.create("https://fixture.invalid/review-required"), Map.of("fixture", "a&b= c"));
        check(request.method().equals("GET"));
        check(request.uri().getRawQuery().equals("fixture=a%26b%3D+c"));
        check(request.timeout().orElseThrow().toSeconds() == 5);
        for (String uri : new String[]{"http://fixture.invalid/x", "https://user@fixture.invalid/x",
                "https://fixture.invalid/x?q=y", "https://fixture.invalid/x#fragment", "https://fixture.invalid:8443/x"}) reject(uri);
        var limited = new WechatHttpsTransport.LimitedBody(); var subscription = new Subscription(); limited.onSubscribe(subscription);
        limited.onNext(List.of(ByteBuffer.wrap(new byte[WechatHttpsTransport.MAX_RESPONSE_BYTES]))); limited.onComplete();
        check(limited.getBody().toCompletableFuture().join().length == WechatHttpsTransport.MAX_RESPONSE_BYTES);
        check(!subscription.cancelled);
        var tooLarge = new WechatHttpsTransport.LimitedBody(); var overflow = new Subscription(); tooLarge.onSubscribe(overflow);
        tooLarge.onNext(List.of(ByteBuffer.wrap(new byte[WechatHttpsTransport.MAX_RESPONSE_BYTES]), ByteBuffer.wrap(new byte[1])));
        check(overflow.cancelled && tooLarge.getBody().toCompletableFuture().isCompletedExceptionally());
        var failed = new WechatHttpsTransport.LimitedBody(); failed.onSubscribe(new Subscription());
        failed.onError(new IllegalStateException("fixture-private-do-not-propagate"));
        check(failed.getBody().toCompletableFuture().handle((body,error) -> error.getCause() == null
                && !error.getMessage().contains("fixture-private")).join());
        var calls = new AtomicInteger();
        var transport = new WechatHttpsTransport((http,handler) -> {
            calls.incrementAndGet();
            return CompletableFuture.failedFuture(new IllegalStateException("fixture-private-do-not-propagate"));
        });
        try { transport.exchange(URI.create("https://fixture.invalid/review-required"), Map.of("fixture", "opaque")); throw new AssertionError(); }
        catch (ApiError error) { check(error.status == 503 && error.getCause() == null && !error.getMessage().contains("fixture-private")); }
        check(calls.get() == 1); // No application retry on failure.
        String originalLogging = System.getProperty("jdk.httpclient.HttpClient.log");
        try {
            System.setProperty("jdk.httpclient.HttpClient.log", "requests");
            try { transport.exchange(URI.create("https://fixture.invalid/review-required"), Map.of("fixture", "opaque")); throw new AssertionError(); }
            catch (ApiError error) { check(error.status == 503); }
            check(calls.get() == 1); // Wire-logging configuration blocks before any send.
        } finally {
            if (originalLogging == null) System.clearProperty("jdk.httpclient.HttpClient.log");
            else System.setProperty("jdk.httpclient.HttpClient.log", originalLogging);
        }

        var now = Instant.parse("2026-10-24T10:00:00Z");
        var binding = new WechatPatientLoginRules.Binding(101, 201, "患者", true, true, 7, true);
        var stamp = WechatPatientLoginRules.bound(binding, 1);
        for (var source : WechatSessionLifecycle.Source.values()) {
            var issued = new WechatSessionLifecycle.Issued(source, "fixture-app", stamp, "fixture-fingerprint", now.plusSeconds(28800));
            check(WechatSessionLifecycle.current(issued, now, "fixture-app", binding, 1, "fixture-fingerprint")
                    == (source == WechatSessionLifecycle.Source.WECHAT_PATIENT));
        }
        var issued = new WechatSessionLifecycle.Issued(WechatSessionLifecycle.Source.WECHAT_PATIENT, "fixture-app", stamp,
                "fixture-fingerprint", now.plusSeconds(28800));
        check(!WechatSessionLifecycle.current(issued, now.plusSeconds(28800), "fixture-app", binding, 1, "fixture-fingerprint"));
        check(!WechatSessionLifecycle.current(issued, now, "other-app", binding, 1, "fixture-fingerprint"));
        check(!WechatSessionLifecycle.current(issued, now, "fixture-app", binding, 1, "reset-fingerprint"));
        check(!WechatSessionLifecycle.current(issued, now, "fixture-app", null, 0, "fixture-fingerprint"));
        check(!WechatSessionLifecycle.current(issued, now, "fixture-app", binding, 2, "fixture-fingerprint"));
        check(!WechatSessionLifecycle.current(issued, now, "fixture-app",
                new WechatPatientLoginRules.Binding(101, 201, "患者", true, true, 8, true), 1, "fixture-fingerprint"));
        System.out.println("WechatTransportLifecycleCheck: " + checks + " passed (no network/SQL/tokens)");
    }
}
