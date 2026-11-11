// SimKit.java -- SimKit 0.2 (2026-11-11) -- UNTESTED STARTER for Simcenter STAR-CCM+ 2402 (19.02). See TESTING.txt.
//
// Single entry point of the team toolkit (spec: SIMKIT_SPEC.md section 6 + SIMKIT_SPEC_v02.md section A):
//   * interactive: a Swing menu window with 20 tools, grouped CASE / CLEAN / CHECK / RUN-OUTPUT / LIBRARY /
//     PARAMETERS / TOOLS;
//   * batch (headless): runs -Dsimkit.action=audit|export|precheck (or env SIMKIT_ACTION); default audit + export.
//       starccm+ -batch run,S:\CFD\_SIMKIT\SimKit.java <sim>
//
// New in 0.2 -- parameter synchronisation ("only what differs from the default gets a parameter"):
//   * config\settings_catalogue.csv lists numeric settings (mesh, solvers, stopping, boundaries, continua) with
//     a reflection path (getDefaultValues/get[star.meshing.BaseSize]...), resolved at run time: new rows need
//     no recompilation. Each setting is compared (SI, relative tolerance 1e-9) with its baseline:
//     templates\<PROJ>\<template>_baseline.csv, else config\factory_defaults_<release>.csv (newest if the exact
//     release is missing), else "no baseline" (never parameterised).
//   * A setting that differs gets a managed parameter p_<FAMILY>_<inst>_<prop> (FAMILY = MESH|SOLV|STOP|BC|PHYS)
//     with the dimensions and units of the setting, and is linked to it (${p_...}). Direct edits are adopted; a
//     setting back at its default is unlinked and its managed parameter deleted if nothing references it.
//     Other parameters (p_mdotAir, ...) are never modified, unlinked or deleted.
//   * Runs as a preview (JTable, rows can be deselected) in Parameter sync / Save new version / New stage,
//     as a WARN count in Pre-run check, and as report-only sections NONDEFAULT + PARAMSYNC of every setup audit
//     (so also in batch). Owner tool "Snapshot baseline" writes the baselines (only write inside _SIMKIT).
//   * TOOLS > Engineering calculators plays lib\generic\SimKitCalc.java (separate macro).
//
// Conventions in this file
//   * Every STAR-CCM+ API call whose exact 2402 signature is not certain carries a tag [Unn] and runs inside
//     try/catch(Throwable) that logs "n/a (<reason>)" and continues. TESTING.txt lists every tag.
//     If a tagged line does not COMPILE in your build: record the single GUI action with the macro recorder
//     (File > Macro > Start Recording), and paste the generated line at the tagged place.
//   * Read-only "getter" calls are never produced by the recorder, so the least certain getters are called
//     through Reflect.tryCall (compiles in any build; returns null -> "n/a" if the method name differs).
//   * All STAR API calls run on the macro thread: the menu is a modal dialog that returns the chosen tool;
//     the tool runs; the menu re-opens. A tool never crashes the session (runTool catches Throwable).
//   * Files are written only below <SHARE>\PROJECTS\<PROJ> (Fs.guard); the single exception is the owner tool
//     "Snapshot baseline", which may write inside _SIMKIT after an explicit owner confirmation.
//     Objects are renamed only after a preview and an explicit confirmation. The ONLY deletion SimKit ever
//     does is of managed p_(MESH|SOLV|STOP|BC|PHYS)_ parameters that nothing references, after the Parameter
//     sync preview and a second confirmation; every deletion is logged. No file is ever deleted.
package macro;

import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableModel;

import star.base.neo.*;
import star.base.report.*;
import star.common.*;
import star.meshing.*;
import star.vis.*;

public class SimKit extends StarMacro {

  // =====================================================================================================
  // Constants (edit here)
  // =====================================================================================================

  static final String VERSION = "SimKit 0.2.1 (2026-11-11)";
  /** Team share. Overridden by the environment variable SIMKIT_SHARE (or -Dsimkit.share=...). */
  static final String SHARE_ROOT_WINDOWS = "S:\\CFD";
  /** Team share on Linux (RHEL 7/9): mount point of the same share. Keep in step with SHARE_ROOT_WINDOWS. */
  static final String SHARE_ROOT_LINUX = "/mnt/cfd";
  /** Default share of this OS (SIMKIT_SHARE / -Dsimkit.share override it on both). */
  static final String SHARE_ROOT = java.io.File.separatorChar == '\\' ? SHARE_ROOT_WINDOWS : SHARE_ROOT_LINUX;
  /** Scene export resolution in pixels. */
  static final int IMG_W = 1600, IMG_H = 900;
  /** Maximum number of reports listed in the register column key_results. */
  static final int MAX_KEY_RESULTS = 30;
  /** Default number of processes offered by "Write batch run script". */
  static final String DEFAULT_NP = "32";
  /** Sub-folders created in every case folder. */
  static final String[] CASE_SUBDIRS = {"sim", "macros", "run", "results/csv", "results/img", "audit"};
  /** Register inbox columns (spec section 7; identical to the Case_Register sheet). */
  static final String[] REGISTER_COLS = {"timestamp", "case_id", "event", "user", "project", "config", "stage",
      "version", "parent_case", "description", "star_version", "sim_file", "cells_total", "time_step_s",
      "max_time_s_or_steps", "continua_models", "changes_vs_previous", "status", "key_results", "audit_file"};
  static final String[] AUDIT_HEADER = {"section", "object_path", "property", "value"};
  static final String[] AUDIT_SECTIONS = {"VERSION", "PARAMETERS", "CONTINUA", "REGIONS", "BOUNDARIES",
      "INTERFACES", "MESH", "SOLVERS", "STOPPING", "REPORTS", "TAGS", "NONDEFAULT", "PARAMSYNC"};
  static final String[] LINT_HEADER = {"object_type", "path", "name", "rule", "message"};
  static final String[] DIFF_HEADER = {"change", "section", "object_path", "property", "old_value", "new_value"};
  static final String[] PRECHECK_HEADER = {"level", "check", "detail"};

  /** Default-name detector (always a lint error), spec section 3. */
  static final String DEFAULT_NAME_REGEX = "^(Report|Plot|Scene|Monitor|Plane Section|Line Probe|Point Probe|"
      + "Isosurface|Threshold|User Field Function|Tag|Group|Parameter|Scalar|Vector|Body|Default|Region|Interface|"
      + "Continuum|Physics|Mesh|Automated Mesh|Volumetric Control|Surface Control|Block|Cylinder|Sphere|Cone)( [0-9]+)?$";

  /** Built-in naming rules, used when config\naming_rules.csv is missing: object_type, regex, example, message. */
  static final String[][] DEFAULT_NAMING_RULES = {
      {"geometry_part", "^(BRN|CAL|SPC|FIX|ENV|CEL)_[a-z][A-Za-z0-9]*(_[0-9]+)?$", "BRN_stator", "<ZONE>_<component>[_<n>]"},
      {"part_surface", "^(IN|OUT|OPEN|WALL|SYM|IF)_[a-z][A-Za-z0-9]*(_(up|dn|in|out|ps|ss|fr|bk|tp|bt))?$",
          "WALL_vane_ps", "<BC>_<feature>[_<side>]"},
      {"region", "^(F|S)_[a-z][A-Za-z0-9]*$", "F_air", "F_<name> or S_<name>"},
      {"interface", "^IF_[FS]_[A-Za-z0-9]+__[FS]_[A-Za-z0-9]+$", "IF_F_air__S_cone", "IF_<regionA>__<regionB> (use Auto-name)"},
      {"continuum", "^PH_[A-Z]+(_[a-z][A-Za-z0-9]*)?$", "PH_COLD", "PH_<STAGE>[_<name>]"},
      {"parameter", "^p_([a-z][A-Za-z0-9]*|(MESH|SOLV|STOP|BC|PHYS)_([A-Za-z0-9]+_)?[a-z][A-Za-z0-9]*)$",
          "p_mdotAir", "p_<quantity> in camelCase; upper-case family = managed by Parameter sync"},
      {"field_function", "^ff_[a-z][A-Za-z0-9]*$", "ff_swirlKernel", "ff_<meaning>"},
      {"coordinate_system", "^cs_[a-z][A-Za-z0-9]*$", "cs_burnerCyl", "cs_<name>"},
      {"derived_part", "^dp_(plane|line|point|iso|thr|surf)_[a-z0-9][A-Za-z0-9]*$", "dp_plane_x102mm", "dp_<type>_<location>"},
      {"report", "^r_[A-Za-z0-9]+_[a-z0-9][A-Za-z0-9]*(_(avg|mfavg|max|min|int|sum|rms))?$", "r_T_rake_avg",
          "r_<quantity>_<location>[_<stat>]"},
      {"monitor", "^m_[A-Za-z0-9]+_[a-z0-9][A-Za-z0-9]*(_(avg|mfavg|max|min|int|sum|rms))?$", "m_T_rake_avg",
          "m_ + report root (use Auto-name)"},
      {"plot", "^pl_[a-z]+_[a-z][A-Za-z0-9]*$", "pl_bal_massEnergy", "pl_<group>_<topic>"},
      {"scene", "^sc_[A-Za-z0-9]+_[a-z][A-Za-z0-9]*$", "sc_T_midSide", "sc_<field>_<view>"},
      {"tag", "^T_[a-z][A-Za-z0-9]*$", "T_yplus1", "T_<property> from the tag dictionary"},
      {"mesh_operation", "^MO_[0-9]{2}_[a-z][A-Za-z0-9]*$", "MO_10_autoMesh", "MO_<nn>_<what>"},
      {"mesh_control", "^MC_[a-z][A-Za-z0-9]*$", "MC_headSlots", "MC_<what>"},
      {"volumetric_shape", "^VC_[a-z][A-Za-z0-9]*$", "VC_shearLayer", "VC_<what>"},
      {"simulation_operation", "^SO_[0-9]{2}_[a-z][A-Za-z0-9]*$", "SO_20_reactStage", "SO_<nn>_<what>"},
      {"group", "^G[0-9]{2}_[a-z][A-Za-z0-9]*$", "G00_balances", "G<nn>_<name>"}};

  /** Tag dictionary (spec section 4). Auto-tag creates only these tags. */
  static final String[][] TAG_DICTIONARY = {
      {"T_yplus1", "wall-resolved prism stack (y+ <= 1)"}, {"T_prism", "standard prism stack"},
      {"T_noPrism", "no prism layers"}, {"T_emisMetal", "oxidised-metal emissivity group"},
      {"T_emisSpec", "specimen emissivity group"}, {"T_black", "open boundaries radiating as black body at T_ext"},
      {"T_cht", "conjugate heat transfer interfaces"}, {"T_instr", "virtual instruments"},
      {"T_handoff", "specimen surfaces for the flame-to-solid hand-off"}, {"T_refine", "GCI refinement zones"},
      {"T_export", "exported automatically by Export results"}};

  /** Built-in tag rules (used when config\tag_rules.csv is missing): object_type, name_regex, tag. */
  static final String[][] DEFAULT_TAG_RULES = {
      {"boundary", "^.*\\.OPEN_.*$", "T_black"}, {"boundary", "^.*\\.OPEN_.*$", "T_noPrism"},
      {"report", "^r_.*", "T_export"}};

  /** Built-in group rules (spec section 5): object_type, name_regex, group. First match wins.
   *  "{2}" in the group is replaced by the second underscore token of the name (first letter lower-cased). */
  static final String[][] DEFAULT_GROUP_RULES = {
      {"report;monitor", "^[rm]_(mdot|E|bal)", "G00_balances"},
      {"report;monitor", "^[rm]_(Ttc|qCal|T_rake|q_calTube)", "G01_targets"},
      {"report;monitor", "^[rm]_(yplus|Co|cells|parcels)", "G02_diagnostics"},
      {"report;monitor", "^[rm]_.*_spec", "G03_specimen"},
      {"report;monitor", ".*", "G09_other"},
      {"plot", "^pl_[a-z]+_", "G20_{2}"},
      {"scene", "^sc_[A-Za-z0-9]+_", "G30_{2}"},
      {"derived_part", "^dp_plane_", "G10_planes"},
      {"derived_part", "^dp_line_", "G11_lines"},
      {"derived_part", "^dp_point_", "G12_points"}};

  static final String[][] DEFAULT_STAGES = {{"GEO", "geometry"}, {"MESH", "mesh"}, {"COLD", "cold flow"},
      {"SPRAY", "spray"}, {"REACT", "reacting"}, {"RAD", "radiation"}, {"CHT", "conjugate heat transfer"},
      {"POST", "post-processing"}};
  static final String[][] DEFAULT_PROJECTS = {{"ISO", "ISO 2685 burner", "CF;C1;C2;C3;VV", ""}};

  static final String[] TOOL_NAMES = {"", "Initialise case", "Save new version", "New stage from this case",
      "Branch new case", "Naming check (lint)", "Auto-name", "Auto-tag", "Auto-group", "Pre-run check",
      "Setup audit + case card", "Compare with parent / previous", "Write batch run script", "Export results",
      "Run a library macro", "Open guideline / workbook", "About / SimKit version", "Parameter sync...",
      "Non-default settings report", "Snapshot baseline (owner)", "Engineering calculators"};
  static final String[] GROUP_NAMES = {"CASE", "CLEAN", "CHECK", "RUN / OUTPUT", "LIBRARY", "PARAMETERS", "TOOLS"};
  static final int[][] GROUP_TOOLS = {{1, 2, 3, 4}, {5, 6, 7, 8}, {9, 10, 11}, {12, 13}, {14, 15, 16}, {17, 18, 19},
      {20}};

  static final Pattern SIM_NAME =
      Pattern.compile("^(([A-Z]{2,5})-([A-Z0-9]{2,3})-([0-9]{3}))_([A-Z]+)_v([0-9]{2,3})\\.sim$");
  static final Pattern CASE_ID = Pattern.compile("^([A-Z]{2,5})-([A-Z0-9]{2,3})-([0-9]{3})$");

  // =====================================================================================================
  // State and entry point
  // =====================================================================================================

  Simulation sim;
  Log log;
  boolean gui;
  final Map<String, Pattern> rxCache = new HashMap<>();

  @Override
  public void execute() {
    sim = getActiveSimulation();
    log = new Log(sim);
    String batchReason = Env.headlessReason();
    gui = batchReason == null;
    log.line(VERSION + " | share " + Fs.share() + " | " + (gui ? "interactive" : "batch: " + batchReason));
    if (!gui) {
      runBatch();
      return;
    }
    for (int tool = showMenu(); tool != 0; tool = showMenu()) runTool(tool);
    log.line("SimKit closed.");
  }

  /** Batch mode: action from -Dsimkit.action or env SIMKIT_ACTION; default audit + export. */
  void runBatch() {
    String action = Env.action();
    log.line("batch action: " + (action.isEmpty() ? "(default) audit + export" : action));
    try {
      switch (action) {
        case "audit": toolAudit(); break;
        case "export": toolExport("results"); break;
        case "precheck": toolPrecheck(); break;
        case "lint": toolLint(); break;
        case "nondefault": toolNonDefault(); break;
        case "paramsync": batchParamSync(false); break;
        case "paramsync_apply": batchParamSync(true); break;
        case "snapshot_factory": batchSnapshotFactory(); break;
        default:
          if (!action.isEmpty()) log.line("unknown action '" + action + "': running the default audit + export");
          toolAudit();
          toolExport("run_end");
      }
    } catch (Cancel c) {
      log.line("stopped: " + c.getMessage());
    } catch (Throwable t) {
      log.line("ERROR: " + reason(t));
    }
  }

  /** Batch: parameter-sync report (apply=false) or apply every pending item and save the sim in place
   *  (apply=true, test use: SIMKIT_ACTION=paramsync_apply). No dialogs; deletions allowed (gui=false). */
  void batchParamSync(boolean apply) throws IOException {
    CaseInfo c = caseOrNull();
    SyncScan s = scanSync(c, true, true);
    log.line(syncSummary(s));
    logItems(s, true);
    if (!apply) return;
    int pending = s.pending();
    if (pending == 0) {
      log.line("paramsync_apply: nothing pending");
      return;
    }
    for (Item it : s.items) it.selected = it.pending();
    applySync(s, c);
    String path = sim.getSessionPath();
    sim.saveState(Fs.guard(Paths.get(path)).toString());
    log.line("paramsync_apply: " + pending + " item(s) applied, saved " + path);
    SyncScan after = scanSync(c, true, false);
    log.line("after apply: " + syncSummary(after));
    logItems(after, true);
  }

  /** Batch owner action: writes configactory_defaults_<release>.csv from the open sim (a sim holding default
   *  objects only). Requires SIMKIT_OWNER=1 in the environment (no dialog in batch). */
  void batchSnapshotFactory() throws IOException {
    if (!"1".equals(System.getenv("SIMKIT_OWNER"))) {
      log.line("snapshot_factory: refused (owner action) -- set SIMKIT_OWNER=1 to allow writing in " + Fs.kit());
      return;
    }
    SyncScan s = scanSync(null, false, true);
    String rel = starVersion();
    if (rel.startsWith("n/a")) throw new Cancel("STAR-CCM+ release not readable");
    String date = Fmt.iso();
    Map<String, Item> first = new LinkedHashMap<>();
    for (Item it : s.items) if (it.v != null && !first.containsKey(it.rowId)) first.put(it.rowId, it);
    List<String[]> rows = new ArrayList<>();
    for (Item it : first.values()) rows.add(new String[] {it.rowId, Fmt.lit(it.v), rel, date});
    for (String id : s.rowIds) if (!first.containsKey(id)) log.line("  no readable instance for " + id);
    if (rows.isEmpty()) throw new Cancel("no readable setting in this sim: nothing to write");
    Path out = Fs.kit().resolve("config").resolve("factory_defaults_" + Fs.fileSafe(rel) + ".csv");
    if (Files.isRegularFile(out)) {
      Path bak = out.resolveSibling(out.getFileName() + "." + Fmt.ts() + ".bak");
      Files.copy(out, Fs.guard(bak, true));
    }
    Csv.write(out, FACTORY_HEADER, rows, true);
    log.line("snapshot_factory: " + rows.size() + " row(s) -> " + out);
    for (String[] r : rows) log.line("  " + String.join("  ", r));
  }

  /** Runs one tool; any failure is logged, never thrown into the session. */
  void runTool(int id) {
    log.take();
    log.line("=== " + id + ". " + TOOL_NAMES[id] + " ===");
    try {
      switch (id) {
        case 1: toolInitCase(); break;
        case 2: toolSaveVersion(false); break;
        case 3: toolSaveVersion(true); break;
        case 4: toolBranch(); break;
        case 5: toolLint(); break;
        case 6: toolAutoName(); break;
        case 7: toolAutoTag(); break;
        case 8: toolAutoGroup(); break;
        case 9: toolPrecheck(); break;
        case 10: toolAudit(); break;
        case 11: toolCompare(); break;
        case 12: toolBatchScript(); break;
        case 13: toolExport("results"); break;
        case 14: toolLibrary(); break;
        case 15: toolLinks(); break;
        case 16: toolAbout(); break;
        case 17: toolParamSync(); break;
        case 18: toolNonDefault(); break;
        case 19: toolSnapshot(); break;
        case 20: toolCalc(); break;
        default: log.line("unknown tool " + id);
      }
    } catch (Cancel c) {
      log.line("cancelled" + (c.getMessage() == null ? "" : ": " + c.getMessage()));
    } catch (Throwable t) {
      log.line("ERROR: " + reason(t) + "  (tool stopped; see the lines above for what was done)");
    }
    Ui.showText(id + ". " + TOOL_NAMES[id], log.take());
  }

  // =====================================================================================================
  // Menu window
  // =====================================================================================================

  /** Shows the modal menu; returns the tool number 1..20, or 0 to close. */
  int showMenu() {
    final int[] choice = {0};
    final JDialog d = new JDialog((java.awt.Frame) null, VERSION + "  -  " + simName(), true);
    d.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
    JPanel root = new JPanel(new BorderLayout(8, 8));
    root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
    root.add(new JLabel("<html>" + Html.esc(contextLine()).replace("\n", "<br>") + "</html>"), BorderLayout.NORTH);
    JPanel cols = new JPanel(new GridLayout(0, 4, 8, 8));   // 7 groups -> two rows of four columns
    for (int g = 0; g < GROUP_NAMES.length; g++) {
      JPanel p = new JPanel(new GridLayout(0, 1, 4, 4));
      p.setBorder(BorderFactory.createTitledBorder(GROUP_NAMES[g]));
      for (int id : GROUP_TOOLS[g]) {
        JButton b = new JButton(id + ". " + TOOL_NAMES[id]);
        b.addActionListener(e -> { choice[0] = id; d.dispose(); });
        p.add(b);
      }
      JPanel holder = new JPanel(new BorderLayout());
      holder.add(p, BorderLayout.NORTH);
      cols.add(holder);
    }
    root.add(cols, BorderLayout.CENTER);
    JButton close = new JButton("Close SimKit");
    close.addActionListener(e -> { choice[0] = 0; d.dispose(); });
    JPanel south = new JPanel(new BorderLayout());
    south.add(new JLabel("Every tool also prints to the Output window."), BorderLayout.WEST);
    south.add(close, BorderLayout.EAST);
    root.add(south, BorderLayout.SOUTH);
    d.setContentPane(root);
    d.pack();
    d.setLocationRelativeTo(null);
    d.setAlwaysOnTop(true);
    d.setVisible(true);   // modal: blocks until a button is pressed or the window is closed
    return choice[0];
  }

  String simName() {
    try { return sim.getPresentationName(); } catch (Throwable t) { return "?"; }
  }

  /** Two-line description of the open sim and its case, shown at the top of the menu. */
  String contextLine() {
    String path = sessionPath();
    try {
      CaseInfo c = parseCase(path);
      return "Case " + c.caseId + "   stage " + c.stage + "   v" + Fmt.two(c.version) + "\n" + path;
    } catch (IllegalStateException e) {
      return "Not a SimKit case (" + e.getMessage() + ") -- use 1. Initialise case\n"
          + (path.isEmpty() ? "(unsaved sim)" : path) + "   |   share " + Fs.share();
    }
  }

  String sessionPath() {
    try {
      String p = sim.getSessionPath();
      return p == null ? "" : p;
    } catch (Throwable t) {
      return "";
    }
  }

  // =====================================================================================================
  // Case identity
  // =====================================================================================================

  /** Identity of a SimKit case, derived from <SHARE>\PROJECTS\<PROJ>\03_cases\<CASEID>\sim\<CASEID>_<STAGE>_v<nn>.sim */
  static final class CaseInfo {
    String proj, cfg, caseId, stage;
    int nnn, version;
    Path caseDir, simFile;

    static CaseInfo of(String proj, String cfg, int nnn, String stage, int version) {
      CaseInfo c = new CaseInfo();
      c.proj = proj;
      c.cfg = cfg;
      c.nnn = nnn;
      c.caseId = proj + "-" + cfg + "-" + String.format("%03d", nnn);
      c.caseDir = Fs.projectsDir().resolve(proj).resolve("03_cases").resolve(c.caseId);
      return c.withVersion(stage, version);
    }

