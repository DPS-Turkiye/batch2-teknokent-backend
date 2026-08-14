const state = {
  mentors: [],
  mentees: [],
  expected: null,
  filenames: { mentors: "sample mentors.json", mentees: "sample mentees.json", expected: "sample expected_matches.json" },
  report: null,
  activeMenteeId: null,
  preferences: {},
};

const form = document.querySelector("#match-form");
const runButton = document.querySelector("#run-button");
const runStatus = document.querySelector("#run-status");
const sampleButton = document.querySelector("#sample-button");
const parsedButton = document.querySelector("#parsed-button");
const libraryStatus = document.querySelector("#library-status");
const emptyState = document.querySelector("#empty-state");
const results = document.querySelector("#results");
const metricStrip = document.querySelector("#metric-strip");
const menteeList = document.querySelector("#mentee-list");
const menteeCount = document.querySelector("#mentee-count");
const menteeSearch = document.querySelector("#mentee-search");
const rankingHeading = document.querySelector("#ranking-heading");
const rankingList = document.querySelector("#ranking-list");
const metricsContent = document.querySelector("#metrics-content");
const rawJson = document.querySelector("#raw-json");
const downloadJson = document.querySelector("#download-json");
const embeddingModel = document.querySelector("#embedding-model");
const modelCatalogStatus = document.querySelector("#model-catalog-status");
const preferenceMentee = document.querySelector("#preference-mentee");
const preferenceFields = ["desired-role", "skills-to-develop", "career-goals", "preferred-industry", "preferred-language", "meeting-format"];

function setStatus(message, isError = false) {
  runStatus.textContent = message;
  runStatus.classList.toggle("is-error", isError);
}

function setLoading(loading) {
  runButton.disabled = loading;
  runButton.classList.toggle("is-running", loading);
  runButton.querySelector("span").textContent = loading ? "Running matching…" : "Run matching";
  for (const input of form.querySelectorAll("input")) input.disabled = loading;
}

function profileId(record, kind) {
  return String(record[`${kind}_id`] || record.profile_id || record.id || "Unknown ID");
}

function listValue(value) {
  return String(value || "").split(",").map((item) => item.trim()).filter(Boolean);
}

function saveVisiblePreferences() {
  const id = preferenceMentee.value;
  if (!id) return;
  state.preferences[id] = {
    desired_role: document.querySelector("#desired-role").value.trim(),
    skills_to_develop: listValue(document.querySelector("#skills-to-develop").value),
    career_goals: listValue(document.querySelector("#career-goals").value),
    preferred_industry: listValue(document.querySelector("#preferred-industry").value),
    preferred_language: listValue(document.querySelector("#preferred-language").value),
    meeting_format: document.querySelector("#meeting-format").value,
  };
}

function showPreferences() {
  const value = state.preferences[preferenceMentee.value] || {};
  document.querySelector("#desired-role").value = value.desired_role || "";
  document.querySelector("#skills-to-develop").value = (value.skills_to_develop || []).join(", ");
  document.querySelector("#career-goals").value = (value.career_goals || []).join(", ");
  document.querySelector("#preferred-industry").value = (value.preferred_industry || []).join(", ");
  document.querySelector("#preferred-language").value = (value.preferred_language || []).join(", ");
  document.querySelector("#meeting-format").value = value.meeting_format || "";
}

function renderPreferenceMentees() {
  saveVisiblePreferences();
  const selected = preferenceMentee.value;
  preferenceMentee.replaceChildren();
  state.mentees.forEach((record) => {
    const id = profileId(record, "mentee");
    const option = new Option(id, id);
    option.selected = id === selected;
    preferenceMentee.append(option);
  });
  showPreferences();
}

function menteesWithPreferences() {
  saveVisiblePreferences();
  return state.mentees.map((record) => ({
    ...record,
    matching_preferences: {
      ...(record.matching_preferences || {}),
      ...(state.preferences[profileId(record, "mentee")] || {}),
    },
  }));
}

function icon(paths) {
  const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  svg.setAttribute("viewBox", "0 0 40 40");
  for (const pathData of paths) {
    const path = document.createElementNS("http://www.w3.org/2000/svg", "path");
    path.setAttribute("d", pathData);
    svg.append(path);
  }
  return svg;
}

