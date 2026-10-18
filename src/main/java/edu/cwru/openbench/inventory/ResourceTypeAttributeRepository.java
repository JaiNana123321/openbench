package edu.cwru.openbench.inventory;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface ResourceTypeAttributeRepository extends JpaRepository<ResourceTypeAttributeRow, Long> {

    List<ResourceTypeAttributeRow> findByTypeIdOrderById(Long typeId);

    List<ResourceTypeAttributeRow> findByTypeIdInOrderById(Collection<Long> typeIds);
}
