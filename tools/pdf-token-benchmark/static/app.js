const MAX_FILES = 50;
const MAX_FILE_BYTES = 50 * 1024 * 1024;

const form = document.querySelector("#benchmark-form");
const fileInput = document.querySelector("#pdf-input");
const dropZone = document.querySelector("#drop-zone");
const fileQueue = document.querySelector("#file-queue");
const queueSummary = document.querySelector("#queue-summary");
const clearQueueButton = document.querySelector("#clear-queue");
const runButton = document.querySelector("#run-button");
const buttonLabel = runButton.querySelector(".button-label");
const runStatus = document.querySelector("#run-status");
const callCount = document.querySelector("#call-count");
const emptyState = document.querySelector("#empty-state");
const results = document.querySelector("#results");
const summaryCopy = document.querySelector("#technical-summary-copy");
const metricStrip = document.querySelector("#metric-strip");
const tokenChart = document.querySelector("#token-chart");
const resultRows = document.querySelector("#result-rows");
const cvDetailList = document.querySelector("#cv-detail-list");
const failureList = document.querySelector("#failure-list");
const accuracyContent = document.querySelector("#accuracy-content");
const resultActions = document.querySelector("#result-actions");
const downloadReport = document.querySelector("#download-report");
const downloadCsv = document.querySelector("#download-csv");
const viewJson = document.querySelector("#view-json");
const matchingTransfer = document.querySelector("#matching-transfer");
const matchingApproach = document.querySelector("#matching-approach");
const sendToMatching = document.querySelector("#send-to-matching");
const transferStatus = document.querySelector("#transfer-status");

let queue = [];
let isRunning = false;
let activeSettings = null;
let activeReportRows = [];
let activeReportSettings = null;

async function loadProviderConfig() {
  try {
    const response = await fetch("/api/config");
    const config = await response.json();
    if (!response.ok) throw new Error(config.detail || "Could not load provider settings.");
    const modelSelect = document.querySelector("#model");
    modelSelect.replaceChildren();
    for (const model of config.models) {
      const option = document.createElement("option");
      option.value = model.id;
      option.textContent = model.name === model.id ? model.id : `${model.name} — ${model.id}`;
      option.selected = model.id === config.default_model;
      modelSelect.append(option);
    }
    if (config.catalog_error) setStatus(config.catalog_error, true);
    if (!config.configured) {
      setStatus(`${config.key_environment_variable} is not configured in Backend/.env.`, true);
    }
  } catch (error) {
    setStatus(error.message, true);
  }
}

