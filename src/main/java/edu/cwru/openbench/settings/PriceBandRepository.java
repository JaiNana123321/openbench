package edu.cwru.openbench.settings;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface PriceBandRepository extends JpaRepository<PriceBandRow, Long> {

    List<PriceBandRow> findByTypeId(Long typeId);

    /** Deletes the type's bands at once, before new ones are inserted. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from PriceBandRow b where b.typeId = :typeId")
    void deleteByTypeId(Long typeId);
}
