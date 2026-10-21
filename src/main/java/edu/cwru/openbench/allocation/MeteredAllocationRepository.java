package edu.cwru.openbench.allocation;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface MeteredAllocationRepository extends JpaRepository<MeteredAllocationRow, Long> {

    List<MeteredAllocationRow> findByLineIdAndStateIn(Long lineId, Collection<RowState> states);
}