    /** Same case, another stage/version (the sim file name follows). */
    CaseInfo withVersion(String newStage, int newVersion) {
      CaseInfo c = new CaseInfo();
      c.proj = proj; c.cfg = cfg; c.nnn = nnn; c.caseId = caseId; c.caseDir = caseDir;
      c.stage = newStage;
      c.version = newVersion;
      c.simFile = caseDir.resolve("sim").resolve(c.simBase() + ".sim");
      return c;
    }

    String simBase() { return caseId + "_" + stage + "_v" + Fmt.two(version); }
    Path sub(String rel) { return caseDir.resolve(rel); }
    Path projectDir() { return Fs.projectsDir().resolve(proj); }
    Path auditFile() { return sub("audit").resolve("setup_audit_" + simBase() + ".csv"); }
    Path cardFile() { return sub("audit").resolve("case_card_" + simBase() + ".html"); }
    Path identityFile() { return sub("sim").resolve("case_identity.txt"); }
  }

  /** Parses the case identity from a sim path; throws IllegalStateException with the reason. */
  static CaseInfo parseCase(String simPath) {
    if (simPath == null || simPath.trim().isEmpty()) throw new IllegalStateException("the sim has not been saved yet");
    Path f = Paths.get(simPath).toAbsolutePath().normalize();
    Matcher m = SIM_NAME.matcher(f.getFileName().toString());
    if (!m.matches()) throw new IllegalStateException("file name is not <CASEID>_<STAGE>_v<nn>.sim");
    CaseInfo c = CaseInfo.of(m.group(2), m.group(3), Integer.parseInt(m.group(4)), m.group(5),
        Integer.parseInt(m.group(6)));
    if (!Fs.same(c.simFile, f)) {
      throw new IllegalStateException("sim is not in " + c.caseDir.resolve("sim") + " (check SIMKIT_SHARE)");
    }
    c.simFile = f;
    return c;
  }

  /** Case of the open sim, or a Cancel with the reason and the remedy. */
  CaseInfo requireCase() {
    try {
      return parseCase(sessionPath());
    } catch (IllegalStateException e) {
      throw new Cancel("this tool needs a sim saved by SimKit in <SHARE>\\PROJECTS\\<PROJ>\\03_cases\\<CASEID>\\sim ("
          + e.getMessage() + "). Run 'Initialise case' first.");
    }
  }

  CaseInfo caseOrNull() {
    try { return parseCase(sessionPath()); } catch (IllegalStateException e) { return null; }
  }

  /** Reads sim\case_identity.txt (key=value lines). Missing file -> empty map. */
  static Map<String, String> readIdentity(CaseInfo c) {
    Map<String, String> kv = new LinkedHashMap<>();
    try {
      for (String line : Fs.read(c.identityFile()).split("\\r?\\n")) {
        int i = line.indexOf('=');
        if (i > 0 && !line.startsWith("#")) kv.put(line.substring(0, i).trim(), line.substring(i + 1).trim());
      }
    } catch (IOException e) {
      // no identity file yet
    }
    return kv;
  }

  static void writeIdentity(CaseInfo c, Map<String, String> kv) throws IOException {
    StringBuilder sb = new StringBuilder("# SimKit case identity -- written by " + VERSION + "; edit only description\n");
    for (Map.Entry<String, String> e : kv.entrySet()) {
      sb.append(e.getKey()).append('=').append(Fmt.oneLine(e.getValue())).append('\n');
    }
    Fs.write(c.identityFile(), sb.toString());
  }

  /** Creates the case folder (next free NNN, safe against a parallel creation) and its sub-folders. */
  CaseInfo createCaseFolders(String proj, String cfg, String stage) throws IOException {
    Path cases = Fs.projectsDir().resolve(proj).resolve("03_cases");
    Fs.mkdirs(cases);
    Fs.mkdirs(Fs.projectsDir().resolve(proj).resolve("00_admin").resolve("register_inbox"));
    for (int nnn = nextCaseNo(proj); nnn <= 999; nnn++) {
      CaseInfo c = CaseInfo.of(proj, cfg, nnn, stage, 1);
      try {
        Files.createDirectory(Fs.guard(c.caseDir));
      } catch (FileAlreadyExistsException taken) {
        continue;
      }
      for (String s : CASE_SUBDIRS) Fs.mkdirs(c.sub(s));
      log.line("created case folder " + c.caseDir);
      return c;
    }
    throw new IOException("no free case number left in " + cases);
  }

  /** Next free NNN across the whole project (all configurations), from the 03_cases folder names. */
  static int nextCaseNo(String proj) {
    Pattern p = Pattern.compile("^" + Pattern.quote(proj) + "-[A-Z0-9]{2,3}-([0-9]{3})$");
    int max = 0;
    for (Path d : Fs.list(Fs.projectsDir().resolve(proj).resolve("03_cases"), "*")) {
      Matcher m = p.matcher(d.getFileName().toString());
      if (m.matches() && Files.isDirectory(d)) max = Math.max(max, Integer.parseInt(m.group(1)));
    }
    return max + 1;
  }

  /** Next version number of a case: highest v<nn> among all stages in sim\ plus one. */
  static int nextVersion(CaseInfo c) {
    Pattern p = Pattern.compile("^" + Pattern.quote(c.caseId) + "_[A-Z]+_v([0-9]{2,3})\\.sim$");
    int max = c.version;
    for (Path f : Fs.list(c.sub("sim"), "*.sim")) {
      Matcher m = p.matcher(f.getFileName().toString());
      if (m.matches()) max = Math.max(max, Integer.parseInt(m.group(1)));
    }
    return max + 1;
  }

  // =====================================================================================================
  // CASE tools (1-4)
  // =====================================================================================================

  /** Tool 1: Initialise case. */
  void toolInitCase() throws IOException {
    CaseInfo existing = caseOrNull();
    if (existing != null) {
      throw new Cancel("this sim is already case " + existing.caseId + " -- use 'Save new version' or 'Branch new case'");
    }
    String[] in = askCaseForm("Initialise case", null);   // proj, cfg, stage, parent, description
    String preview = in[0] + "-" + in[1] + "-" + String.format("%03d", nextCaseNo(in[0]));
    if (!Ui.confirm("Initialise case", "Create case " + preview + " (number re-checked on creation)\nin "
        + Fs.projectsDir().resolve(in[0]).resolve("03_cases") + "\nand save this sim as " + preview + "_" + in[2]
        + "_v01.sim ?")) throw new Cancel(null);
    CaseInfo c = createCaseFolders(in[0], in[1], in[2]);
    setDimensionlessParam("p_simkitCaseNo", c.nnn);
    setDimensionlessParam("p_simkitVersion", 1);
    sim.saveState(Fs.guard(c.simFile).toString());
    log.line("saved " + c.simFile);
    Map<String, String> id = new LinkedHashMap<>();
    id.put("case_id", c.caseId);
    id.put("project", c.proj);
    id.put("config", c.cfg);
    id.put("parent_case", in[3]);
    id.put("description", in[4]);
    id.put("created", Fmt.iso());
    id.put("created_by", Env.user());
    putLast(id, c);
    writeIdentity(c, id);
    log.line("case identity: " + c.identityFile() + " (+ parameters p_simkitCaseNo, p_simkitVersion)");
    Audit a = writeAudit(c);
    register(c, "init", a.facts, "", "", a.file);
  }

  static void putLast(Map<String, String> id, CaseInfo c) {
    id.put("last_stage", c.stage);
    id.put("last_version", Fmt.two(c.version));
    id.put("last_saved", Fmt.iso());
  }

