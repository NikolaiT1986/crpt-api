package org.nikolait.crptapi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrptApiLimiterConcurrencyTest {

    static final class FastHttpAdapter implements CrptApi.HttpClientAdapter {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public HttpResponse<String> send(HttpRequest request) {
            calls.incrementAndGet();
            return new HttpResponse<>() {
                @Override
                public int statusCode() {
                    return 200;
                }

                @Override
                public HttpRequest request() {
                    return request;
                }

                @Override
                public java.util.Optional<HttpResponse<String>> previousResponse() {
                    return java.util.Optional.empty();
                }

                @Override
                public HttpHeaders headers() {
                    return HttpHeaders.of(java.util.Map.of(), (a, b) -> true);
                }

                @Override
                public String body() {
                    return "{}";
                }

                @Override
                public Optional<javax.net.ssl.SSLSession> sslSession() {
                    return Optional.empty();
                }

                @Override
                public java.net.URI uri() {
                    return request.uri();
                }

                @Override
                public HttpClient.Version version() {
                    return HttpClient.Version.HTTP_1_1;
                }
            };
        }
    }

    /**
     * Минимально валидный документ под текущую валидацию:
     * - tnved_code задан,
     * - РОВНО ОДИН из uit_code/uitu_code (ставим uit_code),
     * - обязательные поля верхнего уровня заполнены.
     */
    private static CrptApi.LpIntroduceGoodsDocument minimalDoc() {
        CrptApi.LpIntroduceGoodsDocument.Product p = new CrptApi.LpIntroduceGoodsDocument.Product();
        p.setTnved_code("123");
        p.setProduction_date("2025-10-30");
        p.setUit_code("UT-0001");           // <-- ставим только uit_code
        // p.setUitu_code(null);            // <-- на всякий случай не трогаем, оставляем null

        return CrptApi.LpIntroduceGoodsDocument.builder()
                .descriptionParticipantInn("7700000000")
                .docId("X")
                .docStatus("DRAFT")
                .docType("LP_INTRODUCE_GOODS")
                .importRequest(false)
                .ownerInn("7700000001")
                .participantInn("7700000002")
                .producerInn("7700000003")
                .productionDate("2025-10-30")
                .productionType("OWN_PRODUCTION")
                .products(java.util.List.of(p))
                .regDate("2025-10-30")
                .regNumber("R")
                .build();
    }

    @Test
    @Timeout(5)
    @DisplayName("SlidingWindowRateLimiter: при R>limit запросы растягиваются минимум на требуемое число окон")
    void limiter_underFullLoad_blocksAsExpected() throws Exception {
        long windowMillis = 80;
        int limit = 2;
        long windowNanos = TimeUnit.MILLISECONDS.toNanos(windowMillis);

        CrptApi.SlidingWindowRateLimiter limiter = new CrptApi.SlidingWindowRateLimiter(windowNanos, limit);
        FastHttpAdapter http = new FastHttpAdapter();
        CrptApi api = new CrptApi(CrptApi.DEFAULT_BASE_URI, CrptApi.DEFAULT_CREATE_PATH, limiter, http);

        int requests = 5; // > limit
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        List<Callable<Integer>> tasks = new ArrayList<>();

        String token = "T";
        String pg = "milk";
        String signature = Base64.getEncoder().encodeToString("sig".getBytes(StandardCharsets.UTF_8));
        CrptApi.LpIntroduceGoodsDocument doc = minimalDoc();

        for (int i = 0; i < requests; i++) {
            tasks.add(() -> {
                api.createLpIntroduceGoods(token, pg, doc, signature);
                return 1;
            });
        }

        long t0 = System.nanoTime();
        List<Future<Integer>> futures = pool.invokeAll(tasks);
        for (Future<Integer> f : futures) {
            f.get(3, TimeUnit.SECONDS); // запас по времени на планировщик
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        pool.shutdown();

        // Нижняя граница: ceil((R-L)/L) окон
        int windowsNeeded = (int) Math.ceil((requests - limit) / (double) limit);
        long minExpectedMs = windowsNeeded * windowMillis;

        long slackMs = 15; // небольшой допуск
        assertTrue(elapsedMs >= (minExpectedMs - slackMs),
                "Elapsed " + elapsedMs + "ms, expected >= " + (minExpectedMs - slackMs) + "ms");

        assertEquals(requests, http.calls.get(), "Все запросы должны дойти до HTTP-слоя");
    }
}
