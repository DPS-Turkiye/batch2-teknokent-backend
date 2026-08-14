const state = {
  mentors: [],
  enrichment: {},
  taxonomy: [],
  taxonomyAliases: {},
  taxonomyTouched: false,
  weights: {},
  sampleProblem: null,
  problemId: "mentee-custom-problem",
  report: null,
  activeCandidateId: null,
  decisions: new Map(),
  step: "problem",
  models: [],
  reviewSaved: false,
  feedbackMetrics: null,
};

const $ = (selector) => document.querySelector(selector);
const $$ = (selector) => [...document.querySelectorAll(selector)];

function splitValues(value) {
  return String(value || "").split(",").map((item) => item.trim()).filter(Boolean);
}

function emptyProblem() {
  return {
    mentee_id: "mentee-custom-problem",
    problem_statement: "",
    desired_outcomes: [],
    problem_domains: [],
    sectors: [],
    startup_stage: "",
    technologies: [],
    languages: [],
    meeting_formats: [],
    organization: "",
    minimum_mentor_years: 0,
    urgency: "normal",
  };
}

function readyMentorCount() {
  return state.mentors.filter((record, index) => {
    const settings = state.enrichment[mentorId(record, index)] || {};
    return settings.active !== false && Number(settings.capacity ?? 1) > 0;
  }).length;
}

function clearPoolResult() {
  state.report = null;
  state.decisions = new Map();
  state.activeCandidateId = null;
  state.reviewSaved = false;
  $("#pool-empty").hidden = false;
  $("#pool-empty-title").textContent = "Ready to generate a suggested pool";
  $("#pool-empty-message").textContent = "Enter a problem, review the interpretation, and generate an auditable ranking.";
  $("#candidate-table").hidden = true;
  $("#candidate-inspector").hidden = true;
  $("#save-review-button").disabled = true;
  $("#export-run").href = "#";
  $("#export-run").setAttribute("aria-disabled", "true");
  for (const id of ["eligible-count", "excluded-count", "suggested-count", "confidence-level"]) $("#" + id).textContent = "—";
  $("#exclusion-panel").hidden = true;
}

function updateWorkflowProgress() {
  const problemValid = $("#problem-statement").value.trim().length >= 12;
  const selectedDomains = $$("#taxonomy-grid input:checked").length;
  const complete = {
    readiness: readyMentorCount() > 0,
    problem: problemValid,
    interpretation: problemValid && selectedDomains > 0,
    pool: Boolean(state.report),
    review: state.reviewSaved,
  };
  for (const button of $$("[data-step]")) {
    const done = complete[button.dataset.step];
    button.classList.toggle("is-complete", done);
    button.querySelector("i").textContent = done ? "✓" : "";
    if (button.dataset.step === state.step) button.setAttribute("aria-current", "step");
    else button.removeAttribute("aria-current");
  }
}

function invalidatePool(message = "Inputs changed. Review the interpretation and generate a new pool.") {
  clearPoolResult();
  $("#ready-count").textContent = String(readyMentorCount());
  renderProblemBand();
  setStatus(message);
  updateWorkflowProgress();
}

function mentorId(record, index = 0) {
  return String(record.mentor_id || record.profile_id || record.id || `mentor-${index}`);
}

function mentorProfile(record) {
  return record.profile && typeof record.profile === "object" ? record.profile : record;
}

function formatScore(value) {
  return Number(value || 0).toFixed(2);
}

function setStatus(message, error = false, running = false) {
  const node = $("#run-status");
  node.replaceChildren();
  const dot = document.createElement("span");
  dot.className = "status-dot";
  node.append(dot, document.createTextNode(message));
  node.classList.toggle("is-error", error);
  $("#run-state").replaceChildren(dot.cloneNode(), document.createTextNode(running ? "Running" : error ? "Needs attention" : state.report ? "Complete" : "Ready"));
}

function setStep(step) {
  state.step = step;
  for (const panel of $$("[data-panel]")) panel.hidden = panel.dataset.panel !== step;
  for (const button of $$("[data-step]")) button.classList.toggle("is-active", button.dataset.step === step);
  if (step === "interpretation") renderInterpretation();
  if (step === "pool") renderProblemBand();
  if (step === "review") renderReviewSummary();
  updateWorkflowProgress();
}

