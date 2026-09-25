package com.buildrisk.radar.adapters.common;

import com.buildrisk.radar.adapters.dart.DartApiException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * 외부 API 호출 계측 — buildrisk_external_api_seconds{provider, operation, outcome}.
 * outcome: ok · upstream(네트워크·5xx) · quota(요청 제한) · rejected(키·권한) · error
 * Grafana 에서 제공자별 지연·오류율·호출량을 보고, 한도(020) 접근을 알림으로 잡을 수 있습니다.
 */
@Component
public class ExternalApiMetrics {
    private final MeterRegistry registry;

    public ExternalApiMetrics(MeterRegistry registry) { this.registry = registry; }

    public <T> T time(String provider, String operation, Supplier<T> call) {
        long start = System.nanoTime();
        String outcome = "ok";
        try {
            return call.get();
        } catch (QuotaExceededException e) {
            outcome = "quota";
            throw e;
        } catch (DartApiException | ApiKeyMissingException | ApiKeyRejectedException e) {
            outcome = "rejected";
            throw e;
        } catch (UpstreamException e) {
            outcome = "upstream";
            throw e;
        } catch (RuntimeException e) {
            outcome = "error";
            throw e;
        } finally {
            Timer.builder("buildrisk.external.api")
                    .description("외부 API 호출 시간")
                    .tag("provider", provider).tag("operation", operation).tag("outcome", outcome)
                    .publishPercentileHistogram()
                    .register(registry)
                    .record(System.nanoTime() - start, java.util.concurrent.TimeUnit.NANOSECONDS);
        }
    }
}
