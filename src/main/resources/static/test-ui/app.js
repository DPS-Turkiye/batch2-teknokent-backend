const state = {
	view: "overview",
	username: "",
	password: "",
	users: [],
	organizations: [],
};

const workspace = document.querySelector("#workspace");
const output = document.querySelector("#response-output");
const statusLabel = document.querySelector("#response-status");
const timeLabel = document.querySelector("#response-time");
const toast = document.querySelector("#toast");

const today = new Date().toISOString().slice(0, 10);
const tomorrowLocal = new Date(Date.now() + 86_400_000).toISOString().slice(0, 16);

function escapeHtml(value) {
	return String(value ?? "")
		.replaceAll("&", "&amp;")
		.replaceAll("<", "&lt;")
		.replaceAll(">", "&gt;")
		.replaceAll('"', "&quot;")
		.replaceAll("'", "&#039;");
}

function showToast(message) {
	toast.textContent = message;
	toast.classList.add("show");
	window.clearTimeout(showToast.timer);
	showToast.timer = window.setTimeout(() => toast.classList.remove("show"), 2300);
}

function updateInspector(method, path, status, data, elapsed) {
	document.querySelector("#request-method").value = method;
	document.querySelector("#request-path").value = path;
	statusLabel.textContent = status ? `${status}` : "Network error";
	statusLabel.className = `response-code ${status >= 200 && status < 300 ? "success" : "failure"}`;
	timeLabel.textContent = `${elapsed} ms`;
	output.textContent = typeof data === "string" ? data : JSON.stringify(data, null, 2);
}

async function api(path, { method = "GET", body, silent = false } = {}) {
	const startedAt = performance.now();
	const headers = { Accept: "application/json" };
	if (body !== undefined) headers["Content-Type"] = "application/json";
	if (state.username || state.password) {
		headers.Authorization = `Basic ${btoa(`${state.username}:${state.password}`)}`;
	}

	try {
		const response = await fetch(path, {
			method,
			headers,
			body: body === undefined ? undefined : JSON.stringify(body),
		});
		const raw = await response.text();
		let data = raw;
		if (raw) {
			try {
				data = JSON.parse(raw);
			} catch {
				// Keep non-JSON responses readable in the inspector.
			}
		} else {
			data = null;
		}
		const elapsed = Math.round(performance.now() - startedAt);
		if (!silent) updateInspector(method, path, response.status, data, elapsed);
		if (!response.ok) {
			const error = new Error(data?.message || `Request failed with ${response.status}`);
			error.status = response.status;
			throw error;
		}
		return data;
	} catch (error) {
		if (!silent && !error.status) {
			updateInspector(method, path, 0, error.message, Math.round(performance.now() - startedAt));
		}
		throw error;
	}
}

async function refreshHealth() {
	try {
		const response = await fetch("/api/health");
		const health = await response.json();
		setServiceStatus("#backend-status", health.status === "UP", `Backend ${health.status}`);
		setServiceStatus(
			"#database-status",
			health.components?.database === "UP",
			`Database ${health.components?.database || "UNKNOWN"}`,
		);
	} catch {
		setServiceStatus("#backend-status", false, "Backend DOWN");
		setServiceStatus("#database-status", false, "Database UNKNOWN");
	}
}

function setServiceStatus(selector, isUp, label) {
	const element = document.querySelector(selector);
	element.classList.toggle("up", isUp);
	element.classList.toggle("down", !isUp);
	element.innerHTML = `<i></i>${escapeHtml(label)}`;
}

async function ensureReferences() {
	const [users, organizations] = await Promise.all([
		api("/api/users", { silent: true }),
		api("/api/organizations", { silent: true }),
	]);
	state.users = users;
	state.organizations = organizations;
}

function pageHeading(title, description) {
	return `
		<div class="page-heading">
			<div>
				<h1>${escapeHtml(title)}</h1>
				<p>${escapeHtml(description)}</p>
			</div>
		</div>`;
}

function panel(title, description, content) {
	return `
		<section class="content-panel">
			<div class="panel-title">
				<h2>${escapeHtml(title)}</h2>
				${description ? `<p>${escapeHtml(description)}</p>` : ""}
			</div>
			${content}
		</section>`;
}

function table(rows, columns) {
	if (!rows?.length) return `<div class="empty-state">No records found.</div>`;
	return `
		<div class="table-wrap">
			<table>
				<thead><tr>${columns.map((column) => `<th>${escapeHtml(column.label)}</th>`).join("")}</tr></thead>
				<tbody>
					${rows.map((row) => `
						<tr>${columns.map((column) => `<td>${escapeHtml(column.value(row))}</td>`).join("")}</tr>
					`).join("")}
				</tbody>
			</table>
		</div>`;
}

