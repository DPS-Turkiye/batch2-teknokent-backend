package com.dps.teknokent.service;

import static com.dps.teknokent.util.JsonSchema.arrayOf;
import static com.dps.teknokent.util.JsonSchema.object;
import static com.dps.teknokent.util.JsonSchema.property;
import static com.dps.teknokent.util.JsonSchema.string;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import com.dps.teknokent.domain.ActionItemStatus;
import com.dps.teknokent.domain.AiFeature;
import com.dps.teknokent.domain.NotificationCategory;
import com.dps.teknokent.entity.ActionItem;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.repository.ActionItemRepository;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.OrganizationRepository;
import com.dps.teknokent.util.Texts;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;

/**
 * The open tasks of someone who left a team are marked as theirs-but-left at once, then handed to the members who
 * stay: the AI picks a member per task, and the team lead takes whatever it cannot place.
 */
@Slf4j
@Service
public class TaskHandoverService {

	static final List<ActionItemStatus> OPEN = List.of(ActionItemStatus.OPEN, ActionItemStatus.IN_PROGRESS);
	private static final int MAX_TASK_TEXT = 300;

	private final ActionItemRepository actionItemRepository;
	private final AppUserRepository appUserRepository;
	private final OrganizationRepository organizationRepository;
	private final AiProviderClient aiProviderClient;
	private final LocalPiiRedactionService piiRedactionService;
	private final NotificationService notificationService;
	private final ApplicationEventPublisher eventPublisher;
	private final AiFeatures aiFeatures;
	private final TransactionTemplate reading;
	private final TransactionTemplate writing;
	private final Executor background;

	public TaskHandoverService(ActionItemRepository actionItemRepository, AppUserRepository appUserRepository,
			OrganizationRepository organizationRepository, AiProviderClient aiProviderClient,
			LocalPiiRedactionService piiRedactionService, NotificationService notificationService,
			ApplicationEventPublisher eventPublisher, AiFeatures aiFeatures,
			PlatformTransactionManager transactionManager, @Qualifier("aiTaskExecutor") Executor background) {
		this.actionItemRepository = actionItemRepository;
		this.appUserRepository = appUserRepository;
		this.organizationRepository = organizationRepository;
		this.aiProviderClient = aiProviderClient;
		this.piiRedactionService = piiRedactionService;
		this.notificationService = notificationService;
		this.eventPublisher = eventPublisher;
		this.aiFeatures = aiFeatures;
		this.reading = new TransactionTemplate(transactionManager);
		this.reading.setReadOnly(true);
		this.writing = new TransactionTemplate(transactionManager);
		this.background = background;
	}

	/** Inside the leave's transaction: the to-do list shows the tasks as left right away. */
	public void markLeft(AppUser leaver, Organization organization) {
		Instant now = Instant.now();
		List<ActionItem> tasks = openTasks(organization.getId(), leaver.getId());
		for (ActionItem task : tasks) {
			task.setPreviousOwner(leaver);
			task.setOwnerLeftAt(now);
		}
		actionItemRepository.saveAll(tasks);
		if (!tasks.isEmpty()) {
			eventPublisher.publishEvent(new TeamMemberLeftEvent(organization.getId(), leaver.getId()));
		}
	}

	/**
	 * After commit and on a background thread, so a slow or failing AI call never holds the leave's response or
	 * undoes it. The AI call runs between two short transactions and holds no database connection while it waits.
	 */
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	public void onMemberLeft(TeamMemberLeftEvent event) {
		background.execute(() -> handOver(event));
	}

	private void handOver(TeamMemberLeftEvent event) {
		try {
			Optional<Prompt> prompt = reading.execute(status -> prompt(event));
			Map<UUID, UUID> picked = prompt.map(this::suggest).orElse(Map.of());
			writing.executeWithoutResult(status -> assign(event, picked));
		} catch (RuntimeException failure) {
			log.warn("Task handover failed for team {}", event.organizationId(), failure);
		}
	}

	/** What the AI is asked, with the short ids it answers in; none when it isn't asked. */
	private record Prompt(String input, Map<String, UUID> taskIds, Map<String, UUID> memberIds) {
	}

