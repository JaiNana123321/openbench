package edu.cwru.openbench.allocation;

import edu.cwru.openbench.common.Actor;
import edu.cwru.openbench.common.MeteredAllocationId;
import edu.cwru.openbench.common.PeriodId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Actual usage of Metered allocations (issue #24; the design's endpoint table names none):
 * {@code GET /api/usage?periodId=} lists a Period's allocations of the caller's types, and
 * {@code PUT /api/usage/{id}} with {@code {"usedUnits": 3}} replaces the units one consumed.
 */
@RestController
@RequestMapping("/api/usage")
class UsageController {

    private final UsageService usage;

    UsageController(UsageService usage) {
        this.usage = usage;
    }

    record UsedUnits(@NotNull @Min(0) Integer usedUnits) {}

    @GetMapping
    List<MeteredUsage> usage(Actor actor, @RequestParam PeriodId periodId) {
        return usage.usage(actor, periodId);
    }

    @PutMapping("/{id}")
    MeteredUsage record(Actor actor, @PathVariable MeteredAllocationId id, @Valid @RequestBody UsedUnits body) {
        return usage.recordUsage(actor, id, body.usedUnits());
    }
}
