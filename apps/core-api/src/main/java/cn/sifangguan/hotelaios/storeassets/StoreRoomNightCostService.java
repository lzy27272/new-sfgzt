package cn.sifangguan.hotelaios.storeassets;

import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import cn.sifangguan.hotelaios.shared.security.AccessPolicy;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;

/** Read-only management ratios: immutable posting facts divided by confirmed PMS room usage counts. */
@Service
public class StoreRoomNightCostService {
    private static final List<String> KINDS = List.of("CONSUMABLE", "CLEANING_SUPPLY", "LAUNDRY");
    private final NamedParameterJdbcTemplate jdbc;
    private final TenantDatabaseContext database;
    private final AccessPolicy access;
    private final PmsRoomNightSource source;

    public StoreRoomNightCostService(NamedParameterJdbcTemplate jdbc, TenantDatabaseContext database,
                                    AccessPolicy access, PmsRoomNightSource source) {
        this.jdbc = jdbc; this.database = database; this.access = access; this.source = source;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Analysis analyze(UUID hotelId, String periodType, String value) {
        access.requirePermission("store-resource.read");
        var principal = access.principal();
        database.apply(principal.tenantId());
        access.requireOrgScope(hotelId);
        var params = new MapSqlParameterSource("tenantId", principal.tenantId()).addValue("hotelId", hotelId);
        var configs = jdbc.query("""
                select p.id as profile_id,p.property_code,t.timezone as cost_timezone,
                  coalesce(b.timezone,t.timezone) as business_timezone,
                  coalesce(b.cutoff_local_time,cast('04:00' as time)) as cutoff,
                  coalesce(b.closing_grace_minutes,0) as grace
                from org_unit o join tenant t on t.id=o.tenant_id
                left join hotel_profile p on p.tenant_id=o.tenant_id and p.org_unit_id=o.id
                left join hotel_business_day_config b on b.tenant_id=o.tenant_id and b.hotel_org_unit_id=o.id and b.status='ACTIVE'
                where o.tenant_id=:tenantId and o.id=:hotelId and o.unit_type='HOTEL'
                """, params, (rs, row) -> new Config(rs.getObject("profile_id", UUID.class), rs.getString("property_code"),
                rs.getString("cost_timezone"), rs.getString("business_timezone"), rs.getObject("cutoff", LocalTime.class), rs.getInt("grace")));
        if (configs.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "门店不存在");
        Config config = configs.getFirst();
        Instant now = Instant.now();
        ZonedDateTime localNow = now.atZone(ZoneId.of(config.businessTimezone()));
        LocalDate today = localNow.toLocalDate();
        LocalDate latestClosedDay = latestClosedDay(localNow, config.cutoff(), config.grace());
        Range range = requested(periodType, value, today, latestClosedDay);
        var dataset = source.read(principal.tenantId(), config.profileId(), config.propertyCode(), config.businessTimezone(), config.cutoff(), latestClosedDay, now);
        Window current = window(range, params, dataset, config);
        Range previousRange = comparisonRange(range, periodType, false);
        Range yearRange = comparisonRange(range, periodType, true);
        Window previous = window(previousRange, params, dataset, config);
        Window yearAgo = window(yearRange, params, dataset, config);
        List<Comparison> comparisons = new ArrayList<>();
        for (int i = 0; i < KINDS.size(); i++) comparisons.add(new Comparison(KINDS.get(i),
                compare(current, previous, i), compare(current, yearAgo, i)));
        return new Analysis(periodType, "DAY".equals(periodType) ? range.from().toString() : YearMonth.from(range.from()).toString(),
                YearMonth.from(today).toString(), latestClosedDay.toString(), config.businessTimezone(), config.costTimezone(),
                now.toString(), PmsRoomNightSource.BASIS, current, previous, yearAgo, comparisons, List.of(
                "按同日期范围的入账成本 ÷ PMS全部用房总间次计算（元/间次），包含过夜、免费、自用和钟点房。仅为门店管理摊分，不是全店经营总成本。",
                "同房当天使用两次计2间次，不按房号去重；钟点每次使用计1间次，不按时长折算。已核对的全部用房总间次即可计算，不强制提供四类明细。",
                "月成本按当期总成本 ÷ 当期累计用房间次计算，不平均每日单价。可选分项须互斥且合计一致，缺失不补0；分项只说明数量，不代表各类型独立成本。",
                "旧的过夜房晚、钟点折合房晚不直接当作间次，不与新口径混算同比环比；历史库存流水、月结及KPI不因本次口径修改而重写。",
                "三类分别计算：耗品、清洁用品、合格回收洗涤。报损、盘点调整、固定资产及未验收洗涤费不混入。",
                "当前月截至最近已日结营业日；按月环比为上月同期，按日环比为前一日，同比为去年同期；月底和闰年按对应月份末日截齐。",
                "PMS为最新已发布脱敏汇总的只读分析，未写入成本月结或正式KPI。PMS修订可能改变分析值，原成本月结不变；请按采集时间和来源指纹追溯。"));
    }

    static LocalDate latestClosedDay(ZonedDateTime now, LocalTime cutoff, int graceMinutes) {
        ZonedDateTime effective = now.minusMinutes(graceMinutes);
        LocalDate boundary = effective.toLocalDate();
        if (effective.isBefore(boundary.atTime(cutoff).atZone(now.getZone()))) boundary = boundary.minusDays(1);
        return boundary.minusDays(1);
    }

    private Range requested(String type, String value, LocalDate today, LocalDate lastClosed) {
        if (!Set.of("DAY", "MONTH").contains(type)) throw new IllegalArgumentException("统计方式只能选择日或月");
        try {
            if ("DAY".equals(type)) {
                if (value != null && !value.matches("\\d{4}-\\d{2}-\\d{2}")) throw new IllegalArgumentException();
                LocalDate day = value == null ? lastClosed : LocalDate.parse(value);
                if (day.getYear() < 2000 || day.isAfter(today)) throw new IllegalArgumentException();
                return new Range(day, day, true);
            }
            if (value != null && !value.matches("\\d{4}-\\d{2}")) throw new IllegalArgumentException();
            YearMonth month = value == null ? YearMonth.from(today) : YearMonth.parse(value);
            if (month.getYear() < 2000 || month.isAfter(YearMonth.from(today))) throw new IllegalArgumentException();
            LocalDate end = month.atEndOfMonth().isAfter(lastClosed) ? lastClosed : month.atEndOfMonth();
            return new Range(month.atDay(1), end, end.equals(month.atEndOfMonth()));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("日期格式或范围无效：日用YYYY-MM-DD、月用YYYY-MM，仅支持2000年起至当前日期");
        }
    }

    static Range comparisonRange(Range current, String type, boolean yearly) {
        if ("DAY".equals(type)) {
            LocalDate date = yearly ? current.from().minusYears(1) : current.from().minusDays(1);
            return new Range(date, date, true);
        }
        YearMonth month = YearMonth.from(current.from());
        YearMonth other = yearly ? month.minusYears(1) : month.minusMonths(1);
        if (current.to().isBefore(current.from())) return new Range(other.atDay(1), other.atDay(1).minusDays(1), false);
        LocalDate end = current.fullMonth() ? other.atEndOfMonth() : other.atDay(Math.min(current.to().getDayOfMonth(), other.lengthOfMonth()));
        return new Range(other.atDay(1), end, current.fullMonth());
    }

    private Window window(Range range, MapSqlParameterSource base, PmsRoomNightSource.Dataset dataset, Config config) {
        return window(range, base, dataset, config, false);
    }

    private Window window(Range range, MapSqlParameterSource base, PmsRoomNightSource.Dataset dataset, Config config, boolean businessBasis) {
        var p = new MapSqlParameterSource(base.getValues()).addValue("from", range.from()).addValue("until", range.to().plusDays(1));
        var evidence = dataset.period(range.from(), range.to());
        if (!config.costTimezone().equals(config.businessTimezone()))
            evidence = PmsRoomNightSource.Evidence.blocked("DATE_BASIS_MISMATCH", "成本记账与门店营业日时区不同，需先核对日期归属", evidence.expectedDays());
        String costSql = """
                select cost_kind,sum(amount) as amount,count(*) as entry_count,
                  count(*) filter(where amount=0 and direction=1 and not exists(select 1 from store_cost_entry revoked
                    where revoked.tenant_id=store_cost_entry.tenant_id and revoked.reverses_entry_id=store_cost_entry.id)) as zero_count,
                  count(*) filter(where legacy_classification and not exists(select 1 from store_cost_entry revoked
                    where revoked.tenant_id=store_cost_entry.tenant_id and revoked.reverses_entry_id=store_cost_entry.id)) as legacy_count,
                  count(*) filter(where business_date<>posting_date) as shifted_count
                from store_cost_entry where tenant_id=:tenantId and hotel_org_unit_id=:hotelId
                  and posting_date>=:from and posting_date<:until
                  and cost_kind in ('CONSUMABLE','CLEANING_SUPPLY','LAUNDRY') group by cost_kind
                """;
        // This is a server-chosen column, never interpolated user input.
        if (businessBasis) costSql=costSql.replace("and posting_date>=:from and posting_date<:until", "and business_date>=:from and business_date<:until");
        List<Map<String, Object>> rows = jdbc.queryForList(costSql, p);
        long pending = jdbc.queryForObject("""
                select count(*) from store_work_usage u join work_record r on r.tenant_id=u.tenant_id and r.id=u.work_record_id
                where u.tenant_id=:tenantId and u.hotel_org_unit_id=:hotelId and u.status='PENDING'
                  and r.business_date>=:from and r.business_date<:until
                """, p, Long.class);
        boolean closed = Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from store_cost_month_close where tenant_id=:tenantId and hotel_org_unit_id=:hotelId
                  and month_start=:month)
                """, p.addValue("month", range.from().withDayOfMonth(1)), Boolean.class));
        List<Metric> metrics = new ArrayList<>();
        long shifted = 0;
        for (String kind : KINDS) {
            Map<String,Object> row = rows.stream().filter(item -> kind.equals(item.get("cost_kind"))).findFirst().orElse(null);
            long count = count(row, "entry_count"), zero = count(row, "zero_count"), legacy = count(row, "legacy_count");
            shifted += count(row, "shifted_count");
            BigDecimal amount = row == null ? BigDecimal.ZERO : (BigDecimal) row.get("amount");
            String state = "READY", reason = "按已入账成本计算，未包含未录入或未验收业务";
            if (!evidence.usable()) { state = evidence.state(); reason = evidence.reason(); }
            else if (count == 0) { state = "NO_COST_RECORDS"; reason = "本类没有入账记录，不能认定实际成本为0"; }
            else if (pending > 0 && !"LAUNDRY".equals(kind)) { state = "PENDING_USAGE"; reason = "本期仍有待审核耗用，暂不计算耗品及清洁用品每间次成本"; }
            else if (zero > 0) { state = "ZERO_AMOUNT_REVIEW"; reason = "存在零金额原始成本，请先核对；不将缺单价当作免费"; }
            else if (legacy > 0) { state = "LEGACY_REVIEW"; reason = "包含分类尚未核对的历史成本，暂不用于每间次成本比较"; }
            BigDecimal unit = "READY".equals(state) ? amount.divide(new BigDecimal(evidence.roomUses()), 4, RoundingMode.HALF_UP) : null;
            metrics.add(new Metric(kind, amount.setScale(4).toPlainString(), count, zero, legacy, state, reason, unit == null ? null : unit.toPlainString()));
        }
        return new Window(range.from().toString(), range.to().toString(), closed, pending, shifted, evidence, metrics);
    }

    private Change compare(Window current, Window baseline, int index) {
        Metric a = current.costs().get(index), b = baseline.costs().get(index);
        if (!"READY".equals(a.state()) || !"READY".equals(b.state()))
            return new Change(null, "NOT_COMPARABLE", "本期或对比期缺少通过核对的用房间次或成本，暂不计算涨跌幅");
        if (!Objects.equals(current.source().definitionKey(), baseline.source().definitionKey()))
            return new Change(null, "DEFINITION_CHANGED", "两个期间的PMS用房间次口径不同，不能直接比较");
        BigDecimal amount = new BigDecimal(a.amount()), prior = new BigDecimal(b.amount());
        if (prior.signum() <= 0 || amount.signum() < 0)
            return new Change(null, "NON_POSITIVE_BASE", "基期为0或存在净负成本，冲销期间不计算百分比");
        // Compare exact ratios, not their four-decimal display values.
        BigDecimal left = amount.multiply(new BigDecimal(baseline.source().roomUses()));
        BigDecimal right = prior.multiply(new BigDecimal(current.source().roomUses()));
        return new Change(left.subtract(right).multiply(BigDecimal.valueOf(100)).divide(right, 2, RoundingMode.HALF_UP).toPlainString(), "READY", "正数为成本上升，负数为下降；需结合经营及冲销原因判断");
    }

    private long count(Map<String,Object> row, String key) { return row == null ? 0 : ((Number) row.get(key)).longValue(); }

    /** Live operating view uses original business dates, unlike immutable posting-date financial closes. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public OperatingSummary operating(UUID hotelId, String type, String selection, String from, String to) {
        access.requirePermission("daily-operation.read");
        access.requirePermission("store-resource.read");
        var principal=access.principal(); database.apply(principal.tenantId()); access.requireOrgScope(hotelId);
        var p=new MapSqlParameterSource("tenantId",principal.tenantId()).addValue("hotelId",hotelId);
        List<Config> configs=jdbc.query("""
                select hp.id as profile_id,hp.property_code,t.timezone as cost_timezone,
                  coalesce(b.timezone,t.timezone) as business_timezone,
                  coalesce(b.cutoff_local_time,cast('04:00' as time)) as cutoff,coalesce(b.closing_grace_minutes,0) as grace
                from org_unit o join tenant t on t.id=o.tenant_id
                left join hotel_profile hp on hp.tenant_id=o.tenant_id and hp.org_unit_id=o.id
                left join hotel_business_day_config b on b.tenant_id=o.tenant_id and b.hotel_org_unit_id=o.id and b.status='ACTIVE'
                where o.tenant_id=:tenantId and o.id=:hotelId and o.unit_type='HOTEL' and o.status='ACTIVE'
                """,p,(rs,row)->new Config(rs.getObject("profile_id",UUID.class),rs.getString("property_code"),rs.getString("cost_timezone"),
                rs.getString("business_timezone"),rs.getObject("cutoff",LocalTime.class),rs.getInt("grace")));
        if (configs.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"门店不存在或已停用");
        Config config=configs.getFirst(); Instant now=Instant.now();
        ZonedDateTime local=now.atZone(ZoneId.of(config.businessTimezone())); LocalDate today=local.toLocalDate(), yesterday=today.minusDays(1);
        LocalDate closed=latestClosedDay(local,config.cutoff(),config.grace());
        Range requested;
        if ("CUSTOM".equals(type)) {
            try {
                if (from==null || to==null || !from.matches("\\d{4}-\\d{2}-\\d{2}") || !to.matches("\\d{4}-\\d{2}-\\d{2}")) throw new IllegalArgumentException();
                LocalDate start=LocalDate.parse(from),end=LocalDate.parse(to);
                if (start.getYear()<2000 || end.isBefore(start) || end.isAfter(yesterday) || java.time.temporal.ChronoUnit.DAYS.between(start,end)>1095) throw new IllegalArgumentException();
                requested=new Range(start,end,false);
            } catch (RuntimeException error) { throw new IllegalArgumentException("自定义周期须为有效日期，开始不晚于结束，截止昨日，最多1096天"); }
        } else requested=requested(type,selection,today,yesterday);
        if (requested.to().isAfter(yesterday)) throw new IllegalArgumentException("成本汇总只查询截至昨日的数据");
        var data=source.read(principal.tenantId(),config.profileId(),config.propertyCode(),config.businessTimezone(),config.cutoff(),closed,now);
        Range month=new Range(today.withDayOfMonth(1),yesterday,false);
        return new OperatingSummary(hotelId.toString(),today.toString(),yesterday.toString(),closed.toString(),now.toString(),
                PmsRoomNightSource.BASIS,"BUSINESS_DATE_LIVE",summarize(window(new Range(yesterday,yesterday,false),p,data,config,true)),
                summarize(window(month,p,data,config,true)),summarize(window(requested,p,data,config,true)),List.of(
                "成本范围为已入账耗品、清洁用品和合格回收布草洗涤费；不包含人工、房租、能耗、资产折旧，不等于全店总经营成本。",
                "员工提交先待审核，审核通过后才扣库及记成本。布草使用数量是周转记录，只有洗涤回收验收确认的费用才计洗涤成本。",
                "上级更正采用冲销原值并重新记账；这里按原营业日归属实时更新，原始提交和已关账财务月结保留不变。",
                "单房开房成本按已确认总成本÷PMS全部用房间次计算；同一房间使用两次算2间次。月累计除以月累计间次，不平均每日单价。",
                "没有成本记录、PMS缺数或口径未确认时显示不可计算，不补零；有待审核、未核对零金额或历史分类时不发布单房成本。",
                "昨日按门店当地日历日显示，当前月从1日截至昨日；月初尚无本月已结束日期时显示无记录，不挪用上月数据。"));
    }

    private OperatingWindow summarize(Window value) {
        long entries=value.costs().stream().mapToLong(Metric::entryCount).sum();
        BigDecimal total=value.costs().stream().map(m->new BigDecimal(m.amount())).reduce(BigDecimal.ZERO,BigDecimal::add);
        boolean quality=value.pendingUsageCount()==0 && value.costs().stream().allMatch(m->m.zeroAmountCount()==0 && m.legacyCount()==0);
        boolean usable=entries>0 && quality && value.source().usable() && total.signum()>=0;
        String reason=entries==0?"暂无已入账成本，不能认定为0":!quality?"存在待审核耗用、零金额或待核对历史成本，单房成本暂不可计算":!value.source().usable()?value.source().reason():total.signum()<0?"当前周期净冲销为负，请先核对原始业务":"按已入账的三类成本合计计算，不包含尚未录入或验收的业务";
        return new OperatingWindow(value.from(),value.to(),entries==0?null:total.setScale(4).toPlainString(),
                usable?total.divide(new BigDecimal(value.source().roomUses()),4,RoundingMode.HALF_UP).toPlainString():null,
                usable?"READY":entries==0?"NO_COST_RECORDS":!quality?"COST_REVIEW_REQUIRED":total.signum()<0?"NEGATIVE_NET_COST":value.source().state(),reason,value);
    }

    public record OperatingWindow(String from,String to,String amount,String perRoomUse,String state,String reason,Window detail) { }
    public record OperatingSummary(String hotelId,String today,String yesterdayDate,String latestClosedDay,String generatedAt,String roomUseBasis,
            String dateBasis,OperatingWindow yesterday,OperatingWindow monthToYesterday,OperatingWindow selected,List<String> notices) { }
    private record Config(UUID profileId, String propertyCode, String costTimezone, String businessTimezone, LocalTime cutoff, int grace) { }
    record Range(LocalDate from, LocalDate to, boolean fullMonth) { }
    public record Metric(String kind, String amount, long entryCount, long zeroAmountCount, long legacyCount, String state, String reason, String perRoomUse) { }
    public record Window(String from, String to, boolean costMonthClosed, long pendingUsageCount, long shiftedEntryCount, PmsRoomNightSource.Evidence source, List<Metric> costs) { }
    public record Change(String percent, String state, String reason) { }
    public record Comparison(String kind, Change previous, Change yearAgo) { }
    public record Analysis(String periodType, String selection, String currentMonth, String latestClosedDay, String businessTimezone, String costTimezone,
                           String generatedAt, String roomUseBasis, Window current, Window previous, Window yearAgo, List<Comparison> comparisons, List<String> notices) { }
}
