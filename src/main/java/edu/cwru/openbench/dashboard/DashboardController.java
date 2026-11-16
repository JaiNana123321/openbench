package edu.cwru.openbench.dashboard;

import edu.cwru.openbench.common.Actor;
import edu.cwru.openbench.common.PeriodId;
import edu.cwru.openbench.common.ResourceTypeId;
import edu.cwru.openbench.common.TeamId;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Dashboard endpoints (design Table "Endpoints by module"). {@code periodId} defaults to the
 * current Period; {@code typeId} and {@code teamId} narrow a list to one row.
 */
@RestController
@RequestMapping("/api/dashboard")
class DashboardController {

    private final DashboardQueries dashboard;

    DashboardController(DashboardQueries dashboard) {
        this.dashboard = dashboard;
    }

    @GetMapping("/utilization")
    List<TypeUtilization> utilization(Actor actor, @RequestParam(required = false) PeriodId periodId,
            @RequestParam(required = false) ResourceTypeId typeId) {
        return dashboard.utilization(actor, periodId, typeId);
    }

    @GetMapping("/idle")
    List<TypeIdle> idle(Actor actor, @RequestParam(defaultValue = "7") @Min(1) @Max(366) int days,
            @RequestParam(required = false) ResourceTypeId typeId) {
        return dashboard.idleNextDays(actor, days, typeId);
    }

    @GetMapping("/waiting")
    List<TypeWaiting> waiting(Actor actor, @RequestParam(required = false) ResourceTypeId typeId) {
        return dashboard.waitingCount(actor, typeId);
    }

    @GetMapping("/credits")
    List<TeamCredits> credits(Actor actor, @RequestParam(required = false) PeriodId periodId,
            @RequestParam(required = false) TeamId teamId) {
        return dashboard.credits(actor, periodId, teamId);
    }

    @GetMapping("/person-days")
    List<PersonDays> personDays(Actor actor, @RequestParam(required = false) PeriodId periodId,
            @RequestParam(required = false) TeamId teamId) {
        return dashboard.personDays(actor, periodId, teamId);
    }

    @GetMapping("/usage")
    List<TeamTypeUsage> usage(Actor actor, @RequestParam(required = false) PeriodId periodId) {
        return dashboard.usage(actor, periodId);
    }
}
