package edu.cwru.openbench.members;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface TeamRepository extends JpaRepository<Team, Long> {

    List<Team> findByRetiredAtIsNullOrderByName();
}
