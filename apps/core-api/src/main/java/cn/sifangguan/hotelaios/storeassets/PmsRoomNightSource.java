package cn.sifangguan.hotelaios.storeassets;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Reads only published, sanitized PMS aggregates. Never opens vendor sessions or infers nights from occupancy. */
@Component
public class PmsRoomNightSource {
    public static final String BASIS = "ALL_ROOM_USES_V1";
    private final ObjectMapper mapper;
    private final String tenantId, sourceTenantCode, directoryPath, summaryPath;

    public PmsRoomNightSource(ObjectMapper mapper,
            @Value("${app.store-cost.pms-source.tenant-id:}") String tenantId,
            @Value("${app.store-cost.pms-source.allowed-tenant-code:}") String sourceTenantCode,
            @Value("${app.store-cost.pms-source.hotel-directory-path:}") String directoryPath,
            @Value("${app.store-cost.pms-source.summary-path:}") String summaryPath) {
        this.mapper = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        this.tenantId = tenantId; this.sourceTenantCode = sourceTenantCode;
        this.directoryPath = directoryPath; this.summaryPath = summaryPath;
    }

    public Dataset read(UUID coreTenantId, UUID hotelProfileId, String propertyCode, String timezone,
                        LocalTime cutoff, LocalDate lastClosedDay, Instant now) {
        if (!coreTenantId.toString().equals(tenantId) || sourceTenantCode.isBlank()
                || directoryPath.isBlank() || summaryPath.isBlank())
            return Dataset.unavailable("NOT_CONNECTED", "本租户尚未配置经授权的PMS用房间次数据源");
        if (hotelProfileId == null || propertyCode == null || propertyCode.isBlank())
            return Dataset.unavailable("NOT_MAPPED", "门店缺少PMS对应档案，请先核对门店编码和档案编号");
        try {
            JsonNode directory = readJson(directoryPath, 1024 * 1024);
            if (!directory.isArray()) return Dataset.unavailable("SOURCE_ERROR", "PMS门店目录格式异常，请联系管理员");
            List<JsonNode> matches = new ArrayList<>();
            Map<String, Integer> sourceIdCounts = new HashMap<>();
            int matchingIds = 0, matchingCodes = 0;
            for (JsonNode hotel : directory) {
                if (!sourceTenantCode.equals(hotel.path("tenantCode").asText())) continue;
                String sourceId = directoryText(hotel, "hotelId");
                if (sourceId != null) sourceIdCounts.merge(sourceId, 1, Integer::sum);
                boolean idMatches = hotelProfileId.toString().equals(mappedProfileId(hotel, coreTenantId));
                boolean codeMatches = propertyCode.equals(directoryText(hotel, "hotelCode"));
                if (idMatches) matchingIds++;
                if (codeMatches) matchingCodes++;
                if (idMatches && codeMatches) matches.add(hotel);
            }
            // Only the server-published binding can connect distinct IDs; names and client input cannot.
            if (matches.size() != 1 || matchingIds != 1 || matchingCodes != 1)
                return Dataset.unavailable("NOT_MAPPED", "PMS门店档案编号与编码未唯一匹配，暂不计算每间次成本");
            JsonNode matchedHotel = matches.getFirst();
            String sourceId = directoryText(matchedHotel, "hotelId");
            if (sourceId == null || sourceIdCounts.getOrDefault(sourceId, 0) != 1)
                return Dataset.unavailable("NOT_MAPPED", "PMS来源编号缺失或被重复映射，暂不计算每间次成本");
            List<JsonNode> selected = readRecords(sourceId);
            for (JsonNode row : selected) {
                // An explicit tenant on a record must agree with the authorized source directory.
                if (row.has("tenantCode") && !sourceTenantCode.equals(row.path("tenantCode").asText()))
                    return Dataset.unavailable("SOURCE_ERROR", "PMS汇总租户归属冲突，请重新核对数据源");
            }
            return new Dataset(selected, matchedHotel.path("pmsProviderCode").asText("PMS"),
                    propertyCode, timezone, cutoff, lastClosedDay, now, null, null);
        } catch (Exception exception) {
            // Do not expose filesystem paths, raw payloads or vendor/session information to the web client.
            return Dataset.unavailable("SOURCE_ERROR", "PMS脱敏汇总读取失败，成本账仍可查看，请联系管理员核对数据源");
        }
    }

