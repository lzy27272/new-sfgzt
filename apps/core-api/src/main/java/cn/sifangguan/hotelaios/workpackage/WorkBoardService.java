package cn.sifangguan.hotelaios.workpackage;

import cn.sifangguan.hotelaios.shared.security.AccessPolicy;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.OffsetDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Read-only operational boards. This deliberately does not invoke the legacy
 * workbench KPI snapshot writer: reviewing old work must not rewrite settlements.
 */
@Service
public class WorkBoardService {
    private final WorkPackageService work;
    private final NamedParameterJdbcTemplate jdbc;
    private final AccessPolicy access;

    public WorkBoardService(WorkPackageService work, NamedParameterJdbcTemplate jdbc, AccessPolicy access) {
        this.work = work;
        this.jdbc = jdbc;
        this.access = access;
    }

    // The four exclusive states are separate from the late-submission flag.
    static final String STATE_SQL = """
            case
              when coalesce(execution_policy ->> 'repeatableDailyRecords', 'false') = 'true'
                and pending_review_count > 0 and review_mode <> 'NONE' then 'REVIEW'
              when latest_record_status in ('REJECTED', 'RETURNED') then
                case when due_at <= :now then 'OVERDUE' else 'PENDING' end
              when status = 'FAILED' then
                case when due_at <= :now then 'OVERDUE' else 'PENDING' end
              when latest_record_status = 'SUBMITTED'
                and (review_mode = 'MANUAL'
                  or (review_mode = 'STANDARD_EVALUATION' and status <> 'SATISFIED')) then 'REVIEW'
              when latest_record_status = 'APPROVED' or status in ('SATISFIED', 'COMPLETED', 'APPROVED')
                or (review_mode = 'NONE' and status = 'SUBMITTED') then 'COMPLETED'
              when status = 'SUBMITTED' then 'REVIEW'
              when status in ('MISSED', 'OVERDUE') or due_at <= :now then 'OVERDUE'
              else 'PENDING'
            end
            """;

    private static final String COUNTS = """
            count(*) filter (where not optional_record and business_date <= :today) as expected,
            count(*) filter (where not optional_record and business_date <= :today and board_status = 'PENDING') as pending,
            count(*) filter (where not optional_record and business_date <= :today and board_status = 'OVERDUE') as overdue,
            count(*) filter (where not optional_record and business_date <= :today and board_status = 'REVIEW') as review,
            count(*) filter (where not optional_record and business_date <= :today and board_status = 'COMPLETED') as completed,
            count(*) filter (where not optional_record and business_date <= :today and board_status in ('REVIEW','COMPLETED')) as submitted,
            count(*) filter (where not optional_record and business_date <= :today and board_status = 'COMPLETED'
                              and valid_submitted_at <= due_at) as on_time_completed,
            count(*) filter (where not optional_record and business_date <= :today and board_status in ('REVIEW','COMPLETED')
                              and valid_submitted_at <= due_at) as on_time_submitted,
            count(*) filter (where not optional_record and business_date <= :today and is_late) as late,
            count(*) filter (where optional_record) as optional_records,
            coalesce(sum(submitted_record_count) filter (where optional_record),0) as optional_entries,
            coalesce(sum(pending_review_count) filter (where board_status='REVIEW'),0) as pending_review_records,
            count(*) as item_count
            """;

