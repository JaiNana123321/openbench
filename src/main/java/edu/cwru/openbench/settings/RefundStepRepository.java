package edu.cwru.openbench.settings;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface RefundStepRepository extends JpaRepository<RefundStepRow, Long> {

    List<RefundStepRow> findByTypeId(Long typeId);

    /** Deletes the type's steps at once, so new steps with the same lead times can be inserted. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from RefundStepRow s where s.typeId = :typeId")
    void deleteByTypeId(Long typeId);
}
