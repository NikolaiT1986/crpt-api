package org.nikolait.crptapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;

import static org.junit.jupiter.api.Assertions.*;

class CrptApiDocsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    // ======================== СТАБЫ ========================

    static final class CapturingHttpAdapter implements CrptApi.HttpClientAdapter {
        volatile HttpRequest lastRequest;
        int calls;

        @Override
        public HttpResponse<String> send(HttpRequest request) {
            this.lastRequest = request;
            calls++;
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
                public Optional<HttpResponse<String>> previousResponse() {
                    return Optional.empty();
                }

                @Override
                public HttpHeaders headers() {
                    return HttpHeaders.of(Map.of(), (a, b) -> true);
                }

                @Override
                public String body() {
                    return "{\"ok\":true}";
                }

                @Override
                public Optional<javax.net.ssl.SSLSession> sslSession() {
                    return Optional.empty();
                }

                @Override
                public URI uri() {
                    return request.uri();
                }

                @Override
                public HttpClient.Version version() {
                    return HttpClient.Version.HTTP_1_1;
                }
            };
        }
    }

    static final class CountingLimiter implements CrptApi.RequestLimiter {
        int acquires;

        @Override
        public void acquire() {
            acquires++;
        }
    }

    // ======================== ДАННЫЕ ========================

    static List<String> docs() {
        return Arrays.asList(
                "docs/lp_doc_minimal.json",
                "docs/lp_doc_with_extras.json"
        );
    }

    // ======================== ХЕЛПЕРЫ ========================

    /**
     * Прод-код валидирует «ровно одно из uit_code/uitu_code».
     * JSON-файлы этого не гарантируют — в тесте доводим документ до валидного состояния.
     */
    private static void ensureExactlyOneOfUitOrUitu(CrptApi.LpIntroduceGoodsDocument doc) {
        if (doc.getProducts() == null) return;
        for (CrptApi.LpIntroduceGoodsDocument.Product p : doc.getProducts()) {
            boolean hasUit = p.getUit_code() != null && !p.getUit_code().trim().isEmpty();
            boolean hasUitu = p.getUitu_code() != null && !p.getUitu_code().trim().isEmpty();
            if (hasUit == hasUitu) {
                p.setUitu_code(null);
                p.setUit_code("TEST-UIT-0000000000000");
            }
        }
    }

    private static String readBody(HttpRequest request) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription s) {
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer item) {
                byte[] bytes = new byte[item.remaining()];
                item.get(bytes);
                baos.write(bytes, 0, bytes.length);
            }

            @Override
            public void onError(Throwable t) {
                done.countDown();
            }

            @Override
            public void onComplete() {
                done.countDown();
            }
        });
        assertTrue(done.await(3, java.util.concurrent.TimeUnit.SECONDS), "Body read timed out");
        return baos.toString(StandardCharsets.UTF_8);
    }

    // ======================== ТЕСТ ========================

    @ParameterizedTest(name = "Документ из {0} сериализуется в product_document (base64) эквивалентно POJO")
    @MethodSource("docs")
    @DisplayName("createLpIntroduceGoods: product_document соответствует ObjectMapper.valueToTree(doc)")
    void create_fromResourceDocs_buildsProperRequest(String resourcePath) throws Exception {
        // 1) Загружаем JSON
        String srcJson;
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            assertNotNull(is, "Resource not found: " + resourcePath);
            srcJson = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }

        // 2) Десериализуем в POJO
        CrptApi.LpIntroduceGoodsDocument doc =
                MAPPER.readValue(srcJson, CrptApi.LpIntroduceGoodsDocument.class);

        // 3) Делаем документ валидным под правило uit/uitu
        ensureExactlyOneOfUitOrUitu(doc);

        // 4) Готовим окружение клиента
        CapturingHttpAdapter http = new CapturingHttpAdapter();
        CountingLimiter limiter = new CountingLimiter();
        CrptApi api = new CrptApi(
                CrptApi.DEFAULT_BASE_URI,
                CrptApi.DEFAULT_CREATE_PATH,
                limiter,
                http
        );

        String token = "TKN";
        String pg = "milk";
        String signature = Base64.getEncoder().encodeToString("fake-signature".getBytes(StandardCharsets.UTF_8));

        // 5) Вызываем createLpIntroduceGoods (passPgInQuery=true по умолчанию)
        HttpResponse<String> response = api.createLpIntroduceGoods(token, pg, doc, signature);

        // 6) Проверяем базовые инварианты
        assertEquals(200, response.statusCode());
        assertEquals(1, limiter.acquires);
        assertEquals(1, http.calls);

        HttpRequest req = http.lastRequest;
        assertEquals("POST", req.method());
        assertEquals(URI.create(CrptApi.DEFAULT_BASE_URI + CrptApi.DEFAULT_CREATE_PATH + "?pg=milk"), req.uri());
        assertEquals("Bearer " + token, req.headers().firstValue("Authorization").orElse(null));
        assertEquals("application/json", req.headers().firstValue("Content-Type").orElse(null));

        // 7) Тело: формат, тип, подпись, pg=null в body
        String body = readBody(req);
        JsonNode root = MAPPER.readTree(body);
        assertEquals("MANUAL", root.path("document_format").asText());
        assertEquals("LP_INTRODUCE_GOODS", root.path("type").asText());
        assertEquals(signature, root.path("signature").asText());
        assertTrue(root.has("product_group") && root.get("product_group").isNull(), "product_group should be null");

        // 8) product_document = base64(JSON(doc))
        String productDocB64 = root.path("product_document").asText();
        String decodedJson = new String(Base64.getDecoder().decode(productDocB64), StandardCharsets.UTF_8);

        JsonNode expected = MAPPER.valueToTree(doc);
        JsonNode actual = MAPPER.readTree(decodedJson);
        assertEquals(expected, actual, "product_document must be base64(JSON of the POJO we passed)");
    }
}
