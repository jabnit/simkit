/**
 * SimKit 0.1 (2026-11-11) -- Case register import for the project workbook. UNTESTED STARTER.
 * Google Apps Script, V8 runtime. Paste into the workbook: Extensions > Apps Script > Code.gs, save, reload the sheet.
 *
 * Menu "SimKit":
 *   Import register inbox -- reads every *.csv in the inbox Drive folder (one file per SimKit event, written by
 *                            SimKit.java into <SHARE>\PROJECTS\<PROJ>\00_admin\register_inbox and synced by Google
 *                            Drive for desktop, or dropped there by users), appends the rows to the sheet
 *                            "Case_Register" in its column order, skips rows already present (key: case_id,
 *                            timestamp, event), then moves each processed file into the sub-folder "processed".
 *   Settings              -- stores the inbox folder ID (or folder URL) in the Document Properties.
 *
 * Files that cannot be parsed stay in the inbox and are listed in the result message.
 * For an unattended import, add a time-driven trigger on importRegisterInboxQuiet (Triggers > Add trigger).
 */

const SK = {
  SHEET: 'Case_Register',
  PROP_FOLDER: 'SIMKIT_INBOX_FOLDER_ID',
  PROCESSED: 'processed',
  // Spec section 7 -- identical to the header written by SimKit.java.
  COLUMNS: ['timestamp', 'case_id', 'event', 'user', 'project', 'config', 'stage', 'version', 'parent_case',
    'description', 'star_version', 'sim_file', 'cells_total', 'time_step_s', 'max_time_s_or_steps',
    'continua_models', 'changes_vs_previous', 'status', 'key_results', 'audit_file'],
  REQUIRED: ['timestamp', 'case_id', 'event'],
  TIME_FORMAT: 'yyyy-MM-dd HH:mm:ss',
};

/** Adds the SimKit menu. */
function onOpen() {
  SpreadsheetApp.getUi().createMenu('SimKit')
    .addItem('Import register inbox', 'importRegisterInbox')
    .addItem('Settings', 'simkitSettings')
    .addToUi();
}

/** Settings dialog: inbox folder ID or URL -> Document Properties. */
function simkitSettings() {
  const ui = SpreadsheetApp.getUi();
  const props = PropertiesService.getDocumentProperties();
  const current = props.getProperty(SK.PROP_FOLDER) || '(not set)';
  const r = ui.prompt('SimKit settings',
    'Drive folder ID or folder URL of the register inbox.\nCurrent: ' + current, ui.ButtonSet.OK_CANCEL);
  if (r.getSelectedButton() !== ui.Button.OK) return;
  const id = extractFolderId_(r.getResponseText());
  if (!id) {
    ui.alert('No valid folder ID entered; settings unchanged.');
    return;
  }
  let name;
  try {
    name = DriveApp.getFolderById(id).getName();
  } catch (e) {
    ui.alert('Cannot open folder ' + id + ': ' + e.message);
    return;
  }
  props.setProperty(SK.PROP_FOLDER, id);
  ui.alert('Register inbox set to "' + name + '" (' + id + ').');
}

/** Menu entry: import with a result message. */
function importRegisterInbox() {
  const ui = SpreadsheetApp.getUi();
  const id = PropertiesService.getDocumentProperties().getProperty(SK.PROP_FOLDER);
  if (!id) {
    ui.alert('Set the inbox folder first: SimKit > Settings.');
    return;
  }
  const lock = LockService.getDocumentLock();
  if (!lock.tryLock(30000)) {
    ui.alert('Another import is running; try again in a minute.');
    return;
  }
  try {
    ui.alert('SimKit import', summary_(importFolder_(id)), ui.ButtonSet.OK);
  } finally {
    lock.releaseLock();
  }
}

/** Trigger entry: same import, no dialogs (result in the execution log). */
function importRegisterInboxQuiet() {
  const id = PropertiesService.getDocumentProperties().getProperty(SK.PROP_FOLDER);
  if (!id) return;
  const lock = LockService.getDocumentLock();
  if (!lock.tryLock(30000)) return;
  try {
    console.log(summary_(importFolder_(id)));
  } finally {
    lock.releaseLock();
  }
}

/** Imports all CSV files of the folder; rows are appended before any file is moved. */
function importFolder_(folderId) {
  const folder = DriveApp.getFolderById(folderId);
  const sheet = registerSheet_();
  const header = ensureHeader_(sheet);
  const seen = existingKeys_(sheet, header);
  const res = { files: 0, rows: 0, duplicates: 0, problems: [] };
  const newRows = [];
  const doneFiles = [];
  const files = folder.getFiles();
  while (files.hasNext()) {
    const f = files.next();
    if (!/\.csv$/i.test(f.getName())) continue;
    try {
      for (const rec of parseCsvFile_(f)) {
        const k = key_(rec.case_id, rec.timestamp, rec.event);
        if (seen.has(k)) {
          res.duplicates++;
          continue;
        }
        seen.add(k);
        newRows.push(header.map((h) => cell_(rec[h])));
      }
      doneFiles.push(f);
    } catch (e) {
      res.problems.push(f.getName() + ': ' + e.message);
    }
  }
  const tsCol = header.indexOf('timestamp');
  newRows.sort((a, b) => String(a[tsCol]).localeCompare(String(b[tsCol])));
  if (newRows.length) {
    sheet.getRange(sheet.getLastRow() + 1, 1, newRows.length, header.length).setValues(newRows);
    SpreadsheetApp.flush();
  }
  const processed = subfolder_(folder, SK.PROCESSED);
  doneFiles.forEach((f) => f.moveTo(processed));
  res.files = doneFiles.length;
  res.rows = newRows.length;
  return res;
}