function formatBytes(bytes) {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

function formatNumber(value) {
  return Number(value || 0).toLocaleString("en-US");
}

function formatMoney(value, precision = 6) {
  return `$${Number(value || 0).toFixed(precision)}`;
}

function formatSigned(value, formatter = formatNumber) {
  const numeric = Number(value || 0);
  if (numeric === 0) return formatter(0);
  return `${numeric > 0 ? "+" : "−"}${formatter(Math.abs(numeric))}`;
}

function median(values) {
  if (!values.length) return 0;
  const sorted = [...values].sort((a, b) => a - b);
  const middle = Math.floor(sorted.length / 2);
  return sorted.length % 2 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2;
}

function settings() {
  return {
    profile_type: document.querySelector("#profile-type").value,
    model: document.querySelector("#model").value,
    pdf_detail: document.querySelector("#pdf-detail").value,
    order: document.querySelector("#order").value,
  };
}

function setStatus(message, isError = false) {
  runStatus.textContent = message;
  runStatus.classList.toggle("is-error", isError);
}

function fileKey(file) {
  return `${file.name}:${file.size}:${file.lastModified}`;
}

function addFiles(files) {
  const incoming = [...files];
  let rejected = 0;
  for (const file of incoming) {
    if (queue.length >= MAX_FILES) {
      rejected += 1;
      continue;
    }
    if (!file.name.toLowerCase().endsWith(".pdf") || file.size > MAX_FILE_BYTES) {
      rejected += 1;
      continue;
    }
    if (queue.some((item) => item.key === fileKey(file))) continue;
    queue.push({
      id: crypto.randomUUID(),
      key: fileKey(file),
      file,
      status: "queued",
      result: null,
      error: "",
    });
  }
  fileInput.value = "";
  renderQueue();
  resultActions.hidden = true;
  matchingTransfer.hidden = true;
  if (rejected) {
    setStatus(`${rejected} file${rejected === 1 ? " was" : "s were"} skipped. Use PDFs under 50 MB; the batch limit is ${MAX_FILES}.`, true);
  } else if (queue.length) {
    setStatus("Ready. Each CV will run through both approaches sequentially.");
  }
}

function statusLabel(item) {
  return {
    queued: "Queued",
    running: "Running",
    completed: "Completed",
    failed: "Failed",
  }[item.status];
}

function renderQueue() {
  fileQueue.replaceChildren();
  for (const item of queue) {
    const row = document.createElement("div");
    row.className = `queue-item is-${item.status}`;

    const icon = document.createElement("span");
    icon.className = "pdf-mini";
    icon.textContent = "PDF";

    const identity = document.createElement("div");
    identity.className = "queue-identity";
    const name = document.createElement("strong");
    name.textContent = item.file.name;
    const size = document.createElement("span");
    size.textContent = formatBytes(item.file.size);
    identity.append(name, size);

    const state = document.createElement("span");
    state.className = "queue-state";
    state.textContent = statusLabel(item);

    const action = document.createElement("button");
    action.className = "queue-action";
    action.type = "button";
    action.dataset.id = item.id;
    action.dataset.action = item.status === "failed" ? "retry" : "remove";
    action.disabled = isRunning || item.status === "running";
    action.textContent = item.status === "failed" ? "Retry" : "×";
    action.setAttribute("aria-label", item.status === "failed" ? `Retry ${item.file.name}` : `Remove ${item.file.name}`);

    row.append(icon, identity, state, action);
    fileQueue.append(row);
  }

  const completed = queue.filter((item) => item.status === "completed").length;
  queueSummary.textContent = queue.length
    ? `${queue.length} file${queue.length === 1 ? "" : "s"} in queue${completed ? ` · ${completed} completed` : ""}`
    : "No files in queue";
  const calls = queue.length * 2;
  callCount.textContent = `${calls} API call${calls === 1 ? "" : "s"}`;
  clearQueueButton.disabled = isRunning || !queue.length;
  runButton.disabled = isRunning || !queue.some((item) => item.status === "queued" || item.status === "failed");
  const pending = queue.filter((item) => item.status === "queued" || item.status === "failed").length;
  buttonLabel.textContent = isRunning
    ? "Running batch…"
    : pending
      ? `Run ${pending} benchmark${pending === 1 ? "" : "s"}`
      : queue.length
        ? "Batch complete"
        : "Run batch";
}

function setLoading(loading) {
  isRunning = loading;
  runButton.classList.toggle("is-running", loading);
  for (const control of form.querySelectorAll("select, input")) control.disabled = loading;
  renderQueue();
}

function readAsBase64(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.addEventListener("load", () => resolve(String(reader.result).split(",", 2)[1]));
    reader.addEventListener("error", () => reject(new Error(`Could not read ${file.name}.`)));
    reader.readAsDataURL(file);
  });
}

async function benchmarkItem(item) {
  item.status = "running";
  item.error = "";
  renderQueue();
  const payload = {
    filename: item.file.name,
    pdf_base64: await readAsBase64(item.file),
    ...activeSettings,
  };
  const response = await fetch("/api/benchmark", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
  });
  const data = await response.json();
  if (!response.ok) throw new Error(data.detail || "Benchmark failed.");
  const local = data.results?.local_text;
  const direct = data.results?.direct_pdf;
  if (!local || !direct || local.error || direct.error) {
    throw new Error([local?.error, direct?.error].filter(Boolean).join(" ") || "One parsing approach failed.");
  }
  item.result = data;
  item.status = "completed";
}

function compactRow(item) {
  if (item.status !== "completed" || !item.result) {
    return { pdf: item.file.name, status: "failed", error: item.error || "Benchmark did not complete." };
  }
  const data = item.result;
  const local = data.results.local_text;
  const direct = data.results.direct_pdf;
  return {
    source_job_id: data.job_id,
    pdf: item.file.name,
    status: "completed",
    error: "",
    pages: data.pdf_pages,
    bytes: data.pdf_bytes,
    extracted_characters: data.extracted_characters,
    local_total_tokens: local.usage.total_tokens,
    direct_total_tokens: direct.usage.total_tokens,
    local_input_tokens: local.usage.input_tokens,
    direct_input_tokens: direct.usage.input_tokens,
    local_output_tokens: local.usage.output_tokens,
    direct_output_tokens: direct.usage.output_tokens,
    local_cost_usd: local.estimated_cost_usd,
    direct_cost_usd: direct.estimated_cost_usd,
    local_latency_seconds: local.elapsed_seconds,
    direct_latency_seconds: direct.elapsed_seconds,
    local_warning_count: local.profile?.warnings?.length || 0,
    direct_warning_count: direct.profile?.warnings?.length || 0,
    extraction_warning_count: data.local_extraction_warnings?.length || 0,
  };
}

