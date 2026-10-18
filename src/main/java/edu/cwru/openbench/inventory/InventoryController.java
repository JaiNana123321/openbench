package edu.cwru.openbench.inventory;

import edu.cwru.openbench.common.Actor;
import edu.cwru.openbench.common.PageResponse;
import edu.cwru.openbench.common.ResourceId;
import edu.cwru.openbench.common.ResourceTypeId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Inventory endpoints (design Table "Endpoints by module"). The holder timeline and the
 * free-for-a-window search under {@code /api/inventory/resources} belong to Allocation's controller.
 */
@RestController
@RequestMapping("/api/inventory")
class InventoryController {

    /** Query parameters {@code attr.<name>=<value>} filter Resources by attribute value. */
    private static final String ATTRIBUTE_PARAMETER = "attr.";

    private final ResourceTypeService resourceTypes;
    private final ResourceService resources;
    private final InventoryQueries queries;

    InventoryController(ResourceTypeService resourceTypes, ResourceService resources, InventoryQueries queries) {
        this.resourceTypes = resourceTypes;
        this.resources = resources;
        this.queries = queries;
    }

    @GetMapping("/types")
    List<ResourceType> types(@RequestParam(defaultValue = "false") boolean includeRetired) {
        return queries.browseTypes(includeRetired);
    }

    @GetMapping("/types/{id}")
    ResourceType type(@PathVariable ResourceTypeId id) {
        return queries.browseType(id);
    }

    @PostMapping("/types")
    ResponseEntity<ResourceType> define(Actor actor, @Valid @RequestBody ResourceTypeDefinition definition) {
        ResourceType type = resourceTypes.define(actor, definition);
        return ResponseEntity.created(URI.create("/api/inventory/types/" + type.id().value())).body(type);
    }

    @PutMapping("/types/{id}")
    ResourceType change(Actor actor, @PathVariable ResourceTypeId id, @Valid @RequestBody ResourceTypeDefinition definition) {
        return resourceTypes.change(actor, id, definition);
    }

    @PostMapping("/types/{id}/retire")
    ResourceType retireType(@PathVariable ResourceTypeId id) {
        return resourceTypes.retire(id);
    }

    @GetMapping("/resources")
    PageResponse<Resource> resources(
            @RequestParam(required = false) ResourceTypeId type,
            @RequestParam(defaultValue = "false") boolean includeRetired,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size,
            @RequestParam Map<String, String> parameters) {
        Map<String, String> attributes = new LinkedHashMap<>();
        parameters.forEach((name, value) -> {
            if (name.startsWith(ATTRIBUTE_PARAMETER)) {
                attributes.put(name.substring(ATTRIBUTE_PARAMETER.length()), value);
            }
        });
        ResourceFilter filter = new ResourceFilter(type, attributes, includeRetired);
        return PageResponse.of(queries.browseResources(filter, page, size));
    }

    @GetMapping("/resources/{id}")
    Resource resource(@PathVariable ResourceId id) {
        return queries.browseResource(id);
    }

    @PostMapping("/resources")
    ResponseEntity<Resource> add(Actor actor, @Valid @RequestBody NewResource command) {
        Resource resource = resources.add(actor, command);
        return ResponseEntity.created(URI.create("/api/inventory/resources/" + resource.id().value())).body(resource);
    }

    @PutMapping("/resources/{id}")
    Resource edit(Actor actor, @PathVariable ResourceId id, @Valid @RequestBody ResourceEdit command) {
        return resources.edit(actor, id, command);
    }

    @PutMapping("/resources/{id}/units")
    Resource setUnits(Actor actor, @PathVariable ResourceId id, @Valid @RequestBody UnitsChange change) {
        return resources.setUnits(actor, id, change.units());
    }

    @PostMapping("/resources/{id}/retire")
    Resource retireResource(Actor actor, @PathVariable ResourceId id) {
        return resources.retire(actor, id);
    }

    /** The body of PUT /api/inventory/resources/{id}/units: {@code {"units": 4}}. */
    record UnitsChange(@NotNull Integer units) {}
}