/** The Case_Register sheet (created if missing). */
function registerSheet_() {
  const ss = SpreadsheetApp.getActiveSpreadsheet();
  return ss.getSheetByName(SK.SHEET) || ss.insertSheet(SK.SHEET);
}

/** Header of the sheet, lower-case; writes the spec header on an empty sheet and appends missing columns. */
function ensureHeader_(sheet) {
  if (sheet.getLastRow() === 0 || sheet.getLastColumn() === 0) {
    sheet.getRange(1, 1, 1, SK.COLUMNS.length).setValues([SK.COLUMNS]);
    sheet.setFrozenRows(1);
    return SK.COLUMNS.slice();
  }
  const header = sheet.getRange(1, 1, 1, sheet.getLastColumn()).getValues()[0]
    .map((h) => String(h).trim().toLowerCase());
  const missing = SK.COLUMNS.filter((c) => header.indexOf(c) < 0);
  if (missing.length) {
    sheet.getRange(1, header.length + 1, 1, missing.length).setValues([missing]);
    missing.forEach((c) => header.push(c));
  }
  return header;
}

/** Keys (case_id|timestamp|event) of the rows already in the sheet. */
function existingKeys_(sheet, header) {
  const keys = new Set();
  const n = sheet.getLastRow() - 1;
  if (n < 1) return keys;
  const data = sheet.getRange(2, 1, n, header.length).getValues();
  const ic = header.indexOf('case_id');
  const it = header.indexOf('timestamp');
  const ie = header.indexOf('event');
  data.forEach((r) => keys.add(key_(r[ic], r[it], r[ie])));
  return keys;
}

/** Dedupe key; a timestamp the sheet turned into a Date is formatted back to yyyy-MM-dd HH:mm:ss. */
function key_(caseId, ts, event) {
  let t = ts;
  if (Object.prototype.toString.call(ts) === '[object Date]') {
    t = Utilities.formatDate(ts, SpreadsheetApp.getActiveSpreadsheet().getSpreadsheetTimeZone(), SK.TIME_FORMAT);
  }
  return [String(caseId).trim(), String(t).trim(), String(event).trim().toLowerCase()].join('|');
}

/** One inbox CSV -> array of records keyed by lower-case header names. */
function parseCsvFile_(file) {
  let text = file.getBlob().getDataAsString('UTF-8');
  if (text.charCodeAt(0) === 0xFEFF) text = text.slice(1);
  const rows = Utilities.parseCsv(text.replace(/\r\n?/g, '\n'));
  if (rows.length < 2) throw new Error('no data row');
  const head = rows[0].map((h) => String(h).trim().toLowerCase());
  const absent = SK.REQUIRED.filter((c) => head.indexOf(c) < 0);
  if (absent.length) throw new Error('missing column(s) ' + absent.join(', '));
  const out = [];
  for (let i = 1; i < rows.length; i++) {
    if (rows[i].every((v) => String(v).trim() === '')) continue;
    const rec = {};
    head.forEach((h, k) => { rec[h] = rows[i][k] !== undefined ? rows[i][k] : ''; });
    out.push(rec);
  }
  return out;
}

/** Cell value; text that the sheet would read as a formula is prefixed with an apostrophe. */
function cell_(v) {
  const s = v === undefined || v === null ? '' : String(v);
  if (/^[=+@]/.test(s) || (/^-/.test(s) && isNaN(Number(s)))) return "'" + s;
  return s;
}

/** Sub-folder by name (created if missing). */
function subfolder_(parent, name) {
  const it = parent.getFoldersByName(name);
  return it.hasNext() ? it.next() : parent.createFolder(name);
}

function extractFolderId_(text) {
  const t = String(text || '').trim();
  const m = t.match(/\/folders\/([A-Za-z0-9_-]+)/) || t.match(/[?&]id=([A-Za-z0-9_-]+)/);
  if (m) return m[1];
  return /^[A-Za-z0-9_-]{10,}$/.test(t) ? t : '';
}

function summary_(r) {
  let s = 'Imported ' + r.rows + ' row(s) from ' + r.files + ' file(s); ' + r.duplicates + ' duplicate(s) skipped.';
  if (r.problems.length) s += '\n\nLeft in the inbox (not imported):\n' + r.problems.join('\n');
  return s;
}
