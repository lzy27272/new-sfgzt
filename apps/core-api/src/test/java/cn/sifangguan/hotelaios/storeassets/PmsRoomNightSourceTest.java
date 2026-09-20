package cn.sifangguan.hotelaios.storeassets;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class PmsRoomNightSourceTest {
    @TempDir Path dir;
    final ObjectMapper mapper = new ObjectMapper();
    static final UUID TENANT = UUID.randomUUID(), PROFILE = UUID.randomUUID();
    static final LocalDate START = LocalDate.parse("2026-08-01"), END = LocalDate.parse("2026-08-31");
    PmsRoomNightSource reader;

    @BeforeEach void setup() throws Exception {
        mapper.writeValue(dir.resolve("hotels.json").toFile(), List.of(Map.of("tenantCode", "tenant-a", "hotelId", PROFILE.toString(), "hotelCode", "009", "pmsProviderCode", "TEST")));
        reader = new PmsRoomNightSource(mapper, TENANT.toString(), "tenant-a", dir.resolve("hotels.json").toString(), dir.resolve("summary.json").toString());
    }
    @Test void exactPeriodRequiresExplicitRoomCountAndDefinition() throws Exception {
        ObjectNode row = record(START, END, 310); write(row);
        var result = dataset().period(START, END);
        assertThat(result.usable()).isTrue(); assertThat(result.roomUses()).isEqualTo("310");
        assertThat(result.expectedDays()).isEqualTo(31); assertThat(result.revision()).hasSize(64);
        assertThat(result.definitionLabel()).contains("含钟点房", "含免费房", "含自用房");
        assertThat(dataset().period(START.minusMonths(1), END.minusMonths(1)).state()).isEqualTo("NO_DATA");
    }
    @Test void anOfficialOccupancyRateIsNotANightCountAndMissingDefinitionIsOnlyACandidate() throws Exception {
        ObjectNode row = record(START, END, 310);
        row.remove("roomUseDefinition"); row.put("officialScoreEligible", true); write(row);
        var candidate = dataset().period(START, END);
        assertThat(candidate.state()).isEqualTo("UNVERIFIED"); assertThat(candidate.roomUses()).isNull();
        assertThat(candidate.candidateRoomUses()).isEqualTo("310");
        ((ObjectNode) row.path("metrics")).remove("totalRoomUses");
        ((ObjectNode) row.path("metrics")).put("occupancyRate", 0.97).put("soldRooms", 50).put("overnightSoldRoomNights", 310); write(row);
        assertThat(dataset().period(START, END).roomUses()).isNull();
    }
    @Test void bindsBothTenantAndProfileIdentityAndCodeNotNames() throws Exception {
        write(record(START, END, 310));
        assertThat(reader.read(UUID.randomUUID(), PROFILE, "009", "Asia/Shanghai", LocalTime.of(4, 0), END, Instant.parse("2026-09-10T00:00:00Z")).period(START, END).state()).isEqualTo("NOT_CONNECTED");
        assertThat(reader.read(TENANT, UUID.randomUUID(), "009", "Asia/Shanghai", LocalTime.of(4, 0), END, Instant.parse("2026-09-10T00:00:00Z")).period(START, END).state()).isEqualTo("NOT_MAPPED");
        mapper.writeValue(dir.resolve("hotels.json").toFile(), List.of(Map.of("tenantCode", "tenant-a", "hotelId", PROFILE.toString(), "hotelCode", "009"), Map.of("tenantCode", "tenant-a", "hotelId", "another", "hotelCode", "009")));
        assertThat(dataset().period(START, END).state()).isEqualTo("NOT_MAPPED");
    }
    @Test void anExplicitBindingReadsTheOriginalSourceIdWithoutRenamingHistoricalRecords() throws Exception {
        String sourceId = UUID.randomUUID().toString();
        directory(binding(sourceId));
        ObjectNode source = record(START, END, 310).put("sourceHotelId", sourceId);
        ObjectNode unrelated = record(START, END, 999);
        write(source, unrelated);
        var result = dataset().period(START, END);
        assertThat(result.usable()).isTrue();
        assertThat(result.roomUses()).isEqualTo("310");
        assertThat(mapper.readTree(dir.resolve("summary.json").toFile()).path("records").get(0)
                .path("sourceHotelId").asText()).isEqualTo(sourceId);
    }
    @Test void incompleteExplicitBindingsDoNotFallBackToCoincidentallyEqualIds() throws Exception {
        write(record(START, END, 310));
        for (String field : List.of("coreTenantId", "coreHotelProfileId", "mappingEvidenceReference")) {
            ObjectNode hotel = binding(PROFILE.toString()); hotel.remove(field); directory(hotel);
            assertThat(dataset().period(START, END).state()).as("missing %s", field).isEqualTo("NOT_MAPPED");
            hotel = binding(PROFILE.toString()); hotel.put(field, " "); directory(hotel);
            assertThat(dataset().period(START, END).state()).as("blank %s", field).isEqualTo("NOT_MAPPED");
            hotel = binding(PROFILE.toString()); hotel.putNull(field); directory(hotel);
            assertThat(dataset().period(START, END).state()).as("null %s", field).isEqualTo("NOT_MAPPED");
        }
    }
    @Test void explicitBindingsMustStillMatchTheCoreTenantAndPropertyCode() throws Exception {
        write(record(START, END, 310));
        directory(binding(PROFILE.toString()).put("coreTenantId", UUID.randomUUID().toString()));
        assertThat(dataset().period(START, END).state()).isEqualTo("NOT_MAPPED");
        directory(binding(PROFILE.toString()).put("hotelCode", "another-code"));
        assertThat(dataset().period(START, END).state()).isEqualTo("NOT_MAPPED");
        directory(binding(PROFILE.toString()).put("tenantCode", "another-source"));
        assertThat(dataset().period(START, END).state()).isEqualTo("NOT_MAPPED");
    }
    @Test void explicitMappingIsOneToOneOnBothCoreAndSourceSides() throws Exception {
        String sourceId = UUID.randomUUID().toString();
        write(record(START, END, 310).put("sourceHotelId", sourceId));
        ObjectNode hotel = binding(sourceId);
        // A source must not be shared by two different core hotels, even with different property codes.
        directory(hotel, binding(sourceId).put("coreHotelProfileId", UUID.randomUUID().toString()).put("hotelCode", "other"));
        assertThat(dataset().period(START, END).state()).isEqualTo("NOT_MAPPED");
        // A core hotel must not claim two sources, even with different property codes.
        directory(hotel, binding(UUID.randomUUID().toString()).put("hotelCode", "other"));
        assertThat(dataset().period(START, END).state()).isEqualTo("NOT_MAPPED");
        // Legacy and explicit entries cannot silently compete for the same core identity.
        directory(hotel, mapper.valueToTree(Map.of("tenantCode", "tenant-a", "hotelId", PROFILE.toString(), "hotelCode", "other")));
        assertThat(dataset().period(START, END).state()).isEqualTo("NOT_MAPPED");
    }
    @Test void explicitMappingDoesNotWeakenRecordTenantAndCodeChecks() throws Exception {
        String sourceId = UUID.randomUUID().toString(); directory(binding(sourceId));
        ObjectNode row = record(START, END, 310).put("sourceHotelId", sourceId).put("tenantCode", "wrong-tenant");
        write(row);
        assertThat(dataset().period(START, END).state()).isEqualTo("SOURCE_ERROR");
        row.remove("tenantCode"); row.put("sourceHotelCode", "wrong-hotel"); write(row);
        assertThat(dataset().period(START, END).state()).isEqualTo("UNVERIFIED");
    }
    private ObjectNode binding(String sourceId) {
        return mapper.createObjectNode().put("tenantCode", "tenant-a").put("hotelId", sourceId)
                .put("hotelCode", "009").put("pmsProviderCode", "TEST").put("coreTenantId", TENANT.toString())
                .put("coreHotelProfileId", PROFILE.toString()).put("mappingEvidenceReference", "test:verified-hotel-mapping");
    }
    private void directory(com.fasterxml.jackson.databind.JsonNode... hotels) throws Exception {
        mapper.writeValue(dir.resolve("hotels.json").toFile(), List.of(hotels));
    }
    @Test void dailyCoverageCannotBeFilledWithZeroOrAnOlderMonthlyAggregate() throws Exception {
        write(record(START, END, 310), record(START, START, 10));
        var result = dataset().period(START, END);
        assertThat(result.state()).isEqualTo("INCOMPLETE"); assertThat(result.roomUses()).isNull();
        assertThat(result.missingDates()).hasSize(30); assertThat(result.coveredDays()).isEqualTo(1);
    }
    @Test void dailyZeroIsValidButWholePeriodZeroDoesNotDivideAndDefinitionsMustMatch() throws Exception {
        ObjectNode a = record(START, START, 0), b = record(START.plusDays(1), START.plusDays(1), 10);
        write(a, b);
        assertThat(dataset().period(START, START.plusDays(1)).roomUses()).isEqualTo("10");
        assertThat(dataset().period(START, START).state()).isEqualTo("ZERO_USES");
        ((ObjectNode)b.path("roomUseDefinition")).put("countingRule", "UNIQUE_ROOM_PER_DAY"); write(a, b);
        assertThat(dataset().period(START, START.plusDays(1)).state()).isEqualTo("UNVERIFIED");
    }
    @Test void latestInvalidVersionDoesNotFallBackAndSameInstantDuplicatesAreRejected() throws Exception {
        ObjectNode old = record(START, END, 310), latest = old.deepCopy();
        latest.put("collectedAt", "2026-09-03T08:00:00+08:00");
        ((ObjectNode)latest.path("validation")).put("numericState", "FAIL"); write(old, latest);
        assertThat(dataset().period(START, END).state()).isEqualTo("INCOMPLETE");
        latest.put("collectedAt", "2026-09-02T00:00:00Z"); write(old, latest);
        assertThat(dataset().period(START, END).state()).isEqualTo("DUPLICATE");
    }
    @Test void rejectsBadCountsMissingChecksTimezoneCutoffAndFutureCollection() throws Exception {
        for (String field : List.of("expectedDayCount", "validDistinctDayCount")) {
            ObjectNode row = record(START, END, 310); ((ObjectNode)row.path("period")).put(field, 30); write(row);
            assertThat(dataset().period(START, END).usable()).isFalse();
        }
        ObjectNode row = record(START, END, 310);
        ((ObjectNode)row.path("period")).remove("duplicateDates"); write(row);
        assertThat(dataset().period(START, END).state()).isEqualTo("INCOMPLETE");
        row = record(START, END, 310); ((ObjectNode)row.path("roomUseDefinition")).put("businessDayCutoff", "06:00"); write(row);
        assertThat(dataset().period(START, END).state()).isEqualTo("UNVERIFIED");
        row = record(START, END, 310); row.put("collectedAt", "2026-10-01T00:00:00Z"); write(row);
        assertThat(dataset().period(START, END).state()).isEqualTo("UNVERIFIED");
        row = record(START, END, 310); row.put("collectedAt", "2026-08-01T00:00:00Z"); write(row);
        assertThat(dataset().period(START, END).state()).isEqualTo("UNVERIFIED");
    }
    @Test void disallowsNegativeOverpreciseAndStringRoomCountsAndExcludedHourlyRooms() throws Exception {
        for (double bad : new double[]{-1, 0.5, 0.00001, 1000000001}) {
            ObjectNode row = record(START, END, 310); ((ObjectNode)row.path("metrics")).put("totalRoomUses", bad); write(row);
            assertThat(dataset().period(START, END).state()).isEqualTo("UNVERIFIED");
        }
        ObjectNode row = record(START, END, 310); ((ObjectNode)row.path("metrics")).put("totalRoomUses", "310"); write(row);
        assertThat(dataset().period(START, END).roomUses()).isNull();
        row = record(START, END, 310); ((ObjectNode)row.path("roomUseDefinition")).put("hourlyRooms", "EXCLUDED"); write(row);
        assertThat(dataset().period(START, END).usable()).isFalse();
    }
    @Test void sourceFailureAndExplicitTenantConflictNeverExposeRawDataOrPaths() throws Exception {
        Files.writeString(dir.resolve("summary.json"), "not-json-secret");
        var error = dataset().period(START, END);
        assertThat(error.state()).isEqualTo("SOURCE_ERROR");
        assertThat(mapper.writeValueAsString(error)).doesNotContain("not-json-secret", dir.toString());
        ObjectNode row = record(START, END, 310); row.put("tenantCode", "another-tenant"); write(row);
        assertThat(dataset().period(START, END).state()).isEqualTo("SOURCE_ERROR");
    }
    @Test void metadataFingerprintsChangeOnRevisionWithoutReturningPrivatePayloadFields() throws Exception {
        ObjectNode row = record(START, END, 310); row.put("guestMobile", "NEVER-RETURN"); write(row);
        var first = dataset().period(START, END);
        assertThat(mapper.writeValueAsString(first)).doesNotContain("NEVER-RETURN", "guestMobile");
        ((ObjectNode)row.path("metrics")).put("totalRoomUses", 311).put("paidOvernightRoomUses", 311); write(row);
        assertThat(dataset().period(START, END).revision()).isNotEqualTo(first.revision());
    }
    @Test void openBusinessDatesAreNotFinalDenominators() throws Exception {
        ObjectNode row = record(START, END, 310); write(row);
        var source = reader.read(TENANT, PROFILE, "009", "Asia/Shanghai", LocalTime.of(4, 0), END.minusDays(1), Instant.parse("2026-09-10T00:00:00Z"));
        assertThat(source.period(START, END).state()).isEqualTo("PERIOD_OPEN");
    }
    @Test void includesAllFourExclusiveCategoriesWithoutConvertingHourlyUses() throws Exception {
        ObjectNode row = record(START, END, 200);
        ((ObjectNode)row.path("metrics")).put("totalRoomUses", new java.math.BigDecimal("217.0000"))
                .put("complimentaryRoomUses", 10).put("houseUseRoomUses", 5)
                .put("paidHourlyRoomUses", new java.math.BigDecimal("2.0000"));
        write(row);
        var result = dataset().period(START, END);
        assertThat(result.usable()).isTrue();
        assertThat(result.roomUses()).isEqualTo("217");
        var breakdown = mapper.valueToTree(result).path("breakdown");
        assertThat(breakdown.path("paidOvernightRoomUses").asText()).isEqualTo("200");
        assertThat(breakdown.path("complimentaryRoomUses").asText()).isEqualTo("10");
        assertThat(breakdown.path("houseUseRoomUses").asText()).isEqualTo("5");
        assertThat(breakdown.path("paidHourlyRoomUses").asText()).isEqualTo("2");
    }
    @Test void missingOrInvalidComponentsAreNotAssumedZero() throws Exception {
        for (String field : List.of("paidOvernightRoomUses", "complimentaryRoomUses", "houseUseRoomUses", "paidHourlyRoomUses")) {
            ObjectNode row = record(START, END, 310); ((ObjectNode)row.path("metrics")).remove(field); write(row);
            assertThat(dataset().period(START, END).state()).as("missing optional %s", field).isEqualTo("READY");
            assertThat(dataset().period(START, END).breakdown()).isNull();
            row = record(START, END, 310); ((ObjectNode)row.path("metrics")).put(field, -1); write(row);
            assertThat(dataset().period(START, END).state()).as("negative %s", field).isEqualTo("UNVERIFIED");
            row = record(START, END, 310); ((ObjectNode)row.path("metrics")).put(field, "0"); write(row);
            assertThat(dataset().period(START, END).state()).as("string %s", field).isEqualTo("UNVERIFIED");
        }
    }
    @Test void categorySumMustExactlyMatchThePublishedTotalWithoutRoundingOrAddingAgain() throws Exception {
        ObjectNode row = record(START, END, 310);
        ((ObjectNode)row.path("metrics")).put("complimentaryRoomUses", 10); write(row);
        assertThat(dataset().period(START, END).state()).isEqualTo("INCOMPLETE");
        ((ObjectNode)row.path("metrics")).put("totalRoomUses", 320); write(row);
        assertThat(dataset().period(START, END).roomUses()).isEqualTo("320");
    }
    @Test void legacyOvernightOnlyRecordsCannotBeRelabeledAsAllUsedRooms() throws Exception {
        ObjectNode row = record(START, END, 310);
        ((ObjectNode)row.path("roomUseDefinition")).put("code", "OVERNIGHT_SOLD"); write(row);
        assertThat(dataset().period(START, END).state()).isEqualTo("UNVERIFIED");
        assertThat(dataset().period(START, END).roomUses()).isNull();
        ((ObjectNode)row.path("metrics")).remove("totalRoomUses");
        ((ObjectNode)row.path("metrics")).put("overnightSoldRoomNights", 310); write(row);
        assertThat(dataset().period(START, END).candidateRoomUses()).isNull();
    }
    @Test void requiresAllInclusionsAndEachUseCountingInsteadOfRoomDeduplication() throws Exception {
        for (String field : List.of("complimentaryRooms", "houseUseRooms", "hourlyRooms", "unit", "countingRule", "breakdownRule")) {
            ObjectNode row = record(START, END, 310); ((ObjectNode)row.path("roomUseDefinition")).remove(field); write(row);
            assertThat(dataset().period(START, END).state()).as("missing %s", field).isEqualTo("UNVERIFIED");
        }
        for (String field : List.of("complimentaryRooms", "houseUseRooms", "hourlyRooms")) {
            ObjectNode row = record(START, END, 310); ((ObjectNode)row.path("roomUseDefinition")).put(field, "EXCLUDED"); write(row);
            assertThat(dataset().period(START, END).usable()).as("excluded %s", field).isFalse();
        }
        ObjectNode row = record(START, END, 310);
        ((ObjectNode)row.path("roomUseDefinition")).put("unit", "OPEN_ROOM_COUNT"); write(row);
        assertThat(dataset().period(START, END).state()).isEqualTo("UNVERIFIED");
        ((ObjectNode)row.path("roomUseDefinition")).put("unit", "ROOM_USE").put("countingRule", "UNIQUE_ROOM_PER_DAY"); write(row);
        assertThat(dataset().period(START, END).state()).isEqualTo("UNVERIFIED");
    }
    @Test void dailyBreakdownsAddExactlyAndRemainVisibleAfterAggregation() throws Exception {
        ObjectNode a = record(START, START, 0), b = record(START.plusDays(1), START.plusDays(1), 0);
        ((ObjectNode)a.path("metrics")).put("totalRoomUses", 1).put("paidHourlyRoomUses", 1);
        ((ObjectNode)b.path("metrics")).put("totalRoomUses", 2).put("paidHourlyRoomUses", 2);
        write(a, b);
        var result = dataset().period(START, START.plusDays(1));
        assertThat(result.roomUses()).isEqualTo("3");
        assertThat(mapper.valueToTree(result).path("breakdown").path("paidHourlyRoomUses").asText()).isEqualTo("3");
    }
    @Test void aConfirmedInclusiveTotalNeedsNeitherFourCategoriesNorHourlyConversion() throws Exception {
        ObjectNode row = totalOnly(record(START, END, 310)); write(row);
        var total = dataset().period(START, END);
        assertThat(total.usable()).isTrue(); assertThat(total.roomUses()).isEqualTo("310");
        assertThat(total.breakdown()).isNull();
        write(record(START, END, 310));
        assertThat(dataset().period(START, END).definitionKey()).isEqualTo(total.definitionKey());
    }
    @Test void mixedDailyTotalsAndOptionalDetailsSumCountsWithoutInventingCategoryZeros() throws Exception {
        ObjectNode a = totalOnly(record(START, START, 2)), b = record(START.plusDays(1), START.plusDays(1), 8);
        write(a, b);
        var result = dataset().period(START, START.plusDays(1));
        assertThat(result.usable()).isTrue(); assertThat(result.roomUses()).isEqualTo("10");
        assertThat(result.breakdown()).isNull(); assertThat(result.coveredDays()).isEqualTo(2);
    }
    @Test void sameRoomHourlyThenOvernightCountsTwoUsesNotOneOrAFraction() throws Exception {
        ObjectNode row = record(START, START, 1);
        ((ObjectNode)row.path("metrics")).put("totalRoomUses", 2).put("paidHourlyRoomUses", 1);
        write(row);
        var result = dataset().period(START, START);
        assertThat(result.roomUses()).isEqualTo("2");
        assertThat(result.breakdown().paidHourlyRoomUses()).isEqualTo("1");
        assertThat(result.breakdown().paidOvernightRoomUses()).isEqualTo("1");
        assertThat(result.definitionLabel()).contains("同房当天使用两次计2间次", "钟点不折算");
    }
    @Test void partialContradictoryAndFractionalBreakdownsCannotOverrideConfirmedTotals() throws Exception {
        ObjectNode row = record(START, END, 2);
        ((ObjectNode)row.path("metrics")).remove("paidOvernightRoomUses");
        ((ObjectNode)row.path("metrics")).put("paidHourlyRoomUses", 3); write(row);
        assertThat(dataset().period(START, END).state()).isEqualTo("INCOMPLETE");
        ((ObjectNode)row.path("metrics")).put("paidHourlyRoomUses", 0.5); write(row);
        assertThat(dataset().period(START, END).state()).isEqualTo("UNVERIFIED");
    }
    @Test void oldEquivalentRoomNightDefinitionsAndValuesCannotSilentlyMigrate() throws Exception {
        ObjectNode row = record(START, END, 2);
        ((ObjectNode)row.path("roomUseDefinition")).put("code", "ALL_USED_ROOM_NIGHTS_V1"); write(row);
        assertThat(dataset().period(START, END).state()).isEqualTo("UNVERIFIED");
        row = record(START, END, 2);
        row.set("roomNightDefinition", row.remove("roomUseDefinition"));
        ((ObjectNode)row.path("metrics")).remove("totalRoomUses");
        ((ObjectNode)row.path("metrics")).put("totalUsedRoomNights", 2); write(row);
        assertThat(dataset().period(START, END).roomUses()).isNull();
        assertThat(dataset().period(START, END).candidateRoomUses()).isNull();
    }
    static ObjectNode totalOnly(ObjectNode row) {
        ((ObjectNode)row.path("metrics")).remove(List.of("paidOvernightRoomUses", "complimentaryRoomUses", "houseUseRoomUses", "paidHourlyRoomUses"));
        ((ObjectNode)row.path("roomUseDefinition")).remove(List.of("breakdownRule", "hourlyConversionRuleId"));
        return row;
    }
    private PmsRoomNightSource.Dataset dataset() {
        return reader.read(TENANT, PROFILE, "009", "Asia/Shanghai", LocalTime.of(4, 0), END, Instant.parse("2026-09-10T00:00:00Z"));
    }
    private void write(ObjectNode... records) throws Exception { mapper.writeValue(dir.resolve("summary.json").toFile(), Map.of("records", List.of(records))); }
    private ObjectNode record(LocalDate from, LocalDate to, int nights) {
        return fixture(mapper, PROFILE.toString(), "009", from, to, nights, "2026-09-02T08:00:00+08:00");
    }
    static ObjectNode fixture(ObjectMapper mapper, String profile, String code, LocalDate from, LocalDate to, int nights, String collectedAt) {
        ObjectNode row = mapper.createObjectNode();
        row.put("sourceHotelId", profile).put("sourceHotelCode", code).put("collectedAt", collectedAt).put("responseContentSha256", "a".repeat(64));
        ObjectNode period = row.putObject("period");
        int days = (int)java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;
        period.put("from", from.toString()).put("to", to.toString()).put("expectedDayCount", days).put("validDistinctDayCount", days);
        period.putArray("missingDates"); period.putArray("duplicateDates");
        row.putObject("metrics").put("totalRoomUses", nights).put("paidOvernightRoomUses", nights)
                .put("complimentaryRoomUses", 0).put("houseUseRoomUses", 0).put("paidHourlyRoomUses", 0);
        row.putObject("validation").put("coverageState", "PASS").put("numericState", "PASS").put("aggregateCrosscheckState", "PASS");
        row.putObject("roomUseDefinition").put("code", "ALL_ROOM_USES_V1").put("verificationState", "CONFIRMED")
                .put("timezone", "Asia/Shanghai").put("businessDayCutoff", "04:00").put("hourlyRooms", "INCLUDED")
                .put("complimentaryRooms", "INCLUDED").put("houseUseRooms", "INCLUDED").put("evidenceReference", "test:explicit-pms-definition")
                .put("unit", "ROOM_USE").put("breakdownRule", "FREE_AND_HOUSE_USE_FIRST")
                .put("countingRule", "EACH_USE");
        return row;
    }
}
