package cn.sifangguan.hotelaios.aidata;
import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class AiDataSupportTest {
    @Test void coverageNeverTreatsMissingDaysAsZeroOrOpenPeriodAsComplete() {
        var query=new AiDataModels.Query("OTA_DAILY",LocalDate.of(2020,1,1),LocalDate.of(2020,1,2),null,UUID.randomUUID(),0);
        var first=Map.<String,Object>of("business_date","2020-01-01","completeness","COMPLETE");
        var second=Map.<String,Object>of("business_date","2020-01-02","completeness","COMPLETE");
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