function derivedRows() {
  return queue
    .filter((item) => item.status === "completed" || item.status === "failed")
    .map(compactRow)
    .map((row) => {
      const tokenDifference = Number(row.local_total_tokens || 0) - Number(row.direct_total_tokens || 0);
      return {
        ...row,
        token_difference: tokenDifference,
        token_difference_percent: row.direct_total_tokens ? tokenDifference / row.direct_total_tokens * 100 : 0,
        cost_difference_usd: Number(row.local_cost_usd || 0) - Number(row.direct_cost_usd || 0),
        latency_difference_seconds: Number(row.local_latency_seconds || 0) - Number(row.direct_latency_seconds || 0),
      };
    });
}

function localSummary(rows) {
  const completed = rows.filter((row) => row.status === "completed");
  return {
    files_total: rows.length,
    completed: completed.length,
    failed: rows.length - completed.length,
    total_api_cost_usd: completed.reduce((sum, row) => sum + Number(row.local_cost_usd || 0) + Number(row.direct_cost_usd || 0), 0),
    median_token_difference: median(completed.map((row) => row.token_difference)),
    median_token_difference_percent: median(completed.map((row) => row.token_difference_percent)),
    median_latency_difference_seconds: median(completed.map((row) => row.latency_difference_seconds)),
  };
}

function addMetric(label, value, note) {
  const card = document.createElement("div");
  card.className = "summary-metric";
  const labelNode = document.createElement("span");
  labelNode.textContent = label;
  const valueNode = document.createElement("strong");
  valueNode.textContent = value;
  const noteNode = document.createElement("small");
  noteNode.textContent = note;
  card.append(labelNode, valueNode, noteNode);
  metricStrip.append(card);
}

function renderChart(completed) {
  tokenChart.replaceChildren();
  if (!completed.length) {
    const empty = document.createElement("p");
    empty.className = "section-note";
    empty.textContent = "No completed comparisons to chart yet.";
    tokenChart.append(empty);
    return;
  }
  const max = Math.max(...completed.flatMap((row) => [row.local_total_tokens, row.direct_total_tokens]), 1);
  for (const row of completed) {
    const group = document.createElement("div");
    group.className = "chart-row";
    const label = document.createElement("div");
    label.className = "chart-label";
    label.textContent = row.pdf;
    const bars = document.createElement("div");
    bars.className = "chart-bars";
    for (const [kind, value] of [["Local", row.local_total_tokens], ["Direct", row.direct_total_tokens]]) {
      const line = document.createElement("div");
      line.className = "bar-line";
      const kindLabel = document.createElement("span");
      kindLabel.textContent = kind;
      const track = document.createElement("div");
      track.className = "bar-track";
      const bar = document.createElement("i");
      bar.className = kind.toLowerCase();
      bar.style.width = `${value / max * 100}%`;
      track.append(bar);
      const amount = document.createElement("b");
      amount.textContent = formatNumber(value);
      line.append(kindLabel, track, amount);
      bars.append(line);
    }
    group.append(label, bars);
    tokenChart.append(group);
  }
}

function renderTable(rows) {
  resultRows.replaceChildren();
  for (const row of rows) {
    const tr = document.createElement("tr");
    const cells = row.status === "completed"
      ? [row.pdf, formatNumber(row.local_total_tokens), formatNumber(row.direct_total_tokens), `${formatSigned(row.token_difference)} (${formatSigned(row.token_difference_percent, (v) => `${Number(v).toFixed(1)}%`)})`, formatSigned(row.cost_difference_usd, (v) => formatMoney(v)), formatSigned(row.latency_difference_seconds, (v) => `${Number(v).toFixed(2)}s`), "Completed"]
      : [row.pdf, "—", "—", "—", "—", "—", "Failed"];
    cells.forEach((value, index) => {
      const td = document.createElement("td");
      td.textContent = value;
      if (index >= 3 && index <= 5 && Number(row[[("token_difference"), ("cost_difference_usd"), ("latency_difference_seconds")][index - 3]]) < 0) td.classList.add("is-saving");
      tr.append(td);
    });
    resultRows.append(tr);
  }
}

