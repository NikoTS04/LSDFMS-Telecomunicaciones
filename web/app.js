"use strict";

const byId = (id) => document.getElementById(id);
const numbers = new Intl.NumberFormat("es-PE");
const dateTimes = new Intl.DateTimeFormat("es-PE", {
  dateStyle: "short", timeStyle: "medium",
});
const clockTimes = new Intl.DateTimeFormat("es-PE", { timeStyle: "medium" });
const columns = { phone: "teléfono", timestamp: "fecha/hora", size: "tamaño" };
const minLong = -(2n ** 63n);
const maxLong = 2n ** 63n - 1n;
const demoRequested = new URLSearchParams(location.search).get("mock") === "1";
const searchForm = byId("search-form");
const recordForm = byId("record-form");
const deleteDialog = byId("delete-dialog");
const resultRows = byId("result-rows");
const recordFields = ["phone", "timestamp", "service", "cell", "duration", "roaming"];
let api = demoRequested ? createMockApi() : realApi;
let status = null;
let online = false;
let busy = false;
let refreshing = false;
let statusRequest = null;
let lastSearch = null;
let resultVersion = null;
let editing = null;
let pendingDelete = null;
let maintenanceScheduled = false;
let searchNeedsRefresh = false;

function element(tag, text, className) {
  const node = document.createElement(tag);
  if (text !== undefined) node.textContent = text;
  if (className) node.className = className;
  return node;
}

function setText(id, text) {
  const node = byId(id);
  if (node.textContent !== text) node.textContent = text;
}

function badge(id, text, tone = "") {
  setText(id, text);
  byId(id).className = "badge dot " + tone;
}

function localDateValue(seconds) {
  const date = new Date(Number(seconds) * 1000);
  if (!Number.isFinite(date.getTime()) || date.getFullYear() < 1) return "";
  const pad = (value) => String(value).padStart(2, "0");
  const year = String(date.getFullYear()).padStart(4, "0");
  return year + "-" + pad(date.getMonth() + 1) + "-" + pad(date.getDate()) +
    "T" + pad(date.getHours()) + ":" + pad(date.getMinutes()) + ":" + pad(date.getSeconds());
}

function epochSeconds(value) {
  const date = new Date(value);
  const seconds = date.getTime() / 1000;
  if (!Number.isSafeInteger(seconds) || localDateValue(seconds).slice(0, value.length) !== value) {
    throw new Error("Introduzca una fecha y hora local válidas.");
  }
  return seconds;
}

function formatDate(milliseconds, fallback) {
  const date = new Date(milliseconds);
  return Number.isFinite(date.getTime()) ? dateTimes.format(date) : fallback;
}

function longValue(value, message, minimum = minLong) {
  if (!/^-?\d+$/.test(value)) throw new Error(message);
  const number = BigInt(value);
  if (number < minimum || number > maxLong) throw new Error(message);
  return number.toString();
}

function parseResponse(text) {
  // Retain the digits of long record identifiers before JSON.parse converts numbers to doubles.
  const jsonStrings = /"(?:[^"\\]|\\.)*"(?:\s*:\s*-?\d+(?=\s*[,}]))?/g;
  const exactIntegers = /^"(phone|offset|timestamp)"\s*:\s*(-?\d+)$/;
  return JSON.parse(text.replace(jsonStrings, (token) => {
    return token.replace(exactIntegers, '"$1":"$2"');
  }));
}

async function realApi(method, path, body) {
  const options = { method, cache: "no-store", signal: AbortSignal.timeout(10000) };
  if (body) {
    const { phone, offset, ...fields } = body;
    const identity = offset === undefined ? "" : ',"offset":' + offset;
    // Both identifiers are validated decimal integers before entering the JSON body.
    options.headers = { "Content-Type": "application/json" };
    options.body = JSON.stringify(fields).slice(0, -1) + ',"phone":' + phone + identity + "}";
  }
  let response;
  try {
    response = await fetch("/api/" + path, options);
  } catch {
    throw new Error("El servidor no respondió. Compruebe la conexión con el motor.");
  }
  let result;
  try {
    result = parseResponse(await response.text());
  } catch {
    throw new Error("El servidor no devolvió una respuesta JSON válida (HTTP " + response.status + ").");
  }
  if (!response.ok) throw new Error(result?.error || "Error del servidor (HTTP " + response.status + ").");
  return result;
}