function fillModels(selected) {
  const select = $("#model");
  const provider = $("#provider").value;
  select.replaceChildren();
  if (provider === "deterministic") {
    select.append(new Option("deterministic-hash-v1", "deterministic-hash-v1"));
    select.disabled = true;
    return;
  }
  if (provider === "ollama") {
    select.append(new Option("bge-m3", "bge-m3"));
    select.disabled = false;
    return;
  }
  select.disabled = false;
  for (const model of state.models) {
    const option = new Option(model.name === model.id ? model.id : `${model.name} — ${model.id}`, model.id);
    option.selected = model.id === selected;
    select.append(option);
  }
  if (!select.options.length) select.append(new Option(selected || "Provider default", selected || ""));
}

function renderReadiness() {
  const container = $("#mentor-readiness-table");
  container.replaceChildren();
  state.mentors.forEach((record, index) => {
    const id = mentorId(record, index);
    const profile = mentorProfile(record);
    const settings = state.enrichment[id] || (state.enrichment[id] = { active: true, capacity: 1 });
    const row = document.createElement("div");
    row.className = "readiness-row";

    const identity = document.createElement("div");
    const name = document.createElement("strong");
    name.textContent = profile.full_name || id;
    const identifier = document.createElement("small");
    identifier.textContent = id;
    identity.append(name, identifier);

    const activeLabel = document.createElement("label");
    activeLabel.className = "switch-label";
    const active = document.createElement("input");
    active.type = "checkbox";
    active.checked = settings.active !== false;
    active.addEventListener("change", () => {
      settings.active = active.checked;
      invalidatePool("Mentor readiness changed. Generate a new pool when ready.");
    });
    activeLabel.append(active, document.createTextNode("Active"));

    const capacityLabel = document.createElement("label");
    capacityLabel.textContent = "Available capacity";
    const capacity = document.createElement("input");
    capacity.type = "number";
    capacity.min = "0";
    capacity.max = "20";
    capacity.value = String(settings.capacity ?? 1);
    capacity.addEventListener("input", () => {
      settings.capacity = Number(capacity.value);
      invalidatePool("Mentor capacity changed. Generate a new pool when ready.");
    });
    capacityLabel.append(capacity);

    const preferencesLabel = document.createElement("label");
    preferencesLabel.textContent = "Preferred problem domains";
    const preferences = document.createElement("input");
    preferences.value = (settings.preferred_problem_domains || []).join(", ");
    preferences.placeholder = "Enterprise sales, Pricing";
    preferences.addEventListener("input", () => {
      settings.preferred_problem_domains = splitValues(preferences.value);
      invalidatePool("Mentor preferences changed. Generate a new pool when ready.");
    });
    preferencesLabel.append(preferences);

    const exposureLabel = document.createElement("label");
    exposureLabel.textContent = "Recent suggestions";
    const exposure = document.createElement("input");
    exposure.type = "number";
    exposure.min = "0";
    exposure.max = "100";
    exposure.value = String(settings.recent_recommendation_count ?? 0);
    exposure.addEventListener("input", () => {
      settings.recent_recommendation_count = Number(exposure.value);
      invalidatePool("Mentor exposure changed. Generate a new diversity-aware pool.");
    });
    exposureLabel.append(exposure);

    row.append(identity, activeLabel, capacityLabel, preferencesLabel, exposureLabel);
    container.append(row);
  });
  $("#ready-count").textContent = String(readyMentorCount());
}

function writeProblem(problem) {
  state.problemId = problem.mentee_id || "mentee-custom-problem";
  $("#problem-statement").value = problem.problem_statement || "";
  $("#desired-outcome").value = (problem.desired_outcomes || []).join(", ");
  $("#sector").value = (problem.sectors || []).join(", ");
  $("#startup-stage").value = problem.startup_stage || "";
  $("#technologies").value = (problem.technologies || []).join(", ");
  $("#languages").value = (problem.languages || []).join(", ");
  $("#meeting-formats").value = (problem.meeting_formats || [])[0] || "";
  $("#organization").value = problem.organization || "";
  $("#minimum-mentor-years").value = String(problem.minimum_mentor_years || 0);
  $("#urgency").value = problem.urgency || "normal";
  $("#problem-error").hidden = true;
}