function userOptions(role) {
	return state.users
		.filter((user) => !role || user.role === role)
		.map((user) => `<option value="${user.id}">${escapeHtml(user.fullName)} · ${user.role}</option>`)
		.join("");
}

function organizationOptions() {
	return state.organizations
		.map((organization) => `<option value="${organization.id}">${escapeHtml(organization.name)}</option>`)
		.join("");
}

function field(label, name, type = "text", value = "", options = "") {
	if (type === "textarea") {
		return `<label class="field full"><span>${escapeHtml(label)}</span><textarea name="${name}">${escapeHtml(value)}</textarea></label>`;
	}
	if (type === "select") {
		return `<label class="field"><span>${escapeHtml(label)}</span><select name="${name}">${options}</select></label>`;
	}
	return `<label class="field"><span>${escapeHtml(label)}</span><input name="${name}" type="${type}" value="${escapeHtml(value)}" ${type === "text" ? "" : ""}></label>`;
}

function form(content, buttonLabel) {
	return `
		<form id="entity-form" class="entity-form">
			${content}
			<div class="form-actions"><button type="submit">${escapeHtml(buttonLabel)}</button></div>
		</form>`;
}

async function renderOverview(silent) {
	const summary = await api("/api/dashboard/summary", { silent });
	const actions = await api("/api/actions", { silent: true });
	workspace.innerHTML = `
		${pageHeading("Overview", "Live totals from the prototype database.")}
		<div class="metric-grid">
			${[
				["Active mentorships", summary.activeMentorships],
				["Open bottlenecks", summary.openBottlenecks],
				["High severity", summary.highSeverityBottlenecks],
				["Overdue actions", summary.overdueActions],
				["Upcoming meetings", summary.upcomingMeetings],
			].map(([label, value]) => `<div class="metric"><span>${label}</span><strong>${value}</strong></div>`).join("")}
		</div>
		${panel("Recent action items", "Create more records from the entity sections.", table(actions.slice(0, 8), [
			{ label: "Title", value: (row) => row.title },
			{ label: "Owner", value: (row) => row.ownerName },
			{ label: "Status", value: (row) => row.status },
			{ label: "Due", value: (row) => row.dueDate || "—" },
		]))}`;
}

async function renderOrganizations(silent) {
	const organizations = await api("/api/organizations", { silent });
	state.organizations = organizations;
	workspace.innerHTML = `
		${pageHeading("Organizations", "Create and inspect startup records.")}
		${panel("Quick create", "Adds a new startup organization.", form(
			field("Name", "name") + field("Description", "description", "textarea"),
			"Create organization",
		))}
		${panel("Recent records", "", table(organizations, [
			{ label: "Name", value: (row) => row.name },
			{ label: "Description", value: (row) => row.description || "—" },
			{ label: "ID", value: (row) => row.id },
		]))}`;
	bindForm(async (data) => api("/api/organizations", {
		method: "POST",
		body: { name: data.get("name"), description: data.get("description") || null },
	}), "Organization created");
}

async function renderMentorships(silent) {
	await ensureReferences();
	const rows = await api("/api/mentorships", { silent });
	workspace.innerHTML = `
		${pageHeading("Mentorships", "Link a mentor to an organization.")}
		${panel("Quick create", "Dates use YYYY-MM-DD.", form(
			field("Mentor", "mentorId", "select", "", userOptions("MENTOR")) +
			field("Organization", "organizationId", "select", "", organizationOptions()) +
			field("Status", "status", "select", "", ["PLANNED", "ACTIVE", "PAUSED", "COMPLETED", "CANCELLED"].map((value) => `<option>${value}</option>`).join("")) +
			field("Start date", "startDate", "date", today) +
			field("End date (optional)", "endDate", "date") +
			field("Goals", "goals", "textarea"),
			"Create mentorship",
		))}
		${panel("Recent records", "", table(rows, [
			{ label: "Mentor", value: (row) => row.mentorName },
			{ label: "Organization", value: (row) => row.organizationName },
			{ label: "Status", value: (row) => row.status },
			{ label: "Start", value: (row) => row.startDate },
			{ label: "Goals", value: (row) => row.goals || "—" },
		]))}`;
	bindForm(async (data) => api("/api/mentorships", {
		method: "POST",
		body: {
			mentorId: data.get("mentorId"),
			organizationId: data.get("organizationId"),
			status: data.get("status"),
			startDate: data.get("startDate"),
			endDate: data.get("endDate") || null,
			goals: data.get("goals") || null,
		},
	}), "Mentorship created");
}

