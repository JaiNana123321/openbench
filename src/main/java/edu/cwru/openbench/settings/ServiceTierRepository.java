package edu.cwru.openbench.settings;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface ServiceTierRepository extends JpaRepository<ServiceTierRow, Long> {

    List<ServiceTierRow> findByTypeIdOrderByPremium(Long typeId);

    /**
     * Deletes these tiers at once. A tier a line refers to fails on {@code request_line}'s foreign
     * key, which the caller turns into a refusal.
     */
    @Modifying(flushAutomatically = true)
    @Query("delete from ServiceTierRow t where t.id in :ids")
    void deleteByIds(Collection<Long> ids);
}