function readProblem() {
  const selectedDomains = $$("#taxonomy-grid input:checked").map((input) => input.value);
  return {
    mentee_id: state.problemId,
    problem_statement: $("#problem-statement").value.trim(),
    desired_outcomes: splitValues($("#desired-outcome").value),
    problem_domains: selectedDomains,
    sectors: splitValues($("#sector").value),
    startup_stage: $("#startup-stage").value,
    technologies: splitValues($("#technologies").value),
    languages: splitValues($("#languages").value),
    meeting_formats: splitValues($("#meeting-formats").value),
    organization: $("#organization").value.trim(),
    minimum_mentor_years: Number($("#minimum-mentor-years").value || 0),
    urgency: $("#urgency").value,
  };
}

function renderTaxonomy(selectedDomains = []) {
  const grid = $("#taxonomy-grid");
  const selected = new Set(selectedDomains);
  grid.replaceChildren();
  for (const term of state.taxonomy) {
    const label = document.createElement("label");
    label.className = "taxonomy-option";
    const input = document.createElement("input");
    input.type = "checkbox";
    input.value = term;
    input.checked = selected.has(term);
    input.addEventListener("change", () => {
      state.taxonomyTouched = true;
      $("#taxonomy-hint").textContent = "Manually adjusted. These domains will be used for scoring.";
      invalidatePool();
    });
    label.append(input, document.createTextNode(term));
    grid.append(label);
  }
}

function normalizedText(value) {
  return String(value || "").toLowerCase().replaceAll("-", " ").replace(/\s+/g, " ").trim();
}

function inferProblemDomains() {
  const text = normalizedText([
    $("#problem-statement").value,
    $("#desired-outcome").value,
    $("#sector").value,
    $("#technologies").value,
  ].join(" "));
  if (!text) return [];
  return state.taxonomy.filter((domain) => {
    const candidates = [domain, ...(state.taxonomyAliases[domain] || [])];
    return candidates.some((candidate) => text.includes(normalizedText(candidate)));
  });
}

function applyInferredTaxonomy() {
  if (state.taxonomyTouched) return;
  const inferred = new Set(inferProblemDomains());
  for (const input of $$("#taxonomy-grid input")) input.checked = inferred.has(input.value);
  $("#taxonomy-hint").textContent = inferred.size
    ? `Detected from the problem: ${[...inferred].join(", ")}. You can adjust this selection.`
    : "No controlled domain was detected automatically. Select relevant domains or continue with lexical and semantic retrieval.";
}

function validateProblem({ focus = true } = {}) {
  const statement = $("#problem-statement").value.trim();
  const error = $("#problem-error");
  if (statement.length >= 12) {
    error.hidden = true;
    return true;
  }
  error.textContent = "Enter a problem statement with at least 12 characters before continuing.";
  error.hidden = false;
  setStatus("A meaningful problem statement is required.", true);
  setStep("problem");
  if (focus) $("#problem-statement").focus();
  return false;
}

function renderInterpretation() {
  applyInferredTaxonomy();
  const problem = readProblem();
  $("#interpretation-problem").textContent = problem.problem_statement || "No problem statement entered.";
  const facts = $("#interpretation-facts");
  facts.replaceChildren();
  const rows = [
    ["Desired outcome", problem.desired_outcomes.join(", ") || "Not provided"],
    ["Sector", problem.sectors.join(", ") || "Not provided"],
    ["Startup stage", problem.startup_stage || "Not provided"],
    ["Technology", problem.technologies.join(", ") || "Not required"],
    ["Constraints", [...problem.languages, ...problem.meeting_formats].join(", ") || "None"],
    ["Minimum experience", problem.minimum_mentor_years ? `${problem.minimum_mentor_years} years` : "Not required"],
    ["Urgency", problem.urgency],
  ];
  for (const [term, value] of rows) {
    const row = document.createElement("div");
    const dt = document.createElement("dt");
    const dd = document.createElement("dd");
    dt.textContent = term;
    dd.textContent = value;
    row.append(dt, dd);
    facts.append(row);
  }
}

