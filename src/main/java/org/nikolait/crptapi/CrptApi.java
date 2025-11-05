package org.nikolait.crptapi;

import com.fasterxml.jackson.annotation.*;
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
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe клиент для API Честного знака (ГИС МТ) с блокирующим ограничением запросов.
 *
 * <p>Реализует «Единый метод создания документов» для сценария«Ввод в оборот товара, произведённого на территории РФ»
 * тип {@code LP_INTRODUCE_GOODS} с открепленной подписью в {@code base64}.
 *
 * <p>Класс спроектирован для удобного расширения и тестирования:
 * <ul>
 *   <li>Использует {@code protected} поля конфигурации, что ослабляет инкапсуляцию,
 *       но упрощает переопределение поведения в наследниках;</li>
 *   <li>Конструктор по заданию {@link #CrptApi(TimeUnit, int)} с дефолтным URL и путём;</li>
 *   <li>Перегруженные конструкторы с возможностью указать {@code baseUri}, {@code createPath};</li>
 *   <li>Возможность внедрить собственный {@link RequestLimiter} и HTTP-клиент через {@link HttpClientAdapter};</li>
 *   <li>Возможность зарегистрировать собственные {@link DocumentConverter}.</li>
 * </ul>
 *
 * <p>Все вспомогательные типы объявлены как вложенные классы.
 */
public class CrptApi {

    /**
     * Формат документа в запросе.
     */
    public enum DocumentFormat {
        MANUAL, //  формат JSON
        XML,
        CSV
    }

    /**
     * Тип документа для ввода в оборот товара, произведённого на территории РФ.
     */
    public enum DocumentType {
        LP_INTRODUCE_GOODS, // для формата MANUAL
        LP_INTRODUCE_GOODS_CSV,
        LP_INTRODUCE_GOODS_XML
    }

    /**
     * Дефолтный базовый URL (песочница ГИС МТ).
     */
    protected static final String DEFAULT_BASE_URI = "https://markirovka.demo.crpt.tech";
    /**
     * Дефолтный путь «Единого метода создания документов».
     */
    protected static final String DEFAULT_CREATE_PATH = "/api/v3/lk/documents/create";

    /**
     * Общий {@link ObjectMapper} по умолчанию.
     */
    protected static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    // --- Константы протокола/заголовков
    protected static final String HDR_AUTH = "Authorization";
    protected static final String HDR_CONTENT_TYPE = "Content-Type";
    protected static final String MIME_JSON = "application/json";

    // --- Прочие константы протокола
    protected static final String BEARER_PREFIX = "Bearer ";
    protected static final String QUERY_PG = "?pg=";

    // --- Конфигурация экземпляра API
    protected final URI baseUri;               // напр., https://ismp.crpt.ru или https://markirovka.sandbox.crptech.ru
    protected final String createPath;         // напр., /api/v3/lk/documents/create
    protected final HttpClientAdapter http;    // адаптер HTTP-клиента
    protected final RequestLimiter limiter;    // стратегия лимитирования запросов

    // --- Реестр конвертеров документов
    protected final Map<String, DocumentConverter> converters = new HashMap<>();
    protected final Object converterMonitor = new Object();
    protected boolean convertersFrozen = false;

    // ================================= КОНСТРУКТОРЫ =================================

    /**
     * Конструктор по условию задания.
     * <p>Использует базовый URL ({@link #DEFAULT_BASE_URI}) и путь {@link #DEFAULT_CREATE_PATH},
     * HTTP-клиент из {@link DefaultHttpClientAdapter} и лимитер {@link SlidingWindowRateLimiter}.
     * <p>Ограничивает {@code requestLimit} запросов за интервал 1 {@code timeUnit}.
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
     * <p>Использует дефолтный путь ({@link #DEFAULT_CREATE_PATH}),
     * HTTP-клиент из {@link DefaultHttpClientAdapter} и лимитер {@link SlidingWindowRateLimiter}.
     * <p>Ограничивает {@code requestLimit} запросов за интервал 1 {@code timeUnit}.
     *
     * @param baseUri      базовый адрес (например, {@code "https://ismp.crpt.ru"})
     * @param timeUnit     единица окна (секунда, минута и т.п.)
     * @param requestLimit макс. число запросов за один интервал (>0)
     * @throws IllegalArgumentException если {@code baseUri} равен {@code null} или пустой,
     *                                  либо {@code requestLimit <= 0}
     * @throws NullPointerException     или {@code timeUnit} равен {@code null}
     */
    public CrptApi(String baseUri, TimeUnit timeUnit, int requestLimit) {
        this(baseUri, DEFAULT_CREATE_PATH,
                new SlidingWindowRateLimiter(toWindowNanos(timeUnit), requestLimit),
                new DefaultHttpClientAdapter());
    }

    /**
     * Конструктор с указанием базового URL и пути.
     * <p>Использует HTTP-клиент из {@link DefaultHttpClientAdapter} и лимитер {@link SlidingWindowRateLimiter}.
     * <p>Ограничивает {@code requestLimit} запросов за интервал 1 {@code timeUnit}.
     *
     * @param baseUri      базовый адрес
     * @param createPath   путь метода, если не начинается с {@code /}, он будет добавлен
     * @param timeUnit     единица окна (секунда, минута и т.п.)
     * @param requestLimit макс. число запросов за один интервал (>0)
     * @throws IllegalArgumentException если {@code baseUri} или {@code createPath} равны {@code null} или пусты,
     *                                  либо {@code requestLimit <= 0}
     * @throws NullPointerException     если {@code timeUnit} равен {@code null}
     */
    public CrptApi(String baseUri, String createPath, TimeUnit timeUnit, int requestLimit) {
        this(baseUri, createPath,
                new SlidingWindowRateLimiter(toWindowNanos(timeUnit), requestLimit),
                new DefaultHttpClientAdapter());
    }

    /**
     * Конструктор с указанием базового URL, пути и собственного {@link HttpClientAdapter}
     * <p>Использует лимитер {@link SlidingWindowRateLimiter}.
     * <p>Ограничивает {@code requestLimit} запросов за интервал 1 {@code timeUnit}.
     *
     * @param baseUri      базовый адрес
     * @param createPath   путь метода, если не начинается с {@code /}, он будет добавлен
     * @param timeUnit     единица окна
     * @param requestLimit макс. число запросов за один интервал (>0)
     * @param httpAdapter  HTTP-адаптер
     * @throws IllegalArgumentException если {@code baseUri} или {@code createPath} равны {@code null} или пусты,
     *                                  либо {@code requestLimit <= 0}
     * @throws NullPointerException     если {@code timeUnit} или {@code httpAdapter} равен {@code null}
     */
    public CrptApi(String baseUri, String createPath, TimeUnit timeUnit,
                   int requestLimit, HttpClientAdapter httpAdapter) {
        this(baseUri, createPath,
                new SlidingWindowRateLimiter(toWindowNanos(timeUnit), requestLimit),
                httpAdapter);
    }

    /**
     * Конструктор с возможностью указать размер окна в единицах времени.
     * <p>Использует базовый URL ({@link #DEFAULT_BASE_URI}) и путь {@link #DEFAULT_CREATE_PATH},
     * HTTP-клиент из {@link DefaultHttpClientAdapter} и лимитер {@link SlidingWindowRateLimiter}.
     * <p>Ограничивает {@code requestLimit} запросов за интервал {@code windowAmount × timeUnit}.
     *
     * @param windowAmount число единиц времени в окне (>0)
     * @param timeUnit     единица окна (секунда, минута и т.п.)
     * @param requestLimit макс. число запросов за одно окно (>0)
     * @throws IllegalArgumentException если {@code windowAmount <= 0} или {@code requestLimit <= 0}
     * @throws NullPointerException     если {@code timeUnit} равен {@code null}
     */
    public CrptApi(long windowAmount, TimeUnit timeUnit, int requestLimit) {
        this(DEFAULT_BASE_URI, DEFAULT_CREATE_PATH,
                new SlidingWindowRateLimiter(toWindowNanos(windowAmount, timeUnit), requestLimit),
                new DefaultHttpClientAdapter());
    }

    /**
     * Конструктор с указанием базового URL и размер окна в единицах времени.
     * <p>Использует дефолтный путь {@link #DEFAULT_CREATE_PATH},
     * HTTP-клиент из {@link DefaultHttpClientAdapter} и лимитер {@link SlidingWindowRateLimiter}.
     * <p>Ограничивает {@code requestLimit} запросов за интервал {@code windowAmount × timeUnit}.
     *
     * @param baseUri      базовый адрес
     * @param windowAmount число единиц времени в окне (>0)
     * @param timeUnit     единица окна
     * @param requestLimit макс. число запросов за одно окно (>0)
     * @throws IllegalArgumentException если {@code baseUri} равен {@code null} или пуст,
     *                                  либо {@code windowAmount <= 0} или {@code requestLimit <= 0}
     * @throws NullPointerException     если {@code timeUnit} равен {@code null}
     */
    public CrptApi(String baseUri, long windowAmount, TimeUnit timeUnit, int requestLimit) {
        this(baseUri, DEFAULT_CREATE_PATH,
                new SlidingWindowRateLimiter(toWindowNanos(windowAmount, timeUnit), requestLimit),
                new DefaultHttpClientAdapter());
    }

    /**
     * Конструктор с указанием базового URL, пути и размер окна в единицах времени.
     * <p>Использует HTTP-клиент из {@link DefaultHttpClientAdapter} и лимитер {@link SlidingWindowRateLimiter}.
     * <p>Ограничивает {@code requestLimit} запросов за интервал {@code windowAmount × timeUnit}.
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
     * Конструктор с указанием базового URL, пути метода, количества единиц окна и
     * собственной реализации {@link HttpClientAdapter}.
     * <p>Даёт полный контроль над поведением клиента.
     *
     * @param baseUri      базовый адрес
     * @param createPath   путь метода, если не начинается с {@code /}, он будет добавлен
     * @param windowAmount число единиц времени в окне (>0)
     * @param timeUnit     единица окна
     * @param requestLimit макс. число запросов за одно окно (>0)
     * @param httpAdapter  адаптер {@link HttpClientAdapter}
     * @throws IllegalArgumentException если {@code baseUri} или {@code createPath} равны {@code null} или пусты,
     *                                  либо {@code windowAmount <= 0} или {@code requestLimit <= 0}
     * @throws NullPointerException     если {@code timeUnit} равен {@code null}
     */

    public CrptApi(String baseUri, String createPath, long windowAmount, TimeUnit timeUnit,
                   int requestLimit, HttpClientAdapter httpAdapter) {
        this(baseUri, createPath,
                new SlidingWindowRateLimiter(toWindowNanos(windowAmount, timeUnit), requestLimit),
                httpAdapter);
    }

    /**
     * Конструктор с указанием собственного лимитера.
     * <p>Использует базовый URL ({@link #DEFAULT_BASE_URI}) и путь {@link #DEFAULT_CREATE_PATH},
     * HTTP-клиент из {@link DefaultHttpClientAdapter}.
     * <p>Поваляет ограничить количество запросов через собственную реализацию {@link RequestLimiter}.
     *
     * @param limiter стратегия лимитирования
     * @throws NullPointerException если {@code limiter} равен {@code null}
     */
    public CrptApi(RequestLimiter limiter) {
        this(DEFAULT_BASE_URI, DEFAULT_CREATE_PATH, limiter, new DefaultHttpClientAdapter());
    }

    /**
     * Конструктор с указанием базового URL и собственного лимитера.
     * <p>Использует дефолтный путь {@link #DEFAULT_CREATE_PATH} и HTTP-клиент из {@link DefaultHttpClientAdapter}.
     * <p>Поваляет ограничить количество запросов через собственную реализацию {@link RequestLimiter}.
     *
     * @param baseUri базовый адрес
     * @param limiter стратегия лимитирования
     */
    public CrptApi(String baseUri, RequestLimiter limiter) {
        this(baseUri, DEFAULT_CREATE_PATH, limiter, new DefaultHttpClientAdapter());
    }

    /**
     * Конструктор с указанием базового URL, пути и собственного лимитера.
     * <p>Использует HTTP-клиент из {@link DefaultHttpClientAdapter}.
     * <p>Поваляет ограничить количество запросов через собственную реализацию {@link RequestLimiter}.
     *
     * @param baseUri    базовый адрес
     * @param createPath путь метода, если не начинается с {@code /}, он будет добавлен
     * @param limiter    стратегия лимитирования
     * @throws IllegalArgumentException если {@code baseUri} или {@code createPath} равны {@code null} или пусты
     * @throws NullPointerException     если {@code limiter} равен {@code null}
     */
    public CrptApi(String baseUri, String createPath, RequestLimiter limiter) {
        this(baseUri, createPath, limiter, new DefaultHttpClientAdapter());
    }


    /**
     * Конструктор с максимальным контролем конфигурации.
     *
     * @param baseUri     базовый адрес (не пустой)
     * @param createPath  путь метода (нормализуется к виду с ведущим '/')
     * @param limiter     стратегия лимитирования (не null)
     * @param httpAdapter HTTP-адаптер (не null)
     * @throws IllegalArgumentException если {@code baseUri} или {@code createPath} равны {@code null} или пусты
     * @throws NullPointerException     если {@code limiter} или {@code httpAdapter} равен {@code null}
     */
    public CrptApi(String baseUri, String createPath, RequestLimiter limiter, HttpClientAdapter httpAdapter) {
        this.baseUri = URI.create(requireNotBlank(baseUri, "baseUri"));
        this.createPath = normalizePath(requireNotBlank(createPath, "createPath"));
        this.http = requireNotNull(httpAdapter, "httpAdapter");
        this.limiter = requireNotNull(limiter, "limiter");
        registerConverter(DocumentFormat.MANUAL.name(), new JsonDocumentConverter(MAPPER));
    }

    // ============================== ПУБЛИЧНЫЕ МЕТОДЫ ==============================

    /**
     * Специализированный метод для «Ввод в оборот. Производство РФ»
     * (тип документа {@link DocumentType#LP_INTRODUCE_GOODS}, формат {@link DocumentFormat#MANUAL}).
     *
     * @param accessToken             токен доступа без префикса {@code "Bearer "}
     * @param productGroup            код товарной группы
     * @param document                модель документа
     * @param detachedSignatureBase64 откреплённая подпись в {@code base64}
     * @param passPgInQuery           {@code true} — передавать pg в query (?pg=...), {@code false} — в теле запроса
     * @return HTTP-ответ сервера
     * @throws IOException              при ошибках ввода-вывода во время отправки
     * @throws InterruptedException     если поток прерван во время ожидания лимитера или отправки
     * @throws IllegalArgumentException при валидации или пустых параметрах
     * @throws NullPointerException     если документ или обязательные поля равны null
     * @throws IllegalStateException    при ошибке сериализации
     */
    public HttpResponse<String> createLpIntroduceGoods(
            String accessToken,
            String productGroup,
            LpIntroduceGoodsDocument document,
            String detachedSignatureBase64,
            boolean passPgInQuery
    ) throws IOException, InterruptedException {
        validate(document);
        return createDocument(
                accessToken,
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
            String accessToken,
            String productGroup,
            LpIntroduceGoodsDocument document,
            String detachedSignatureBase64
    ) throws IOException, InterruptedException {
        return createLpIntroduceGoods(accessToken, productGroup, document, detachedSignatureBase64, true);
    }

    // ============================== ЗАЩИЩЁННЫЕ МЕТОДЫ ==============================

    /**
     * Защищённый универсальный "Единый метод создания документов",
     * чтобы публичный API соответствовал ТЗ, но реализация была готова к расширению.
     *
     * @param accessToken             токен доступа без префикса {@code "Bearer "}
     * @param productGroup            код товарной группы
     * @param document                модель документа, которая будет сериализована и закодирована {@code base64}
     * @param detachedSignatureBase64 откреплённая подпись в {@code base64}
     * @param type                    тип документа, например, "LP_INTRODUCE_GOODS"
     * @param format                  формат документа в теле (String, чтобы допускать будущие значения)
     * @param passPgInQuery           если true — передаём ?pg=...; иначе — в поле body.product_group
     * @throws IOException              при ошибках ввода-вывода во время отправки
     * @throws InterruptedException     если поток прерван во время ожидания лимитера или отправки
     * @throws IllegalArgumentException при валидации или пустых параметрах
     * @throws NullPointerException     если документ или обязательные поля равны null
     * @throws IllegalStateException    при ошибке сериализации
     */
    protected HttpResponse<String> createDocument(
            String accessToken,
            String productGroup,
            CrptDocument document,
            String detachedSignatureBase64,
            String type,
            String format,
            boolean passPgInQuery
    ) throws IOException, InterruptedException {

        requireNotBlank(accessToken, "accessToken");
        requireNotBlank(productGroup, "productGroup");
        requireNotNull(document, "document");
        requireNotBlank(detachedSignatureBase64, "detachedSignatureBase64");
        requireNotBlank(type, "type");
        requireNotBlank(format, "format");

        limiter.acquire();
        freezeConvertersIfNeeded();

        DocumentConverter converter = converters.get(format);
        String productDocumentBase64 = Base64.getEncoder().encodeToString(converter.toBytes(document));

        Map<String, Object> body = new HashMap<>();
        body.put("document_format", format);
        body.put("product_document", productDocumentBase64);
        body.put("type", type);
        body.put("signature", detachedSignatureBase64);

        String createdPath = this.createPath;
        if (!passPgInQuery) {
            body.put("product_group", productGroup);
        } else {
            createdPath = this.createPath + QUERY_PG + UrlUtils.encode(productGroup);
        }

        URI uri = baseUri.resolve(createdPath);

        HttpRequest req = HttpRequest.newBuilder(uri)
                .header(HDR_AUTH, BEARER_PREFIX + accessToken)
                .header(HDR_CONTENT_TYPE, MIME_JSON)
                .POST(HttpRequest.BodyPublishers.ofString(JsonUtils.toJson(body)))
                .build();

        return http.send(req);
    }

    /**
     * Регистрирует или перезаписывает конвертер формата до заморозки реестра.
     * После 1-го вызова метода {@link #createDocument(String, String, CrptDocument, String, String, String, boolean)}
     * реестр конвертеров замораживается, дальнейшие попытки регистрации приведут к {@link IllegalStateException}.
     *
     * @param format    идентификатор формата (регистр игнорируется)
     * @param converter реализация конвертера (не {@code null})
     * @throws IllegalArgumentException если {@code format} равен {@code null} или пустой
     * @throws NullPointerException     если {@code converter} равен {@code null}
     * @throws IllegalStateException    если реестр уже заморожен
     */
    public void registerConverter(String format, DocumentConverter converter) {
        final String key = normalizeFormatKey(format);
        requireNotNull(converter, "converter");

        if (convertersFrozen) throw new IllegalStateException("Converters are frozen");
        synchronized (converterMonitor) {
            if (convertersFrozen) throw new IllegalStateException("Converters are frozen");
            converters.put(key, converter);
        }
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
        if (isBlank(value)) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    protected static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    protected static String normalizeFormatKey(String format) {
        return requireNotBlank(format, "format").toUpperCase(Locale.ROOT);
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

    /**
     * Замораживает реестр конвертеров, предотвращая дальнейшие изменения.
     */
    protected void freezeConvertersIfNeeded() {
        if (!convertersFrozen) {
            synchronized (converterMonitor) {
                convertersFrozen = true;
            }
        }
    }

    // ============================== КОНВЕРТЕРЫ ==============================

    /**
     * Конвертер для преобразования Java-объектов в массив байт нужного формата.
     * <p>При ошибках сериализации допускается выбрасывать {@link RuntimeException}.
     */
    @FunctionalInterface
    public interface DocumentConverter {
        byte[] toBytes(CrptDocument document);
    }

    /**
     * Сериализует Java объект документа в JSON массив байт.
     */
    public static final class JsonDocumentConverter implements DocumentConverter {

        private final ObjectMapper mapper;

        public JsonDocumentConverter(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        @Override
        public byte[] toBytes(CrptDocument document) {
            requireNotNull(document, "document");
            try {
                return mapper.writeValueAsBytes(document);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("JSON serialization failed", e);
            }
        }
    }

    // ============================== ЛИМИТЕРЫ ==============================

    /**
     * Минимальный интерфейс лимитера запросов (локальный или распределённый).
     */
    @FunctionalInterface
    public interface RequestLimiter {
        void acquire() throws InterruptedException;
    }

    /**
     * Блокирующий лимитер со скользящим окном: не более {@code limit} запросов за интервал {@code windowNanos}.
     * Реализация без фоновых потоков: ReentrantLock + Condition.
     */
    public static class SlidingWindowRateLimiter implements RequestLimiter {
        private final long windowNanos;
        private final int limit;

        private final Lock lock;
        private final Condition slotFreed;

        // Кольцевой буфер временных меток запросов (наносекунды), ёмкость ровно limit
        private final long[] buffer;
        private int head = 0; // индекс старейшей метки
        private int tail = 0; // индекс для следующей записи
        private int size = 0; // текущее число меток

        public SlidingWindowRateLimiter(long windowNanos, int limit) {
            this(windowNanos, limit, true);
        }

        public SlidingWindowRateLimiter(long windowNanos, int limit, boolean fairLock) {
            this.windowNanos = requirePositive(windowNanos, "windowNanos");
            this.limit = requirePositive(limit, "limit");
            this.buffer = new long[this.limit];
            this.lock = new ReentrantLock(fairLock);
            this.slotFreed = this.lock.newCondition();
        }

        @Override
        public void acquire() throws InterruptedException {
            lock.lock();
            try {
                long now = nowNanos();
                int removed = pruneExpired(now);

                while (isFull()) {
                    now = waitForNextExpiry(now);
                    removed += pruneExpired(now);
                }

                append(now);
                signalOnRemoved(removed);
            } finally {
                lock.unlock();
            }
        }

        // Удаляет из головы буфера все отметки, вышедшие за окно [now - windowNanos, now].
        private int pruneExpired(long now) {
            int removed = 0;
            while (size > 0 && now - buffer[head] >= windowNanos) {
                head = (head + 1 == limit) ? 0 : head + 1;
                size--;
                removed++;
            }
            return removed;
        }

        private boolean isFull() {
            return size >= limit;
        }

        private long waitForNextExpiry(long now) throws InterruptedException {
            long deadline = oldestDeadlineNanos();
            long waitNanos = deadline - now;
            if (waitNanos > 0L) {
                long remaining = slotFreed.awaitNanos(waitNanos);
                return (remaining <= 0L) ? deadline : nowNanos();
            }
            return nowNanos();
        }

        private long oldestDeadlineNanos() {
            return buffer[head] + windowNanos;
        }

        private void append(long t) {
            buffer[tail] = t;
            tail = (tail + 1 == limit) ? 0 : tail + 1;
            size++;
        }

        // Нотификация ожидающих потоков: если удалили >1 — разбудим всех, если 1 — одного.
        // Если 0 — молчим, чтобы избежать лишних пробуждений.
        private void signalOnRemoved(int removed) {
            if (removed > 1) {
                slotFreed.signalAll();
            } else if (removed == 1) {
                slotFreed.signal();
            }
        }

        private long nowNanos() {
            return System.nanoTime();
        }
    }

    /**
     * Лимитер с фиксированным окном: не более {@code limit} запросов за интервал {@code windowNanos}.
     * Реализация без фоновых потоков: Semaphore + ленивое продвижение окна при вызовах {@link #acquire()}.
     * <p>При превышении лимита вызов {@link #acquire()} блокируется до начала следующего окна.</p>
     */
    public static class FixedWindowSemaphoreLimiter implements RequestLimiter {
        private final long windowNanos;
        private final int limit;

        private final Semaphore semaphore;
        private final Lock lock;
        private final Condition windowAdvanced;

        private long windowStartNanos;

        public FixedWindowSemaphoreLimiter(long windowNanos, int limit) {
            this(windowNanos, limit, true, true);
        }

        public FixedWindowSemaphoreLimiter(long windowNanos, int limit, boolean fairSemaphore, boolean fairLock) {
            this.windowNanos = requirePositive(windowNanos, "windowNanos");
            this.limit = requirePositive(limit, "limit");
            this.semaphore = new Semaphore(limit, fairSemaphore);
            this.lock = new ReentrantLock(fairLock);
            this.windowAdvanced = this.lock.newCondition();
            this.windowStartNanos = System.nanoTime();
        }

        @Override
        public void acquire() throws InterruptedException {
            if (semaphore.tryAcquire()) return;

            lock.lock();
            try {
                for (; ; ) {
                    long now = System.nanoTime();

                    if (now - windowStartNanos >= windowNanos) {
                        windowStartNanos = now;
                        semaphore.drainPermits();
                        semaphore.release(limit);
                        windowAdvanced.signalAll();
                    }

                    if (semaphore.tryAcquire()) return;

                    long waitNanos = windowStartNanos + windowNanos - now;
                    if (waitNanos > 0L) {
                        long ignored = windowAdvanced.awaitNanos(waitNanos);
                    }
                }
            } finally {
                lock.unlock();
            }
        }
    }

    // ============================== HTTP АДАПТЕР ==============================

    /**
     * Интерфейс отправки запросов, не ограниченный конкретным HTTP-клиентом.
     */
    @FunctionalInterface
    public interface HttpClientAdapter {
        HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException;
    }

    /**
     * Реализация на {@link java.net.http.HttpClient} (Java 11+).
     */
    public static class DefaultHttpClientAdapter implements HttpClientAdapter {

        private static final int DEFAULT_CONNECT_TIMEOUT_SECS = 5;
        private static final int DEFAULT_REQUEST_TIMEOUT_SECS = 30;

        private final HttpClient client = HttpClient.newBuilder()
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
    protected static final class JsonUtils {

        static String toJson(Object o) {
            try {
                return MAPPER.writeValueAsString(o);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("JSON serialization failed", e);
            }
        }
    }

    /**
     * Утилита URL.
     */
    protected static final class UrlUtils {
        static String encode(String s) {
            return URLEncoder.encode(s, StandardCharsets.UTF_8);
        }
    }

    // ============================== МОДЕЛИ ДАННЫХ (РАСШИРЯЕМЫЕ) ==============================

    /**
     * Маркерный интерфейс для всех моделей документов Честного знака,
     */
    public interface CrptDocument {
    }

    /**
     * Абстрактная базовая модель документа Честного знака (ГИС МТ).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static abstract class AbstractCrptDocument implements CrptDocument {

        private String doc_id;
        private String doc_status;
        private String doc_type;
        private Boolean importRequest;
        private String owner_inn;
        private String participant_inn;
        private String producer_inn;
        private String production_date;   // "yyyy-MM-dd"
        private String production_type;   // OWN_PRODUCTION | CONTRACT_PRODUCTION
        private String reg_date;          // "yyyy-MM-dd"
        private String reg_number;

        @JsonIgnore
        private final Map<String, Object> extra = createExtraMap();

        protected Map<String, Object> createExtraMap() {
            return new HashMap<>();
        }

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

        // --- геттеры/сеттеры общих полей ---

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
    }

    /**
     * Документ «Ввод в оборот товара, произведённого на территории РФ», тип LP_INTRODUCE_GOODS, формат MANUAL.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LpIntroduceGoodsDocument extends AbstractCrptDocument {

        private Description description;
        private List<Product> products;

        public Description getDescription() {
            return description;
        }

        public void setDescription(Description description) {
            this.description = description;
        }

        public List<Product> getProducts() {
            return products;
        }

        public void setProducts(List<Product> products) {
            this.products = products;
        }

        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class Description {
            private String participantInn;

            @JsonIgnore
            private final Map<String, Object> extra = createExtraMap();

            protected Map<String, Object> createExtraMap() {
                return new HashMap<>();
            }

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

        @JsonInclude(JsonInclude.Include.NON_NULL)
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
            private final Map<String, Object> extra = createExtraMap();

            protected Map<String, Object> createExtraMap() {
                return new HashMap<>();
            }

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
                Description d = document.getDescription();
                if (d == null) d = new Description();
                d.setParticipantInn(participantInn);
                document.setDescription(d);
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
