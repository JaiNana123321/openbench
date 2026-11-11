package edu.cwru.openbench.settings;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface PeriodRepository extends JpaRepository<PeriodRow, Long> {

    /** The one Period containing the day; Periods never overlap. */
    @Query("select p from PeriodRow p where p.startsOn <= :day and p.endsOn >= :day")
    Optional<PeriodRow> findContaining(LocalDate day);

    List<PeriodRow> findAllByOrderByStartsOn();

    /** The latest Period starting before the day: the previous Period of one starting that day. */
    Optional<PeriodRow> findFirstByStartsOnBeforeOrderByStartsOnDesc(LocalDate day);

    /** The Periods starting from {@code from} to {@code to}, both included, by start date. */
    List<PeriodRow> findByStartsOnBetweenOrderByStartsOn(LocalDate from, LocalDate to);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from PeriodRow p where p.id in :ids")
    void deleteByIds(List<Long> ids);
}