function renderDetails(rows) {
  cvDetailList.replaceChildren();
  for (const row of rows.filter((entry) => entry.status === "completed")) {
    const section = document.createElement("section");
    section.className = "cv-detail";
    const heading = document.createElement("h2");
    heading.textContent = row.pdf;
    const meta = document.createElement("p");
    meta.textContent = `${row.pages} pages · ${formatBytes(row.bytes)} · ${formatNumber(row.extracted_characters)} locally extracted characters`;
    const grid = document.createElement("div");
    grid.className = "detail-grid";
    const details = [
      ["Local text", `${formatNumber(row.local_input_tokens)} input · ${formatNumber(row.local_output_tokens)} output · ${formatMoney(row.local_cost_usd)} · ${Number(row.local_latency_seconds).toFixed(2)}s`],
      ["Direct PDF", `${formatNumber(row.direct_input_tokens)} input · ${formatNumber(row.direct_output_tokens)} output · ${formatMoney(row.direct_cost_usd)} · ${Number(row.direct_latency_seconds).toFixed(2)}s`],
      ["Warnings", `${row.extraction_warning_count} extraction · ${row.local_warning_count} local profile · ${row.direct_warning_count} direct profile`],
    ];
    for (const [label, value] of details) {
      const box = document.createElement("div");
      const strong = document.createElement("strong");
      strong.textContent = label;
      const span = document.createElement("span");
      span.textContent = value;
      box.append(strong, span);
      grid.append(box);
    }
    section.append(heading, meta, grid);
    cvDetailList.append(section);
  }
}

function renderFailures(rows) {
  failureList.replaceChildren();
  for (const row of rows) {
    const block = document.createElement("div");
    block.className = `finding-row ${row.status === "failed" ? "is-failure" : ""}`;
    const heading = document.createElement("strong");
    heading.textContent = row.pdf;
    const copy = document.createElement("span");
    if (row.status === "failed") {
      copy.textContent = row.error || "Benchmark did not complete.";
    } else {
      const count = Number(row.extraction_warning_count || 0) + Number(row.local_warning_count || 0) + Number(row.direct_warning_count || 0);
      copy.textContent = count ? `${count} warning${count === 1 ? "" : "s"} across extraction and profile outputs.` : "No warnings reported.";
    }
    block.append(heading, copy);
    failureList.append(block);
  }
}

function renderAccuracy(accuracy) {
  accuracyContent.replaceChildren();
  if (!accuracy || accuracy.status !== "complete") {
    const note = document.createElement("p");
    note.className = "section-note";
    note.textContent = "No accuracy audit is attached to this report yet.";
    accuracyContent.append(note);
    return;
  }
  const local = accuracy.local_text;
  const direct = accuracy.direct_pdf;
  const overview = document.createElement("div");
  overview.className = "detail-grid";
  for (const [label, value] of [
    ["Median overall accuracy", `Local ${local.median_overall_accuracy_score} · Direct ${direct.median_overall_accuracy_score}`],
    ["Missing facts", `Local ${local.missing_information_count} · Direct ${direct.missing_information_count}`],
    ["Quote support", `Local ${local.quote_support_rate_percent ?? "—"}% · Direct ${direct.quote_support_rate_percent ?? "—"}%`],
    ["CV wins", `Local ${accuracy.winner_counts.local_text} · Direct ${accuracy.winner_counts.direct_pdf} · Tie ${accuracy.winner_counts.tie}`],
  ]) {
    const box = document.createElement("div");
    const strong = document.createElement("strong");
    strong.textContent = label;
    const span = document.createElement("span");
    span.textContent = value;
    box.append(strong, span);
    overview.append(box);
  }
  accuracyContent.append(overview);
  for (const documentResult of accuracy.documents || []) {
    const details = document.createElement("details");
    details.className = "cv-detail";
    const summary = document.createElement("summary");
    const localResult = documentResult.judge.local_text;
    const directResult = documentResult.judge.direct_pdf;
    summary.textContent = `${documentResult.pdf} · overall ${localResult.overall_accuracy_score} local / ${directResult.overall_accuracy_score} direct`;
    const copy = document.createElement("div");
    copy.className = "detail-grid";
    for (const [label, values] of [
      ["Local missing from JSON", localResult.missing_information],
      ["Direct missing from JSON", directResult.missing_information],
      ["Local unsupported / hallucinated", [...localResult.unsupported_claims, ...localResult.hallucinated_information]],
      ["Direct unsupported / hallucinated", [...directResult.unsupported_claims, ...directResult.hallucinated_information]],
    ]) {
      const block = document.createElement("div");
      const strong = document.createElement("strong");
      strong.textContent = label;
      const span = document.createElement("span");
      span.textContent = values.length ? values.join("; ") : "None identified.";
      block.append(strong, span);
      copy.append(block);
    }
    details.append(summary, copy);
    accuracyContent.append(details);
  }
}

