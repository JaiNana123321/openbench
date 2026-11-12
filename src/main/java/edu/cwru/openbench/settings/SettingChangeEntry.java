package edu.cwru.openbench.settings;

import edu.cwru.openbench.common.MemberId;
import java.time.Instant;

/**
 * One entry of the setting-change log (Admin.Member.Create): the setting's key, as in
 * {@code PUT /api/settings/{key}}, the value before and after as readable text (null when the
 * setting was not set), the Capacity Planner who changed it, and when. JSON:
 * {@code {"id": 7, "key": "day_rate", "oldValue": "400.00", "newValue": "450.00", "actor": 8,
 * "actorName": "Erin Walsh", "at": "2026-10-12T09:00:00-04:00"}}.
 */
public record SettingChangeEntry(
        long id, String key, String oldValue, String newValue, MemberId actor, String actorName, Instant at) {}