function renderFileSummary(kind) {
  const summaryIds = {
    mentors: "#mentor-summary",
    mentees: "#mentee-summary",
    expected: "#expected-summary",
  };
  const summary = document.querySelector(summaryIds[kind]);
  summary.replaceChildren();
  const value = state[kind];
  if (kind === "expected" ? !value : !value.length) return;
  const row = document.createElement("div");
  row.className = "file-row";
  row.append(icon(["M9 4h15l7 7v25H9z", "M24 4v8h7"]));
  const name = document.createElement("strong");
  name.textContent = state.filenames[kind];
  const count = document.createElement("span");
  const records = kind === "expected" ? Object.keys(value).length : value.length;
  count.textContent = `${records} record${records === 1 ? "" : "s"}`;
  const check = document.createElement("i");
  check.textContent = "✓";
  row.append(name, count, check);
  const recordCount = document.createElement("small");
  recordCount.className = "record-count";
  recordCount.textContent = `${records} ${kind === "expected" ? "labelled mentee" : kind.slice(0, -1)} record${records === 1 ? "" : "s"}`;
  summary.append(row, recordCount);
}

function renderAllFileSummaries() {
  renderFileSummary("mentors");
  renderFileSummary("mentees");
  renderFileSummary("expected");
}

async function loadSamples() {
  setStatus("Loading sample profiles…");
  try {
    const response = await fetch("/api/samples");
    const data = await response.json();
    if (!response.ok) throw new Error(data.detail || "Could not load samples.");
    state.mentors = data.mentors;
    state.mentees = data.mentees;
    state.expected = data.expected;
    state.filenames = { mentors: "sample mentors.json", mentees: "sample mentees.json", expected: "sample expected_matches.json" };
    renderAllFileSummaries();
    renderPreferenceMentees();
    setStatus("Sample profiles are ready. Choose a provider and run matching.");
  } catch (error) {
    setStatus(error.message, true);
  }
}

async function loadProviderConfig() {
  try {
    const response = await fetch("/api/config");
    const config = await response.json();
    if (!response.ok) throw new Error(config.detail || "Could not load provider settings.");
    const option = form.querySelector(`input[name="provider"][value="${config.provider}"]`);
    if (option) option.checked = true;
    renderModelOptions(config.models, config.embedding_model);
    modelCatalogStatus.textContent = config.catalog_error || `${config.models.length} compatible model${config.models.length === 1 ? "" : "s"} available.`;
    if (!config.configured) {
      setStatus(`${config.key_environment_variable} is not configured; offline testing still works.`, true);
    }
  } catch (error) {
    setStatus(error.message, true);
  }
}

function renderModelOptions(models, selectedModel) {
  embeddingModel.replaceChildren();
  for (const model of models) {
    const option = document.createElement("option");
    option.value = model.id;
    option.textContent = model.name === model.id ? model.id : `${model.name} — ${model.id}`;
    option.selected = model.id === selectedModel;
    embeddingModel.append(option);
  }
}

async function loadModelsForProvider(provider) {
  if (provider === "ollama") {
    embeddingModel.replaceChildren(new Option("bge-m3", "bge-m3"));
    embeddingModel.disabled = false;
    modelCatalogStatus.textContent = "Local BGE-M3 via Ollama; no profile text leaves this machine.";
    return;
  }
  if (provider === "deterministic") {
    embeddingModel.replaceChildren(new Option("deterministic-hash-v1", "deterministic-hash-v1"));
    embeddingModel.disabled = true;
    modelCatalogStatus.textContent = "Offline test uses its fixed deterministic model.";
    return;
  }
  embeddingModel.disabled = true;
  modelCatalogStatus.textContent = "Loading models available to the configured key…";
  try {
    const response = await fetch(`/api/models?provider=${encodeURIComponent(provider)}`);
    const data = await response.json();
    if (!response.ok) throw new Error(data.detail || "Could not load models.");
    renderModelOptions(data.models, data.default_model);
    modelCatalogStatus.textContent = data.catalog_error || `${data.models.length} compatible model${data.models.length === 1 ? "" : "s"} available.`;
  } catch (error) {
    modelCatalogStatus.textContent = error.message;
  } finally {
    embeddingModel.disabled = false;
  }
}

async function fetchParsedProfiles() {
  const response = await fetch("/api/parsed-profiles");
  const data = await response.json();
  if (!response.ok) throw new Error(data.detail || "Could not read the parsed-profile library.");
  libraryStatus.textContent = `${data.mentor_count} mentors · ${data.mentee_count} mentees`;
  return data;
}

