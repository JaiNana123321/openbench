package edu.cwru.openbench.inventory;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface ResourceTypeRepository extends JpaRepository<ResourceTypeRow, Long> {

    List<ResourceTypeRow> findAllByOrderByName();

    List<ResourceTypeRow> findByRetiredAtIsNullOrderByName();

    /** Whether another type has this name, ignoring case; pass 0 when defining a new type. */
    @Query("select count(t) > 0 from ResourceTypeRow t where lower(t.name) = lower(:name) and t.id <> :exceptId")
    boolean nameTaken(String name, long exceptId);

    /**
     * Locks the type's row until the transaction ends: the lock {@code AllocationLock.lockTypes}
     * takes, so a capacity change and an allocation of the same type never interleave.
     */
    @Query(value = "SELECT id FROM resource_type WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<Long> lock(long id);
}
