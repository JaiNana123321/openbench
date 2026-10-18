package edu.cwru.openbench.inventory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * The kind of a Resource Type attribute, which decides what values a Resource may give it. Values
 * are stored as text and checked against the kind on every write. JSON: the upper-case name.
 */
public enum AttributeKind {
    /** Any text that is not blank. */
    TEXT,
    /** A decimal number, such as {@code 12} or {@code 2.5}. */
    NUMBER,
    /** An ISO date, such as {@code 2026-10-27}. */
    DATE,
    /** One of the attribute's allowed values, exactly as defined. */
    LIST;

    /** Whether a trimmed, non-blank value fits this kind; {@code allowedValues} is read for LIST only. */
    boolean accepts(String value, List<String> allowedValues) {
        return switch (this) {
            case TEXT -> true;
            case NUMBER -> parses(() -> new BigDecimal(value));
            case DATE -> parses(() -> LocalDate.parse(value));
            case LIST -> allowedValues.contains(value);
        };
    }

    private static boolean parses(Runnable parse) {
        try {
            parse.run();
            return true;
        } catch (NumberFormatException | DateTimeParseException e) {
            return false;
        }
    }
}