function notify(message, tone = "info") {
  byId("feedback").className = "feedback " + tone;
  setText("feedback-text", message);
  byId("feedback").hidden = false;
}

function log(title, detail, before = status?.dataVersion, after = before, error = false) {
  const now = new Date();
  const entry = element("li");
  const time = element("time", clockTimes.format(now));
  time.dateTime = now.toISOString();
  const message = element("div");
  message.append(element("strong", title, error ? "log-error" : ""), element("p", detail, "log-detail"));
  const versions = before == null ? "Versión de datos sin confirmar" :
    "Datos v" + before + (after !== before ? " → v" + after : "");
  entry.append(time, message, element("span", versions, "transition"));
  byId("activity-log").prepend(entry);
  byId("activity-empty").hidden = true;
}

function locationLabel(record) {
  return "F_" + record.fileId + ".dat · " + record.offset;
}

function statusAllowsChanges() {
  return online && !status?.needsRecover && !status?.maintenanceRunning && !maintenanceScheduled;
}

function offsetsReady() {
  return statusAllowsChanges() && !refreshing && !searchNeedsRefresh &&
    status?.indexVersion === resultVersion;
}

function updateControls() {
  byId("maintenance-button").disabled = busy || !statusAllowsChanges();
  byId("search-button").disabled = busy || refreshing || !online || status?.maintenanceRunning === true;
  byId("save-record").disabled = busy || !statusAllowsChanges() || (editing !== null && !offsetsReady());
  byId("cancel-edit").disabled = busy;
  byId("confirm-delete").disabled = busy || !offsetsReady();
  byId("enable-mock").disabled = busy || refreshing;
  for (const field of document.querySelectorAll("form input, form select")) field.disabled = busy;
  for (const button of resultRows.querySelectorAll("button")) {
    button.disabled = busy || !offsetsReady();
  }
}

function renderStatus(next) {
  const previous = status;
  status = next;
  online = true;
  byId("offline").hidden = true;
  byId("recover").hidden = !next.needsRecover;
  badge("connection", api === realApi ? "Servidor conectado" : "Modo demostración",
    api === realApi ? "good" : "info");
  const metrics = {
    "active-count": next.activeRecords, "deleted-count": next.deletedRecords,
    "total-count": next.totalRecords, "file-count": next.files, "queue-depth": next.queueDepth,
    "data-version": next.dataVersion, "index-version": next.indexVersion,
  };
  for (const [id, value] of Object.entries(metrics)) setText(id, numbers.format(value));
  badge("index-badge", next.stale ? "Índice desactualizado" : "Índice actualizado", next.stale ? "warning" : "good");
  setText("index-detail", next.stale ?
    "Las búsquedas incluyen los registros agregados desde la última reindexación (delta)." :
    "El índice corresponde a la versión actual de los datos.");

  const maintenance = next.lastMaintenance;
  const finished = previous && maintenance &&
    (maintenance.at !== previous.lastMaintenance?.at || next.indexVersion !== previous.indexVersion);
  if (next.maintenanceRunning || finished || next.needsRecover) maintenanceScheduled = false;
  const maintenanceText = next.maintenanceRunning ? "En ejecución" : maintenanceScheduled ? "En cola" : "En espera";
  badge("maintenance-state", maintenanceText, maintenanceText === "En espera" ? "" : "info");
  const summary = maintenance
    ? formatDate(maintenance.at, "Fecha sin confirmar") + " · " +
      numbers.format(maintenance.removedRecords) +
      (maintenance.removedRecords === 1 ? " registro retirado · " : " registros retirados · ") +
      numbers.format(maintenance.durationMs) + " ms · " +
      maintenance.indexes.map((column) => columns[column] ?? column).join(", ")
    : "Todavía no se ha completado ningún ciclo.";
  setText("maintenance-summary", summary);

  if (finished) {
    log("Mantenimiento completado", summary + ". Índice v" + previous.indexVersion +
      " → v" + next.indexVersion + ".", previous.dataVersion, next.dataVersion);
  }
  if (lastSearch && (next.indexVersion !== resultVersion || finished ||
      (previous && next.dataVersion !== previous.dataVersion))) searchNeedsRefresh = true;
  if (previous && (next.indexVersion !== previous.indexVersion || finished)) {
    if (editing) {
      resetRecordForm();
      notify("El mantenimiento cambió las ubicaciones. Seleccione de nuevo el registro para editarlo.", "warning");
    }
    if (deleteDialog.open) deleteDialog.close("cancel");
  }
  updateControls();
}

