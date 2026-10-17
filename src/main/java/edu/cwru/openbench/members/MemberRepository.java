package edu.cwru.openbench.members;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface MemberRepository extends JpaRepository<Member, Long> {

    Optional<Member> findByUsername(String username);

    @Query("select m.id from Member m where m.active order by m.id")
    List<Long> findActiveIds();
}
