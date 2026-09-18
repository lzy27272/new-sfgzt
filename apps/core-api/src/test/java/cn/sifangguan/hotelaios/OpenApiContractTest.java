package cn.sifangguan.hotelaios;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiContractTest {

    @Test
    void dashboardAndIamContractsMatchRuntimeBoundaries() throws IOException {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Map<String, Object> document;
        try (Reader reader = Files.newBufferedReader(openApiPath())) {
            document = map(new Yaml(new SafeConstructor(options)).load(reader));
        }

        Map<String, Object> paths = map(document.get("paths"));
        Map<String, Object> hotelDiscovery = map(map(paths.get("/api/v1/dashboards/hotels")).get("get"));
        assertThat(String.valueOf(hotelDiscovery.get("description"))).contains("dashboard.hotel");
        assertThat(responseSchemaRef(hotelDiscovery)).isEqualTo("#/components/schemas/AccessibleHotels");

        Map<String, Object> operations = map(map(paths.get("/api/v1/dashboards/operations")).get("get"));
        assertThat(String.valueOf(operations.get("description")))
                .contains("dashboard.operations")
                .doesNotContain("Requires dashboard.hotel");

        Map<String, Object> schemas = map(map(document.get("components")).get("schemas"));
        Map<String, Object> roleType = map(map(map(schemas.get("CreateRole")).get("properties")).get("roleType"));
        assertThat(list(roleType.get("enum"))).containsExactly("CUSTOM", null);
        assertThat(roleType.get("default")).isEqualTo("CUSTOM");

        Map<String, Object> accessibleHotels = map(schemas.get("AccessibleHotels"));
        assertThat(list(accessibleHotels.get("required"))).containsExactly("hotels");
        Map<String, Object> accessibleHotel = map(schemas.get("AccessibleHotel"));
        assertThat(list(accessibleHotel.get("required"))).containsExactly("id", "code", "name");
    }

    @Test
    void storeResourceContractsExposeInventoryAssetsAndThreePartyHandover() throws IOException {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Map<String, Object> document;
        try (Reader reader = Files.newBufferedReader(openApiPath())) {
            document = map(new Yaml(new SafeConstructor(options)).load(reader));
        }

        Map<String, Object> paths = map(document.get("paths"));
        assertThat(paths).containsKeys(
                "/api/v1/store-resources/hotels",
                "/api/v1/store-resources/hotels/{hotelId}/items",
                "/api/v1/store-resources/hotels/{hotelId}/items/{itemId}",
                "/api/v1/store-resources/hotels/{hotelId}/summary",
                "/api/v1/store-resources/hotels/{hotelId}/balances",
                "/api/v1/store-resources/hotels/{hotelId}/movements",
                "/api/v1/store-resources/hotels/{hotelId}/assets",
                "/api/v1/store-resources/hotels/{hotelId}/assignment-options",
                "/api/v1/store-resources/hotels/{hotelId}/imports/template",
                "/api/v1/store-resources/hotels/{hotelId}/imports",
                "/api/v1/store-resources/hotels/{hotelId}/imports/preview",
                "/api/v1/store-resources/hotels/{hotelId}/imports/{importJobId}/errors",
                "/api/v1/store-resources/hotels/{hotelId}/imports/{importJobId}/actions/apply",
                "/api/v1/store-resources/hotels/{hotelId}/handovers",
                "/api/v1/store-resources/handovers/{handoverId}",
                "/api/v1/store-resources/handovers/{handoverId}/actions/dispute-decision",
                "/api/v1/store-resources/handovers/{handoverId}/actions/physical-count",
                "/api/v1/store-resources/handovers/{handoverId}/actions/transferor-confirm",
                "/api/v1/store-resources/handovers/{handoverId}/actions/recipient-confirm",
                "/api/v1/store-resources/handovers/{handoverId}/actions/approve"
        );

        Map<String, Object> movementPost = map(map(paths.get(
                "/api/v1/store-resources/hotels/{hotelId}/movements")).get("post"));
        assertThat(list(movementPost.get("parameters")))
                .extracting(parameter -> String.valueOf(map(parameter).get("$ref")))
                .contains("#/components/parameters/HotelId", "#/components/parameters/IdempotencyKey");
        assertThat(requestSchemaRef(movementPost))
                .isEqualTo("#/components/schemas/CreateStoreInventoryMovement");

        Map<String, Object> applyImport = map(map(paths.get(
                "/api/v1/store-resources/hotels/{hotelId}/imports/{importJobId}/actions/apply")).get("post"));
        assertThat(list(applyImport.get("parameters")))
                .extracting(parameter -> String.valueOf(map(parameter).get("$ref")))
                .containsExactly(
                        "#/components/parameters/HotelId",
                        "#/components/parameters/StoreResourceImportJobId",
                        "#/components/parameters/IdempotencyKey"
                );

        Map<String, Object> createHandover = map(map(paths.get(
                "/api/v1/store-resources/hotels/{hotelId}/handovers")).get("post"));
        assertThat(requestSchemaRef(createHandover))
                .isEqualTo("#/components/schemas/CreateStoreResourceHandover");

        Map<String, Object> schemas = map(map(document.get("components")).get("schemas"));
        assertThat(list(map(schemas.get("CreateStoreResourceHandover")).get("required")))
                .containsExactly("transferorAssignmentId", "recipientAssignmentId", "approverAssignmentId");
        assertThat(list(map(schemas.get("StoreResourcePhysicalCount")).get("required")))
                .containsExactly("lines", "expectedVersion");
        assertThat(list(map(map(map(schemas.get("CreateStoreResourceItem"))
                .get("properties")).get("category")).get("enum")))
                .containsExactly("CONSUMABLE", "LINEN", "CLEANING_SUPPLY", "OPERATING_ITEM");
    }

    private static String responseSchemaRef(Map<String, Object> operation) {
        return String.valueOf(map(map(map(map(map(operation.get("responses")).get("200"))
                .get("content")).get("application/json")).get("schema")).get("$ref"));
    }

    private static String requestSchemaRef(Map<String, Object> operation) {
        return String.valueOf(map(map(map(map(operation.get("requestBody"))
                .get("content")).get("application/json")).get("schema")).get("$ref"));
    }

    private static Path openApiPath() {
        Path workingDirectory = Path.of("").toAbsolutePath();
        Path fromRepository = workingDirectory.resolve("docs/openapi.yaml");
        if (Files.isRegularFile(fromRepository)) {
            return fromRepository;
        }
        return workingDirectory.resolve("../../docs/openapi.yaml").normalize();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        assertThat(value).isInstanceOf(List.class);
        return (List<Object>) value;
    }
}