async function readStatus(fresh = false) {
  if (statusRequest) {
    if (!fresh) return statusRequest;
    await statusRequest;
    return readStatus(true);
  }
  statusRequest = (async () => {
    try {
      renderStatus(await api("GET", "status"));
      return true;
    } catch (error) {
      online = false;
      badge("connection", "Servidor no disponible", "warning");
      setText("offline-message", error.message);
      byId("offline").hidden = false;
      updateControls();
      return false;
    }
  })();
  try {
    return await statusRequest;
  } finally {
    statusRequest = null;
  }
}

async function pollStatus() {
  if (refreshing) return;
  await readStatus();
  await refreshResults();
}

function updateSearchInputs() {
  const column = byId("search-column").value;
  const range = byId("search-mode").value === "range";
  setText("search-lower-label", range ? "Desde" : "Valor");
  byId("search-upper-field").hidden = !range;
  for (const id of ["search-lower", "search-upper"]) {
    const input = byId(id);
    const type = column === "timestamp" ? "datetime-local" : "text";
    if (input.type !== type) input.value = "";
    input.type = type;
    input.step = "1";
    input.placeholder = column === "phone" ? "51912345678" : "64";
  }
  byId("search-upper").required = range;
}

function searchQuery() {
  const col = byId("search-column").value;
  const range = byId("search-mode").value === "range";
  const parse = (value) => col === "timestamp" ? String(epochSeconds(value)) :
    longValue(value.trim(), "Introduzca un número entero válido para " + columns[col] + ".");
  const lo = parse(byId("search-lower").value);
  const hi = range ? parse(byId("search-upper").value) : lo;
  if (BigInt(lo) > BigInt(hi)) throw new Error("El valor inicial debe ser menor o igual al final.");
  return new URLSearchParams({ col, lo, hi, limit: byId("search-limit").value });
}

function renderResults(data) {
  const rows = document.createDocumentFragment();
  for (const record of data.results) {
    const row = element("tr");
    const values = [
      record.phone, formatDate(Number(record.timestamp) * 1000, record.timestamp + " s"),
      numbers.format(record.payloadLen), record.service, record.cell,
      numbers.format(record.duration) + " s", record.roaming, locationLabel(record),
    ];
    row.append(...values.map((value) => element("td", String(value))));
    const source = element("td");
    const delta = record.source === "delta";
    source.append(element("span", delta ? "Delta" : "Índice", "badge " + (delta ? "warning" : "good")));
    const actions = element("td", undefined, "actions");
    const edit = element("button", "Editar");
    const remove = element("button", "Eliminar", "delete");
    for (const button of [edit, remove]) {
      button.type = "button";
      button.setAttribute("aria-label", button.textContent + " registro " + record.phone + ", " + locationLabel(record));
    }
    edit.addEventListener("click", () => startEdit(record));
    remove.addEventListener("click", () => confirmDelete(record));
    actions.append(edit, remove);
    row.append(source, actions);
    rows.append(row);
  }
  resultRows.replaceChildren(rows);
  const count = data.results.length;
  const summary = count === 0 ? "No se encontraron registros." :
    numbers.format(count) + (count === 1 ? " registro" : " registros") +
    (data.truncated ? " · Límite alcanzado. Acote la búsqueda para ver más coincidencias." : "");
  setText("result-summary", summary);
  return count;
}

async function loadResults(query) {
  const dataVersion = status?.dataVersion;
  const data = await api("GET", "search?" + query.toString());
  lastSearch = query;
  resultVersion = data.indexVersion;
  searchNeedsRefresh = resultVersion !== status?.indexVersion || dataVersion !== status?.dataVersion;
  const count = renderResults(data);
  updateControls();
  return count;
}