function renderProblemBand() {
  const problem = readProblem();
  $("#problem-band-text").textContent = problem.problem_statement || "Complete problem intake to generate a pool.";
  const tags = $("#problem-tags");
  tags.replaceChildren();
  for (const value of [...problem.problem_domains, ...problem.sectors.slice(0, 1), problem.startup_stage].filter(Boolean).slice(0, 5)) {
    const tag = document.createElement("span");
    tag.textContent = value;
    tags.append(tag);
  }
}

function scoreRow(label, score) {
  const row = document.createElement("div");
  row.className = "score-line";
  const name = document.createElement("span");
  name.textContent = label;
  const bar = document.createElement("i");
  bar.style.setProperty("--score", Math.max(0, Math.min(1, Number(score || 0))));
  const value = document.createElement("em");
  value.textContent = formatScore(score);
  row.append(name, bar, value);
  return row;
}

function decisionFor(candidate, index) {
  if (!state.decisions.has(candidate.mentor_id)) {
    state.decisions.set(candidate.mentor_id, {
      mentor_id: candidate.mentor_id,
      approved: false,
      label: "unlabelled",
      reason: "",
      notes: "",
      display_position: index + 1,
    });
  }
  return state.decisions.get(candidate.mentor_id);
}

function renderCandidates() {
  const rows = $("#candidate-rows");
  rows.replaceChildren();
  if (!state.report) return;
  state.report.suggested_pool.forEach((candidate, index) => {
    const decision = decisionFor(candidate, index);
    const row = document.createElement("article");
    row.className = `candidate-row${candidate.mentor_id === state.activeCandidateId ? " is-selected" : ""}`;
    row.dataset.mentorId = candidate.mentor_id;

    const rank = document.createElement("div");
    rank.className = "candidate-cell candidate-rank";
    rank.textContent = String(index + 1);

    const identity = document.createElement("div");
    identity.className = "candidate-cell candidate-identity";
    const identityButton = document.createElement("button");
    identityButton.type = "button";
    identityButton.textContent = candidate.mentor_name;
    identityButton.addEventListener("click", () => selectCandidate(candidate.mentor_id));
    const id = document.createElement("small");
    id.textContent = candidate.mentor_id;
    identity.append(identityButton, id);

    const final = document.createElement("div");
    final.className = "candidate-cell candidate-final";
    final.textContent = formatScore(candidate.final_score);

    const scores = document.createElement("div");
    scores.className = "candidate-cell";
    const stack = document.createElement("div");
    stack.className = "score-stack";
    stack.append(
      scoreRow("Problem expertise", candidate.components.problem_expertise),
      scoreRow("Evidence", candidate.components.supporting_evidence),
      scoreRow("Mentor depth", candidate.components.mentor_depth),
      scoreRow("Stage & sector", (candidate.components.startup_stage + candidate.components.sector) / 2),
    );
    scores.append(stack);

    const reason = document.createElement("div");
    reason.className = "candidate-cell candidate-reason";
    const baseReason = candidate.reasons.join(" ") || "Semantic retrieval candidate; coordinator evidence review required.";
    const llmRationale = candidate.reranker_assessment?.rationale;
    reason.textContent = llmRationale ? `${baseReason} Experimental LLM assessment: ${llmRationale}` : baseReason;

    const eligibility = document.createElement("div");
    eligibility.className = "candidate-cell eligibility";
    eligibility.textContent = `Eligible · ${candidate.confidence_level} confidence`;

    const actions = document.createElement("div");
    actions.className = "candidate-cell candidate-actions";
    const approve = document.createElement("button");
    approve.type = "button";
    approve.dataset.action = "approve";
    approve.textContent = decision.approved ? "Approved" : "Approve";
    approve.classList.toggle("is-approved", decision.approved);
    approve.addEventListener("click", () => {
      decision.approved = !decision.approved;
      if (decision.approved && decision.label === "unlabelled") decision.label = "possible";
      renderCandidates();
      if (candidate.mentor_id === state.activeCandidateId) renderInspector();
    });
    const remove = document.createElement("button");
    remove.type = "button";
    remove.dataset.action = "remove";
    remove.textContent = "Remove";
    remove.addEventListener("click", () => {
      decision.approved = false;
      decision.label = "inappropriate";
      decision.reason ||= "Removed by coordinator";
      renderCandidates();
      if (candidate.mentor_id === state.activeCandidateId) renderInspector();
    });
    const details = document.createElement("button");
    details.type = "button";
    details.textContent = "Details";
    details.addEventListener("click", () => selectCandidate(candidate.mentor_id));
    const move = document.createElement("button");
    move.type = "button";
    move.textContent = index ? "Move up" : "Move down";
    move.addEventListener("click", () => moveCandidate(index, index ? index - 1 : index + 1));
    actions.append(approve, remove, details, move);
    row.append(rank, identity, final, scores, reason, eligibility, actions);
    rows.append(row);
  });
}

