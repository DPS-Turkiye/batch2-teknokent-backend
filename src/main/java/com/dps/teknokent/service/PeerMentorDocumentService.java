package com.dps.teknokent.service;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.dps.teknokent.domain.DocumentKind;
import com.dps.teknokent.domain.IdentityKind;
import com.dps.teknokent.domain.PeerDocumentVisibility;
import com.dps.teknokent.domain.PeerEvidenceType;
import com.dps.teknokent.domain.ProgramMentorStatus;
import com.dps.teknokent.domain.ProgramType;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.responses.PeerMentorDocumentResponse;
import com.dps.teknokent.dto.responses.StoredDocumentResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.PeerMentorDocument;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramMentor;
import com.dps.teknokent.entity.StoredDocument;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.PeerMentorDocumentRepository;
import com.dps.teknokent.repository.ProgramCoordinatorRepository;
import com.dps.teknokent.repository.ProgramMentorRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.StoredDocumentRepository;
import com.dps.teknokent.util.ProgramCoordination;
import com.dps.teknokent.util.Texts;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The evidence a peer mentor attaches in the wizard's "Belgeler ve kanıtlar" step: files, which go through the one
 * upload path ({@link DocumentService}, kind peer-evidence), and portfolio links. Who may see it:
 * <ul>
 * <li>the mentor: all of their own, and only they add, change or delete;</li>
 * <li>an admin, or a coordinator of a peer program the mentor is in: all (VERIFICATION_ONLY ones are for them);</li>
 * <li>a student of such a program: the PROFILE ones;</li>
 * <li>anyone else: none, answered as not found so an id says nothing about whether it exists.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PeerMentorDocumentService {

	/** Enough for every card several times over; a cap so one account cannot fill the bucket. */
	static final int MAX_EVIDENCE = 30;
	private static final Pattern INVISIBLE = Pattern.compile("[\\p{Cntrl}\\p{Z}\\s]");

	private final DocumentService documentService;
	private final ObjectStorage objectStorage;
	private final StoredDocumentRepository storedDocumentRepository;
	private final PeerMentorDocumentRepository peerMentorDocumentRepository;
	private final DomainLookupService domainLookupService;
	private final InstitutionDirectoryService institutionDirectoryService;
	private final MenteeProgramService menteeProgramService;
	private final ProgramMentorRepository programMentorRepository;
	private final ProgramRepository programRepository;
	private final ProgramCoordinatorRepository programCoordinatorRepository;
	private final PlatformTransactionManager transactionManager;

	/** The file of one piece of evidence: what the download endpoint streams. */
	public record DocumentContent(String filename, String contentType, byte[] bytes) {
	}

	@Transactional(readOnly = true)
	public List<PeerMentorDocumentResponse> mine(UUID actorId) {
		AppUser mentor = requirePeerMentor(actorId);
		return responses(peerMentorDocumentRepository.findByUserIdOrderByCreatedAtAsc(mentor.getId()));
	}

	/**
	 * Stores the file with the uploads' own checks (PDF only, size, signature) as the mentor's personal document, never
	 * the personal team's, so it stays off any team file list.
	 */
	@Transactional
	public PeerMentorDocumentResponse upload(UUID actorId, PeerEvidenceType evidenceType,
			PeerDocumentVisibility visibility, MultipartFile file) {
		AppUser mentor = requirePeerMentor(actorId);
		if (evidenceType == null || visibility == null || file == null) {
			throw new InvalidRequestException("A document type, a visibility and a file are required.");
		}
		if (evidenceType.isLink()) {
			throw new InvalidRequestException("A portfolio is added as a link, not a file.");
		}
		requireRoom(mentor.getId());
		byte[] bytes;
		try {
			bytes = file.getBytes();
		} catch (IOException error) {
			throw new InvalidRequestException("The uploaded file could not be read.");
		}
		StoredDocumentResponse stored = documentService.store(mentor.getId(), null, DocumentKind.PEER_EVIDENCE.value(),
				file.getOriginalFilename(), file.getContentType(), bytes);
		PeerMentorDocument row = newRow(mentor.getId(), evidenceType, visibility);
		row.setDocumentId(stored.id());
		PeerMentorDocument saved = peerMentorDocumentRepository.save(row);
		StoredDocument storedFile = requireFile(saved);
		removeObjectIfRolledBack(storedFile.getS3Key());
		return PeerMentorDocumentResponse.ofFile(saved, storedFile);
	}

	@Transactional
	public PeerMentorDocumentResponse addLink(UUID actorId, String url, PeerDocumentVisibility visibility) {
		AppUser mentor = requirePeerMentor(actorId);
		requireRoom(mentor.getId());
		PeerMentorDocument row = newRow(mentor.getId(), PeerEvidenceType.PORTFOLIO, visibility);
		row.setUrl(requireWebLink(url));
		return PeerMentorDocumentResponse.ofLink(peerMentorDocumentRepository.save(row));
	}

	@Transactional
	public PeerMentorDocumentResponse updateVisibility(UUID actorId, UUID documentId,
			PeerDocumentVisibility visibility) {
		AppUser mentor = requirePeerMentor(actorId);
		PeerMentorDocument row = ownRow(mentor.getId(), documentId);
		row.setVisibility(visibility);
		return response(peerMentorDocumentRepository.save(row));
	}

	/**
	 * A file goes with it. As with team files, the stored object goes only once the rows' deletion has committed: a
	 * storage hiccup leaves an orphaned object rather than a record pointing at nothing.
	 */
	public void delete(UUID actorId, UUID documentId) {
		String s3Key = new TransactionTemplate(transactionManager).execute(status -> {
			AppUser mentor = requirePeerMentor(actorId);
			PeerMentorDocument row = ownRow(mentor.getId(), documentId);
			peerMentorDocumentRepository.delete(row);
			if (row.getDocumentId() == null) {
				return null;
			}
			return storedDocumentRepository.findById(row.getDocumentId()).map(file -> {
				storedDocumentRepository.delete(file);
				return file.getS3Key();
			}).orElse(null);
		});
		if (s3Key != null) {
			deleteObject(s3Key);
		}
	}

	/** What the caller may see of this mentor's evidence; not found if they may see none of it. */
	@Transactional(readOnly = true)
	public List<PeerMentorDocumentResponse> ofMentor(UUID actorId, UUID mentorId) {
		AppUser viewer = domainLookupService.requireUser(actorId);
		Access access = accessTo(viewer, mentorId);
		if (access == Access.NONE) {
			throw notFound(mentorId);
		}
		List<PeerMentorDocument> rows = access == Access.ALL
				? peerMentorDocumentRepository.findByUserIdOrderByCreatedAtAsc(mentorId)
				: peerMentorDocumentRepository.findByUserIdAndVisibilityOrderByCreatedAtAsc(mentorId,
						PeerDocumentVisibility.PROFILE);
		return responses(rows);
	}

	@Transactional(readOnly = true)
	public DocumentContent content(UUID actorId, UUID documentId) {
		AppUser viewer = domainLookupService.requireUser(actorId);
		PeerMentorDocument row = peerMentorDocumentRepository.findById(documentId)
				.filter(found -> found.getDocumentId() != null)
				.orElseThrow(() -> notFound(documentId));
		Access access = accessTo(viewer, row.getUserId());
		boolean visible = access == Access.ALL
				|| (access == Access.PROFILE && row.getVisibility() == PeerDocumentVisibility.PROFILE);
		if (!visible) {
			throw notFound(documentId);
		}
		StoredDocument file = requireFile(row);
		return new DocumentContent(file.getOriginalFilename(), file.getContentType(), objectStorage.get(file.getS3Key()));
	}

	private enum Access {
		ALL,
		PROFILE,
		NONE
	}

	private Access accessTo(AppUser viewer, UUID mentorId) {
		if (viewer.getId().equals(mentorId) || viewer.has(UserRole.ADMIN)) {
			return Access.ALL;
		}
		List<Program> programs = peerPrograms(mentorId);
		if (programs.isEmpty()) {
			return Access.NONE;
		}
		if (viewer.has(UserRole.COORDINATOR) && programs.stream()
				.anyMatch(program -> ProgramCoordination.coordinates(program, viewer.getId(),
						programCoordinatorRepository))) {
			return Access.ALL;
		}
		if (viewer.has(UserRole.MENTEE) && isStudent(viewer)) {
			Set<UUID> ids = programs.stream().map(Program::getId).collect(Collectors.toSet());
			if (menteeProgramService.programOf(viewer).map(ids::contains).orElse(false)) {
				return Access.PROFILE;
			}
		}
		return Access.NONE;
	}

	/** The peer programs the mentor is an accepted mentor of, archived ones included (for their coordinators). */
	private List<Program> peerPrograms(UUID mentorId) {
		List<UUID> ids = programMentorRepository
				.findByMentorIdAndStatusOrderByCreatedAtDesc(mentorId, ProgramMentorStatus.ACCEPTED).stream()
				.map(ProgramMentor::getProgramId)
				.toList();
		return ids.isEmpty() ? List.of()
				: programRepository.findAllById(ids).stream()
						.filter(program -> program.getProgramType() == ProgramType.PEER_MENTORING)
						.toList();
	}

	private List<PeerMentorDocumentResponse> responses(List<PeerMentorDocument> rows) {
		List<UUID> fileIds = rows.stream().map(PeerMentorDocument::getDocumentId).filter(Objects::nonNull).toList();
		Map<UUID, StoredDocument> files = fileIds.isEmpty() ? Map.of()
				: storedDocumentRepository.findAllById(fileIds).stream()
						.collect(Collectors.toMap(StoredDocument::getId, Function.identity()));
		return rows.stream()
				.filter(row -> row.getDocumentId() == null || files.containsKey(row.getDocumentId()))
				.map(row -> row.getDocumentId() == null ? PeerMentorDocumentResponse.ofLink(row)
						: PeerMentorDocumentResponse.ofFile(row, files.get(row.getDocumentId())))
				.toList();
	}

	private PeerMentorDocumentResponse response(PeerMentorDocument row) {
		return row.getDocumentId() == null ? PeerMentorDocumentResponse.ofLink(row)
				: PeerMentorDocumentResponse.ofFile(row, requireFile(row));
	}

	private static PeerMentorDocument newRow(UUID mentorId, PeerEvidenceType evidenceType,
			PeerDocumentVisibility visibility) {
		PeerMentorDocument row = new PeerMentorDocument();
		row.setId(UUID.randomUUID());
		row.setUserId(mentorId);
		row.setEvidenceType(evidenceType);
		row.setVisibility(visibility);
		row.setCreatedAt(Instant.now());
		return row;
	}

	/**
	 * Students open these links from the mentor's profile: an http(s) address with a host, no credentials in it and
	 * nothing invisible (control or space characters) that could disguise where it goes.
	 */
	static String requireWebLink(String url) {
		InvalidRequestException invalid = new InvalidRequestException("Please enter a web address (https://…).");
		if (url == null || url.length() > 500 || !url.equals(url.strip()) || INVISIBLE.matcher(url).find()) {
			throw invalid;
		}
		URI uri;
		try {
			uri = new URI(url);
		} catch (URISyntaxException error) {
			throw invalid;
		}
		String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
		if (!(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null || uri.getHost().isBlank()
				|| uri.getRawUserInfo() != null) {
			throw invalid;
		}
		return url;
	}

	/** An upload whose transaction rolls back takes its stored object with it. */
	private void removeObjectIfRolledBack(String s3Key) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCompletion(int status) {
				if (status == STATUS_ROLLED_BACK) {
					deleteObject(s3Key);
				}
			}
		});
	}

	private void deleteObject(String s3Key) {
		try {
			objectStorage.delete(s3Key);
		} catch (RuntimeException error) {
			log.warn("Peer mentor evidence object {} remains in storage: {}", s3Key, error.getMessage());
		}
	}

	private void requireRoom(UUID mentorId) {
		if (peerMentorDocumentRepository.countByUserId(mentorId) >= MAX_EVIDENCE) {
			throw new InvalidRequestException("At most " + MAX_EVIDENCE + " documents and links per profile.");
		}
	}

	private PeerMentorDocument ownRow(UUID mentorId, UUID documentId) {
		return peerMentorDocumentRepository.findById(documentId)
				.filter(row -> row.getUserId().equals(mentorId))
				.orElseThrow(() -> notFound(documentId));
	}

	private StoredDocument requireFile(PeerMentorDocument row) {
		return storedDocumentRepository.findById(row.getDocumentId())
				.orElseThrow(() -> notFound(row.getId()));
	}

	/** The same gate as the rest of the mentor side: a student who holds MENTOR (the Akran Mentoru badge). */
	private AppUser requirePeerMentor(UUID actorId) {
		AppUser user = domainLookupService.requireUser(actorId);
		if (!user.has(UserRole.MENTOR) || !isStudent(user)) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, Texts.forRequest("error.peerMentoringStudentsOnly"));
		}
		return user;
	}

	private boolean isStudent(AppUser user) {
		return institutionDirectoryService.kindsOf(user.getId()).contains(IdentityKind.STUDENT);
	}

	private static ResourceNotFoundException notFound(UUID id) {
		return new ResourceNotFoundException("Peer mentor document not found: " + id);
	}
}