async function loadParsedProfiles() {
  parsedButton.disabled = true;
  setStatus("Loading profiles from PDF Parse Lab…");
  try {
    const data = await fetchParsedProfiles();
    if (!data.mentor_count || !data.mentee_count) {
      const missing = [!data.mentor_count ? "mentor" : "", !data.mentee_count ? "mentee" : ""].filter(Boolean).join(" and ");
      throw new Error(`The matching library still needs ${missing} profiles. Parse and add that profile type first.`);
    }
    state.mentors = data.mentors;
    state.mentees = data.mentees;
    state.expected = null;
    state.filenames = {
      mentors: "PDF Parse Lab mentor library",
      mentees: "PDF Parse Lab mentee library",
      expected: "",
    };
    renderAllFileSummaries();
    renderPreferenceMentees();
    setStatus(`Loaded ${data.mentor_count} parsed mentors and ${data.mentee_count} parsed mentees. Ready to match.`);
  } catch (error) {
    setStatus(error.message, true);
  } finally {
    parsedButton.disabled = false;
  }
}

async function initialize() {
  await loadSamples();
  await loadProviderConfig();
  try {
    await fetchParsedProfiles();
  } catch (error) {
    libraryStatus.textContent = "Library unavailable";
  }
  if (new URLSearchParams(window.location.search).get("import") === "1") {
    await loadParsedProfiles();
  }
}

async function readJsonFile(file, kind) {
  if (!file || (!file.name.toLowerCase().endsWith(".json") && file.type !== "application/json")) {
    throw new Error("Choose a JSON file.");
  }
  if (file.size > 8 * 1024 * 1024) throw new Error("JSON files must be under 8 MB.");
  let data;
  try {
    data = JSON.parse(await file.text());
  } catch {
    throw new Error(`${file.name} is not valid JSON.`);
  }
  if (kind === "expected") {
    if (!data || Array.isArray(data) || typeof data !== "object") throw new Error("Expected matches must be a JSON object.");
  } else if (!Array.isArray(data) || !data.length || !data.every((item) => item && typeof item === "object" && !Array.isArray(item))) {
    throw new Error(`${kind === "mentors" ? "Mentor" : "Mentee"} profiles must be a non-empty JSON array.`);
  }
  state[kind] = data;
  state.filenames[kind] = file.name;
  renderFileSummary(kind);
  if (kind === "mentees") renderPreferenceMentees();
  setStatus(`${file.name} loaded.`);
}

function wireFileInput(inputId, kind) {
  const input = document.querySelector(inputId);
  const zone = input.closest(".drop-zone");
  input.addEventListener("change", async () => {
    try { await readJsonFile(input.files[0], kind); } catch (error) { setStatus(error.message, true); }
    input.value = "";
  });
  for (const eventName of ["dragenter", "dragover"]) {
    zone.addEventListener(eventName, (event) => { event.preventDefault(); zone.classList.add("is-dragging"); });
  }
  for (const eventName of ["dragleave", "drop"]) {
    zone.addEventListener(eventName, (event) => { event.preventDefault(); zone.classList.remove("is-dragging"); });
  }
  zone.addEventListener("drop", async (event) => {
    try { await readJsonFile(event.dataTransfer.files[0], kind); } catch (error) { setStatus(error.message, true); }
  });
}

function addMetric(label, value, paths) {
  const metric = document.createElement("div");
  metric.className = "summary-metric";
  metric.append(icon(paths));
  const labelNode = document.createElement("span");
  labelNode.textContent = label;
  const valueNode = document.createElement("strong");
  valueNode.textContent = value;
  metric.append(labelNode, valueNode);
  metricStrip.append(metric);
}

function renderSummaryMetrics() {
  metricStrip.replaceChildren();
  const metrics = state.report.metrics;
  addMetric("Mentees evaluated", String(state.report.results.length), ["M5 33c1-8 5-12 11-12s10 4 11 12", "M10 10a6 6 0 1 0 12 0 6 6 0 0 0-12 0", "M26 20c5 0 8 4 9 10", "M26 8a5 5 0 1 1 0 10"]);
  addMetric("Recall@3", metrics ? Number(metrics.macro_recall_at_3).toFixed(2) : "Not labelled", ["M4 30 14 19l7 7 11-15", "M29 13h7v7"]);
  addMetric("Mentor cache records", String(state.report.cache_record_count), ["M6 9c0-3 6-5 14-5s14 2 14 5-6 5-14 5S6 12 6 9", "M6 9v11c0 3 6 5 14 5s14-2 14-5V9", "M6 20v10c0 3 6 5 14 5s14-2 14-5V20"]);
}

function sharedLabels(sharedFields) {
  const values = [];
  for (const [key, items] of Object.entries(sharedFields || {})) {
    if (items.length) values.push(key.replaceAll("_", " "));
  }
  return values;
}

