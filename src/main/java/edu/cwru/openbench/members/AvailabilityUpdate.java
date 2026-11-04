package edu.cwru.openbench.members;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

/**
 * The body of PUT /api/members/{id}/availability: the Member's whole availability, which replaces
 * the saved ranges. A range is not a {@code DateRange} here, so a range that ends before it starts
 * reaches the service and is refused with {@code RANGE_INVALID} instead of failing to parse.
 */
public record AvailabilityUpdate(@NotNull @Size(max = 200) List<@NotNull @Valid Range> ranges) {

    /** Days, inclusive at both ends. JSON: {@code {"from": "2026-11-02", "to": "2026-11-06"}}. */
    public record Range(@NotNull LocalDate from, @NotNull LocalDate to) {}
}
