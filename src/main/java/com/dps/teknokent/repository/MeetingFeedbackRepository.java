package com.dps.teknokent.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;

import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.MeetingFeedback;

public interface MeetingFeedbackRepository extends JpaRepository<MeetingFeedback, UUID> {

	/** One mentorship's share of a mentor's rating: every 1–5 answer mentees gave on its meetings. */
	interface MentorshipRating {
		UUID getRequestId();

		Double getAverage();

		Long getFeedbackCount();
	}

	@Query("""
			select m.mentorshipRequest.id as requestId, avg(r) as average, count(distinct f.id) as feedbackCount
			from MeetingFeedback f join f.meeting m join f.ratings r
			where m.mentor.id = :mentorId and f.author.role = com.dps.teknokent.domain.UserRole.MENTEE
			group by m.mentorshipRequest.id
			""")
	List<MentorshipRating> ratingsByMentorship(@Param("mentorId") UUID mentorId);

	Optional<MeetingFeedback> findByMeetingIdAndAuthorId(UUID meetingId, UUID authorId);

	List<MeetingFeedback> findByMeetingIdInAndAuthorId(Collection<UUID> meetingIds, UUID authorId);

	List<MeetingFeedback> findByMeetingIdInAndAuthorRole(Collection<UUID> meetingIds, UserRole role);

	/** One page of every side's feedback on these meetings, with the people and team a report row shows. */
	@EntityGraph(attributePaths = { "author", "meeting", "meeting.mentor", "meeting.organization" })
	Page<MeetingFeedback> findByMeetingIdIn(Collection<UUID> meetingIds, Pageable pageable);

	/** Mentee feedback on a mentor's meetings, for the {@code WELL_RATED} badge (same answers as "Memnuniyet"). */
	List<MeetingFeedback> findByMeetingMentorIdAndAuthorRole(UUID mentorId, UserRole role);

	List<MeetingFeedback> findByAuthorId(UUID authorId);

	@Query("select distinct f.author.id from MeetingFeedback f")
	List<UUID> findAuthorIds();

	@Query("select distinct f.meeting.mentor.id from MeetingFeedback f where f.meeting.mentor is not null")
	List<UUID> findRatedMentorIds();
}