function moveCandidate(from, to) {
  if (!state.report || to < 0 || to >= state.report.suggested_pool.length) return;
  const [candidate] = state.report.suggested_pool.splice(from, 1);
  state.report.suggested_pool.splice(to, 0, candidate);
  state.report.suggested_pool.forEach((item, index) => { decisionFor(item, index).display_position = index + 1; });
  renderCandidates();
}

function inspectorRows(container, values) {
  container.replaceChildren();
  for (const [label, score] of values) {
    const row = document.createElement("div");
    row.className = "inspector-score-row";
    const name = document.createElement("span");
    name.textContent = label;
    const value = document.createElement("strong");
    value.textContent = formatScore(score);
    const bar = document.createElement("i");
    bar.style.setProperty("--score", Math.max(0, Math.min(1, Number(score || 0))));
    row.append(name, value, bar);
    container.append(row);
  }
}

function renderList(container, values, emptyText) {
  container.replaceChildren();
  const items = values?.length ? values : [emptyText];
  for (const value of items) {
    const item = document.createElement("li");
    item.textContent = value;
    container.append(item);
  }
}

function selectCandidate(mentorIdValue) {
  state.activeCandidateId = mentorIdValue;
  renderCandidates();
  renderInspector();
}

function renderInspector() {
  const candidate = state.report?.suggested_pool.find((item) => item.mentor_id === state.activeCandidateId);
  const inspector = $("#candidate-inspector");
  inspector.hidden = !candidate;
  if (!candidate) return;
  const decision = decisionFor(candidate, state.report.suggested_pool.indexOf(candidate));
  $("#inspector-name").textContent = candidate.mentor_name;
  $("#inspector-id").textContent = candidate.mentor_id;
  $("#inspector-score").textContent = formatScore(candidate.final_score);
  inspectorRows($("#retrieval-scores"), [
    ["Taxonomy", candidate.retrieval.taxonomy],
    ["Lexical (BM25)", candidate.retrieval.lexical],
    [`Semantic (${state.report.provider.model})`, candidate.retrieval.semantic],
    [`Reranker (${state.report.reranker.model || "off"})`, candidate.retrieval.reranker],
  ]);
  inspectorRows($("#component-scores"), Object.entries(candidate.components).map(([key, value]) => [key.replaceAll("_", " "), value]));
  inspectorRows($("#evidence-quality"), Object.entries(candidate.evidence_assessment || {})
    .filter(([key, value]) => typeof value === "number" && key !== "relevant_claim_count")
    .map(([key, value]) => [key.replaceAll("_", " "), value]));
  renderList($("#evidence-list"), candidate.evidence, "No source evidence was parsed for this mentor.");
  renderList($("#uncertainty-list"), candidate.uncertainties, "No parser-declared uncertainty.");
  $("#inspector-confidence").textContent = `${candidate.confidence_level} · ${formatScore(candidate.confidence)}`;
  const llmAssessment = candidate.reranker_assessment;
  const confidenceNotes = [...(candidate.confidence_caveats || [])];
  if (llmAssessment?.concerns?.length) confidenceNotes.push(`LLM concerns: ${llmAssessment.concerns.join("; ")}`);
  $("#inspector-confidence-note").textContent = confidenceNotes.join(" · ") || "Retrieval signals and profile evidence are consistent.";
  const radio = $(`input[name="candidate-label"][value="${decision.label}"]`);
  for (const input of $$("input[name='candidate-label']")) input.checked = false;
  if (radio) radio.checked = true;
  $("#candidate-notes").value = decision.notes;
}

