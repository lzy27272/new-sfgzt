package cn.sifangguan.hotelaios.storeassets;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class StoreItemUsageScopesTest {
    private final StoreItemUsageScopes scopes = new StoreItemUsageScopes(mock(NamedParameterJdbcTemplate.class));
    private final UUID department = UUID.randomUUID(), position = UUID.randomUUID();
    private final StoreItemUsageScopes.Options options = new StoreItemUsageScopes.Options(
            List.of(new StoreItemUsageScopes.Option(department, "HK", "客房部")),
            List.of(new StoreItemUsageScopes.Option(position, "HKA", "客房服务员")));

    @Test void optionalPositionsAndExplicitAllDepartmentsHaveDifferentMeaningFromLegacy() {
        assertThat(scopes.validate(null, options)).isNull();
        assertThat(scopes.parse("客房部", "", options).departmentIds()).containsExactly(department);
        assertThat(scopes.parse("客房部", "", options).positionIds()).isEmpty();
        assertThat(scopes.parse("全店通用", "HKA", options).positionIds()).containsExactly(position);
        assertThat(scopes.parse("全店通用", "", options).mode()).isEqualTo("ALL_DEPARTMENTS");
    }

    @Test void rejectsAmbiguousNamesDuplicateSelectionsAndUnknownReferences() {
        var ambiguous = new StoreItemUsageScopes.Options(List.of(options.departments().getFirst(),
                new StoreItemUsageScopes.Option(UUID.randomUUID(), "HK2", "客房部")), options.positions());
        assertThatThrownBy(() -> scopes.parse("客房部", "", ambiguous)).hasMessageContaining("歧义");
        assertThat(scopes.parse("HK", "", ambiguous).departmentIds()).containsExactly(department);
        for (String raw : List.of("客房部;HK", "全店通用;客房部", "客房部;", "", "另一门店部门"))
            assertThatThrownBy(() -> scopes.parse(raw, "", options)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> scopes.parse("客房部", "未知岗位", options)).hasMessageContaining("有效岗位");
    }
}