function renderExactFields(container, sharedFields) {
  const heading = document.createElement("h3");
  heading.textContent = "Exact shared fields";
  container.append(heading);
  const entries = Object.entries(sharedFields || {});
  if (!entries.length) {
    const empty = document.createElement("p");
    empty.className = "no-shared";
    empty.textContent = "No exact shared values. Ranking may still reflect semantic similarity.";
    container.append(empty);
    return;
  }
  const list = document.createElement("dl");
  for (const [key, values] of entries) {
    const term = document.createElement("dt");
    term.textContent = key.replaceAll("_", " ");
    const definition = document.createElement("dd");
    definition.textContent = values.join(", ");
    list.append(term, definition);
  }
  container.append(list);
}

function renderRanking() {
  const result = state.report.results.find((item) => item.mentee_id === state.activeMenteeId);
  rankingList.replaceChildren();
  if (!result) return;
  rankingHeading.textContent = `Top 3 recommendations for ${result.mentee_id}`;
  if (!result.recommendations.length) {
    const empty = document.createElement("p");
    empty.className = "section-note";
    empty.textContent = "No usable mentor profiles were available.";
    rankingList.append(empty);
    return;
  }
  result.recommendations.forEach((recommendation, index) => {
    const row = document.createElement("article");
    row.className = `ranking-row${index === 0 ? " is-open" : ""}`;
    const summary = document.createElement("button");
    summary.className = "ranking-summary";
    summary.type = "button";
    summary.setAttribute("aria-expanded", String(index === 0));
    const rank = document.createElement("span");
    rank.className = "rank-number";
    rank.textContent = String(recommendation.ranking_position);
    const identity = document.createElement("div");
    identity.className = "mentor-identity";
    const mentorId = document.createElement("strong");
    mentorId.textContent = recommendation.mentor_id;
    const score = document.createElement("span");
    score.textContent = `${(Number(recommendation.final_score) * 100).toFixed(1)} / 100`;
    const scoreLabel = document.createElement("small");
    scoreLabel.textContent = "hybrid ranking score";
    identity.append(mentorId, score, scoreLabel);
    const shared = document.createElement("div");
    shared.className = "shared-preview";
    const sharedTitle = document.createElement("small");
    sharedTitle.textContent = "Shared fields";
    const labels = document.createElement("div");
    labels.className = "shared-values";
    const fields = sharedLabels(recommendation.shared_fields);
    if (fields.length) {
      fields.forEach((field) => { const tag = document.createElement("span"); tag.textContent = field; labels.append(tag); });
    } else {
      const none = document.createElement("small"); none.textContent = "No exact overlap"; labels.append(none);
    }
    shared.append(sharedTitle, labels);
    const chevron = icon(["m9 15 11 10 11-10"]);
    chevron.classList.add("chevron");
    summary.append(rank, identity, shared, chevron);

    const details = document.createElement("div");
    details.className = "ranking-details";
    details.hidden = index !== 0;
    for (const [title, text] of [["Mentor matching text", recommendation.mentor_matching_text], ["Mentee matching text", recommendation.mentee_matching_text]]) {
      const panel = document.createElement("section");
      panel.className = "text-panel";
      const heading = document.createElement("h3");
      heading.textContent = title;
      const pre = document.createElement("pre");
      pre.className = "matching-text";
      pre.textContent = text;
      panel.append(heading, pre);
      details.append(panel);
    }
    const exact = document.createElement("section");
    exact.className = "exact-fields";
    renderExactFields(exact, recommendation.shared_fields);
    details.append(exact);
    const components = document.createElement("section");
    components.className = "exact-fields score-components";
    const componentHeading = document.createElement("h3");
    componentHeading.textContent = "Score components";
    components.append(componentHeading);
    const componentList = document.createElement("dl");
    for (const [key, value] of Object.entries(recommendation.components || {})) {
      const term = document.createElement("dt"); term.textContent = key.replaceAll("_", " ");
      const definition = document.createElement("dd"); definition.textContent = `${(Number(value) * 100).toFixed(0)}%`;
      componentList.append(term, definition);
    }
    components.append(componentList);
    details.append(components);
    summary.addEventListener("click", () => {
      const open = !row.classList.contains("is-open");
      row.classList.toggle("is-open", open);
      details.hidden = !open;
      summary.setAttribute("aria-expanded", String(open));
    });
    row.append(summary, details);
    rankingList.append(row);
  });
}