function renderReport() {
  const report = state.report;
  $("#ready-count").textContent = String(readyMentorCount());
  $("#eligible-count").textContent = String(report.eligible_count);
  $("#excluded-count").textContent = String(report.excluded_count);
  $("#suggested-count").textContent = String(report.suggested_count);
  $("#confidence-level").textContent = report.run_confidence_level;
  $("#export-run").href = report.download_url;
  $("#export-run").setAttribute("aria-disabled", "false");
  const hasSuggestions = report.suggested_pool.length > 0;
  $("#pool-empty").hidden = hasSuggestions;
  $("#candidate-table").hidden = !hasSuggestions;
  $("#save-review-button").disabled = !hasSuggestions;
  renderExclusions();
  if (!hasSuggestions) {
    const reasonCounts = new Map();
    for (const exclusion of report.exclusions || []) {
      for (const reason of exclusion.reasons || []) reasonCounts.set(reason, (reasonCounts.get(reason) || 0) + 1);
    }
    const reasonLabels = {
      INACTIVE: "inactive mentors",
      AT_CAPACITY: "mentors at capacity",
      CONFLICT_OF_INTEREST: "conflicts of interest",
      LANGUAGE_MISMATCH: "language mismatches",
      LANGUAGE_UNCONFIRMED: "unconfirmed languages",
      MEETING_FORMAT_MISMATCH: "meeting-format mismatches",
      MEETING_FORMAT_UNCONFIRMED: "unconfirmed meeting formats",
      EXCLUDED_PROBLEM_DOMAIN: "excluded problem domains",
      INSUFFICIENT_EXPERIENCE: "mentors below the experience requirement",
      MAXIMUM_LOAD_REACHED: "mentors at maximum load",
    };
    const summary = [...reasonCounts.entries()]
      .map(([reason, count]) => `${count} ${reasonLabels[reason] || reason.toLowerCase().replaceAll("_", " ")}`)
      .join(", ");
    $("#pool-empty-title").textContent = "No eligible mentors found";
    $("#pool-empty-message").textContent = summary
      ? `Eligibility removed every mentor: ${summary}. Adjust readiness or problem constraints and generate again.`
      : "No mentor could be ranked for this problem. Add more profile evidence or broaden the problem description.";
    $("#candidate-inspector").hidden = true;
  }
  state.activeCandidateId = report.suggested_pool[0]?.mentor_id || null;
  renderCandidates();
  renderInspector();
  updateWorkflowProgress();
}

function renderExclusions() {
  const panel = $("#exclusion-panel");
  const rows = state.report?.exclusions || [];
  panel.hidden = !rows.length;
  $("#exclusion-summary").textContent = `${rows.length} mentor${rows.length === 1 ? "" : "s"} excluded`;
  const container = $("#exclusion-list");
  container.replaceChildren();
  for (const exclusion of rows) {
    const row = document.createElement("div");
    const id = document.createElement("strong"); id.textContent = exclusion.mentor_id;
    const reasons = document.createElement("span");
    reasons.textContent = exclusion.reasons.map((reason) => reason.toLowerCase().replaceAll("_", " ")).join(", ");
    row.append(id, reasons);
    container.append(row);
  }
}

async function generatePool() {
  if (!validateProblem()) return;
  applyInferredTaxonomy();
  setStep("pool");
  const button = $("#generate-button");
  button.disabled = true;
  button.textContent = "Generating…";
  setStatus("Applying eligibility, evidence scoring, reranking, and diversity selection…", false, true);
  try {
    const response = await fetch("/api/pool", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        mentors: state.mentors,
        enrichment: state.enrichment,
        problem: readProblem(),
        provider: $("#provider").value,
        model: $("#model").value,
        pool_size: Number($("#pool-size").value),
        weights: state.weights,
        reranker: $("#reranker").value,
      }),
    });
    const data = await response.json();
    if (!response.ok) throw new Error(data.detail || "Could not generate the mentor pool.");
    state.report = data;
    state.decisions = new Map();
    renderReport();
    state.feedbackMetrics = data.feedback_metrics;
    setStatus(data.suggested_count
      ? `Run complete: ${data.suggested_count} diverse suggestions from ${data.eligible_count} eligible mentors · ${data.run_confidence_level} confidence.`
      : `Run complete: no eligible mentors for the current constraints.`,
      data.suggested_count === 0);
  } catch (error) {
    $("#pool-empty").hidden = false;
    $("#pool-empty-title").textContent = "Pool generation failed";
    $("#pool-empty-message").textContent = error.message;
    $("#candidate-table").hidden = true;
    $("#candidate-inspector").hidden = true;
    setStatus(error.message, true);
  } finally {
    button.disabled = false;
    button.textContent = "Generate pool";
  }
}

