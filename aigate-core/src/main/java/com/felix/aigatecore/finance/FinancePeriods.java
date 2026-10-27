package com.felix.aigatecore.finance;

import com.felix.aigatecore.web.ApiException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** 公司财务周期；显式配置IANA时区，不使用JVM或宿主机默认值。 */
@Component
public class FinancePeriods {
    private final ZoneId zone;

    /**
     * 校验公司财务时区。
     *
     * @param environment 受控服务配置
     */
    public FinancePeriods(Environment environment) {
        String configured = environment.getRequiredProperty("AIGATE_COMPANY_ZONE_ID");
        if (!ZoneId.getAvailableZoneIds().contains(configured)) {
            throw new IllegalStateException("AIGATE_COMPANY_ZONE_ID must be an IANA zone");
        }
        zone = ZoneId.of(configured);
    }

    record Period(Instant start, Instant end) {
    }

    Period week(Instant at) {
        var date = at.atZone(zone).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return new Period(date.atStartOfDay(zone).toInstant(), date.plusWeeks(1).atStartOfDay(zone).toInstant());
    }

    Period month(Instant at) {
        return month(YearMonth.from(at.atZone(zone)).toString());
    }

    Period month(String value) {
        try {
            if (value == null || !value.matches("[1-9][0-9]{3}-(0[1-9]|1[0-2])")) {
                throw ApiException.invalid();
            }
            var month = YearMonth.parse(value);
            return new Period(month.atDay(1).atStartOfDay(zone).toInstant(),
                month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant());
        } catch (java.time.DateTimeException failure) {
            throw ApiException.invalid();
        }
    }

    String monthName(Instant start) {
        return YearMonth.from(start.atZone(zone)).toString();
    }
}
