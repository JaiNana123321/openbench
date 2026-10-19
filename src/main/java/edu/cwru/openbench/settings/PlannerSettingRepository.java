package edu.cwru.openbench.settings;

import org.springframework.data.jpa.repository.JpaRepository;

interface PlannerSettingRepository extends JpaRepository<PlannerSettingRow, String> {}
