package edu.cwru.openbench.settings;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cwru.openbench.common.Credits;
import edu.cwru.openbench.common.NotFoundException;
import edu.cwru.openbench.common.PeriodId;
import edu.cwru.openbench.common.ResourceTypeId;
import edu.cwru.openbench.common.RuleViolation;
import edu.cwru.openbench.common.TeamId;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Turns the key and body of {@code PUT /api/settings/{key}} into a {@link SettingChange} (design
 * Section 7). The body is always {@code {"value": ...}}, the value in the shape {@code GET
 * /api/settings} shows it:
 * <ul>
 * <li>{@code day_rate}, {@code grant.{teamId}.{periodId}}, {@code unit_price.{typeId}}: credits, such as {@code 450.00};
 * <li>{@code priority_premium}: a number, such as {@code 2.5};
 * <li>{@code objection_window_working_days}, {@code period_months}: a whole number;
 * <li>{@code quota.{teamId}.{typeId}}: a whole number, or {@code null} for no Quota;
 * <li>{@code bump_cutoff.{typeId}}: an ISO-8601 duration in whole hours, such as {@code "PT48H"};
 * <li>{@code price_bands.{typeId}}: every band of the type, {@code [{"weekdays": ["SATURDAY", "SUNDAY"],
 *     "start": "00:00", "end": "00:00", "multiplier": 0.25}]} (an end of 00:00 is midnight);
 * <li>{@code refund_steps.{typeId}}: every step of the Refund Schedule, {@code [{"minLead": "PT168H", "fraction": 0.5}]}.
 * </ul>
 * An unknown key is {@code NOT_FOUND}; a body without a value of the right shape is {@code VALIDATION_FAILED}.
 */
final class SettingKeys {

    private static final TypeReference<List<PriceBand>> BANDS = new TypeReference<>() {};
    private static final TypeReference<List<RefundStep>> STEPS = new TypeReference<>() {};

    private SettingKeys() {}

    static SettingChange parse(String key, JsonNode body, ObjectMapper json) {
        if (body == null || !body.isObject() || !body.has("value")) {
            throw invalid("The body must be {\"value\": ...} with the setting's new value.");
        }
        JsonNode value = body.get("value");
        String[] parts = key.split("\\.", -1);
        return switch (parts[0]) {
            case "day_rate" -> single(parts, key, () -> new SettingChange.DayRate(credits(value)));
            case "priority_premium" -> single(parts, key, () -> new SettingChange.PriorityPremium(decimal(value)));
            case "objection_window_working_days" -> single(parts, key, () -> new SettingChange.ObjectionWindow(whole(value)));
            case "period_months" -> single(parts, key, () -> new SettingChange.PeriodLength(whole(value)));
            case "grant" -> new SettingChange.GrantPlan(
                    new TeamId(id(parts, 1, 3, key)), new PeriodId(id(parts, 2, 3, key)), credits(value));
            case "quota" -> new SettingChange.Quota(new TeamId(id(parts, 1, 3, key)),
                    new ResourceTypeId(id(parts, 2, 3, key)),
                    value.isNull() ? Optional.empty() : Optional.of(whole(value)));
            case "unit_price" -> new SettingChange.UnitPrice(type(parts, key), credits(value));
            case "bump_cutoff" -> new SettingChange.BumpCutoff(type(parts, key), convert(value, json, Duration.class));
            case "price_bands" -> new SettingChange.PriceBands(type(parts, key), convert(value, json, BANDS));
            case "refund_steps" -> new SettingChange.Refunds(type(parts, key),
                    new RefundSchedule(convert(value, json, STEPS)));
            default -> throw unknown(key);
        };
    }

    private static SettingChange single(String[] parts, String key, java.util.function.Supplier<SettingChange> change) {
        if (parts.length != 1) {
            throw unknown(key);
        }
        return change.get();
    }

    private static ResourceTypeId type(String[] parts, String key) {
        return new ResourceTypeId(id(parts, 1, 2, key));
    }

    /** The id at {@code index} of a key with {@code length} parts, such as the 7 of {@code unit_price.7}. */
    private static long id(String[] parts, int index, int length, String key) {
        if (parts.length != length) {
            throw unknown(key);
        }
        try {
            return Long.parseLong(parts[index]);
        } catch (NumberFormatException e) {
            throw unknown(key);
        }
    }

    private static Credits credits(JsonNode value) {
        return new Credits(decimal(value));
    }

    private static BigDecimal decimal(JsonNode value) {
        if (!value.isNumber()) {
            throw invalid("The value must be a number.");
        }
        return value.decimalValue();
    }

    private static int whole(JsonNode value) {
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw invalid("The value must be a whole number.");
        }
        return value.intValue();
    }

    private static <T> T convert(JsonNode value, ObjectMapper json, Class<T> type) {
        try {
            T converted = json.convertValue(value, type);
            if (converted == null) {
                throw invalid("The value is missing.");
            }
            return converted;
        } catch (IllegalArgumentException e) {
            throw invalid("The value is not in the setting's shape.");
        }
    }

    private static <T> List<T> convert(JsonNode value, ObjectMapper json, TypeReference<List<T>> type) {
        try {
            List<T> converted = json.convertValue(value, type);
            if (converted == null || converted.contains(null)) {
                throw invalid("The value must be a list of the setting's entries.");
            }
            return converted;
        } catch (IllegalArgumentException e) {
            throw invalid("The value is not in the setting's shape.");
        }
    }

    private static NotFoundException unknown(String key) {
        return new NotFoundException("No setting is named " + key + ".");
    }

    private static RuleViolation invalid(String message) {
        return new RuleViolation("VALIDATION_FAILED", message,
                Map.of("errors", List.of(Map.of("field", "value", "message", message))));
    }
}