function renderReport(report = null) {
  const rows = report?.rows || derivedRows();
  const summary = report?.summary || localSummary(rows);
  const completed = rows.filter((row) => row.status === "completed");
  if (!rows.length) return;
  if (report) {
    activeReportRows = report.rows || [];
    activeReportSettings = report.settings || null;
  }

  summaryCopy.textContent = report?.technical_summary || (completed.length
    ? `Across ${completed.length} completed CV${completed.length === 1 ? "" : "s"}, the current median local-minus-direct token difference is ${formatSigned(summary.median_token_difference)}. Final report files will be generated when the queue finishes.`
    : "No CV has completed both approaches yet.");
  metricStrip.replaceChildren();
  addMetric("CVs completed", `${summary.completed} / ${summary.files_total}`, `${summary.failed} failed`);
  addMetric("Total API cost", formatMoney(summary.total_api_cost_usd), "Completed CVs only");
  addMetric("Median token difference", formatSigned(summary.median_token_difference), `${formatSigned(summary.median_token_difference_percent, (v) => `${Number(v).toFixed(1)}%`)} local vs direct`);
  addMetric("Median latency difference", formatSigned(summary.median_latency_difference_seconds, (v) => `${Number(v).toFixed(2)}s`), "Local vs direct");
  renderChart(completed);
  renderTable(rows);
  renderDetails(rows);
  renderFailures(rows);
  renderAccuracy(report?.accuracy);
  emptyState.hidden = true;
  results.hidden = false;
  const hasQueueProfiles = queue.some((item) => item.status === "completed" && item.result);
  const hasSavedProfiles = activeReportRows.some((row) => row.status === "completed" && row.source_job_id);
  matchingTransfer.hidden = !hasQueueProfiles && !hasSavedProfiles;
}

async function addProfilesToMatchingLibrary() {
  const approach = matchingApproach.value;
  const completed = queue.filter((item) => item.status === "completed" && item.result?.results?.[approach]?.profile);
  const savedSources = activeReportRows
    .filter((row) => row.status === "completed" && row.source_job_id)
    .map((row) => ({ job_id: row.source_job_id, source_name: row.pdf }));
  if (!completed.length && !savedSources.length) {
    transferStatus.textContent = `No completed ${approach === "local_text" ? "local-text" : "direct-PDF"} profiles are available.`;
    transferStatus.classList.add("is-error");
    return;
  }
  sendToMatching.disabled = true;
  transferStatus.classList.remove("is-error");
  const transferCount = completed.length || savedSources.length;
  transferStatus.textContent = `Adding ${transferCount} profile${transferCount === 1 ? "" : "s"}…`;
  try {
    const response = await fetch("/api/matching-library", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        profile_type: activeSettings?.profile_type || activeReportSettings?.profile_type || settings().profile_type,
        approach,
        ...(completed.length
          ? {
              entries: completed.map((item) => ({
                source_name: item.file.name,
                profile: item.result.results[approach].profile,
              })),
            }
          : { sources: savedSources }),
      }),
    });
    const payload = await response.json();
    if (!response.ok) throw new Error(payload.detail || "Profiles could not be added.");
    transferStatus.textContent = `${payload.imported} ${payload.profile_type} profile${payload.imported === 1 ? "" : "s"} added · library now has ${payload.mentor_count} mentors and ${payload.mentee_count} mentees.`;
  } catch (error) {
    transferStatus.textContent = error.message || "Profiles could not be added.";
    transferStatus.classList.add("is-error");
  } finally {
    sendToMatching.disabled = false;
  }
}

async function generateReport() {
  const rows = queue.filter((item) => item.status === "completed" || item.status === "failed").map(compactRow);
  if (!rows.length) return;
  const response = await fetch("/api/report", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ rows, settings: activeSettings || settings() }),
  });
  const report = await response.json();
  if (!response.ok) throw new Error(report.detail || "Could not generate the batch report.");
  renderReport(report);
  downloadReport.href = report.report_url;
  downloadCsv.href = report.csv_url;
  viewJson.href = report.json_url;
  resultActions.hidden = false;
}

