package com.felix.aigatecore.finance;

import com.felix.aigatecore.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** 固定精度财务运算；HTTP金额不经过浮点类型。 */
final class FinanceAmounts {
    private FinanceAmounts() {
    }

    static BigDecimal parse(String text, int scale) {
        if (text == null || !text.matches("(0|[1-9][0-9]{0," + (19 - scale) + "})\\.[0-9]{" + scale + "}")) {
            throw ApiException.invalid();
        }
        return new BigDecimal(text);
    }

    static BigDecimal charge(long input, long output, BigDecimal inputPrice, BigDecimal outputPrice,
        int scale, RoundingMode rounding) {
        if (input < 0 || output < 0 || inputPrice.signum() < 0 || outputPrice.signum() < 0) {
            throw ApiException.invalid();
        }
        BigDecimal result = inputPrice.multiply(BigDecimal.valueOf(input))
            .add(outputPrice.multiply(BigDecimal.valueOf(output))).divide(BigDecimal.valueOf(1000), scale, rounding);
        if (result.precision() > 20) {
            throw new ApiException(422, "BILLING_BOUND_UNVERIFIABLE", "Billing amount exceeds supported range");
        }
        return result;
    }

    static String text(BigDecimal value, int scale) {
        return value == null ? null : value.setScale(scale, RoundingMode.UNNECESSARY).toPlainString();
    }
}
