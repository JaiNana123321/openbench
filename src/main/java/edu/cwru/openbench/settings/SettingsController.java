package edu.cwru.openbench.settings;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The Settings endpoints (design Table "Endpoints by module"); Demo 1 has the reads. */
@RestController
@RequestMapping("/api/settings")
class SettingsController {

    private final SettingsService settings;

    SettingsController(SettingsService settings) {
        this.settings = settings;
    }

    @GetMapping
    PlannerSettings settings() {
        return settings.viewSettings();
    }

    @GetMapping("/periods")
    List<Period> periods() {
        return settings.listPeriods();
    }
}
