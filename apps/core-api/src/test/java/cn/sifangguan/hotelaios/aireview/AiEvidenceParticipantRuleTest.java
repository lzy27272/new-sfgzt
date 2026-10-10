package cn.sifangguan.hotelaios.aireview;

import cn.sifangguan.hotelaios.shared.audit.AuditWriter;
import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import cn.sifangguan.hotelaios.shared.security.AccessPolicy;
import cn.sifangguan.hotelaios.shared.security.WorkVisibilityPolicy;
import cn.sifangguan.hotelaios.workdata.AttachmentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Database-free rule-selection and response-boundary regression tests.
 * The separate integration suite verifies SQL, RLS and attachment authorization. */
class AiEvidenceParticipantRuleTest {
    final UUID tenant=UUID.randomUUID(),actor=UUID.randomUUID(),hotel=UUID.randomUUID(),otherHotel=UUID.randomUUID();
    final UUID assistant=UUID.randomUUID(),front=UUID.randomUUID(),assignment=UUID.randomUUID();
    final NamedParameterJdbcTemplate jdbc=mock(NamedParameterJdbcTemplate.class);
    final TenantDatabaseContext db=mock(TenantDatabaseContext.class);
    final AccessPolicy access=mock(AccessPolicy.class);
    final AiVisionGateway gateway=mock(AiVisionGateway.class);
    final ObjectMapper json=new ObjectMapper();
    AiEvidenceService service(String pilots) {
        return new AiEvidenceService(jdbc,db,access,mock(WorkVisibilityPolicy.class),mock(AuditWriter.class),
                mock(AttachmentService.class),json,gateway,100,pilots);
    }
    Map<String,Object> rule(UUID position,String scope,UUID target,String item) throws Exception {
        return Map.of("id",UUID.randomUUID(),"position_id",position,"position_name","店助","version_no",1,"state","SHADOW",
                "configuration",json.writeValueAsString(Map.of("subjectScope",scope,"hotelIds",List.of(target),"itemCodes",List.of(item),
                    "criteria",List.of(Map.of("code","BADGE","enabled",true,"standard","无需工牌")),
                    "sampleIds",List.of(UUID.randomUUID()),"exceptions","按批准例外执行","sourceRuleVersion",13)));
    }
    Map<String,Object> record(UUID position,UUID target,String item) {
        return Map.of("position_id",position,"hotel_id",target,"item_code",item);
    }
    Map<String,Object> resolve(Map<String,Object> record,UUID subject,List<Map<String,Object>> rules) {
        return ReflectionTestUtils.invokeMethod(service(hotel.toString()),"resolveRule",record,subject,rules);
    }
    void principal(Set<UUID> owned,UUID selected) {
        when(access.principal()).thenReturn(new TenantPrincipal(tenant,actor,"FRONT_DESK",Set.of("FRONT_DESK"),
                Set.of("work-record.read"),Set.of(hotel),owned,selected,false,UUID.randomUUID()));
    }
    @Test void taskParticipantStandardOverridesSubjectPositionStandard() throws Exception {
        var participant=rule(assistant,"WORK_PARTICIPANTS",hotel,"AGM_NS_APPEARANCE_MEETING");
        var legacy=rule(front,"POSITION",hotel,"AGM_NS_APPEARANCE_MEETING");
        assertThat(resolve(record(assistant,hotel,"AGM_NS_APPEARANCE_MEETING"),front,List.of(legacy,participant)))
                .isSameAs(participant);
        verifyNoInteractions(gateway);
    }
    @Test void participantScopeNeverEscapesTaskOwnerHotelOrItem() throws Exception {
        var participant=rule(assistant,"WORK_PARTICIPANTS",hotel,"AGM_NS_APPEARANCE_MEETING");
        assertThat(resolve(record(front,hotel,"AGM_NS_APPEARANCE_MEETING"),front,List.of(participant))).isNull();
        assertThat(resolve(record(assistant,otherHotel,"AGM_NS_APPEARANCE_MEETING"),front,List.of(participant))).isNull();
        assertThat(resolve(record(assistant,hotel,"OTHER_WORK"),front,List.of(participant))).isNull();
    }
    @Test void legacyPositionScopeIsUnchangedAndCannotSelectOtherPositions() throws Exception {
        var legacy=rule(front,"POSITION",hotel,"GROOMING");
        assertThat(resolve(record(assistant,hotel,"GROOMING"),front,List.of(legacy))).isSameAs(legacy);
        assertThat(resolve(record(assistant,hotel,"GROOMING"),assistant,List.of(legacy))).isNull();
    }
    @Test void emptyPilotAllowlistFailsClosedEvenWhenRulesAreDistributedToAllStores() {
        assertThat((Boolean)ReflectionTestUtils.invokeMethod(service(""),"pilotHotel",hotel)).isFalse();
        assertThat((Boolean)ReflectionTestUtils.invokeMethod(service(hotel.toString()),"pilotHotel",hotel)).isTrue();
        assertThat((Boolean)ReflectionTestUtils.invokeMethod(service(hotel.toString()),"pilotHotel",otherHotel)).isFalse();
    }
    @Test void employeeReferenceIncludesOnlyOwnHotelAndSanitizedStandardFields() throws Exception {
        principal(Set.of(assignment),assignment);
        var participant=rule(assistant,"WORK_PARTICIPANTS",hotel,"GROOMING");
        var config=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree((String)participant.get("configuration"));
        config.putArray("hotelIds").add(hotel.toString()).add(otherHotel.toString());
        var source=new HashMap<>(participant);
        source.put("configuration",config.toString());source.put("created_by",UUID.randomUUID());
        when(jdbc.queryForList(anyString(),any(MapSqlParameterSource.class))).thenAnswer(invocation->{
            String sql=invocation.getArgument(0);
            MapSqlParameterSource params=invocation.getArgument(1);
            assertThat(params.getValue("tenant")).isEqualTo(tenant);
            if(sql.contains("employee_position_assignment")) {
                assertThat(params.getValue("actor")).isEqualTo(actor);
                assertThat(params.getValue("assignments")).isEqualTo(Set.of(assignment));
                assertThat(sql).contains("e.account_id=:actor","e.deleted_at is null","a.valid_from<=current_date");
                return List.of(Map.of("position_id",front,"id",hotel,"name","授权门店"));
            }
            return List.of(source,rule(assistant,"POSITION",hotel,"GROOMING"),rule(assistant,"WORK_PARTICIPANTS",otherHotel,"GROOMING"));
        });
        var result=service(hotel.toString()).myRuleReferences();
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().get("subjectScope")).isEqualTo("WORK_PARTICIPANTS");
        assertThat(result.getFirst().get("sourceRuleVersion")).isEqualTo(13);
        assertThat(result.getFirst().get("hotels")).isEqualTo(List.of(Map.of("id",hotel,"name","授权门店")));
        assertThat(json.writeValueAsString(result)).contains("无需工牌","规范样例 1")
                .doesNotContain(otherHotel.toString(),"created_by","storage_key","subject_name","work_record_id");
        verify(db).apply(tenant);verifyNoInteractions(gateway);
    }
    @Test void missingOrForgedBusinessAssignmentCannotReadStandards() {
        principal(Set.of(),null);
        assertThat(service(hotel.toString()).myRuleReferences()).isEmpty();
        principal(Set.of(assignment),UUID.randomUUID());
        assertThat(service(hotel.toString()).myRuleReferences()).isEmpty();
        verifyNoInteractions(jdbc,gateway);
    }
    @Test void optionalScopePreservesLegacyConfigurationContract() throws Exception {
        var legacy=new AiEvidenceModels.Configuration(List.of(hotel),List.of("GROOMING"),
                List.of(new AiEvidenceModels.Criterion("BADGE",true,"无需工牌")),"",List.of());
        assertThat(json.writeValueAsString(legacy)).doesNotContain("subjectScope","sourceRuleId","sourceRuleVersion");
        try(var factory=jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            var validator=factory.getValidator();
            var copied=new AiEvidenceModels.Configuration(legacy.hotelIds(),legacy.itemCodes(),legacy.criteria(),"",List.of(),
                    "WORK_PARTICIPANTS",UUID.randomUUID(),13);
            assertThat(validator.validate(copied)).isEmpty();
            var invalid=new AiEvidenceModels.Configuration(legacy.hotelIds(),legacy.itemCodes(),legacy.criteria(),"",List.of(),
                    "ANYONE",null,0);
            assertThat(validator.validate(invalid)).hasSize(2);
        }
    }
}
