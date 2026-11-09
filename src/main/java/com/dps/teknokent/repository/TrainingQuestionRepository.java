package com.dps.teknokent.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dps.teknokent.entity.TrainingQuestion;

public interface TrainingQuestionRepository extends JpaRepository<TrainingQuestion, UUID> {

	List<TrainingQuestion> findByModuleIdAndVersionOrderByPositionAsc(UUID moduleId, int version);
}
