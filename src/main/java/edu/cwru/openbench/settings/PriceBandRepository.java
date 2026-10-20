package edu.cwru.openbench.settings;

import org.springframework.data.jpa.repository.JpaRepository;

interface PriceBandRepository extends JpaRepository<PriceBandRow, Long> {}