function renderMenteeList(filter = "") {
  menteeList.replaceChildren();
  const normalized = filter.trim().toLowerCase();
  const visible = state.report.results.filter((item) => item.mentee_id.toLowerCase().includes(normalized));
  for (const result of visible) {
    const button = document.createElement("button");
    button.className = `mentee-button${result.mentee_id === state.activeMenteeId ? " is-active" : ""}`;
    button.type = "button";
    const id = document.createElement("strong");
    id.textContent = result.mentee_id;
    const profile = document.createElement("span");
    profile.textContent = `${result.recommendations.length} recommendation${result.recommendations.length === 1 ? "" : "s"}`;
    button.append(id, profile);
    button.addEventListener("click", () => { state.activeMenteeId = result.mentee_id; renderMenteeList(menteeSearch.value); renderRanking(); });
    menteeList.append(button);
  }
  menteeCount.textContent = `${visible.length} of ${state.report.results.length} mentees`;
}

function renderMetrics() {
  metricsContent.replaceChildren();
  const metrics = state.report.metrics;
  if (!metrics) {
    const message = document.createElement("p");
    message.className = "section-note";
    message.textContent = "No expected matches were supplied, so Precision@3 and Recall@3 were not calculated.";
    metricsContent.append(message);
    return;
  }
  const summary = document.createElement("p");
  summary.className = "section-note";
  summary.textContent = `${metrics.queries_evaluated} labelled queries · Macro Precision@3 ${Number(metrics.macro_precision_at_3).toFixed(4)} · Macro Recall@3 ${Number(metrics.macro_recall_at_3).toFixed(4)}`;
  const table = document.createElement("table");
  table.className = "metrics-table";
  const head = document.createElement("thead");
  const headerRow = document.createElement("tr");
  for (const label of ["Mentee", "Expected mentors", "Hits@3", "Precision@3", "Recall@3"]) { const th = document.createElement("th"); th.textContent = label; headerRow.append(th); }
  head.append(headerRow);
  const body = document.createElement("tbody");
  for (const item of metrics.per_mentee) {
    const row = document.createElement("tr");
    for (const value of [item.mentee_id, item.expected_mentor_ids.join(", "), item.hits_at_3, Number(item.precision_at_3).toFixed(4), Number(item.recall_at_3).toFixed(4)]) { const cell = document.createElement("td"); cell.textContent = String(value); row.append(cell); }
    body.append(row);
  }
  table.append(head, body);
  metricsContent.append(summary, table);
}

function renderReport() {
  emptyState.hidden = true;
  results.hidden = false;
  state.activeMenteeId = state.report.results[0]?.mentee_id || null;
  renderSummaryMetrics();
  renderMenteeList();
  renderRanking();
  renderMetrics();
  rawJson.textContent = JSON.stringify(state.report, null, 2);
  downloadJson.href = state.report.download_url;
}

form.addEventListener("submit", async (event) => {
  event.preventDefault();
  if (!state.mentors.length || !state.mentees.length) { setStatus("Load mentor and mentee profile arrays first.", true); return; }
  setLoading(true);
  setStatus(`Embedding ${state.mentors.length} mentors and matching ${state.mentees.length} mentees…`);
  try {
    const provider = form.querySelector('input[name="provider"]:checked').value;
    const model = provider === "deterministic" ? null : embeddingModel.value;
    const response = await fetch("/api/match", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ provider, model, mentors: state.mentors, mentees: menteesWithPreferences(), expected: state.expected }) });
    const data = await response.json();
    if (!response.ok) throw new Error(data.detail || "Matching failed.");
    state.report = data;
    renderReport();
    const skipped = data.skipped_mentees?.length || 0;
    setStatus(`Completed ${data.results.length} mentees against ${data.mentor_count} mentors${skipped ? `; skipped ${skipped} unusable mentee profile${skipped === 1 ? "" : "s"}.` : "."}`);
  } catch (error) {
    setStatus(error.message, true);
  } finally {
    setLoading(false);
  }
});

document.querySelectorAll(".tab").forEach((tab) => {
  tab.addEventListener("click", () => {
    document.querySelectorAll(".tab").forEach((item) => { item.classList.toggle("is-active", item === tab); item.setAttribute("aria-selected", String(item === tab)); });
    for (const name of ["recommendations", "metrics", "json"]) document.querySelector(`#${name}-panel`).hidden = name !== tab.dataset.tab;
  });
});

menteeSearch.addEventListener("input", () => renderMenteeList(menteeSearch.value));
sampleButton.addEventListener("click", loadSamples);
parsedButton.addEventListener("click", loadParsedProfiles);
form.querySelectorAll('input[name="provider"]').forEach((input) => input.addEventListener("change", () => loadModelsForProvider(input.value)));
preferenceMentee.addEventListener("change", showPreferences);
preferenceFields.forEach((id) => document.querySelector(`#${id}`).addEventListener("input", saveVisiblePreferences));
wireFileInput("#mentor-input", "mentors");
wireFileInput("#mentee-input", "mentees");
wireFileInput("#expected-input", "expected");
initialize();