async function saveReview() {
  if (!state.report) return;
  syncInspectorDecision();
  const button = $("#save-review-button");
  button.disabled = true;
  button.textContent = "Saving…";
  try {
    const response = await fetch(state.report.review_url, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ coordinator: "TeamTNT", decisions: [...state.decisions.values()] }),
    });
    const data = await response.json();
    if (!response.ok) throw new Error(data.detail || "Could not save the coordinator review.");
    state.reviewSaved = true;
    state.feedbackMetrics = data.feedback_metrics;
    renderFeedbackMetrics();
    setStatus(`Review saved with ${data.decisions.length} coordinator labels.`);
    setStep("review");
  } catch (error) {
    setStatus(error.message, true);
  } finally {
    button.disabled = false;
    button.textContent = "Save review";
  }
}

function syncInspectorDecision() {
  const candidate = state.report?.suggested_pool.find((item) => item.mentor_id === state.activeCandidateId);
  if (!candidate) return;
  const decision = decisionFor(candidate, state.report.suggested_pool.indexOf(candidate));
  const selectedLabel = $("input[name='candidate-label']:checked");
  if (selectedLabel) {
    decision.label = selectedLabel.value;
    decision.approved = ["strong", "possible"].includes(selectedLabel.value);
  }
  decision.notes = $("#candidate-notes").value;
}

function renderReviewSummary() {
  const container = $("#review-summary");
  container.replaceChildren();
  if (!state.report) {
    const message = document.createElement("p");
    message.textContent = "Generate a mentor pool before completing the review.";
    container.append(message);
    return;
  }
  const heading = document.createElement("h2");
  heading.textContent = "Coordinator decisions";
  const table = document.createElement("table");
  const head = document.createElement("thead");
  const headRow = document.createElement("tr");
  for (const label of ["Position", "Mentor", "Label", "Approved", "Notes"]) {
    const cell = document.createElement("th"); cell.textContent = label; headRow.append(cell);
  }
  head.append(headRow);
  const body = document.createElement("tbody");
  for (const candidate of state.report.suggested_pool) {
    const decision = decisionFor(candidate, candidate.ranking_position - 1);
    const row = document.createElement("tr");
    for (const value of [decision.display_position, candidate.mentor_name, decision.label, decision.approved ? "Yes" : "No", decision.notes || decision.reason || "—"]) {
      const cell = document.createElement("td"); cell.textContent = String(value); row.append(cell);
    }
    body.append(row);
  }
  table.append(head, body);
  const actions = document.createElement("p");
  const link = document.createElement("a");
  link.className = "secondary-button";
  link.href = state.report.download_url;
  link.download = "";
  link.textContent = "Download complete run JSON";
  actions.append(link);
  container.append(heading, table, actions);
}

function renderFeedbackMetrics() {
  const container = $("#feedback-metrics");
  container.replaceChildren();
  const metrics = state.feedbackMetrics;
  if (!metrics || !metrics.labelled_decisions) {
    const empty = document.createElement("p");
    empty.textContent = "No labelled decisions yet. Saved reviews will build calibration data here.";
    container.append(empty);
    return;
  }
  const values = [
    ["Labelled decisions", metrics.labelled_decisions],
    ["Approval rate", `${(Number(metrics.approval_rate || 0) * 100).toFixed(0)}%`],
    ["Strong labels", metrics.labels.strong],
    ["Inappropriate labels", metrics.labels.inappropriate],
  ];
  for (const [label, value] of values) {
    const card = document.createElement("div");
    const strong = document.createElement("strong"); strong.textContent = String(value);
    const span = document.createElement("span"); span.textContent = label;
    card.append(strong, span);
    container.append(card);
  }
}

