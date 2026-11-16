package edu.cwru.openbench.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cwru.openbench.common.Actor;
import edu.cwru.openbench.common.Credits;
import edu.cwru.openbench.common.PageResponse;
import edu.cwru.openbench.common.PeriodId;
import edu.cwru.openbench.common.ResourceTypeId;
import edu.cwru.openbench.common.TeamId;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The Settings endpoints (design Table "Endpoints by module"), and the change log's read. */
@RestController
@RequestMapping("/api/settings")
class SettingsController {

    private final SettingsService settings;
    private final ObjectMapper json;

    SettingsController(SettingsService settings, ObjectMapper json) {
        this.settings = settings;
        this.json = json;
    }

    @GetMapping
    PlannerSettings settings() {
        return settings.viewSettings();
    }

    @GetMapping("/periods")
    List<Period> periods() {
        return settings.listPeriods();
    }

    /** A Metered type's service tiers, for any Member drafting a line; not in the design's table (issue #23). */
    @GetMapping("/tiers/{typeId}")
    List<ServiceTier> tiers(@PathVariable ResourceTypeId typeId) {
        return settings.listTiers(typeId);
    }

    @GetMapping("/grants/{periodId}")
    Map<TeamId, Credits> plannedGrants(@PathVariable PeriodId periodId) {
        return settings.viewPlannedGrants(periodId);
    }

    /** One setting's new value, as {@code {"value": ...}}; {@link SettingKeys} lists the keys and shapes. */
    @PutMapping("/{key}")
    PlannerSettings update(Actor actor, @PathVariable String key, @RequestBody(required = false) JsonNode body) {
        return settings.update(actor, SettingKeys.parse(key, body, json));
    }

    @GetMapping("/changes")
    PageResponse<SettingChangeEntry> changes(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return PageResponse.of(settings.changes(page, size));
    }
}