async function refreshResults() {
  if (busy || refreshing || !online || status?.maintenanceRunning || !searchNeedsRefresh || !lastSearch) return;
  refreshing = true;
  updateControls();
  try {
    await loadResults(lastSearch);
  } catch (error) {
    const message = "No se pudieron actualizar los resultados. " + error.message;
    if (byId("result-summary").textContent !== message) notify(message, "warning");
    setText("result-summary", message);
  } finally {
    refreshing = false;
    updateControls();
  }
}

async function operate(title, action) {
  if (busy || refreshing) return;
  busy = true;
  byId("feedback").hidden = true;
  updateControls();
  let before = status?.dataVersion;
  try {
    // Recheck before sending offsets, especially after waiting in the confirmation dialog.
    if (!await readStatus(true)) throw new Error("No se pudo consultar el estado del motor.");
    before = status.dataVersion;
    const result = await action();
    log(title, result.detail, before, result.dataVersion ?? before);
    if (result.notice) notify(result.detail, "good");
    await readStatus(true);
  } catch (error) {
    log(title + " · Error", error.message, before, before, true);
    notify(error.message, "error");
  } finally {
    busy = false;
    updateControls();
    await refreshResults();
  }
}

async function sendMutation(method, path, body) {
  // Sample the queue while the mutation is waiting for a response.
  const request = api(method, path, body);
  const queueCheck = setTimeout(readStatus, 160);
  try {
    return await request;
  } finally {
    clearTimeout(queueCheck);
  }
}

function recordData() {
  const values = Object.fromEntries(recordFields.map((key) => [key, byId("record-" + key).value]));
  return {
    phone: longValue(values.phone, "El teléfono debe ser un entero positivo válido.", 1n),
    timestamp: epochSeconds(values.timestamp),
    service: values.service,
    cell: Number(values.cell),
    duration: Number(values.duration),
    roaming: values.roaming,
  };
}

function resetRecordForm() {
  editing = null;
  recordForm.reset();
  byId("record-timestamp").value = localDateValue(Math.floor(Date.now() / 1000));
  setText("record-title", "Insertar registro");
  setText("save-record", "Insertar registro");
  byId("edit-detail").hidden = true;
  byId("cancel-edit").hidden = true;
  updateControls();
}

function startEdit(record) {
  const timestamp = localDateValue(record.timestamp);
  if (!timestamp) {
    notify("La fecha del registro está fuera del intervalo que admite el navegador.", "error");
    return;
  }
  editing = { fileId: record.fileId, offset: record.offset, version: resultVersion };
  for (const key of recordFields) {
    byId("record-" + key).value = key === "timestamp" ? timestamp : record[key];
  }
  setText("record-title", "Editar registro");
  setText("save-record", "Guardar cambios");
  setText("edit-detail", locationLabel(record));
  byId("edit-detail").hidden = false;
  byId("cancel-edit").hidden = false;
  updateControls();
  byId("record-phone").focus();
}

function checkWritable(version) {
  if (version !== undefined && (version !== status.indexVersion || !offsetsReady())) {
    throw new Error("La ubicación del registro ya no está disponible. Vuelva a buscar antes de continuar.");
  }
  if (!statusAllowsChanges()) throw new Error("Los cambios están suspendidos mientras el motor está ocupado o requiere recuperación.");
}

function confirmDelete(record) {
  pendingDelete = { record, version: resultVersion };
  setText("delete-detail", "Teléfono " + record.phone + " · " + locationLabel(record));
  deleteDialog.returnValue = "";
  deleteDialog.showModal();
}

searchForm.addEventListener("submit", (event) => {
  event.preventDefault();
  operate("Búsqueda", async () => {
    const query = searchQuery();
    const count = await loadResults(query);
    const matches = numbers.format(count) + (count === 1 ? " coincidencia." : " coincidencias.");
    return { detail: "Consulta por " + columns[query.get("col")] + " · " + matches };
  });
});
for (const id of ["search-column", "search-mode"]) {
  byId(id).addEventListener("change", updateSearchInputs);
}

