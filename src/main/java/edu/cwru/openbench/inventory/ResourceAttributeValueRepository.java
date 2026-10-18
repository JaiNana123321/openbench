package edu.cwru.openbench.inventory;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface ResourceAttributeValueRepository
        extends JpaRepository<ResourceAttributeValueRow, ResourceAttributeValueRow.Key> {

    List<ResourceAttributeValueRow> findByResourceId(Long resourceId);

    List<ResourceAttributeValueRow> findByResourceIdIn(Collection<Long> resourceIds);

    /** The distinct values Resources, retired or not, give the attribute: what a change must keep. */
    @Query("select distinct v.value from ResourceAttributeValueRow v where v.attributeId = :attributeId")
    List<String> valuesInUse(Long attributeId);
}
