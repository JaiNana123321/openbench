package edu.cwru.openbench.members;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface TeamRepository extends JpaRepository<Team, Long> {

    List<Team> findByRetiredAtIsNullOrderByName();

    /** Every Team, retired ones included, by name. */
    List<Team> findAllByOrderByName();

    /** True when a Team other than {@code except}, retired or not, has the name, ignoring case. */
    @Query("select count(t) > 0 from Team t where lower(t.name) = lower(:name) and t.id <> :except")
    boolean nameTaken(String name, Long except);

    /** The Team, its row locked until the transaction ends ({@code SELECT ... FOR UPDATE}). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Team t where t.id = :id")
    Optional<Team> lockById(Long id);
}
