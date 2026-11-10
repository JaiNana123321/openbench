package edu.cwru.openbench.ledger;

import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface AccountRepository extends JpaRepository<Account, Long> {

    /** {@code SELECT ... FOR UPDATE}: the Team's account row, locked until the transaction ends. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.teamId = :teamId")
    Optional<Account> lockByTeamId(Long teamId);

    /** Creates the Team's account row with zero totals unless it exists; a concurrent creator waits. */
    @Modifying
    @Query(nativeQuery = true, value = "INSERT INTO account (team_id) VALUES (:teamId) ON CONFLICT DO NOTHING")
    void createIfMissing(Long teamId);

    /** The totals without a lock and without loading the entity. */
    @Query("select a.granted as granted, a.earned as earned, a.held as held from Account a where a.teamId = :teamId")
    Optional<Figures> figuresOf(Long teamId);

    /** Every Team that has an account row, ascending: the rollover locks them in this order. */
    @Query("select a.teamId from Account a order by a.teamId")
    List<Long> findAllTeamIds();

    interface Figures {

        BigDecimal getGranted();

        BigDecimal getEarned();

        BigDecimal getHeld();
    }
}
