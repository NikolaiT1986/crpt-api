package org.nikolait.crptapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CrptApiModelSerializationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    @Test
    @DisplayName("Сериализация минимального документа совпадает с эталоном из resources")
    void serialize_minimal_matches_fixture() throws Exception {
        CrptApi.LpIntroduceGoodsDocument document = CrptApi.LpIntroduceGoodsDocument.builder()
                .docId("doc-min")
                .docStatus("DRAFT")
                .docType("LP_INTRODUCE_GOODS")
                .ownerInn("7700000000")
                .participantInn("7700000000")
                .producerInn("7700000000")
                .productionDate("2025-01-01")
                .productionType("OWN_PRODUCTION")
                .descriptionParticipantInn("7700000000")
                .products(List.of(createProduct()))
                .build();

        JsonNode actual = toJsonTree(document);
        JsonNode expected = readJson("/docs/lp_introduce_goods_min_expected.json");

        assertEquals(expected, actual, () -> diff(expected, actual));
    }

    @Test
    @DisplayName("Сериализация документа с extra на всех уровнях совпадает с эталоном из resources")
    void serialize_with_extras_matches_fixture() throws Exception {
        CrptApi.LpIntroduceGoodsDocument.Product product = new CrptApi.LpIntroduceGoodsDocument.Product();
        product.setTnved_code("640399");
        product.setUit_code("00000000000000000000000000000000001");
        product.extra("prod_extra", 123);

        CrptApi.LpIntroduceGoodsDocument document = CrptApi.LpIntroduceGoodsDocument.builder()
                .docId("doc-ex")
                .docStatus("DRAFT")
                .docType("LP_INTRODUCE_GOODS")
                .ownerInn("7700000000")
                .participantInn("7700000000")
                .producerInn("7700000000")
                .productionDate("2025-01-02")
                .productionType("OWN_PRODUCTION")
                .descriptionParticipantInn("7700000000")
                .products(List.of(product))
                .extra("top_level_extra", "value")
                .descriptionExtra("desc_extra", true)
                .build();

        JsonNode actual = toJsonTree(document);
        JsonNode expected = readJson("/docs/lp_introduce_goods_min_with_extras_expected.json");

        assertEquals(expected, actual, () -> diff(expected, actual));

        assertEquals("value", actual.get("top_level_extra").asText());
        assertTrue(actual.get("description").get("desc_extra").asBoolean());
        assertEquals(123, actual.get("products").get(0).get("prod_extra").asInt());
    }

    @Test
    @DisplayName("NULL-поля не сериализуются (NON_NULL)")
    void nonNull_fields_are_omitted() throws Exception {
        CrptApi.LpIntroduceGoodsDocument document = CrptApi.LpIntroduceGoodsDocument.builder()
                .docId("doc-nn")
                .docStatus("DRAFT")
                .docType("LP_INTRODUCE_GOODS")
                .ownerInn("7700000000")
                .participantInn("7700000000")
                .producerInn("7700000000")
                .productionDate("2025-01-03")
                .productionType("OWN_PRODUCTION")
                .descriptionParticipantInn("7700000000")
                .products(List.of(createProduct()))
                .build();

        JsonNode tree = toJsonTree(document);

        assertNull(tree.get("importRequest"));
        assertNull(tree.get("reg_date"));
        assertNull(tree.get("reg_number"));
    }

    @Test
    @DisplayName("Round-trip: неизвестные поля попадают в extra и сохраняются при повторной сериализации")
    void roundTrip_with_unknown_fields() throws Exception {
        JsonNode input = readJson("/docs/lp_introduce_goods_roundtrip_in.json");

        CrptApi.LpIntroduceGoodsDocument document =
                MAPPER.treeToValue(input, CrptApi.LpIntroduceGoodsDocument.class);
        JsonNode result = toJsonTree(document);

        assertEquals(input, result, () -> diff(input, result));

        assertEquals(42, document.getExtra().get("unknown_top"));
        assertEquals("x", document.getDescription().getExtra().get("unknown_desc"));
        assertEquals(true, document.getProducts().get(0).getExtra().get("unknown_prod"));
    }


    private static CrptApi.LpIntroduceGoodsDocument.Product createProduct() {
        CrptApi.LpIntroduceGoodsDocument.Product product = new CrptApi.LpIntroduceGoodsDocument.Product();
        product.setTnved_code("640399");
        product.setUit_code("00000000000000000000000000000000001");
        return product;
    }

    private static JsonNode toJsonTree(Object object) throws IOException {
        return MAPPER.readTree(MAPPER.writeValueAsString(object));
    }

    private static JsonNode readJson(String path) throws IOException {
        try (InputStream is = CrptApiModelSerializationTest.class.getResourceAsStream(path)) {
            assertNotNull(is, "Файл ресурса не найден: " + path);
            String text = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            return MAPPER.readTree(text);
        }
    }

    private static String diff(JsonNode expected, JsonNode actual) {
        return "\nExpected:\n" + expected.toPrettyString()
                + "\nActual:\n" + actual.toPrettyString() + "\n";
    }
}
