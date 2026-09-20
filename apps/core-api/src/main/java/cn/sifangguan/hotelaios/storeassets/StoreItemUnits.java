package cn.sifangguan.hotelaios.storeassets;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/** Deterministic per-item conversion. Never accept a client-supplied posting factor. */
record StoreItemUnits(String baseUnit, String purchaseUnit, BigDecimal purchaseFactor,
                      String issueUnit, BigDecimal issueFactor) {
    StoreItemUnits {
        baseUnit = name(baseUnit, "基础单位");
        purchaseUnit = purchaseUnit == null || purchaseUnit.isBlank() ? baseUnit : name(purchaseUnit, "采购单位");
        issueUnit = issueUnit == null || issueUnit.isBlank() ? baseUnit : name(issueUnit, "领用/耗用单位");
        if (!purchaseUnit.equalsIgnoreCase(baseUnit) && purchaseFactor == null
                || !issueUnit.equalsIgnoreCase(baseUnit) && issueFactor == null) {
            throw new IllegalArgumentException("非基础单位必须明确填写换算比例");
        }
        purchaseFactor = factor(purchaseFactor);
        issueFactor = factor(issueFactor);
        if (purchaseUnit.equalsIgnoreCase(baseUnit) && purchaseFactor.compareTo(BigDecimal.ONE) != 0
                || issueUnit.equalsIgnoreCase(baseUnit) && issueFactor.compareTo(BigDecimal.ONE) != 0
                || purchaseUnit.equalsIgnoreCase(issueUnit) && purchaseFactor.compareTo(issueFactor) != 0) {
            throw new IllegalArgumentException("同名单位必须使用相同换算比例，基础单位的比例必须为1");
        }
    }

    static StoreItemUnits from(Map<String, Object> item) {
        return new StoreItemUnits((String) item.get("unit"), (String) item.get("purchase_unit"),
                (BigDecimal) item.get("purchase_factor"), (String) item.get("issue_unit"),
                (BigDecimal) item.get("issue_factor"));
    }

    String selectedUnit(String input) {
        String requested = input == null || input.isBlank() ? baseUnit : input.trim();
        if (requested.equalsIgnoreCase(baseUnit)) return baseUnit;
        if (requested.equalsIgnoreCase(purchaseUnit)) return purchaseUnit;
        if (requested.equalsIgnoreCase(issueUnit)) return issueUnit;
        throw new IllegalArgumentException("所选单位未在该物品中配置，请刷新物品配置后重试");
    }

    BigDecimal factorFor(String input) {
        String selected = selectedUnit(input);
        if (selected.equals(baseUnit)) return BigDecimal.ONE;
        if (selected.equals(purchaseUnit)) return purchaseFactor;
        return issueFactor;
    }

    BigDecimal baseQuantity(BigDecimal input, String unit) {
        BigDecimal quantity = exact(input, 4, 14, "录入数量");
        return exact(quantity.multiply(factorFor(unit)), 4, 14, "换算后的基础数量");
    }

    static BigDecimal exact(BigDecimal value, int decimals, int integers, String label) {
        if (value == null) throw new IllegalArgumentException(label + "不能为空");
        try {
            if (value.signum() == 0) return BigDecimal.ZERO.setScale(decimals);
            // Bound exponents before allocating a potentially enormous rescaled decimal.
            if ((long) value.precision() - value.scale() > integers
                    || value.stripTrailingZeros().scale() > decimals) throw new ArithmeticException();
            BigDecimal result = value.setScale(decimals, RoundingMode.UNNECESSARY);
            if (result.precision() - result.scale() > integers) throw new ArithmeticException();
            return result;
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(label + "最多支持" + integers + "位整数和" + decimals + "位小数，不能静默舍入");
        }
    }

    private static BigDecimal factor(BigDecimal value) {
        BigDecimal result = exact(value == null ? BigDecimal.ONE : value, 6, 12, "换算比例");
        if (result.signum() <= 0) throw new IllegalArgumentException("换算比例必须大于0");
        return result;
    }

    private static String name(String value, String label) {
        if (value == null || value.isBlank() || value.trim().length() > 32) {
            throw new IllegalArgumentException(label + "须填写1至32个字符");
        }
        return value.trim();
    }
}