  /** Form: project, configuration, stage, parent case, description. 'fixed' pre-selects project/config/stage. */
  String[] askCaseForm(String title, CaseInfo fixed) {
    List<String[]> projects = projects();
    List<String> stages = stageList();
    String[] projLabels = new String[projects.size()];
    for (int i = 0; i < projLabels.length; i++) projLabels[i] = projects.get(i)[0] + "  -  " + projects.get(i)[1];
    JComboBox<String> proj = new JComboBox<>(projLabels);
    JComboBox<String> cfg = new JComboBox<>();
    JComboBox<String> stage = new JComboBox<>(stages.toArray(new String[0]));
    JTextField parent = new JTextField(fixed == null ? "" : fixed.caseId, 14);
    JTextField desc = new JTextField(40);
    Runnable fillCfg = () -> {
      cfg.removeAllItems();
      int i = Math.max(0, proj.getSelectedIndex());
      for (String code : projects.get(i)[2].split(";")) if (!code.trim().isEmpty()) cfg.addItem(code.trim());
    };
    proj.addActionListener(e -> fillCfg.run());
    if (fixed != null) {
      for (int i = 0; i < projects.size(); i++) if (projects.get(i)[0].equals(fixed.proj)) proj.setSelectedIndex(i);
      stage.setSelectedItem(fixed.stage);
    }
    fillCfg.run();
    if (fixed != null) cfg.setSelectedItem(fixed.cfg);
    JPanel p = new JPanel(new GridLayout(0, 2, 6, 4));
    p.add(new JLabel("Project")); p.add(proj);
    p.add(new JLabel("Configuration")); p.add(cfg);
    p.add(new JLabel("Stage")); p.add(stage);
    p.add(new JLabel("Parent case ID (optional)")); p.add(parent);
    p.add(new JLabel("One-line description")); p.add(desc);
    if (fixed != null) proj.setEnabled(false);
    int r = JOptionPane.showConfirmDialog(null, p, title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
    if (r != JOptionPane.OK_OPTION || cfg.getSelectedItem() == null) throw new Cancel(null);
    String par = parent.getText().trim().toUpperCase(Locale.ROOT);
    if (!par.isEmpty() && !CASE_ID.matcher(par).matches()) throw new Cancel("parent case ID '" + par + "' is not PROJ-CFG-NNN");
    String d = Fmt.oneLine(desc.getText());
    if (d.isEmpty()) throw new Cancel("a one-line description is required");
    return new String[] {projects.get(Math.max(0, proj.getSelectedIndex()))[0], (String) cfg.getSelectedItem(),
        (String) stage.getSelectedItem(), par, d};
  }

  /** Tools 2 and 3: Save new version (optional stage change) / New stage from this case (stage change). */
  void toolSaveVersion(boolean newStage) throws IOException {
    CaseInfo c = requireCase();
    List<String> stages = stageList();
    if (newStage) stages.remove(c.stage);
    String msg = newStage ? "New stage (current: " + c.stage + ")" : "Stage (keep " + c.stage + " or change)";
    String stage = Ui.choose(TOOL_NAMES[newStage ? 3 : 2], msg, stages.toArray(new String[0]), newStage ? null : c.stage);
    CaseInfo n = c.withVersion(stage, nextVersion(c));
    if (!Ui.confirm("Save new version", "Save as " + n.simFile.getFileName() + "\nin " + n.simFile.getParent()
        + " ?\n(the current file " + c.simFile.getFileName() + " stays as it is on disk)")) throw new Cancel(null);
    syncBeforeSave(n);   // spec v0.2 A.4: parameter-sync preview; silent if nothing is pending; never stops the save
    Path prevAudit = c.auditFile();
    setDimensionlessParam("p_simkitVersion", n.version);
    sim.saveState(Fs.guard(n.simFile).toString());
    log.line("saved " + n.simFile);
    Map<String, String> id = readIdentity(n);
    putLast(id, n);
    writeIdentity(n, id);
    Audit a = writeAudit(n);
    String changes;
    if (Files.isRegularFile(prevAudit)) {
      Path diff = n.sub("audit").resolve("diff_" + c.simBase() + "_to_" + n.simBase() + ".csv");
      changes = diffAudits(prevAudit, a.file, diff);
    } else {
      changes = "no audit of " + c.simBase() + " to compare with";
      log.line("diff skipped: " + prevAudit + " not found");
    }
    register(n, "save", a.facts, changes, "", a.file);
  }

  /** Tool 4: Branch new case (current case = parent). */
  void toolBranch() throws IOException {
    CaseInfo c = requireCase();
    String[] in = askCaseForm("Branch new case from " + c.caseId, c);
    String[] modes = {"Copy the sim file as last saved", "Save the current state as the new case", "Cancel"};
    int mode = Ui.option("Branch new case", "Which state goes into the new case?\n- copy: the file on disk "
        + c.simFile.getFileName() + " (unsaved changes are NOT included); you stay in " + c.caseId
        + "\n- save: the open sim is saved into the new case and you continue working in the new case", modes);
    if (mode == 2) throw new Cancel(null);
    CaseInfo n = createCaseFolders(in[0], in[1], in[2]);
    if (mode == 0) {
      Files.copy(c.simFile, Fs.guard(n.simFile), StandardCopyOption.COPY_ATTRIBUTES);
      log.line("copied " + c.simFile.getFileName() + " -> " + n.simFile);
    } else {
      setDimensionlessParam("p_simkitCaseNo", n.nnn);
      setDimensionlessParam("p_simkitVersion", 1);
      sim.saveState(Fs.guard(n.simFile).toString());
      log.line("saved the open sim as " + n.simFile + " (the session now works in " + n.caseId + ")");
    }
    Map<String, String> id = new LinkedHashMap<>();
    id.put("case_id", n.caseId);
    id.put("project", n.proj);
    id.put("config", n.cfg);
    id.put("parent_case", c.caseId);
    id.put("description", in[4]);
    id.put("created", Fmt.iso());
    id.put("created_by", Env.user());
    id.put("branched_from", c.simBase());
    putLast(id, n);
    writeIdentity(n, id);
    Facts f = mode == 1 ? writeAudit(n).facts : quickFacts();
    Path audit = mode == 1 ? n.auditFile() : null;
    register(n, "branch", f, "branched from " + c.simBase(), "", audit);
    if (mode == 0) log.line("open " + n.simFile + " to work on the new case (p_simkitCaseNo updates on its next save).");
  }

  // =====================================================================================================
  // Register inbox (spec section 7)
  // =====================================================================================================

  /** Writes one register-inbox CSV: <PROJ>\00_admin\register_inbox\<CASEID>_<event>_<ts>.csv */
  void register(CaseInfo c, String event, Facts f, String changes, String keyResults, Path auditFile) {
    try {
      Map<String, String> id = readIdentity(c);
      Map<String, String> r = new LinkedHashMap<>();
      r.put("timestamp", Fmt.iso());
      r.put("case_id", c.caseId);
      r.put("event", event);
      r.put("user", Env.user());
      r.put("project", c.proj);
      r.put("config", c.cfg);
      r.put("stage", c.stage);
      r.put("version", "v" + Fmt.two(c.version));
      r.put("parent_case", id.getOrDefault("parent_case", ""));
      r.put("description", id.getOrDefault("description", ""));
      r.put("star_version", f.star);
      r.put("sim_file", c.simFile.getFileName().toString());
      r.put("cells_total", f.cells);
      r.put("time_step_s", f.dt);
      r.put("max_time_s_or_steps", f.maxTime);
      r.put("continua_models", f.models);
      r.put("changes_vs_previous", changes);
      r.put("status", "auto");
      r.put("key_results", keyResults);
      r.put("audit_file", auditFile == null ? ""
          : c.caseDir.toAbsolutePath().normalize().relativize(auditFile.toAbsolutePath().normalize()).toString()
              .replace('\\', '/'));   // same text on Windows and Linux
      String[] row = new String[REGISTER_COLS.length];
      for (int i = 0; i < row.length; i++) row[i] = r.getOrDefault(REGISTER_COLS[i], "");
      Path out = c.projectDir().resolve("00_admin").resolve("register_inbox")
          .resolve(c.caseId + "_" + event + "_" + Fmt.ts() + ".csv");
      Csv.write(out, REGISTER_COLS, Collections.singletonList(row));
      log.line("register row (" + event + "): " + out);
    } catch (Throwable t) {
      log.line("register row (" + event + "): n/a (" + reason(t) + ")");
    }
  }

  // =====================================================================================================
  // Object inventory (what lint / auto-name / auto-tag / auto-group work on)
  // =====================================================================================================

  /** One object of the simulation tree, with its SimKit object type and a readable tree path. */
  static final class Obj {
    final String type, path;
    final ClientServerObject o;

    Obj(String type, String parentPath, Object o) {
      this.type = type;
      this.o = (ClientServerObject) o;
      this.path = parentPath + "/" + nm(o);
    }

    String name() { return nm(o); }
  }

  /** Collects every renamable object (and boundaries if asked). Each category fails independently. */
  List<Obj> collect(boolean withBoundaries) {
    List<Obj> out = new ArrayList<>();
    try { add(out, "parameter", "Tools/Parameters", sim.get(GlobalParameterManager.class).getObjects()); }   // [U10]
    catch (Throwable t) { log.na("parameters", t); }
    try { collectParts(out); } catch (Throwable t) { log.na("geometry parts", t); }
    try { collectRegions(out, withBoundaries); } catch (Throwable t) { log.na("regions", t); }
    try { add(out, "interface", "Interfaces", sim.getInterfaceManager().getObjects()); }
    catch (Throwable t) { log.na("interfaces", t); }
    try {
      for (Object c : sim.getContinuumManager().getObjects()) {
        if (c instanceof PhysicsContinuum) out.add(new Obj("continuum", "Continua", c));
      }
    } catch (Throwable t) { log.na("continua", t); }
    try {
      for (Object f : sim.getFieldFunctionManager().getObjects()) {
        if (f instanceof UserFieldFunction) out.add(new Obj("field_function", "Tools/Field Functions", f));
      }
    } catch (Throwable t) { log.na("field functions", t); }
    try {                                                                                                  // [U18]
      add(out, "coordinate_system", "Tools/Coordinate Systems/Laboratory",
          sim.getCoordinateSystemManager().getLabCoordinateSystem().getLocalCoordinateSystemManager().getObjects());
    } catch (Throwable t) { log.na("coordinate systems", t); }
    try { add(out, "derived_part", "Derived Parts", sim.getPartManager().getObjects()); }                  // [U18]
    catch (Throwable t) { log.na("derived parts", t); }
    try { add(out, "report", "Reports", sim.getReportManager().getObjects()); }
    catch (Throwable t) { log.na("reports", t); }
    try {
      for (Object m : sim.getMonitorManager().getObjects()) {
        if (m instanceof ReportMonitor) out.add(new Obj("monitor", "Monitors", m));
      }
    } catch (Throwable t) { log.na("monitors", t); }
    try {
      for (Object p : sim.getPlotManager().getObjects()) {
        if (!"ResidualPlot".equals(cls(p))) out.add(new Obj("plot", "Plots", p));
      }
    } catch (Throwable t) { log.na("plots", t); }
    try { add(out, "scene", "Scenes", sim.getSceneManager().getScenes()); }
    catch (Throwable t) { log.na("scenes", t); }
    try {
      for (Object t : tagManager().getObjects()) if (t instanceof UserTag) out.add(new Obj("tag", "Tools/Tags", t));
    } catch (Throwable t) { log.na("tags", t); }
    try { collectMeshOps(out); } catch (Throwable t) { log.na("mesh operations", t); }
    collectGroups(out);
    try { add(out, "simulation_operation", "Simulation Operations", simulationOperations()); }
    catch (Throwable t) { log.na("simulation operations", t); }
    return out;
  }

  /** [U23] Simulation operations. The manager class/package is not certain for 2402, so it is looked up by
   *  name and called through reflection (compiles in any build; empty list if not found). */
  List<Object> simulationOperations() {
    for (String cn : new String[] {"star.automation.SimDriverWorkflowManager",   // 2604 (Simulation Operations node)
        "star.common.SimulationOperationManager", "star.automation.SimulationOperationManager"}) {
      try {
        Object mgr = Reflect.tryCall(sim, "get", Class.forName(cn));
        Object objs = Reflect.tryCall(mgr, "getObjects");
        if (objs != null) return Reflect.items(objs);
      } catch (Throwable t) { /* try the next name */ }
    }
    log.line("  simulation operations: n/a (SimulationOperationManager not found)");
    return new ArrayList<>();
  }

  static void add(List<Obj> out, String type, String parentPath, Collection<?> items) {
    for (Object o : items) out.add(new Obj(type, parentPath, o));
  }

  /** Geometry parts. A simple shape part (block, cylinder, ...) counts as a volumetric shape (VC_ rule) only when a
   *  volumetric custom control uses it; a shape used as geometry (e.g. a test box) is a geometry part. [U02] */
  void collectParts(List<Obj> out) {
    Set<Object> vcParts = volumetricControlParts();
    for (Object o : sim.get(SimulationPartManager.class).getParts()) {
      boolean shape = cls(o).startsWith("Simple") && cls(o).endsWith("Part") && vcParts.contains(o);
      out.add(new Obj(shape ? "volumetric_shape" : "geometry_part", "Geometry/Parts", o));
      if (!shape && o instanceof GeometryPart) {
        try { add(out, "part_surface", "Geometry/Parts/" + nm(o), ((GeometryPart) o).getPartSurfaces()); }
        catch (Throwable t) { log.na("part surfaces of " + nm(o), t); }
      }
    }
  }

  /** Parts referenced by the volumetric custom controls of the automated mesh operations (reflection:
   *  VolumeCustomMeshControl.getGeometryObjects().getObjects(), same in 2402..2604). [U03] */
  Set<Object> volumetricControlParts() {
    Set<Object> parts = Collections.newSetFromMap(new IdentityHashMap<>());
    try {
      for (Object op : sim.get(MeshOperationManager.class).getObjects()) {
        if (!(op instanceof AutoMeshOperation)) continue;
        for (Object cc : ((AutoMeshOperation) op).getCustomMeshControls().getObjects()) {
          if (!cls(cc).startsWith("Volume")) continue;
          try {
            Object g = Reflect.invoke(cc, "getGeometryObjects");
            Object objs = g == null ? null : Reflect.invoke(g, "getObjects");
            if (objs instanceof Collection) parts.addAll((Collection<?>) objs);
          } catch (Throwable t) { log.na("geometry of volumetric control " + nm(cc), t); }
        }
      }
    } catch (Throwable t) { log.na("volumetric controls", t); }
    return parts;
  }

  void collectRegions(List<Obj> out, boolean withBoundaries) {
    for (Region r : sim.getRegionManager().getRegions()) {
      out.add(new Obj("region", "Regions", r));
      if (withBoundaries) add(out, "boundary", "Regions/" + nm(r), r.getBoundaryManager().getBoundaries());
    }
  }

  /** Mesh operations and the custom controls of automated mesh operations. [U03] */
  void collectMeshOps(List<Obj> out) {
    for (Object op : sim.get(MeshOperationManager.class).getObjects()) {
      out.add(new Obj("mesh_operation", "Geometry/Operations", op));
      if (op instanceof AutoMeshOperation) {
        try {
          add(out, "mesh_control", "Geometry/Operations/" + nm(op) + "/Custom Controls",
              ((AutoMeshOperation) op).getCustomMeshControls().getObjects());                            // [U03]
        } catch (Throwable t) { log.na("custom controls of " + nm(op), t); }
      }
    }
  }

  /** Top-level tree groups (folders) of reports, monitors, plots, scenes, derived parts. [U08] */
  void collectGroups(List<Obj> out) {
    for (String kind : new String[] {"report", "monitor", "plot", "scene", "derived_part"}) {
      try { add(out, "group", kind + " groups", groupsOf(kind).getObjects()); }
      catch (Throwable t) { log.na(kind + " groups", t); }
    }
  }

  // =====================================================================================================
  // STAR API wrappers (tagged; see TESTING.txt)
  // =====================================================================================================

  static String nm(Object o) {
    try { return ((ClientServerObject) o).getPresentationName(); } catch (Throwable t) { return String.valueOf(o); }
  }

  static String cls(Object o) { return o == null ? "none" : o.getClass().getSimpleName(); }

  /** [U01] STAR-CCM+ release, e.g. "19.02.009". Getter -> called through reflection. */
  String starVersion() {
    Object v = Reflect.tryCall(Reflect.tryCall(sim, "getStarVersion"), "getString", "ReleaseNumber");
    return v == null ? "n/a (getStarVersion().getString(\"ReleaseNumber\") not available)" : String.valueOf(v);
  }

  /** [U04] The tag manager. */
  TagManager tagManager() { return sim.get(TagManager.class); }

  /** [U05] Names of the tags on an object, or null if this build offers none of the tried getters. */
  List<String> tagsOf(Object o) {
    Object res = null;
    try { res = Reflect.tryCall(tagManager(), "getObjectTags", o); } catch (Throwable t) { /* try next */ }
    if (res == null) res = Reflect.tryCall(o, "getTags");
    if (res == null) res = Reflect.tryCall(Reflect.tryCall(o, "getTagGroup"), "getObjects");
    if (res == null) return null;
    List<String> names = new ArrayList<>();
    for (Object t : Reflect.items(res)) names.add(nm(t));
    return names;
  }

  boolean hasTag(Object o, String tag) {
    List<String> t = tagsOf(o);
    return t != null && t.contains(tag);
  }

  /** [U07] Existing user tag by name; created from the dictionary if 'create'. */
  UserTag findTag(String name, boolean create) {
    for (Object t : tagManager().getObjects()) if (t instanceof UserTag && name.equals(nm(t))) return (UserTag) t;
    if (!create) return null;
    UserTag t = tagManager().createNewUserTag(name);                                                     // [U07]
    if (!name.equals(nm(t))) t.setPresentationName(name);
    log.line("  created tag " + name);
    return t;
  }

  /** [U06] Adds one tag to an object (keeps the tags it already has). */
  void addTag(ClientServerObject o, UserTag tag) {
    tagManager().addTags(o, new NeoObjectVector(new Object[] {tag}));                                    // [U06]
  }

  /** [U08] Groups manager of the tree node that holds objects of 'kind'. */
  ClientServerObjectGroupManager groupsOf(String kind) {
    switch (kind) {
      case "report": return sim.getReportManager().getGroupsManager();
      case "monitor": return sim.getMonitorManager().getGroupsManager();
      case "plot": return sim.getPlotManager().getGroupsManager();
      case "scene": return sim.getSceneManager().getGroupsManager();
      default: return sim.getPartManager().getGroupsManager();
    }
  }

  /** [U08] Moves objects into the top-level group 'group' (created if missing). */
  void groupInto(ClientServerObjectGroupManager gm, String group, List<ClientServerObject> objs) {
    boolean exists = false;
    for (Object g : gm.getObjects()) if (group.equals(nm(g))) exists = true;
    if (!exists) {
      gm.createGroup(group);                                                                             // [U08]
      log.line("  created group " + group);
    }
    NeoObjectVector vec = new NeoObjectVector(objs.toArray());
    try {
      ((ClientServerObjectGroup) gm.getObject(group)).getGroupsManager().groupObjects(group, vec, true); // [U08] recorded form
    } catch (Throwable t) {
      gm.groupObjects(group, vec, true);                                                                 // [U08] alternative
    }
  }

  /** [U09] Plays another macro file inside this session. */
  void runMacroFile(Path p) {
    new StarScript(getActiveRootObject(), new java.io.File(p.toString())).play();                        // [U09]
  }

  /** [U10] Creates (if missing) a dimensionless scalar global parameter and sets its value. */
  void setDimensionlessParam(String name, double value) {
    try {
      GlobalParameterManager gpm = sim.get(GlobalParameterManager.class);
      ScalarGlobalParameter p = null;
      for (Object o : gpm.getObjects()) if (o instanceof ScalarGlobalParameter && name.equals(nm(o))) p = (ScalarGlobalParameter) o;
      if (p == null) {
        p = (ScalarGlobalParameter) gpm.createGlobalParameter(ScalarGlobalParameter.class, "Scalar");   // [U10]
        p.setPresentationName(name);
      }
      p.getQuantity().setValue(value);                                                                   // [U10]
    } catch (Throwable t) {
      log.na("parameter " + name, t);
    }
  }

  /** [U10] "value units" of a scalar physical quantity. */
  static String qty(ScalarPhysicalQuantity q) {
    String u = "";
    try { u = " " + nm(q.getUnits()); } catch (Throwable t) { /* no units */ }
    return Fmt.num(q.getRawValue()) + u;
  }

  /** [U19] Current value of a report (evaluates the report). */
  static String reportValue(Object r) {
    try { return Fmt.num(((Report) r).getReportMonitorValue()); }
    catch (Throwable t) { return "n/a (" + reason(t) + ")"; }
  }

  /** [U16] The two regions joined by an interface, sorted by name; null if unknown. */
  static String[] interfaceRegions(Object i) {
    try {
      BoundaryInterface bi = (BoundaryInterface) i;
      String a = nm(bi.getBoundary0().getRegion()), b = nm(bi.getBoundary1().getRegion());               // [U16]
      return a.compareTo(b) <= 0 ? new String[] {a, b} : new String[] {b, a};
    } catch (Throwable t) {
      return null;
    }
  }

  /** [U15] Auto-save state: "enabled", "disabled" or "n/a (...)". */
  String autoSaveState() {
    try {
      AutoSave as = sim.getSimulationIterator().getAutoSave();                                           // [U15]
      Object on = Reflect.tryCall(as.getStarUpdate(), "getEnabled");
      if (on == null) on = Reflect.tryCall(as.getStarUpdate(), "isEnabled");
      return on == null ? "n/a (StarUpdate enabled flag not readable)" : (Boolean.TRUE.equals(on) ? "enabled" : "disabled");
    } catch (Throwable t) {
      return "n/a (" + reason(t) + ")";
    }
  }

  /** Total cell count from a report named r_cells* (convention, G02_diagnostics); null if there is none. */
  String cellsFromReport() {
    try {
      for (Object r : sim.getReportManager().getObjects()) if (nm(r).startsWith("r_cells")) return reportValue(r);
    } catch (Throwable t) { /* fall through */ }
    return null;
  }

  /** [U21] True if a finite-volume representation (volume mesh) exists. */
  boolean volumeMeshExists() {
    try {
      for (Object rep : sim.getRepresentationManager().getObjects()) if (rep instanceof FvRepresentation) return true;
    } catch (Throwable t) { /* unknown */ }
    return false;
  }

  /** Implicit unsteady solver, or null (steady / unknown). */
  ImplicitUnsteadySolver unsteadySolver() {
    try {
      for (Object s : sim.getSolverManager().getObjects()) if (s instanceof ImplicitUnsteadySolver) return (ImplicitUnsteadySolver) s;
    } catch (Throwable t) { /* steady or unknown */ }
    return null;
  }

  /** [U12] Stopping criteria as {name, class, used, limit}. Getters through reflection. */
  List<String[]> stoppingCriteria() {
    List<String[]> out = new ArrayList<>();
    try {
      for (Object c : sim.getSolverStoppingCriterionManager().getObjects()) {
        Object used = Reflect.tryCall(c, "getIsUsed");
        Object limit = Reflect.tryCall(c, "getMaximumNumberSteps");
        if (limit == null) limit = Reflect.tryCall(c, "getMaximumNumberInnerIterations");
        if (limit == null) {
          Object q = Reflect.tryCall(c, "getMaximumTime");
          if (q instanceof ScalarPhysicalQuantity) limit = qty((ScalarPhysicalQuantity) q);
        }
        out.add(new String[] {nm(c), cls(c), used == null ? "n/a" : String.valueOf(used),
            limit == null ? "n/a" : String.valueOf(limit)});
      }
    } catch (Throwable t) {
      log.na("stopping criteria", t);
    }
    return out;
  }

  // =====================================================================================================
  // CLEAN tools (5-8)
  // =====================================================================================================

  /** Tool 5: Naming check (lint). */
  void toolLint() throws IOException {
    List<String[]> issues = lint();
    StringBuilder t = new StringBuilder();
    for (String[] i : issues) t.append(String.format("%-18s %-44s %s%n      rule: %s  -- %s%n", i[0], i[1], i[2], i[3], i[4]));
    log.line(issues.isEmpty() ? "no naming issues" : issues.size() + " naming issue(s):\n" + t);
    CaseInfo c = caseOrNull();
    if (c == null) {
      log.line("not in a case folder: lint CSV not written");
      return;
    }
    Path out = c.sub("audit").resolve("lint_" + c.simBase() + ".csv");
    Csv.write(out, LINT_HEADER, issues);
    log.line("written " + out);
  }

  /** Lint rows {object_type, path, name, rule, message}; every row is an error. */
  List<String[]> lint() {
    List<String[]> rules = config("naming_rules.csv", new String[] {"object_type", "regex", "example", "message"},
        DEFAULT_NAMING_RULES);
    Pattern def = Pattern.compile(DEFAULT_NAME_REGEX);
    for (String[] r : rules) {   // a "default_name" row in naming_rules.csv replaces the built-in detector
      if ("default_name".equalsIgnoreCase(r[0]) && rx(r[1]) != null) def = rx(r[1]);
    }
    List<String[]> issues = new ArrayList<>();
    Map<String, Integer> count = new TreeMap<>();
    for (Obj ob : collect(false)) {
      String n = ob.name();
      count.merge(ob.type, 1, Integer::sum);
      if (def.matcher(n).find()) {
        issues.add(new String[] {ob.type, ob.path, n, "default-name", "default STAR-CCM+ name: rename it"});
        continue;
      }
      String[] failed = null;
      boolean ok = false;
      for (String[] r : rules) {
        Pattern p = typeMatches(r[0], ob.type) ? rx(r[1]) : null;
        if (p == null) continue;
        if (p.matcher(n).find()) { ok = true; break; }
        if (failed == null) failed = r;
      }
      if (!ok && failed != null) {
        issues.add(new String[] {ob.type, ob.path, n, failed[1], failed[3] + " (e.g. " + failed[2] + ")"});
      }
      if (ok && "monitor".equals(ob.type)) checkMonitorRoot(ob, issues);
    }
    log.line("checked " + count);
    return issues;
  }

  /** A report monitor must be named m_ + the root of its report. [U20] */
  void checkMonitorRoot(Obj ob, List<String[]> issues) {
    try {
      String rep = nm(((ReportMonitor) ob.o).getReport());
      String want = rep.startsWith("r_") ? "m_" + rep.substring(2) : null;
      if (want != null && !want.equals(ob.name())) {
        issues.add(new String[] {ob.type, ob.path, ob.name(), "m_ + report root", "expected " + want + " (Auto-name)"});
      }
    } catch (Throwable t) { /* report not readable: skip */ }
  }

  /** Tool 6: Auto-name (preview, confirm, rename). */
  void toolAutoName() {
    List<Obj> objs = collect(false);
    Map<ClientServerObject, String> plan = new IdentityHashMap<>();
    Map<ClientServerObject, String> why = new IdentityHashMap<>();
    planMonitorNames(objs, plan, why);
    planInterfaceNames(objs, plan, why);
    planSuffixRemoval(objs, plan, why);
    planRenameRules(objs, plan, why);
    List<String> skipped = new ArrayList<>();
    List<Obj> todo = uniqueRenames(objs, plan, skipped);
    if (todo.isEmpty()) {
      log.line("nothing to rename" + (skipped.isEmpty() ? "" : "\n" + String.join("\n", skipped)));
      return;
    }
    StringBuilder pv = new StringBuilder();
    for (Obj ob : todo) pv.append(String.format("%-16s %-40s -> %-36s [%s]%n", ob.type, ob.path, plan.get(ob.o), why.get(ob.o)));
    for (String s : skipped) pv.append("SKIPPED ").append(s).append('\n');
    log.line("preview:\n" + pv);
    if (!Ui.confirmText("Auto-name", "Rename these " + todo.size() + " object(s)?", pv.toString())) throw new Cancel(null);
    int done = 0;
    for (Obj ob : todo) {
      try {
        ((star.base.neo.NamedObject) ob.o).setPresentationName(plan.get(ob.o));                                                    // [U22]
        done++;
      } catch (Throwable t) {
        log.na("rename " + ob.path, t);
      }
    }
    log.line("renamed " + done + " of " + todo.size() + " object(s)");
  }

  static String cur(Map<ClientServerObject, String> plan, Obj ob) { return plan.getOrDefault(ob.o, ob.name()); }

  static void propose(Map<ClientServerObject, String> plan, Map<ClientServerObject, String> why, Obj ob, String name, String reason) {
    if (name == null || name.isEmpty() || name.equals(cur(plan, ob))) return;
    plan.put(ob.o, name);
    why.put(ob.o, why.containsKey(ob.o) ? why.get(ob.o) + " + " + reason : reason);
  }

  /** Monitors created from a report -> m_ + the report root. [U20] */
  void planMonitorNames(List<Obj> objs, Map<ClientServerObject, String> plan, Map<ClientServerObject, String> why) {
    for (Obj ob : objs) {
      if (!"monitor".equals(ob.type)) continue;
      try {
        String rep = nm(((ReportMonitor) ob.o).getReport());
        if (rep.startsWith("r_")) propose(plan, why, ob, "m_" + rep.substring(2), "monitor of " + rep);
      } catch (Throwable t) { log.na("report of monitor " + ob.name(), t); }
    }
  }

  /** Interfaces -> IF_<regionA>__<regionB> (regions sorted by name). [U16] */
  void planInterfaceNames(List<Obj> objs, Map<ClientServerObject, String> plan, Map<ClientServerObject, String> why) {
    for (Obj ob : objs) {
      if (!"interface".equals(ob.type)) continue;
      String[] r = interfaceRegions(ob.o);
      if (r == null) log.line("  interface " + ob.name() + ": regions n/a, not renamed");
      else propose(plan, why, ob, "IF_" + r[0] + "__" + r[1], "interface regions");
    }
  }

  /** Removes trailing " 2", " 3" suffixes (uniqueness is checked later). */
  void planSuffixRemoval(List<Obj> objs, Map<ClientServerObject, String> plan, Map<ClientServerObject, String> why) {
    Pattern dup = Pattern.compile("^(.*\\S) [0-9]+$");
    Pattern def = Pattern.compile(DEFAULT_NAME_REGEX);
    for (Obj ob : objs) {
      Matcher m = dup.matcher(cur(plan, ob));
      if (m.matches() && !def.matcher(m.group(1)).find()) propose(plan, why, ob, m.group(1), "duplicate suffix");
    }
  }

  /** Optional config\rename_rules.csv: object_type, find_regex, replace (Java replaceAll syntax, $1 groups). */
  void planRenameRules(List<Obj> objs, Map<ClientServerObject, String> plan, Map<ClientServerObject, String> why) {
    if (!Files.isRegularFile(Fs.config("rename_rules.csv"))) return;
    List<String[]> rules = config("rename_rules.csv", new String[] {"object_type", "find_regex", "replace"}, null);
    for (String[] r : rules) {
      Pattern p = rx(r[1]);
      if (p == null) continue;
      for (Obj ob : objs) {
        if (!typeMatches(r[0], ob.type)) continue;
        try { propose(plan, why, ob, p.matcher(cur(plan, ob)).replaceAll(r[2]), "rename rule " + r[1]); }
        catch (Throwable t) { log.na("rename rule " + r[1], t); }
      }
    }
  }

  /** Keeps only renames whose new name is unique among objects of the same type. */
  static List<Obj> uniqueRenames(List<Obj> objs, Map<ClientServerObject, String> plan, List<String> skipped) {
    Map<String, Integer> finalNames = new HashMap<>();
    for (Obj ob : objs) finalNames.merge(ob.type + "|" + cur(plan, ob), 1, Integer::sum);
    List<Obj> todo = new ArrayList<>();
    for (Obj ob : objs) {
      String n = plan.get(ob.o);
      if (n == null) continue;
      if (finalNames.get(ob.type + "|" + n) > 1) skipped.add(ob.path + " -> " + n + " (name not unique)");
      else todo.add(ob);
    }
    return todo;
  }

  /** Tool 7: Auto-tag (adds dictionary tags from tag_rules.csv; never removes a tag). */
  void toolAutoTag() {
    List<String[]> rules = config("tag_rules.csv", new String[] {"object_type", "name_regex", "tag"}, DEFAULT_TAG_RULES);
    Map<String, String> dict = new LinkedHashMap<>();
    for (String[] d : TAG_DICTIONARY) dict.put(d[0], d[1]);
    List<Obj> objs = collect(true);
    List<Object[]> todo = new ArrayList<>();   // {Obj, tag}
    StringBuilder pv = new StringBuilder();
    for (String[] r : rules) {
      if (!dict.containsKey(r[2])) { log.line("  rule tag " + r[2] + " is not in the tag dictionary: skipped"); continue; }
      Pattern p = rx(r[1]);
      if (p == null) continue;
      for (Obj ob : objs) {
        if (!typeMatches(r[0], ob.type) || !p.matcher(ob.name()).find() || hasTag(ob.o, r[2])) continue;
        todo.add(new Object[] {ob, r[2]});
        pv.append(String.format("%-8s %-14s %s%n", "+", r[2], ob.path));
      }
    }
    if (todo.isEmpty()) { log.line("no tags to add"); return; }
    if (gui && !Ui.confirmText("Auto-tag", "Add these " + todo.size() + " tag(s)? (existing tags are kept)", pv.toString())) {
      throw new Cancel(null);
    }
    Map<String, Integer> added = new TreeMap<>();
    for (Object[] t : todo) {
      String tag = (String) t[1];
      Obj ob = (Obj) t[0];
      try {
        addTag(ob.o, findTag(tag, true));
        added.merge(tag, 1, Integer::sum);
      } catch (Throwable e) {
        log.na("tag " + tag + " on " + ob.path, e);
      }
    }
    log.line("added: " + added + "\n" + pv);
  }

  /** Tool 8: Auto-group (reports, monitors, plots, scenes, derived parts). */
  void toolAutoGroup() {
    List<String[]> rules = config("group_rules.csv", new String[] {"object_type", "name_regex", "group"}, DEFAULT_GROUP_RULES);
    Map<String, Map<String, List<ClientServerObject>>> plan = new LinkedHashMap<>();
    StringBuilder pv = new StringBuilder();
    for (Obj ob : collect(false)) {
      if (!Arrays.asList("report", "monitor", "plot", "scene", "derived_part").contains(ob.type)) continue;
      String g = groupFor(rules, ob.type, ob.name());
      if (g == null) continue;
      plan.computeIfAbsent(ob.type, k -> new TreeMap<>()).computeIfAbsent(g, k -> new ArrayList<>()).add(ob.o);
    }
    for (Map.Entry<String, Map<String, List<ClientServerObject>>> k : plan.entrySet()) {
      for (Map.Entry<String, List<ClientServerObject>> g : k.getValue().entrySet()) {
        pv.append(String.format("%-14s %-22s %d object(s)%n", k.getKey(), g.getKey(), g.getValue().size()));
      }
    }
    if (plan.isEmpty()) { log.line("nothing to group"); return; }
    if (!Ui.confirmText("Auto-group", "Move objects into these groups (created if missing)?", pv.toString())) throw new Cancel(null);
    for (Map.Entry<String, Map<String, List<ClientServerObject>>> k : plan.entrySet()) {
      for (Map.Entry<String, List<ClientServerObject>> g : k.getValue().entrySet()) {
        try {
          groupInto(groupsOf(k.getKey()), g.getKey(), g.getValue());
          log.line("  " + k.getKey() + " -> " + g.getKey() + ": " + g.getValue().size());
        } catch (Throwable t) {
          log.na(k.getKey() + " group " + g.getKey(), t);
        }
      }
    }
  }

  /** First matching group rule; "{2}" = second underscore token of the name, first letter lower-cased. */
  String groupFor(List<String[]> rules, String type, String name) {
    for (String[] r : rules) {
      Pattern p = typeMatches(r[0], type) ? rx(r[1]) : null;
      if (p == null || !p.matcher(name).find()) continue;
      String[] tok = name.split("_");
      String t2 = tok.length > 1 && !tok[1].isEmpty() ? Character.toLowerCase(tok[1].charAt(0)) + tok[1].substring(1) : "misc";
      return r[2].replace("{2}", t2);
    }
    return null;
  }

  // =====================================================================================================
  // CHECK tools (9-11)
  // =====================================================================================================

  /** Tool 9: Pre-run check -> PASS / WARN / FAIL lines + audit\precheck_*.csv */
  void toolPrecheck() throws IOException {
    CaseInfo c = caseOrNull();
    List<String[]> rows = new ArrayList<>();
    List<String[]> lintIssues = lint();
    rows.add(chk(lintIssues.isEmpty() ? "PASS" : "FAIL", "naming lint", lintIssues.size() + " issue(s)"));
    checkRequiredParameters(c, rows);
    checkStopping(rows);
    String as = autoSaveState();
    rows.add(chk("enabled".equals(as) ? "PASS" : "WARN", "auto-save configured", as));
    checkRegions(rows);
    checkMesh(rows);
    checkBalances(rows);
    checkParamSync(c, rows);
    rows.add(c == null ? chk("FAIL", "case folder", "sim is not inside a SimKit case folder")
        : chk(Files.isDirectory(c.caseDir) ? "PASS" : "FAIL", "case folder", c.caseDir.toString()));
    int fail = 0, warn = 0;
    StringBuilder sb = new StringBuilder();
    for (String[] r : rows) {
      sb.append(String.format("%-5s %-28s %s%n", r[0], r[1], r[2]));
      if ("FAIL".equals(r[0])) fail++;
      if ("WARN".equals(r[0])) warn++;
    }
    log.line("\n" + sb + "=> " + (fail > 0 ? "FAIL" : warn > 0 ? "WARN" : "PASS") + " (" + fail + " fail, " + warn + " warn)");
    if (c == null) return;
    Path out = c.sub("audit").resolve("precheck_" + c.simBase() + ".csv");
    Csv.write(out, PRECHECK_HEADER, rows);
    log.line("written " + out);
  }

  static String[] chk(String level, String check, String detail) { return new String[] {level, check, detail}; }

  /** Required parameters from _SIMKIT\config\parameters_<PROJ>.csv: column required = yes / all, or a list of
   *  configuration codes (e.g. "C3" or "C2;C3") that applies when it contains the case configuration. */
  void checkRequiredParameters(CaseInfo c, List<String[]> rows) {
    if (c == null) { rows.add(chk("WARN", "required parameters", "no case context: project unknown")); return; }
    String file = "parameters_" + c.proj + ".csv";
    if (!Files.isRegularFile(Fs.config(file))) { rows.add(chk("WARN", "required parameters", file + " not found")); return; }
    List<String> have = new ArrayList<>();
    try { for (Object p : sim.get(GlobalParameterManager.class).getObjects()) have.add(nm(p)); }
    catch (Throwable t) { rows.add(chk("WARN", "required parameters", "n/a (" + reason(t) + ")")); return; }
    List<String> missing = new ArrayList<>();
    for (String[] r : config(file, new String[] {"name", "required"}, null)) {
      if (appliesTo(r[1], c.cfg) && !have.contains(r[0])) missing.add(r[0]);
    }
    rows.add(missing.isEmpty() ? chk("PASS", "required parameters", "all present")
        : chk("FAIL", "required parameters", "missing: " + String.join(" ", missing)));
  }

  /** "yes" / "all" -> true; "no" / "" -> false; otherwise a list of configuration codes. */
  static boolean appliesTo(String required, String cfg) {
    String r = required.trim();
    if (r.equalsIgnoreCase("yes") || r.equalsIgnoreCase("all")) return true;
    for (String code : r.split("[;, ]+")) if (code.equalsIgnoreCase(cfg)) return true;
    return false;
  }

  void checkStopping(List<String[]> rows) {
    List<String[]> crit = stoppingCriteria();
    int used = 0;
    for (String[] c : crit) if (!"false".equals(c[2])) used++;
    rows.add(used > 0 ? chk("PASS", "stopping criterion", used + " in use (or use flag n/a)")
        : chk("FAIL", "stopping criterion", "none in use"));
    if (unsteadySolver() != null) {
      boolean ok = false;
      try { for (Object p : sim.get(GlobalParameterManager.class).getObjects()) if ("p_tAvgStart".equals(nm(p))) ok = true; }
      catch (Throwable t) { /* reported as missing */ }
      rows.add(chk(ok ? "PASS" : "FAIL", "unsteady: p_tAvgStart", ok ? "present" : "missing"));
    }
  }

  void checkRegions(List<String[]> rows) {
    try {
      List<String> none = new ArrayList<>();
      int n = 0;
      for (Region r : sim.getRegionManager().getRegions()) {
        n++;
        if (r.getPhysicsContinuum() == null) none.add(nm(r));
      }
      rows.add(n == 0 ? chk("FAIL", "regions have a continuum", "no regions")
          : none.isEmpty() ? chk("PASS", "regions have a continuum", n + " region(s)")
          : chk("FAIL", "regions have a continuum", "without continuum: " + String.join(" ", none)));
    } catch (Throwable t) {
      rows.add(chk("WARN", "regions have a continuum", "n/a (" + reason(t) + ")"));
    }
  }

  void checkMesh(List<String[]> rows) {
    String cells = cellsFromReport();
    double n = Fmt.parse(cells);
    if (!Double.isNaN(n)) {
      rows.add(chk(n > 0 ? "PASS" : "FAIL", "mesh exists", "cells (r_cells report) = " + cells));
    } else if (volumeMeshExists()) {
      rows.add(chk("WARN", "mesh exists", "volume mesh present; add an r_cells_total report for the cell count"));
    } else {
      rows.add(chk("FAIL", "mesh exists", "no volume mesh representation found (or n/a)"));
    }
  }

  /** Balance reports = reports matching the G00 group rules. */
  void checkBalances(List<String[]> rows) {
    List<String[]> rules = config("group_rules.csv", new String[] {"object_type", "name_regex", "group"}, DEFAULT_GROUP_RULES);
    int n = 0;
    try {
      for (Object r : sim.getReportManager().getObjects()) {
        String g = groupFor(rules, "report", nm(r));
        if (g != null && g.startsWith("G00")) n++;
      }
    } catch (Throwable t) { log.na("reports", t); }
    rows.add(chk(n > 0 ? "PASS" : "FAIL", "balance reports (G00)", n + " report(s)"));
  }

  // ---------------------------------------------------------------- audit

  /** Values that also go into the register row. */
  static final class Facts {
    String star = "n/a", cells = "n/a", dt = "n/a", maxTime = "n/a", models = "n/a";
  }

  /** Setup audit rows (section, object_path, property, value) + facts + the file once written. */
  static final class Audit {
    final List<String[]> rows = new ArrayList<>();
    final Facts facts = new Facts();
    Path file;

    void add(String sec, String path, String prop, Object val) {
      rows.add(new String[] {sec, path, prop, String.valueOf(val)});
    }
  }

  /** Tool 10: Setup audit + case card. */
  void toolAudit() throws IOException {
    writeAudit(requireCase());
  }

  /** Builds and writes audit\setup_audit_<simBase>.csv and audit\case_card_<simBase>.html. */
  Audit writeAudit(CaseInfo c) throws IOException {
    Audit a = new Audit();
    auditVersion(a, c);
    auditSection(a, "PARAMETERS", () -> auditParameters(a));
    auditSection(a, "CONTINUA", () -> auditContinua(a));
    auditSection(a, "REGIONS", () -> auditRegions(a));
    auditSection(a, "INTERFACES", () -> auditInterfaces(a));
    auditSection(a, "MESH", () -> auditMesh(a));
    auditSection(a, "SOLVERS", () -> auditSolvers(a));
    auditSection(a, "STOPPING", () -> auditStopping(a));
    auditSection(a, "REPORTS", () -> auditReports(a));
    auditSection(a, "TAGS", () -> auditTags(a));
    auditSection(a, "PARAMSYNC", () -> auditSync(a, c));   // writes NONDEFAULT + PARAMSYNC (report only)
    a.file = Fs.guard(c.auditFile());
    Csv.write(a.file, AUDIT_HEADER, a.rows);
    Fs.write(c.cardFile(), caseCard(c, a, readIdentity(c)));
    log.line("audit: " + a.rows.size() + " rows -> " + a.file + "\ncase card -> " + c.cardFile());
    return a;
  }

  /** Facts only (no file), for register rows of events that do not write an audit. */
  Facts quickFacts() {
    Audit a = new Audit();
    a.facts.star = starVersion();
    auditSection(a, "CONTINUA", () -> auditContinua(a));
    auditSection(a, "MESH", () -> auditMesh(a));
    auditSection(a, "SOLVERS", () -> auditSolvers(a));
    auditSection(a, "STOPPING", () -> auditStopping(a));
    return a.facts;
  }

  /** Runs one audit section; a failure becomes one "n/a" row and the audit continues. */
  void auditSection(Audit a, String sec, Runnable r) {
    try { r.run(); } catch (Throwable t) { a.add(sec, "-", "error", "n/a (" + reason(t) + ")"); }
  }

  void auditVersion(Audit a, CaseInfo c) {
    a.facts.star = starVersion();
    a.add("VERSION", "STAR-CCM+", "release", a.facts.star);
    a.add("VERSION", "SimKit", "version", VERSION);
    a.add("VERSION", "user", "name", Env.user());
    a.add("VERSION", "date", "audit_time", Fmt.iso());
    a.add("VERSION", "sim", "path", sessionPath());
    a.add("VERSION", "case", "case_id", c.caseId);
  }

  void auditParameters(Audit a) {
    for (Object p : sim.get(GlobalParameterManager.class).getObjects()) {
      if (p instanceof ScalarGlobalParameter) {
        ScalarPhysicalQuantity q = ((ScalarGlobalParameter) p).getQuantity();                            // [U10]
        a.add("PARAMETERS", nm(p), "value", Fmt.num(q.getRawValue()));
        String u = "";
        try { u = nm(q.getUnits()); } catch (Throwable t) { u = "n/a"; }
        a.add("PARAMETERS", nm(p), "units", u);
        Object def = Reflect.tryCall(q, "getDefinition");
        if (def != null) a.add("PARAMETERS", nm(p), "definition", def);
      } else {
        a.add("PARAMETERS", nm(p), "type", cls(p));
      }
    }
  }

  void auditContinua(Audit a) {
    List<String> facts = new ArrayList<>();
    for (Object c : sim.getContinuumManager().getObjects()) {
      if (!(c instanceof PhysicsContinuum)) continue;
      List<String> models = new ArrayList<>();
      for (Object m : ((PhysicsContinuum) c).getModelManager().getObjects()) models.add(nm(m));          // [U17]
      Collections.sort(models);
      a.add("CONTINUA", nm(c), "models", String.join("; ", models));
      facts.add(nm(c) + ": " + String.join("; ", models));
    }
    a.facts.models = String.join(" | ", facts);
  }

  void auditRegions(Audit a) {
    for (Region r : sim.getRegionManager().getRegions()) {
      String type = "n/a";
      try { type = cls(r.getRegionType()); } catch (Throwable t) { /* n/a */ }                          // [U17]
      Collection<Boundary> bs = r.getBoundaryManager().getBoundaries();
      a.add("REGIONS", nm(r), "type", type);
      a.add("REGIONS", nm(r), "continuum", r.getPhysicsContinuum() == null ? "none" : nm(r.getPhysicsContinuum()));
      a.add("REGIONS", nm(r), "boundary_count", bs.size());
      for (Boundary b : bs) {
        String bt;
        try { bt = nm(b.getBoundaryType()); } catch (Throwable t) { bt = "n/a"; }                       // [U17]
        a.add("BOUNDARIES", nm(r) + "/" + nm(b), "type", bt);
        a.add("BOUNDARIES", nm(r) + "/" + nm(b), "tags", tagList(b));
      }
    }
  }

  String tagList(Object o) {
    List<String> t = tagsOf(o);
    if (t == null) return "n/a (tag getter)";
    Collections.sort(t);
    return String.join(" ", t);
  }

  void auditInterfaces(Audit a) {
    for (Object i : sim.getInterfaceManager().getObjects()) {
      String[] r = interfaceRegions(i);
      a.add("INTERFACES", nm(i), "type", cls(i));
      a.add("INTERFACES", nm(i), "regions", r == null ? "n/a" : r[0] + " | " + r[1]);
      a.add("INTERFACES", nm(i), "tags", tagList(i));
    }
  }

  void auditMesh(Audit a) {
    try {
      for (Object op : sim.get(MeshOperationManager.class).getObjects()) a.add("MESH", nm(op), "operation", cls(op));
    } catch (Throwable t) { a.add("MESH", "operations", "error", "n/a (" + reason(t) + ")"); }
    String cells = cellsFromReport();
    a.facts.cells = cells == null ? "n/a (no r_cells report)" : cells;
    a.add("MESH", "all regions", "cells_total", a.facts.cells);
    a.add("MESH", "volume mesh", "exists", volumeMeshExists() ? "yes" : "no or n/a");
    a.add("MESH", "per region", "cells", "n/a (no certain 2402 API; add per-region r_cells_<region> reports)");
  }

  void auditSolvers(Audit a) {
    for (Object s : sim.getSolverManager().getObjects()) a.add("SOLVERS", nm(s), "class", cls(s));
    ImplicitUnsteadySolver u = unsteadySolver();
    if (u != null) {
      try {
        ScalarPhysicalQuantity dt = u.getTimeStep();                                                     // [U11]
        a.facts.dt = qty(dt);
        a.add("SOLVERS", nm(u), "time_step", a.facts.dt);
      } catch (Throwable t) {
        a.add("SOLVERS", nm(u), "time_step", "n/a (" + reason(t) + ")");
      }
    } else {
      a.facts.dt = "steady";
    }
    for (String[] c : stoppingCriteria()) {
      if (c[1].contains("InnerIteration")) a.add("SOLVERS", c[0], "inner_iterations", c[3]);
    }
  }

  void auditStopping(Audit a) {
    String steps = null, time = null;
    for (String[] c : stoppingCriteria()) {
      a.add("STOPPING", c[0], "class", c[1]);
      a.add("STOPPING", c[0], "used", c[2]);
      a.add("STOPPING", c[0], "limit", c[3]);
      if (!"false".equals(c[2]) && c[1].contains("PhysicalTime")) time = c[3];
      if (!"false".equals(c[2]) && c[1].startsWith("Step")) steps = c[3] + " steps";
    }
    a.facts.maxTime = time != null ? time : steps != null ? steps : "n/a";
  }

  void auditReports(Audit a) {
    for (Object r : sim.getReportManager().getObjects()) a.add("REPORTS", nm(r), "type", cls(r));
  }

  void auditTags(Audit a) {
    Map<String, Integer> count = new TreeMap<>();
    for (Object t : tagManager().getObjects()) count.put(nm(t), 0);
    boolean readable = false;
    for (Obj ob : collect(true)) {
      List<String> tags = tagsOf(ob.o);
      if (tags == null) continue;
      readable = true;
      for (String t : tags) count.merge(t, 1, Integer::sum);
    }
    for (Map.Entry<String, Integer> e : count.entrySet()) {
      a.add("TAGS", e.getKey(), "object_count", readable ? String.valueOf(e.getValue()) : "n/a (tag getter)");
    }
  }

  /** One-page HTML summary of the audit, case identity at the top. */
  static String caseCard(CaseInfo c, Audit a, Map<String, String> id) {
    StringBuilder h = new StringBuilder("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>")
        .append(Html.esc(c.simBase())).append("</title><style>")
        .append("body{font-family:Segoe UI,Arial,sans-serif;font-size:12px;margin:16px;color:#222}")
        .append("h1{font-size:18px;margin:0 0 6px}h2{font-size:13px;margin:14px 0 4px;border-bottom:1px solid #999}")
        .append("table{border-collapse:collapse;width:100%}td,th{border:1px solid #ccc;padding:2px 6px;")
        .append("text-align:left;vertical-align:top}th{background:#eee}.k{width:180px;font-weight:bold}")
        .append("</style></head><body>\n<h1>").append(Html.esc(c.caseId)).append(" &mdash; ")
        .append(Html.esc(c.stage)).append(" v").append(Fmt.two(c.version)).append("</h1>\n<table>");
    String[][] ident = {{"Case ID", c.caseId}, {"Project / config", c.proj + " / " + c.cfg},
        {"Stage / version", c.stage + " / v" + Fmt.two(c.version)}, {"Parent case", id.getOrDefault("parent_case", "")},
        {"Description", id.getOrDefault("description", "")}, {"Sim file", c.simFile.toString()},
        {"Created", id.getOrDefault("created", "") + " " + id.getOrDefault("created_by", "")},
        {"Written", Fmt.iso() + " by " + Env.user() + " with " + VERSION}};
    for (String[] r : ident) {
      h.append("<tr><td class=\"k\">").append(Html.esc(r[0])).append("</td><td>").append(Html.esc(r[1])).append("</td></tr>");
    }
    h.append("</table>\n");
    for (String sec : AUDIT_SECTIONS) {
      h.append("<h2>").append(sec).append("</h2><table><tr><th>object</th><th>property</th><th>value</th></tr>");
      for (String[] r : a.rows) {
        if (!r[0].equals(sec)) continue;
        h.append("<tr><td>").append(Html.esc(r[1])).append("</td><td>").append(Html.esc(r[2]))
            .append("</td><td>").append(Html.esc(r[3])).append("</td></tr>");
      }
      h.append("</table>\n");
    }
    return h.append("</body></html>\n").toString();
  }

  // ---------------------------------------------------------------- compare

  /** Tool 11: Compare with parent / previous version. */
  void toolCompare() throws IOException {
    CaseInfo c = requireCase();
    Audit now = writeAudit(c);
    String[] opts = {"Previous version", "Parent case", "Choose an audit file...", "Cancel"};
    int o = Ui.option("Compare", "Compare the current audit of " + c.simBase() + " with:", opts);
    Path other;
    if (o == 0) other = latestAudit(c.sub("audit"), c.caseId, c.version);
    else if (o == 1) other = parentAudit(c);
    else if (o == 2) other = Ui.chooseFile(c.sub("audit"));
    else throw new Cancel(null);
    if (other == null || !Files.isRegularFile(other)) throw new Cancel("no audit file found to compare with");
    String otherBase = other.getFileName().toString().replaceFirst("^setup_audit_", "").replaceFirst("\\.csv$", "");
    Path out = c.sub("audit").resolve("diff_" + Fs.fileSafe(otherBase) + "_to_" + c.simBase() + ".csv");
    String summary = diffAudits(other, now.file, out);
    log.line("summary: " + summary);
  }

  /** Highest-version setup audit of a case below 'belowVersion' (Integer.MAX_VALUE = any). */
  static Path latestAudit(Path auditDir, String caseId, int belowVersion) {
    Pattern p = Pattern.compile("^setup_audit_" + Pattern.quote(caseId) + "_[A-Z]+_v([0-9]{2,3})\\.csv$");
    Path best = null;
    int bestV = -1;
    for (Path f : Fs.list(auditDir, "setup_audit_*.csv")) {
      Matcher m = p.matcher(f.getFileName().toString());
      if (!m.matches()) continue;
      int v = Integer.parseInt(m.group(1));
      if (v < belowVersion && v > bestV) { best = f; bestV = v; }
    }
    return best;
  }

  Path parentAudit(CaseInfo c) {
    String parent = readIdentity(c).getOrDefault("parent_case", "");
    if (parent.isEmpty()) throw new Cancel("no parent_case in " + c.identityFile());
    Path dir = Fs.projectsDir().resolve(parent.split("-")[0]).resolve("03_cases").resolve(parent).resolve("audit");
    return latestAudit(dir, parent, Integer.MAX_VALUE);
  }

  /** Diffs two audit CSVs (VERSION section and p_simkit* ignored); writes the diff CSV; returns a summary. */
  String diffAudits(Path oldF, Path newF, Path out) throws IOException {
    Map<String, String[]> a = auditMap(oldF), b = auditMap(newF);
    List<String[]> rows = new ArrayList<>();
    Map<String, int[]> perSec = new TreeMap<>();
    List<String> examples = new ArrayList<>();
    for (Map.Entry<String, String[]> e : b.entrySet()) {
      String[] o = a.get(e.getKey()), n = e.getValue();
      if (o == null) diffRow(rows, perSec, examples, "added", n, "", n[3]);
      else if (!o[3].equals(n[3])) diffRow(rows, perSec, examples, "changed", n, o[3], n[3]);
    }
    for (Map.Entry<String, String[]> e : a.entrySet()) {
      if (!b.containsKey(e.getKey())) diffRow(rows, perSec, examples, "removed", e.getValue(), e.getValue()[3], "");
    }
    Csv.write(out, DIFF_HEADER, rows);
    int[] tot = new int[3];
    List<String> secs = new ArrayList<>();
    for (Map.Entry<String, int[]> e : perSec.entrySet()) {
      int[] v = e.getValue();
      for (int i = 0; i < 3; i++) tot[i] += v[i];
      secs.add(e.getKey() + " +" + v[0] + " -" + v[1] + " ~" + v[2]);
    }
    String summary = rows.isEmpty() ? "no setup changes" : "+" + tot[0] + " -" + tot[1] + " ~" + tot[2] + " ["
        + String.join("; ", secs) + "] e.g. " + String.join("; ", examples);
    log.line("diff: " + rows.size() + " line(s) -> " + out);
    return Fmt.cut(summary, 400);
  }

  static void diffRow(List<String[]> rows, Map<String, int[]> perSec, List<String> examples, String change,
      String[] r, String oldV, String newV) {
    rows.add(new String[] {change, r[0], r[1], r[2], oldV, newV});
    perSec.computeIfAbsent(r[0], k -> new int[3])["added".equals(change) ? 0 : "removed".equals(change) ? 1 : 2]++;
    if (examples.size() < 3) examples.add(r[1] + "." + r[2] + " " + Fmt.cut(oldV, 30) + "->" + Fmt.cut(newV, 30));
  }

  /** Audit CSV as key "section|path|property[#n]" -> row; VERSION rows and p_simkit* parameters are skipped. */
  static Map<String, String[]> auditMap(Path f) throws IOException {
    Map<String, String[]> m = new LinkedHashMap<>();
    List<String[]> rows = Csv.parse(Fs.read(f));
    for (int i = 1; i < rows.size(); i++) {
      String[] r = Arrays.copyOf(rows.get(i), 4);
      for (int k = 0; k < 4; k++) if (r[k] == null) r[k] = "";
      if ("VERSION".equals(r[0]) || r[1].startsWith("p_simkit")) continue;
      String key = r[0] + "|" + r[1] + "|" + r[2];
      String k2 = key;
      for (int n = 2; m.containsKey(k2); n++) k2 = key + "#" + n;
      m.put(k2, r);
    }
    return m;
  }

  // =====================================================================================================
  // RUN / OUTPUT tools (12-13)
  // =====================================================================================================

  /** Tool 12: Write batch run script (.bat and .sh in run\). */
  void toolBatchScript() throws IOException {
    CaseInfo c = requireCase();
    JTextField np = new JTextField(DEFAULT_NP, 8), chain = new JTextField("run", 30), flags = new JTextField("-power", 30);
    JPanel p = new JPanel(new GridLayout(0, 2, 6, 4));
    p.add(new JLabel("Number of processes (-np)")); p.add(np);
    p.add(new JLabel("Macro chain for -batch (e.g. run  or  run," + Fs.kit().resolve("SimKit.java") + ")")); p.add(chain);
    p.add(new JLabel("Power-session / licence flags")); p.add(flags);
    if (JOptionPane.showConfirmDialog(null, p, "Write batch run script", JOptionPane.OK_CANCEL_OPTION,
        JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) throw new Cancel(null);
    String n = np.getText().trim();
    if (!n.matches("[0-9]{1,6}")) throw new Cancel("number of processes must be an integer");
    String ch = Fmt.oneLine(chain.getText()).replace(" ", ""), fl = Fmt.oneLine(flags.getText());
    if (ch.isEmpty()) ch = "run";
    String simName = c.simFile.getFileName().toString(), id = c.caseId;
    String bat = String.join("\r\n",
        "@echo off",
        "rem Batch run of " + c.simBase() + " -- written " + Fmt.iso() + " by " + VERSION + ". Edit flags as needed.",
        "setlocal",
        "if not defined SIMKIT_SHARE set SIMKIT_SHARE=" + (Fs.WINDOWS ? Fs.share().toString() : SHARE_ROOT_WINDOWS),
        "cd /d \"%~dp0\"",
        "for /f %%i in ('powershell -NoProfile -Command \"Get-Date -Format yyyyMMdd-HHmmss\"') do set TS=%%i",
        "set SIM=..\\sim\\" + simName,
        "starccm+ -batch " + ch + " -batch-report -np " + n + " " + fl + " \"%SIM%\" > \"log_" + id + "_%TS%.txt\" 2>&1",
        "endlocal", "");
    String sh = String.join("\n",
        "#!/bin/bash",
        "# Batch run of " + c.simBase() + " -- written " + Fmt.iso() + " by " + VERSION + ". Edit flags as needed.",
        Fs.WINDOWS ? "# EDIT: SIMKIT_SHARE = the Linux mount of " + Fs.share() : "# share as seen from this Linux host",
        "export SIMKIT_SHARE=\"${SIMKIT_SHARE:-" + (Fs.WINDOWS ? SHARE_ROOT_LINUX : Fs.share().toString()) + "}\"",
        "cd \"$(dirname \"$0\")\" || exit 1",
        "TS=$(date +%Y%m%d-%H%M%S)",
        "SIM=../sim/" + simName,
        "starccm+ -batch " + ch.replace('\\', '/') + " -batch-report -np " + n + " " + fl + " \"$SIM\" > \"log_" + id
            + "_${TS}.txt\" 2>&1", "");
    Fs.write(c.sub("run").resolve("run_" + id + ".bat"), bat);
    Fs.write(c.sub("run").resolve("run_" + id + ".sh"), sh);
    Fs.makeExecutable(c.sub("run").resolve("run_" + id + ".sh"));
    log.line("written " + c.sub("run").resolve("run_" + id + ".bat") + "\nwritten " + c.sub("run").resolve("run_" + id + ".sh")
        + "\nthe scripts run " + simName + "; rewrite them after 'Save new version'.");
  }

  /** Tool 13: Export results (plots CSV, scenes PNG, report snapshot, register row). */
  void toolExport(String event) throws IOException {
    CaseInfo c = requireCase();
    Path csv = c.sub("results/csv"), img = c.sub("results/img");
    Fs.mkdirs(csv);
    Fs.mkdirs(img);
    boolean onlyTagged = anyTagged("T_export");
    if (onlyTagged) log.line("objects tagged T_export found: only tagged plots/scenes are exported");
    exportPlots(c, csv, onlyTagged);
    exportScenes(c, img, onlyTagged);
    String key = exportReports(c, csv);
    Path audit = Files.isRegularFile(c.auditFile()) ? c.auditFile() : null;
    register(c, event, quickFacts(), "", key, audit);
  }

  boolean anyTagged(String tag) {
    try {
      for (Object p : sim.getPlotManager().getObjects()) if (hasTag(p, tag)) return true;
      for (Object s : sim.getSceneManager().getScenes()) if (hasTag(s, tag)) return true;
    } catch (Throwable t) { /* treat as none */ }
    return false;
  }

  /** Monitor plots -> results\csv\<CASEID>_<plot>.csv  [U13] */
  void exportPlots(CaseInfo c, Path dir, boolean onlyTagged) {
    int n = 0;
    try {
      for (Object p : sim.getPlotManager().getObjects()) {
        if (!(p instanceof MonitorPlot) || (onlyTagged && !hasTag(p, "T_export"))) continue;
        try {
          Path f = Fs.guard(dir.resolve(c.caseId + "_" + Fs.fileSafe(nm(p)) + ".csv"));
          ((MonitorPlot) p).export(f.toString(), ",");                                                   // [U13]
          n++;
        } catch (Throwable t) { log.na("plot " + nm(p), t); }
      }
    } catch (Throwable t) { log.na("plots", t); }
    log.line("plots exported: " + n + " -> " + dir);
  }

  /** Scenes -> results\img\<CASEID>_<scene>.png at IMG_W x IMG_H  [U14] */
  void exportScenes(CaseInfo c, Path dir, boolean onlyTagged) {
    int n = 0;
    try {
      for (Scene s : sim.getSceneManager().getScenes()) {
        if (onlyTagged && !hasTag(s, "T_export")) continue;
        try {
          Path f = Fs.guard(dir.resolve(c.caseId + "_" + Fs.fileSafe(nm(s)) + ".png"));
          s.printAndWait(f.toString(), 1, IMG_W, IMG_H);   // [U14] (recorder may write the 6-argument form ..., true, false)
          n++;
        } catch (Throwable t) { log.na("scene " + nm(s), t); }
      }
    } catch (Throwable t) { log.na("scenes", t); }
    log.line("scenes exported: " + n + " -> " + dir);
  }

  /** Snapshot of all report values; returns key results (tagged T_export or G01 rule) as "name=value; ...". */
  String exportReports(CaseInfo c, Path dir) throws IOException {
    List<String[]> rules = config("group_rules.csv", new String[] {"object_type", "name_regex", "group"}, DEFAULT_GROUP_RULES);
    List<String[]> rows = new ArrayList<>();
    List<String> key = new ArrayList<>();
    try {
      for (Object r : sim.getReportManager().getObjects()) {
        String v = reportValue(r), g = groupFor(rules, "report", nm(r));
        boolean isKey = hasTag(r, "T_export") || (g != null && g.startsWith("G01"));
        rows.add(new String[] {nm(r), cls(r), v, isKey ? "yes" : "no"});
        if (isKey && key.size() < MAX_KEY_RESULTS) key.add(nm(r) + "=" + v);
      }
    } catch (Throwable t) { log.na("reports", t); }
    Path f = dir.resolve(c.caseId + "_reports_" + Fmt.ts() + ".csv");
    Csv.write(f, new String[] {"report", "type", "value", "key_result"}, rows);
    log.line("report snapshot: " + rows.size() + " report(s) -> " + f);
    return String.join("; ", key);
  }

  // =====================================================================================================
  // LIBRARY tools (14-16)
  // =====================================================================================================

  /** Tool 14: Run a library macro (lib\generic, lib\projects\<PROJ>, case macros\). */
  void toolLibrary() {
    CaseInfo c = caseOrNull();
    List<String[]> entries = new ArrayList<>();   // {label, path}
    addMacros(entries, "generic", Fs.kit().resolve("lib").resolve("generic"));
    if (c != null) {
      addMacros(entries, c.proj, Fs.kit().resolve("lib").resolve("projects").resolve(c.proj));
      addMacros(entries, "case", c.sub("macros"));
    } else {
      for (Path d : Fs.list(Fs.kit().resolve("lib").resolve("projects"), "*")) {
        if (Files.isDirectory(d)) addMacros(entries, d.getFileName().toString(), d);
      }
    }
    if (entries.isEmpty()) throw new Cancel("no *.java macros found under " + Fs.kit().resolve("lib"));
    String[] labels = new String[entries.size()];
    for (int i = 0; i < labels.length; i++) labels[i] = entries.get(i)[0];
    int i = Ui.pick("Run a library macro", "Select the macro to run:", labels, false)[0];
    Path p = Paths.get(entries.get(i)[1]);
    log.line("running " + p);
    runMacroFile(p);
    log.line("finished " + p.getFileName());
  }

  static void addMacros(List<String[]> entries, String source, Path dir) {
    for (Path f : Fs.list(dir, "*.java")) {
      String name = f.getFileName().toString().replaceFirst("\\.java$", "");
      entries.add(new String[] {String.format("[%s] %-28s %s", source, name, firstComment(f)), f.toString()});
    }
  }

  /** Description = first comment line of a macro file. */
  static String firstComment(Path f) {
    try {
      for (String line : Fs.read(f).split("\\r?\\n")) {
        String t = line.trim();
        if (t.startsWith("//") || t.startsWith("/*") || t.startsWith("*")) {
          t = t.replaceAll("^(//+|/\\*+|\\*+)", "").replaceAll("\\*/$", "").trim();
          if (!t.isEmpty()) return t;
        }
      }
    } catch (IOException e) { /* unreadable */ }
    return "";
  }

  /** Tool 15: Open guideline / workbook (URLs from config\links.csv). */
  void toolLinks() {
    List<String[]> links = config("links.csv", new String[] {"label", "url"}, null);
    List<String[]> ok = new ArrayList<>();
    for (String[] l : links) if (l[1].matches("(?i)^(https?|file):.*") && !l[1].contains("REPLACE")) ok.add(l);
    if (ok.isEmpty()) throw new Cancel("no usable http(s)/file links in " + Fs.config("links.csv")
        + " (placeholders containing REPLACE are skipped)");
    String[] labels = new String[ok.size()];
    for (int i = 0; i < labels.length; i++) labels[i] = ok.get(i)[0] + "   " + ok.get(i)[1];
    for (int i : Ui.pick("Open guideline / workbook", "Links to open in the default browser:", labels, true)) {
      try {
        Ui.browse(ok.get(i)[1]);
        log.line("opened " + ok.get(i)[0]);
      } catch (Throwable t) {
        log.na("open " + ok.get(i)[1], t);
      }
    }
  }

  /** Tool 16: About / SimKit version. */
  void toolAbout() {
    String vfile;
    try { vfile = Fs.read(Fs.kit().resolve("VERSION.txt")).trim(); } catch (IOException e) { vfile = "n/a (VERSION.txt not found)"; }
    StringBuilder sb = new StringBuilder();
    sb.append(VERSION).append(" (this macro)\nVERSION.txt: ").append(vfile)
        .append("\nshare: ").append(Fs.share()).append(System.getenv("SIMKIT_SHARE") != null ? " (from SIMKIT_SHARE)" : " (default)")
        .append("\nSTAR-CCM+: ").append(starVersion()).append("\nJava: ").append(System.getProperty("java.version"))
        .append("\nuser: ").append(Env.user()).append("\n").append(contextLine()).append("\n\nconfig files:");
    for (String f : new String[] {"naming_rules.csv", "tag_rules.csv", "group_rules.csv", "stages.csv", "projects.csv",
        "links.csv", "rename_rules.csv", "settings_catalogue.csv"}) {
      sb.append("\n  ").append(Files.isRegularFile(Fs.config(f)) ? "found    " : "missing  ").append(f);
    }
    List<Path> fd = Fs.list(Fs.kit().resolve("config"), "factory_defaults_*.csv");
    sb.append("\n  factory defaults: ").append(fd.isEmpty() ? "none (use 19. Snapshot baseline in a blank sim)" : "");
    for (Path f : fd) sb.append(f.getFileName()).append("  ");
    CaseInfo c = caseOrNull();
    Path tb = null;
    try { tb = c == null ? null : templateBaselineFile(c); } catch (Throwable t) { /* projects.csv unusable */ }
    if (tb != null) sb.append("\n  template baseline: ").append(Files.isRegularFile(tb) ? "found    " : "missing  ").append(tb);
    log.line(sb.toString());
  }

  // =====================================================================================================
  // PARAMETERS tools (17-19): parameter synchronisation (SIMKIT_SPEC_v02.md section A)
  // =====================================================================================================
  //
  // Data flow: settings_catalogue.csv row x instance -> Item (current SI value, definition, baseline) ->
  // PSync.classify -> state S0..S5 / NP / NB / NA -> preview -> applySync. All STAR access of this section goes
  // through reflection (tags U35-U37, U41-U44) except the parameter setters (U38-U40), which are recorder-style.

  static final String[] CATALOGUE_COLS = {"id", "family", "source", "path", "prop", "parameterisable", "unit",
      "description"};
  static final String[] PARAMSYNC_HEADER = {"timestamp", "row_id", "family", "object", "setting", "state",
      "current_si", "baseline_si", "baseline_source", "definition", "parameter", "action", "selected", "result"};
  static final String[] NONDEFAULT_HEADER = {"row_id", "family", "object", "setting", "description", "current_si",
      "baseline_si", "unit", "baseline_source", "definition", "linked", "parameterisable", "state"};
  static final String[] TEMPLATE_BASELINE_HEADER = {"row_id", "instance", "si_value", "definition", "star_version", "date"};
  static final String[] FACTORY_HEADER = {"row_id", "si_value", "star_version", "date"};
  static final String NO_PARAM_GROUPS = "parameter groups not supported in this build; parameters stay sorted by their "
      + "prefix (use Sort Alphabetically on the Parameters node)";

  /** Tool 17: Parameter sync (preview, apply). */
  void toolParamSync() throws IOException {
    CaseInfo c = caseOrNull();
    if (c == null) log.line("not in a case folder: no template baseline (factory defaults only), no paramsync CSV");
    SyncScan s = scanSync(c, true, true);
    log.line(syncSummary(s));
    logItems(s, false);
    if (s.items.isEmpty()) return;
    int pending = s.pending();
    if (!showSyncPreview(TOOL_NAMES[17], s)) {
      log.line(pending == 0 ? "nothing to apply" : "cancelled: nothing changed, " + pending + " item(s) stay pending");
      return;
    }
    applySync(s, c);
  }

  /** Lifecycle hook of Save new version / New stage (spec A.4): preview before saving; silent when nothing is
   *  pending; Cancel (or any failure) lets the save continue and the audit records the pending items. */
  void syncBeforeSave(CaseInfo n) {
    try {
      if (!gui || !Files.isRegularFile(Fs.config("settings_catalogue.csv"))) return;   // batch: audit reports only
      SyncScan s = scanSync(n, true);
      int pending = s.pending();
      if (pending == 0) return;
      if (showSyncPreview("Parameter sync before saving " + n.simBase() + " (" + pending + " pending)", s)) {
        applySync(s, n);
      } else {
        log.line("parameter sync cancelled: the save continues; " + pending
            + " item(s) stay pending (audit section PARAMSYNC)");
      }
    } catch (Throwable t) {
      log.na("parameter sync before save (the save continues)", t);
    }
  }

  /** Pre-run check line: WARN with the number of pending sync items. */
  void checkParamSync(CaseInfo c, List<String[]> rows) {
    try {
      if (!Files.isRegularFile(Fs.config("settings_catalogue.csv"))) {
        rows.add(chk("WARN", "parameter sync", "config\\settings_catalogue.csv not found"));
        return;
      }
      SyncScan s = scanSync(c, true);
      int p = s.pending();
      rows.add(p == 0 ? chk("PASS", "parameter sync", "nothing pending (" + s.counts() + ")")
          : chk("WARN", "parameter sync", p + " pending item(s): run 17. Parameter sync (" + s.counts() + ")"));
    } catch (Throwable t) {
      rows.add(chk("WARN", "parameter sync", "n/a (" + reason(t) + ")"));
    }
  }

  /** Setup-audit sections NONDEFAULT (every setting that differs from its baseline) and PARAMSYNC (report only). */
  void auditSync(Audit a, CaseInfo c) {
    if (!Files.isRegularFile(Fs.config("settings_catalogue.csv"))) {
      a.add("PARAMSYNC", "summary", "catalogue", "n/a (config\\settings_catalogue.csv not found)");
      return;
    }
    SyncScan s = scanSync(c, true);
    for (Item it : s.items) {
      if (!it.nonDefault()) continue;
      a.add("NONDEFAULT", it.objKey, it.setting(), "current=" + Fmt.num(it.v) + "; baseline=" + Fmt.num(it.b) + " ("
          + it.bSrc + "); unit=" + it.unit + "; definition=" + Fmt.oneLine(String.valueOf(it.def)) + "; state=" + it.state);
    }
    a.add("PARAMSYNC", "summary", "counts", s.counts());
    a.add("PARAMSYNC", "summary", "pending", s.pending());
    a.add("PARAMSYNC", "summary", "baselines", s.base.describe());
    for (Item it : s.items) {
      if (it.pending()) a.add("PARAMSYNC", it.objKey, it.setting(), "pending " + it.state + ": " + it.action);
    }
    for (String o : s.orphans) {
      a.add("PARAMSYNC", o, "orphan", "managed parameter without a catalogue setting (report only)");
    }
  }

  /** Tool 18: Non-default settings report (read only: Output window + audit\nondefault_<sim>.csv). */
  void toolNonDefault() throws IOException {
    CaseInfo c = caseOrNull();
    SyncScan s = scanSync(c, true, true);
    List<String[]> rows = new ArrayList<>();
    StringBuilder t = new StringBuilder();
    for (Item it : s.items) {
      if (!it.nonDefault()) continue;
      String linked = it.def == null ? "n/a" : PSync.linkTarget(it.def) != null ? PSync.linkTarget(it.def)
          : PSync.isLiteral(it.def) ? "no" : "expression";
      rows.add(new String[] {it.rowId, it.family, it.objKey, it.prop, it.desc, Fmt.num(it.v), Fmt.num(it.b), it.unit,
          it.bSrc, String.valueOf(it.def), linked, it.mode, it.state});
      t.append(String.format("%-7s %-38s %-14s %12s %12s %-5s %-4s %s%n", it.rowId, Fmt.cut(it.objKey, 38),
          Fmt.cut(it.prop, 14), Fmt.num(it.v), Fmt.num(it.b), it.unit, it.state, linked));
    }
    log.line(rows.size() + " non-default setting(s)" + (rows.isEmpty() ? "" : ":\n"
        + String.format("%-7s %-38s %-14s %12s %12s %-5s %-4s %s%n", "row", "object", "setting", "current", "baseline",
        "unit", "st", "linked") + t));
    log.line(syncSummary(s));
    int nb = 0;
    for (Item it : s.items) if ("NB".equals(it.state)) nb++;
    if (nb > 0) log.line(nb + " setting(s) have no baseline and are not listed (see 19. Snapshot baseline)");
    if (c == null) {
      log.line("not in a case folder: CSV not written");
      return;
    }
    Path out = c.sub("audit").resolve("nondefault_" + c.simBase() + ".csv");
    Csv.write(out, NONDEFAULT_HEADER, rows);
    log.line("written " + out);
  }

  /** Tool 19: Snapshot baseline (owner): template baseline or factory defaults, written inside _SIMKIT. */
  void toolSnapshot() throws IOException {
    String[] modes = {"Template baseline", "Factory defaults", "Cancel"};
    int m = Ui.option(TOOL_NAMES[19], "OWNER ACTION -- writes a baseline file inside " + Fs.kit() + "\n\n"
        + "Template baseline: the open sim IS the project template;\n"
        + "    writes templates\\<PROJ>\\<template>_baseline.csv (one row per setting and object).\n"
        + "Factory defaults: the open sim is a BLANK sim in which you created one default object of each source:\n"
        + "    an automated mesh operation with one surface and one volumetric custom control, the solvers and\n"
        + "    stopping criteria, a mass-flow inlet, a pressure outlet, a wall and a physics continuum;\n"
        + "    writes config\\factory_defaults_<release>.csv (one row per catalogue row).", modes);
    if (m == 2) throw new Cancel(null);
    SyncScan s = scanSync(null, false, true);
    String rel = starVersion();
    if (rel.startsWith("n/a")) rel = Ui.text(TOOL_NAMES[19], "STAR-CCM+ release not readable; type it (e.g. 19.02.009):", "");
    if (rel.trim().isEmpty()) throw new Cancel("a release is required");
    String date = Fmt.iso();
    List<String[]> rows = new ArrayList<>();
    List<String> notes = new ArrayList<>();
    Path out;
    String[] header;
    if (m == 0) {
      List<String[]> projects = projects();
      String[] codes = new String[projects.size()];
      for (int i = 0; i < codes.length; i++) codes[i] = projects.get(i)[0];
      String proj = Ui.choose(TOOL_NAMES[19], "Project of this template:", codes, null);
      String tmpl = "";
      for (String[] p : projects) if (p[0].equals(proj)) tmpl = p[3];
      String name = PSync.baselineName(tmpl);
      if (name == null) {
        name = PSync.baselineName(Ui.text(TOOL_NAMES[19], "projects.csv has no template for " + proj
            + ". Template name (e.g. " + proj + "_template_v01):", proj + "_template_v01"));
      }
      if (name == null) throw new Cancel("no template name");
      out = Fs.kit().resolve("templates").resolve(proj).resolve(name);
      header = TEMPLATE_BASELINE_HEADER;
      for (Item it : s.items) {
        if (it.v != null) rows.add(new String[] {it.rowId, it.objKey, Fmt.lit(it.v), it.def == null ? "" : it.def, rel, date});
        else notes.add("n/a  " + it.setting() + " @ " + it.objKey + ": " + it.note);
      }
    } else {
      out = Fs.kit().resolve("config").resolve("factory_defaults_" + Fs.fileSafe(rel) + ".csv");
      header = FACTORY_HEADER;
      Map<String, Item> first = new LinkedHashMap<>();
      for (Item it : s.items) {
        if (it.v == null) continue;
        if (!first.containsKey(it.rowId)) first.put(it.rowId, it);
        else notes.add("more than one instance of " + it.rowId + ": " + it.objKey + " ignored (first: "
            + first.get(it.rowId).objKey + ")");
      }
      for (Item it : first.values()) rows.add(new String[] {it.rowId, Fmt.lit(it.v), rel, date});
      for (String id : s.rowIds) {
        if (!first.containsKey(id)) notes.add("no readable instance for " + id + ": create a default object of its source");
      }
    }
    if (rows.isEmpty()) throw new Cancel("no readable setting in this sim: nothing to write");
    StringBuilder pv = new StringBuilder();
    for (String[] r : rows) pv.append(String.join("  ", r)).append('\n');
    if (!notes.isEmpty()) pv.append("\nNot written:\n").append(String.join("\n", notes)).append('\n');
    log.line("baseline preview (" + rows.size() + " row(s)):\n" + pv);
    if (!Ui.confirmText("OWNER ACTION - " + TOOL_NAMES[19], "Write " + rows.size() + " row(s) to " + out
        + " ?  (shared by the whole team; an existing file is kept as a .bak copy)", pv.toString())) {
      throw new Cancel(null);
    }
    if (Files.isRegularFile(out)) {
      Path bak = out.resolveSibling(out.getFileName() + "." + Fmt.ts() + ".bak");
      Files.copy(out, Fs.guard(bak, true));
      log.line("previous file kept as " + bak);
    }
    Csv.write(out, header, rows, true);
    log.line("OWNER ACTION by " + Env.user() + ": written " + rows.size() + " row(s) -> " + out);
  }

  /** Tool 20: Engineering calculators = lib\generic\SimKitCalc.java played through the library runner. */
  void toolCalc() {
    Path p = Fs.kit().resolve("lib").resolve("generic").resolve("SimKitCalc.java");
    String fallback = "Run SimKitCalc.java with File > Macro > Play Macro  (" + p + ")";
    if (!Files.isRegularFile(p)) {
      log.line("not found: " + p + "\n" + fallback);
      return;
    }
    try {
      log.line("running " + p);
      runMacroFile(p);                                                                                  // [U09]
      log.line("finished SimKitCalc");
    } catch (Throwable t) {
      log.na("SimKitCalc through StarScript", t);
      log.line(fallback);
    }
  }

  // ---------------------------------------------------------------- scan

  /** Quiet scan (audit, pre-run check, save hook): catalogue notes summarised in one line. */
  SyncScan scanSync(CaseInfo c, boolean withBaseline) { return scanSync(c, withBaseline, false); }

  /** Reads the catalogue, the baselines (if 'withBaseline') and every setting instance; classifies them.
   *  Read only: never changes the sim. Never throws for a single row or instance (it becomes "n/a").
   *  'verbose' prints every catalogue note (n/a rows, naming problems); otherwise one summary line. */
  SyncScan scanSync(CaseInfo c, boolean withBaseline, boolean verbose) {
    SyncScan s = new SyncScan();
    List<String[]> cat = config("settings_catalogue.csv", CATALOGUE_COLS, null);
    if (withBaseline) s.base = loadBaselines(c);
    s.params.putAll(paramMap());
    for (Map.Entry<String, Object> e : s.params.entrySet()) s.paramSi.put(e.getKey(), paramSi(e.getValue()));
    Pattern rule = parameterRule();
    for (String[] r : cat) {
      try { scanRow(s, r, rule); } catch (Throwable t) { s.notes.add("  catalogue row " + r[0] + ": n/a (" + reason(t) + ")"); }
    }
    if (verbose) for (String n : s.notes) log.line(n);
    else if (!s.notes.isEmpty()) log.line("  parameter sync: " + s.notes.size() + " catalogue note(s) (n/a, inactive, naming); "
        + "details in 17. Parameter sync / 18. Non-default settings report");
    Set<String> expected = new HashSet<>();
    for (Item it : s.items) {
      expected.add(it.pName);
      if (it.pAlt != null) expected.add(it.pAlt);
    }
    for (String n : s.params.keySet()) if (PSync.isManaged(n) && !expected.contains(n)) s.orphans.add(n);
    if (!s.orphans.isEmpty()) log.line("  managed parameters without a catalogue setting (kept): " + s.orphans);
    if (!withBaseline) return s;
    for (Item it : s.items) PSync.classify(it, s.paramSi);
    for (Item it : s.items) {   // S4 changes P: warn if other settings use P too (their value would change)
      if (!"S4".equals(it.state)) continue;
      List<String> users = new ArrayList<>();
      for (Item o : s.items) if (o != it && PSync.referenced(it.pName, Collections.singletonList(o.def))) users.add(o.objKey);
      if (!users.isEmpty()) it.action += "  WARNING: " + it.pName + " is also used by " + users;
    }
    return s;
  }

  /** One catalogue row: all its instances become Items. */
  void scanRow(SyncScan s, String[] r, Pattern rule) {
    String id = r[0], fam = r[1].trim().toUpperCase(Locale.ROOT), src = r[2], prop = r[4];
    String mode = r[5].trim().toLowerCase(Locale.ROOT);
    if (id.isEmpty()) return;
    s.rowIds.add(id);
    if (!Arrays.asList("yes", "check", "no").contains(mode)) {
      s.notes.add("  catalogue " + id + ": parameterisable '" + r[5] + "' unknown, treated as no");
      mode = "no";
    }
    if (!PSync.FAMILIES.contains(fam)) {
      s.notes.add("  catalogue " + id + ": family '" + r[1] + "' is not one of " + PSync.FAMILIES + ": report only");
      mode = "no";
    }
    if (!prop.matches("[A-Za-z][A-Za-z0-9]*")) {
      s.notes.add("  catalogue " + id + ": prop '" + prop + "' must be letters/digits: row skipped");
      return;
    }
    List<String[]> toks;
    try {
      toks = PSync.tokens(r[3]);
    } catch (IllegalArgumentException e) {
      s.notes.add("  catalogue " + id + ": path '" + r[3] + "' not usable (" + e.getMessage() + "): row skipped");
      return;
    }
    List<Object[]> inst;
    try {
      inst = instances(src);
    } catch (Throwable t) {
      s.notes.add("  catalogue " + id + ": source " + src + " n/a (" + reason(t) + ")");
      return;
    }
    List<String> names = new ArrayList<>(), parents = new ArrayList<>();
    for (Object[] o : inst) { names.add((String) o[2]); parents.add((String) o[3]); }
    boolean singular = PSync.singular(src);
    List<String> tok = PSync.instTokens(names, parents, singular);
    int na = 0, off = 0;
    String firstNa = null, firstOff = null;
    for (int i = 0; i < inst.size(); i++) {
      Item it = new Item();
      it.rowId = id;
      it.family = fam;
      it.prop = prop;
      it.mode = mode;
      it.unit = r[6];
      it.desc = r[7];
      it.objKey = (String) inst.get(i)[1];
      it.pName = PSync.managedName(fam, tok.get(i), prop);
      if (singular && inst.size() == 1) it.pAlt = PSync.managedName(fam, PSync.instToken(names.get(i)), prop);
      if (rule != null && !rule.matcher(it.pName).find() && i == 0) {
        s.notes.add("  catalogue " + id + ": managed name " + it.pName + " fails the parameter naming rule (lint will "
            + "flag it): rename prop '" + prop + "' in settings_catalogue.csv or widen the rule in naming_rules.csv");
      }
      readSetting(it, inst.get(i)[0], toks);
      if (it.v == null && it.inactive) {
        off++;
        if (firstOff == null) firstOff = it.objKey + ": " + it.note;
      } else if (it.v == null) {
        na++;
        if (firstNa == null) firstNa = it.objKey + ": " + it.note;
      }
      it.b = s.base.value(id, it.objKey);
      it.bSrc = s.base.source(id, it.objKey);
      s.items.add(it);
    }
    if (na > 0) s.notes.add("  catalogue " + id + ": n/a for " + na + " of " + inst.size() + " instance(s), e.g. " + firstNa);
    if (off > 0) s.notes.add("  catalogue " + id + ": inactive for " + off + " of " + inst.size() + " instance(s), e.g. "
        + firstOff);
  }

  /** Resolves the catalogue path on one object and reads value / definition. [U37][U35][U36] */
  void readSetting(Item it, Object owner, List<String[]> toks) {
    try {
      it.q = PSync.quantityOf(PSync.resolve(owner, toks));                                              // [U37]
      it.v = qSi(it.q);                                                                                  // [U35]
      it.raw = qRaw(it.q);                                                                               // [U35]
      it.def = qDef(it.q);                                                                               // [U36]
      if (it.v == null) it.note = "value not readable (no getSIValue/getRawValue on " + cls(it.q) + ")";
    } catch (Throwable t) {
      it.v = null;
      String cls = PSync.inactiveClass(t);
      it.inactive = cls != null;
      it.note = it.inactive ? "inactive: " + cls + " not present (option not selected)" : reason(t);
    }
  }

  /** Instances of a catalogue source as {object, instance key, name, parent name}. [U03][U17][U43][U44] */
  List<Object[]> instances(String source) throws Exception {
    List<Object[]> out = new ArrayList<>();
    String kind = source.trim(), cn = null;
    int colon = kind.indexOf(':');
    if (colon >= 0) {
      cn = kind.substring(colon + 1).trim();
      kind = kind.substring(0, colon).trim();
    }
    kind = kind.toLowerCase(Locale.ROOT);
    Class<?> k = null;
    if (cn != null) {
      k = Reflect.cls(cn);                                                                               // [U44]
      if (k == null) throw new ClassNotFoundException(cn + " (not in this build)");
    } else if (Arrays.asList("solver", "stopping", "boundary").contains(kind)) {
      throw new IllegalArgumentException("source '" + source + "' needs ':<fully qualified class>'");
    }
    switch (kind) {
      case "automesh":
        for (Object op : sim.get(MeshOperationManager.class).getObjects()) {
          if (op instanceof AutoMeshOperation) out.add(new Object[] {op, nm(op), nm(op), ""});
        }
        break;
      case "customcontrol":
        for (Object op : sim.get(MeshOperationManager.class).getObjects()) {
          if (!(op instanceof AutoMeshOperation)) continue;
          for (Object cc : ((AutoMeshOperation) op).getCustomMeshControls().getObjects()) {               // [U03]
            out.add(new Object[] {cc, nm(op) + "/" + nm(cc), nm(cc), nm(op)});
          }
        }
        break;
      case "solver":
        for (Object o : sim.getSolverManager().getObjects()) if (k.isInstance(o)) out.add(new Object[] {o, nm(o), nm(o), ""});
        break;
      case "stopping":                                                                                   // [U12]
        for (Object o : sim.getSolverStoppingCriterionManager().getObjects()) {
          if (k.isInstance(o)) out.add(new Object[] {o, nm(o), nm(o), ""});
        }
        break;
      case "boundary":
        for (Region r : sim.getRegionManager().getRegions()) {
          for (Boundary b : r.getBoundaryManager().getBoundaries()) {
            Object bt = Reflect.tryCall(b, "getBoundaryType");                                           // [U43]
            if (k.isInstance(b) || k.isInstance(bt)) out.add(new Object[] {b, nm(r) + "/" + nm(b), nm(b), nm(r)});
          }
        }
        break;
      case "continuum":
        for (Object o : sim.getContinuumManager().getObjects()) {
          if (o instanceof PhysicsContinuum) out.add(new Object[] {o, nm(o), nm(o), ""});
        }
        break;
      default:
        throw new IllegalArgumentException("unknown source '" + source + "'");
    }
    return out;
  }

  /** The parameter naming rule (naming_rules.csv row 'parameter', else the built-in one). */
  Pattern parameterRule() {
    for (String[] r : DEFAULT_NAMING_RULES) {
      if (!"parameter".equals(r[0])) continue;
      Pattern p = rx(r[1]);
      try {
        if (Files.isRegularFile(Fs.config("naming_rules.csv"))) {
          for (String[] c : readTable(Fs.config("naming_rules.csv"), new String[] {"object_type", "regex"})) {
            if ("parameter".equals(c[0]) && rx(c[1]) != null) p = rx(c[1]);
          }
        }
      } catch (IOException e) { /* built-in rule */ }
      return p;
    }
    return null;
  }

  // ---------------------------------------------------------------- baselines

  /** Template baseline of the case (if any) + factory defaults for this release (or the newest). */
  Baseline loadBaselines(CaseInfo c) {
    Baseline b = new Baseline();
    Path tf = null;
    try { tf = templateBaselineFile(c); } catch (Throwable t) { log.na("template baseline", t); }
    if (tf != null && Files.isRegularFile(tf)) {
      try {
        for (String[] r : readTable(tf, new String[] {"row_id", "instance", "si_value"})) {
          double v = Fmt.parse(r[2]);
          if (!r[0].isEmpty() && !Double.isNaN(v)) b.addTemplate(r[0], r[1], v);
        }
        b.tmplSrc = "template " + tf.getFileName();
      } catch (IOException e) {
        log.line("  template baseline " + tf + ": unreadable (" + e.getMessage() + ")");
      }
    } else {
      log.line("  template baseline: " + (tf == null ? "n/a (no case / no template in projects.csv)" : "not found " + tf));
    }
    String rel = starVersion();
    if (rel.startsWith("n/a")) rel = "";
    List<String> files = new ArrayList<>();
    for (Path f : Fs.list(Fs.kit().resolve("config"), "factory_defaults_*.csv")) files.add(f.getFileName().toString());
    String[] ch = PSync.chooseRelease(files, rel);
    if (ch == null) {
      log.line("  factory defaults: none in " + Fs.kit().resolve("config") + " (19. Snapshot baseline in a blank sim)");
    } else {
      if (!ch[1].isEmpty()) log.line("  " + ch[1]);
      try {
        for (String[] r : readTable(Fs.config(ch[0]), new String[] {"row_id", "si_value"})) {
          double v = Fmt.parse(r[1]);
          if (!r[0].isEmpty() && !Double.isNaN(v)) b.addFactory(r[0], v);
        }
        b.factSrc = "factory " + ch[0].replaceFirst("(?i)^factory_defaults_", "").replaceFirst("(?i)\\.csv$", "");
      } catch (IOException e) {
        log.line("  factory defaults " + ch[0] + ": unreadable (" + e.getMessage() + ")");
      }
    }
    log.line("  baselines: " + b.describe());
    return b;
  }

  /** _SIMKIT\templates\<PROJ>\<template>_baseline.csv for a case: template from case_identity.txt ("template=")
   *  or from projects.csv; null if unknown. */
  Path templateBaselineFile(CaseInfo c) {
    if (c == null) return null;
    String t = readIdentity(c).getOrDefault("template", "");
    if (t.isEmpty()) for (String[] p : projects()) if (p[0].equals(c.proj)) t = p[3];
    String n = PSync.baselineName(t);
    return n == null ? null : Fs.kit().resolve("templates").resolve(c.proj).resolve(n);
  }

  // ---------------------------------------------------------------- preview and apply

  /** Summary text: catalogue, states, pending, baselines. */
  static String syncSummary(SyncScan s) {
    return "parameter sync: " + s.rowIds.size() + " catalogue row(s), " + s.items.size() + " setting instance(s); "
        + s.counts() + "; pending " + s.pending() + "\nbaselines: " + s.base.describe()
        + (s.orphans.isEmpty() ? "" : "\nmanaged parameters without a setting (kept): " + s.orphans);
  }

  /** Every non-S0 item in the Output window ('all' = also S0). */
  void logItems(SyncScan s, boolean all) {
    StringBuilder t = new StringBuilder();
    for (Item it : s.items) {
      if (!all && ("S0".equals(it.state))) continue;
      t.append(String.format("%-4s %-7s %-36s %-13s %12s %12s  %s%n", it.state, it.rowId, Fmt.cut(it.objKey, 36),
          Fmt.cut(it.prop, 13), Fmt.num(it.v), Fmt.num(it.b), it.action));
    }
    if (t.length() > 0) log.line(String.format("%-4s %-7s %-36s %-13s %12s %12s  %s%n", "st", "row", "object", "setting",
        "current SI", "baseline SI", "action") + t);
  }

  /** Preview window (spec A.4): JTable with a checkbox column; pending rows are ticked and can be deselected,
   *  S2/S5/NP/NB rows are information only. Returns true on Apply (selection stored in Item.selected). */
  boolean showSyncPreview(String title, SyncScan s) {
    final List<Item> rows = new ArrayList<>();
    for (Item it : s.items) if (!"S0".equals(it.state) && !"NA".equals(it.state) && !"OFF".equals(it.state)) rows.add(it);
    if (rows.isEmpty()) return false;
    int pending = s.pending();
    String[] cols = {"apply", "object", "setting", "state", "current (SI)", "baseline (SI)", "action"};
    Object[][] data = new Object[rows.size()][];
    for (int i = 0; i < rows.size(); i++) {
      Item it = rows.get(i);
      it.selected = it.pending();
      data[i] = new Object[] {it.selected, it.objKey, it.setting(), it.state, Fmt.num(it.v) + " " + it.unit,
          Fmt.num(it.b) + " " + it.unit, it.action};
    }
    final DefaultTableModel m = new DefaultTableModel(data, cols) {
      private static final long serialVersionUID = 1L;

      @Override public Class<?> getColumnClass(int col) { return col == 0 ? Boolean.class : String.class; }

      @Override public boolean isCellEditable(int r, int col) { return col == 0 && rows.get(r).pending(); }
    };
    JTable t = new JTable(m);
    int[] w = {45, 230, 150, 45, 110, 110, 460};
    for (int i = 0; i < w.length; i++) t.getColumnModel().getColumn(i).setPreferredWidth(w[i]);
    t.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
    JScrollPane sp = new JScrollPane(t);
    sp.setPreferredSize(new java.awt.Dimension(1150, 420));
    JPanel p = new JPanel(new BorderLayout(4, 4));
    p.add(new JLabel("<html>" + Html.esc(syncSummary(s)).replace("\n", "<br>") + "<br>Ticked rows are applied. "
        + "Untick a row to leave it pending. Unticked grey rows are information only.</html>"), BorderLayout.NORTH);
    p.add(sp, BorderLayout.CENTER);
    p.add(new JLabel("<html>S1 create+link &nbsp; S2 linked, OK &nbsp; S3 back to default: unlink+delete &nbsp; "
        + "S4 direct edit adopted &nbsp; S4b default again: delete &nbsp; S5 user-managed &nbsp; NP not parameterisable "
        + "&nbsp; NB no baseline &nbsp; OFF option not selected. Only managed p_MESH/SOLV/STOP/BC/PHYS_ parameters are ever changed or deleted.</html>"),
        BorderLayout.SOUTH);
    String[] opts = pending > 0 ? new String[] {"Apply", "Cancel"} : new String[] {"Close"};
    int r = JOptionPane.showOptionDialog(null, p, title, JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null,
        opts, opts[0]);
    if (t.isEditing() && t.getCellEditor() != null) t.getCellEditor().stopCellEditing();
    if (pending == 0 || r != 0) return false;
    for (int i = 0; i < rows.size(); i++) {
      rows.get(i).selected = rows.get(i).pending() && Boolean.TRUE.equals(m.getValueAt(i, 0));
    }
    return true;
  }

  /** Applies the selected pending items: S1/S4 first, then S3/S4b (deletions after a second confirmation);
   *  groups the parameters; appends every row to audit\paramsync_<sim>.csv (if in a case); prints a summary. */
  void applySync(SyncScan s, CaseInfo c) throws IOException {
    List<Item> todo = new ArrayList<>(), dels = new ArrayList<>();
    for (Item it : s.items) {
      if (!it.pending()) continue;
      if (!it.selected) { it.result = "pending (deselected)"; continue; }
      todo.add(it);
      if ("S3".equals(it.state) || "S4b".equals(it.state)) dels.add(it);
    }
    boolean allowDelete = true;
    if (!dels.isEmpty() && gui) {
      StringBuilder d = new StringBuilder();
      for (Item it : dels) d.append(it.pName).append("   (").append(it.objKey).append(" ").append(it.prop).append(")\n");
      allowDelete = Ui.confirmText("Parameter sync - delete managed parameters", "Delete these " + dels.size()
          + " managed parameter(s) once nothing references them?  (No = unlink only, keep the parameters)", d.toString());
    }
    Map<String, Integer> done = new TreeMap<>();
    for (int pass = 0; pass < 2; pass++) {
      for (Item it : todo) {
        boolean back = "S3".equals(it.state) || "S4b".equals(it.state);
        if (back != (pass == 1)) continue;
        try {
          if ("S1".equals(it.state)) it.result = doCreate(it);
          else if ("S4".equals(it.state)) it.result = doAdopt(it);
          else if ("S3".equals(it.state)) {
            String u = unlink(it);
            it.result = u != null ? u : "unlinked; " + deleteIfUnreferenced(it, s, allowDelete);
          } else it.result = deleteIfUnreferenced(it, s, allowDelete);
        } catch (Throwable t) {
          it.result = "FAILED: " + reason(t);
        }
        String k = it.result.startsWith("FAILED") ? "failed" : it.result.startsWith("NP") ? "not parameterisable"
            : it.state;
        done.merge(k, 1, Integer::sum);
        log.line("  " + it.state + " " + it.objKey + " " + it.prop + ": " + it.result);
      }
    }
    if (!todo.isEmpty()) groupParameters();
    log.line("parameter sync applied: " + todo.size() + " item(s) " + done + "; " + s.counts() + " before sync");
    if (c == null) {
      log.line("not in a case folder: paramsync CSV not written");
      return;
    }
    writeSyncCsv(s, c.sub("audit").resolve("paramsync_" + c.simBase() + ".csv"));
  }

  /** Appends the sync rows (S0 summarised per catalogue row) to the paramsync CSV. */
  void writeSyncCsv(SyncScan s, Path out) throws IOException {
    String ts = Fmt.iso();
    List<String[]> rows = new ArrayList<>();
    Map<String, Integer> s0 = new LinkedHashMap<>();
    Map<String, String> fam = new HashMap<>();
    for (Item it : s.items) {
      if ("S0".equals(it.state)) {
        s0.merge(it.rowId, 1, Integer::sum);
        fam.put(it.rowId, it.family);
        continue;
      }
      rows.add(new String[] {ts, it.rowId, it.family, it.objKey, it.prop, it.state, Fmt.num(it.v), Fmt.num(it.b), it.bSrc,
          it.def == null ? "n/a" : it.def, it.pName, "NA".equals(it.state) || "OFF".equals(it.state) ? it.note : it.action,
          it.pending() ? (it.selected ? "yes" : "no") : "", it.result});
    }
    for (Map.Entry<String, Integer> e : s0.entrySet()) {
      rows.add(new String[] {ts, e.getKey(), fam.get(e.getKey()), "(" + e.getValue() + " instance(s))", "", "S0", "", "",
          "", "", "", "at baseline: nothing", "", ""});
    }
    for (String o : s.orphans) {
      rows.add(new String[] {ts, "", "", "", "", "ORPHAN", "", "", "", "", o, "managed parameter without a setting: kept",
          "", ""});
    }
    if (Files.isRegularFile(out)) {
      StringBuilder sb = new StringBuilder(Fs.read(out));
      if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') sb.append("\r\n");
      for (String[] r : rows) sb.append(Csv.row(r));
      Fs.write(out, sb.toString());
    } else {
      Csv.write(out, PARAMSYNC_HEADER, rows);
    }
    log.line("paramsync rows appended -> " + out);
  }

  /** S1: create P with the dimensions/units of the setting and its value, then link. */
  String doCreate(Item it) throws Exception {
    if (paramByName(it.pName) != null) return "SKIPPED: " + it.pName + " exists already (run the sync again)";
    ScalarGlobalParameter p = createParam(it);
    String r = link(it, it.pName, p);
    if (r == null) return "created " + it.pName + " = " + Fmt.num(it.v) + " " + it.unit + " and linked";
    removeParam(p);
    log.line("  removed the just-created " + it.pName + " again (link failed)");
    if ("check".equals(it.mode)) {
      it.state = "NP";
      return "NP: not parameterisable in this build (" + r + ")";
    }
    return "FAILED: " + r;
  }

  /** S4: direct edit adopted: P = current value (units of the setting), relink. */
  String doAdopt(Item it) throws Exception {
    Object p = paramByName(it.pName);
    if (!(p instanceof ScalarGlobalParameter)) return "FAILED: " + it.pName + " missing or not a scalar parameter";
    Double before = paramSi(p);
    boolean change = before == null || !PSync.eq(before, it.v);
    if (change) setParamValue((ScalarGlobalParameter) p, it, false);
    String r = link(it, it.pName, p);
    if (r != null) return "FAILED: " + r;
    return change ? "direct edit adopted: " + it.pName + " " + Fmt.num(before) + " -> " + Fmt.num(it.v) + ", relinked"
        : "relinked to " + it.pName;
  }

  /** Links the setting to P ("${P}") and verifies definition and value; restores the old definition otherwise.
   *  Returns null on success, else the reason. [U36] */
  String link(Item it, String pName, Object p) {
    Double pv = paramSi(p);
    if (pv == null || !PSync.eq(pv, it.v)) {
      return "parameter value " + Fmt.num(pv) + " differs from the setting " + Fmt.num(it.v) + " (SI; units?)";
    }
    String old = qDef(it.q);
    try {
      qSetDef(it.q, "${" + pName + "}");                                                                 // [U36]
    } catch (Throwable t) {
      return "setDefinition failed: " + reason(t);
    }
    String now = qDef(it.q);
    Double v2 = qSi(it.q);
    if (!pName.equals(PSync.linkTarget(now)) || v2 == null || !PSync.eq(v2, it.v)) {
      restoreLiteral(it, old);
      return "link not taken or value changed (definition '" + now + "', value " + Fmt.num(v2) + "); restored";
    }
    return null;
  }

  /** S3: unlink (definition = literal of the current value, in the setting's units); verifies the value. [U36] */
  String unlink(Item it) {
    Double raw = qRaw(it.q), before = qSi(it.q);
    if (raw == null) return "FAILED: value not readable, not unlinked";
    try {
      qSetDef(it.q, Fmt.lit(raw));                                                                       // [U36]
    } catch (Throwable t) {
      try {
        Reflect.invoke(it.q, "setValue", raw);                                                           // [U36] fallback
      } catch (Throwable t2) {
        return "FAILED: unlink: " + reason(t);
      }
    }
    String now = qDef(it.q);
    Double after = qSi(it.q);
    if (it.pName.equals(PSync.linkTarget(now))) return "FAILED: still linked (definition '" + now + "')";
    if (before != null && (after == null || !PSync.eq(before, after))) {
      try { qSetDef(it.q, "${" + it.pName + "}"); } catch (Throwable t) { /* reported below */ }
      return "FAILED: value changed when unlinking (" + Fmt.num(before) + " -> " + Fmt.num(after) + "); relinked";
    }
    return null;
  }

  /** Puts a literal definition back (old literal, else the current value). [U36] */
  void restoreLiteral(Item it, String oldDef) {
    double val = it.raw != null ? it.raw : it.v;
    try {
      qSetDef(it.q, oldDef != null && PSync.isLiteral(oldDef) && !oldDef.trim().isEmpty() ? oldDef : Fmt.lit(val));
    } catch (Throwable t) {
      try { Reflect.invoke(it.q, "setValue", val); } catch (Throwable t2) { log.na("restore " + it.objKey + " " + it.prop, t2); }
    }
  }

  /** Deletes managed parameter P if no catalogue setting and no parameter references it any more. Deleting a
   *  managed parameter is the ONLY deletion SimKit does; it is logged. [U41] */
  String deleteIfUnreferenced(Item it, SyncScan s, boolean allowDelete) throws Exception {
    if (!PSync.isManaged(it.pName)) return "kept " + it.pName + " (not a managed parameter)";
    if (!allowDelete) return "kept " + it.pName + " (deletion declined)";
    List<String> defs = new ArrayList<>();
    for (Item o : s.items) if (o != it && o.q != null) defs.add(qDef(o.q));
    for (Map.Entry<String, Object> e : paramMap().entrySet()) {
      if (!e.getKey().equals(it.pName) && e.getValue() instanceof ScalarGlobalParameter) {
        defs.add(qDef(((ScalarGlobalParameter) e.getValue()).getQuantity()));                           // [U10]
      }
    }
    if (PSync.referenced(it.pName, defs)) return "kept " + it.pName + " (still referenced)";
    Object p = paramByName(it.pName);
    if (p == null) return it.pName + " already absent";
    String val = Fmt.num(paramSi(p));
    removeParam(p);
    log.line("  DELETED managed parameter " + it.pName + " (was " + val + " " + it.unit + "; " + it.objKey + " "
        + it.prop + " back to its baseline " + Fmt.num(it.b) + "; unreferenced)");
    return "deleted " + it.pName;
  }

  /** Moves parameters into G30..G34 (managed families) and G35_caseParams (other p_ parameters). [U42][U08] */
  void groupParameters() {
    Object gm = null;
    try { gm = Reflect.tryCall(sim.get(GlobalParameterManager.class), "getGroupsManager"); }            // [U42]
    catch (Throwable t) { /* not supported */ }
    if (!(gm instanceof ClientServerObjectGroupManager)) {
      log.line("  " + NO_PARAM_GROUPS);
      return;
    }
    Map<String, List<ClientServerObject>> plan = new TreeMap<>();
    for (Map.Entry<String, Object> e : paramMap().entrySet()) {
      String g = PSync.groupFor(e.getKey());
      if (g != null && e.getValue() instanceof ClientServerObject) {
        plan.computeIfAbsent(g, x -> new ArrayList<>()).add((ClientServerObject) e.getValue());
      }
    }
    for (Map.Entry<String, List<ClientServerObject>> e : plan.entrySet()) {
      try {
        groupInto((ClientServerObjectGroupManager) gm, e.getKey(), e.getValue());                                         // [U42][U08]
      } catch (Throwable t) {
        log.na("parameter group " + e.getKey(), t);
        log.line("  " + NO_PARAM_GROUPS);
        return;
      }
    }
    log.line("  parameter groups: " + plan.keySet());
  }

  // ---------------------------------------------------------------- STAR access for parameter sync (tagged)

  /** [U35] SI value of a quantity: getSIValue(), else raw value x Units.getConversion() (+ getOffset());
   *  dimensionless or unconvertible -> raw value. null if no value getter works. */
  static Double qSi(Object q) {
    Double v = Reflect.num(Reflect.tryCall(q, "getSIValue"));
    if (v != null) return v;
    Double raw = qRaw(q);
    if (raw == null) return null;
    Object u = Reflect.tryCall(q, "getUnits");
    Double f = Reflect.num(Reflect.tryCall(u, "getConversion"));
    if (f == null) return raw;
    Double off = Reflect.num(Reflect.tryCall(u, "getOffset"));
    return raw * f + (off == null ? 0.0 : off);
  }

  /** [U35] Value in the quantity's own units: getRawValue(), else getValue(). */
  static Double qRaw(Object q) {
    Double v = Reflect.num(Reflect.tryCall(q, "getRawValue"));
    return v != null ? v : Reflect.num(Reflect.tryCall(q, "getValue"));
  }

  /** [U36] Definition ("${p_x}", a literal, or an expression); null if not readable. */
  static String qDef(Object q) {
    Object d = Reflect.tryCall(q, "getDefinition");
    return d == null ? null : String.valueOf(d);
  }

  /** [U36] Sets the definition; throws with the reason. */
  static void qSetDef(Object q, String def) throws Exception { Reflect.invoke(q, "setDefinition", def); }

  /** SI value of a scalar global parameter; null otherwise. [U10][U35] */
  static Double paramSi(Object p) {
    try {
      return p instanceof ScalarGlobalParameter ? qSi(((ScalarGlobalParameter) p).getQuantity()) : null;  // [U10]
    } catch (Throwable t) {
      return null;
    }
  }

  /** All global parameters by name. [U10] */
  Map<String, Object> paramMap() {
    Map<String, Object> m = new LinkedHashMap<>();
    try {
      for (Object p : sim.get(GlobalParameterManager.class).getObjects()) m.put(nm(p), p);              // [U10]
    } catch (Throwable t) {
      log.na("parameters", t);
    }
    return m;
  }

  Object paramByName(String name) { return paramMap().get(name); }

  /** New managed scalar parameter named it.pName with the dimensions/units/value of the setting.
   *  [U10][U22][U38][U39][U40] */
  ScalarGlobalParameter createParam(Item it) throws Exception {
    GlobalParameterManager gpm = sim.get(GlobalParameterManager.class);
    ScalarGlobalParameter p = (ScalarGlobalParameter) gpm.createGlobalParameter(ScalarGlobalParameter.class, "Scalar"); // [U10]
    p.setPresentationName(it.pName);                                                                     // [U22]
    if (!it.pName.equals(nm(p))) {
      String got = nm(p);
      removeParam(p);
      throw new IllegalStateException("name " + it.pName + " not accepted (got '" + got + "'); parameter removed");
    }
    setParamValue(p, it, true);
    return p;
  }

  /** Dimensions (if 'dims': of the quantity, of its units, else from the catalogue unit), units and value of P
   *  taken from the setting. [U38][U39][U40] */
  void setParamValue(ScalarGlobalParameter p, Item it, boolean dims) {
    Object units = Reflect.tryCall(it.q, "getUnits");                                                   // [U35]
    if (dims) {
      Object d = Reflect.tryCall(it.q, "getDimensions");                                                // [U38]
      if (!(d instanceof Dimensions)) d = Reflect.tryCall(units, "getDimensions");                      // [U38]
      if (!(d instanceof Dimensions)) d = dimsForUnit(it.unit);                                         // [U39]
      if (d instanceof Dimensions) p.setDimensions((Dimensions) d);                                     // [U38]
      else log.line("  " + it.pName + ": dimensions n/a (catalogue unit '" + it.unit + "'): set them by hand");
    }
    ScalarPhysicalQuantity pq = p.getQuantity();                                                        // [U10]
    if (units instanceof Units && it.raw != null) {
      pq.setUnits((Units) units);                                                                       // [U40]
      pq.setValue(it.raw);                                                                              // [U40]
    } else {
      Units su = unitsByName(it.unit);                                                                  // [U39]
      if (su != null) pq.setUnits(su);                                                                  // [U40]
      pq.setValue(it.v);                                                                                // [U40]
    }
  }

  /** [U39] Dimensions for a catalogue unit (SI units only; "%", "-" and "" = dimensionless); null if unknown. */
  static Dimensions dimsForUnit(String unit) {
    try {
      switch (unit == null ? "" : unit.trim()) {
        case "m": return Dimensions.Builder().length(1).build();
        case "s": return Dimensions.Builder().time(1).build();
        case "K": return Dimensions.Builder().temperature(1).build();
        case "kg/s": return Dimensions.Builder().mass(1).time(-1).build();
        case "m/s": return Dimensions.Builder().length(1).time(-1).build();
        case "Pa": return Dimensions.Builder().mass(1).length(-1).time(-2).build();
        case "%": case "-": case "": return Dimensions.Builder().build();
        default: return null;
      }
    } catch (Throwable t) {
      return null;
    }
  }

  /** [U39] Units object by name ("m", "Pa", ...); null for dimensionless or unknown units. */
  Units unitsByName(String name) {
    String n = name == null ? "" : name.trim();
    if (n.isEmpty() || n.equals("-") || n.equals("%") || n.equals("1")) return null;
    try { return (Units) sim.getUnitsManager().getObject(n); } catch (Throwable t) { return null; }
  }

  /** [U41] Deletes a global parameter: tries remove / removeObjects / deleteObjects with the object, a one-element
   *  array or a NeoObjectVector; verifies that the parameter is gone. */
  void removeParam(Object p) throws Exception {
    Object gpm = sim.get(GlobalParameterManager.class);
    String name = nm(p);
    Throwable last = null;
    for (String mname : new String[] {"remove", "removeObjects", "deleteObjects"}) {
      for (java.lang.reflect.Method m : gpm.getClass().getMethods()) {
        if (!m.getName().equals(mname) || m.getParameterCount() != 1) continue;
        Class<?> pt = m.getParameterTypes()[0];
        Object arg = null;
        if (pt.isInstance(p)) {
          arg = p;
        } else if (pt.isArray() && pt.getComponentType().isInstance(p)) {
          arg = java.lang.reflect.Array.newInstance(pt.getComponentType(), 1);
          java.lang.reflect.Array.set(arg, 0, p);
        } else if (pt.isAssignableFrom(NeoObjectVector.class)) {
          arg = new NeoObjectVector(new Object[] {p});
        }
        if (arg == null) continue;
        try {
          try {
            m.invoke(gpm, new Object[] {arg});
          } catch (IllegalAccessException e) {
            m.setAccessible(true);
            m.invoke(gpm, new Object[] {arg});
          }
          if (paramByName(name) == null) return;
        } catch (Throwable t) {
          last = t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null ? t.getCause() : t;
        }
      }
    }
    throw new IllegalStateException("could not delete parameter " + name + (last == null ? "" : " (" + reason(last) + ")"));
  }

  // ---------------------------------------------------------------- parameter-sync data (plain Java)

  /** One catalogue row x one object instance. */
  static final class Item {
    String rowId = "", family = "", prop = "", mode = "no", unit = "", desc = "", objKey = "", pName = "", pAlt;
    Object q;                       // the STAR quantity (null in unit tests / when n/a)
    Double v, raw, b;               // current SI value, current value in the quantity's units, baseline SI value
    String def;                     // definition; null = not readable
    String bSrc = "none", state = "", action = "", note = "", result = "";
    boolean selected;
    boolean inactive;               // the setting is not present because its option is not selected (state OFF)

    boolean pending() { return Arrays.asList(PSync.PENDING).contains(state); }
    boolean nonDefault() { return v != null && b != null && !PSync.eq(v, b); }
    String setting() { return rowId + " " + prop; }
  }

  /** Result of one scan. */
  static final class SyncScan {
    final List<Item> items = new ArrayList<>();
    final List<String> rowIds = new ArrayList<>();
    final Map<String, Object> params = new LinkedHashMap<>();
    final Map<String, Double> paramSi = new HashMap<>();   // existing parameter name -> SI value (null if n/a)
    final List<String> orphans = new ArrayList<>();
    final List<String> notes = new ArrayList<>();          // per-row remarks (n/a reasons, naming problems)
    Baseline base = new Baseline();

    int pending() {
      int n = 0;
      for (Item it : items) if (it.pending()) n++;
      return n;
    }

    String counts() {
      Map<String, Integer> m = new TreeMap<>();
      for (Item it : items) m.merge(it.state.isEmpty() ? "-" : it.state, 1, Integer::sum);
      StringBuilder sb = new StringBuilder();
      for (Map.Entry<String, Integer> e : m.entrySet()) sb.append(sb.length() == 0 ? "" : " ").append(e.getKey()).append('=').append(e.getValue());
      return sb.length() == 0 ? "no settings" : sb.toString();
    }
  }

  /** Baseline values: template (row_id + instance) first, then factory defaults (row_id). */
  static final class Baseline {
    final Map<String, Double> tmpl = new HashMap<>(), fact = new HashMap<>();
    String tmplSrc = "", factSrc = "";

    void addTemplate(String row, String inst, double v) { tmpl.put(row + "|" + inst, v); }
    void addFactory(String row, double v) { fact.putIfAbsent(row, v); }

    Double value(String row, String inst) {
      Double v = tmpl.get(row + "|" + inst);
      return v != null ? v : fact.get(row);
    }

    String source(String row, String inst) {
      return tmpl.containsKey(row + "|" + inst) ? tmplSrc : fact.containsKey(row) ? factSrc : "none";
    }

    String describe() {
      return "template = " + (tmplSrc.isEmpty() ? "none" : tmplSrc + " (" + tmpl.size() + " values)") + "; factory = "
          + (factSrc.isEmpty() ? "none" : factSrc + " (" + fact.size() + " values)");
    }
  }

  /** Pure parameter-sync logic (no STAR types): path tokens, reflection resolver, names, tolerance, states,
   *  baseline file names and release choice. Unit-tested on a plain JDK. */
  static final class PSync {
    static final List<String> FAMILIES = Arrays.asList("MESH", "SOLV", "STOP", "BC", "PHYS");
    static final String[] FAMILY_GROUPS = {"G30_meshParams", "G31_solverParams", "G32_stopParams", "G33_bcParams",
        "G34_physParams"};
    static final String CASE_GROUP = "G35_caseParams";
    static final String[] PENDING = {"S1", "S3", "S4", "S4b"};
    static final Pattern MANAGED = Pattern.compile("^p_(MESH|SOLV|STOP|BC|PHYS)_");
    static final Pattern TOKEN = Pattern.compile("^([A-Za-z_$][A-Za-z0-9_$]*)(?:\\[\\s*([A-Za-z_$][A-Za-z0-9_$.]*)\\s*\\])?$");
    static final Pattern LINK = Pattern.compile("^\\s*\\$\\{\\s*([^}\\s]+)\\s*\\}\\s*$");
    static final Pattern NUMBER = Pattern.compile("^\\s*[-+]?(\\d+\\.?\\d*|\\.\\d+)([eE][-+]?\\d+)?\\s*$");
    static final Pattern RELEASE_FILE = Pattern.compile("^factory_defaults_(.+)\\.csv$", Pattern.CASE_INSENSITIVE);

    /** "a/b[pkg.C]/c" -> {{"a", null}, {"b", "pkg.C"}, {"c", null}}; IllegalArgumentException if malformed. */
    static List<String[]> tokens(String path) {
      if (path == null || path.trim().isEmpty()) throw new IllegalArgumentException("empty path");
      List<String[]> out = new ArrayList<>();
      for (String raw : path.split("/", -1)) {
        Matcher m = TOKEN.matcher(raw.trim());
        if (!m.matches()) throw new IllegalArgumentException("bad token '" + raw.trim() + "'");
        out.add(new String[] {m.group(1), m.group(2)});
      }
      return out;
    }

    /** Follows the tokens by reflection: name -> name(), name[C] -> name(Class.forName(C)). Throws with the reason
     *  (missing method, missing class, null result); never returns null. */
    static Object resolve(Object root, List<String[]> toks) throws Exception {
      Object cur = root;
      for (String[] t : toks) {
        if (cur == null) throw new IllegalStateException("null object before " + t[0]);
        String call;
        if (t[1] == null) {
          call = t[0] + "()";
          cur = Reflect.invoke(cur, t[0]);
        } else {
          Class<?> k = Reflect.cls(t[1]);
          if (k == null) throw new ClassNotFoundException(t[1]);
          call = t[0] + "(" + k.getSimpleName() + ")";
          cur = Reflect.invoke(cur, t[0], k);
        }
        if (cur == null) throw new IllegalStateException(call + " returned null");
      }
      return cur;
    }

    /** The quantity at the end of a path: the object itself if it has getDefinition() and setDefinition(String),
     *  else its getQuantity(), else the object (read only). */
    static Object quantityOf(Object o) throws Exception {
      if (Reflect.has(o, "getDefinition") && Reflect.has(o, "setDefinition", String.class)) return o;
      if (Reflect.has(o, "getQuantity")) {
        Object q = Reflect.invoke(o, "getQuantity");
        if (q == null) throw new IllegalStateException("getQuantity() returned null");
        return q;
      }
      return o;
    }

    /** If t says that a manager has no object of a class (STAR NeoException "Class: class x.y.Z": the option that
     *  creates it is not selected), the simple class name Z; else null. Such settings are inactive, not errors. */
    static String inactiveClass(Throwable t) {
      for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
        String m = c.getMessage();
        if (m == null) continue;
        Matcher x = INACTIVE.matcher(m);
        if (x.find()) return x.group(1).substring(x.group(1).lastIndexOf('.') + 1);
      }
      return null;
    }

    static final Pattern INACTIVE = Pattern.compile("^\\s*Class:\\s*(?:class|interface)\\s+([\\w.$]+)");

    /** Spec tolerance: |a-b| <= 1e-9 * max(1, |a|, |b|). */
    static boolean eq(double a, double b) {
      return Math.abs(a - b) <= 1e-9 * Math.max(1.0, Math.max(Math.abs(a), Math.abs(b)));
    }

    /** Object name stripped of everything that is not a letter or digit (MO_30_autoMesh -> MO30autoMesh). */
    static String instToken(String name) {
      String s = name == null ? "" : name.replaceAll("[^A-Za-z0-9]", "");
      return s.isEmpty() ? "obj" : s;
    }

    /** p_<FAMILY>_<inst>_<prop>, or p_<FAMILY>_<prop> when inst is empty. */
    static String managedName(String family, String inst, String prop) {
      return "p_" + family + "_" + (inst == null || inst.isEmpty() ? "" : inst + "_") + prop;
    }

    static boolean isManaged(String name) { return name != null && MANAGED.matcher(name).find(); }

    /** Parameter group: G30..G34 by managed family, G35_caseParams for any other p_ name, null otherwise. */
    static String groupFor(String name) {
      if (name == null || !name.startsWith("p_")) return null;
      Matcher m = MANAGED.matcher(name);
      return m.find() ? FAMILY_GROUPS[FAMILIES.indexOf(m.group(1))] : CASE_GROUP;
    }

    /** Sources that normally have one instance (inst is omitted when there is exactly one). */
    static boolean singular(String source) {
      String s = source == null ? "" : source.trim().toLowerCase(Locale.ROOT);
      return s.startsWith("solver:") || s.startsWith("stopping:") || s.equals("continuum");
    }

    /** inst tokens for the instances of one row: "" for a single instance of a singular source; names that strip
     *  to the same token get the parent prefix (custom controls: op + control), then a counter. */
    static List<String> instTokens(List<String> names, List<String> parents, boolean singular) {
      List<String> out = new ArrayList<>();
      if (singular && names.size() == 1) {
        out.add("");
        return out;
      }
      Map<String, Integer> count = new HashMap<>();
      for (String s : names) count.merge(instToken(s), 1, Integer::sum);
      Set<String> used = new HashSet<>();
      for (int i = 0; i < names.size(); i++) {
        String t = instToken(names.get(i));
        if (count.get(t) > 1) t = instToken(parents.get(i) + names.get(i));
        String u = t;
        for (int k = 2; used.contains(u); k++) u = t + k;
        used.add(u);
        out.add(u);
      }
      return out;
    }

    /** "${p_x}" -> "p_x"; anything else -> null. */
    static String linkTarget(String def) {
      if (def == null) return null;
      Matcher m = LINK.matcher(def);
      return m.matches() ? m.group(1) : null;
    }

    /** Empty or a plain number = not linked. */
    static boolean isLiteral(String def) {
      return def != null && (def.trim().isEmpty() || NUMBER.matcher(def).matches());
    }

    /** True if any definition references parameter p (${p} or $p). */
    static boolean referenced(String p, Collection<String> defs) {
      Pattern r = Pattern.compile("\\$\\{?\\s*" + Pattern.quote(p) + "(?![A-Za-z0-9_])");
      for (String d : defs) if (d != null && r.matcher(d).find()) return true;
      return false;
    }

    /** State and proposed action of one item (spec A.4 table). paramSi: existing parameters -> SI value. */
    static void classify(Item it, Map<String, Double> paramSi) {
      if (it.v == null) {
        if (it.inactive) set(it, "OFF", it.note + ": report only");
        else set(it, "NA", "n/a: " + it.note);
        return;
      }
      String link = linkTarget(it.def);
      boolean linkedP = link != null && (link.equals(it.pName) || link.equals(it.pAlt));
      if (linkedP) it.pName = link;
      if (it.b == null) {
        set(it, "NB", "no baseline: report only" + (linkedP ? " (linked to " + link + ")" : ""));
        return;
      }
      boolean atBase = eq(it.v, it.b);
      if (linkedP) {
        Double pv = paramSi.get(it.pName);
        if (eq(pv != null ? pv : it.v, it.b)) set(it, "S3", "back to default: unlink, delete " + it.pName + " if unreferenced");
        else set(it, "S2", "OK (linked to " + it.pName + ")");
        return;
      }
      if (it.def == null) {
        set(it, atBase ? "S0" : "NP", atBase ? "nothing" : "non-default; definition not readable: report only");
        return;
      }
      if (!isLiteral(it.def)) {
        set(it, "S5", "user-managed (" + Fmt.cut(Fmt.oneLine(it.def), 60) + "): report only");
        return;
      }
      if ("no".equals(it.mode)) {
        set(it, atBase ? "S0" : "NP", atBase ? "nothing" : "non-default setting (not parameterisable): report only");
        return;
      }
      String p = paramSi.containsKey(it.pName) ? it.pName
          : it.pAlt != null && paramSi.containsKey(it.pAlt) ? it.pAlt : null;
      if (p != null) {
        it.pName = p;
        if (atBase) {
          set(it, "S4b", "setting back at default: delete " + p + " if unreferenced");
        } else {
          Double pv = paramSi.get(p);
          set(it, "S4", pv != null && eq(it.v, pv) ? "relink to " + p + " (same value)"
              : "direct edit adopted: set " + p + " = current value, relink");
        }
        return;
      }
      if (atBase) set(it, "S0", "nothing");
      else set(it, "S1", "create " + it.pName + " = current value and link"
          + ("check".equals(it.mode) ? " (tried; may turn out not parameterisable)" : ""));
    }

    static void set(Item it, String state, String action) {
      it.state = state;
      it.action = action;
    }

    /** "templates\ISO\ISO_template_v01.simt" -> "ISO_template_v01_baseline.csv"; null if empty. */
    static String baselineName(String template) {
      if (template == null) return null;
      String t = template.trim().replace('\\', '/');
      t = t.substring(t.lastIndexOf('/') + 1).replaceFirst("(?i)\\.(simt|sim)$", "");
      return t.isEmpty() ? null : t + "_baseline.csv";
    }

    /** Factory-defaults file for 'release' among the file names: {file, ""} for the exact release, else
     *  {newest file, note}; null if there is none. */
    static String[] chooseRelease(List<String> fileNames, String release) {
      String rel = release == null ? "" : release.trim();
      String want = rel.replaceAll("[^A-Za-z0-9._-]+", "_");
      String best = null, bestRel = null;
      for (String f : fileNames) {
        Matcher m = RELEASE_FILE.matcher(f);
        if (!m.matches()) continue;
        if (!rel.isEmpty() && m.group(1).equals(want)) return new String[] {f, ""};
        if (best == null || compareRelease(m.group(1), bestRel) > 0) {
          best = f;
          bestRel = m.group(1);
        }
      }
      if (best == null) return null;
      return new String[] {best, "factory defaults for release " + (rel.isEmpty() ? "(unknown)" : rel)
          + " not found: using the newest, " + bestRel};
    }

    /** Compares release strings numerically token by token (19.10.002 > 19.02.009 > 18.06). */
    static int compareRelease(String a, String b) {
      String[] x = a.split("[^0-9]+"), y = b.split("[^0-9]+");
      for (int i = 0; i < Math.max(x.length, y.length); i++) {
        long u = i < x.length && !x[i].isEmpty() ? Long.parseLong(x[i]) : -1;
        long v = i < y.length && !y[i].isEmpty() ? Long.parseLong(y[i]) : -1;
        if (u != v) return Long.compare(u, v);
      }
      return a.compareTo(b);
    }
  }

  // =====================================================================================================
  // Configuration (CSV files in _SIMKIT\config, built-in defaults when missing)
  // =====================================================================================================

  /** Reads config\<file> and returns its rows with the requested columns (by header name, in 'cols' order).
   *  Missing file -> 'defaults' (or an empty list). Lines whose first cell starts with '#' are comments. */
  List<String[]> config(String file, String[] cols, String[][] defaults) {
    Path p = Fs.config(file);
    List<String[]> out = new ArrayList<>();
    if (!Files.isRegularFile(p)) {
      log.line("  config " + file + ": not found, " + (defaults == null ? "nothing to use" : "using built-in defaults"));
      if (defaults != null) for (String[] d : defaults) out.add(Arrays.copyOf(d, cols.length));
      return out;
    }
    try {
      out.addAll(readTable(p, cols));
      log.line("  config " + file + ": " + out.size() + " row(s)");
    } catch (IOException e) {
      log.line("  config " + file + ": unreadable (" + e.getMessage() + ")" + (defaults == null ? "" : ", using defaults"));
      if (defaults != null) for (String[] d : defaults) out.add(Arrays.copyOf(d, cols.length));
    }
    return out;
  }

  /** Rows of a CSV file with the requested columns (by header name, case-insensitive, in 'cols' order; missing
   *  column -> ""). Lines whose first cell starts with '#' are comments. */
  static List<String[]> readTable(Path p, String[] cols) throws IOException {
    List<String[]> out = new ArrayList<>();
    List<String[]> rows = Csv.parse(Fs.read(p));
    if (rows.isEmpty()) return out;
    Map<String, Integer> idx = new HashMap<>();
    String[] head = rows.get(0);
    for (int i = 0; i < head.length; i++) idx.put(head[i].trim().toLowerCase(Locale.ROOT), i);
    for (int r = 1; r < rows.size(); r++) {
      String[] src = rows.get(r);
      if (src.length == 0 || src[0].trim().startsWith("#")) continue;
      String[] dst = new String[cols.length];
      for (int k = 0; k < cols.length; k++) {
        Integer i = idx.get(cols[k]);
        dst[k] = i != null && i < src.length ? src[i].trim() : "";
      }
      out.add(dst);
    }
    return out;
  }

  List<String[]> projects() {
    List<String[]> p = config("projects.csv", new String[] {"proj", "description", "config_codes", "template"}, DEFAULT_PROJECTS);
    if (p.isEmpty()) throw new Cancel("projects.csv has no rows");
    return p;
  }

  List<String> stageList() {
    List<String> s = new ArrayList<>();
    for (String[] r : config("stages.csv", new String[] {"stage", "description"}, DEFAULT_STAGES)) {
      if (r[0].matches("[A-Z]+")) s.add(r[0]);
    }
    return s;
  }

  /** Compiled rule regex (cached); null and a log line if the regex is invalid. */
  Pattern rx(String regex) {
    if (regex == null || regex.isEmpty()) return null;
    if (rxCache.containsKey(regex)) return rxCache.get(regex);
    Pattern p = null;
    try { p = Pattern.compile(regex); } catch (PatternSyntaxException e) { log.line("  invalid regex in config: " + regex); }
    rxCache.put(regex, p);
    return p;
  }

  /** Rule object_type matches an object type: "report;monitor", plurals ("reports"), spaces, "*" or "all". */
  static boolean typeMatches(String ruleType, String type) {
    for (String t : ruleType.toLowerCase(Locale.ROOT).split("[;,|/]")) {
      t = t.trim().replace(' ', '_');
      if (t.equals("*") || t.equals("all") || t.equals(type) || t.equals(type + "s")
          || ((t.equals("part") || t.equals("parts")) && type.equals("geometry_part"))
          || (type.endsWith("y") && t.equals(type.substring(0, type.length() - 1) + "ies"))
          || (type.equals("boundary") && t.equals("boundaries"))) return true;
    }
    return false;
  }

  static String reason(Throwable t) {
    String m = t.getMessage();
    return t.getClass().getSimpleName() + (m == null ? "" : ": " + Fmt.cut(m.split("\\r?\\n")[0], 160));
  }

  // =====================================================================================================
  // Helper classes
  // =====================================================================================================

  /** Thrown to stop a tool quietly (user cancelled, or a precondition is not met). */
  static final class Cancel extends RuntimeException {
    private static final long serialVersionUID = 1L;

    Cancel(String msg) { super(msg); }
  }

  /** Output-window logger that also keeps the text of the running tool for the result dialog. */
  static final class Log {
    final Simulation sim;
    final StringBuilder buf = new StringBuilder();

    Log(Simulation sim) { this.sim = sim; }

    void line(String s) {
      buf.append(s).append('\n');
      try { sim.println("[SimKit] " + s); } catch (Throwable t) { System.out.println("[SimKit] " + s); }
    }

    void na(String what, Throwable t) { line("  " + what + ": n/a (" + reason(t) + ")"); }

    String take() {
      String s = buf.toString();
      buf.setLength(0);
      return s;
    }
  }

  /** Environment: headless detection, batch action, user name. */
  static final class Env {
    static String action() {
      String a = System.getProperty("simkit.action");
      if (a == null || a.trim().isEmpty()) a = System.getenv("SIMKIT_ACTION");
      return a == null ? "" : a.trim().toLowerCase(Locale.ROOT);
    }

    /** Batch if an action is given, if Java is headless, if the process was started with "-batch", or if no
     *  window of this JVM is visible (the STAR-CCM+ GUI client is a Swing application, so a played macro sees
     *  its main window; a batch run has none). Env SIMKIT_GUI=1 forces the menu if a heuristic is wrong. */
    static String headlessReason() {
      if (!action().isEmpty()) return "simkit.action / SIMKIT_ACTION is set";
      if ("1".equals(System.getenv("SIMKIT_GUI"))) return null;
      try { if (GraphicsEnvironment.isHeadless()) return "Java is headless"; } catch (Throwable t) { return "no display"; }
      try {
        String cmd = ProcessHandle.current().info().commandLine().orElse("");
        if (Pattern.compile("\\s-batch(\\s|$)").matcher(cmd).find()) return "process started with -batch";
      } catch (Throwable t) { /* unknown */ }
      try {
        for (java.awt.Frame f : java.awt.Frame.getFrames()) if (f.isVisible()) return null;
        return "no visible window in this JVM (set env SIMKIT_GUI=1 to force the menu)";
      } catch (Throwable t) {
        return null;
      }
    }

    static String user() { return System.getProperty("user.name", "unknown"); }
  }

  /** File system: share layout, write guard, small read/write helpers. */
  static final class Fs {
    /** True on Windows (10/11), false on Linux (RHEL 7/9). */
    static final boolean WINDOWS = java.io.File.separatorChar == '\\';

    /** chmod u+x,g+x on POSIX file systems (Linux); no-op on Windows or on a mount without POSIX permissions. */
    static void makeExecutable(Path p) {
      try {
        Set<java.nio.file.attribute.PosixFilePermission> perm = new java.util.HashSet<>(Files.getPosixFilePermissions(p));
        perm.add(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE);
        perm.add(java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE);
        Files.setPosixFilePermissions(p, perm);
      } catch (Throwable t) { /* Windows, or a CIFS mount without POSIX permissions: run it with 'bash run_x.sh' */ }
    }

    static Path share() {
      String s = System.getenv("SIMKIT_SHARE");
      if (s == null || s.trim().isEmpty()) s = System.getProperty("simkit.share", SHARE_ROOT);
      return Paths.get(s.trim());
    }

    static Path kit() { return share().resolve("_SIMKIT"); }
    static Path config(String file) { return kit().resolve("config").resolve(file); }
    static Path projectsDir() { return share().resolve("PROJECTS"); }

    /** Comparison key of a path: case-insensitive on Windows only (Linux file systems are case-sensitive). */
    static String key(Path p) {
      String s = p.toAbsolutePath().normalize().toString();
      return java.io.File.separatorChar == '\\' ? s.toLowerCase(Locale.ROOT) : s;
    }
    static boolean same(Path a, Path b) { return key(a).equals(key(b)); }

    /** All writes go through here: only below <SHARE>\PROJECTS is allowed. */
    static Path guard(Path p) throws IOException { return guard(p, false); }

    /** Write guard. allowToolkitWrite additionally permits <SHARE>\_SIMKIT; it is used ONLY by the owner tool
     *  "Snapshot baseline" after an explicit owner confirmation. */
    static Path guard(Path p, boolean allowToolkitWrite) throws IOException {
      if (under(p, projectsDir()) || (allowToolkitWrite && under(p, kit()))) return p.toAbsolutePath().normalize();
      throw new IOException("refused to write outside " + projectsDir() + (allowToolkitWrite ? " and " + kit() : "")
          + ": " + p);
    }

    static boolean under(Path p, Path root) {
      String r = key(root), sep = java.io.File.separator;
      return key(p).startsWith(r.endsWith(sep) ? r : r + sep);
    }

    static void write(Path p, String text) throws IOException { write(p, text, false); }

    static void write(Path p, String text, boolean allowToolkitWrite) throws IOException {
      Path g = guard(p, allowToolkitWrite);
      Files.createDirectories(g.getParent());
      Files.write(g, text.getBytes(StandardCharsets.UTF_8));
    }

    static void mkdirs(Path p) throws IOException { Files.createDirectories(guard(p)); }

    static String read(Path p) throws IOException {
      String s = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
      return s.startsWith("\uFEFF") ? s.substring(1) : s;   // UTF-8 BOM (escaped: the source stays ASCII)
    }

    /** Directory entries matching a glob, sorted; empty if the directory does not exist. */
    static List<Path> list(Path dir, String glob) {
      List<Path> out = new ArrayList<>();
      if (!Files.isDirectory(dir)) return out;
      try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, glob)) {
        for (Path p : ds) out.add(p);
      } catch (IOException e) { /* treat as empty */ }
      Collections.sort(out);
      return out;
    }

