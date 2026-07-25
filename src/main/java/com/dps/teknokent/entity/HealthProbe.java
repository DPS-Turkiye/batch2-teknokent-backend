package com.dps.teknokent.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "health_probe")
@Getter
@Setter
@NoArgsConstructor
public class HealthProbe {

	public static final short PROBE_ID = 1;

	@Id
	private Short id;

	@Column(nullable = false)
	private String message;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

}