	private Optional<Prompt> prompt(TeamMemberLeftEvent event) {
		if (!aiFeatures.isEnabled(AiFeature.TASK_HANDOVER)) {
			return Optional.empty();
		}
		Team team = team(event);
		if (team == null) {
			return Optional.empty();
		}
		Map<UUID, Integer> load = new HashMap<>();
		for (ActionItem open : actionItemRepository.findByOrganizationIdAndStatusIn(event.organizationId(), OPEN)) {
			load.merge(open.getOwner().getId(), 1, Integer::sum);
		}
		Map<String, UUID> memberIds = new LinkedHashMap<>();
		Map<String, UUID> taskIds = new LinkedHashMap<>();
		StringBuilder input = new StringBuilder("Members (id, job title, open tasks, team lead):\n");
		List<AppUser> members = team.stay().stream().sorted(Comparator.comparing(AppUser::getFullName)).toList();
		for (int index = 0; index < members.size(); index++) {
			AppUser member = members.get(index);
			String id = "M" + (index + 1);
			memberIds.put(id, member.getId());
			input.append(id).append('\t').append(Optional.ofNullable(member.getJobTitle()).orElse("-"))
					.append('\t').append(load.getOrDefault(member.getId(), 0)).append('\t')
					.append(member.getId().equals(team.lead().getId()) ? "yes" : "no").append('\n');
		}
		input.append("Tasks (id, title, description):\n");
		for (int index = 0; index < team.tasks().size(); index++) {
			ActionItem task = team.tasks().get(index);
			String id = "T" + (index + 1);
			taskIds.put(id, task.getId());
			input.append(id).append('\t').append(clean(task.getTitle())).append('\t')
					.append(clean(task.getDescription())).append('\n');
		}
		return Optional.of(new Prompt(input.toString(), taskIds, memberIds));
	}

	/** Task id → member id; empty when the AI fails, so every task goes to the lead. */
	private Map<UUID, UUID> suggest(Prompt prompt) {
		try {
			JsonNode result = aiProviderClient.taskHandover(prompt.input(), schema());
			Map<UUID, UUID> picked = new HashMap<>();
			for (JsonNode row : result.path("assignments")) {
				UUID taskId = prompt.taskIds().get(row.path("task").asString(""));
				UUID memberId = prompt.memberIds().get(row.path("member").asString(""));
				if (taskId != null && memberId != null) {
					picked.putIfAbsent(taskId, memberId);
				}
			}
			return picked;
		} catch (RuntimeException failure) {
			log.warn("Task handover suggestion failed; tasks go to the team lead: {}", failure.getMessage());
			return Map.of();
		}
	}

	/**
	 * Read again after the AI call: a task closed or a member gone in the meantime is not assigned from the stale
	 * answer. A task the AI did not place, or placed on someone who has left since, goes to the lead.
	 */
	private void assign(TeamMemberLeftEvent event, Map<UUID, UUID> picked) {
		Team team = team(event);
		if (team == null) {
			return;
		}
		Map<UUID, AppUser> byId = new HashMap<>();
		team.stay().forEach(member -> byId.put(member.getId(), member));

		Map<AppUser, Integer> handedTo = new LinkedHashMap<>();
		for (ActionItem task : team.tasks()) {
			AppUser owner = byId.getOrDefault(picked.get(task.getId()), team.lead());
			task.setOwner(owner);
			handedTo.merge(owner, 1, Integer::sum);
		}
		actionItemRepository.saveAll(team.tasks());

		ActionItem first = team.tasks().get(0);
		String leaverName = first.getPreviousOwner() == null ? "" : first.getPreviousOwner().getFullName();
		handedTo.forEach((owner, count) -> notificationService.create(owner, NotificationCategory.TASK_ASSIGNED,
				Texts.get(owner, "notification.tasksHandedOver.title"),
				Texts.get(owner, "notification.tasksHandedOver.body", leaverName, count), "/startup/process"));
	}

	/** The leaver's open tasks, the members who stay and who takes what nobody else does; null if nothing to do. */
	private record Team(List<ActionItem> tasks, List<AppUser> stay, AppUser lead) {
	}

	private @Nullable Team team(TeamMemberLeftEvent event) {
		Organization organization = organizationRepository.findById(event.organizationId()).orElse(null);
		List<ActionItem> tasks = openTasks(event.organizationId(), event.leaverId());
		List<AppUser> stay = appUserRepository.findByOrganizationIdOrderByFullNameAsc(event.organizationId());
		if (organization == null || tasks.isEmpty() || stay.isEmpty()) {
			return null;
		}
		AppUser lead = Optional.ofNullable(organization.getFounder())
				.filter(founder -> stay.stream().anyMatch(member -> member.getId().equals(founder.getId())))
				.orElse(stay.get(0));
		return new Team(tasks, stay, lead);
	}

	private String clean(String text) {
		if (text == null || text.isBlank()) {
			return "-";
		}
		String redacted = piiRedactionService.redact(text).text().replaceAll("\\s+", " ").trim();
		return redacted.length() > MAX_TASK_TEXT ? redacted.substring(0, MAX_TASK_TEXT) : redacted;
	}

	private List<ActionItem> openTasks(UUID organizationId, UUID ownerId) {
		return actionItemRepository.findByOrganizationIdAndOwnerIdAndStatusIn(organizationId, ownerId, OPEN);
	}

	private static JsonNode schema() {
		return object(property("assignments",
				arrayOf(object(property("task", string()), property("member", string())))));
	}
}