    private static String mappedProfileId(JsonNode hotel, UUID coreTenantId) {
        boolean explicit = hotel.has("coreHotelProfileId") || hotel.has("coreTenantId")
                || hotel.has("mappingEvidenceReference");
        if (!explicit) return directoryText(hotel, "hotelId");
        // A partial, blank or foreign-tenant binding must never fall back to an equal source ID.
        if (!coreTenantId.toString().equals(directoryText(hotel, "coreTenantId"))
                || directoryText(hotel, "mappingEvidenceReference") == null) return null;
        return directoryText(hotel, "coreHotelProfileId");
    }

    private static String directoryText(JsonNode row, String field) {
        JsonNode value = row.path(field);
        if (!value.isTextual()) return null;
        String text = value.textValue();
        return text.isBlank() || text.length() > 256 || !text.equals(text.strip())
                || text.chars().anyMatch(Character::isISOControl) ? null : text;
    }

    // Stream other hotels rather than retaining a multi-year, multi-property document in each request.
    private List<JsonNode> readRecords(String sourceHotelId) throws Exception {
        Path path = Path.of(summaryPath);
        int limit = 64 * 1024 * 1024;
        if (!Files.isRegularFile(path) || Files.size(path) > limit) throw new IllegalArgumentException();
        List<JsonNode> result = new ArrayList<>();
        try (var input = new LimitedInputStream(Files.newInputStream(path), limit);
             var parser = mapper.getFactory().createParser(input)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) throw new IllegalArgumentException();
            boolean found = false;
            int count = 0;
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() == null) throw new IllegalArgumentException();
                String name = parser.currentName(); parser.nextToken();
                if (!"records".equals(name)) { parser.skipChildren(); continue; }
                if (parser.currentToken() != JsonToken.START_ARRAY) throw new IllegalArgumentException();
                found = true;
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    if (parser.currentToken() != JsonToken.START_OBJECT || ++count > 100000) throw new IllegalArgumentException();
                    JsonNode record = mapper.readTree(parser);
                    if (sourceHotelId.equals(record.path("sourceHotelId").asText())) result.add(record);
                }
            }
            if (!found || parser.nextToken() != null) throw new IllegalArgumentException();
        }
        return result;
    }

    private static final class LimitedInputStream extends FilterInputStream {
        private long remaining;
        LimitedInputStream(InputStream stream, long maximum) { super(stream); remaining = maximum; }
        @Override public int read() throws IOException {
            int value = in.read(); if (value != -1 && --remaining < 0) throw new IOException("Source exceeds size limit"); return value;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            int count = in.read(bytes, offset, (int)Math.min(length, remaining + 1));
            if (count > 0 && (remaining -= count) < 0) throw new IOException("Source exceeds size limit"); return count;
        }
    }

    private JsonNode readJson(String file, int maxBytes) throws Exception {
        Path path = Path.of(file);
        if (!Files.isRegularFile(path) || Files.size(path) > maxBytes) throw new IllegalArgumentException();
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(maxBytes + 1);
            if (bytes.length > maxBytes) throw new IllegalArgumentException();
            return mapper.readTree(bytes);
        }
    }

    /** Optional mutually exclusive categories, counted as uses, never time-equivalent nights. */
    public record Breakdown(String paidOvernightRoomUses, String complimentaryRoomUses,
                            String houseUseRoomUses, String paidHourlyRoomUses) {
        BigDecimal total() {
            return new BigDecimal(paidOvernightRoomUses).add(new BigDecimal(complimentaryRoomUses))
                    .add(new BigDecimal(houseUseRoomUses)).add(new BigDecimal(paidHourlyRoomUses));
        }
        Breakdown plus(Breakdown other) {
            return new Breakdown(add(paidOvernightRoomUses, other.paidOvernightRoomUses),
                    add(complimentaryRoomUses, other.complimentaryRoomUses),
                    add(houseUseRoomUses, other.houseUseRoomUses),
                    add(paidHourlyRoomUses, other.paidHourlyRoomUses));
        }
        private static String add(String a, String b) { return quantityText(new BigDecimal(a).add(new BigDecimal(b))); }
    }

    private static String quantityText(BigDecimal value) { return value.stripTrailingZeros().toPlainString(); }

    public record Evidence(String state, String reason, String roomUses, String candidateRoomUses,
                           int coveredDays, int expectedDays, List<String> missingDates, String mode,
                           String definitionLabel, String definitionKey, String collectedAt, String revision, Breakdown breakdown) {
        public boolean usable() { return "READY".equals(state); }
        static Evidence blocked(String state, String reason, int expected) {
            return new Evidence(state, reason, null, null, 0, expected, List.of(), null, null, null, null, null, null);
        }
    }

    public record Dataset(List<JsonNode> records, String provider, String propertyCode, String timezone,
                          LocalTime cutoff, LocalDate lastClosedDay, Instant now, String errorState, String errorReason) {
        static Dataset unavailable(String state, String reason) {
            return new Dataset(List.of(), null, null, null, null, null, null, state, reason);
        }

        public Evidence period(LocalDate from, LocalDate to) {
            int expected = (int) ChronoUnit.DAYS.between(from, to) + 1;
            if (expected <= 0) return Evidence.blocked("PERIOD_OPEN", "本期还没有完成日结的营业日", 0);
            if (errorState != null) return Evidence.blocked(errorState, errorReason, expected);
            if (to.isAfter(lastClosedDay)) return Evidence.blocked("PERIOD_OPEN", "该营业日尚未完成日结及宽限期，暂不计算", expected);
            // A partially published daily series must not be disguised by an older monthly total.
            Map<LocalDate, List<JsonNode>> daily = new TreeMap<>();
            List<JsonNode> aggregates = new ArrayList<>();
            for (JsonNode row : records) {
                String a = row.path("period").path("from").asText(), b = row.path("period").path("to").asText();
                if (a.equals(from.toString()) && b.equals(to.toString())) aggregates.add(row);
                if (a.equals(b)) {
                    try {
                        LocalDate date = LocalDate.parse(a);
                        if (!date.isBefore(from) && !date.isAfter(to)) daily.computeIfAbsent(date, key -> new ArrayList<>()).add(row);
                    } catch (RuntimeException ignored) { /* No implicit date inferred from collection time. */ }
                }
            }
            if (daily.isEmpty()) return latest(aggregates, from, to, "PERIOD_SUMMARY");
            List<String> missing = new ArrayList<>();
            List<Evidence> days = new ArrayList<>();
            for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
                if (!daily.containsKey(date)) missing.add(date.toString());
                else days.add(latest(daily.get(date), date, date, "DAILY"));
            }
            if (!missing.isEmpty()) return new Evidence("INCOMPLETE", "PMS缺少 " + missing.size() + " 个营业日，不能用部分天数摊薄整期成本",
                    null, null, expected - missing.size(), expected, missing, "DAILY", null, null, null, null, null);
            Evidence invalid = days.stream().filter(day -> !day.usable() && !"ZERO_USES".equals(day.state())).findFirst().orElse(null);
            if (invalid != null) return Evidence.blocked(invalid.state(), "期间内有未通过校验的营业日：" + invalid.reason(), expected);
            if (days.stream().map(Evidence::definitionKey).distinct().count() != 1)
                return Evidence.blocked("DEFINITION_CHANGED", "期间内PMS用房间次口径发生变化，不能直接合计", expected);
            BigDecimal nights = days.stream().map(day -> new BigDecimal(day.roomUses())).reduce(BigDecimal.ZERO, BigDecimal::add);
            // A confirmed total is sufficient. Partial category coverage must not appear as a full breakdown.
            Breakdown breakdown = days.stream().allMatch(day -> day.breakdown() != null)
                    ? days.stream().map(Evidence::breakdown).reduce(Breakdown::plus).orElseThrow() : null;
            String collected = days.stream().map(Evidence::collectedAt).max(Comparator.comparing(OffsetDateTime::parse)).orElse(null);
            String revision = digest(String.join("|", days.stream().map(Evidence::revision).toList()));
            return new Evidence(nights.signum() == 0 ? "ZERO_USES" : "READY", nights.signum() == 0 ? "本期PMS确认全部用房为0间次，不进行除法" : "已按完整营业日汇总全部用房间次",
                    quantityText(nights), null, expected, expected, List.of(), "DAILY", days.getFirst().definitionLabel(),
                    days.getFirst().definitionKey(), collected, revision, breakdown);
        }

        private Evidence latest(List<JsonNode> rows, LocalDate from, LocalDate to, String mode) {
            int expected = (int) ChronoUnit.DAYS.between(from, to) + 1;
            if (rows.isEmpty()) return Evidence.blocked("NO_DATA", "未取得该日期范围的PMS用房间次；不会借用其他月份或出租率", expected);
            List<JsonNode> ordered = new ArrayList<>(rows);
            try {
                ordered.sort(Comparator.comparing((JsonNode row) -> OffsetDateTime.parse(row.path("collectedAt").asText()).toInstant()).reversed());
                if (ordered.size() > 1 && OffsetDateTime.parse(ordered.get(0).path("collectedAt").asText()).isEqual(
                        OffsetDateTime.parse(ordered.get(1).path("collectedAt").asText())))
                    return Evidence.blocked("DUPLICATE", "同一期间存在采集时间相同的重复汇总，请先去重核对", expected);
                JsonNode row = ordered.getFirst(), period = row.path("period"), validation = row.path("validation");
                Instant collected = OffsetDateTime.parse(row.path("collectedAt").asText()).toInstant();
                Instant periodEnd = to.plusDays(1).atTime(cutoff).atZone(ZoneId.of(timezone)).toInstant();
                if (collected.isAfter(now.plusSeconds(300)) || collected.isBefore(periodEnd))
                    return Evidence.blocked("UNVERIFIED", "PMS采集时间早于该期结束或晚于当前时间，请重新核对", expected);
                if (!propertyCode.equals(row.path("sourceHotelCode").asText()))
                    return Evidence.blocked("UNVERIFIED", "PMS汇总的门店编码与已匹配档案不一致", expected);
                if (!"PASS".equals(validation.path("coverageState").asText())
                        || !"PASS".equals(validation.path("numericState").asText())
                        || !"PASS".equals(validation.path("aggregateCrosscheckState").asText())
                        || !exactCount(period.path("expectedDayCount"), expected)
                        || !exactCount(period.path("validDistinctDayCount"), expected)
                        || !period.path("missingDates").isArray() || !period.path("missingDates").isEmpty()
                        || !period.path("duplicateDates").isArray() || !period.path("duplicateDates").isEmpty())
                    return Evidence.blocked("INCOMPLETE", "PMS天数、缺失、重复、数值或合计校验未通过", expected);
                JsonNode metrics = row.path("metrics"), number = metrics.path("totalRoomUses");
                if (!validQuantity(number))
                    return Evidence.blocked("UNVERIFIED", "缺少有效的全部用房总间次；需为非负整数，不能用过夜房晚、折合房晚、房号去重数或出租率替代", expected);
                var fields = List.of("paidOvernightRoomUses", "complimentaryRoomUses", "houseUseRoomUses", "paidHourlyRoomUses");
                BigDecimal suppliedSubtotal = BigDecimal.ZERO;
                for (String field : fields) {
                    if (metrics.hasNonNull(field)) {
                        if (!validQuantity(metrics.path(field)))
                            return Evidence.blocked("UNVERIFIED", "已提供的用房分项不是有效的非负整数间次，请核对；不将无效值当作0", expected);
                        suppliedSubtotal = suppliedSubtotal.add(metrics.path(field).decimalValue());
                    }
                }
                if (suppliedSubtotal.compareTo(number.decimalValue()) > 0)
                    return Evidence.blocked("INCOMPLETE", "PMS用房分项已超过总间次，请核对重复计数或遗漏", expected);
                Breakdown breakdown = null;
                if (fields.stream().allMatch(metrics::hasNonNull)) {
                    breakdown = new Breakdown(quantityText(metrics.path("paidOvernightRoomUses").decimalValue()),
                            quantityText(metrics.path("complimentaryRoomUses").decimalValue()),
                            quantityText(metrics.path("houseUseRoomUses").decimalValue()),
                            quantityText(metrics.path("paidHourlyRoomUses").decimalValue()));
                    if (breakdown.total().compareTo(number.decimalValue()) != 0)
                        return Evidence.blocked("INCOMPLETE", "PMS全部用房总间次与四类分项不一致，请核对重复计数或遗漏", expected);
                }
                String nights = quantityText(number.decimalValue());
                JsonNode def = row.path("roomUseDefinition");
                if (!"CONFIRMED".equals(def.path("verificationState").asText())
                        || !BASIS.equals(def.path("code").asText())
                        || !"INCLUDED".equals(def.path("hourlyRooms").asText())
                        || !"INCLUDED".equals(def.path("complimentaryRooms").asText())
                        || !"INCLUDED".equals(def.path("houseUseRooms").asText())
                        || !"ROOM_USE".equals(def.path("unit").asText())
                        || !"EACH_USE".equals(def.path("countingRule").asText())
                        || (fields.stream().anyMatch(metrics::hasNonNull)
                            && !"FREE_AND_HOUSE_USE_FIRST".equals(def.path("breakdownRule").asText()))
                        || !timezone.equals(def.path("timezone").asText())
                        || !def.path("businessDayCutoff").isTextual()
                        || !cutoff.equals(LocalTime.parse(def.path("businessDayCutoff").asText("00:00")))
                        || directoryText(def, "evidenceReference") == null
                        || !row.path("responseContentSha256").asText().matches("[a-fA-F0-9]{64}"))
                    return new Evidence("UNVERIFIED", "总间次仅供核对：需确认包含过夜、免费、自用和钟点房，每次使用计1次、同房多次不去重，并核对营业日来源；不要求提供四类明细或钟点折算",
                            null, nights, expected, expected, List.of(), mode, null, null, collected.toString(), null, null);
                String key = definitionKey(provider, timezone, cutoff);
                String label = "全部用房间次；含收费过夜房；含免费房；含自用房；含钟点房；"
                        + "每次使用计1间次，同房当天使用两次计2间次，不按房号去重、钟点不折算；" + timezone + " " + cutoff + "日切";
                return new Evidence(nights.equals("0") ? "ZERO_USES" : "READY", nights.equals("0") ? "PMS确认全部用房为0间次，不进行除法" : "PMS全部用房总间次及统计口径已核对",
                        nights, null, expected, expected, List.of(), mode, label, key, collected.toString(), digest(row.toString()), breakdown);
            } catch (RuntimeException exception) {
                return Evidence.blocked("UNVERIFIED", "PMS期间数据格式异常，请核对最新汇总；不会回退到旧数据冒充最新结果", expected);
            }
        }

        private static boolean exactCount(JsonNode value, int expected) {
            return value.isIntegralNumber() && value.canConvertToInt() && value.intValue() == expected;
        }

        private static boolean validQuantity(JsonNode value) {
            return value.isNumber() && value.decimalValue().signum() >= 0
                    && value.decimalValue().stripTrailingZeros().scale() <= 0
                    && value.decimalValue().compareTo(new BigDecimal("1000000000")) <= 0;
        }

        private static String definitionKey(String provider, String timezone, LocalTime cutoff) {
            // Length prefixes prevent delimiter collisions in publisher-controlled rule IDs or provider codes.
            // Optional category detail does not change the total-use denominator or comparability.
            var fields = List.of(BASIS, "ROOM_USE", "EACH_USE", provider, timezone, cutoff.toString());
            return digest(String.join("", fields.stream().map(value -> value.length() + ":" + value).toList()));
        }
    }

    private static String digest(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }
}
