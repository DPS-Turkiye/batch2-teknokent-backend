package com.dps.teknokent.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.entity.HealthProbe;

public interface HealthProbeRepository extends JpaRepository<HealthProbe, Short> {
}
