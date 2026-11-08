package com.dps.teknokent.entity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import com.dps.teknokent.domain.AppLanguage;
import com.dps.teknokent.domain.ContactPreference;
import com.dps.teknokent.domain.PermissionSource;
import com.dps.teknokent.domain.PermissionStatus;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;

import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.ElementCollection;
import org.hibernate.annotations.BatchSize;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "app_user")
@Getter
@Setter
@NoArgsConstructor
public class AppUser {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(nullable = false, unique = true)
	private String email;

	@Column(name = "full_name", nullable = false)
	private String fullName;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private UserRole role;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "organization_id")
	private Organization organization;

	/** The program chosen at signup; the startup's program once the person has one. */
	@Column(name = "program_id")
	private UUID programId;

	@Column(name = "job_title")
	private String jobTitle;

	@Column(length = 32)
	private String phone;

	@Column(columnDefinition = "TEXT")
	private String bio;

	@Column(name = "linkedin_url")
	private String linkedinUrl;

	@ElementCollection
	@CollectionTable(name = "app_user_expertise", joinColumns = @JoinColumn(name = "user_id"))
	@OrderColumn(name = "display_order")
	@Column(name = "expertise", nullable = false)
	@BatchSize(size = 50)
	private List<String> expertise = new ArrayList<>();

	@ElementCollection
	@CollectionTable(name = "app_user_contact_preference", joinColumns = @JoinColumn(name = "user_id"))
	@OrderColumn(name = "display_order")
	@Enumerated(EnumType.STRING)
	@Column(name = "preference", nullable = false, length = 32)
	@BatchSize(size = 50)
	private List<ContactPreference> contactPreferences = new ArrayList<>();

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private UserAccountStatus status = UserAccountStatus.APPROVED;

	@Column(name = "firebase_uid", unique = true)
	private String firebaseUid;

	@Column(name = "sign_ins_revoked_at")
	private Instant signInsRevokedAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "preferred_language", nullable = false, length = 8)
	private AppLanguage preferredLanguage = AppLanguage.TR;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	/** Eager because the bearer filter and many non-transactional reads ask {@link #has}; at most five rows. */
	@OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
	@BatchSize(size = 50)
	@Getter(AccessLevel.NONE)
	@Setter(AccessLevel.NONE)
	private List<AppUserPermission> permissions = new ArrayList<>();

	/**
	 * Sets the primary role and, until accounts can hold more than one permission, makes it the only active
	 * one, so every reader of {@code role} and of {@link #has} agrees.
	 */
	public void setRole(UserRole role) {
		this.role = role;
		if (role == null) {
			return;
		}
		Instant now = Instant.now();
		PermissionSource source = id == null ? PermissionSource.REGISTRATION : PermissionSource.ADMIN;
		boolean held = false;
		for (AppUserPermission grant : permissions) {
			if (grant.getPermission() == role) {
				held = true;
				if (!grant.activeAt(now)) {
					grant.reactivate(source, now);
				}
			} else if (grant.getStatus() == PermissionStatus.ACTIVE) {
				grant.revoke(now);
			}
		}
		if (!held) {
			permissions.add(new AppUserPermission(this, role, source, now));
		}
	}

	/**
	 * The primary role counts as held only without a row, so an account inserted by a later seed migration
	 * without its grant keeps working; dropped with {@code role} itself.
	 */
	public Set<UserRole> activePermissions() {
		Set<UserRole> active = EnumSet.noneOf(UserRole.class);
		if (role != null && permissions.stream().noneMatch(grant -> grant.getPermission() == role)) {
			active.add(role);
		}
		Instant now = Instant.now();
		for (AppUserPermission grant : permissions) {
			if (grant.activeAt(now)) {
				active.add(grant.getPermission());
			}
		}
		return active;
	}

	public boolean has(UserRole permission) {
		return activePermissions().contains(permission);
	}
}