    @Transactional(readOnly = true)
    public Map<String, Object> board(boolean mine, UUID orgUnitId, UUID departmentId, UUID assignmentId,
                                     LocalDate from, LocalDate to, String status, String queue,
                                     boolean lateOnly, int page, int size) {
        if (mine) access.requireAnyPermission("work-record.read", "work-record.submit", "work.submit");
        else access.requireAnyPermission("work-record.team-read", "work-record.review");
        // Prepare the tenant context and reject unauthorized organization filters before resolving dates.
        WorkPackageService.ExpectationQuery contextQuery = work.expectationQuery(
                null, null, orgUnitId, null, null, null, mine, page, size, mine);
        var dayParams = contextQuery.params().addValue("boardDayOrg", orgUnitId)
                .addValue("boardDayAssignment", access.principal().businessActorAssignmentId());
        Map<String, Object> dayConfig = jdbc.queryForMap("""
                select coalesce(config.timezone, tenant.timezone) as timezone,
                       coalesce(config.cutoff_local_time,
                         case when hotel.id is null then time '00:00' else time '04:00' end) as cutoff
                from tenant
                left join employee_position_assignment assignment
                  on assignment.tenant_id = tenant.id and assignment.id = :boardDayAssignment
                left join lateral (
                  select unit.id from org_unit_closure closure
                  join org_unit unit on unit.tenant_id = closure.tenant_id and unit.id = closure.ancestor_id
                  where closure.tenant_id = tenant.id and unit.unit_type = 'HOTEL' and unit.status = 'ACTIVE'
                    and closure.descendant_id = coalesce(cast(:boardDayOrg as uuid), assignment.org_unit_id)
                  order by closure.depth limit 1
                ) hotel on true
                left join hotel_business_day_config config
                  on config.tenant_id = tenant.id and config.hotel_org_unit_id = hotel.id and config.status = 'ACTIVE'
                where tenant.id = :tenantId
                """, dayParams);
        var localNow = OffsetDateTime.now().atZoneSameInstant(ZoneId.of(dayConfig.get("timezone").toString()));
        LocalTime cutoff = ((java.sql.Time) dayConfig.get("cutoff")).toLocalTime();
        LocalDate today = localNow.toLocalTime().isBefore(cutoff)
                ? localNow.toLocalDate().minusDays(1) : localNow.toLocalDate();
        from = from == null ? today : from;
        to = to == null ? from : to;
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) > 365) {
            throw badRequest("请选择起止顺序正确、跨度不超过366天的日期");
        }
        status = status == null ? "ALL" : status;
        queue = queue == null ? "PERIOD" : queue;
        if (!Set.of("ALL", "PENDING", "OVERDUE", "REVIEW", "COMPLETED").contains(status)
                || !Set.of("PERIOD", "REVIEW", "CARRYOVER").contains(queue)) {
            throw badRequest("不支持的工作状态或工作队列");
        }
        // All scope/tenant checks, including delegated/shared work, come from the same query.
        WorkPackageService.ExpectationQuery query = work.expectationQuery(
                null, null, orgUnitId, null, null, null, mine, page, size, mine,
                from.isBefore(today.withDayOfMonth(1)) ? from : today.withDayOfMonth(1),
                to.isAfter(today) ? to : today);
        var params = query.params()
                .addValue("today", today).addValue("now", OffsetDateTime.now())
                .addValue("fromDate", from).addValue("toDate", to)
                .addValue("monthStart", today.withDayOfMonth(1))
                .addValue("boardStatus", status).addValue("boardQueue", queue)
                .addValue("lateOnly", lateOnly);
        String scopeFilter = "";
        if (departmentId != null) {
            access.requireOrgScope(departmentId);
            params.addValue("boardDepartment", departmentId);
            scopeFilter += " and department_org_unit_id = :boardDepartment";
        }
        if (assignmentId != null) {
            params.addValue("boardAssignment", assignmentId);
            scopeFilter += " and position_assignment_id = :boardAssignment";
        }
        String classified = "with source as (" + query.sql() + "), classified as (select source.*, "
                + STATE_SQL + " as board_status, "
                + "(not item_required or coalesce(execution_policy ->> 'repeatableDailyRecords', 'false') = 'true') as optional_record, "
                + "coalesce(valid_submitted_at > due_at, false) as is_late "
                + "from source where status not in ('WAIVED','CANCELLED')" + scopeFilter + ") ";
        String period = "business_date between :fromDate and :toDate";
        String review = "board_status = 'REVIEW' and business_date <= :today";
        String carryover = "business_date < :fromDate and business_date <= :today "
                + "and period_type <> 'DAY' and not optional_record and board_status in ('PENDING','OVERDUE')";
        String selected = " where ("
                + "(:boardQueue = 'PERIOD' and " + period + ")"
                + " or (:boardQueue = 'REVIEW' and " + review + ")"
                + " or (:boardQueue = 'CARRYOVER' and " + carryover + "))"
                + " and (:boardStatus = 'ALL' or board_status = :boardStatus)"
                + " and (not :lateOnly or is_late)";
        // Aggregation happens over the entire authorized scope before list pagination.
        String summarySql = classified
                + aggregate("period", period) + " union all "
                + aggregate("today", "business_date = :today") + " union all "
                + aggregate("month", "business_date between :monthStart and :today") + " union all "
                + aggregate("reviewQueue", review) + " union all "
                + aggregate("carryover", carryover) + " union all "
                + "select 'filtered' as dimension, null::text as group_id, null::text as group_name, "
                + COUNTS + " from classified" + selected + " union all "
                + groupAggregate("hotel", "hotel_org_unit_id", "hotel_name", period) + " union all "
                + groupAggregate("department", "department_org_unit_id", "coalesce(department_name, '直属岗位')", period) + " union all "
                + groupAggregate("employee", "position_assignment_id", "employee_name || ' · ' || position_name", period);
        List<Map<String, Object>> aggregates = jdbc.queryForList(summarySql, params);
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> groups = new ArrayList<>();
        for (Map<String, Object> aggregate : aggregates) {
            String dimension = (String) aggregate.remove("dimension");
            addRates(aggregate);
            if (Set.of("hotel", "department", "employee").contains(dimension)) {
                aggregate.put("dimension", dimension);
                groups.add(aggregate);
            } else result.put(dimension, aggregate);
        }
        result.put("groups", groups);
        result.put("items", jdbc.queryForList(classified + "select * from classified" + selected
                + " order by case board_status when 'REVIEW' then 0 when 'OVERDUE' then 1 else 2 end, "
                + "latest_submitted_at desc nulls last, due_at, id limit :pageSize offset :pageOffset", params));
        result.put("recentSubmissions", jdbc.queryForList(classified + "select * from classified where "
                + "latest_submitted_at between :boardRecentSince and :now"
                + " order by latest_submitted_at desc, id limit 5", params));
        result.put("upcomingItems", jdbc.queryForList(classified + "select * from classified where "
                + period + " and not optional_record and board_status='PENDING'"
                + " order by due_at, id limit 3", params));
        result.put("businessDate", today);
        result.put("fromDate", from);
        result.put("toDate", to);
        result.put("page", page);
        result.put("size", size);
        return result;
    }

    private static String aggregate(String name, String condition) {
        return "select '" + name + "' as dimension, null::text as group_id, null::text as group_name, "
                + COUNTS + " from classified where " + condition;
    }

    private static String groupAggregate(String dimension, String id, String name, String condition) {
        return "select '" + dimension + "' as dimension, " + id + "::text as group_id, "
                + name + " as group_name, " + COUNTS + " from classified where " + condition
                + " group by " + id + ", " + name;
    }

    private static void addRates(Map<String, Object> metric) {
        long expected = ((Number) metric.get("expected")).longValue();
        for (String field : List.of("completed", "submitted", "on_time_completed", "on_time_submitted")) {
            long value = ((Number) metric.get(field)).longValue();
            metric.put(field + "_rate", expected == 0 ? null : Math.round(value * 1000.0 / expected) / 10.0);
        }
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
