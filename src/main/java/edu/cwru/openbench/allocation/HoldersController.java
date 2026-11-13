package edu.cwru.openbench.allocation;

import edu.cwru.openbench.common.Actor;
import edu.cwru.openbench.common.PageResponse;
import edu.cwru.openbench.common.ResourceId;
import edu.cwru.openbench.common.ResourceTypeId;
import edu.cwru.openbench.inventory.Resource;
import edu.cwru.openbench.inventory.ResourceFilter;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The holder views under the Inventory's nouns (design Section 7, "Paths"): served by Allocation,
 * which owns the occurrences, since Inventory may not depend on it. {@code from} and {@code to} are
 * ISO-8601 instants with any offset, such as {@code 2026-11-18T08:00:00-05:00}.
 */
@RestController
@RequestMapping("/api/inventory/resources")
class HoldersController {

    /** Query parameters {@code attr.<name>=<value>} filter Resources by attribute value, as on the list. */
    private static final String ATTRIBUTE_PARAMETER = "attr.";

    private final HolderService holders;

    HoldersController(HolderService holders) {
        this.holders = holders;
    }

    @GetMapping("/{id}/holders")
    List<Holding> holders(Actor actor, @PathVariable ResourceId id, @RequestParam Instant from,
            @RequestParam Instant to) {
        return holders.holders(actor, id, from, to);
    }

    @GetMapping("/free")
    PageResponse<Resource> free(
            @RequestParam ResourceTypeId type,
            @RequestParam Instant from,
            @RequestParam Instant to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size,
            @RequestParam Map<String, String> parameters) {
        Map<String, String> attributes = new LinkedHashMap<>();
        parameters.forEach((name, value) -> {
            if (name.startsWith(ATTRIBUTE_PARAMETER)) {
                attributes.put(name.substring(ATTRIBUTE_PARAMETER.length()), value);
            }
        });
        return PageResponse.of(holders.free(new ResourceFilter(type, attributes, false), from, to, page, size));
    }
}
