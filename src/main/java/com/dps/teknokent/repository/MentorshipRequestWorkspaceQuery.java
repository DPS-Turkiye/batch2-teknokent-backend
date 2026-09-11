package com.dps.teknokent.repository;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.hibernate.dialect.Dialect;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import com.dps.teknokent.domain.MentorshipWorkspaceTab;
import com.dps.teknokent.entity.MentorshipRequest;
import com.dps.teknokent.exception.InvalidRequestException;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;

@Repository
public class MentorshipRequestWorkspaceQuery {

	@PersistenceContext
	private EntityManager entityManager;

	public Page<MentorshipRequest> findPage(
			UUID mentorId,
			UUID organizationId,
			MentorshipWorkspaceTab tab,
			Pageable pageable,
			Instant now) {
		if (mentorId == null && organizationId == null) {
			throw new InvalidRequestException("mentorId or organizationId is required for workspace pages");
		}
		if (mentorId != null && organizationId != null) {
			throw new InvalidRequestException("Provide only one of mentorId or organizationId");
		}

		String scopePredicate = mentorId != null
				? "r.mentor_id = :scopeId"
				: "r.organization_id = :scopeId";
		UUID scopeId = mentorId != null ? mentorId : organizationId;
		String tabPredicate = tabPredicate(tab);

		String fromWhere = """
				FROM mentorship_request r
				WHERE %s
				AND (%s)
				""".formatted(scopePredicate, tabPredicate);

		Query countQuery = entityManager.createNativeQuery("SELECT COUNT(r.id) " + fromWhere);
		bindCommon(countQuery, scopeId, tab, now);
		long total = ((Number) countQuery.getSingleResult()).longValue();
		if (total == 0) {
			return Page.empty(pageable);
		}

		Query idQuery = entityManager.createNativeQuery(
				"SELECT r.id " + fromWhere + " ORDER BY r.created_at DESC");
		bindCommon(idQuery, scopeId, tab, now);
		idQuery.setFirstResult((int) pageable.getOffset());
		idQuery.setMaxResults(pageable.getPageSize());

		List<?> rawIds = idQuery.getResultList();
		List<UUID> ids = rawIds.stream()
				.map(MentorshipRequestWorkspaceQuery::toUuid)
				.toList();
		if (ids.isEmpty()) {
			return new PageImpl<>(List.of(), pageable, total);
		}

		TypedQuery<MentorshipRequest> entityQuery = entityManager.createQuery(
				"""
						SELECT r FROM MentorshipRequest r
						WHERE r.id IN :ids
						ORDER BY r.createdAt DESC
						""",
				MentorshipRequest.class);
		entityQuery.setParameter("ids", ids);
		List<MentorshipRequest> content = entityQuery.getResultList();
		return new PageImpl<>(content, pageable, total);
	}

	private String tabPredicate(MentorshipWorkspaceTab tab) {
		String hasRelated = hasRelatedSql();
		String hasOpen = hasOpenSql();
		String hasEnded = hasEndedSql();
		return switch (tab) {
			case ACTIVE -> "r.status = 'ACCEPTED' AND " + hasOpen;
			case REQUESTS -> """
					r.status IN ('PENDING', 'MORE_INFO_REQUESTED', 'COUNTER_PROPOSED')
					OR (
					r.status = 'ACCEPTED'
					AND NOT %s
					)
					""".formatted(hasRelated);
			case HISTORY -> """
					r.status IN ('DECLINED', 'WITHDRAWN')
					OR (
					r.status = 'ACCEPTED'
					AND %s
					AND %s
					AND NOT %s
					)
					""".formatted(hasRelated, hasEnded, hasOpen);
		};
	}

	private String hasRelatedSql() {
		return """
				(
				EXISTS (
				SELECT 1 FROM meeting lm WHERE lm.mentorship_request_id = r.id
				)
				OR (
				NOT EXISTS (
				SELECT 1 FROM meeting lm WHERE lm.mentorship_request_id = r.id
				)
				AND EXISTS (
				SELECT 1 FROM meeting cm
				WHERE cm.mentorship_request_id IS NULL
				AND cm.mentor_id = r.mentor_id
				AND cm.organization_id = r.organization_id
				)
				)
				)
				""";
	}

	private String hasOpenSql() {
		String open = openMeetingPredicate("m");
		return """
				(
				EXISTS (
				SELECT 1 FROM meeting m
				WHERE m.mentorship_request_id = r.id
				AND %s
				)
				OR (
				NOT EXISTS (
				SELECT 1 FROM meeting lm WHERE lm.mentorship_request_id = r.id
				)
				AND EXISTS (
				SELECT 1 FROM meeting m
				WHERE m.mentorship_request_id IS NULL
				AND m.mentor_id = r.mentor_id
				AND m.organization_id = r.organization_id
				AND %s
				)
				)
				)
				""".formatted(open, open);
	}

	private String hasEndedSql() {
		String ended = endedMeetingPredicate("m");
		return """
				(
				EXISTS (
				SELECT 1 FROM meeting m
				WHERE m.mentorship_request_id = r.id
				AND %s
				)
				OR (
				NOT EXISTS (
				SELECT 1 FROM meeting lm WHERE lm.mentorship_request_id = r.id
				)
				AND EXISTS (
				SELECT 1 FROM meeting m
				WHERE m.mentorship_request_id IS NULL
				AND m.mentor_id = r.mentor_id
				AND m.organization_id = r.organization_id
				AND %s
				)
				)
				)
				""".formatted(ended, ended);
	}

	private String openMeetingPredicate(String alias) {
		return alias + ".status <> 'COMPLETED' AND " + meetingEndsAt(alias) + " >= :now";
	}

	private String endedMeetingPredicate(String alias) {
		return "(" + alias + ".status = 'COMPLETED' OR " + meetingEndsAt(alias) + " < :now)";
	}

	private String meetingEndsAt(String alias) {
		if (isH2()) {
			return "DATEADD('MINUTE', COALESCE(" + alias + ".duration_minutes, 60), " + alias + ".scheduled_at)";
		}
		return "(" + alias + ".scheduled_at + (COALESCE(" + alias + ".duration_minutes, 60) * INTERVAL '1 minute'))";
	}

	private boolean isH2() {
		Dialect dialect = entityManager.getEntityManagerFactory()
				.unwrap(SessionFactoryImplementor.class)
				.getJdbcServices()
				.getDialect();
		return dialect.getClass().getName().contains("H2");
	}

	private static void bindCommon(
			Query query, UUID scopeId, MentorshipWorkspaceTab tab, Instant now) {
		query.setParameter("scopeId", scopeId);
		if (tab == MentorshipWorkspaceTab.ACTIVE || tab == MentorshipWorkspaceTab.HISTORY) {
			query.setParameter("now", java.sql.Timestamp.from(now));
		}
	}

	private static UUID toUuid(Object value) {
		if (value instanceof UUID uuid) {
			return uuid;
		}
		if (value instanceof byte[] bytes) {
			ByteBuffer buffer = ByteBuffer.wrap(bytes);
			return new UUID(buffer.getLong(), buffer.getLong());
		}
		return UUID.fromString(value.toString());
	}
}