async function loadReportFromUrl() {
  const reportId = new URLSearchParams(window.location.search).get("report");
  if (!reportId || !/^[a-z0-9]+$/i.test(reportId)) return;
  try {
    const response = await fetch(`/api/reports/${reportId}/batch-results.json`);
    const report = await response.json();
    if (!response.ok) throw new Error(report.detail || "Saved report not found.");
    renderReport(report);
    downloadReport.href = `/api/reports/${reportId}/report.html`;
    downloadCsv.href = `/api/reports/${reportId}/batch-summary.csv`;
    viewJson.href = `/api/reports/${reportId}/batch-results.json`;
    resultActions.hidden = false;
    setStatus("Saved batch report loaded. Add PDFs to start a new run.");
  } catch (error) {
    setStatus(error.message || "Saved report could not be loaded.", true);
  }
}

async function runPending(items) {
  if (!items.length || isRunning) return;
  activeSettings = settings();
  setLoading(true);
  resultActions.hidden = true;
  matchingTransfer.hidden = true;
  let index = 0;
  for (const item of items) {
    index += 1;
    setStatus(`Running ${index} of ${items.length}: ${item.file.name}`);
    try {
      await benchmarkItem(item);
    } catch (error) {
      item.status = "failed";
      item.error = error.message || "Benchmark failed.";
    }
    renderQueue();
    renderReport();
  }
  try {
    setStatus("Generating the downloadable technical report…");
    await generateReport();
    const failed = queue.filter((item) => item.status === "failed").length;
    setStatus(failed ? `Batch complete with ${failed} failure${failed === 1 ? "" : "s"}. Report generated.` : "Batch complete. Report generated.", Boolean(failed));
  } catch (error) {
    setStatus(error.message || "The report could not be generated.", true);
  } finally {
    setLoading(false);
  }
}

function showTab(tabName) {
  for (const tab of document.querySelectorAll(".tab")) {
    const active = tab.dataset.tab === tabName;
    tab.classList.toggle("is-active", active);
    tab.setAttribute("aria-selected", String(active));
  }
  for (const panel of document.querySelectorAll(".tab-panel")) panel.hidden = panel.id !== `${tabName}-panel`;
}

fileInput.addEventListener("change", () => addFiles(fileInput.files));
for (const eventName of ["dragenter", "dragover"]) {
  dropZone.addEventListener(eventName, (event) => {
    event.preventDefault();
    dropZone.classList.add("is-dragging");
  });
}
for (const eventName of ["dragleave", "drop"]) {
  dropZone.addEventListener(eventName, (event) => {
    event.preventDefault();
    dropZone.classList.remove("is-dragging");
  });
}
dropZone.addEventListener("drop", (event) => addFiles(event.dataTransfer.files));

fileQueue.addEventListener("click", (event) => {
  const button = event.target.closest("button[data-id]");
  if (!button || isRunning) return;
  const item = queue.find((entry) => entry.id === button.dataset.id);
  if (!item) return;
  if (button.dataset.action === "retry") {
    item.status = "queued";
    item.error = "";
    renderQueue();
    runPending([item]);
  } else {
    queue = queue.filter((entry) => entry.id !== item.id);
    renderQueue();
    if (!queue.length) {
      results.hidden = true;
      emptyState.hidden = false;
      setStatus("Add one or more CV PDFs to begin.");
    } else {
      renderReport();
    }
  }
});

clearQueueButton.addEventListener("click", () => {
  if (isRunning) return;
  queue = [];
  activeReportRows = [];
  activeReportSettings = null;
  renderQueue();
  results.hidden = true;
  emptyState.hidden = false;
  resultActions.hidden = true;
  matchingTransfer.hidden = true;
  setStatus("Add one or more CV PDFs to begin.");
});

for (const tab of document.querySelectorAll(".tab")) tab.addEventListener("click", () => showTab(tab.dataset.tab));
sendToMatching.addEventListener("click", addProfilesToMatchingLibrary);

form.addEventListener("submit", (event) => {
  event.preventDefault();
  const pending = queue.filter((item) => item.status === "queued" || item.status === "failed");
  for (const item of pending) {
    if (item.status === "failed") {
      item.status = "queued";
      item.error = "";
    }
  }
  runPending(pending);
});

renderQueue();
loadProviderConfig().then(loadReportFromUrl);
