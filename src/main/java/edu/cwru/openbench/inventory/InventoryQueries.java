package edu.cwru.openbench.inventory;

import edu.cwru.openbench.common.NotFoundException;
import edu.cwru.openbench.common.ResourceId;
import edu.cwru.openbench.common.ResourceTypeId;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The read side of the Inventory (design Section 4.2): Resource Types, Resources, and Pool Capacity.
 *
 * <p>The {@code browse} methods serve the API and need a signed-in Member. The others are the
 * unscoped reads other modules use for their own rules, also from scheduled jobs, which run
 * without a signed-in Member; so they carry no role check, and no controller calls them.
 */
@Service
@Transactional(readOnly = true)
public class InventoryQueries {

    private static final Sort BY_NAME = Sort.by("name", "id");

    private final ResourceTypeRepository types;
    private final ResourceRepository resources;
    private final InventoryViews views;

    InventoryQueries(ResourceTypeRepository types, ResourceRepository resources, InventoryViews views) {
        this.types = types;
        this.resources = resources;
        this.views = views;
    }

    /** GET /api/inventory/types: the Resource Types by name, with Pool Capacity for Metered ones. */
    @PreAuthorize("hasAuthority('MEMBER')")
    public List<ResourceType> browseTypes(boolean includeRetired) {
        return types(includeRetired);
    }

    /** GET /api/inventory/types/{id}. */
    @PreAuthorize("hasAuthority('MEMBER')")
    public ResourceType browseType(ResourceTypeId id) {
        return type(id);
    }

    /** GET /api/inventory/resources: one page of the matching Resources, by name. */
    @PreAuthorize("hasAuthority('MEMBER')")
    public Page<Resource> browseResources(ResourceFilter filter, int page, int size) {
        Page<ResourceRow> rows = resources.findAll(matching(filter), PageRequest.of(page, size, BY_NAME));
        return new PageImpl<>(views.resources(rows.getContent()), rows.getPageable(), rows.getTotalElements());
    }

    /** GET /api/inventory/resources/{id}: one Resource, retired or not. */
    @PreAuthorize("hasAuthority('MEMBER')")
    public Resource browseResource(ResourceId id) {
        return resource(id);
    }

    /** (Unscoped) A Resource Type, retired or not, with Pool Capacity when Metered. */
    public ResourceType type(ResourceTypeId id) {
        return views.type(types.findById(id.value()).orElseThrow(() -> typeNotFound(id)));
    }

    /** (Unscoped) Every Resource Type by name; retired ones only when asked. */
    public List<ResourceType> types(boolean includeRetired) {
        return views.types(includeRetired ? types.findAllByOrderByName() : types.findByRetiredAtIsNullOrderByName());
    }

    /** (Unscoped) The type's unretired Resources by name: never a retired one, so no allocator picks one. */
    public List<Resource> instancesOf(ResourceTypeId id) {
        return views.resources(resources.findByTypeIdAndRetiredAtIsNullOrderByName(id.value()));
    }

    /** (Unscoped) The sum of the units of the type's unretired Resources, computed on every call. */
    public int poolCapacity(ResourceTypeId id) {
        return views.poolCapacity(id.value());
    }

    /** (Unscoped) Every matching Resource by name. */
    public List<Resource> search(ResourceFilter filter) {
        return views.resources(resources.findAll(matching(filter), BY_NAME));
    }

    /** (Unscoped) One Resource, retired or not. */
    public Resource resource(ResourceId id) {
        return views.resource(resources.findById(id.value())
                .orElseThrow(() -> new NotFoundException("Resource " + id.value() + " does not exist.")));
    }

    static NotFoundException typeNotFound(ResourceTypeId id) {
        return new NotFoundException("Resource Type " + id.value() + " does not exist.");
    }

    /** The filter as SQL: each attribute condition is an EXISTS on the stored values. */
    private static Specification<ResourceRow> matching(ResourceFilter filter) {
        return (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (filter.type() != null) {
                where.add(cb.equal(root.get("typeId"), filter.type().value()));
            }
            if (!filter.includeRetired()) {
                where.add(cb.isNull(root.get("retiredAt")));
            }
            filter.attributes().forEach((name, value) -> {
                Subquery<Long> given = query.subquery(Long.class);
                Root<ResourceAttributeValueRow> stored = given.from(ResourceAttributeValueRow.class);
                Root<ResourceTypeAttributeRow> attribute = given.from(ResourceTypeAttributeRow.class);
                given.select(stored.get("resourceId")).where(
                        cb.equal(stored.get("resourceId"), root.get("id")),
                        cb.equal(stored.get("attributeId"), attribute.get("id")),
                        cb.equal(attribute.get("name"), name),
                        cb.equal(stored.get("value"), value));
                where.add(cb.exists(given));
            });
            return cb.and(where.toArray(Predicate[]::new));
        };
    }
}
