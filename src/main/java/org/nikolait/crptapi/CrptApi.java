package org.nikolait.crptapi;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe клиент для API Честного знака (ГИС МТ) с блокирующим ограничением запросов.
 *
 * <p>Поддерживает "Единый метод создания документов" {@code /api/v3/lk/documents/create} для сценария
 * «Ввод в оборот товара, произведенного на территории РФ» (тип {@code LP_INTRODUCE_GOODS})
 * с открепленной подписью (base64).
 *
 * <p>Класс спроектирован для удобного тестирования и расширения:
 * <ul>
 *   <li>Конструктор по заданию {@link #CrptApi(TimeUnit, int)} с дефолтным URL и путём;</li>
 *   <li>Перегруженные конструкторы с возможностью указать {@code baseUri}, {@code createPath};</li>
 *   <li>Возможность внедрить собственные {@link RequestLimiter} и {@link HttpClientAdapter};</li>
 *   <li>Ограничение запросов реализовано стратегией лимитера без фоновых потоков.</li>
 * </ul>
 *
 * <p>Все вспомогательные типы объявлены как вложенные классы.
 */
public class CrptApi {

    /**
     * Формат документа в запросе.
     * <p>MANUAL — формат JSON.</p>
     */
    public enum DocumentFormat {
        MANUAL, XML, CSV
    }

    /**
     * Тип документа.
     */
    public enum DocumentType {
        LP_INTRODUCE_GOODS // Ввод в оборот товара, произведённого на территории РФ
    }

    /**
     * Дефолтный базовый URL (песочница ГИС МТ).
     */
    public static final String DEFAULT_BASE_URI = "https://markirovka.demo.crpt.tech";
    /**
     * Дефолтный путь «Единого метода создания документов».
     */
    public static final String DEFAULT_CREATE_PATH = "/api/v3/lk/documents/create";

    // --- Константы протокола/заголовков
    protected static final String HDR_AUTH = "Authorization";
    protected static final String HDR_CONTENT_TYPE = "Content-Type";
    protected static final String MIME_JSON = "application/json";

    // --- Прочие константы протокола
    protected static final String BEARER_PREFIX = "Bearer ";
    protected static final String QUERY_PARAM_PRODUCT_GROUP = "pg";

    protected final URI baseUri;               // напр., https://ismp.crpt.ru или https://markirovka.sandbox.crptech.ru
    protected final String createPath;         // напр., /api/v3/lk/documents/create
    protected final HttpClientAdapter http;    // можно заменить/подменить в тестах
    protected final RequestLimiter limiter;    // стратегия лимитирования запросов

    // ================================= КОНСТРУКТОРЫ =================================

    /**
     * Конструктор по условию задания.
     * <p>Использует базовый URL песочницы ({@link #DEFAULT_BASE_URI}),
     * дефолтный путь {@link #DEFAULT_CREATE_PATH}, стандартный HTTP-клиент
     * и дефолтную реализацию лимитера {@link SlidingWindowRateLimiter}.
     * <p>Ограничение — N запросов за одну единицу {@code timeUnit}.
     *
     * @param timeUnit     единица окна (секунда, минута и т.п.)
     * @param requestLimit макс. число запросов за один интервал (>0)
     * @throws IllegalArgumentException если {@code requestLimit <= 0}
     * @throws NullPointerException     если {@code timeUnit == null}
     */
    public CrptApi(TimeUnit timeUnit, int requestLimit) {
        this(DEFAULT_BASE_URI, DEFAULT_CREATE_PATH,
                new SlidingWindowRateLimiter(toWindowNanos(timeUnit), requestLimit),
                new DefaultHttpClientAdapter());
    }

    /**
     * Конструктор с указанием базового URL.
     * <p>Путь берётся по умолчанию ({@link #DEFAULT_CREATE_PATH}),
     * HTTP-клиент дефолтный, лимитер — {@link SlidingWindowRateLimiter}.
     * <p>Ограничение — N запросов за одну единицу {@code timeUnit}.
     *
     * @param baseUri      базовый адрес (например, {@code "https://ismp.crpt.ru"})
     * @param timeUnit     единица окна (секунда, минута и т.п.)
     * @param requestLimit макс. число запросов за один интервал (>0)
     * @throws IllegalArgumentException если {@code requestLimit <= 0}
     * @throws NullPointerException     если {@code baseUri} или {@code timeUnit} равны {@code null}
     */
    public CrptApi(String baseUri, TimeUnit timeUnit, int requestLimit) {
        this(baseUri, DEFAULT_CREATE_PATH,
                new SlidingWindowRateLimiter(toWindowNanos(timeUnit), requestLimit),
                new DefaultHttpClientAdapter());
    }

    /**
     * Конструктор с указанием базового URL и пути метода.
     * <p>HTTP-клиент дефолтный, лимитер — {@link SlidingWindowRateLimiter}.
     * <p>Ограничение — N запросов за одну единицу {@code timeUnit}.
     *
     * @param baseUri      базовый адрес
     * @param createPath   путь метода, если не начинается с {@code /}, он будет добавлен
     * @param timeUnit     единица окна (секунда, минута и т.п.)
     * @param requestLimit макс. число запросов за один интервал (>0)
     */
    public CrptApi(String baseUri, String createPath, TimeUnit timeUnit, int requestLimit) {
        this(baseUri, createPath,
                new SlidingWindowRateLimiter(toWindowNanos(timeUnit), requestLimit),
                new DefaultHttpClientAdapter());
    }

    /**
     * Конструктор с указанием базового URL, пути метода и собственного HTTP-клиента.
     * <p>Лимитер — {@link SlidingWindowRateLimiter}.
     * <p>Ограничение — N запросов за одну единицу {@code timeUnit}.
     *
     * @param baseUri      базовый адрес
     * @param createPath   путь метода, если не начинается с {@code /}, он будет добавлен
     * @param timeUnit     единица окна
     * @param requestLimit макс. число запросов за один интервал (>0)
     * @param httpAdapter  HTTP-адаптер (можно подменить в тестах)
     */
    public CrptApi(String baseUri, String createPath, TimeUnit timeUnit,
                   int requestLimit, HttpClientAdapter httpAdapter) {
        this(baseUri, createPath,
                new SlidingWindowRateLimiter(toWindowNanos(timeUnit), requestLimit),
                httpAdapter);
    }

    /**
     * Конструктор с возможностью указать количество единиц окна.
     * <p>Например: 5 запросов за 2 секунды — {@code new CrptApi(2, SECONDS, 5)}.
     *
     * @param windowAmount число единиц времени в окне (>0)
     * @param timeUnit     единица окна (секунда, минута и т.п.)
     * @param requestLimit макс. число запросов за одно окно (>0)
     * @throws IllegalArgumentException если {@code windowAmount <= 0} или {@code requestLimit <= 0}
     * @throws NullPointerException     если {@code timeUnit == null}
     */
    public CrptApi(long windowAmount, TimeUnit timeUnit, int requestLimit) {
        this(DEFAULT_BASE_URI, DEFAULT_CREATE_PATH,
                new SlidingWindowRateLimiter(toWindowNanos(windowAmount, timeUnit), requestLimit),
                new DefaultHttpClientAdapter());
    }

    /**
     * Конструктор с указанием базового URL и количества единиц окна.
     * <p>Пример: {@code new CrptApi("https://ismp.crpt.ru", 10, MINUTES, 100)}.
     *
     * @param baseUri      базовый адрес
     * @param windowAmount число единиц времени в окне (>0)
     * @param timeUnit     единица окна
     * @param requestLimit макс. число запросов за одно окно (>0)
     */
    public CrptApi(String baseUri, long windowAmount, TimeUnit timeUnit, int requestLimit) {
        this(baseUri, DEFAULT_CREATE_PATH,
                new SlidingWindowRateLimiter(toWindowNanos(windowAmount, timeUnit), requestLimit),
                new DefaultHttpClientAdapter());
    }

    /**
     * Конструктор с указанием базового URL, пути метода и количества единиц окна.
     * <p>Пример: {@code new CrptApi("https://ismp.crpt.ru", "/api/v3/lk/documents/create", 5, SECONDS, 10)}.
     *
     * @param baseUri      базовый адрес
     * @param createPath   путь метода, если не начинается с {@code /}, он будет добавлен
     * @param windowAmount число единиц времени в окне (>0)
     * @param timeUnit     единица окна
     * @param requestLimit макс. число запросов за одно окно (>0)
     */
    public CrptApi(String baseUri, String createPath, long windowAmount, TimeUnit timeUnit, int requestLimit) {
        this(baseUri, createPath,
                new SlidingWindowRateLimiter(toWindowNanos(windowAmount, timeUnit), requestLimit),
                new DefaultHttpClientAdapter());
    }

    /**
     * Конструктор с указанием базового URL, пути метода, количества единиц окна и собственного HTTP-клиента.
     * <p>Даёт полный контроль над поведением клиента.
     *
     * @param baseUri      базовый адрес
     * @param createPath   путь метода, если не начинается с {@code /}, он будет добавлен
     * @param windowAmount число единиц времени в окне (>0)
     * @param timeUnit     единица окна
     * @param requestLimit макс. число запросов за одно окно (>0)
     * @param httpAdapter  HTTP-адаптер (можно подменить в тестах)
     */
    public CrptApi(String baseUri, String createPath, long windowAmount, TimeUnit timeUnit,
                   int requestLimit, HttpClientAdapter httpAdapter) {
        this(baseUri, createPath,
                new SlidingWindowRateLimiter(toWindowNanos(windowAmount, timeUnit), requestLimit),
                httpAdapter);
    }

    /**
     * Конструктор с указанием собственного лимитера.
     * <p>Базовый URL берётся по умолчанию ({@link #DEFAULT_BASE_URI}),
     * путь — по умолчанию ({@link #DEFAULT_CREATE_PATH}),
     * HTTP-клиент — дефолтный ({@link DefaultHttpClientAdapter}).
     *
     * @param limiter стратегия лимитирования
     */
    public CrptApi(RequestLimiter limiter) {
        this(DEFAULT_BASE_URI, DEFAULT_CREATE_PATH, limiter, new DefaultHttpClientAdapter());
    }

    /**
     * Конструктор с указанием базового URL и собственного лимитера.
     * <p>Путь берётся по умолчанию ({@link #DEFAULT_CREATE_PATH}),
     * HTTP-клиент — дефолтный ({@link DefaultHttpClientAdapter}).
     *
     * @param baseUri базовый адрес
     * @param limiter стратегия лимитирования
     */
    public CrptApi(String baseUri, RequestLimiter limiter) {
        this(baseUri, DEFAULT_CREATE_PATH, limiter, new DefaultHttpClientAdapter());
    }

    /**
     * Конструктор с указанием базового URL, пути метода и собственного лимитера.
     * <p>HTTP-клиент — дефолтный ({@link DefaultHttpClientAdapter}).
     *
     * @param baseUri    базовый адрес
     * @param createPath путь метода, если не начинается с {@code /}, он будет добавлен
     * @param limiter    стратегия лимитирования
     */
    public CrptApi(String baseUri, String createPath, RequestLimiter limiter) {
        this(baseUri, createPath, limiter, new DefaultHttpClientAdapter());
    }


    /**
     * «Максимальный контроль»: инжекция собственного лимитера и HTTP-клиента.
     * <p>Позволяет, например, разделить одну квоту между несколькими клиентами,
     * передав общий {@link RequestLimiter}, либо использовать распределённый лимитер.
     *
     * @param baseUri     базовый адрес
     * @param createPath  путь метода, если не начинается с {@code /}, он будет добавлен
     * @param limiter     стратегия лимитирования
     * @param httpAdapter HTTP-адаптер
     */
    public CrptApi(String baseUri, String createPath, RequestLimiter limiter, HttpClientAdapter httpAdapter) {
        requireNotBlank(baseUri, "baseUri");
        requireNotBlank(createPath, "createPath");
        requireNotNull(limiter, "limiter");
        requireNotNull(httpAdapter, "httpAdapter");

        this.baseUri = URI.create(baseUri);
        this.createPath = normalizePath(createPath);
        this.http = httpAdapter;
        this.limiter = limiter;
    }

    // ============================== ПУБЛИЧНЫЕ МЕТОДЫ ==============================

    /**
     * Специализированный метод для «Ввод в оборот. Производство РФ»
     * (тип документа {@code LP_INTRODUCE_GOODS}, формат {@code MANUAL}).
     *
     * @param bearerToken             без префикса "Bearer "
     * @param productGroup            код товарной группы
     * @param document                модель {@link LpIntroduceGoodsDocument}
     * @param detachedSignatureBase64 откреплённая подпись (base64) над исходным JSON документа
     * @param passPgInQuery           true — передавать pg в query (?pg=...), false — в теле запроса
     */
    public HttpResponse<String> createLpIntroduceGoods(
            String bearerToken,
            String productGroup,
            LpIntroduceGoodsDocument document,
            String detachedSignatureBase64,
            boolean passPgInQuery
    ) throws IOException, InterruptedException {
        validate(document);
        return createDocument(
                bearerToken,
                productGroup,
                document,
                detachedSignatureBase64,
                DocumentType.LP_INTRODUCE_GOODS.name(),
                DocumentFormat.MANUAL.name(),
                passPgInQuery
        );
    }

    /**
     * Упрощённая версия {@link #createLpIntroduceGoods(String, String, LpIntroduceGoodsDocument, String, boolean)},
     * где параметр {@code passPgInQuery} по умолчанию равен {@code true}.
     */
    public HttpResponse<String> createLpIntroduceGoods(
            String bearerToken,
            String productGroup,
            LpIntroduceGoodsDocument document,
            String detachedSignatureBase64
    ) throws IOException, InterruptedException {
        return createLpIntroduceGoods(bearerToken, productGroup, document, detachedSignatureBase64, true);
    }


    // ============================== ЗАЩИЩЁННЫЕ МЕТОДЫ ==============================

    /**
     * Защищённый универсальный "Единый метод создания документов",
     * чтобы публичный API соответствовал ТЗ, но реализация была готова к расширению.
     *
     * @param bearerToken             без префикса "Bearer "
     * @param productGroup            код товарной группы
     * @param document                модель документа (будет сериализована и закодирована в Base64)
     * @param detachedSignatureBase64 откреплённая подпись над исходным JSON документа, Base64
     * @param type                    тип документа (например, "LP_INTRODUCE_GOODS")
     * @param format                  формат документа в теле (String, чтобы допускать будущие значения)
     * @param passPgInQuery           если true — передаём ?pg=...; иначе — в поле body.product_group
     */
    protected HttpResponse<String> createDocument(
            String bearerToken,
            String productGroup,
            Object document,
            String detachedSignatureBase64,
            String type,
            String format,
            boolean passPgInQuery
    ) throws IOException, InterruptedException {

        requireNotBlank(bearerToken, "bearerToken");
        requireNotBlank(productGroup, "productGroup");
        requireNotNull(document, "document");
        requireNotBlank(detachedSignatureBase64, "detachedSignatureBase64");
        requireNotBlank(type, "type");
        requireNotBlank(format, "format");

        limiter.acquire();

        // JSON -> base64
        String documentJson = Json.toJson(document);
        String productDocumentBase64 = Base64.getEncoder()
                .encodeToString(documentJson.getBytes(StandardCharsets.UTF_8));

        CreateRequestBody body = new CreateRequestBody();
        body.document_format = format;
        body.product_document = productDocumentBase64;
        body.type = type;
        body.signature = detachedSignatureBase64;
        body.product_group = passPgInQuery ? null : productGroup;

        String pathWithQuery = passPgInQuery
                ? this.createPath + "?" + QUERY_PARAM_PRODUCT_GROUP + "=" + Urls.encode(productGroup)
                : this.createPath;

        URI uri = baseUri.resolve(pathWithQuery);

        HttpRequest req = HttpRequest.newBuilder(uri)
                .header(HDR_AUTH, BEARER_PREFIX + bearerToken)
                .header(HDR_CONTENT_TYPE, MIME_JSON)
                .POST(HttpRequest.BodyPublishers.ofString(Json.toJson(body)))
                .build();

        return http.send(req);
    }

    // ============================== ХЕЛПЕРЫ ==============================

    protected static String normalizePath(String path) {
        requireNotBlank(path, "createPath");
        return path.startsWith("/") ? path : "/" + path;
    }

    protected static long toWindowNanos(TimeUnit unit) {
        return toWindowNanos(1L, requireNotNull(unit, "timeUnit"));
    }

    protected static long toWindowNanos(long amount, TimeUnit unit) {
        requirePositive(amount, "windowAmount");
        requireNotNull(unit, "windowUnit");
        return unit.toNanos(amount);
    }

    protected static <T> T requireNotNull(T value, String name) {
        return Objects.requireNonNull(value, name + " must not be null");
    }

    protected static <T extends Number> T requirePositive(T value, String name) {
        if (value.longValue() <= 0) throw new IllegalArgumentException(name + " must be > 0");
        return value;
    }

    protected static String requireNotBlank(String value, String name) {
        if (value == null || isBlank(value)) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    protected static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    // Проверяет обязательные поля LP_INTRODUCE_GOODS и выбрасывает IllegalArgumentException, если найдены ошибки.
    protected static void validate(LpIntroduceGoodsDocument document) {
        List<String> errors = new ArrayList<>();
        if (document == null) {
            throw new IllegalArgumentException("document must not be null");
        }

        if (document.getDescription() == null
                || isBlank(document.getDescription().getParticipantInn())) {
            errors.add("description.participantInn is required");
        }

        if (isBlank(document.getDoc_id())) errors.add("doc_id is required");
        if (isBlank(document.getDoc_status())) errors.add("doc_status is required");
        if (isBlank(document.getDoc_type())) errors.add("doc_type is required");

        if (isBlank(document.getOwner_inn())) errors.add("owner_inn is required");
        if (isBlank(document.getParticipant_inn())) errors.add("participant_inn is required");
        if (isBlank(document.getProducer_inn())) errors.add("producer_inn is required");

        if (isBlank(document.getProduction_date())) errors.add("production_date is required");
        if (isBlank(document.getProduction_type())) errors.add("production_type is required");

        List<LpIntroduceGoodsDocument.Product> products = document.getProducts();
        if (products == null || products.isEmpty()) {
            errors.add("products must not be empty");
        } else {
            for (int i = 0; i < products.size(); i++) {
                LpIntroduceGoodsDocument.Product product = products.get(i);
                if (isBlank(product.getTnved_code())) {
                    errors.add("products[" + i + "].tnved_code is required");
                }
                boolean hasUit = !isBlank(product.getUit_code());
                boolean hasUitu = !isBlank(product.getUitu_code());
                if (hasUit == hasUitu) {
                    errors.add("products[" + i + "]: exactly one of uit_code or uitu_code is required");
                }
            }
        }

        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", errors));
        }
    }


    // ============================== ЛИМИТЕРЫ ==============================

    /**
     * Минимальный интерфейс лимитера запросов (локальный или распределённый).
     */
    public interface RequestLimiter {
        /**
         * Заблокировать вызывающий поток до появления слота под новый запрос.
         */
        void acquire() throws InterruptedException;
    }

    /**
     * Реализация блокирующего лимитера со скользящим окном (N запросов на окно) — без фоновых потоков.
     * <p>
     * Храним таймстемпы последних запросов. Если их уже >= limit и самое старое ещё внутри окна —
     * ждём до момента, когда окно «съедет» за него. Потокобезопасность: ReentrantLock + Condition.
     */
    public static class SlidingWindowRateLimiter implements RequestLimiter {
        protected final long windowNanos;
        protected final int limit;

        protected final Deque<Long> timestamps = new ArrayDeque<>();
        protected final ReentrantLock lock = new ReentrantLock(true);
        protected final Condition spaceAvailable = lock.newCondition();

        SlidingWindowRateLimiter(long windowNanos, int limit) {
            this.windowNanos = requirePositive(windowNanos, "windowNanos");
            this.limit = requirePositive(limit, "limit");
        }

        @Override
        public void acquire() throws InterruptedException {
            lock.lock();
            try {
                long now = System.nanoTime();
                prune(now);

                while (timestamps.size() >= limit) {
                    long oldest = requireNotNull(timestamps.peekFirst(), "request timestamp");
                    long deadline = oldest + windowNanos;
                    long waitNanos = deadline - now;

                    if (waitNanos > 0L) {
                        long ignored = spaceAvailable.awaitNanos(waitNanos);
                    }
                    now = System.nanoTime();
                    prune(now);
                }

                timestamps.addLast(now);
                spaceAvailable.signalAll();
            } finally {
                lock.unlock();
            }
        }

        /**
         * Удаляет устаревшие таймстемпы (вышедшие за окно).
         * Будит ожидающих только если что-то реально удалили.
         */
        protected void prune(long now) {
            boolean removed = false;
            while (!timestamps.isEmpty()) {
                long oldest = timestamps.peekFirst();
                if (now - oldest >= windowNanos) {
                    timestamps.removeFirst();
                    removed = true;
                } else {
                    break;
                }
            }
            if (removed) {
                spaceAvailable.signalAll();
            }
        }
    }


    // ============================== HTTP АДАПТЕР ==============================

    /**
     * Абстракция HTTP, чтобы было удобно подменять в тестах.
     */
    public interface HttpClientAdapter {
        HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException;
    }

    /**
     * Реализация на {@link java.net.http.HttpClient} (Java 11+).
     */
    public static class DefaultHttpClientAdapter implements HttpClientAdapter {

        protected static final int DEFAULT_CONNECT_TIMEOUT_SECS = 5;
        protected static final int DEFAULT_REQUEST_TIMEOUT_SECS = 30;

        protected final HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(DEFAULT_CONNECT_TIMEOUT_SECS))
                .build();

        @Override
        public HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
            HttpRequest.Builder builder = HttpRequest.newBuilder(request.uri())
                    .method(request.method(), request.bodyPublisher().orElse(HttpRequest.BodyPublishers.noBody()));

            request.headers().map().forEach((name, values) ->
                    values.forEach(value -> builder.header(name, value)));

            if (request.timeout().isEmpty()) {
                builder.timeout(Duration.ofSeconds(DEFAULT_REQUEST_TIMEOUT_SECS));
            }

            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    // ============================== JSON/URL УТИЛИТЫ ==============================

    /**
     * Мини-JSON помощник на Jackson.
     */
    protected static final class Json {
        private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

        static String toJson(Object o) {
            try {
                return MAPPER.writeValueAsString(o);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("JSON serialization failed", e);
            }
        }
    }

    /**
     * Утилиты URL.
     */
    protected static final class Urls {
        static String encode(String s) {
            return URLEncoder.encode(s, StandardCharsets.UTF_8);
        }
    }

    // ============================== МОДЕЛИ ДАННЫХ (РАСШИРЯЕМЫЕ) ==============================

    /**
     * Тело единого метода создания документов.
     */
    protected static class CreateRequestBody {
        public String document_format;   // "MANUAL" | "XML" | "CSV"
        public String product_document;  // base64(JSON/XML/CSV)
        public String product_group;     // можно не указывать в теле, если передаём ?pg=...
        public String signature;         // base64 откреплённая подпись (detached)
        public String type;              // напр., "LP_INTRODUCE_GOODS"
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LpIntroduceGoodsDocument {

        private Description description;
        private String doc_id;
        private String doc_status;
        private String doc_type;
        private Boolean importRequest;
        private String owner_inn;
        private String participant_inn;
        private String producer_inn;
        private String production_date;   // "yyyy-MM-dd"
        private String production_type;   // OWN_PRODUCTION | CONTRACT_PRODUCTION
        private List<Product> products;
        private String reg_date;          // "yyyy-MM-dd"
        private String reg_number;

        @JsonIgnore
        protected final Map<String, Object> extra = new HashMap<>();

        @JsonAnySetter
        public void putExtra(String key, Object value) {
            extra.put(key, value);
        }

        @JsonAnyGetter
        public Map<String, Object> getExtra() {
            return Map.copyOf(extra);
        }

        public void extra(String key, Object value) {
            putExtra(key, value);
        }

        // --- Публичные геттеры/сеттеры (часть внешнего контракта) ---

        public Description getDescription() {
            return description;
        }

        public void setDescription(Description description) {
            this.description = description;
        }

        public String getDoc_id() {
            return doc_id;
        }

        public void setDoc_id(String doc_id) {
            this.doc_id = doc_id;
        }

        public String getDoc_status() {
            return doc_status;
        }

        public void setDoc_status(String doc_status) {
            this.doc_status = doc_status;
        }

        public String getDoc_type() {
            return doc_type;
        }

        public void setDoc_type(String doc_type) {
            this.doc_type = doc_type;
        }

        public Boolean getImportRequest() {
            return importRequest;
        }

        public void setImportRequest(Boolean importRequest) {
            this.importRequest = importRequest;
        }

        public String getOwner_inn() {
            return owner_inn;
        }

        public void setOwner_inn(String owner_inn) {
            this.owner_inn = owner_inn;
        }

        public String getParticipant_inn() {
            return participant_inn;
        }

        public void setParticipant_inn(String participant_inn) {
            this.participant_inn = participant_inn;
        }

        public String getProducer_inn() {
            return producer_inn;
        }

        public void setProducer_inn(String producer_inn) {
            this.producer_inn = producer_inn;
        }

        public String getProduction_date() {
            return production_date;
        }

        public void setProduction_date(String production_date) {
            this.production_date = production_date;
        }

        public String getProduction_type() {
            return production_type;
        }

        public void setProduction_type(String production_type) {
            this.production_type = production_type;
        }

        public List<Product> getProducts() {
            return products;
        }

        public void setProducts(List<Product> products) {
            this.products = products;
        }

        public String getReg_date() {
            return reg_date;
        }

        public void setReg_date(String reg_date) {
            this.reg_date = reg_date;
        }

        public String getReg_number() {
            return reg_number;
        }

        public void setReg_number(String reg_number) {
            this.reg_number = reg_number;
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class Description {
            private String participantInn;

            @JsonIgnore
            protected final Map<String, Object> extra = new HashMap<>();

            @JsonAnySetter
            void putExtra(String key, Object value) {
                extra.put(key, value);
            }

            @JsonAnyGetter
            public Map<String, Object> getExtra() {
                return Map.copyOf(extra);
            }

            public void extra(String key, Object value) {
                putExtra(key, value);
            }

            public String getParticipantInn() {
                return participantInn;
            }

            public void setParticipantInn(String participantInn) {
                this.participantInn = participantInn;
            }
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class Product {
            private String certificate_document;
            private String certificate_document_date; // "yyyy-MM-dd"
            private String certificate_document_number;
            private String owner_inn;
            private String producer_inn;
            private String production_date;           // "yyyy-MM-dd"
            private String tnved_code;
            private String uit_code;
            private String uitu_code;

            @JsonIgnore
            protected final Map<String, Object> extra = new HashMap<>();

            @JsonAnySetter
            public void putExtra(String key, Object value) {
                extra.put(key, value);
            }

            @JsonAnyGetter
            public Map<String, Object> getExtra() {
                return Map.copyOf(extra);
            }

            public void extra(String key, Object value) {
                putExtra(key, value);
            }

            public String getCertificate_document() {
                return certificate_document;
            }

            public void setCertificate_document(String v) {
                this.certificate_document = v;
            }

            public String getCertificate_document_date() {
                return certificate_document_date;
            }

            public void setCertificate_document_date(String v) {
                this.certificate_document_date = v;
            }

            public String getCertificate_document_number() {
                return certificate_document_number;
            }

            public void setCertificate_document_number(String v) {
                this.certificate_document_number = v;
            }

            public String getOwner_inn() {
                return owner_inn;
            }

            public void setOwner_inn(String v) {
                this.owner_inn = v;
            }

            public String getProducer_inn() {
                return producer_inn;
            }

            public void setProducer_inn(String v) {
                this.producer_inn = v;
            }

            public String getProduction_date() {
                return production_date;
            }

            public void setProduction_date(String v) {
                this.production_date = v;
            }

            public String getTnved_code() {
                return tnved_code;
            }

            public void setTnved_code(String v) {
                this.tnved_code = v;
            }

            public String getUit_code() {
                return uit_code;
            }

            public void setUit_code(String v) {
                this.uit_code = v;
            }

            public String getUitu_code() {
                return uitu_code;
            }

            public void setUitu_code(String v) {
                this.uitu_code = v;
            }
        }

        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private final LpIntroduceGoodsDocument document = new LpIntroduceGoodsDocument();

            public Builder descriptionParticipantInn(String participantInn) {
                Description description = document.getDescription();
                if (description == null) description = new Description();
                description.setParticipantInn(participantInn);
                document.setDescription(description);
                return this;
            }

            public Builder docId(String v) {
                document.setDoc_id(v);
                return this;
            }

            public Builder docStatus(String v) {
                document.setDoc_status(v);
                return this;
            }

            public Builder docType(String v) {
                document.setDoc_type(v);
                return this;
            }

            public Builder importRequest(Boolean v) {
                document.setImportRequest(v);
                return this;
            }

            public Builder ownerInn(String v) {
                document.setOwner_inn(v);
                return this;
            }

            public Builder participantInn(String v) {
                document.setParticipant_inn(v);
                return this;
            }

            public Builder producerInn(String v) {
                document.setProducer_inn(v);
                return this;
            }

            public Builder productionDate(String v) {
                document.setProduction_date(v);
                return this;
            }

            public Builder productionType(String v) {
                document.setProduction_type(v);
                return this;
            }

            public Builder products(List<Product> v) {
                document.setProducts(v);
                return this;
            }

            public Builder regDate(String v) {
                document.setReg_date(v);
                return this;
            }

            public Builder regNumber(String v) {
                document.setReg_number(v);
                return this;
            }

            public Builder extra(String key, Object value) {
                document.extra(key, value);
                return this;
            }

            public Builder descriptionExtra(String key, Object value) {
                if (document.getDescription() == null) document.setDescription(new Description());
                document.getDescription().extra(key, value);
                return this;
            }

            public Builder productExtra(int index, String key, Object value) {
                List<Product> list = document.getProducts();
                if (list == null || index < 0 || index >= list.size()) {
                    throw new IndexOutOfBoundsException("No product at index " + index);
                }
                list.get(index).extra(key, value);
                return this;
            }

            public LpIntroduceGoodsDocument build() {
                return document;
            }
        }
    }
}
