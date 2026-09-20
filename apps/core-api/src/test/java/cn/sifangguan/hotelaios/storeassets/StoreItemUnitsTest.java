package cn.sifangguan.hotelaios.storeassets;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StoreItemUnitsTest {
    @Test
    void convertsPurchaseAndConsumptionToOneBaseUnitExactly() {
        StoreItemUnits units = new StoreItemUnits("毫升", "桶", new BigDecimal("5000"), "升", new BigDecimal("1000"));
        assertThat(units.baseQuantity(new BigDecimal("2.5"), "桶")).isEqualByComparingTo("12500");
        assertThat(units.baseQuantity(new BigDecimal("-0.15"), "升")).isEqualByComparingTo("-150");
        assertThat(units.baseQuantity(new BigDecimal("150"), null)).isEqualByComparingTo("150");
    }

    @Test
    void oldItemsDefaultToBaseUnitAndSameUnitNamesAreNotAmbiguous() {
        StoreItemUnits units = new StoreItemUnits(" 支 ", null, null, "", null);
        assertThat(units.purchaseUnit()).isEqualTo("支");
        assertThat(units.factorFor("支")).isEqualByComparingTo("1");
        assertThatThrownBy(() -> new StoreItemUnits("ML", "ml", BigDecimal.TEN, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("同名单位");
        assertThatThrownBy(() -> new StoreItemUnits("支", "箱", BigDecimal.TEN, "箱", BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("同名单位");
        assertThatThrownBy(() -> new StoreItemUnits("支", "箱", null, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("明确填写");
    }

    @Test
    void rejectsUnknownUnitsNonpositiveFactorsLossyQuantitiesAndOverflow() {
        StoreItemUnits units = new StoreItemUnits("升", "毫升", new BigDecimal("0.001"), null, null);
        assertThatThrownBy(() -> units.factorFor("瓶")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> units.baseQuantity(new BigDecimal("0.0001"), "毫升"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不能静默舍入");
        assertThatThrownBy(() -> units.baseQuantity(new BigDecimal("100000000000000"), "升"))
                .isInstanceOf(IllegalArgumentException.class);
        for (String factor : new String[]{"0", "-1", "0.0000001", "1000000000000"}) {
            assertThatThrownBy(() -> new StoreItemUnits("支", "箱", new BigDecimal(factor), null, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> StoreItemUnits.exact(new BigDecimal("1E+100000000"), 4, 14, "数量"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StoreItemUnits.exact(new BigDecimal("1E-100000000"), 4, 14, "数量"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