async function renderProgress(silent) {
	await ensureReferences();
	const organizationId = state.organizations[0]?.id;
	const rows = organizationId ? await api(`/api/organizations/${organizationId}/progress`, { silent }) : [];
	workspace.innerHTML = `
		${pageHeading("Progress", "Record dated startup progress updates.")}
		${panel("Quick create", "The list below shows the selected organization.", form(
			field("Organization", "organizationId", "select", "", organizationOptions()) +
			field("Author", "authorId", "select", "", userOptions()) +
			field("Category", "category", "text", "Product") +
			field("Status", "status", "select", "", ["ON_TRACK", "AT_RISK", "BLOCKED", "COMPLETED"].map((value) => `<option>${value}</option>`).join("")) +
			field("Recorded date", "recordedAt", "date", today) +
			field("Summary", "summary", "textarea") +
			field("Evidence", "evidence", "textarea"),
			"Create progress update",
		))}
		${panel("Recent records", "", table(rows, [
			{ label: "Category", value: (row) => row.category },
			{ label: "Status", value: (row) => row.status },
			{ label: "Summary", value: (row) => row.summary },
			{ label: "Author", value: (row) => row.authorName },
			{ label: "Date", value: (row) => row.recordedAt },
		]))}`;
	bindForm(async (data) => {
		const organization = data.get("organizationId");
		return api(`/api/organizations/${organization}/progress`, {
			method: "POST",
			body: {
				authorId: data.get("authorId"),
				category: data.get("category"),
				status: data.get("status"),
				summary: data.get("summary"),
				evidence: data.get("evidence") || null,
				recordedAt: data.get("recordedAt"),
			},
		});
	}, "Progress update created");
}

async function renderBottlenecks(silent) {
	await ensureReferences();
	const organizationId = state.organizations[0]?.id;
	const rows = organizationId ? await api(`/api/organizations/${organizationId}/bottlenecks`, { silent }) : [];
	workspace.innerHTML = `
		${pageHeading("Bottlenecks", "Capture blockers, owners, severity and target dates.")}
		${panel("Quick create", "New bottlenecks begin with OPEN status.", form(
			field("Organization", "organizationId", "select", "", organizationOptions()) +
			field("Owner", "ownerId", "select", "", `<option value="">Unassigned</option>${userOptions()}`) +
			field("Title", "title") +
			field("Severity", "severity", "select", "", ["LOW", "MEDIUM", "HIGH", "CRITICAL"].map((value) => `<option>${value}</option>`).join("")) +
			field("Target resolution", "targetResolutionDate", "date") +
			field("Description", "description", "textarea"),
			"Create bottleneck",
		))}
		${panel("Recent records", "", table(rows, [
			{ label: "Title", value: (row) => row.title },
			{ label: "Owner", value: (row) => row.ownerName || "Unassigned" },
			{ label: "Severity", value: (row) => row.severity },
			{ label: "Status", value: (row) => row.status },
			{ label: "Target", value: (row) => row.targetResolutionDate || "—" },
		]))}`;
	bindForm(async (data) => {
		const organization = data.get("organizationId");
		return api(`/api/organizations/${organization}/bottlenecks`, {
			method: "POST",
			body: {
				ownerId: data.get("ownerId") || null,
				title: data.get("title"),
				description: data.get("description") || null,
				severity: data.get("severity"),
				targetResolutionDate: data.get("targetResolutionDate") || null,
			},
		});
	}, "Bottleneck created");
}

async function renderMeetings(silent) {
	await ensureReferences();
	const rows = await api("/api/meetings", { silent });
	workspace.innerHTML = `
		${pageHeading("Meetings", "Schedule local meeting records and attach a Meet link.")}
		${panel("Quick create", "Calendar synchronization is intentionally not included yet.", form(
			field("Organization", "organizationId", "select", "", organizationOptions()) +
			field("Mentor", "mentorId", "select", "", `<option value="">No mentor</option>${userOptions("MENTOR")}`) +
			field("Scheduled at", "scheduledAt", "datetime-local", tomorrowLocal) +
			field("Duration (minutes)", "durationMinutes", "number", "60") +
			field("Status", "status", "select", "", ["SCHEDULED", "COMPLETED", "CANCELLED"].map((value) => `<option>${value}</option>`).join("")) +
			field("Meet link", "meetLink", "url") +
			field("Agenda", "agenda", "textarea"),
			"Create meeting",
		))}
		${panel("Recent records", "", table(rows, [
			{ label: "Organization", value: (row) => row.organizationName },
			{ label: "Mentor", value: (row) => row.mentorName || "—" },
			{ label: "Scheduled", value: (row) => row.scheduledAt },
			{ label: "Status", value: (row) => row.status },
			{ label: "ID", value: (row) => row.id },
		]))}`;
	bindForm(async (data) => api("/api/meetings", {
		method: "POST",
		body: {
			organizationId: data.get("organizationId"),
			mentorId: data.get("mentorId") || null,
			scheduledAt: new Date(data.get("scheduledAt")).toISOString(),
			durationMinutes: Number(data.get("durationMinutes")),
			agenda: data.get("agenda") || null,
			notes: null,
			outcomes: null,
			meetLink: data.get("meetLink") || null,
			status: data.get("status"),
		},
	}), "Meeting created");
}

