package cn.sifangguan.hotelaios.organization;

import java.util.Set;

public final class EmployeeType {
    private EmployeeType() {}
    public static String validate(String value, boolean required) {
        if (value == null || value.isBlank()) {
            if (required) throw new IllegalArgumentException("请选择员工类型：实习员工、正式员工或第三方委派（兼职）");
            return null;
        }
        if (!Set.of("INTERN", "REGULAR", "SECONDMENT").contains(value))
            throw new IllegalArgumentException("员工类型只能为实习员工、正式员工或第三方委派（兼职）");
        return value;
    }
}