recordForm.addEventListener("submit", (event) => {
  event.preventDefault();
  const target = editing;
  operate(target ? "Edición" : "Inserción", async () => {
    checkWritable(target?.version);
    const body = recordData();
    if (target) Object.assign(body, { fileId: target.fileId, offset: target.offset });
    const result = await sendMutation(target ? "PUT" : "POST", "records", body);
    resetRecordForm();
    if (lastSearch) searchNeedsRefresh = true;
    return {
      dataVersion: result.dataVersion,
      detail: (target ? "Registro actualizado" : "Registro insertado") + " · " +
        result.record.phone + " · " + locationLabel(result.record) + ".",
      notice: true,
    };
  });
});
byId("cancel-edit").addEventListener("click", () => {
  resetRecordForm();
  byId("record-phone").focus();
});

deleteDialog.addEventListener("close", () => {
  const selection = pendingDelete;
  pendingDelete = null;
  if (deleteDialog.returnValue !== "delete" || !selection) return;
  const { record, version } = selection;
  operate("Eliminación", async () => {
    checkWritable(version);
    const query = new URLSearchParams({ fileId: record.fileId, offset: record.offset });
    const result = await sendMutation("DELETE", "records?" + query.toString());
    if (editing?.fileId === record.fileId && editing.offset === record.offset) resetRecordForm();
    if (lastSearch) searchNeedsRefresh = true;
    return {
      dataVersion: result.dataVersion,
      detail: "Registro de " + record.phone + " marcado como eliminado.",
      notice: true,
    };
  });
});

byId("maintenance-button").addEventListener("click", () => {
  operate("Mantenimiento solicitado", async () => {
    checkWritable();
    const result = await api("POST", "maintenance");
    if (!result.scheduled) throw new Error("El servidor no pudo programar el mantenimiento.");
    maintenanceScheduled = true;
    badge("maintenance-state", "En cola", "info");
    return { detail: "Se programó el ciclo de PACK y reindexación.", notice: true };
  });
});

function initializeDemoSearch() {
  byId("search-column").value = "phone";
  byId("search-mode").value = "range";
  updateSearchInputs();
  byId("search-lower").value = "51900000000";
  byId("search-upper").value = "51999999999";
  lastSearch = searchQuery();
  searchNeedsRefresh = true;
}

byId("enable-mock").addEventListener("click", () => {
  const outstanding = statusRequest;
  busy = true;
  updateControls();
  (async () => {
    await outstanding;
    api = createMockApi();
    status = null;
    resultVersion = null;
    maintenanceScheduled = false;
    resetRecordForm();
    initializeDemoSearch();
    await readStatus();
    busy = false;
    await refreshResults();
    updateControls();
    log("Modo demostración activado", "Se cargaron registros simulados en memoria.");
  })();
});
byId("dismiss-feedback").addEventListener("click", () => { byId("feedback").hidden = true; });

resetRecordForm();
updateSearchInputs();
if (demoRequested) initializeDemoSearch();
void pollStatus();
setInterval(pollStatus, 1500);

