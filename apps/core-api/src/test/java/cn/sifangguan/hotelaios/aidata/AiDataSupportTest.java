package cn.sifangguan.hotelaios.aidata;
import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class AiDataSupportTest {
    @Test void evidenceHashSurvivesJsonbOrderingAndDecimalNormalization() throws Exception {
        var json=new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        var a=json.readTree("{\"z\":1234567890123456.12345678,\"a\":[1,2.00,null]}");
        var b=json.readTree("{ \"a\": [1.0, 2, null], \"z\": 1234567890123456.123456780 }");
        assertThat(AiDataSupport.dataHash(json,a)).isEqualTo(AiDataSupport.dataHash(json,b));
        assertThat(AiDataSupport.dataHash(json,a)).isNotEqualTo(AiDataSupport.dataHash(json,json.readTree("{\"a\":[2,1,null],\"z\":1234567890123456.12345678}")));
    }
    @Test void coverageNeverTreatsMissingDaysAsZeroOrOpenPeriodAsComplete() {
        var query=new AiDataModels.Query("OTA_DAILY",LocalDate.of(2020,1,1),LocalDate.of(2020,1,2),null,UUID.randomUUID(),0);
        var first=Map.<String,Object>of("business_date","2020-01-01","completeness","COMPLETE","room_revenue",100,"sold_room_nights",2,"effective_sellable_room_nights",5);
        var second=Map.<String,Object>of("business_date","2020-01-02","completeness","COMPLETE","room_revenue",100,"sold_room_nights",2,"effective_sellable_room_nights",5);
        assertThat(AiDataService.coverage(List.of(first),query,false)).containsEntry("state","PARTIAL").containsEntry("missingIsZero",false);
        assertThat(AiDataService.coverage(List.of(first,second),query,false)).containsEntry("state","COMPLETE");
        assertThat(AiDataService.coverage(List.of(first,second),query,true)).containsEntry("state","PARTIAL");
    }
    @Test void permissionFingerprintIncludesAssignmentAndScopesNotRequestCorrelation() {
        UUID tenant=UUID.randomUUID(),actor=UUID.randomUUID(),org=UUID.randomUUID();
        var first=new TenantPrincipal(tenant,actor,"EMPLOYEE",Set.of("EMPLOYEE"),Set.of("work-record.read"),Set.of(org),Set.of(),false,UUID.randomUUID());
        var same=new TenantPrincipal(tenant,actor,"EMPLOYEE",Set.of("EMPLOYEE"),Set.of("work-record.read"),Set.of(org),Set.of(),false,UUID.randomUUID());
        var broader=new TenantPrincipal(tenant,actor,"EMPLOYEE",Set.of("EMPLOYEE"),Set.of("work-record.read"),Set.of(org),Set.of(),true,UUID.randomUUID());
        assertThat(AiDataSupport.scope(first)).isEqualTo(AiDataSupport.scope(same)).isNotEqualTo(AiDataSupport.scope(broader));
        assertThat(AiDataService.allowed(first,"WORK_RECORDS")).isTrue();
        assertThat(AiDataService.allowed(first,"KPI_FACTS")).isFalse();
    }
}
