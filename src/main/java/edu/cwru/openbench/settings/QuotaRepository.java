package edu.cwru.openbench.settings;

import org.springframework.data.jpa.repository.JpaRepository;

interface QuotaRepository extends JpaRepository<QuotaRow, QuotaRow.Key> {}