function resetProblem() {
  state.taxonomyTouched = false;
  writeProblem(state.sampleProblem);
  renderTaxonomy(state.sampleProblem.problem_domains || []);
  clearPoolResult();
  renderProblemBand();
  setStep("problem");
  setStatus("Example problem loaded. Review or edit it before continuing.");
}

function clearProblem() {
  state.taxonomyTouched = false;
  writeProblem(emptyProblem());
  renderTaxonomy();
  clearPoolResult();
  renderProblemBand();
  setStep("problem");
  setStatus("Problem form cleared. Enter the mentee's original problem.");
  $("#problem-statement").focus();
}

async function initialize() {
  setStatus("Loading shared mentor profiles…", false, true);
  try {
    const response = await fetch("/api/bootstrap");
    const data = await response.json();
    if (!response.ok) throw new Error(data.detail || "Could not load the Mentor Pool Lab.");
    state.mentors = data.mentors;
    state.enrichment = data.enrichment;
    state.taxonomy = data.taxonomy;
    state.taxonomyAliases = data.taxonomy_aliases || {};
    state.weights = data.weights;
    state.sampleProblem = data.sample_problem;
    state.models = data.models || [];
    state.feedbackMetrics = data.feedback_metrics;
    $("#profile-source").textContent = data.mentor_source;
    $("#readiness-source").textContent = data.mentor_source === "parsed-profile-library" ? "PDF Parse Lab profile library" : "Sample mentor library";
    $("#mentor-total").textContent = String(data.mentor_count);
    $("#provider").value = "deterministic";
    $("#run-provider").textContent = "Offline test";
    fillModels(data.embedding_model);
    writeProblem(emptyProblem());
    renderTaxonomy();
    renderReadiness();
    renderProblemBand();
    renderFeedbackMetrics();
    setStep("problem");
    setStatus(`${data.mentor_count} mentors loaded. Enter the mentee's original problem.`);
  } catch (error) {
    setStatus(error.message, true);
  }
}

function navigateToStep(step) {
  if (["interpretation", "pool"].includes(step) && !validateProblem()) return;
  if (step === "interpretation") applyInferredTaxonomy();
  setStep(step);
}

for (const button of $$("[data-step]")) button.addEventListener("click", () => navigateToStep(button.dataset.step));
for (const button of $$("[data-next]")) button.addEventListener("click", () => navigateToStep(button.dataset.next));
$("#review-interpretation-button").addEventListener("click", () => navigateToStep("interpretation"));
$("#use-interpretation-button").addEventListener("click", generatePool);
$("#provider").addEventListener("change", () => {
  $("#run-provider").textContent = $("#provider").selectedOptions[0].textContent;
  fillModels();
  invalidatePool("Embedding provider changed. Generate a new pool when ready.");
});
$("#reranker").addEventListener("change", () => invalidatePool("Reranker changed. Generate a new pool when ready."));
$("#generate-button").addEventListener("click", generatePool);
$("#save-review-button").addEventListener("click", saveReview);
$("#reset-button").addEventListener("click", resetProblem);
$("#clear-problem-button").addEventListener("click", clearProblem);
$("#problem-form").addEventListener("input", () => {
  $("#problem-error").hidden = true;
  invalidatePool();
});
for (const input of $$("input[name='candidate-label']")) {
  input.addEventListener("change", () => {
    const candidate = state.report?.suggested_pool.find((item) => item.mentor_id === state.activeCandidateId);
    if (!candidate) return;
    const decision = decisionFor(candidate, state.report.suggested_pool.indexOf(candidate));
    decision.label = input.value;
    decision.approved = ["strong", "possible"].includes(input.value);
    renderCandidates();
  });
}
$("#candidate-notes").addEventListener("input", () => {
  const candidate = state.report?.suggested_pool.find((item) => item.mentor_id === state.activeCandidateId);
  if (!candidate) return;
  decisionFor(candidate, state.report.suggested_pool.indexOf(candidate)).notes = $("#candidate-notes").value;
});

initialize();
