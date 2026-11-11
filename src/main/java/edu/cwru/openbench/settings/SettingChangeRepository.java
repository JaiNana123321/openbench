package edu.cwru.openbench.settings;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface SettingChangeRepository extends JpaRepository<SettingChangeRow, Long> {

    /** The change log, newest first; changes at the same instant by the order they were logged, newest first. */
    Page<SettingChangeRow> findAllByOrderByAtDescIdDesc(Pageable page);
}
