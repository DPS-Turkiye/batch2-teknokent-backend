package com.dps.teknokent.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.entity.StudentInterest;

public interface StudentInterestRepository extends JpaRepository<StudentInterest, UUID> {
}
