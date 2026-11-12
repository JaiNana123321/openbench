package edu.cwru.openbench.members;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface AdminChangeRepository extends JpaRepository<AdminChangeRow, Long> {

    /** The change record, newest first; changes at the same instant by the order they were recorded, newest first. */
    Page<AdminChangeRow> findAllByOrderByAtDescIdDesc(Pageable page);
}
