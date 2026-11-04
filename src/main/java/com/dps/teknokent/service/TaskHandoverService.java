package com.dps.teknokent.service;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.dps.teknokent.domain.ActionItemStatus;
import com.dps.teknokent.domain.NotificationCategory;
import com.dps.teknokent.entity.ActionItem;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.repository.ActionItemRepository;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.OrganizationRepository;
import com.dps.teknokent.util.JsonUtils;
import com.dps.teknokent.util.Texts;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The open tasks of someone who left a team are marked as theirs-but-left at once, then handed to the members who
 * stay: the AI picks a member per task, and the team lead takes whatever it cannot place.
 */
@Slf4j
@Service
@RequiredArgsConstructor
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
	private final JsonUtils jsonUtils;

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

	// After commit and in its own transaction, so a slow or failing AI call never holds or undoes the leave.
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void onMemberLeft(TeamMemberLeftEvent event) {
		Organization organization = organizationRepository.findById(event.organizationId()).orElse(null);
		List<ActionItem> tasks = openTasks(event.organizationId(), event.leaverId());
		List<AppUser> stay = appUserRepository.findByOrganizationIdOrderByFullNameAsc(event.organizationId());
		if (organization == null || tasks.isEmpty() || stay.isEmpty()) {
			return;
		}
		AppUser fallback = Optional.ofNullable(organization.getFounder())
				.filter(founder -> stay.stream().anyMatch(member -> member.getId().equals(founder.getId())))
				.orElse(stay.get(0));
		Map<UUID, UUID> picked = suggest(tasks, stay, fallback);
		Map<UUID, AppUser> byId = new HashMap<>();
		stay.forEach(member -> byId.put(member.getId(), member));

		Map<AppUser, Integer> handedTo = new LinkedHashMap<>();
		for (ActionItem task : tasks) {
			AppUser owner = byId.getOrDefault(picked.get(task.getId()), fallback);
			task.setOwner(owner);
			handedTo.merge(owner, 1, Integer::sum);
		}
		actionItemRepository.saveAll(tasks);

		String leaverName = tasks.get(0).getPreviousOwner() == null ? "" : tasks.get(0).getPreviousOwner().getFullName();
		handedTo.forEach((owner, count) -> notificationService.create(owner, NotificationCategory.TASK_ASSIGNED,
				Texts.get(owner, "notification.tasksHandedOver.title"),
				Texts.get(owner, "notification.tasksHandedOver.body", leaverName, count), "/startup/process"));
	}

	/** Task id → member id; empty when the AI is unavailable, so every task goes to the fallback. */
	private Map<UUID, UUID> suggest(List<ActionItem> tasks, List<AppUser> stay, AppUser lead) {
		Map<String, UUID> memberIds = new LinkedHashMap<>();
		Map<String, UUID> taskIds = new LinkedHashMap<>();
		StringBuilder input = new StringBuilder("Members (id, job title, open tasks, team lead):\n");
		List<AppUser> members = stay.stream().sorted(Comparator.comparing(AppUser::getFullName)).toList();
		for (int index = 0; index < members.size(); index++) {
			AppUser member = members.get(index);
			String id = "M" + (index + 1);
			memberIds.put(id, member.getId());
			int load = actionItemRepository
					.findByOrganizationIdAndOwnerIdAndStatusIn(tasks.get(0).getOrganization().getId(), member.getId(),
							OPEN)
					.size();
			input.append(id).append('\t').append(Optional.ofNullable(member.getJobTitle()).orElse("-"))
					.append('\t').append(load).append('\t').append(member.getId().equals(lead.getId()) ? "yes" : "no")
					.append('\n');
		}
		input.append("Tasks (id, title, description):\n");
		for (int index = 0; index < tasks.size(); index++) {
			ActionItem task = tasks.get(index);
			String id = "T" + (index + 1);
			taskIds.put(id, task.getId());
			input.append(id).append('\t').append(clean(task.getTitle())).append('\t')
					.append(clean(task.getDescription())).append('\n');
		}
		try {
			JsonNode result = aiProviderClient.taskHandover(input.toString(), schema());
			Map<UUID, UUID> picked = new HashMap<>();
			for (JsonNode row : result.path("assignments")) {
				UUID taskId = taskIds.get(row.path("task").asText(""));
				UUID memberId = memberIds.get(row.path("member").asText(""));
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

	private JsonNode schema() {
		ObjectNode assignment = jsonUtils.createObjectNode().put("type", "object").put("additionalProperties", false);
		ObjectNode properties = assignment.putObject("properties");
		properties.putObject("task").put("type", "string");
		properties.putObject("member").put("type", "string");
		assignment.putArray("required").add("task").add("member");

		ObjectNode root = jsonUtils.createObjectNode().put("type", "object").put("additionalProperties", false);
		ArrayNode required = root.putArray("required");
		required.add("assignments");
		root.putObject("properties").putObject("assignments").put("type", "array").set("items", assignment);
		return root;
	}
}