// In-browser demo API. The queue is serialized and PACK rewrites offsets per file.
function createMockApi() {
  const files = 3;
  const indexedColumns = ["phone", "timestamp", "size"];
  const keyNames = { phone: "phone", timestamp: "timestamp", size: "payloadLen" };
  let dataVersion = 1;
  let indexVersion = 1;
  let lastMaintenance = null;
  let queueDepth = 0;
  let maintenanceRunning = false;
  let maintenanceQueued = false;
  let tail = Promise.resolve();
  const baseTime = Math.floor(Date.now() / 1000);
  let records = [];
  for (const [i, phone] of ["51912345678", "51915648203", "51923510864", "51941820653", "51970164285"].entries()) {
    append({
      phone, timestamp: baseTime - i * 257, service: ["VOZ", "DATA", "SMS"][i % 3],
      cell: 41823 + i * 103, duration: [82, 190, 0][i % 3], roaming: ["PE", "PE", "CL"][i % 3],
    }, i % files + 1, "index", [128, 256, 96, 160, 192][i]);
  }

  function append(data, fileId, source = "delta", payloadLen = 64) {
    const last = records.findLast((record) => record.fileId === fileId);
    const offset = last ? last.offset + last.payloadLen + 21 : 0;
    const record = { ...data, fileId, offset, payloadLen, source, deleted: false };
    records.push(record);
    return record;
  }

  function view({ deleted, ...record }) {
    return { ...record, offset: String(record.offset) };
  }

  function delay(ms) {
    return new Promise((resolve) => setTimeout(resolve, ms));
  }

  function enqueue(work) {
    queueDepth++;
    const job = tail.then(async () => {
      await delay(650);
      queueDepth--;
      return work();
    });
    tail = job.catch(() => {});
    return job;
  }

  function mockStatus() {
    const activeRecords = records.filter((record) => !record.deleted).length;
    return {
      dataVersion, indexVersion, stale: dataVersion !== indexVersion, files, activeRecords,
      totalRecords: records.length, deletedRecords: records.length - activeRecords, queueDepth,
      maintenanceRunning, needsRecover: false, lastMaintenance,
    };
  }

  function search(params) {
    const key = keyNames[params.get("col")];
    const lo = BigInt(longValue(params.get("lo") ?? "", "Límite inferior inválido."));
    const hi = BigInt(longValue(params.get("hi") ?? "", "Límite superior inválido."));
    const limit = Number(params.get("limit") ?? 100);
    if (!Object.hasOwn(keyNames, params.get("col")) || lo > hi || !Number.isInteger(limit) || limit < 1 || limit > 500) {
      throw new Error("Parámetros de búsqueda inválidos.");
    }
    const matches = records.filter((record) => {
      const value = BigInt(record[key]);
      return !record.deleted && value >= lo && value <= hi;
    }).sort((a, b) => {
      const difference = BigInt(a[key]) - BigInt(b[key]);
      return Math.sign(Number(difference)) || a.fileId - b.fileId || a.offset - b.offset;
    });
    return { indexVersion, results: matches.slice(0, limit).map(view), truncated: matches.length > limit };
  }

  function locate(fileId, offset) {
    const record = records.find((candidate) =>
      !candidate.deleted && candidate.fileId === Number(fileId) && String(candidate.offset) === String(offset));
    if (!record) throw new Error("No se encontró el registro activo en esa ubicación.");
    return record;
  }

  function mutate(method, params, body) {
    if (method === "DELETE") {
      locate(params.get("fileId"), params.get("offset")).deleted = true;
      return { deleted: true, dataVersion: ++dataVersion };
    }
    const { fileId, offset, ...data } = body;
    longValue(data.phone, "Teléfono inválido.", 1n);
    if (!Number.isSafeInteger(data.timestamp) || !["VOZ", "SMS", "DATA"].includes(data.service) ||
        !["PE", "CL", "CO", "EC", "US"].includes(data.roaming) ||
        !Number.isInteger(data.cell) || data.cell < 10000 || data.cell > 99999 ||
        !Number.isInteger(data.duration) || data.duration < 0 || data.duration > 3600) {
      throw new Error("Los datos del registro no son válidos.");
    }
    const previous = method === "PUT" ? locate(fileId, offset) : null;
    const record = append(data, previous?.fileId ?? files);
    if (previous) previous.deleted = true;
    const result = { record: view(record), dataVersion: ++dataVersion };
    if (previous) result.old = { fileId, offset };
    return result;
  }

  function scheduleMaintenance() {
    if (maintenanceQueued || maintenanceRunning) throw new Error("Ya hay un mantenimiento programado.");
    maintenanceQueued = true;
    void enqueue(async () => {
      maintenanceQueued = false;
      maintenanceRunning = true;
      const started = Date.now();
      await delay(2000);
      const count = records.length;
      records = records.filter((record) => !record.deleted);
      const offsets = new Map();
      for (const record of records) {
        record.offset = offsets.get(record.fileId) ?? 0;
        offsets.set(record.fileId, record.offset + record.payloadLen + 21);
        record.source = "index";
      }
      indexVersion = dataVersion;
      lastMaintenance = {
        at: Date.now(), durationMs: Date.now() - started,
        removedRecords: count - records.length, indexes: indexedColumns,
      };
      maintenanceRunning = false;
    });
    return { scheduled: true };
  }

  return async (method, path, body) => {
    const [route, query] = path.split("?");
    const params = new URLSearchParams(query);
    if (method === "GET" && route === "status") return mockStatus();
    if (method === "GET" && route === "search") return search(params);
    if (method === "POST" && route === "maintenance") return scheduleMaintenance();
    if (route === "records" && ["POST", "PUT", "DELETE"].includes(method)) {
      return enqueue(() => mutate(method, params, body));
    }
    throw new Error("La operación solicitada no existe.");
  };
}