    static String fileSafe(String s) { return s.replaceAll("[^A-Za-z0-9._-]+", "_"); }
  }

  /** CSV (RFC 4180 style): fields with comma, quote or newline are quoted; UTF-8 without BOM. */
  static final class Csv {
    static String q(String s) {
      if (s == null) s = "";
      boolean quote = s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r");
      return quote ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

    static String row(String[] cells) {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < cells.length; i++) sb.append(i == 0 ? "" : ",").append(q(cells[i]));
      return sb.append("\r\n").toString();
    }

    static void write(Path p, String[] header, List<String[]> rows) throws IOException { write(p, header, rows, false); }

    static void write(Path p, String[] header, List<String[]> rows, boolean allowToolkitWrite) throws IOException {
      StringBuilder sb = new StringBuilder(row(header));
      for (String[] r : rows) sb.append(row(r));
      Fs.write(p, sb.toString(), allowToolkitWrite);
    }

    static List<String[]> parse(String s) {
      List<String[]> out = new ArrayList<>();
      List<String> row = new ArrayList<>();
      StringBuilder f = new StringBuilder();
      boolean inQ = false;
      for (int i = 0; i < s.length(); i++) {
        char c = s.charAt(i);
        if (inQ) {
          if (c != '"') f.append(c);
          else if (i + 1 < s.length() && s.charAt(i + 1) == '"') { f.append('"'); i++; }
          else inQ = false;
        } else if (c == '"') {
          inQ = true;
        } else if (c == ',') {
          row.add(f.toString());
          f.setLength(0);
        } else if (c == '\n' || c == '\r') {
          if (c == '\r' && i + 1 < s.length() && s.charAt(i + 1) == '\n') i++;
          row.add(f.toString());
          f.setLength(0);
          addRow(out, row);
          row = new ArrayList<>();
        } else {
          f.append(c);
        }
      }
      if (f.length() > 0 || !row.isEmpty()) { row.add(f.toString()); addRow(out, row); }
      return out;
    }

    static void addRow(List<String[]> out, List<String> row) {
      if (row.size() == 1 && row.get(0).trim().isEmpty()) return;   // blank line
      out.add(row.toArray(new String[0]));
    }
  }

  /** Formatting helpers. */
  static final class Fmt {
    static String ts() { return new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()); }
    static String iso() { return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()); }
    static String two(int v) { return String.format("%02d", v); }
    static String num(double v) { return String.format(Locale.ROOT, "%.6g", v); }
    static String oneLine(String s) { return s == null ? "" : s.replaceAll("[\\r\\n\\t]+", " ").trim(); }
    static String cut(String s, int n) { return s == null ? "" : s.length() <= n ? s : s.substring(0, n - 3) + "..."; }

    static double parse(String s) {
      try { return s == null ? Double.NaN : Double.parseDouble(s.trim()); } catch (NumberFormatException e) { return Double.NaN; }
    }

    /** Value of a nullable number for display: "n/a" or %.6g. */
    static String num(Double v) { return v == null ? "n/a" : num(v.doubleValue()); }

    /** Literal for a definition (15 significant digits, no trailing zeros): 0.0001, 5, 1e-09, 101325. */
    static String lit(double v) {
      if (Double.isNaN(v) || Double.isInfinite(v)) return String.valueOf(v);
      if (v == Math.rint(v) && Math.abs(v) < 1e15) return String.valueOf((long) v);
      String s = String.format(Locale.ROOT, "%.15g", v);
      String exp = "";
      int e = s.indexOf('e');
      if (e >= 0) { exp = s.substring(e); s = s.substring(0, e); }
      if (s.indexOf('.') >= 0) s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
      return s + exp;
    }
  }

  static final class Html {
    static String esc(String s) {
      return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
  }

  /** Swing dialogs (interactive mode only). Cancel/close throws Cancel. */
  static final class Ui {
    /** Opens a URL in the default browser: java.awt.Desktop (Windows, GNOME), else xdg-open (RHEL without
     *  Desktop support, e.g. some X11 / VNC sessions), else the OS "start" on Windows. */
    static void browse(String url) throws Exception {
      URI u = new URI(url);
      try {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
          Desktop.getDesktop().browse(u);
          return;
        }
      } catch (Throwable t) { /* fall through to the OS command */ }
      String[] cmd = Fs.WINDOWS ? new String[] {"rundll32", "url.dll,FileProtocolHandler", url}
          : new String[] {"xdg-open", url};
      new ProcessBuilder(cmd).redirectErrorStream(true).start();
    }

    static void showText(String title, String text) {
      JOptionPane.showMessageDialog(null, scroll(text), title, JOptionPane.INFORMATION_MESSAGE);
    }

    static boolean confirm(String title, String msg) {
      return JOptionPane.showConfirmDialog(null, msg, title, JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION;
    }

    static boolean confirmText(String title, String question, String body) {
      JPanel p = new JPanel(new BorderLayout(4, 4));
      p.add(new JLabel(question), BorderLayout.NORTH);
      p.add(scroll(body), BorderLayout.CENTER);
      return JOptionPane.showConfirmDialog(null, p, title, JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION;
    }

    static String choose(String title, String msg, String[] options, String def) {
      if (options.length == 0) throw new Cancel("nothing to choose from");
      Object r = JOptionPane.showInputDialog(null, msg, title, JOptionPane.QUESTION_MESSAGE, null, options,
          def != null ? def : options[0]);
      if (r == null) throw new Cancel(null);
      return (String) r;
    }

    static String text(String title, String msg, String def) {
      Object r = JOptionPane.showInputDialog(null, msg, title, JOptionPane.QUESTION_MESSAGE, null, null, def);
      if (r == null) throw new Cancel(null);
      return String.valueOf(r).trim();
    }

    static int option(String title, String msg, String[] opts) {
      int r = JOptionPane.showOptionDialog(null, msg, title, JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
          null, opts, opts[0]);
      if (r < 0) throw new Cancel(null);
      return r;
    }

    static int[] pick(String title, String msg, String[] items, boolean multi) {
      JList<String> list = new JList<>(items);
      list.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
      list.setSelectionMode(multi ? ListSelectionModel.MULTIPLE_INTERVAL_SELECTION : ListSelectionModel.SINGLE_SELECTION);
      if (multi) list.setSelectionInterval(0, items.length - 1); else list.setSelectedIndex(0);
      JScrollPane sp = new JScrollPane(list);
      sp.setPreferredSize(new java.awt.Dimension(820, 360));
      JPanel p = new JPanel(new BorderLayout(4, 4));
      p.add(new JLabel(msg), BorderLayout.NORTH);
      p.add(sp, BorderLayout.CENTER);
      int r = JOptionPane.showConfirmDialog(null, p, title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
      if (r != JOptionPane.OK_OPTION || list.isSelectionEmpty()) throw new Cancel(null);
      return list.getSelectedIndices();
    }

    static Path chooseFile(Path dir) {
      JFileChooser fc = new JFileChooser(dir.toFile());
      if (fc.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) throw new Cancel(null);
      return fc.getSelectedFile().toPath();
    }

    static JScrollPane scroll(String text) {
      JTextArea ta = new JTextArea(text);
      ta.setEditable(false);
      ta.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
      JScrollPane sp = new JScrollPane(ta);
      sp.setPreferredSize(new java.awt.Dimension(940, 480));
      return sp;
    }
  }

  /** Reflection for read-only getters whose 2402 name is not certain (the recorder never records getters). */
  static final class Reflect {
    /** Calls a public method by name with matching argument types; null on any failure or if not found. */
    static Object tryCall(Object target, String method, Object... args) {
      if (target == null) return null;
      for (java.lang.reflect.Method m : target.getClass().getMethods()) {
        if (!m.getName().equals(method) || m.getParameterCount() != args.length) continue;
        Class<?>[] pt = m.getParameterTypes();
        boolean ok = true;
        for (int i = 0; i < pt.length; i++) if (pt[i].isPrimitive() || !pt[i].isInstance(args[i])) ok = false;
        if (!ok) continue;
        try {
          m.setAccessible(true);
          return m.invoke(target, args);
        } catch (Throwable t) {
          return null;
        }
      }
      return null;
    }

    /** Public method 'name' whose parameters accept 'args' (boxed values for primitive parameters); null if none. */
    static java.lang.reflect.Method find(Class<?> c, String name, Object[] args) {
      for (java.lang.reflect.Method m : c.getMethods()) {
        if (!m.getName().equals(name) || m.getParameterCount() != args.length) continue;
        Class<?>[] pt = m.getParameterTypes();
        boolean ok = true;
        for (int i = 0; i < pt.length && ok; i++) ok = accepts(pt[i], args[i]);
        if (ok) return m;
      }
      return null;
    }

    static boolean accepts(Class<?> p, Object a) {
      if (!p.isPrimitive()) return a == null || p.isInstance(a);
      if (a == null) return false;
      return (p == double.class && a instanceof Double) || (p == int.class && a instanceof Integer)
          || (p == boolean.class && a instanceof Boolean) || (p == long.class && a instanceof Long)
          || (p == float.class && a instanceof Float);
    }

    /** True if 'o' has a public method 'name' with exactly these parameter types (or supertypes of them). */
    static boolean has(Object o, String name, Class<?>... types) {
      if (o == null) return false;
      for (java.lang.reflect.Method m : o.getClass().getMethods()) {
        if (!m.getName().equals(name) || m.getParameterCount() != types.length) continue;
        Class<?>[] pt = m.getParameterTypes();
        boolean ok = true;
        for (int i = 0; i < pt.length && ok; i++) ok = pt[i].isAssignableFrom(types[i]);
        if (ok) return true;
      }
      return false;
    }

    /** Calls a public method like tryCall, but throws with a readable reason instead of returning null:
     *  NoSuchMethodException "Class.name/argc", or the exception thrown inside the method. */
    static Object invoke(Object target, String name, Object... args) throws Exception {
      if (target == null) throw new IllegalStateException("no object to call " + name + "() on");
      java.lang.reflect.Method m = find(target.getClass(), name, args);
      if (m == null) throw new NoSuchMethodException(target.getClass().getSimpleName() + "." + name + "/" + args.length);
      try {
        try {
          return m.invoke(target, args);
        } catch (IllegalAccessException e) {   // public method of a non-public implementation class
          m.setAccessible(true);
          return m.invoke(target, args);
        }
      } catch (java.lang.reflect.InvocationTargetException e) {
        Throwable c = e.getCause() == null ? e : e.getCause();
        throw c instanceof Exception ? (Exception) c : new RuntimeException(c);
      }
    }

    /** Class by fully qualified name (macro class loader first, then the context loader); null if not found. */
    static Class<?> cls(String name) {
      try { return Class.forName(name, false, SimKit.class.getClassLoader()); } catch (Throwable t) { /* next */ }
      try {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl != null) return Class.forName(name, false, cl);
      } catch (Throwable t) { /* not found */ }
      return null;
    }

    /** A Number result as Double; null otherwise. */
    static Double num(Object o) { return o instanceof Number ? Double.valueOf(((Number) o).doubleValue()) : null; }

    /** Elements of a Collection, Iterable or array result. */
    static List<Object> items(Object res) {
      List<Object> out = new ArrayList<>();
      if (res instanceof Iterable) for (Object o : (Iterable<?>) res) out.add(o);
      else if (res instanceof Object[]) out.addAll(Arrays.asList((Object[]) res));
      else if (res != null) out.add(res);
      return out;
    }
  }
}
