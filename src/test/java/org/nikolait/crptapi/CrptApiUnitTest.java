package org.nikolait.crptapi;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CrptApiUnitTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    /**
     * Минимальный документ для успешной валидации: содержит tnved_code и РОВНО ОДИН из uit/uitu.
     */
    private static CrptApi.LpIntroduceGoodsDocument sampleDocWithUit() {
        CrptApi.LpIntroduceGoodsDocument.Product p = new CrptApi.LpIntroduceGoodsDocument.Product();
        p.setTnved_code("1234567");
        p.setUit_code("0104601234567890");
        p.setUitu_code(null);

        return CrptApi.LpIntroduceGoodsDocument.builder()
                .descriptionParticipantInn("7700000000")
                .docId("test-123")
                .docStatus("DRAFT")
                .docType("LP_INTRODUCE_GOODS")
                .importRequest(false)
                .ownerInn("7700000001")
                .participantInn("7700000002")
                .producerInn("7700000003")
                .productionDate("2025-10-30")
                .productionType("OWN_PRODUCTION")
                .products(List.of(p))
                .regDate("2025-10-30")
                .regNumber("REG-42")
                .build();
    }

    private static CrptApi.LpIntroduceGoodsDocument sampleDocWithUitu() {
        CrptApi.LpIntroduceGoodsDocument.Product p = new CrptApi.LpIntroduceGoodsDocument.Product();
        p.setTnved_code("1234567");
        p.setUit_code(null);
        p.setUitu_code("0104601234567890ABC");

        return CrptApi.LpIntroduceGoodsDocument.builder()
                .descriptionParticipantInn("7700000000")
                .docId("test-456")
                .docStatus("DRAFT")
                .docType("LP_INTRODUCE_GOODS")
                .importRequest(false)
                .ownerInn("7700000001")
                .participantInn("7700000002")
                .producerInn("7700000003")
                .productionDate("2025-10-30")
                .productionType("OWN_PRODUCTION")
                .products(List.of(p))
                .regDate("2025-10-30")
                .regNumber("REG-43")
                .build();
    }

    /**
     * Стаб HTTP-адаптера: перехватывает последний запрос и отдаёт подготовленный ответ.
     */
    private static final class CapturingHttpAdapter implements CrptApi.HttpClientAdapter {
        volatile HttpRequest lastRequest;
        final AtomicInteger sendCalls = new AtomicInteger();

        int status = 200;
        String body = "{\"ok\":true}";
        HttpHeaders headers = HttpHeaders.of(Map.of(), (a, b) -> true);

        @Override
        public HttpResponse<String> send(HttpRequest request) {
            this.lastRequest = request;
            sendCalls.incrementAndGet();
            return simpleResponse(status, body, headers, request.uri());
        }

        private static HttpResponse<String> simpleResponse(
                int status,
                String body,
                HttpHeaders headers,
                URI uri
        ) {
            return new HttpResponse<>() {
                @Override
                public int statusCode() {
                    return status;
                }

                @Override
                public HttpRequest request() {
                    return null;
                }

                @Override
                public Optional<HttpResponse<String>> previousResponse() {
                    return Optional.empty();
                }

                @Override
                public HttpHeaders headers() {
                    return headers;
                }

                @Override
                public String body() {
                    return body;
                }

                @Override
                public Optional<javax.net.ssl.SSLSession> sslSession() {
                    return Optional.empty();
                }

                @Override
                public URI uri() {
                    return uri;
                }

                @Override
                public HttpClient.Version version() {
                    return HttpClient.Version.HTTP_1_1;
                }
            };
        }
    }

    /**
     * Стаб лимитера: лишь считает вызовы acquire().
     */
    private static final class CountingLimiter implements CrptApi.RequestLimiter {
        final AtomicInteger acquires = new AtomicInteger();

        @Override
        public void acquire() {
            acquires.incrementAndGet();
        }
    }

    /**
     * Лимитер, который симулирует прерывание.
     */
    private static final class InterruptingLimiter implements CrptApi.RequestLimiter {
        @Override
        public void acquire() throws InterruptedException {
            throw new InterruptedException("simulated");
        }
    }

    /**
     * Достаём текст тела из HttpRequest.BodyPublisher через Flow API.
     */
    private static String readBody(HttpRequest request) throws Exception {
        Optional<HttpRequest.BodyPublisher> opt = request.bodyPublisher();
        assertTrue(opt.isPresent(), "BodyPublisher must be present");
        HttpRequest.BodyPublisher pub = opt.get();

        CountDownLatch done = new CountDownLatch(1);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        pub.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer item) {
                byte[] bytes = new byte[item.remaining()];
                item.get(bytes);
                baos.write(bytes, 0, bytes.length);
            }

            @Override
            public void onError(Throwable throwable) {
                done.countDown();
            }

            @Override
            public void onComplete() {
                done.countDown();
            }
        });

        assertTrue(done.await(5, TimeUnit.SECONDS), "Body read timed out");
        return baos.toString(StandardCharsets.UTF_8);
    }

    // ===================== ТЕСТЫ =====================

    @Test
    @DisplayName("createLpIntroduceGoods(passPgInQuery=true): правильные метод, URI, заголовки и тело")
    void create_buildsRequestCorrectly_queryPg() throws Exception {
        CapturingHttpAdapter http = new CapturingHttpAdapter();
        CountingLimiter limiter = new CountingLimiter();
        CrptApi api = new CrptApi(
                "https://markirovka.demo.crpt.tech",
                "/api/v3/lk/documents/create",
                limiter,
                http
        );

        String token = "ACCESS_TOKEN";
        String pg = "milk";
        CrptApi.LpIntroduceGoodsDocument doc = sampleDocWithUit();
        String signatureB64 = "BASE64_SIGNATURE";

        HttpResponse<String> resp =
                api.createLpIntroduceGoods(token, pg, doc, signatureB64);

        assertEquals(1, http.sendCalls.get());
        assertEquals(1, limiter.acquires.get());

        HttpRequest req = http.lastRequest;
        assertNotNull(req);

        assertEquals("POST", req.method());
        assertEquals(
                URI.create("https://markirovka.demo.crpt.tech/api/v3/lk/documents/create?pg=milk"),
                req.uri()
        );

        assertEquals("Bearer " + token, req.headers().firstValue("Authorization").orElse(null));
        assertEquals("application/json", req.headers().firstValue("Content-Type").orElse(null));

        String body = readBody(req);
        JsonNode root = MAPPER.readTree(body);
        assertEquals("MANUAL", root.path("document_format").asText());
        assertEquals("LP_INTRODUCE_GOODS", root.path("type").asText());
        assertEquals("BASE64_SIGNATURE", root.path("signature").asText());
        assertFalse(root.has("product_group"), "product_group should be absent when passed in query");

        String productDocumentB64 = root.path("product_document").asText();
        byte[] decoded = Base64.getDecoder().decode(productDocumentB64);
        String originalJson = new String(decoded, StandardCharsets.UTF_8);

        String expectedJson = MAPPER.writeValueAsString(doc);
        Map<String, Object> expectedMap = MAPPER.readValue(expectedJson, new TypeReference<>() {
        });
        Map<String, Object> actualMap = MAPPER.readValue(originalJson, new TypeReference<>() {
        });
        assertEquals(expectedMap, actualMap);

        assertEquals(200, resp.statusCode());
        assertEquals("{\"ok\":true}", resp.body());
    }

    @Test
    @DisplayName("createLpIntroduceGoods(passPgInQuery=false): pg идёт в теле, а не в query")
    void create_buildsRequestCorrectly_bodyPg() throws Exception {
        CapturingHttpAdapter http = new CapturingHttpAdapter();
        CountingLimiter limiter = new CountingLimiter();
        CrptApi api = new CrptApi(
                "https://markirovka.demo.crpt.tech",
                "/api/v3/lk/documents/create",
                limiter,
                http
        );

        String token = "ACCESS_TOKEN";
        String pg = "milk";
        CrptApi.LpIntroduceGoodsDocument doc = sampleDocWithUitu();
        String signatureB64 = "BASE64_SIGNATURE";

        HttpResponse<String> resp =
                api.createLpIntroduceGoods(token, pg, doc, signatureB64, false);

        assertEquals(200, resp.statusCode());
        assertEquals(
                URI.create("https://markirovka.demo.crpt.tech/api/v3/lk/documents/create"),
                http.lastRequest.uri()
        );

        String body = readBody(http.lastRequest);
        JsonNode root = MAPPER.readTree(body);
        assertEquals("milk", root.path("product_group").asText(), "pg must be in body when passPgInQuery=false");
    }

    @Test
    @DisplayName("Валидация документа: запрещены одновременно uit_code и uitu_code")
    void validate_rejectsBothUitAndUitu() {
        CrptApi.LpIntroduceGoodsDocument doc = sampleDocWithUit();
        CrptApi.LpIntroduceGoodsDocument.Product p = doc.getProducts().get(0);
        p.setUitu_code("X");
        assertThrows(IllegalArgumentException.class, () ->
                new CrptApi(TimeUnit.SECONDS, 1)
                        .createLpIntroduceGoods("T", "milk", doc, "S"));
    }

    @Test
    @DisplayName("Валидация документа: если ни одного из uit_code/uitu_code — ошибка")
    void validate_rejectsNeitherUitNorUitu() {
        CrptApi.LpIntroduceGoodsDocument doc = sampleDocWithUit();
        CrptApi.LpIntroduceGoodsDocument.Product p = doc.getProducts().get(0);
        p.setUit_code(null);
        p.setUitu_code(null);
        assertThrows(IllegalArgumentException.class, () ->
                new CrptApi(TimeUnit.SECONDS, 1)
                        .createLpIntroduceGoods("T", "milk", doc, "S"));
    }

    @Test
    @DisplayName("Аргументы метода: null/blank приводят к IllegalArgumentException")
    void create_nullOrBlankChecks() {
        CapturingHttpAdapter http = new CapturingHttpAdapter();
        CountingLimiter limiter = new CountingLimiter();
        CrptApi api = new CrptApi(
                "https://markirovka.demo.crpt.tech",
                "/api/v3/lk/documents/create",
                limiter,
                http
        );

        CrptApi.LpIntroduceGoodsDocument doc = sampleDocWithUit();

        assertThrows(IllegalArgumentException.class,
                () -> api.createLpIntroduceGoods(null, "milk", doc, "S"));
        assertThrows(IllegalArgumentException.class,
                () -> api.createLpIntroduceGoods("   ", "milk", doc, "S"));

        assertThrows(IllegalArgumentException.class,
                () -> api.createLpIntroduceGoods("T", null, doc, "S"));
        assertThrows(IllegalArgumentException.class,
                () -> api.createLpIntroduceGoods("T", "", doc, "S"));

        assertThrows(IllegalArgumentException.class,
                () -> api.createLpIntroduceGoods("T", "milk", null, "S"));

        assertThrows(IllegalArgumentException.class,
                () -> api.createLpIntroduceGoods("T", "milk", doc, null));
        assertThrows(IllegalArgumentException.class,
                () -> api.createLpIntroduceGoods("T", "milk", doc, " "));
    }

    @Test
    @DisplayName("Прерывание из лимитера пробрасывается, HTTP не вызывается")
    void create_interruptedBeforeHttp() {
        CapturingHttpAdapter http = new CapturingHttpAdapter();
        CrptApi api = new CrptApi(
                "https://markirovka.demo.crpt.tech",
                "/api/v3/lk/documents/create",
                new InterruptingLimiter(),
                http
        );

        assertThrows(InterruptedException.class,
                () -> api.createLpIntroduceGoods("T", "milk", sampleDocWithUit(), "S"));

        assertEquals(0, http.sendCalls.get(), "HTTP must NOT be called if limiter interrupted");
    }

    @Test
    @DisplayName("Конструктор из задания: применяются дефолты baseUri и createPath")
    void ctor_assignment_defaultsApplied() throws Exception {
        CrptApi api = new CrptApi(TimeUnit.SECONDS, 3);

        var baseUriF = CrptApi.class.getDeclaredField("baseUri");
        var createPathF = CrptApi.class.getDeclaredField("createPath");
        baseUriF.setAccessible(true);
        createPathF.setAccessible(true);

        URI baseUri = (URI) baseUriF.get(api);
        String createPath = (String) createPathF.get(api);

        assertEquals(URI.create(CrptApi.DEFAULT_BASE_URI), baseUri);
        assertEquals(CrptApi.DEFAULT_CREATE_PATH, createPath);
    }

    @Test
    @DisplayName("Нормализация пути: ведущий '/' добавляется при необходимости")
    void ctor_normalizesCreatePathLeadingSlash() throws Exception {
        var http = new CapturingHttpAdapter();
        var api = new CrptApi(
                "https://h",
                "api/v3/lk/documents/create",
                new CrptApi.SlidingWindowRateLimiter(1, 1),
                http
        );

        api.createLpIntroduceGoods("T", "milk", sampleDocWithUit(), "S");

        assertEquals(
                URI.create("https://h/api/v3/lk/documents/create?pg=milk"),
                http.lastRequest.uri()
        );
    }

    @Test
    @DisplayName("Конструкторы с ограничением по единице времени: проверки аргументов")
    void ctors_timeWindow_argValidation() {
        assertThrows(IllegalArgumentException.class, () -> new CrptApi(TimeUnit.SECONDS, 0));
        assertThrows(IllegalArgumentException.class, () -> new CrptApi(TimeUnit.SECONDS, -1));

        assertThrows(NullPointerException.class, () -> new CrptApi(null, 1));

        assertThrows(IllegalArgumentException.class, () -> new CrptApi(0L, TimeUnit.SECONDS, 1));
        assertThrows(IllegalArgumentException.class, () -> new CrptApi(-5L, TimeUnit.SECONDS, 1));

        assertThrows(IllegalArgumentException.class, () -> new CrptApi("https://h", 0L, TimeUnit.SECONDS, 1));
    }

    @Test
    @DisplayName("Конструктор с кастомным лимитером/HTTP: запрос идёт через переданные зависимости")
    void ctor_customLimiterAndHttp_areUsed() throws Exception {
        CapturingHttpAdapter http = new CapturingHttpAdapter();
        CountingLimiter limiter = new CountingLimiter();

        CrptApi api = new CrptApi(
                "https://example.org",
                "/api/v3/lk/documents/create",
                limiter,
                http
        );

        HttpResponse<String> r = api.createLpIntroduceGoods("T", "milk", sampleDocWithUit(), "S");

        assertEquals(200, r.statusCode());
        assertEquals(1, http.sendCalls.get());
        assertEquals(1, limiter.acquires.get());
        assertEquals(URI.create("https://example.org/api/v3/lk/documents/create?pg=milk"), http.lastRequest.uri());
    }

    @Test
    @DisplayName("Конструктор (baseUri + limiter): нормализация пути по умолчанию и проверка аргументов")
    void ctor_baseUriAndLimiter_pathDefaultAndArgs() {
        assertThrows(IllegalArgumentException.class,
                () -> new CrptApi("   ", new CrptApi.SlidingWindowRateLimiter(1, 1)));

        assertThrows(NullPointerException.class,
                () -> new CrptApi("https://h", null));
    }

    @Test
    @DisplayName("Конструктор (baseUri, createPath, limiter): проверки аргументов")
    void ctor_fullArgs_argValidation() {
        CrptApi.RequestLimiter limiter = new CrptApi.SlidingWindowRateLimiter(1, 1);
        CrptApi.HttpClientAdapter http = new CapturingHttpAdapter();

        assertThrows(IllegalArgumentException.class, () -> new CrptApi(" ", "/p", limiter, http));
        assertThrows(IllegalArgumentException.class, () -> new CrptApi("https://h", " ", limiter, http));
        assertThrows(NullPointerException.class, () -> new CrptApi("https://h", "/p", null, http));
        assertThrows(NullPointerException.class, () -> new CrptApi("https://h", "/p", limiter, null));
    }

    @Test
    @DisplayName("Ctor(baseUri, windowAmount, unit, limit): успешный вызов createLpIntroduceGoods")
    void ctor_baseUri_windowAmount_positive() throws Exception {
        var http = new CapturingHttpAdapter();
        var api = new CrptApi(
                "https://ex",
                2L, TimeUnit.SECONDS, 5 // окно 2 сек, лимит 5
        );
        // подменим http и limiter через рефлексию, чтобы не зависеть от реального клиента
        var httpF = CrptApi.class.getDeclaredField("http");
        httpF.setAccessible(true);
        httpF.set(api, http);
        var limiterF = CrptApi.class.getDeclaredField("limiter");
        limiterF.setAccessible(true);
        limiterF.set(api, new CountingLimiter());

        var resp = api.createLpIntroduceGoods("T", "milk", sampleDocWithUit(), "S");
        assertEquals(200, resp.statusCode());
        assertTrue(http.lastRequest.uri().toString().startsWith("https://ex/"));
    }

    @Test
    @DisplayName("Ctor(baseUri, createPath, windowAmount, unit, limit): нормализует путь и работает")
    void ctor_baseUri_createPath_windowAmount_positive() throws Exception {
        var http = new CapturingHttpAdapter();
        var api = new CrptApi(
                "https://h",
                "api/v3/lk/documents/create",
                5L, TimeUnit.SECONDS, 10
        );
        var httpF = CrptApi.class.getDeclaredField("http");
        httpF.setAccessible(true);
        httpF.set(api, http);
        var limiterF = CrptApi.class.getDeclaredField("limiter");
        limiterF.setAccessible(true);
        limiterF.set(api, new CountingLimiter());

        api.createLpIntroduceGoods("T", "milk", sampleDocWithUit(), "S");
        assertEquals(URI.create("https://h/api/v3/lk/documents/create?pg=milk"), http.lastRequest.uri());
    }

}
