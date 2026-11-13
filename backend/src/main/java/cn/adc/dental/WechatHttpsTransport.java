package cn.adc.dental;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** Bounded execution layer used only by the fixed official protocol adapter. */
final class WechatHttpsTransport {
    static final int MAX_RESPONSE_BYTES = 16 * 1024; // Project limit, not a WeChat protocol assertion.
    private static final Duration DEADLINE = Duration.ofSeconds(5);
    interface Sender { CompletableFuture<HttpResponse<byte[]>> send(HttpRequest request, HttpResponse.BodyHandler<byte[]> body); }
    private final Sender sender;
    WechatHttpsTransport() {
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        sender = client::sendAsync;
    }
    // Local fixture seam; not a bean, runtime configuration hook or identity provider.
    WechatHttpsTransport(Sender sender) { this.sender = sender; }

    // Must only be called by a future reviewed protocol adapter, never with client-controlled settings.
    byte[] exchange(URI reviewedEndpoint, Map<String, String> reviewedParameters) {
        HttpRequest request = request(reviewedEndpoint, reviewedParameters);
        // JDK wire logging may include credential-bearing query strings. Refuse that configuration.
        if (!System.getProperty("jdk.httpclient.HttpClient.log", "").isBlank()
                || Boolean.getBoolean("jdk.internal.httpclient.disableHostnameVerification")) throw unavailable();
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            pending = sender.send(request, ignored -> new LimitedBody());
            HttpResponse<byte[]> response = pending.get(DEADLINE.toMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode() != 200) throw unavailable();
            return response.body();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (ExecutionException | TimeoutException | RuntimeException failure) {
            // Do not retain a cause/message containing the URL, code, secret or upstream body.
            throw unavailable();
        } finally {
            if (pending != null && !pending.isDone()) pending.cancel(true);
        }
    }

    static HttpRequest request(URI endpoint, Map<String, String> parameters) {
        // Host/path must come from reviewed official evidence, absent from current runtime wiring.
        if (endpoint == null || !"https".equals(endpoint.getScheme()) || endpoint.getHost() == null
                || endpoint.getRawUserInfo() != null || endpoint.getRawQuery() != null
                || endpoint.getRawFragment() != null || endpoint.getPort() != -1
                || parameters == null || parameters.isEmpty()) throw unavailable();
        StringBuilder query = new StringBuilder();
        for (var field : parameters.entrySet()) {
            if (field.getKey() == null || field.getKey().isBlank() || field.getValue() == null
                    || field.getValue().isBlank()) throw unavailable();
            if (!query.isEmpty()) query.append('&');
            query.append(encode(field.getKey())).append('=').append(encode(field.getValue()));
        }
        return HttpRequest.newBuilder(URI.create(endpoint.toASCIIString() + "?" + query))
                .timeout(DEADLINE).header("Accept", "application/json").GET().build();
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static ApiError unavailable() { return new ApiError(503, "WECHAT_LOGIN_UNAVAILABLE", "微信登录暂不可用"); }

    /** Cancels before copying any buffer that would exceed the configured cap. */
    static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription incoming) {
            if (subscription != null) { incoming.cancel(); return; }
            subscription = incoming;
            incoming.request(1);
        }
        public void onNext(List<ByteBuffer> buffers) {
            if (result.isDone()) return;
            long incoming = buffers.stream().mapToLong(ByteBuffer::remaining).sum();
            if (incoming > MAX_RESPONSE_BYTES - body.size()) {
                subscription.cancel(); body.reset(); result.completeExceptionally(unavailable()); return;
            }
            for (ByteBuffer buffer : buffers) {
                byte[] bytes = new byte[buffer.remaining()]; buffer.get(bytes); body.writeBytes(bytes);
            }
            subscription.request(1);
        }
        public void onError(Throwable ignored) { body.reset(); result.completeExceptionally(unavailable()); }
        public void onComplete() { result.complete(body.toByteArray()); body.reset(); }
    }
}