async function renderActions(silent) {
	await ensureReferences();
	const [rows, meetings] = await Promise.all([
		api("/api/actions", { silent }),
		api("/api/meetings", { silent: true }),
	]);
	const meetingOptions = meetings.map((meeting) =>
		`<option value="${meeting.id}">${escapeHtml(meeting.organizationName)} · ${escapeHtml(meeting.scheduledAt)}</option>`
	).join("");
	workspace.innerHTML = `
		${pageHeading("Actions", "Assign follow-up work with an optional meeting link.")}
		${panel("Quick create", "New action items begin with OPEN status.", form(
			field("Organization", "organizationId", "select", "", organizationOptions()) +
			field("Owner", "ownerId", "select", "", userOptions()) +
			field("Meeting", "meetingId", "select", "", `<option value="">No meeting</option>${meetingOptions}`) +
			field("Due date", "dueDate", "date") +
			field("Title", "title") +
			field("Description", "description", "textarea"),
			"Create action item",
		))}
		${panel("Recent records", "", table(rows, [
			{ label: "Title", value: (row) => row.title },
			{ label: "Owner", value: (row) => row.ownerName },
			{ label: "Organization", value: (row) => row.organizationName },
			{ label: "Status", value: (row) => row.status },
			{ label: "Due", value: (row) => row.dueDate || "—" },
			{ label: "ID", value: (row) => row.id },
		]))}`;
	bindForm(async (data) => api("/api/actions", {
		method: "POST",
		body: {
			organizationId: data.get("organizationId"),
			meetingId: data.get("meetingId") || null,
			ownerId: data.get("ownerId"),
			title: data.get("title"),
			description: data.get("description") || null,
			dueDate: data.get("dueDate") || null,
		},
	}), "Action item created");
}

function bindForm(submit, successMessage) {
	document.querySelector("#entity-form")?.addEventListener("submit", async (event) => {
		event.preventDefault();
		const button = event.currentTarget.querySelector("button[type=submit]");
		button.disabled = true;
		try {
			await submit(new FormData(event.currentTarget));
			showToast(successMessage);
			await render(state.view, true);
			await refreshHealth();
		} catch (error) {
			showToast(error.message);
		} finally {
			button.disabled = false;
		}
	});
}

async function render(view, silent = false) {
	state.view = view;
	document.querySelectorAll(".nav-item").forEach((item) => {
		item.classList.toggle("active", item.dataset.view === view);
	});
	workspace.innerHTML = `<div class="empty-state">Loading ${escapeHtml(view)}…</div>`;

	try {
		const renderers = {
			overview: renderOverview,
			organizations: renderOrganizations,
			mentorships: renderMentorships,
			progress: renderProgress,
			bottlenecks: renderBottlenecks,
			meetings: renderMeetings,
			actions: renderActions,
		};
		await renderers[view](silent);
	} catch (error) {
		workspace.innerHTML = `
			${pageHeading("Connection required", "Enter the prototype credentials and connect.")}
			${panel("Request failed", "", `<div class="empty-state">${escapeHtml(error.message)}</div>`)}`;
	}
}

document.querySelectorAll(".nav-item").forEach((item) => {
	item.addEventListener("click", () => render(item.dataset.view));
});

document.querySelector("#auth-form").addEventListener("submit", async (event) => {
	event.preventDefault();
	state.username = document.querySelector("#username").value;
	state.password = document.querySelector("#password").value;
	try {
		await ensureReferences();
		showToast("Connected to the backend");
		await render(state.view);
	} catch (error) {
		showToast(error.status === 401 ? "Incorrect username or password" : error.message);
	}
});

document.querySelector("#request-form").addEventListener("submit", async (event) => {
	event.preventDefault();
	const method = document.querySelector("#request-method").value;
	const path = document.querySelector("#request-path").value.trim();
	const rawBody = document.querySelector("#request-body").value.trim();
	try {
		const body = rawBody ? JSON.parse(rawBody) : undefined;
		await api(path, { method, body });
	} catch (error) {
		if (error instanceof SyntaxError) {
			showToast("The request body is not valid JSON");
		}
	}
});

document.querySelector("#request-method").addEventListener("change", (event) => {
	document.querySelector("#request-body").disabled = event.target.value === "GET";
});

document.querySelector("#copy-response").addEventListener("click", async () => {
	await navigator.clipboard.writeText(output.textContent);
	showToast("Response copied");
});

refreshHealth();
render("overview");
window.setInterval(refreshHealth, 30_000);
