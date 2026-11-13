// SimKit.java -- SimKit 0.3 (2026-11-11) -- STAR-CCM+ 2402 and later, Windows 10/11 and Linux RHEL 7/9.
//
// Team helper for STAR-CCM+ cases. It keeps the native STAR-CCM+ workflow; it only adds:
//   * Save new version: not a case yet -> creates the case folder and <CASEID>[_<label>]_v01.sim (number automatic);
//                     a case -> keeps the open file as it is and continues in <CASEID>[_<label>]_v<nn+1>.sim (Enter),
//                     or starts a new case from it; records the version it is based on, the set-up and the changes.
//                     The normal STAR-CCM+ Save (Ctrl+S) keeps saving the current version file as usual.
//   * Check         : a short list of things that would spoil a run (PASS / WARN / FAIL); changes nothing
//   * Export results: monitor plots to CSV, scenes to PNG, report values to CSV in the case results folder
//   Other: Calculators (lib/generic/SimKitCalc.java), Compare versions, Case tracker, Guideline, About, ...
//   Nothing in the sim is renamed, re-organised or parameterised by SimKit. The label in the file name is free
//   text chosen by the user (e.g. cold, react, mesh2) and is optional.
//
// Where things are: this file sits at the SimKit ROOT (one shared folder; lib/, config/, buttons/ below it). The
//   root is found from this file's own location (STAR-CCM+ resolvePath), so no environment variable or path has
//   to be set. CASES are wherever the user wants: New case asks for a folder and creates
//   <folder>/<CASEID>/{sim,results,record} there.
// Toolbar buttons: buttons/SimKit_<Tool>.java run one tool directly (no menu); see buttons/README.txt.
// Non-blocking: the SimKit window floats next to STAR-CCM+ and the macro ends at once, so the user keeps working
//   on the model. A tool runs in a background thread (STAR-CCM+ calls as in a Simulation Assistant); only its short
//   questions wait for an answer; results open in floating windows.
// Batch: starccm+ -batch SimKit.java <sim>  writes the set-up record and exports results (no dialog);
//   SIMKIT_ACTION=check | record | export runs one of them only.
//
// Conventions: every STAR-CCM+ call that may differ between releases runs inside try/catch and logs
// "n/a (<reason>)" instead of failing. SimKit writes only inside the folder that holds the case folders
// (Fs.guard) and never inside the kit. Nothing is ever deleted.
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
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

// Some STAR-CCM+ calls used here are marked deprecated in 2606 but still work, and are kept so the kit also runs on
// 2402: the warnings are silenced so that STAR-CCM+ does not open its Compile tab every time the macro is played.
@SuppressWarnings({"deprecation", "unchecked", "rawtypes"})
public class SimKit extends StarMacro {

  // =====================================================================================================
  // Constants (edit here)
  // =====================================================================================================

  static final String VERSION = "SimKit 0.3 (2026-11-11)";
  /** Kit files used by SimKit, relative to the SimKit root ('/' on every OS). */
  static final String CONFIG_DIR = "config", VERSION_FILE = "VERSION.txt", CALC_MACRO = "lib/generic/SimKitCalc.java";
  /** Scene export resolution in pixels. */
  static final int IMG_W = 1600, IMG_H = 900;
  /** Sub-folders created in every case folder. */
  static final String[] CASE_SUBDIRS = {"sim", "results/csv", "results/img", "record"};
  static final String[] AUDIT_HEADER = {"section", "object_path", "property", "value"};
  static final String[] AUDIT_SECTIONS = {"VERSION", "PARAMETERS", "CONTINUA", "REGIONS", "BOUNDARIES",
      "INTERFACES", "MESH", "SOLVERS", "STOPPING", "REPORTS"};
  static final String[] DIFF_HEADER = {"change", "section", "object_path", "property", "old_value", "new_value"};
  static final String[] CHECK_HEADER = {"level", "check", "detail"};
  static final String[] CASE_LIST_COLS = {"case_id", "description", "configuration", "parent_case", "latest_file",
      "last_saved", "created", "created_by"};
  /** Name of the case list written in every folder that holds case folders. */
  static final String CASE_LIST = "case_list.csv";
  /** Key results of a case (written by Export results, read by the case list): <case>/record/key_results.csv */
  static final String KEY_RESULTS = "key_results.csv";
  /** Result columns of the case list (from key_results.csv; unsteady = mean over the averaging window). */
  static final String[] KEY_COLS = {"r_mdot_in", "r_mdot_net", "r_p_in_avg", "r_p_out_avg", "r_T_in_mfavg",
      "r_T_out_mfavg", "r_Q_wall", "r_yplus_wall_max", "r_Co_max"};

  /** Default STAR-CCM+ names ("Report 3", "Plane Section 2", "Default", ...): Check lists them as a WARN. */
  static final String DEFAULT_NAME_REGEX = "^(Report|Plot|Scene|Monitor|Plane Section|Line Probe|Point Probe|"
      + "Isosurface|Threshold|User Field Function|Parameter|Scalar|Vector|Body|Default|Region|Interface|"
      + "Continuum|Physics|Mesh|Automated Mesh|Volumetric Control|Surface Control|Block|Cylinder|Sphere|Cone)( [0-9]+)?$"
      + "|^[A-Z][A-Za-z]*( [A-Z][A-Za-z]*)* [0-9]+$";   // any "Capitalised Words <n>" (Maximum 1, Surface Average 2)

  /** Built-in naming rules, used when config/naming_rules.csv is missing: object, pattern, example, explanation. */
  static final String[][] DEFAULT_NAMING_RULES = {
      {"part", "^[A-Z]{2,5}_[a-z][A-Za-z0-9]*(_[0-9]+)?$", "BRN_stator", "<ZONE>_<component>"},
      {"surface", "^(IN|OUT|OPEN|WALL|SYM|IF)_[A-Za-z0-9_]+$", "WALL_vane_ps", "<BC>_<feature>"}};
  static final String[][] DEFAULT_PROJECTS = {{"ISO", "ISO 2685 burner", ""}};

  static final String[] TOOL_NAMES = {"", "New case", "Save new version", "Check", "Export results",
      "Calculators", "Compare versions", "Case tracker", "Guideline", "About", "Standard reports", "",
      "Mesh study", "Standard scenes", "Case report"};
  static final String[] TOOL_HELP = {"",
      "Creates the case folder and saves this sim as <CASEID>_v01.sim (from a case: a new case with this one as parent)",
      "Keeps this file as it is and continues in the next version (_v02, _v03, ...), or starts a new case from it. "
          + "Not a case yet: creates the case folder and saves v01. Your normal Save (Ctrl+S) keeps saving the current file",
      "Lists what would spoil a run (mesh, continua, stopping criteria, names); changes nothing",
      "Writes monitor plots (CSV), scenes (PNG) and report values (CSV) to the case results folder",
      "y+ and prism layers, Reynolds, time step, properties, ... (values ready to paste in STAR-CCM+)",
      "Differences between this version and the previous one, the parent case or any record",
      "Every version of every case in the folder: ID, comment, status, based on, description; set a status "
          + "and write comments (saved in the case folders); export to CSV for Google Sheets",
      "Opens the team guideline", "SimKit version, share and STAR-CCM+ release",
      "Mass balance, inlet/outlet pressure and temperature, wall heat flow, y+, Courant: reports, "
          + "monitors and plots built from the IN_/OUT_/OPEN_/WALL_ surface names (you choose; nothing is replaced)",
      "",
      "Parametrize only: the absolute mesh sizes you tick follow one factor p_meshScale (1 = today's mesh). "
          + "Mesh levels: one case per mesh you define (coarse, medium, fine, ...; sizes per level editable) for a "
          + "sensitivity study or GCI",
      "Same views for every case: y+ and temperature on the WALL_ surfaces, velocity, pressure, temperature and mesh "
          + "on a mid-plane (you choose; existing scenes are never changed). Export results saves them as images",
      "One HTML page for the current version: identity, what changed, Check, key results, "
          + "averages, images (in the case record folder, opened in your browser)"};

  /** <CASEID>[_<label>]_v<nn>.sim ; label = free text, letters/digits, starts with a letter (old STAGE names fit). */
  /** <CASEID>[_<label>]_v<nn>.sim with CASEID = <PROJECT>-<NNN> (e.g. ISO-007). Cases made with SimKit 0.3 before
   *  this change are <PROJECT>-<CFG>-<NNN> (ISO-CF-001) and are still recognised (group 3 = old configuration code). */
  static final Pattern SIM_NAME = Pattern.compile(
      "^(([A-Z][A-Z0-9]{1,9})-(?:([A-Z0-9]{2,3})-)?([0-9]{3}))(?:_([A-Za-z][A-Za-z0-9]{0,19}))?_v([0-9]{2,3})\\.sim$");
  static final Pattern CASE_ID = Pattern.compile("^([A-Z][A-Z0-9]{1,9})-(?:([A-Z0-9]{2,3})-)?([0-9]{3})$");
  static final Pattern PROJECT = Pattern.compile("^[A-Z][A-Z0-9]{1,9}$");
  static final Pattern LABEL = Pattern.compile("^[A-Za-z][A-Za-z0-9]{0,19}$");

  // =====================================================================================================
  // State and entry point
  // =====================================================================================================

  Simulation sim;
  Log log;
  boolean gui;
  final Map<String, Pattern> rxCache = new HashMap<>();

  @Override
  public void execute() {
    int preloaded = preloadOwnClasses(getClass());   // before anything else: see preloadOwnClasses
    sim = getActiveSimulation();
    log = new Log(sim);
    Fs.ROOT = Paths.get(resolvePath(".")).toAbsolutePath().normalize();   // this file is <root>/SimKit.java
    String batchReason = Env.headlessReason();
    gui = batchReason == null;
    log.line(VERSION + " | kit " + Fs.kit() + " | " + (gui ? "interactive" : "batch: " + batchReason)
        + " | classes preloaded: " + preloaded);
    int direct = Env.directTool();   // set by a toolbar button macro (buttons/SimKit_<Tool>.java)
    if (direct > 0) {
      if (gui) runTool(direct);
      else runBatchTool(direct);
      return;
    }
    if (!gui) {
      runBatch();
      return;
    }
    openWindow();
    log.line("SimKit window open (it floats next to STAR-CCM+; the model stays free to use).");
  }

  /** STAR-CCM+ compiles a macro into a temporary folder and DELETES the class files when execute() returns. The
   *  SimKit windows live on after that (non-blocking), so every class of this macro is loaded into memory now,
   *  while the files still exist. Returns the number of classes loaded (-1 if the folder could not be read). */
  static int preloadOwnClasses(Class<?> self) {
    try {
      java.nio.file.Path dir = java.nio.file.Paths.get(self.getProtectionDomain().getCodeSource().getLocation().toURI());
      String pkg = self.getPackage() == null ? "" : self.getPackage().getName();
      if (!pkg.isEmpty()) dir = dir.resolve(pkg.replace('.', '/'));
      String simple = self.getSimpleName();
      int n = 0;
      try (java.nio.file.DirectoryStream<java.nio.file.Path> ds = java.nio.file.Files.newDirectoryStream(dir, simple + "*.class")) {
        for (java.nio.file.Path f : ds) {
          String fn = f.getFileName().toString();
          String cn = fn.substring(0, fn.length() - 6);
          if (!cn.equals(simple) && !cn.startsWith(simple + "$")) continue;
          Class.forName((pkg.isEmpty() ? "" : pkg + ".") + cn, false, self.getClassLoader());
          n++;
        }
      }
      return n;
    } catch (Throwable t) {
      return -1;
    }
  }

  /** Batch mode: action from -Dsimkit.action or env SIMKIT_ACTION; default = record + export. */
  void runBatch() {
    String action = Env.action();
    log.line("batch action: " + (action.isEmpty() ? "(default) record + export" : action));
    try {
      switch (action) {
        case "check": case "precheck": toolCheck(); break;
        case "record": case "audit": writeAudit(requireCase()); break;
        case "export": toolExport(); break;
        case "reports": {   // test action: create the standard reports and save the sim in place
          CaseInfo c = requireCase();
          toolStandardReports();
          sim.saveState(Fs.guard(c.simFile).toString());
          log.line("saved " + c.simFile);
          break;
        }
        case "scenes": {   // test action: create the standard scenes and save the sim in place
          CaseInfo c = requireCase();
          toolStandardScenes();
          sim.saveState(Fs.guard(c.simFile).toString());
          log.line("saved " + c.simFile);
          break;
        }
        case "casereport": toolCaseReport(); break;
        case "tracker": toolTracker(); break;
        case "newversion": toolSaveVersion(); break;   // New version (asks nothing, so it also runs in batch)
        case "meshstudy": toolMeshStudy(); break;   // test action: levels coarse (2) and medium (1), meshed
        case "meshparams": {   // test action: parametrize the mesh (no prism layers) and save the sim in place
          CaseInfo c = requireCase();
          toolParametrizeMesh();
          sim.saveState(Fs.guard(c.simFile).toString());
          log.line("saved " + c.simFile);
          break;
        }
        default:   // e.g. after a run on the cluster: starccm+ -batch run,SimKit.java <sim>
          if (!action.isEmpty()) log.line("unknown action '" + action + "': running the default record + export");
          writeAudit(requireCase());
          toolExport();
      }
    } catch (Cancel c) {
      log.line("stopped: " + c.getMessage());
    } catch (Throwable t) {
      log.line("ERROR: " + reason(t));
    }
  }

  /** A toolbar button in batch: only the tools without dialogs can run. */
  void runBatchTool(int id) {
    log.line("button " + TOOL_NAMES[id] + " (batch)");
    try {
      if (id == 3) toolCheck();
      else if (id == 4) toolExport();
      else log.line(TOOL_NAMES[id] + " needs the STAR-CCM+ window: nothing done in batch");
    } catch (Cancel c) {
      log.line("stopped: " + c.getMessage());
    } catch (Throwable t) {
      log.line("ERROR: " + reason(t));
    }
  }

  /** Tools whose text is the result: their window opens. The others report on the status line (Details... shows
   *  their text); an error always opens the window. Everything is also in the STAR-CCM+ Output window. */
  static final Set<Integer> SHOWS_TEXT = new HashSet<>(Arrays.asList(3, 6, 9));
  volatile String summary;                         // one-line outcome of the tool (set by the tool)
  volatile String lastText, lastTitle;             // full text of the last tool, for Details...
  volatile String lastStatus;
  volatile int lastLevel;                          // 0 done, 1 cancelled / skipped items, 2 error

  /** Runs one tool; any failure is logged, never thrown into the session. */
  void runTool(int id) {
    log.take();
    summary = null;
    log.line("=== " + TOOL_NAMES[id] + " ===");
    String stop = null;
    boolean error = false;
    try {
      switch (id) {
        case 1: toolNewCase(); break;
        case 2: toolSave(); break;
        case 3: toolCheck(); break;
        case 4: toolExport(); break;
        case 5: toolCalc(); break;
        case 6: toolCompare(); break;
        case 7: toolTracker(); break;
        case 8: toolLinks(); break;
        case 9: toolAbout(); break;
        case 10: toolStandardReports(); break;
        case 12: toolMeshStudy(); break;
        case 13: toolStandardScenes(); break;
        case 14: toolCaseReport(); break;
        default: log.line("unknown tool " + id);
      }
    } catch (Cancel c) {
      stop = c.getMessage() == null ? "" : c.getMessage();
      log.line("cancelled" + (stop.isEmpty() ? "" : ": " + stop));
    } catch (Throwable t) {
      error = true;
      stop = reason(t);
      log.line("ERROR: " + stop + "  (tool stopped; see the lines above for what was done)");
    }
    String text = log.take();
    lastTitle = TOOL_NAMES[id] + "  -  " + simName();
    lastText = text;
    boolean skipped = text.contains(": n/a (");
    if (error) {
      lastLevel = 2;
      lastStatus = TOOL_NAMES[id] + " stopped: " + Fmt.cut(stop, 90);
    } else if (stop != null) {
      lastLevel = 1;
      lastStatus = "Cancelled: " + TOOL_NAMES[id] + (stop.isEmpty() ? "" : "  -  " + Fmt.cut(stop, 90));
    } else {
      lastLevel = skipped ? 1 : 0;
      lastStatus = "Done: " + (summary != null ? summary : TOOL_NAMES[id]) + (skipped ? "  (some items skipped: Details)" : "");
    }
    if (id != 5 && (error || SHOWS_TEXT.contains(id))) Ui.showText(lastTitle, text);
  }

  /** Shows the outcome of the last tool on the status line (amber: cancelled or items skipped, red: error). */
  void showStatus() {
    if (statusLine == null) return;
    statusLine.setText(lastStatus == null ? "Ready." : lastStatus);
    statusLine.setToolTipText(lastStatus);
    statusLine.setForeground(lastLevel == 2 ? new java.awt.Color(0xB0, 0x20, 0x20)
        : lastLevel == 1 ? new java.awt.Color(0x8A, 0x5A, 0x00) : new java.awt.Color(0x2E, 0x6B, 0x2E));
    if (detailsButton != null) detailsButton.setEnabled(lastText != null);
  }

  /** Records the one-line outcome of a tool (shown on the status line) and logs it. */
  void result(String s) {
    summary = s;
  }

  // =====================================================================================================
  // Menu window
  // =====================================================================================================

  /** Key under which the open SimKit window is kept for this STAR-CCM+ session (one window at a time). */
  static final String WINDOW_KEY = "simkit.window";
  final List<JButton> toolButtons = new ArrayList<>();
  volatile boolean busy;

  /** Opens the floating SimKit window (non-modal, attached to the STAR-CCM+ window) and returns at once.
   *  A window left open by an earlier play is closed first (it may belong to another simulation). */
  /** Main window layout: one column per stage of the work; each button with a one-line explanation under it. */
  static final Object[][] SECTIONS = {
      {"Case", new int[] {2, 7, 6}},
      {"Set-up", new int[] {3, 10, 13, 12}},
      {"Results", new int[] {4, 14}},
      {"Tools", new int[] {5, 8, 9}}};
  static final String[] TOOL_SHORT = {"",
      "Start a case folder (v01)", "Next version, or a new case", "Check before running", "Plots, images, values, averages",
      "y+, Re, time step, properties, GCI", "What changed in the set-up", "All versions, comments, status",
      "Team guideline", "Version and kit location", "Mass balance, p, T, y+ ...", "",
      "Parametrize, or mesh levels", "Same views for every case", "One page for reviews"};

  JLabel headTitle, headSub, headPath, statusLine;
  JButton detailsButton;
  /** Tools that need the open sim to be a SimKit case (greyed otherwise). */
  static final Set<Integer> NEEDS_CASE = new HashSet<>(Arrays.asList(4, 6, 14));
  final Map<Integer, JButton> buttonById = new HashMap<>();

  void openWindow() {
    Object old = System.getProperties().get(WINDOW_KEY);
    if (old instanceof java.awt.Window) ((java.awt.Window) old).dispose();
    final JDialog d = buildWindow();
    d.setLocationRelativeTo(Ui.owner());
    Env.track(d);
    d.addWindowListener(new java.awt.event.WindowAdapter() {
      @Override public void windowClosed(java.awt.event.WindowEvent e) {
        if (System.getProperties().get(WINDOW_KEY) == d) System.getProperties().remove(WINDOW_KEY);
      }
    });
    System.getProperties().put(WINDOW_KEY, d);
    d.setVisible(true);   // non-modal: returns at once
  }

  /** Builds the main window (not shown). */
  JDialog buildWindow() {
    final JDialog d = new JDialog(Ui.owner(), "SimKit  -  " + simName(), java.awt.Dialog.ModalityType.MODELESS);
    d.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
    JPanel root = new JPanel(new BorderLayout(0, 12));
    root.setBorder(BorderFactory.createEmptyBorder(14, 16, 10, 16));
    root.setBackground(java.awt.Color.WHITE);

    JPanel head = new JPanel(new GridLayout(0, 1, 0, 2));
    head.setOpaque(false);
    headTitle = new JLabel();
    headTitle.setFont(headTitle.getFont().deriveFont(Font.BOLD, 18f));
    headSub = new JLabel();
    headSub.setFont(headSub.getFont().deriveFont(Font.PLAIN, 13f));
    headPath = new JLabel();
    headPath.setFont(headPath.getFont().deriveFont(Font.PLAIN, 11f));
    headPath.setForeground(java.awt.Color.GRAY);
    head.add(headTitle);
    head.add(headSub);
    head.add(headPath);
    refreshHeader();
    root.add(head, BorderLayout.NORTH);

    JPanel cols = new JPanel(new GridLayout(1, SECTIONS.length, 14, 0));
    cols.setOpaque(false);
    for (Object[] sec : SECTIONS) {
      JPanel col = new JPanel();
      col.setOpaque(false);
      col.setLayout(new javax.swing.BoxLayout(col, javax.swing.BoxLayout.Y_AXIS));
      JLabel t = new JLabel(((String) sec[0]).toUpperCase(Locale.ROOT));
      t.setFont(t.getFont().deriveFont(Font.BOLD, 11f));
      t.setForeground(new java.awt.Color(0x55, 0x66, 0x77));
      t.setAlignmentX(0f);
      col.add(t);
      col.add(javax.swing.Box.createVerticalStrut(4));
      javax.swing.JSeparator line = new javax.swing.JSeparator();
      line.setAlignmentX(0f);
      line.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, 2));
      col.add(line);
      for (int id : (int[]) sec[1]) {
        col.add(javax.swing.Box.createVerticalStrut(8));
        JButton b = new JButton(TOOL_NAMES[id]);
        b.setFont(b.getFont().deriveFont(Font.BOLD, 13f));
        b.setAlignmentX(0f);
        b.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, 32));
        b.setPreferredSize(new java.awt.Dimension(190, 32));
        wire(b, id);
        col.add(b);
        JLabel sub = new JLabel(TOOL_SHORT[id]);
        sub.setFont(sub.getFont().deriveFont(Font.PLAIN, 11f));
        sub.setForeground(java.awt.Color.GRAY);
        sub.setAlignmentX(0f);
        sub.setBorder(BorderFactory.createEmptyBorder(2, 4, 0, 0));
        col.add(sub);
      }
      col.add(javax.swing.Box.createVerticalGlue());
      cols.add(col);
    }
    root.add(cols, BorderLayout.CENTER);

    statusLine = new JLabel("Ready. Hover over a button to see what it does.");
    statusLine.setForeground(java.awt.Color.GRAY);
    JButton close = new JButton("Close");
    close.addActionListener(e -> d.dispose());
    javax.swing.JCheckBox onTop = new javax.swing.JCheckBox("Keep in front of other apps", Env.onTop());
    onTop.setOpaque(false);
    onTop.setToolTipText("Keep the SimKit windows above every other window (remembered for next time). "
        + "Off: they stay above STAR-CCM+ only.");
    onTop.addActionListener(e -> Env.setOnTop(onTop.isSelected()));
    JPanel right = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 8, 0));
    right.setOpaque(false);
    detailsButton = new JButton("Details");
    detailsButton.setToolTipText("Full text of the last tool (it is also in the STAR-CCM+ Output window)");
    detailsButton.setEnabled(lastText != null);
    detailsButton.addActionListener(e -> { if (lastText != null) Ui.showText(lastTitle, lastText); });
    right.add(detailsButton);
    right.add(onTop);
    right.add(close);
    JPanel south = new JPanel(new BorderLayout());
    south.setOpaque(false);
    south.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, new java.awt.Color(0xDD, 0xDD, 0xDD)),
        BorderFactory.createEmptyBorder(8, 0, 0, 0)));
    south.add(statusLine, BorderLayout.WEST);
    south.add(right, BorderLayout.EAST);
    root.add(south, BorderLayout.SOUTH);
    refreshHeader();   // again now that the buttons exist (greys the case-only ones)
    d.setContentPane(root);
    d.pack();
    return d;
  }

  /** Header: case and version, description, file. */
  void refreshHeader() {
    if (headTitle == null) return;
    String path = sessionPath();
    CaseInfo c = caseOrNull();
    if (c != null) {
      headTitle.setText(c.caseId + "  \u00B7  v" + Fmt.two(c.version) + (c.label.isEmpty() ? "" : "  \u00B7  " + c.label));
      headTitle.setForeground(java.awt.Color.BLACK);
      String desc = readIdentity(c).getOrDefault("description", "");
      headSub.setText(desc.isEmpty() ? " " : Md.plain(desc));
      headPath.setText(path);
    } else {
      headTitle.setText("Not a SimKit case yet");
      headTitle.setForeground(new java.awt.Color(0x8A, 0x5A, 0x00));
      headSub.setText("Start with Save new version: it creates the case folder and saves this simulation as v01.");
      headPath.setText(path.isEmpty() ? "(simulation not saved yet)" : path);
    }
    for (int id : NEEDS_CASE) {   // greyed until the sim is a case
      JButton b = buttonById.get(id);
      if (b == null) continue;
      b.setEnabled(c != null && !busy);
      b.setToolTipText(c != null ? TOOL_HELP[id] : "Needs a SimKit case: use Save new version first. (" + TOOL_HELP[id] + ")");
    }
  }

  void wire(JButton b, int id) {
    buttonById.put(id, b);
    b.setToolTipText(TOOL_HELP[id]);
    b.addActionListener(e -> startTool(id));
    toolButtons.add(b);
  }

  /** Runs a tool in a background thread (STAR-CCM+ stays responsive); one tool at a time. */
  void startTool(int id) {
    if (busy) return;
    busy = true;
    for (JButton b : toolButtons) b.setEnabled(false);
    if (statusLine != null) statusLine.setText("Running: " + TOOL_NAMES[id] + " ...  (STAR-CCM+ stays usable)");
    Thread t = new Thread(() -> {
      try {
        runTool(id);
      } finally {
        javax.swing.SwingUtilities.invokeLater(() -> {
          busy = false;
          for (JButton b : toolButtons) b.setEnabled(true);
          refreshHeader();
          showStatus();
        });
      }
    }, "SimKit " + TOOL_NAMES[id]);
    t.setDaemon(true);
    t.start();
  }


  String simName() {
    try { return sim.getPresentationName(); } catch (Throwable t) { return "?"; }
  }

  /** Two-line description of the open sim and its case, shown at the top of the menu. */
  String contextLine() {
    String path = sessionPath();
    try {
      CaseInfo c = parseCase(path);
      return "Case " + c.caseId + (c.label.isEmpty() ? "" : "   label " + c.label) + "   version v" + Fmt.two(c.version)
          + "\n" + path;
    } catch (IllegalStateException e) {
      return "Not a SimKit case yet (" + e.getMessage() + "): use Save new version\n"
          + (path.isEmpty() ? "(unsaved sim)" : path);
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

  /** Identity of a case: <cases folder>/<CASEID>/sim/<CASEID>[_<label>]_v<nn>.sim. The cases folder is free. */
  static final class CaseInfo {
    String proj, cfg, caseId, label = "";
    int nnn, version;
    Path casesDir, caseDir, simFile;

    static CaseInfo of(Path casesDir, String proj, String cfg, int nnn, String label, int version) {
      CaseInfo c = new CaseInfo();
      c.proj = proj;
      c.cfg = cfg;
      c.nnn = nnn;
      c.caseId = proj + (cfg == null || cfg.isEmpty() ? "" : "-" + cfg) + "-" + String.format("%03d", nnn);
      c.casesDir = casesDir.toAbsolutePath().normalize();
      c.caseDir = c.casesDir.resolve(c.caseId);
      Fs.allow(c.casesDir);
      return c.withVersion(label, version);
    }

    /** Same case, another label/version (the sim file name follows). */
    CaseInfo withVersion(String newLabel, int newVersion) {
      CaseInfo c = new CaseInfo();
      c.proj = proj; c.cfg = cfg; c.nnn = nnn; c.caseId = caseId; c.casesDir = casesDir; c.caseDir = caseDir;
      c.label = newLabel == null ? "" : newLabel;
      c.version = newVersion;
      c.simFile = caseDir.resolve("sim").resolve(c.simBase() + ".sim");
      return c;
    }

    String simBase() { return caseId + (label.isEmpty() ? "" : "_" + label) + "_v" + Fmt.two(version); }
    Path sub(String rel) { return caseDir.resolve(rel); }
    Path auditFile() { return sub("record").resolve("setup_" + simBase() + ".csv"); }
    Path cardFile() { return sub("record").resolve("case_card_" + simBase() + ".html"); }
    Path identityFile() { return sub("sim").resolve("case_identity.txt"); }
  }

  /** Parses the case identity from a sim path (<any folder>/<CASEID>/sim/<file>.sim); throws with the reason. */
  static CaseInfo parseCase(String simPath) {
    if (simPath == null || simPath.trim().isEmpty()) throw new IllegalStateException("the sim has not been saved yet");
    Path f = Paths.get(simPath).toAbsolutePath().normalize();
    Matcher m = SIM_NAME.matcher(f.getFileName().toString());
    if (!m.matches()) throw new IllegalStateException("file name is not <CASEID>[_<label>]_v<nn>.sim");
    Path simDir = f.getParent(), caseDir = simDir == null ? null : simDir.getParent();
    if (caseDir == null || caseDir.getParent() == null || !simDir.getFileName().toString().equals("sim")
        || !caseDir.getFileName().toString().equals(m.group(1))) {
      throw new IllegalStateException("the file is not in a case folder " + m.group(1) + "/sim");
    }
    CaseInfo c = CaseInfo.of(caseDir.getParent(), m.group(2), m.group(3), Integer.parseInt(m.group(4)), m.group(5),
        Integer.parseInt(m.group(6)));
    c.simFile = f;
    return c;
  }

  /** Case of the open sim, or a Cancel with the reason and the remedy. */
  CaseInfo requireCase() {
    try {
      return parseCase(sessionPath());
    } catch (IllegalStateException e) {
      throw new Cancel("this needs a sim saved with Save new version (" + e.getMessage() + ")");
    }
  }

  CaseInfo caseOrNull() {
    try { return parseCase(sessionPath()); } catch (IllegalStateException e) { return null; }
  }

  /** Reads sim/case_identity.txt (key=value lines). Missing file -> empty map. */
  static Map<String, String> readIdentity(CaseInfo c) { return readIdentity(c.identityFile()); }

  static Map<String, String> readIdentity(Path file) {
    Map<String, String> kv = new LinkedHashMap<>();
    try {
      for (String line : Fs.read(file).split("\\r?\\n")) {
        int i = line.indexOf('=');
        if (i > 0 && !line.startsWith("#")) kv.put(line.substring(0, i).trim(), idDecode(line.substring(i + 1).trim()));
      }
    } catch (IOException e) {
      // no identity file yet
    }
    return kv;
  }

  /** A value for case_identity.txt (one line per key). Text over several lines (comments, descriptions) is kept as
   *  "ml:" + the lines joined by \n (a backslash is written \\); everything else as one line, as before. */
  static String idEncode(String v) {
    if (v == null) return "";
    String s = v.replace("\r\n", "\n").replace('\r', '\n');
    if (s.indexOf('\n') < 0 && !s.startsWith("ml:")) return Fmt.oneLine(s);
    s = s.replace('\t', ' ').replaceAll("[ ]+\n", "\n").replaceAll("^\n+|\n+$", "");
    if (s.indexOf('\n') < 0 && !s.startsWith("ml:")) return s.trim();
    return "ml:" + s.replace("\\", "\\\\").replace("\n", "\\n");
  }

  static String idDecode(String v) {
    if (v == null || !v.startsWith("ml:")) return v;
    StringBuilder sb = new StringBuilder();
    for (int i = 3; i < v.length(); i++) {
      char ch = v.charAt(i);
      if (ch == '\\' && i + 1 < v.length()) {
        char nx = v.charAt(++i);
        sb.append(nx == 'n' ? '\n' : nx);
      } else {
        sb.append(ch);
      }
    }
    return sb.toString();
  }

  /** The whole case_identity.txt for these keys and values. */
  static String identityText(Map<String, String> kv) {
    StringBuilder sb = new StringBuilder("# SimKit case identity -- written by " + VERSION + "; you may edit description\n");
    for (Map.Entry<String, String> e : kv.entrySet()) sb.append(e.getKey()).append('=').append(idEncode(e.getValue())).append('\n');
    return sb.toString();
  }

  static void writeIdentity(CaseInfo c, Map<String, String> kv) throws IOException {
    Fs.write(c.identityFile(), identityText(kv));
  }

  /** Creates the case folder in 'casesDir' (next free NNN there, safe against a parallel creation). */
  CaseInfo createCaseFolders(Path casesDir, String proj, String label) throws IOException {
    Fs.allow(casesDir);
    Fs.mkdirs(casesDir);
    for (int nnn = nextCaseNo(casesDir, proj); nnn <= 999; nnn++) {
      CaseInfo c = CaseInfo.of(casesDir, proj, null, nnn, label, 1);
      try {
        Files.createDirectory(Fs.guard(c.caseDir));
      } catch (FileAlreadyExistsException taken) {
        continue;
      }
      for (String sd : CASE_SUBDIRS) Fs.mkdirs(c.sub(sd));
      log.line("created case folder " + c.caseDir);
      return c;
    }
    throw new IOException("no free case number left in " + casesDir);
  }

  /** Next free NNN of a project in a cases folder, from the case folder names. */
  static int nextCaseNo(Path casesDir, String proj) {
    Pattern p = Pattern.compile("^" + Pattern.quote(proj) + "-(?:[A-Z0-9]{2,3}-)?([0-9]{3})$");   // also old ISO-CF-001
    int max = 0;
    for (Path d : Fs.list(casesDir, "*")) {
      Matcher m = p.matcher(d.getFileName().toString());
      if (m.matches() && Files.isDirectory(d)) max = Math.max(max, Integer.parseInt(m.group(1)));
    }
    return max + 1;
  }

  /** Cases folder proposed by New case / Case list: the current case's, else the last used, else the open sim's
   *  folder, else the user's home folder. */
  Path defaultCasesDir() {
    CaseInfo c = caseOrNull();
    if (c != null) return c.casesDir;
    String last = Env.pref("casesDir");
    if (last != null && Files.isDirectory(Paths.get(last))) return Paths.get(last);
    String p = sessionPath();
    if (!p.isEmpty() && Paths.get(p).getParent() != null) return Paths.get(p).toAbsolutePath().getParent();
    return Paths.get(System.getProperty("user.home"));
  }

  /** Next version number of a case: highest v<nn> of any file of the case in sim/ plus one. */
  static int nextVersion(CaseInfo c) {
    int max = c.version;
    for (Path f : Fs.list(c.sub("sim"), "*.sim")) {
      Matcher m = SIM_NAME.matcher(f.getFileName().toString());
      if (m.matches() && m.group(1).equals(c.caseId)) max = Math.max(max, Integer.parseInt(m.group(6)));
    }
    return max + 1;
  }

  static String checkLabel(String s) {
    String l = s == null ? "" : s.trim();
    if (l.isEmpty()) return "";
    if (!LABEL.matcher(l).matches() || l.matches("v[0-9]+")) {
      throw new Cancel("label '" + l + "': letters and digits only, starting with a letter, at most 20 characters "
          + "(e.g. cold, react, mesh2)");
    }
    return l;
  }

  // =====================================================================================================
  // New case / New version
  // =====================================================================================================

  /** New case. If the open sim is already a case, the new case gets it as parent (branch). */
  void toolNewCase() throws IOException {
    CaseInfo parent = caseOrNull();
    String sourceFile = sessionPath();   // the file this sim was opened from (if any): recorded as "based on"
    List<String[]> projects = projects();
    String[] projLabels = new String[projects.size()];
    for (int i = 0; i < projLabels.length; i++) projLabels[i] = projects.get(i)[0] + "  -  " + projects.get(i)[1];
    JComboBox<String> proj = new JComboBox<>(projLabels);
    proj.setEditable(true);   // a new project code can be typed
    JComboBox<String> cfg = new JComboBox<>();
    cfg.setEditable(true);    // free text; the list only suggests
    JTextField label = new JTextField(parent == null ? "" : parent.label, 12);
    JTextField desc = new JTextField(40);
    JTextField where = new JTextField(defaultCasesDir().toString(), 40);
    JButton browse = new JButton("Browse...");
    browse.addActionListener(e -> {
      JFileChooser fc = new JFileChooser(where.getText());
      fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
      fc.setDialogTitle("Folder that will hold the case folder");
      if (fc.showOpenDialog(where) == JFileChooser.APPROVE_OPTION) where.setText(fc.getSelectedFile().getPath());
    });
    JPanel wherePanel = new JPanel(new BorderLayout(4, 0));
    wherePanel.add(where, BorderLayout.CENTER);
    wherePanel.add(browse, BorderLayout.EAST);
    Path suggestDir = defaultCasesDir();
    Runnable fillCfg = () -> {
      Object typed = cfg.getEditor().getItem();
      cfg.removeAllItems();
      cfg.addItem("");
      Set<String> seen = new LinkedHashSet<>();
      int i = proj.getSelectedIndex();
      if (i >= 0 && i < projects.size()) for (String code : projects.get(i)[2].split(";")) if (!code.trim().isEmpty()) seen.add(code.trim());
      seen.addAll(usedConfigurations(suggestDir));
      for (String x : seen) cfg.addItem(x);
      if (typed != null) cfg.getEditor().setItem(typed);
    };
    proj.addActionListener(e -> fillCfg.run());
    if (parent != null) {
      boolean found = false;
      for (int i = 0; i < projects.size(); i++) if (projects.get(i)[0].equals(parent.proj)) { proj.setSelectedIndex(i); found = true; }
      if (!found) proj.getEditor().setItem(parent.proj);
    }
    fillCfg.run();
    if (parent != null) cfg.getEditor().setItem(readIdentity(parent).getOrDefault("configuration", parent.cfg == null ? "" : parent.cfg));
    JPanel p = new JPanel(new GridLayout(0, 2, 6, 4));
    if (parent != null) { p.add(new JLabel("Parent case")); p.add(new JLabel(parent.caseId + "  (this sim)")); }
    p.add(new JLabel("Project")); p.add(proj);
    p.add(new JLabel("Description (one line)")); p.add(desc);
    p.add(new JLabel("Configuration (optional, free text)")); p.add(cfg);
    p.add(new JLabel("Label in the file name (optional)")); p.add(label);
    p.add(new JLabel("Create the case folder in")); p.add(wherePanel);
    JLabel hint = new JLabel(" ");
    hint.setForeground(new java.awt.Color(0xB0, 0x20, 0x20));
    String d;
    while (true) {   // a missing entry re-opens the form with the inputs kept (no extra window)
      if (!Ui.form(parent == null ? "Save new version  -  start the case (v01)" : "New case from " + parent.caseId, p)) throw new Cancel(null);
      d = Fmt.oneLine(desc.getText());
      String missing = d.isEmpty() ? "Please type a one-line description."
          : where.getText().trim().isEmpty() ? "Please choose the folder that will hold the case folder." : null;
      if (missing == null) break;
      if (hint.getParent() == null) {
        p.add(new JLabel(""));
        p.add(hint);
      }
      hint.setText(missing);
    }
    String lab = checkLabel(label.getText());
    String pr = projectCode(proj, projects);
    String conf = Fmt.oneLine(String.valueOf(cfg.getEditor().getItem() == null ? "" : cfg.getEditor().getItem()));
    Path casesDir = Paths.get(where.getText().trim()).toAbsolutePath().normalize();
    if (Fs.under(casesDir, Fs.kit()) || Fs.same(casesDir, Fs.kit())) throw new Cancel("cases cannot go inside the kit folder " + Fs.kit());
    CaseInfo c = createCaseFolders(casesDir, pr, lab);
    Env.setPref("casesDir", casesDir.toString());
    sim.saveState(Fs.guard(c.simFile).toString());
    log.line("saved " + c.simFile + (parent == null ? "" : "\n(" + parent.caseId + " stays as it is on disk; you now work in "
        + c.caseId + ")"));
    Map<String, String> id = new LinkedHashMap<>();
    id.put("case_id", c.caseId);
    id.put("description", d);
    id.put("configuration", conf);
    id.put("parent_case", parent == null ? "" : parent.caseId);
    id.put("parent_file", parent == null ? "" : parent.simFile.toString());
    id.put("created", Fmt.iso());
    id.put("created_by", Env.user());
    id.put("forked_v01", parent != null ? versionId(parent)
        : sourceFile.isEmpty() ? "" : Paths.get(sourceFile).getFileName().toString().replaceFirst("\\.sim$", ""));
    putLast(id, c);
    writeIdentity(c, id);
    writeAudit(c);
    updateCaseList(c.casesDir);
    result("saved " + c.simFile.getFileName() + "  (new case " + c.caseId + ")");
  }

  /** Project code from the (editable) project box: a listed project, or a typed code (2-10 capitals/digits). */
  static String projectCode(JComboBox<String> proj, List<String[]> projects) {
    int i = proj.getSelectedIndex();
    Object typed = proj.getEditor().getItem();
    String t = typed == null ? "" : typed.toString().trim();
    for (String[] p : projects) if (t.equals(p[0] + "  -  " + p[1])) return p[0];
    if (i >= 0 && i < projects.size() && t.isEmpty()) return projects.get(i)[0];
    String code = t.split("\\s")[0].toUpperCase(Locale.ROOT);
    if (!PROJECT.matcher(code).matches()) {
      throw new Cancel("project code '" + t + "': 2 to 10 capital letters or digits, starting with a letter (e.g. ISO, FIRE2)");
    }
    return code;
  }

  /** Configurations already used by the cases of a folder (suggestions for New case). */
  static List<String> usedConfigurations(Path casesDir) {
    Set<String> out = new TreeSet<>();
    for (Path d : Fs.list(casesDir, "*")) {
      if (!CASE_ID.matcher(d.getFileName().toString()).matches()) continue;
      String c = readIdentity(d.resolve("sim").resolve("case_identity.txt")).getOrDefault("configuration", "");
      if (!c.isEmpty()) out.add(c);
    }
    return new ArrayList<>(out);
  }

  static void putLast(Map<String, String> id, CaseInfo c) {
    id.put("latest_file", c.simFile.getFileName().toString());
    id.put("last_saved", Fmt.iso());
  }

  /** New version: keeps the open file untouched and saves the set-up as the next _v<nn> of the case (same label),
   *  without any question. Records the version it is based on, the set-up record and the list of changes. */
  /** Save new version (one button): not a case yet -> create the case (v01); a case -> one small question,
   *  "next version" by default (Enter), or "new case from this one". */
  void toolSave() throws IOException {
    CaseInfo c = caseOrNull();
    if (c == null) {
      toolNewCase();
      return;
    }
    if (!gui) {
      saveNextVersion(c, c.label);
      return;
    }
    int v = nextVersion(c);
    javax.swing.JRadioButton next = new javax.swing.JRadioButton("Next version of " + c.caseId + "   (v" + Fmt.two(v) + ")", true);
    javax.swing.JRadioButton fresh = new javax.swing.JRadioButton("New case from this one   (asks for a description)");
    javax.swing.ButtonGroup bg = new javax.swing.ButtonGroup();
    bg.add(next);
    bg.add(fresh);
    next.setFont(next.getFont().deriveFont(Font.BOLD));
    fresh.setFont(fresh.getFont().deriveFont(Font.BOLD));
    JTextField name = new JTextField(c.label, 14);
    JLabel file = new JLabel();
    Runnable upd = () -> {
      String t = name.getText().trim();
      file.setText(c.caseId + (t.isEmpty() ? "" : "_" + t) + "_v" + Fmt.two(v) + ".sim");
      name.setEnabled(next.isSelected());
      next.setFont(next.getFont().deriveFont(next.isSelected() ? Font.BOLD : Font.PLAIN));
      fresh.setFont(fresh.getFont().deriveFont(fresh.isSelected() ? Font.BOLD : Font.PLAIN));
    };
    name.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
      @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { upd.run(); }
      @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { upd.run(); }
      @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { upd.run(); }
    });
    next.addActionListener(e -> upd.run());
    fresh.addActionListener(e -> upd.run());
    upd.run();
    JPanel p = new JPanel();
    p.setLayout(new javax.swing.BoxLayout(p, javax.swing.BoxLayout.Y_AXIS));
    JPanel nameRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));
    nameRow.add(new JLabel("name (optional)"));
    nameRow.add(name);
    nameRow.add(new JLabel("->"));
    nameRow.add(file);
    p.add(left(next));
    p.add(left(grey("Same question, better set-up: a fix, a setting, more iterations, a rerun. Only the latest version counts.")));
    p.add(left(nameRow));
    p.add(javax.swing.Box.createVerticalStrut(10));
    p.add(left(fresh));
    p.add(left(grey("A different answer to compare: another geometry, operating point, model choice or mesh level.")));
    p.add(javax.swing.Box.createVerticalStrut(6));
    p.add(left(grey("Either way the open file stays as it is on disk and you continue in the new one.")));
    if (!Ui.form("Save new version  -  " + c.simBase(), p)) throw new Cancel(null);
    if (fresh.isSelected()) {
      toolNewCase();
      return;
    }
    saveNextVersion(c, checkLabel(name.getText()));
  }

  static javax.swing.JComponent left(javax.swing.JComponent c) {
    c.setAlignmentX(0f);
    if (c instanceof JLabel) ((JLabel) c).setBorder(BorderFactory.createEmptyBorder(0, 26, 2, 0));
    return c;
  }

  static JLabel grey(String s) {
    JLabel l = new JLabel(s);
    l.setForeground(java.awt.Color.GRAY);
    return l;
  }

  /** New version without a question (batch action "newversion": keeps the name). */
  void toolSaveVersion() throws IOException {
    CaseInfo c = requireCase();
    saveNextVersion(c, c.label);
  }

  void saveNextVersion(CaseInfo c, String label) throws IOException {
    int v = nextVersion(c);
    CaseInfo n = c.withVersion(label, v);
    Path prevAudit = c.auditFile();
    sim.saveState(Fs.guard(n.simFile).toString());
    log.line("new version " + versionId(n) + " = " + n.simFile.getFileName() + " (based on " + versionId(c) + ", which stays as it is)."
        + "\nYou now work in " + n.simFile.getFileName() + "; Ctrl+S saves it as usual.");
    Map<String, String> id = readIdentity(n);
    putLast(id, n);
    id.put("forked_v" + Fmt.two(n.version), versionId(c));
    writeIdentity(n, id);
    Audit a = writeAudit(n);
    if (Files.isRegularFile(prevAudit)) {
      Path diff = n.sub("record").resolve("changes_" + c.simBase() + "_to_" + n.simBase() + ".csv");
      log.line("set-up changes since " + c.simBase() + ": " + diffAudits(prevAudit, a.file, diff));
    } else {
      log.line("no record of " + c.simBase() + " to compare with (list of changes skipped)");
    }
    updateCaseList(n.casesDir);
    result("saved " + n.simFile.getFileName() + "  (the previous file is unchanged)");
  }

  // =====================================================================================================
  // Case list (one CSV per project, rebuilt from the case folders; import it in Google Sheets)
  // =====================================================================================================

  // =====================================================================================================
  // Case tracker: one row per .sim version, read from the case folders; status and comments editable
  // =====================================================================================================

  /** Tracker columns. ID = <CASEID>.v<nn> (one per version file; "based on" refers to it). comment, status and
   *  description are the user's (editable); everything else is read from the case folders. */
  static final String[] TRACKER_COLS = {"ID", "comment", "status", "based on", "description", "saved", "file"};
  static final int COL_ID = 0, COL_COMMENT = 1, COL_STATUS = 2, COL_BASED = 3, COL_DESC = 4, COL_SAVED = 5, COL_FILE = 6;

  /** Version ID: ISO-007.v03 (case + version; the optional name in the file is not part of it). */
  static String versionId(CaseInfo c) { return c.caseId + ".v" + Fmt.two(c.version); }

  /** A recorded reference (file base name, older records) as a version ID when it is a SimKit file; else as it is. */
  static String asVersionId(String ref) {
    if (ref == null || ref.isEmpty()) return "";
    if (ref.matches(".*\\.v[0-9]{2,3}$")) return ref;
    Matcher m = SIM_NAME.matcher(ref.endsWith(".sim") ? ref : ref + ".sim");
    return m.matches() ? m.group(1) + ".v" + Fmt.two(Integer.parseInt(m.group(6))) : ref;
  }

  static String caseOf(String versionId) {
    int i = versionId.lastIndexOf(".v");
    return i < 0 ? versionId : versionId.substring(0, i);
  }

  static String verOf(String versionId) {
    int i = versionId.lastIndexOf(".v");
    return i < 0 ? "" : versionId.substring(i + 2);
  }

  /** Hidden in the tracker and the case list (files untouched): hidden=yes (whole case) or hidden_v<nn>=yes. */
  static boolean isHidden(Map<String, String> id, String v) {
    return "yes".equals(id.get("hidden")) || "yes".equals(id.get("hidden_v" + v));
  }

  /** File names (rows) currently hidden in a cases folder. */
  static Set<String> hiddenFiles(Path casesDir, List<String[]> rows) {
    Set<String> out = new HashSet<>();
    Map<String, Map<String, String>> ids = new HashMap<>();
    for (String[] r : rows) {
      String cid = caseOf(r[COL_ID]);
      Map<String, String> id = ids.computeIfAbsent(cid, k -> readIdentity(casesDir.resolve(k).resolve("sim").resolve("case_identity.txt")));
      if (isHidden(id, verOf(r[COL_ID]))) out.add(r[COL_FILE]);
    }
    return out;
  }

  /** Hides or shows again a whole case (v == null) or one version; only case_identity.txt changes. */
  static void setHidden(Path casesDir, String caseId, String v, boolean hide) throws IOException {
    Path idf = casesDir.resolve(caseId).resolve("sim").resolve("case_identity.txt");
    Map<String, String> id = readIdentity(idf);
    if (v == null) {
      if (hide) id.put("hidden", "yes"); else id.remove("hidden");
      if (!hide) id.keySet().removeIf(k -> k.startsWith("hidden_v"));
    } else {
      if (hide) id.put("hidden_v" + v, "yes"); else { id.remove("hidden_v" + v); id.remove("hidden"); }
    }
    Fs.write(idf, identityText(id));
  }
  static final String[] STATUSES = {"", "planned", "meshing", "running", "done", "reported", "dropped"};
  static final String TRACKER_CSV = "case_tracker.csv";

  /** One row per version file in casesDir/<CASEID>/sim/. Reads only files; never the simulation. */
  static List<String[]> trackerRows(Path casesDir) {
    List<String[]> rows = new ArrayList<>();
    for (Path d : Fs.list(casesDir, "*")) {
      if (!CASE_ID.matcher(d.getFileName().toString()).matches()) continue;
      Map<String, String> id = readIdentity(d.resolve("sim").resolve("case_identity.txt"));
      for (Path f : Fs.list(d.resolve("sim"), "*.sim")) {
        Matcher m = SIM_NAME.matcher(f.getFileName().toString());
        if (!m.matches() || !m.group(1).equals(d.getFileName().toString())) continue;
        String base = f.getFileName().toString().replaceFirst("\\.sim$", "");
        String v = Fmt.two(Integer.parseInt(m.group(6)));
        String[] r = new String[TRACKER_COLS.length];
        Arrays.fill(r, "");
        r[COL_ID] = m.group(1) + ".v" + v;
        r[COL_COMMENT] = id.getOrDefault("comment_v" + v, "");
        r[COL_STATUS] = id.getOrDefault("status_v" + v, "");
        r[COL_BASED] = asVersionId(id.getOrDefault("forked_v" + v, v.equals("01") ? fileName(id.getOrDefault("parent_file", "")) : ""));
        r[COL_DESC] = id.getOrDefault("description", "");
        try {
          r[COL_SAVED] = new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(Files.getLastModifiedTime(f).toMillis()));
        } catch (IOException e) { /* unreadable: left empty */ }
        r[COL_FILE] = f.getFileName().toString();
        rows.add(r);
      }
    }
    rows.sort((x, y) -> {   // by case, then version number (not by file name)
      int k = caseOf(x[COL_ID]).compareTo(caseOf(y[COL_ID]));
      return k != 0 ? k : Integer.compare(Integer.parseInt(verOf(x[COL_ID])), Integer.parseInt(verOf(y[COL_ID])));
    });
    return rows;
  }

  static String fileName(String path) {
    if (path == null || path.isEmpty()) return "";
    try { return Paths.get(path).getFileName().toString().replaceFirst("\\.sim$", ""); } catch (Throwable t) { return path; }
  }

  /** Physics models of each continuum, from a set-up record. */
  static String modelSummary(Path setup) {
    if (!Files.isRegularFile(setup)) return "";
    try {
      List<String> models = new ArrayList<>();
      List<String[]> rows = Csv.parse(Fs.read(setup));
      for (int i = 1; i < rows.size(); i++) {
        String[] x = Arrays.copyOf(rows.get(i), 4);
        if ("CONTINUA".equals(x[0]) && "models".equals(x[2])) models.add(x[1] + ": " + x[3]);
      }
      return String.join(" | ", models);
    } catch (IOException e) { return ""; }
  }

  static String keySummary(Path kr) {
    if (!Files.isRegularFile(kr)) return "";
    try {
      List<String> out = new ArrayList<>();
      List<String[]> rows = Csv.parse(Fs.read(kr));
      for (int i = 1; i < rows.size(); i++) {
        String[] x = rows.get(i);
        if (x.length >= 2 && (Arrays.asList(KEY_COLS).contains(x[0]) || x[0].equals("cells"))) out.add(x[0] + "=" + x[1]);
      }
      return String.join("; ", out);
    } catch (IOException e) { return "?"; }
  }

  /** Writes status_vNN / comment_vNN of the edited rows into each case_identity.txt. Returns the number saved. */
  static int saveTracker(Path casesDir, List<String[]> rows, Map<String, String[]> original) throws IOException {
    Map<String, Map<String, String>> byCase = new LinkedHashMap<>();
    int n = 0;
    for (String[] r : rows) {
      String[] o = original.get(r[COL_FILE]);
      if (o != null && o[COL_STATUS].equals(r[COL_STATUS]) && o[COL_COMMENT].equals(r[COL_COMMENT])
          && o[COL_DESC].equals(r[COL_DESC])) continue;
      String cid = caseOf(r[COL_ID]);
      Path idf = casesDir.resolve(cid).resolve("sim").resolve("case_identity.txt");
      Map<String, String> id = byCase.computeIfAbsent(cid, k -> readIdentity(idf));
      String v = verOf(r[COL_ID]);
      id.put("status_v" + v, Fmt.oneLine(r[COL_STATUS]));
      id.put("comment_v" + v, r[COL_COMMENT]);
      if (o == null || !o[COL_DESC].equals(r[COL_DESC])) id.put("description", r[COL_DESC]);   // per case
      n++;
    }
    for (Map.Entry<String, Map<String, String>> e : byCase.entrySet()) {
      Fs.write(casesDir.resolve(e.getKey()).resolve("sim").resolve("case_identity.txt"), identityText(e.getValue()));
    }
    return n;
  }

  /** Case tracker: floating window of every case and version; comment, status and description are saved as you edit. */
  void toolTracker() throws IOException {
    CaseInfo c = caseOrNull();
    Path dir = c != null ? c.casesDir : Ui.chooseDir("Case tracker: folder that holds the case folders", defaultCasesDir());
    Fs.allow(dir);
    List<String[]> rows = trackerRows(dir);
    updateCaseList(dir);
    if (!gui) {   // batch (test): write the CSV only
      writeTrackerCsv(dir, rows);
      log.line("case tracker: " + rows.size() + " version(s) -> " + dir.resolve(TRACKER_CSV));
      return;
    }
    log.line("case tracker: " + rows.size() + " version(s) in " + dir + " (window open)");
    result("case tracker open, " + rows.size() + " version(s)");
    final String openId = c != null ? versionId(c) : null;
    Ui.edt(() -> {
      TrackerWindow t = new TrackerWindow(dir, openId);
      t.w.setLocationRelativeTo(Ui.owner());
      Env.track(t.w);
      t.w.setVisible(true);   // non-modal: STAR-CCM+ stays usable
      return null;
    });
  }

  /** case_tracker.csv: the tracker columns + hidden (yes / empty). */
  static void writeTrackerCsv(Path dir, List<String[]> rows) throws IOException {
    Set<String> hid = hiddenFiles(dir, rows);
    String[] head = Arrays.copyOf(TRACKER_COLS, TRACKER_COLS.length + 1);
    head[TRACKER_COLS.length] = "hidden";
    List<String[]> out = new ArrayList<>();
    for (String[] r : rows) {
      String[] x = Arrays.copyOf(r, r.length + 1);
      x[r.length] = hid.contains(r[COL_FILE]) ? "yes" : "";
      out.add(x);
    }
    Csv.write(dir.resolve(TRACKER_CSV), head, out);
  }

  /** Sets one key of a case's case_identity.txt, read just before writing so other keys (a colleague's edits) are kept. */
  static void setIdentityKey(Path casesDir, String caseId, String key, String value) throws IOException {
    Path idf = casesDir.resolve(caseId).resolve("sim").resolve("case_identity.txt");
    Map<String, String> id = readIdentity(idf);
    id.put(key, value == null ? "" : value);
    Fs.write(idf, identityText(id));
  }

  /** Status colours in the tracker: {background, text}. */
  static java.awt.Color[] statusColors(String s) {
    switch (s == null ? "" : s) {
      case "planned": return new java.awt.Color[] {new java.awt.Color(0xEC, 0xEF, 0xF3), new java.awt.Color(0x4A, 0x55, 0x63)};
      case "meshing": return new java.awt.Color[] {new java.awt.Color(0xFF, 0xF1, 0xD6), new java.awt.Color(0x8A, 0x5A, 0x00)};
      case "running": return new java.awt.Color[] {new java.awt.Color(0xDC, 0xEB, 0xFF), new java.awt.Color(0x1F, 0x4E, 0x9A)};
      case "done": return new java.awt.Color[] {new java.awt.Color(0xDD, 0xF2, 0xDF), new java.awt.Color(0x2E, 0x6B, 0x2E)};
      case "reported": return new java.awt.Color[] {new java.awt.Color(0xEB, 0xE3, 0xF7), new java.awt.Color(0x5B, 0x3A, 0x8C)};
      case "dropped": return new java.awt.Color[] {new java.awt.Color(0xEE, 0xEE, 0xEE), new java.awt.Color(0x88, 0x88, 0x88)};
      default: return null;
    }
  }

  /** "2026-11-11 22:52" -> "11 Nov 22:52" (this year) or "11 Nov 2025". */
  static String shortDate(String iso) {
    try {
      Date d = new SimpleDateFormat("yyyy-MM-dd HH:mm").parse(iso);
      boolean thisYear = new SimpleDateFormat("yyyy").format(d).equals(new SimpleDateFormat("yyyy").format(new Date()));
      return new SimpleDateFormat(thisYear ? "d MMM HH:mm" : "d MMM yyyy", Locale.ENGLISH).format(d);
    } catch (Exception e) {
      return iso == null ? "" : iso;
    }
  }

  /** The optional name in a version file: ISO-007_cold_v03.sim -> "cold". */
  static String fileLabel(String file) {
    Matcher m = SIM_NAME.matcher(file == null ? "" : file);
    return m.matches() && m.group(5) != null ? m.group(5) : "";
  }

  static String html(String s) {
    return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  /** Small painted icons (no font glyphs: the same on Windows and RHEL). kind: 0 folded, 1 unfolded, 2 open in STAR, 3 blank. */
  static javax.swing.Icon trackerIcon(final int kind) {
    return new javax.swing.Icon() {
      @Override public int getIconWidth() { return 12; }
      @Override public int getIconHeight() { return 12; }
      @Override public void paintIcon(java.awt.Component c, java.awt.Graphics g0, int x, int y) {
        java.awt.Graphics2D g = (java.awt.Graphics2D) g0.create();
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        if (kind == 0) {
          g.setColor(new java.awt.Color(0x55, 0x66, 0x77));
          g.fillPolygon(new int[] {x + 3, x + 9, x + 3}, new int[] {y + 1, y + 6, y + 11}, 3);
        } else if (kind == 1) {
          g.setColor(new java.awt.Color(0x55, 0x66, 0x77));
          g.fillPolygon(new int[] {x + 1, x + 11, x + 6}, new int[] {y + 3, y + 3, y + 9}, 3);
        } else if (kind == 2) {
          g.setColor(new java.awt.Color(0x2E, 0x8B, 0x3E));
          g.fillOval(x + 2, y + 2, 8, 8);
        }
        g.dispose();
      }
    };
  }

  /** The Case tracker window: cases as fold-able header rows with their versions below; edits are saved at once;
   *  details of the selected version underneath; refreshed from the case folders whenever the window gets focus. */
  final class TrackerWindow {
    static final int T_ID = 0, T_STATUS = 1, T_COMMENT = 2, T_BASED = 3, T_SAVED = 4;
    final String[] cols = {"ID", "status", "comment", "based on", "saved"};
    final java.awt.Color caseBg = new java.awt.Color(0xF1, 0xF3, 0xF6), stripe = new java.awt.Color(0xFA, 0xFB, 0xFC),
        selBg = new java.awt.Color(0xDC, 0xE8, 0xF8), pale = new java.awt.Color(0xB8, 0xBE, 0xC6),
        grey = new java.awt.Color(0x6B, 0x75, 0x80), link = new java.awt.Color(0x1F, 0x4E, 0x9A),
        red = new java.awt.Color(0xB0, 0x20, 0x20), green = new java.awt.Color(0x2E, 0x6B, 0x2E);

    /** One table row: a case header (r == null) or a version. */
    final class Item {
      final String caseId;
      final String[] r;
      Item(String caseId, String[] r) { this.caseId = caseId; this.r = r; }
      boolean isCase() { return r == null; }
      String key() { return r == null ? "case:" + caseId : r[COL_ID]; }
    }

    Path dir;
    final String openId;
    List<String[]> rows = new ArrayList<>();
    final Map<String, List<String[]>> byCase = new LinkedHashMap<>();
    final Map<String, Map<String, String>> ids = new HashMap<>();
    final Set<String> expanded = new HashSet<>();
    final List<Item> view = new ArrayList<>();
    final JDialog w;
    final JTable table;
    final javax.swing.table.AbstractTableModel model;
    final JTextField filter;
    final JComboBox<String> statusFilter = new JComboBox<>(new String[] {"All statuses", "Active (planned, meshing, running)",
        "Done or reported", "Dropped", "No status"});
    final javax.swing.JCheckBox showHidden = new javax.swing.JCheckBox("Show hidden");
    final JLabel summary = new JLabel(), message = new JLabel(" "), folderName = new JLabel(), headline = new JLabel(" "),
        areaLabel = new JLabel("Comment");
    final JTextArea area = new JTextArea(5, 40);
    final javax.swing.JEditorPane viewer = new javax.swing.JEditorPane();   // the comment, formatted
    final java.awt.CardLayout cardLayout = new java.awt.CardLayout();
    final JPanel cards = new JPanel(cardLayout), editPanel = new JPanel(new BorderLayout(0, 2));
    boolean editing = false;
    final JButton folderBtn = new JButton("Open case folder"), hideBtn = new JButton("Hide...");
    String areaKey = null;   // what the details text area edits: version ID (comment) or "case:<id>" (description)

    TrackerWindow(Path dir0, String openId) {
      this.dir = dir0;
      this.openId = openId;
      if (openId != null) expanded.add(caseOf(openId));
      model = new javax.swing.table.AbstractTableModel() {
        private static final long serialVersionUID = 1L;
        @Override public int getRowCount() { return view.size(); }
        @Override public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int c) { return cols[c]; }
        @Override public Object getValueAt(int row, int col) {
          Item it = view.get(row);
          if (it.isCase()) return col == T_ID ? it.caseId : col == T_COMMENT ? description(it.caseId) : "";
          switch (col) {
            case T_ID: return it.r[COL_ID];
            case T_STATUS: return it.r[COL_STATUS];
            case T_COMMENT: return it.r[COL_COMMENT];
            case T_BASED: return it.r[COL_BASED];
            default: return it.r[COL_SAVED];
          }
        }
        @Override public boolean isCellEditable(int row, int col) {   // comments and descriptions: in the box below
          return !view.get(row).isCase() && col == T_STATUS;
        }
        @Override public void setValueAt(Object v, int row, int col) {
          Item it = view.get(row);
          String s = v == null ? "" : Fmt.oneLine(String.valueOf(v)).trim();
          if (it.isCase()) saveDescription(it.caseId, s);
          else if (col == T_STATUS) saveVersion(it.r, COL_STATUS, "status", s);
          else saveVersion(it.r, COL_COMMENT, "comment", s);
          fireTableRowsUpdated(row, row);
          showDetails();
        }
      };
      table = new JTable(model);
      table.setRowHeight(26);
      table.setShowVerticalLines(false);
      table.setShowHorizontalLines(true);
      table.setGridColor(new java.awt.Color(0xEC, 0xEE, 0xF1));
      table.setIntercellSpacing(new java.awt.Dimension(0, 1));
      table.setFillsViewportHeight(true);
      table.setAutoResizeMode(JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS);
      table.getTableHeader().setReorderingAllowed(false);
      ((javax.swing.table.DefaultTableCellRenderer) table.getTableHeader().getDefaultRenderer())
          .setHorizontalAlignment(javax.swing.SwingConstants.LEFT);
      int[] widths = {230, 105, 0, 130, 115};
      for (int i = 0; i < widths.length; i++) {
        if (widths[i] == 0) { table.getColumnModel().getColumn(i).setPreferredWidth(600); continue; }
        table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        table.getColumnModel().getColumn(i).setMinWidth(widths[i] - 30);
        table.getColumnModel().getColumn(i).setMaxWidth(widths[i] + 120);
      }
      table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
      javax.swing.DefaultCellEditor statusEd = new javax.swing.DefaultCellEditor(new JComboBox<>(STATUSES));
      statusEd.setClickCountToStart(1);
      table.getColumnModel().getColumn(T_STATUS).setCellEditor(statusEd);
      table.setDefaultRenderer(Object.class, new Cells());
      table.getColumnModel().getColumn(T_STATUS).setCellRenderer(new Badge());
      table.getSelectionModel().addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) showDetails(); });
      table.addMouseListener(new java.awt.event.MouseAdapter() {
        @Override public void mousePressed(java.awt.event.MouseEvent e) { popup(e); }
        @Override public void mouseReleased(java.awt.event.MouseEvent e) { popup(e); }
        @Override public void mouseClicked(java.awt.event.MouseEvent e) {
          int row = table.rowAtPoint(e.getPoint()), col = table.columnAtPoint(e.getPoint());
          if (row < 0 || !javax.swing.SwingUtilities.isLeftMouseButton(e)) return;
          Item it = view.get(row);
          if (col == T_COMMENT && e.getClickCount() == 2) startEdit();   // write in the box below
          else if (it.isCase() && (col == T_ID || e.getClickCount() == 2)) toggle(it.caseId);
          else if (!it.isCase() && col == T_BASED && byId(it.r[COL_BASED]) != null) jumpTo(it.r[COL_BASED]);
        }
      });
      table.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
        @Override public void mouseMoved(java.awt.event.MouseEvent e) {
          int row = table.rowAtPoint(e.getPoint()), col = table.columnAtPoint(e.getPoint());
          boolean hand = row >= 0 && (view.get(row).isCase() && col == T_ID
              || !view.get(row).isCase() && col == T_BASED && byId(view.get(row).r[COL_BASED]) != null);
          table.setCursor(java.awt.Cursor.getPredefinedCursor(hand ? java.awt.Cursor.HAND_CURSOR : java.awt.Cursor.DEFAULT_CURSOR));
        }
      });

      filter = new JTextField(26) {
        private static final long serialVersionUID = 1L;
        @Override protected void paintComponent(java.awt.Graphics g) {
          super.paintComponent(g);
          if (getText().isEmpty() && !isFocusOwner()) {
            g.setColor(pale);
            java.awt.Insets in = getInsets();
            g.drawString("Filter by ID, comment, description...", in.left + 2, in.top + g.getFontMetrics().getAscent() + 1);
          }
        }
      };
      filter.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
        @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { rebuild(); }
        @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { rebuild(); }
        @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { rebuild(); }
      });
      filter.addFocusListener(new java.awt.event.FocusAdapter() {
        @Override public void focusGained(java.awt.event.FocusEvent e) { filter.repaint(); }
        @Override public void focusLost(java.awt.event.FocusEvent e) { filter.repaint(); }
      });
      statusFilter.addActionListener(e -> rebuild());
      showHidden.setToolTipText("Hidden cases and versions (their files are kept) are shown in grey");
      showHidden.addActionListener(e -> rebuild());

      area.setLineWrap(true);
      area.setWrapStyleWord(true);
      area.setFont(table.getFont());
      area.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
      area.addFocusListener(new java.awt.event.FocusAdapter() {
        @Override public void focusLost(java.awt.event.FocusEvent e) {
          java.awt.Component to = e.getOppositeComponent();
          if (e.isTemporary() || to != null && javax.swing.SwingUtilities.isDescendingFrom(to, editPanel)) flushArea();
          else endEdit();
        }
      });
      bind(area, "ctrl B", () -> wrap("**"));
      bind(area, "ctrl I", () -> wrap("*"));
      bind(area, "ESCAPE", this::endEdit);
      bind(area, "ENTER", this::newLine);
      viewer.setContentType("text/html");
      viewer.setEditable(false);
      viewer.putClientProperty(javax.swing.JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
      viewer.setFont(table.getFont());
      viewer.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
      viewer.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.TEXT_CURSOR));
      viewer.setToolTipText("Click to edit");
      viewer.addMouseListener(new java.awt.event.MouseAdapter() {
        @Override public void mouseClicked(java.awt.event.MouseEvent e) { startEdit(); }
      });
      JPanel bar = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
      Object[][] tools = {{"B", "Bold: **text** (Ctrl+B)", (Runnable) () -> wrap("**")},
          {"I", "Italic: *text* (Ctrl+I)", (Runnable) () -> wrap("*")},
          {"• List", "Bullet list: lines starting with - ", (Runnable) () -> list(false)},
          {"1. List", "Numbered list: lines starting with 1. 2. 3.", (Runnable) () -> list(true)},
          {"Done", "Save and show the formatted text (Esc)", (Runnable) this::endEdit}};
      for (Object[] t : tools) {
        JButton b = new JButton((String) t[0]);
        b.setFocusable(false);
        b.setToolTipText((String) t[1]);
        b.setMargin(new java.awt.Insets(1, 8, 1, 8));
        if (t[0].equals("B")) b.setFont(b.getFont().deriveFont(Font.BOLD));
        if (t[0].equals("I")) b.setFont(b.getFont().deriveFont(Font.ITALIC));
        final Runnable run = (Runnable) t[2];
        b.addActionListener(e -> run.run());
        if (t[0].equals("Done")) bar.add(javax.swing.Box.createHorizontalStrut(12));
        bar.add(b);
      }
      JLabel marks = new JLabel("   **bold**   *italic*   - bullet   1. numbered   (saved when you leave the box)");
      marks.setForeground(pale);
      bar.add(marks);
      editPanel.add(bar, BorderLayout.NORTH);
      editPanel.add(new JScrollPane(area), BorderLayout.CENTER);
      JScrollPane viewScroll = new JScrollPane(viewer);
      viewScroll.getViewport().setBackground(java.awt.Color.WHITE);
      cards.add(viewScroll, "view");
      cards.add(editPanel, "edit");
      folderBtn.addActionListener(e -> openFolder());
      hideBtn.addActionListener(e -> hideOrShow());
      headline.setFont(headline.getFont().deriveFont(Font.BOLD));
      areaLabel.setForeground(grey);

      JButton change = new JButton("Change...");
      change.setToolTipText("Show the cases of another folder");
      change.addActionListener(e -> changeFolder());
      JButton export = new JButton("Export CSV");
      export.setToolTipText("Write " + TRACKER_CSV + " in the cases folder (Google Sheets: File > Import > Upload)");
      export.addActionListener(e -> {
        try {
          writeTrackerCsv(dir, rows);
          say("Written " + dir.resolve(TRACKER_CSV), green);
        } catch (Throwable t) {
          say("NOT written: " + reason(t), red);
        }
      });

      w = new JDialog(Ui.owner(), "Case tracker", java.awt.Dialog.ModalityType.MODELESS);
      w.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
      JButton close = new JButton("Close");
      close.addActionListener(e -> w.dispose());

      JPanel topLeft = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 0));
      JLabel casesIn = new JLabel("Cases in");
      casesIn.setForeground(grey);
      folderName.setFont(folderName.getFont().deriveFont(Font.BOLD));
      topLeft.add(casesIn);
      topLeft.add(folderName);
      topLeft.add(change);
      topLeft.add(javax.swing.Box.createHorizontalStrut(16));
      topLeft.add(filter);
      topLeft.add(statusFilter);
      topLeft.add(showHidden);
      summary.setForeground(grey);
      JPanel top = new JPanel(new BorderLayout());
      top.add(topLeft, BorderLayout.WEST);
      top.add(summary, BorderLayout.EAST);

      JPanel details = new JPanel(new BorderLayout(10, 4));
      details.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, new java.awt.Color(0xDD, 0xDD, 0xDD)),
          BorderFactory.createEmptyBorder(8, 2, 4, 2)));
      details.add(headline, BorderLayout.NORTH);
      JPanel areaBox = new JPanel(new BorderLayout(0, 2));
      areaBox.add(areaLabel, BorderLayout.NORTH);
      areaBox.add(cards, BorderLayout.CENTER);
      details.add(areaBox, BorderLayout.CENTER);
      details.setPreferredSize(new java.awt.Dimension(1250, 210));
      details.setMinimumSize(new java.awt.Dimension(400, 120));
      JPanel acts = new JPanel(new GridLayout(2, 1, 0, 6));
      acts.add(folderBtn);
      acts.add(hideBtn);
      JPanel actsBox = new JPanel(new BorderLayout());
      actsBox.add(acts, BorderLayout.NORTH);
      details.add(actsBox, BorderLayout.EAST);

      JPanel bottom = new JPanel(new BorderLayout());
      JPanel btns = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 0));
      btns.add(export);
      btns.add(close);
      bottom.add(message, BorderLayout.CENTER);
      bottom.add(btns, BorderLayout.EAST);

      JPanel root = new JPanel(new BorderLayout(0, 8));
      root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
      root.add(top, BorderLayout.NORTH);
      JScrollPane sp = new JScrollPane(table);
      sp.getViewport().setBackground(java.awt.Color.WHITE);
      sp.setPreferredSize(new java.awt.Dimension(1250, 420));
      sp.setMinimumSize(new java.awt.Dimension(400, 120));
      javax.swing.JSplitPane split = new javax.swing.JSplitPane(javax.swing.JSplitPane.VERTICAL_SPLIT, sp, details);
      split.setResizeWeight(1.0);   // a bigger window gives the table the room; drag the bar for a bigger box
      split.setBorder(null);
      split.setDividerSize(7);
      split.setContinuousLayout(true);
      root.add(split, BorderLayout.CENTER);
      root.add(bottom, BorderLayout.SOUTH);
      w.setContentPane(root);

      w.addWindowFocusListener(new java.awt.event.WindowAdapter() {
        @Override public void windowGainedFocus(java.awt.event.WindowEvent e) {
          if (!table.isEditing() && !editing) reload();
        }
      });
      reload();
      if (openId != null) jumpTo(openId);
      w.pack();
    }

    // ----- data -----

    void reload() {
      rows = trackerRows(dir);
      byCase.clear();
      ids.clear();
      for (String[] r : rows) byCase.computeIfAbsent(caseOf(r[COL_ID]), k -> new ArrayList<>()).add(r);
      for (String cid : byCase.keySet()) ids.put(cid, readIdentity(dir.resolve(cid).resolve("sim").resolve("case_identity.txt")));
      if (openId == null && expanded.isEmpty()) expanded.addAll(byCase.keySet());
      w.setTitle("Case tracker  -  cases in " + dir);
      folderName.setText(dir.getFileName() == null ? dir.toString() : dir.getFileName().toString());
      folderName.setToolTipText(dir.toString());
      rebuild();
    }

    String description(String caseId) {
      List<String[]> v = byCase.get(caseId);
      return v == null || v.isEmpty() ? "" : v.get(0)[COL_DESC];
    }

    boolean caseHidden(String caseId) { return "yes".equals(ids.getOrDefault(caseId, new HashMap<>()).get("hidden")); }

    boolean hidden(String[] r) { return isHidden(ids.getOrDefault(caseOf(r[COL_ID]), new HashMap<>()), verOf(r[COL_ID])); }

    String[] byId(String id) {
      for (String[] r : rows) if (r[COL_ID].equals(id)) return r;
      return null;
    }

    boolean filtering() { return !filter.getText().trim().isEmpty() || statusFilter.getSelectedIndex() > 0; }

    boolean statusOk(String s) {
      switch (statusFilter.getSelectedIndex()) {
        case 1: return s.equals("planned") || s.equals("meshing") || s.equals("running");
        case 2: return s.equals("done") || s.equals("reported");
        case 3: return s.equals("dropped");
        case 4: return s.isEmpty();
        default: return true;
      }
    }

    /** Rebuilds the visible rows from the data, the filter and the folded cases; keeps the selection. */
    void rebuild() {
      if (table.isEditing()) table.getCellEditor().cancelCellEditing();
      Set<String> sel = new HashSet<>();
      for (int i : table.getSelectedRows()) if (i < view.size()) sel.add(view.get(i).key());
      String t = filter.getText().trim().toLowerCase(Locale.ROOT);
      view.clear();
      int nCases = 0, nVers = 0, nRunning = 0, nHidden = 0;
      for (Map.Entry<String, List<String[]>> e : byCase.entrySet()) {
        String cid = e.getKey();
        boolean ch = caseHidden(cid);
        List<String[]> vis = new ArrayList<>();
        for (String[] r : e.getValue()) {
          boolean h = ch || hidden(r);
          if (h) nHidden++;
          else { nVers++; if (r[COL_STATUS].equals("running") || r[COL_STATUS].equals("meshing")) nRunning++; }
          if (h && !showHidden.isSelected()) continue;
          if (!statusOk(r[COL_STATUS])) continue;
          String all = (String.join(" ", r) + " " + cid).toLowerCase(Locale.ROOT);
          if (!t.isEmpty() && !all.contains(t)) continue;
          vis.add(r);
        }
        if (!ch) nCases++;
        if (vis.isEmpty()) continue;
        view.add(new Item(cid, null));
        if (expanded.contains(cid) || filtering()) for (String[] r : vis) view.add(new Item(cid, r));
      }
      model.fireTableDataChanged();
      for (int i = 0; i < view.size(); i++) if (sel.contains(view.get(i).key())) table.addRowSelectionInterval(i, i);
      summary.setText(nCases + (nCases == 1 ? " case" : " cases") + "  ·  " + nVers + (nVers == 1 ? " version" : " versions")
          + (nRunning > 0 ? "  ·  " + nRunning + " meshing or running" : "") + (nHidden > 0 ? "  ·  " + nHidden + " hidden" : ""));
      showDetails();
    }

    void toggle(String caseId) {
      if (filtering()) { say("Clear the filter to fold or unfold cases", grey); return; }
      if (!expanded.remove(caseId)) expanded.add(caseId);
      rebuild();
    }

    void jumpTo(String id) {
      String[] r = byId(id);
      if (r == null) return;
      if (hidden(r) || caseHidden(caseOf(id))) showHidden.setSelected(true);
      expanded.add(caseOf(id));
      if (filtering()) { filter.setText(""); statusFilter.setSelectedIndex(0); }
      rebuild();
      for (int i = 0; i < view.size(); i++) {
        if (id.equals(view.get(i).key())) {
          table.setRowSelectionInterval(i, i);
          table.scrollRectToVisible(table.getCellRect(i, 0, true));
          return;
        }
      }
    }

    // ----- saving (at once, one key at a time) -----

    void saveVersion(String[] r, int col, String key, String value) {
      if (value.equals(r[col])) return;
      try {
        setIdentityKey(dir, caseOf(r[COL_ID]), key + "_v" + verOf(r[COL_ID]), value);
        r[col] = value;
        if (col == COL_COMMENT && r[COL_ID].equals(areaKey)) area.setText(value);   // else the details box would put the old text back
        afterSave("Saved: " + key + " of " + r[COL_ID]);
      } catch (Throwable t) {
        say("NOT saved (" + key + " of " + r[COL_ID] + "): " + reason(t), red);
      }
    }

    void saveDescription(String caseId, String value) {
      if (value.equals(description(caseId))) return;
      try {
        setIdentityKey(dir, caseId, "description", value);
        for (String[] r : byCase.get(caseId)) r[COL_DESC] = value;
        if (("case:" + caseId).equals(areaKey)) area.setText(value);
        afterSave("Saved: description of " + caseId);
        updateCaseList(dir);
      } catch (Throwable t) {
        say("NOT saved (description of " + caseId + "): " + reason(t), red);
      }
    }

    void afterSave(String what) {
      log.line("case tracker: " + Character.toLowerCase(what.charAt(0)) + what.substring(1));
      try { writeTrackerCsv(dir, rows); } catch (Throwable t) { /* the CSV is a copy; Export CSV reports errors */ }
      say(what, green);
    }

    void say(String s, java.awt.Color c) {
      message.setForeground(c);
      message.setText(s);
    }

    // ----- details of the selection -----

    List<Item> selected() {
      List<Item> out = new ArrayList<>();
      for (int i : table.getSelectedRows()) if (i < view.size()) out.add(view.get(i));
      return out;
    }

    void showDetails() {
      flushArea();   // a comment being typed is saved before the details change
      List<Item> sel = selected();
      boolean allHidden = !sel.isEmpty();
      for (Item it : sel) allHidden &= it.isCase() ? caseHidden(it.caseId) : caseHidden(it.caseId) || hidden(it.r);
      hideBtn.setText(allHidden ? "Show again" : "Hide...");
      hideBtn.setToolTipText(allHidden ? "Bring the selection back into the tracker and the case list"
          : "Take the selection out of the tracker and the case list; the files stay where they are");
      hideBtn.setEnabled(!sel.isEmpty());
      folderBtn.setEnabled(!sel.isEmpty());
      String key = null, text = "";
      if (sel.isEmpty()) {
        headline.setText("Select a case or a version to see its details");
        areaLabel.setText("Comment");
      } else if (sel.size() > 1) {
        headline.setText(sel.size() + " rows selected");
        areaLabel.setText("Comment");
      } else if (sel.get(0).isCase()) {
        Item it = sel.get(0);
        Map<String, String> id = ids.getOrDefault(it.caseId, new HashMap<>());
        List<String> bits = new ArrayList<>();
        bits.add(byCase.get(it.caseId).size() + " version(s)");
        if (!id.getOrDefault("parent_case", "").isEmpty()) bits.add("from case " + id.get("parent_case"));
        if (!id.getOrDefault("mesh_level", "").isEmpty()) bits.add("mesh study " + id.getOrDefault("mesh_study", "")
            + ": " + id.get("mesh_level") + (id.getOrDefault("mesh_cells", "").isEmpty() ? "" : ", " + id.get("mesh_cells") + " cells"));
        if (caseHidden(it.caseId)) bits.add("hidden");
        headline.setText("<html>" + html(it.caseId) + "&nbsp;&nbsp;<font color='#6B7580'>" + html(String.join("  ·  ", bits))
            + "</font></html>");
        areaLabel.setText("Description of the case (the same for all its versions)");
        key = it.key();
        text = description(it.caseId);
      } else {
        String[] r = sel.get(0).r;
        Map<String, String> id = ids.getOrDefault(sel.get(0).caseId, new HashMap<>());
        List<String> bits = new ArrayList<>();
        bits.add("file " + r[COL_FILE]);
        bits.add(r[COL_BASED].isEmpty() ? "first version" : "based on " + r[COL_BASED]);
        bits.add("saved " + r[COL_SAVED]);
        if (!id.getOrDefault("mesh_level", "").isEmpty()) bits.add("mesh: " + id.get("mesh_level")
            + (id.getOrDefault("mesh_cells", "").isEmpty() ? "" : ", " + id.get("mesh_cells") + " cells"));
        if (r[COL_ID].equals(openId)) bits.add("open in STAR-CCM+");
        if (hidden(r) || caseHidden(sel.get(0).caseId)) bits.add("hidden");
        headline.setText("<html>" + html(r[COL_ID]) + "&nbsp;&nbsp;<font color='#6B7580'>" + html(String.join("  ·  ", bits))
            + "</font></html>");
        areaLabel.setText("Comment  (what you are doing with this version)");
        key = r[COL_ID];
        text = r[COL_COMMENT];
      }
      if (key == null || !key.equals(areaKey) || !editing) {   // never overwrite what is being typed
        if (editing && (key == null || !key.equals(areaKey))) {
          editing = false;
          cardLayout.show(cards, "view");
        }
        areaKey = key;
        if (!area.getText().equals(text)) area.setText(text);
        area.setEditable(key != null);
        String empty = key == null ? "" : key.startsWith("case:") ? "Click here to describe the case" : "Click here to write a comment";
        viewer.setText("<html><head><style>" + viewerCss() + "</style></head><body>"
            + (text.trim().isEmpty() ? "<p class='empty'>" + empty + "</p>" : Md.toHtml(text)) + "</body></html>");
        viewer.setCaretPosition(0);
        viewer.setBackground(key != null ? java.awt.Color.WHITE : new java.awt.Color(0xF4, 0xF5, 0xF7));
        viewer.setCursor(java.awt.Cursor.getPredefinedCursor(key != null ? java.awt.Cursor.TEXT_CURSOR : java.awt.Cursor.DEFAULT_CURSOR));
      }
    }

    String viewerCss() {
      Font f = table.getFont();
      return "body{font-family:'" + f.getFamily() + "';font-size:" + f.getSize() + "pt;margin:2px}p{margin-top:0;margin-bottom:5px}"
          + "ul,ol{margin-top:0;margin-bottom:5px;margin-left:20px;margin-left-ltr:22px}li{margin-bottom:1px}"
          + ".empty{color:#B8BEC6;font-style:italic}";   // margin-left-ltr: Swing's own name for the list indent
    }

    // ----- writing in the box: click the formatted text to edit; leaving the box (or Done, Esc) saves -----

    void startEdit() {
      if (areaKey == null || selected().size() != 1) return;
      editing = true;
      cardLayout.show(cards, "edit");
      area.requestFocusInWindow();
      area.setCaretPosition(area.getText().length());
    }

    void endEdit() {
      if (!editing) return;
      flushArea();
      editing = false;
      showDetails();   // the formatted text, as saved
      cardLayout.show(cards, "view");
    }

    void bind(JTextArea a, String keyStroke, Runnable r) {
      a.getInputMap().put(javax.swing.KeyStroke.getKeyStroke(keyStroke), keyStroke);
      a.getActionMap().put(keyStroke, new javax.swing.AbstractAction() {
        private static final long serialVersionUID = 1L;
        @Override public void actionPerformed(java.awt.event.ActionEvent e) { r.run(); }
      });
    }

    /** Puts the selection between marks (or takes them off); without a selection, places the caret between two marks. */
    void wrap(String m) {
      int a = area.getSelectionStart(), b = area.getSelectionEnd();
      String s = area.getSelectedText() == null ? "" : area.getSelectedText();
      if (s.length() >= 2 * m.length() && s.startsWith(m) && s.endsWith(m) && !(m.equals("*") && s.startsWith("**"))) {
        area.replaceSelection(s.substring(m.length(), s.length() - m.length()));
        area.select(a, b - 2 * m.length());
      } else {
        area.replaceSelection(m + s + m);
        if (s.isEmpty()) area.setCaretPosition(a + m.length()); else area.select(a, b + 2 * m.length());
      }
      area.requestFocusInWindow();
    }

    /** Makes the selected lines (or the current one) a bullet or numbered list; again on a list: back to plain lines. */
    void list(boolean numbered) {
      try {
        int l0 = area.getLineOfOffset(area.getSelectionStart()), l1 = area.getLineOfOffset(area.getSelectionEnd());
        if (l1 > l0 && area.getSelectionEnd() == area.getLineStartOffset(l1)) l1--;
        int start = area.getLineStartOffset(l0), end = area.getLineEndOffset(l1);
        String[] ls = area.getText().substring(start, end).split("\n", -1);
        boolean all = true;
        for (String l : ls) if (!l.trim().isEmpty()) all &= (numbered ? Md.NUMBER : Md.BULLET).matcher(l).matches();
        StringBuilder sb = new StringBuilder();
        int n = 1;
        for (int i = 0; i < ls.length; i++) {
          String l = ls[i];
          Matcher b = Md.BULLET.matcher(l), k = Md.NUMBER.matcher(l);
          String body = b.matches() ? b.group(1) : k.matches() ? k.group(2) : l.trim();
          if (i > 0) sb.append('\n');
          if (l.trim().isEmpty() && i == ls.length - 1 && ls.length > 1) continue;
          sb.append(all ? body : (numbered ? (n++) + ". " : "- ") + body);
        }
        area.replaceRange(sb.toString(), start, end);
        area.setCaretPosition(Math.min(area.getText().length(), start + sb.length()));
      } catch (javax.swing.text.BadLocationException e) { /* nothing selected */ }
      area.requestFocusInWindow();
    }

    /** Enter: in a list, the next line starts with the next bullet or number; Enter on an empty item ends the list. */
    void newLine() {
      try {
        int caret = area.getCaretPosition(), line = area.getLineOfOffset(caret), ls = area.getLineStartOffset(line);
        String before = area.getText(ls, caret - ls);
        Matcher b = Pattern.compile("^(\\s*)([-*\u2022])\\s+(.*)$").matcher(before), k = Md.NUMBER.matcher(before);
        if (b.matches() || k.matches()) {
          String rest = b.matches() ? b.group(3) : k.group(2);
          if (rest.trim().isEmpty()) {   // empty item: end the list
            area.replaceRange("", ls, caret);
            return;
          }
          area.replaceSelection("\n" + (b.matches() ? b.group(1) + b.group(2) + " " : (Integer.parseInt(k.group(1)) + 1) + ". "));
          return;
        }
      } catch (javax.swing.text.BadLocationException e) { /* plain new line */ }
      area.replaceSelection("\n");
    }

    /** Saves the box (comment or description) if it differs from what is stored; line breaks are kept. */
    void flushArea() {
      if (areaKey == null || !area.isEditable()) return;
      String s = area.getText().replace("\r", "").replaceAll("[ \\t]+\n", "\n").replaceAll("^\n+|\\s+$", "");
      if (areaKey.startsWith("case:")) {
        String cid = areaKey.substring(5);
        if (!byCase.containsKey(cid) || s.equals(description(cid))) return;
        saveDescription(cid, s);
      } else {
        String[] r = byId(areaKey);
        if (r == null || s.equals(r[COL_COMMENT])) return;
        saveVersion(r, COL_COMMENT, "comment", s);
      }
      if (!view.isEmpty()) model.fireTableRowsUpdated(0, view.size() - 1);   // keeps the selection
    }


    // ----- actions -----

    void openFolder() {
      List<Item> sel = selected();
      if (sel.isEmpty()) return;
      try { Ui.browse(dir.resolve(sel.get(0).caseId).toUri().toString()); } catch (Throwable t) { say("Cannot open: " + reason(t), red); }
    }

    void hideOrShow() {
      List<Item> sel = selected();
      if (sel.isEmpty()) return;
      try {
        if (hideBtn.getText().startsWith("Show")) {
          List<String> done = new ArrayList<>();
          for (Item it : sel) {
            if (it.isCase() || caseHidden(it.caseId)) setHidden(dir, it.caseId, null, false);
            else setHidden(dir, it.caseId, verOf(it.r[COL_ID]), false);
            done.add(it.isCase() ? it.caseId : it.r[COL_ID]);
          }
          updateCaseList(dir);
          reload();
          say("Shown again: " + String.join(", ", done), green);
          return;
        }
        Set<String> cases = new LinkedHashSet<>();
        List<String> vers = new ArrayList<>();
        for (Item it : sel) if (it.isCase()) cases.add(it.caseId); else vers.add(it.r[COL_ID]);
        vers.removeIf(v -> cases.contains(caseOf(v)));
        boolean whole;
        if (vers.isEmpty()) {
          whole = JOptionPane.showConfirmDialog(w, "Hide " + String.join(", ", cases) + " and all its versions?\n"
              + "It leaves the tracker and the case list; the files stay where they are.\nShow hidden + Show again brings it back.",
              "Hide", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE) == JOptionPane.OK_OPTION;
          if (!whole) return;
        } else {
          Set<String> their = new LinkedHashSet<>(cases);
          for (String v : vers) their.add(caseOf(v));
          int how = JOptionPane.showOptionDialog(w, "Hide from the tracker (the files stay where they are):\n" + String.join(", ", vers)
              + "\n\nShow hidden + Show again brings them back.", "Hide", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null,
              new String[] {"These versions", "The whole case" + (their.size() > 1 ? "s" : "") + " (" + String.join(", ", their) + ")", "Cancel"},
              "These versions");
          if (how < 0 || how == 2) return;
          whole = how == 1;
          if (whole) cases.addAll(their);
        }
        for (String cid : cases) setHidden(dir, cid, null, true);
        if (!whole) for (String v : vers) setHidden(dir, caseOf(v), verOf(v), true);
        updateCaseList(dir);
        reload();
        say("Hidden: " + String.join(", ", whole ? new ArrayList<>(cases) : vers) + "  (files kept; tick Show hidden to see them)", green);
      } catch (Throwable t) {
        say("NOT changed: " + reason(t), red);
      }
    }

    void changeFolder() {
      try {
        Path d = Ui.chooseDir("Case tracker: folder that holds the case folders", dir);
        Fs.allow(d);
        dir = d;
        expanded.clear();
        reload();
        updateCaseList(dir);
        say("Showing the cases in " + dir, grey);
      } catch (Throwable t) {
        if (!(t instanceof Cancel)) say("Cannot change folder: " + reason(t), red);
      }
    }

    void popup(java.awt.event.MouseEvent e) {
      if (!e.isPopupTrigger()) return;
      int row = table.rowAtPoint(e.getPoint());
      if (row >= 0 && !table.isRowSelected(row)) table.setRowSelectionInterval(row, row);
      javax.swing.JPopupMenu m = new javax.swing.JPopupMenu();
      javax.swing.JMenuItem open = new javax.swing.JMenuItem("Open case folder");
      open.addActionListener(a -> openFolder());
      javax.swing.JMenuItem hide = new javax.swing.JMenuItem(hideBtn.getText());
      hide.addActionListener(a -> hideOrShow());
      open.setEnabled(row >= 0);
      hide.setEnabled(row >= 0);
      javax.swing.JMenuItem ex = new javax.swing.JMenuItem("Unfold all cases");
      ex.addActionListener(a -> { expanded.addAll(byCase.keySet()); rebuild(); });
      javax.swing.JMenuItem co = new javax.swing.JMenuItem("Fold all cases");
      co.addActionListener(a -> { expanded.clear(); rebuild(); });
      m.add(open);
      m.add(hide);
      m.addSeparator();
      m.add(ex);
      m.add(co);
      m.show(table, e.getX(), e.getY());
    }

    // ----- look -----

    java.awt.Color rowBg(int row, boolean sel) {
      if (sel) return selBg;
      Item it = view.get(row);
      if (it.isCase()) return caseBg;
      int k = 0;
      for (int i = row - 1; i >= 0 && !view.get(i).isCase(); i--) k++;
      return k % 2 == 0 ? java.awt.Color.WHITE : stripe;
    }

    /** A comment or description in a cell: its first line formatted, "+n lines" in grey; the whole text, formatted, as
     *  the tooltip when there is more than the cell shows. */
    String tipHtml(String s) {
      return Md.lines(s).size() > 1 || s.length() > 60 ? "<html><head><style>" + viewerCss() + "</style></head><body style='width:440px'>"
          + Md.toHtml(s) + "</body></html>" : null;
    }

    String formatted(String s, boolean struck) {
      int more = Md.lines(s).size() - 1;
      String first = Md.firstLineHtml(s);
      return "<html>" + (struck ? "<s>" + first + "</s>" : first)
          + (more > 0 ? "&nbsp;&nbsp;<font color='#8A94A0'>+" + more + (more == 1 ? " line" : " lines") + "</font>" : "") + "</html>";
    }

    final class Cells extends javax.swing.table.DefaultTableCellRenderer {
      private static final long serialVersionUID = 1L;
      @Override
      public java.awt.Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int row, int col) {
        super.getTableCellRendererComponent(t, v, sel, false, row, col);
        Item it = view.get(row);
        setBackground(rowBg(row, sel));
        setForeground(java.awt.Color.BLACK);
        setIcon(null);
        setToolTipText(null);
        setHorizontalAlignment(LEFT);
        setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
        Font f = t.getFont();
        String s = v == null ? "" : String.valueOf(v);
        if (it.isCase()) {
          boolean ch = caseHidden(it.caseId);
          setFont(f.deriveFont(ch ? Font.BOLD | Font.ITALIC : Font.BOLD));
          if (ch) setForeground(grey);
          if (col == T_ID) {
            setIcon(trackerIcon(expanded.contains(it.caseId) || filtering() ? 1 : 0));
            setIconTextGap(6);
          } else if (col == T_COMMENT && s.isEmpty()) {
            setFont(f.deriveFont(Font.ITALIC));
            setForeground(pale);
            s = "no description (double-click to add)";
          } else if (col == T_COMMENT) {
            setToolTipText(tipHtml(s));
            s = formatted(s, false);
          } else if (col == T_SAVED) {
            int n = byCase.get(it.caseId).size();
            setFont(f);
            setForeground(grey);
            s = n + (n == 1 ? " version" : " versions");
          }
          setText(s);
          return this;
        }
        boolean h = hidden(it.r) || caseHidden(it.caseId);
        boolean dropped = "dropped".equals(it.r[COL_STATUS]);
        setFont(f.deriveFont(h ? Font.ITALIC : Font.PLAIN));
        if (h || dropped) setForeground(grey);
        switch (col) {
          case T_ID: {
            setBorder(BorderFactory.createEmptyBorder(0, 24, 0, 6));
            setIcon(trackerIcon(s.equals(openId) ? 2 : 3));
            setIconTextGap(6);
            if (s.equals(openId)) setToolTipText("Open in STAR-CCM+ now");
            String lab = fileLabel(it.r[COL_FILE]);
            setText(lab.isEmpty() ? s : "<html>" + html(s) + "&nbsp;&nbsp;<font color='#8A94A0'>" + html(lab) + "</font></html>");
            setToolTipText(it.r[COL_FILE] + (s.equals(openId) ? "  (open in STAR-CCM+ now)" : ""));
            return this;
          }
          case T_COMMENT:
            if (s.isEmpty()) { setForeground(pale); setText("—"); }
            else { setText(formatted(s, dropped)); setToolTipText(tipHtml(s)); }
            return this;
          case T_BASED:
            if (s.isEmpty()) { setForeground(pale); setText("—"); }
            else if (byId(s) != null) {
              setText("<html><u>" + html(s) + "</u></html>");
              if (!sel && !h) setForeground(link);
              setToolTipText("Go to " + s);
            } else setText(s);
            return this;
          default:
            setText(shortDate(s));
            setToolTipText(s);
            return this;
        }
      }
    }

    /** Status as a small coloured label. */
    final class Badge extends javax.swing.JComponent implements javax.swing.table.TableCellRenderer {
      private static final long serialVersionUID = 1L;
      String text = "";
      java.awt.Color bg = java.awt.Color.WHITE;
      boolean isCase;
      @Override
      public java.awt.Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int row, int col) {
        isCase = view.get(row).isCase();
        text = isCase ? "" : v == null ? "" : String.valueOf(v);
        bg = rowBg(row, sel);
        setFont(t.getFont());
        setToolTipText(view.get(row).isCase() ? null : "Click to change the status");
        return this;
      }
      @Override protected void paintComponent(java.awt.Graphics g0) {
        java.awt.Graphics2D g = (java.awt.Graphics2D) g0.create();
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(bg);
        g.fillRect(0, 0, getWidth(), getHeight());
        java.awt.FontMetrics fm = g.getFontMetrics(getFont());
        g.setFont(getFont());
        java.awt.Color[] c = statusColors(text);
        if (c == null) {
          if (text.isEmpty() && !isCase) {
            g.setColor(pale);
            g.drawString("—", 8, (getHeight() + fm.getAscent() - fm.getDescent()) / 2);
          }
          g.dispose();
          return;
        }
        int tw = fm.stringWidth(text), h = fm.getHeight() + 2, y = (getHeight() - h) / 2;
        g.setColor(c[0]);
        g.fillRoundRect(6, y, tw + 16, h, h, h);
        g.setColor(c[1]);
        g.drawString(text, 14, y + (h + fm.getAscent() - fm.getDescent()) / 2);
        g.dispose();
      }
    }
  }

  void toolCaseList() throws IOException {
    CaseInfo c = caseOrNull();
    Path dir = c != null ? c.casesDir : Ui.chooseDir("Case list: folder that holds the case folders", defaultCasesDir());
    Fs.allow(dir);
    Path f = updateCaseList(dir);
    if (f == null) return;
    StringBuilder sb = new StringBuilder();
    for (String[] r : caseRows(dir)) {
      sb.append(String.format("%-12s %-26s %-40s %s%n", r[0], Fmt.cut(r[3], 26), Fmt.cut(r[1], 40), r[5]));
    }
    log.line(sb.length() == 0 ? "no cases in " + dir : sb.toString());
    log.line("Google Sheets: File > Import > Upload " + f.getFileName() + " > Replace current sheet (in the tab 'Cases').");
  }

  /** Rebuilds <cases folder>/case_list.csv from every case_identity.txt. Never stops the calling tool. */
  Path updateCaseList(Path casesDir) {
    try {
      Path f = casesDir.resolve(CASE_LIST);
      Csv.write(f, caseListHeader(), caseRows(casesDir));
      log.line("case list -> " + f);
      return f;
    } catch (Throwable t) {
      log.na("case list (close it if it is open in another program)", t);
      return null;
    }
  }

  /** Case list columns: identity, then "results_of" and one column per key result. */
  static String[] caseListHeader() {
    String[] h = Arrays.copyOf(CASE_LIST_COLS, CASE_LIST_COLS.length + 1 + KEY_COLS.length);
    h[CASE_LIST_COLS.length] = "results_of";
    System.arraycopy(KEY_COLS, 0, h, CASE_LIST_COLS.length + 1, KEY_COLS.length);
    return h;
  }

  static List<String[]> caseRows(Path casesDir) {
    List<String[]> rows = new ArrayList<>();
    for (Path d : Fs.list(casesDir, "*")) {
      if (!CASE_ID.matcher(d.getFileName().toString()).matches()) continue;
      Map<String, String> id = readIdentity(d.resolve("sim").resolve("case_identity.txt"));
      if (id.isEmpty() || "yes".equals(id.get("hidden"))) continue;   // hidden in the tracker: not listed
      String[] r = new String[caseListHeader().length];
      Arrays.fill(r, "");
      for (int i = 0; i < CASE_LIST_COLS.length; i++) r[i] = id.getOrDefault(CASE_LIST_COLS[i], "");
      if (r[0].isEmpty()) r[0] = d.getFileName().toString();
      Path kr = d.resolve("record").resolve(KEY_RESULTS);
      if (Files.isRegularFile(kr)) {
        try {
          List<String[]> k = readTable(kr, new String[] {"report", "value", "how", "sim", "date"});
          for (String[] row : k) {
            int j = Arrays.asList(KEY_COLS).indexOf(row[0]);
            if (j >= 0) r[CASE_LIST_COLS.length + 1 + j] = row[1];
            if (!k.isEmpty()) r[CASE_LIST_COLS.length] = row[3] + " (" + row[4] + ")";
          }
        } catch (IOException e) { r[CASE_LIST_COLS.length] = "key_results.csv unreadable"; }
      }
      rows.add(r);
    }
    return rows;
  }

  // =====================================================================================================
  // Object inventory (what the name check works on)
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
    try { collectMeshOps(out); } catch (Throwable t) { log.na("mesh operations", t); }
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

  /** [U09] Plays another macro file inside this session. */
  void runMacroFile(Path p) {
    new StarScript(getActiveRootObject(), new java.io.File(p.toString())).play();                        // [U09]
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
  // Names (used by Check)
  // =====================================================================================================

  /** Naming issues {object_type, path, name, rule, message}: default STAR-CCM+ names on any object, and part /
   *  part-surface names against config/naming_rules.csv. Read only. */
  List<String[]> namingIssues() {
    List<String[]> rules = config("naming_rules.csv", new String[] {"object", "pattern", "example", "explanation"},
        DEFAULT_NAMING_RULES);
    Pattern def = Pattern.compile(DEFAULT_NAME_REGEX);
    List<String[]> issues = new ArrayList<>();
    for (Obj ob : collect(false)) {
      String n = ob.name();
      if (def.matcher(n).find()) {
        issues.add(new String[] {ob.type, ob.path, n, "default name", "default STAR-CCM+ name: give it a meaningful name"});
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
        issues.add(new String[] {ob.type, ob.path, n, failed[3], "expected " + failed[3] + " (e.g. " + failed[2] + ")"});
      }
    }
    return issues;
  }

  // =====================================================================================================
  // Check (read only)
  // =====================================================================================================

  /** Check -> PASS / WARN / FAIL lines + record/check_<sim>.csv. FAIL = the run would fail or be wrong. */
  void toolCheck() throws IOException {
    CaseInfo c = caseOrNull();
    List<String[]> rows = new ArrayList<>();
    checkRegions(rows);
    checkBoundaryTypes(rows);
    checkBoundaryValues(rows);
    checkMesh(rows);
    checkStopping(rows);
    String as = autoSaveState();
    rows.add(chk("enabled".equals(as) ? "PASS" : "WARN", "auto-save during the run", as));
    List<String[]> names = namingIssues();
    rows.add(chk(names.isEmpty() ? "PASS" : "WARN", "names", names.isEmpty() ? "parts, surfaces and default names OK"
        : names.size() + " to improve (list below)"));
    rows.add(c == null ? chk("WARN", "case folder", "not a SimKit case: use Save new version to keep a record of it")
        : chk("PASS", "case folder", c.caseDir.toString()));
    int fail = 0, warn = 0;
    StringBuilder sb = new StringBuilder();
    for (String[] r : rows) {
      sb.append(String.format("%-5s %-26s %s%n", r[0], r[1], r[2]));
      if ("FAIL".equals(r[0])) fail++;
      if ("WARN".equals(r[0])) warn++;
    }
    log.line("\n" + sb + "=> " + (fail > 0 ? "FAIL" : warn > 0 ? "WARN" : "PASS") + " (" + fail + " fail, " + warn + " warn)");
    if (!names.isEmpty()) {
      StringBuilder t = new StringBuilder("\nNames to improve (first 15):\n");
      for (int i = 0; i < Math.min(15, names.size()); i++) {
        String[] n = names.get(i);
        t.append(String.format("  %-44s %s%n", Fmt.cut(n[1], 44), n[4]));
      }
      log.line(t.toString());
    }
    if (c == null) return;
    List<String[]> all = new ArrayList<>(rows);
    for (String[] n : names) all.add(chk("WARN", "name", n[1] + ": " + n[4]));
    Path out = c.sub("record").resolve("check_" + c.simBase() + ".csv");
    Csv.write(out, CHECK_HEADER, all);
    log.line("written " + out);
  }

  static String[] chk(String level, String check, String detail) { return new String[] {level, check, detail}; }

  void checkStopping(List<String[]> rows) {
    List<String[]> crit = stoppingCriteria();
    int used = 0;
    for (String[] c : crit) if (!"false".equals(c[2])) used++;
    rows.add(used > 0 ? chk("PASS", "stopping criteria", used + " in use")
        : chk("FAIL", "stopping criteria", "none in use: the run would not stop"));
  }

  void checkRegions(List<String[]> rows) {
    try {
      List<String> none = new ArrayList<>();
      int n = 0;
      for (Region r : sim.getRegionManager().getRegions()) {
        n++;
        if (r.getPhysicsContinuum() == null) none.add(nm(r));
      }
      rows.add(n == 0 ? chk("FAIL", "regions have physics", "no regions")
          : none.isEmpty() ? chk("PASS", "regions have physics", n + " region(s)")
          : chk("FAIL", "regions have physics", "without a physics continuum: " + String.join(" ", none)));
    } catch (Throwable t) {
      rows.add(chk("WARN", "regions have physics", "n/a (" + reason(t) + ")"));
    }
  }

  /** Boundary types that fit each surface prefix (simple class names of the STAR-CCM+ boundary types). */
  static final String[][] BC_TYPES = {
      {"IN", "InletBoundary MassFlowBoundary StagnationBoundary FreeStreamBoundary", "an inlet (velocity, mass flow, stagnation or free stream)"},
      {"OUT", "PressureBoundary OutletBoundary FreeStreamBoundary MassFlowBoundary", "an outlet (pressure, flow-split, free stream or mass flow)"},
      {"OPEN", "PressureBoundary StagnationBoundary FreeStreamBoundary", "an open boundary (pressure, stagnation or free stream)"},
      {"WALL", "WallBoundary", "a wall"},
      {"SYM", "SymmetryBoundary", "a symmetry plane"},
      {"IF", "*Interface*", "part of an interface (contact between regions)"}};

  /** Prefix of a boundary name (IN, OUT, OPEN, WALL, SYM, IF) from its surface part ("Part.IN_air" -> IN); null if none. */
  static String bcToken(String boundaryName) {
    String n = boundaryName.substring(boundaryName.lastIndexOf('.') + 1);
    Matcher m = Pattern.compile("^(IN|OUT|OPEN|WALL|SYM|IF)_").matcher(n);
    return m.find() ? m.group(1) : null;
  }

  /** Boundaries of all regions grouped by prefix token. */
  Map<String, List<Boundary>> boundariesByToken() {
    Map<String, List<Boundary>> out = new LinkedHashMap<>();
    for (Region r : sim.getRegionManager().getRegions()) {
      for (Boundary b : r.getBoundaryManager().getBoundaries()) {
        String t = bcToken(nm(b));
        if (t != null) out.computeIfAbsent(t, k -> new ArrayList<>()).add(b);
      }
    }
    return out;
  }

  /** Each IN_/OUT_/OPEN_/WALL_/SYM_/IF_ boundary must have a matching type (catches e.g. an inlet left as a wall). */
  void checkBoundaryTypes(List<String[]> rows) {
    try {
      List<String> bad = new ArrayList<>();
      int n = 0;
      for (Map.Entry<String, List<Boundary>> e : boundariesByToken().entrySet()) {
        String[] rule = null;
        for (String[] r : BC_TYPES) if (r[0].equals(e.getKey())) rule = r;
        for (Boundary b : e.getValue()) {
          n++;
          String type = cls(b.getBoundaryType());
          boolean ok = rule[1].startsWith("*") ? type.contains("Interface")
              : Arrays.asList(rule[1].split(" ")).contains(type);
          if (!ok) bad.add(nm(b.getRegion()) + "/" + nm(b) + " is " + nm(b.getBoundaryType()) + ", expected " + rule[2]);
        }
      }
      rows.add(n == 0 ? chk("WARN", "boundary types", "no IN_/OUT_/OPEN_/WALL_/SYM_/IF_ boundaries: types not checked")
          : bad.isEmpty() ? chk("PASS", "boundary types", n + " boundaries match their names")
          : chk("FAIL", "boundary types", bad.size() + " do not match their names: " + String.join("; ", bad)));
    } catch (Throwable t) {
      rows.add(chk("WARN", "boundary types", "n/a (" + reason(t) + ")"));
    }
  }

  /** Constant value (SI) of a boundary profile, e.g. star.flow.MassFlowRateProfile; null if absent or not constant. */
  static Double profileValue(Boundary b, String profileClass) {
    try {
      Class<?> k = Reflect.cls(profileClass);
      if (k == null) return null;
      Object prof = Reflect.invoke(b.getValues(), "get", k);
      Object meth = Reflect.invoke(prof, "getMethod", Reflect.cls("star.common.ConstantScalarProfileMethod"));
      Object q = Reflect.invoke(meth, "getQuantity");
      Object v = Reflect.tryCall(q, "getSIValue");
      if (v == null) v = Reflect.tryCall(q, "getRawValue");
      return v instanceof Number ? ((Number) v).doubleValue() : null;
    } catch (Throwable t) {
      return null;   // not this kind of boundary, or a field function / table: not checked
    }
  }

  /** Values most often forgotten: inlet flow 0, inlet temperature still at STAR's 300 K, reference pressure 0 with an
   *  ideal gas, gravity off in a case with energy and an ideal gas. FAIL = the run is wrong; WARN = please confirm. */
  void checkBoundaryValues(List<String[]> rows) {
    try {
      List<String> fail = new ArrayList<>(), warn = new ArrayList<>();
      String models = "";
      for (Object co : sim.getContinuumManager().getObjects()) {
        if (co instanceof PhysicsContinuum) for (Object md : ((PhysicsContinuum) co).getModelManager().getObjects()) models += nm(md) + ";";
      }
      boolean energy = fieldFunction("Temperature") != null, idealGas = models.contains("Ideal Gas");
      for (Boundary b : boundariesByToken().getOrDefault("IN", Collections.emptyList())) {
        String where = nm(b.getRegion()) + "/" + nm(b);
        String type = cls(b.getBoundaryType());
        Double flow = type.equals("MassFlowBoundary") ? profileValue(b, "star.flow.MassFlowRateProfile")
            : type.equals("InletBoundary") ? profileValue(b, "star.flow.VelocityMagnitudeProfile") : null;
        if (flow != null && flow == 0) fail.add(where + ": " + (type.equals("MassFlowBoundary") ? "mass flow" : "velocity") + " is 0");
        if (energy) {
          Double t = profileValue(b, "star.energy.TotalTemperatureProfile");
          if (t == null) t = profileValue(b, "star.energy.StaticTemperatureProfile");
          if (t != null && Math.abs(t - 300.0) < 1e-9) warn.add(where + ": temperature still at STAR's default 300 K");
        }
      }
      if (idealGas) {
        for (Object co : sim.getContinuumManager().getObjects()) {
          if (!(co instanceof PhysicsContinuum)) continue;
          try {
            Object rp = Reflect.invoke(((PhysicsContinuum) co).getReferenceValues(), "get", Reflect.cls("star.flow.ReferencePressure"));
            Object v = Reflect.tryCall(rp, "getSIValue");
            if (v instanceof Number && ((Number) v).doubleValue() == 0) fail.add(nm(co) + ": reference pressure is 0 Pa with an ideal gas");
          } catch (Throwable t) { /* not readable: not checked */ }
        }
        if (energy && !models.contains("Gravity")) warn.add("gravity is off (buoyancy ignored) in a case with energy and an ideal gas");
      }
      rows.add(!fail.isEmpty() ? chk("FAIL", "boundary values", String.join("; ", fail) + (warn.isEmpty() ? "" : "; " + String.join("; ", warn)))
          : !warn.isEmpty() ? chk("WARN", "boundary values", String.join("; ", warn) + " -- intended?")
          : chk("PASS", "boundary values", "no inlet at 0, no value left at a suspicious default"));
    } catch (Throwable t) {
      rows.add(chk("WARN", "boundary values", "n/a (" + reason(t) + ")"));
    }
  }

  void checkMesh(List<String[]> rows) {
    rows.add(volumeMeshExists() ? chk("PASS", "volume mesh", "present")
        : chk("FAIL", "volume mesh", "no volume mesh found (generate the mesh before running)"));
  }

  // ---------------------------------------------------------------- audit

  /** Key facts of a set-up (shown in the case card). */
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

  /** Set-up record: writes record/setup_<sim>.csv and record/case_card_<sim>.html (silent, at every save). */
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
    a.file = Fs.guard(c.auditFile());
    Csv.write(a.file, AUDIT_HEADER, a.rows);
    Fs.write(c.cardFile(), caseCard(c, a, readIdentity(c)));
    log.line("set-up record: " + a.rows.size() + " rows -> " + a.file + "\ncase card -> " + c.cardFile());
    return a;
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
      }
    }
  }

  void auditInterfaces(Audit a) {
    for (Object i : sim.getInterfaceManager().getObjects()) {
      String[] r = interfaceRegions(i);
      a.add("INTERFACES", nm(i), "type", cls(i));
      a.add("INTERFACES", nm(i), "regions", r == null ? "n/a" : r[0] + " | " + r[1]);
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

  /** One-page HTML summary of the audit, case identity at the top. */
  static String caseCard(CaseInfo c, Audit a, Map<String, String> id) {
    StringBuilder h = new StringBuilder("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>")
        .append(Html.esc(c.simBase())).append("</title><style>")
        .append("body{font-family:Segoe UI,Arial,sans-serif;font-size:12px;margin:16px;color:#222}")
        .append("h1{font-size:18px;margin:0 0 6px}h2{font-size:13px;margin:14px 0 4px;border-bottom:1px solid #999}")
        .append("table{border-collapse:collapse;width:100%}td,th{border:1px solid #ccc;padding:2px 6px;")
        .append("text-align:left;vertical-align:top}th{background:#eee}.k{width:180px;font-weight:bold}" + MD_CSS)
        .append("</style></head><body>\n<h1>").append(Html.esc(c.caseId)).append(" &mdash; ")
        .append(Html.esc(c.label)).append(" v").append(Fmt.two(c.version)).append("</h1>\n<table>");
    String[][] ident = {{"ID", versionId(c)}, {"Project / configuration", c.proj + " / "
        + id.getOrDefault("configuration", c.cfg == null ? "" : c.cfg)},
        {"Label / version", (c.label.isEmpty() ? "-" : c.label) + " / v" + Fmt.two(c.version)},
        {"Based on", asVersionId(id.getOrDefault("forked_v" + Fmt.two(c.version), ""))},
        {"Parent case", id.getOrDefault("parent_case", "")},
        {"Description", id.getOrDefault("description", "")}, {"Sim file", c.simFile.toString()},
        {"Key facts", "cells " + a.facts.cells + "; time step " + a.facts.dt + "; end " + a.facts.maxTime},
        {"Created", id.getOrDefault("created", "") + " " + id.getOrDefault("created_by", "")},
        {"Written", Fmt.iso() + " by " + Env.user() + " with " + VERSION}};
    for (String[] r : ident) {
      h.append("<tr><td class=\"k\">").append(Html.esc(r[0])).append("</td><td>").append(identCell(r)).append("</td></tr>");
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

  /** Compare versions: current set-up vs previous version, parent case or any record file. */
  void toolCompare() throws IOException {
    CaseInfo c = requireCase();
    Audit now = writeAudit(c);
    String[] opts = {"Previous version", "Parent case", "Choose a record file...", "Cancel"};
    int o = Ui.option("Compare versions", "Compare the current set-up of " + c.simBase() + " with:", opts);
    Path other;
    if (o == 0) other = latestAudit(c.sub("record"), c.caseId, c.version);
    else if (o == 1) other = parentAudit(c);
    else if (o == 2) other = Ui.chooseFile(c.sub("record"));
    else throw new Cancel(null);
    if (other == null || !Files.isRegularFile(other)) throw new Cancel("no record found to compare with");
    String otherBase = other.getFileName().toString().replaceFirst("^setup_", "").replaceFirst("\\.csv$", "");
    Path out = c.sub("record").resolve("changes_" + Fs.fileSafe(otherBase) + "_to_" + c.simBase() + ".csv");
    String summary = diffAudits(other, now.file, out);
    log.line("summary: " + summary);
  }

  /** Highest-version set-up record of a case below 'belowVersion' (Integer.MAX_VALUE = any). */
  static Path latestAudit(Path auditDir, String caseId, int belowVersion) {
    Pattern p = Pattern.compile("^setup_" + Pattern.quote(caseId) + "(_[A-Za-z][A-Za-z0-9]*)?_v([0-9]{2,3})\\.csv$");
    Path best = null;
    int bestV = -1;
    for (Path f : Fs.list(auditDir, "setup_*.csv")) {
      Matcher m = p.matcher(f.getFileName().toString());
      if (!m.matches()) continue;
      int v = Integer.parseInt(m.group(2));
      if (v < belowVersion && v > bestV) { best = f; bestV = v; }
    }
    return best;
  }

  Path parentAudit(CaseInfo c) {
    String parent = readIdentity(c).getOrDefault("parent_case", "");
    if (parent.isEmpty()) throw new Cancel("no parent_case in " + c.identityFile());
    String pf = readIdentity(c).getOrDefault("parent_file", "");
    Path dir = c.casesDir.resolve(parent).resolve("record");
    try {
      Path pd = Paths.get(pf).getParent();
      if (pd != null && pd.getParent() != null && Files.isDirectory(pd.getParent().resolve("record"))) {
        dir = pd.getParent().resolve("record");
      }
    } catch (Throwable t) { /* old identity file: sibling folder */ }
    return latestAudit(dir, parent, Integer.MAX_VALUE);
  }

  /** Differences of two set-up records (VERSION section ignored); writes the CSV; returns a summary. */
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
    log.line("list of changes: " + rows.size() + " line(s) -> " + out);
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
  // Standard reports (built from the surface prefixes)
  // =====================================================================================================

  /** name, report class, field function ("" = none), targets (tokens joined by +, or REGIONS), plot group
   *  ("" = monitor only, "-" = no monitor), condition ("" / energy / unsteady), meaning. */
  static final String[][] STANDARD_REPORTS = {
      {"r_mdot_in", "star.flow.MassFlowReport", "", "IN", "massBalance", "", "mass flow entering (IN_ surfaces)"},
      {"r_mdot_out", "star.flow.MassFlowReport", "", "OUT+OPEN", "massBalance", "", "mass flow leaving (OUT_ and OPEN_)"},
      {"r_mdot_net", "star.flow.MassFlowReport", "", "IN+OUT+OPEN", "massBalance", "", "net mass flow: tends to 0 when mass is conserved"},
      {"r_p_in_avg", "star.base.report.AreaAverageReport", "Pressure", "IN", "pressure", "", "inlet static pressure (area average)"},
      {"r_p_out_avg", "star.base.report.AreaAverageReport", "Pressure", "OUT", "pressure", "", "outlet static pressure (area average)"},
      {"r_T_in_mfavg", "star.flow.MassFlowAverageReport", "Temperature", "IN", "temperature", "energy", "inlet temperature (mass-flow average)"},
      {"r_T_out_mfavg", "star.flow.MassFlowAverageReport", "Temperature", "OUT+OPEN", "temperature", "energy", "outlet temperature (mass-flow average)"},
      {"r_Q_wall", "star.energy.HeatTransferReport", "", "WALL", "heat", "energy", "heat flow through the walls"},
      {"r_yplus_wall_max", "star.base.report.MaxReport", "WallYplus", "WALL", "yplus", "", "maximum wall y+"},
      {"r_yplus_wall_avg", "star.base.report.AreaAverageReport", "WallYplus", "WALL", "yplus", "", "average wall y+"},
      {"r_Co_max", "star.base.report.MaxReport", "CourantNumber", "REGIONS", "courant", "unsteady", "maximum convective Courant number"}};

  /** Standard reports: proposes the reports that fit this sim (surface prefixes, models), the user picks, SimKit
   *  creates the reports, their monitors and one plot per group. Existing objects are never changed or replaced. */
  void toolStandardReports() {
    Map<String, List<Boundary>> bc = boundariesByToken();
    List<Object> regions = new ArrayList<>(sim.getRegionManager().getRegions());
    Set<String> existing = new HashSet<>();
    for (Object r : sim.getReportManager().getObjects()) existing.add(nm(r));
    boolean unsteady = unsteadySolver() != null;
    boolean energy = fieldFunction("Temperature") != null;
    List<String[]> plan = new ArrayList<>();
    List<List<Object>> parts = new ArrayList<>();
    StringBuilder skipped = new StringBuilder();
    for (String[] r : STANDARD_REPORTS) {
      String why = null;
      List<Object> target = new ArrayList<>();
      if (r[3].equals("REGIONS")) target.addAll(regions);
      else for (String t : r[3].split("\\+")) target.addAll(bc.getOrDefault(t, Collections.emptyList()));
      if (existing.contains(r[0])) why = "already exists";
      else if (target.isEmpty()) why = "no " + r[3].replace("+", "_/") + "_ surfaces";
      else if (r[5].equals("energy") && !energy) why = "no energy model (no Temperature)";
      else if (r[5].equals("unsteady") && !unsteady) why = "steady simulation";
      else if (!r[2].isEmpty() && fieldFunction(r[2]) == null) why = "field " + r[2] + " not available";
      else if (Reflect.cls(r[1]) == null) why = "report type not available in this release";
      if (r[0].equals("r_mdot_net") && (bc.getOrDefault("IN", Collections.emptyList()).isEmpty()
          || (bc.getOrDefault("OUT", Collections.emptyList()).isEmpty() && bc.getOrDefault("OPEN", Collections.emptyList()).isEmpty()))) {
        why = why == null ? "needs both IN_ and OUT_/OPEN_ surfaces" : why;
      }
      if (why != null) {
        skipped.append(String.format("  %-18s %s%n", r[0], why));
        continue;
      }
      plan.add(r);
      parts.add(target);
    }
    if (plan.isEmpty()) {
      log.line("nothing to add.\n" + skipped + "\nName the surfaces IN_..., OUT_..., OPEN_..., WALL_... to get these reports.");
      return;
    }
    String[] labels = new String[plan.size()];
    for (int i = 0; i < labels.length; i++) {
      String[] r = plan.get(i);
      labels[i] = String.format("%-18s %-44s on %d object(s)", r[0], r[6], parts.get(i).size());
    }
    int[] pick = gui ? Ui.pick("Standard reports", "Reports to create (with their monitors and one plot per group):",
        labels, true) : allIndices(labels.length);
    Map<String, List<Object>> groups = new LinkedHashMap<>();
    List<Object> monitorOnly = new ArrayList<>();
    int made = 0;
    for (int i : pick) {
      String[] r = plan.get(i);
      try {
        Object report = Reflect.invoke(sim.getReportManager(), "createReport", Reflect.cls(r[1]));
        ((NamedObject) report).setPresentationName(r[0]);
        if (!r[2].isEmpty()) Reflect.invoke(report, "setFieldFunction", fieldFunction(r[2]));
        Reflect.invoke(Reflect.invoke(report, "getParts"), "setObjects", new NeoObjectVector(parts.get(i).toArray()));
        made++;
        log.line(String.format("  created %-18s %s", r[0], r[6]));
        if (r[4].equals("-")) continue;
        if (r[4].isEmpty()) monitorOnly.add(report);
        else groups.computeIfAbsent(r[4], k -> new ArrayList<>()).add(report);
      } catch (Throwable t) {
        log.na("report " + r[0], t);
      }
    }
    for (Map.Entry<String, List<Object>> g : groups.entrySet()) monitorsAndPlot(g.getValue(), "pl_" + g.getKey());
    if (!monitorOnly.isEmpty()) monitorsAndPlot(monitorOnly, null);
    log.line(made + " report(s) created" + (skipped.length() == 0 ? "" : "; not proposed:\n" + skipped));
    result(made + " report(s) with monitors and plots created");
  }

  static int[] allIndices(int n) {
    int[] a = new int[n];
    for (int i = 0; i < n; i++) a[i] = i;
    return a;
  }

  /** Field function by its function name (e.g. WallYplus, CourantNumber), or null if this sim does not have it.
   *  (FieldFunctionManager.has() looks at display names, so it is not used.) */
  Object fieldFunction(String name) {
    try {
      for (FieldFunction f : sim.getFieldFunctionManager().getObjects()) {
        if (name.equals(f.getFunctionName())) return f;
      }
    } catch (Throwable t) { /* treated as missing */ }
    return null;
  }

  /** One monitor per report (Report.createMonitor) and ONE plot named 'plot' showing them all; plot == null:
   *  monitors only. [U24] */
  void monitorsAndPlot(List<Object> reports, String plot) {
    List<Monitor> monitors = new ArrayList<>();
    for (Object r : reports) {
      try { monitors.add(((Report) r).createMonitor()); }                                                // [U24]
      catch (Throwable t) { log.na("monitor of " + nm(r), t); }
    }
    if (plot == null || monitors.isEmpty()) {
      log.line("  monitors for " + monitors.size() + " report(s)");
      return;
    }
    try {
      MonitorPlot p = sim.getPlotManager().createMonitorPlot(monitors, plot);                             // [U24]
      if (!plot.equals(nm(p))) p.setPresentationName(plot);
      log.line("  monitors + plot " + plot + " (" + monitors.size() + " report(s))");
    } catch (Throwable t) {
      log.na("plot " + plot + " (monitors were created)", t);
    }
  }

  // =====================================================================================================
  // Standard scenes
  // =====================================================================================================

  /** Standard scenes: wall scenes on the WALL_ surfaces and mid-plane scenes on a plane through the domain centre.
   *  Proposes only what fits (fields present), the user picks, existing scenes / planes are reused, never changed. */
  void toolStandardScenes() {
    List<Boundary> walls = boundariesByToken().getOrDefault("WALL", Collections.emptyList());
    List<Region> regions = new ArrayList<>(sim.getRegionManager().getRegions());
    Set<String> existing = new HashSet<>();
    for (Scene s : sim.getSceneManager().getScenes()) existing.add(nm(s));
    String axis = gui ? new String[] {"X", "Y", "Z"}[Ui.option("Standard scenes",
        "Mid-plane through the centre of the domain, normal to:", new String[] {"X", "Y", "Z"})] : "Z";
    String plane = "dp_plane_mid" + axis;
    // name, field ("" = mesh), target (WALL / PLANE), condition, meaning
    String[][] specs = {
        {"sc_yplus_walls", "WallYplus", "WALL", "", "wall y+ on the WALL_ surfaces"},
        {"sc_T_walls", "Temperature", "WALL", "energy", "temperature on the WALL_ surfaces"},
        {"sc_U_" + plane.substring(3), "Velocity", "PLANE", "", "velocity magnitude on " + plane},
        {"sc_p_" + plane.substring(3), "Pressure", "PLANE", "", "pressure on " + plane},
        {"sc_T_" + plane.substring(3), "Temperature", "PLANE", "energy", "temperature on " + plane},
        {"sc_mesh_" + plane.substring(3), "", "PLANE", "", "mesh cut by " + plane}};
    boolean energy = fieldFunction("Temperature") != null;
    List<String[]> plan = new ArrayList<>();
    StringBuilder skipped = new StringBuilder();
    for (String[] sp : specs) {
      String why = existing.contains(sp[0]) ? "already exists"
          : sp[2].equals("WALL") && walls.isEmpty() ? "no WALL_ surfaces"
          : sp[3].equals("energy") && !energy ? "no energy model"
          : !sp[1].isEmpty() && fieldFunction(sp[1]) == null ? "field " + sp[1] + " not available" : null;
      if (why != null) skipped.append(String.format("  %-22s %s%n", sp[0], why));
      else plan.add(sp);
    }
    if (plan.isEmpty()) {
      log.line("nothing to add.\n" + skipped);
      return;
    }
    String[] labels = new String[plan.size()];
    for (int i = 0; i < labels.length; i++) labels[i] = String.format("%-22s %s", plan.get(i)[0], plan.get(i)[4]);
    int[] pick = gui ? Ui.pick("Standard scenes", "Scenes to create (Export results saves them as images):", labels, true)
        : allIndices(labels.length);
    Object planePart = null;
    int made = 0;
    for (int i : pick) {
      String[] sp = plan.get(i);
      try {
        List<Object> parts = new ArrayList<>();
        if (sp[2].equals("WALL")) parts.addAll(walls);
        else {
          if (planePart == null) planePart = midPlane(plane, axis, regions);
          parts.add(planePart);
        }
        Scene sc = sim.getSceneManager().createScene();                                                  // [U27]
        sc.setPresentationName(sp[0]);
        List<Object> all = new ArrayList<>();
        for (Region r : regions) all.addAll(r.getBoundaryManager().getBoundaries());
        PartDisplayer outline = sc.getDisplayerManager().createPartDisplayer("Outline");                 // [U27]
        outline.setOutline(true);
        outline.setSurface(false);
        outline.getInputParts().setObjects(named(all));
        if (sp[1].isEmpty()) {
          PartDisplayer mesh = sc.getDisplayerManager().createPartDisplayer("Mesh");
          mesh.setMesh(true);
          mesh.getInputParts().setObjects(named(parts));
        } else {
          ScalarDisplayer d = sc.getDisplayerManager().createScalarDisplayer("Scalar");                  // [U27]
          d.getInputParts().setObjects(named(parts));
          Object ff = fieldFunction(sp[1]);
          Object type = Reflect.tryCall(ff, "getType");
          boolean vector = (type != null && cls(type).contains("Vector")) || sp[1].equals("Velocity");
          Object mag = vector ? Reflect.tryCall(ff, "getMagnitudeFunction") : null;   // only for vectors (Velocity)
          d.getScalarDisplayQuantity().setFieldFunction((FieldFunction) (mag != null ? mag : ff));
        }
        made++;
        log.line(String.format("  created %-22s %s", sp[0], sp[4]));
      } catch (Throwable t) {
        log.na("scene " + sp[0], t);
      }
    }
    result(made + " scene(s) created");
    log.line(made + " scene(s) created" + (skipped.length() == 0 ? "" : "; not proposed:\n" + skipped)
        + "\nAdjust the views once if you like (camera, colour range): Export results saves the scenes as they are.");
  }

  static List<NamedObject> named(List<?> objs) {
    List<NamedObject> out = new ArrayList<>();
    for (Object o : objs) if (o instanceof NamedObject) out.add((NamedObject) o);
    return out;
  }

  /** Mid-plane section through the centre of the regions' bounding box (existing plane of that name is reused). */
  Object midPlane(String name, String axis, List<Region> regions) {
    for (Object p : sim.getPartManager().getObjects()) if (name.equals(nm(p))) return p;
    double[] c = new double[3];
    Object pos = fieldFunction("Position");
    for (int k = 0; k < 3; k++) {
      double lo = 0, hi = 0;
      try {
        Object comp = Reflect.invoke(pos, "getComponentFunction", k);
        MinReport mn = sim.getReportManager().createReport(MinReport.class);
        MaxReport mx = sim.getReportManager().createReport(MaxReport.class);
        mn.setFieldFunction((FieldFunction) comp);
        mx.setFieldFunction((FieldFunction) comp);
        mn.getParts().setObjects(regions);
        mx.getParts().setObjects(regions);
        lo = mn.getReportMonitorValue();
        hi = mx.getReportMonitorValue();
        sim.getReportManager().removeObjects(mn, mx);
      } catch (Throwable t) { log.na("domain extent " + "XYZ".charAt(k), t); }
      c[k] = 0.5 * (lo + hi);
    }
    double[] n = {axis.equals("X") ? 1 : 0, axis.equals("Y") ? 1 : 0, axis.equals("Z") ? 1 : 0};
    PlaneSection p = sim.getPartManager().createImplicitPart(new NeoObjectVector(regions.toArray()),
        new DoubleVector(n), new DoubleVector(c), 0, 1, new DoubleVector(new double[] {0.0}));            // [U27]
    p.setPresentationName(name);
    log.line(String.format("  created %s (normal %s through %.4g %.4g %.4g m)", name, axis, c[0], c[1], c[2]));
    return p;
  }

  // =====================================================================================================
  // Case report (one HTML page per version)
  // =====================================================================================================

  void toolCaseReport() throws IOException {
    CaseInfo c = requireCase();
    Map<String, String> id = readIdentity(c);
    String base = c.simBase();
    StringBuilder h = new StringBuilder("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>")
        .append(Html.esc(base)).append("</title><style>")
        .append("body{font-family:Segoe UI,Arial,sans-serif;font-size:13px;margin:20px;color:#222;max-width:1100px}")
        .append("h1{font-size:20px;margin:0 0 4px}h2{font-size:15px;margin:22px 0 6px;border-bottom:1px solid #999}")
        .append("table{border-collapse:collapse}td,th{border:1px solid #ccc;padding:3px 8px;text-align:left;vertical-align:top}")
        .append("th{background:#eee}.PASS{background:#e3f4e3}.WARN{background:#fdf3d8}.FAIL{background:#f9dcdc}")
        .append("img{max-width:520px;margin:4px;border:1px solid #ccc}.k{font-weight:bold;width:170px}" + MD_CSS)
        .append("</style></head><body>\n<h1>").append(Html.esc(c.caseId)).append(" &mdash; v").append(Fmt.two(c.version))
        .append(c.label.isEmpty() ? "" : " (" + Html.esc(c.label) + ")").append("</h1>\n<table>");
    String[][] ident = {{"Description", id.getOrDefault("description", "")},
        {"Based on", asVersionId(id.getOrDefault("forked_v" + Fmt.two(c.version), ""))},
        {"Comment", id.getOrDefault("comment_v" + Fmt.two(c.version), "")},
        {"Status", id.getOrDefault("status_v" + Fmt.two(c.version), "")},
        {"Parent case", id.getOrDefault("parent_case", "")}, {"Sim file", c.simFile.toString()},
        {"Created", id.getOrDefault("created", "") + " by " + id.getOrDefault("created_by", "")},
        {"Report written", Fmt.iso() + " by " + Env.user() + " with " + VERSION + ", STAR-CCM+ " + starVersion()}};
    for (String[] r : ident) h.append("<tr><td class=\"k\">").append(Html.esc(r[0])).append("</td><td>").append(identCell(r)).append("</td></tr>");
    h.append("</table>\n");
    Path rec = c.sub("record");
    reportTable(h, "Check (before the run)", rec.resolve("check_" + base + ".csv"), true);
    reportTable(h, "Key results", rec.resolve(KEY_RESULTS), false);
    reportTable(h, "Averaged results", latest(c.sub("results/csv"), base + "_averages_*.csv"), false);
    reportTable(h, "Changes since the previous version", latest(rec, "changes_*_to_" + base + ".csv"), false);
    List<Path> imgs = Fs.list(c.sub("results/img"), base + "_*.png");
    h.append("<h2>Images</h2>");
    if (imgs.isEmpty()) h.append("<p>No image yet: run Export results (scenes are saved as images).</p>");
    for (Path p : imgs) {
      String rel = "../results/img/" + p.getFileName();
      h.append("<a href=\"").append(Html.esc(rel)).append("\"><img src=\"").append(Html.esc(rel)).append("\" title=\"")
          .append(Html.esc(p.getFileName().toString())).append("\"></a>");
    }
    h.append("\n<p style=\"color:#777\">Missing sections: run Check or Export results on this version.</p>")
        .append("</body></html>\n");
    Path out = rec.resolve("case_report_" + base + ".html");
    Fs.write(out, h.toString());
    log.line("case report -> " + out);
    result("case report " + out.getFileName() + (gui ? " opened in the browser" : ""));
    if (gui) {
      try { Ui.browse(out.toUri().toString()); } catch (Throwable t) { log.na("open in the browser", t); }
    }
  }

  static Path latest(Path dir, String glob) {
    Path best = null;
    for (Path p : Fs.list(dir, glob)) if (best == null || p.getFileName().toString().compareTo(best.getFileName().toString()) > 0) best = p;
    return best;
  }

  /** A CSV file as an HTML table (first column coloured by PASS/WARN/FAIL if 'levels'); a note if missing. */
  static void reportTable(StringBuilder h, String title, Path csv, boolean levels) {
    h.append("<h2>").append(Html.esc(title)).append("</h2>");
    if (csv == null || !Files.isRegularFile(csv)) {
      h.append("<p>not available</p>\n");
      return;
    }
    try {
      List<String[]> rows = Csv.parse(Fs.read(csv));
      h.append("<table>");
      for (int i = 0; i < rows.size(); i++) {
        String[] r = rows.get(i);
        String cls = levels && i > 0 && r.length > 0 ? " class=\"" + Html.esc(r[0]) + "\"" : "";
        h.append("<tr").append(cls).append(">");
        for (String cell : r) h.append(i == 0 ? "<th>" : "<td>").append(Html.esc(cell)).append(i == 0 ? "</th>" : "</td>");
        h.append("</tr>");
      }
      h.append("</table><p style=\"color:#777\">").append(Html.esc(csv.getFileName().toString())).append("</p>\n");
    } catch (IOException e) {
      h.append("<p>unreadable: ").append(Html.esc(e.getMessage())).append("</p>\n");
    }
  }

  // =====================================================================================================
  // Parametrize mesh (mesh sensitivity with one factor)
  // =====================================================================================================

  static final String MESH_SCALE = "p_meshScale";

  /** One mesh length setting found in an automated mesh operation. */
  static final class MeshSetting {
    String op, control = "", what, kind;   // kind: size | prism
    Object q;                              // ScalarPhysicalQuantity holding the absolute length
    String pName, def = "", note = "";
    double raw;
    Object units;
  }

  /** Size settings: class, label, kind. BaseSize / PrismWallThickness are quantities; the others are
   *  relative-or-absolute sizes (only the ABSOLUTE ones are parametrized; RELATIVE ones follow the base size). */
  static final String[][] MESH_SIZES = {
      {"star.meshing.BaseSize", "baseSize", "size"},
      {"star.meshing.PartsTargetSurfaceSize", "targetSize", "size"},
      {"star.meshing.PartsMinimumSurfaceSize", "minSize", "size"},
      {"star.meshing.VolumeControlSize", "volSize", "size"},
      {"star.prismmesher.PrismThickness", "prismThick", "prism"},
      {"star.prismmesher.PrismWallThickness", "prismFirst", "prism"}};

  /** Parametrize mesh: absolute mesh lengths -> p_mesh_<op>[_<control>]_<setting> (current value) and definition
   *  ${p_mesh_...} * ${p_meshScale}; p_meshScale = 1 keeps today's mesh. Asks before changing anything. */
  void toolParametrizeMesh() {
    List<MeshSetting> found = new ArrayList<>();
    List<String> relative = new ArrayList<>(), already = new ArrayList<>();
    for (Object op : sim.get(MeshOperationManager.class).getObjects()) {
      if (!(op instanceof AutoMeshOperation)) continue;
      String opName = nm(op);
      try { scanMeshValues(Reflect.invoke(op, "getDefaultValues"), opName, "", found, relative, already); }
      catch (Throwable t) { log.na("default values of " + opName, t); }
      try {
        for (Object cc : ((AutoMeshOperation) op).getCustomMeshControls().getObjects()) {
          Object cv = Reflect.tryCall(cc, "getCustomValues");
          if (cv == null) continue;
          scanMeshValues(cv, opName, nm(cc), found, relative, already);
          Object prism = null;
          try { prism = Reflect.invoke(cv, "get", Reflect.cls("star.prismmesher.CustomPrismValuesManager")); }
          catch (Throwable t) { /* no custom prism values on this control */ }
          if (prism != null) scanMeshValues(prism, opName, nm(cc), found, relative, already);
        }
      } catch (Throwable t) { log.na("custom controls of " + opName, t); }
    }
    StringBuilder info = new StringBuilder();
    if (!relative.isEmpty()) info.append("Relative to the base size (follow it automatically, nothing to do):\n  ")
        .append(String.join("\n  ", relative)).append('\n');
    if (!already.isEmpty()) info.append("Already driven by a parameter or expression (left as they are):\n  ")
        .append(String.join("\n  ", already)).append('\n');
    if (found.isEmpty()) {
      log.line("no absolute mesh size to parametrize.\n" + info);
      return;
    }
    boolean prism = false;
    boolean anyPrism = false;
    for (MeshSetting m : found) anyPrism |= m.kind.equals("prism");
    if (gui && anyPrism) {
      prism = Ui.option("Mesh study  -  parametrize", "Prism layer thicknesses are absolute too. Scale them with the mesh?\n"
          + "No (usual): keeps the first-cell height, so y+ stays the same between the meshes.\n"
          + "Yes: refines the prism layers together with the cells.", new String[] {"No, keep prism layers", "Yes, scale them"}) == 1;
    }
    List<MeshSetting> todo = new ArrayList<>();
    for (MeshSetting m : found) if (prism || m.kind.equals("size")) todo.add(m);
    if (todo.isEmpty()) {
      log.line("only prism thicknesses are absolute and you chose to keep them: nothing to do.\n" + info);
      return;
    }
    String[] labels = new String[todo.size()];
    for (int i = 0; i < labels.length; i++) {
      MeshSetting m = todo.get(i);
      labels[i] = String.format("%-26s %-14s %10s %-4s -> %s", Fmt.cut(m.op + (m.control.isEmpty() ? "" : "/" + m.control), 26),
          m.what, Fmt.num(m.raw), nm(m.units), "${" + m.pName + "} * ${" + MESH_SCALE + "}");
    }
    int[] pick = gui ? Ui.pick("Mesh study  -  parametrize", "Absolute mesh sizes to drive with " + MESH_SCALE
        + " (each keeps its value in its own parameter; the mesh is unchanged while " + MESH_SCALE + " = 1):", labels, true)
        : allIndices(labels.length);
    ScalarGlobalParameter scale = findParam(MESH_SCALE);
    if (scale == null) {
      scale = newParam(MESH_SCALE, null, 1.0, null);
      log.line("  created " + MESH_SCALE + " = 1 (dimensionless)");
    }
    int done = 0;
    for (int i : pick) {
      MeshSetting m = todo.get(i);
      try {
        ScalarGlobalParameter p = findParam(m.pName);
        if (p == null) p = newParam(m.pName, "length", m.raw, m.units);
        Reflect.invoke(m.q, "setDefinition", "${" + m.pName + "} * ${" + MESH_SCALE + "}");             // [U26]
        done++;
        log.line(String.format("  %-26s %-14s = ${%s} * ${%s}   (%s = %s %s)", Fmt.cut(m.op + (m.control.isEmpty() ? "" : "/" + m.control), 26),
            m.what, m.pName, MESH_SCALE, m.pName, Fmt.num(m.raw), nm(m.units)));
      } catch (Throwable t) {
        log.na(m.op + " " + m.what, t);
      }
    }
    result(done + " mesh size(s) now follow " + MESH_SCALE + "  (Details: the list)");
    log.line(done + " mesh size(s) now follow " + MESH_SCALE + ".\n" + info
        + "\nMesh sensitivity: Mesh study > Create mesh levels makes one case per mesh level; or set " + MESH_SCALE
        + " yourself (Tools > Parameters) and regenerate the mesh.");
  }

  // ----- Mesh study: parametrize only, or one case per mesh level -----------------------------------------

  /** One level of a mesh study (saved as a case of its own). */
  static final class MeshLevel {
    String name;
    double scale;
    final Map<String, Double> sizes = new LinkedHashMap<>();   // edited sizes only: parameter -> size in its units

    MeshLevel(String name, double scale) {
      this.name = name;
      this.scale = scale;
    }
  }

  /** Mesh size parameters made by Parametrize mesh (p_mesh_*), by name. */
  Map<String, ScalarGlobalParameter> meshParams() {
    Map<String, ScalarGlobalParameter> m = new LinkedHashMap<>();
    for (Object o : sim.get(GlobalParameterManager.class).getObjects()) {
      if (o instanceof ScalarGlobalParameter && nm(o).startsWith("p_mesh_")) m.put(nm(o), (ScalarGlobalParameter) o);
    }
    return m;
  }

  static String shortNum(double v) {
    String t = Fmt.num(v);
    return t.contains("e") || !t.contains(".") ? t : t.replaceAll("0+$", "").replaceAll("\\.$", "");
  }

  /** Mesh study: "Parametrize only" (one factor p_meshScale drives the absolute sizes you tick) or "Create mesh
   *  levels" (one case per level, each meshed with its p_meshScale and the sizes you edit). */
  void toolMeshStudy() throws IOException {
    boolean ready = !meshParams().isEmpty() && findParam(MESH_SCALE) != null;
    int mode = 1;
    if (gui) {
      mode = Ui.option("Mesh study", (ready ? "This mesh already follows " + MESH_SCALE + " (" + meshParams().size()
          + " size parameter(s)).\n\n" : "")
          + "Parametrize only: the absolute mesh sizes you tick follow one factor " + MESH_SCALE
          + " (1 = today's mesh).\n    Nothing else changes; you can then vary the mesh yourself.\n"
          + "Create mesh levels: one case per mesh you define (coarse, medium, fine, ...), each meshed and saved,\n"
          + "    ready to run; GCI From cases finds them. The open file stays as it is on disk.",
          new String[] {ready ? "Parametrize more sizes" : "Parametrize only", "Create mesh levels"});
    }
    if (mode == 0) {
      toolParametrizeMesh();
      return;
    }
    CaseInfo c = caseOrNull();
    if (c == null) throw new Cancel("mesh levels are saved as cases next to this one: use Save new version first");
    if (!ready) {
      log.line("first, parametrize the mesh:");
      toolParametrizeMesh();
      if (meshParams().isEmpty() || findParam(MESH_SCALE) == null) throw new Cancel("no mesh size follows " + MESH_SCALE + ": nothing to vary");
    }
    Map<String, ScalarGlobalParameter> params = meshParams();
    List<MeshLevel> levels;
    boolean mesh = true;
    if (gui) {
      Object[] r = meshLevelDialog(c, params);
      @SuppressWarnings("unchecked") List<MeshLevel> l = (List<MeshLevel>) r[0];
      levels = l;
      mesh = (Boolean) r[1];
    } else {
      levels = new ArrayList<>(Arrays.asList(new MeshLevel("coarse", 2.0), new MeshLevel("medium", 1.0)));
    }
    createMeshLevels(c, params, levels, mesh);
  }

  /** The levels table: create, name, p_meshScale, then one column per size parameter (size of that level, editable;
   *  an edited size is kept when p_meshScale changes and shown in blue). Returns {levels, mesh now}. */
  Object[] meshLevelDialog(CaseInfo c, Map<String, ScalarGlobalParameter> params) {
    final List<String> names = new ArrayList<>(params.keySet());
    final double[] base = new double[names.size()];
    final String[] unit = new String[names.size()];
    for (int i = 0; i < base.length; i++) {
      base[i] = params.get(names.get(i)).getQuantity().getValue();
      Object u = null;
      try { u = params.get(names.get(i)).getQuantity().getUnits(); } catch (Throwable t) { /* none */ }
      unit[i] = u == null ? "" : nm(u);
    }
    final int fixed = 3;
    final String[] cols = new String[fixed + names.size()];
    cols[0] = "create";
    cols[1] = "level (file name)";
    cols[2] = MESH_SCALE;
    for (int i = 0; i < names.size(); i++) {
      cols[fixed + i] = names.get(i).substring("p_mesh_".length()) + (unit[i].isEmpty() ? "" : " [" + unit[i] + "]");
    }
    final List<Set<Integer>> edited = new ArrayList<>();
    final boolean[] filling = {false};
    final javax.swing.table.DefaultTableModel model = new javax.swing.table.DefaultTableModel(cols, 0) {
      @Override public Class<?> getColumnClass(int col) { return col == 0 ? Boolean.class : String.class; }

      @Override public void setValueAt(Object v, int row, int col) {
        super.setValueAt(v, row, col);
        if (filling[0]) return;
        if (col >= fixed) edited.get(row).add(col);
        if (col == 2) refill(row);
      }

      void refill(int row) {
        double k = Fmt.parse(String.valueOf(getValueAt(row, 2)));
        filling[0] = true;
        try {
          for (int i = 0; i < base.length; i++) {
            if (!edited.get(row).contains(fixed + i)) super.setValueAt(Double.isNaN(k) ? "" : shortNum(base[i] * k), row, fixed + i);
          }
        } finally {
          filling[0] = false;
        }
      }
    };
    final java.util.function.BiConsumer<String, Double> addRow = (n, k) -> {
      Object[] r = new Object[cols.length];
      r[0] = Boolean.TRUE;
      r[1] = n;
      r[2] = shortNum(k);
      for (int i = 0; i < base.length; i++) r[fixed + i] = shortNum(base[i] * k);
      edited.add(new HashSet<>());
      filling[0] = true;
      try { model.addRow(r); } finally { filling[0] = false; }
    };
    final javax.swing.JRadioButton now = new javax.swing.JRadioButton("Mesh each level now (shows the cell counts; takes the meshing time)", true);
    final javax.swing.JRadioButton later = new javax.swing.JRadioButton("Save without a mesh (it is built when the run starts, e.g. on the cluster)");
    final javax.swing.JTable[] tableBox = new javax.swing.JTable[1];
    JPanel p = Ui.edt(() -> {
      addRow.accept("coarse", 1.41);
      addRow.accept("medium", 1.0);
      addRow.accept("fine", 0.71);
      javax.swing.JTable table = new javax.swing.JTable(model);
      tableBox[0] = table;
      table.setRowHeight(22);
      table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
      table.getColumnModel().getColumn(0).setPreferredWidth(50);
      table.getColumnModel().getColumn(1).setPreferredWidth(130);
      table.getColumnModel().getColumn(2).setPreferredWidth(90);
      javax.swing.table.DefaultTableCellRenderer r = new javax.swing.table.DefaultTableCellRenderer() {
        @Override
        public java.awt.Component getTableCellRendererComponent(javax.swing.JTable t, Object v, boolean sel, boolean foc, int row, int col) {
          java.awt.Component k = super.getTableCellRendererComponent(t, v, sel, foc, row, col);
          boolean mine = row < edited.size() && edited.get(row).contains(col);
          k.setFont(k.getFont().deriveFont(mine ? Font.BOLD : Font.PLAIN));
          if (!sel) k.setForeground(mine ? new java.awt.Color(0x1F, 0x4E, 0x9A) : col >= fixed ? java.awt.Color.DARK_GRAY : java.awt.Color.BLACK);
          setHorizontalAlignment(col >= 2 ? javax.swing.SwingConstants.RIGHT : javax.swing.SwingConstants.LEFT);
          return k;
        }
      };
      for (int i = 1; i < cols.length; i++) table.getColumnModel().getColumn(i).setCellRenderer(r);
      JScrollPane sp = new JScrollPane(table);
      sp.setPreferredSize(new java.awt.Dimension(Math.max(560, 110 * cols.length), 160));
      JComboBox<String> ratio = new JComboBox<>(new String[] {"1.41", "1.5", "2", "1.3", "1.2"});
      ratio.setEditable(true);
      JButton fill = new JButton("Apply ratio");
      fill.setToolTipText("p_meshScale of the rows: ratio^k around the row closest to 1 (today's mesh); edited sizes are kept");
      fill.addActionListener(e -> {
        if (table.isEditing()) table.getCellEditor().stopCellEditing();
        double rr = Fmt.parse(String.valueOf(ratio.getEditor().getItem()));
        if (!(rr > 1)) return;
        int ref = 0;
        double best = Double.MAX_VALUE;
        for (int i = 0; i < model.getRowCount(); i++) {
          double k = Fmt.parse(String.valueOf(model.getValueAt(i, 2)));
          if (!Double.isNaN(k) && Math.abs(Math.log(k)) < best) { best = Math.abs(Math.log(k)); ref = i; }
        }
        for (int i = 0; i < model.getRowCount(); i++) model.setValueAt(shortNum(Math.pow(rr, ref - i)), i, 2);
      });
      JButton add = new JButton("Add level");
      add.addActionListener(e -> {
        if (table.isEditing()) table.getCellEditor().stopCellEditing();
        double last = model.getRowCount() == 0 ? 1 : Fmt.parse(String.valueOf(model.getValueAt(model.getRowCount() - 1, 2)));
        addRow.accept("level" + (model.getRowCount() + 1), Double.isNaN(last) ? 1 : last / 1.41);
      });
      JButton del = new JButton("Remove level");
      del.addActionListener(e -> {
        int i = table.getSelectedRow();
        if (i < 0) return;
        if (table.isEditing()) table.getCellEditor().cancelCellEditing();
        model.removeRow(i);
        edited.remove(i);
      });
      JPanel tools = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));
      tools.add(add);
      tools.add(del);
      tools.add(javax.swing.Box.createHorizontalStrut(14));
      tools.add(new JLabel("refinement ratio"));
      tools.add(ratio);
      tools.add(fill);
      javax.swing.ButtonGroup bg = new javax.swing.ButtonGroup();
      bg.add(now);
      bg.add(later);
      JPanel opts = new JPanel(new GridLayout(0, 1, 0, 2));
      opts.add(now);
      opts.add(later);
      JLabel head = new JLabel("<html>One case per ticked level, next to " + Html.esc(c.caseId) + " (its parent), named "
          + Html.esc(c.proj) + "-NNN_&lt;level&gt;_v01.sim.<br>A size is " + MESH_SCALE + " x today's value; type a size to set it "
          + "for that level only (shown in blue; it is kept when " + MESH_SCALE + " changes).<br>Sizes relative to the base size "
          + "follow the base size. The open file stays as it is on disk; at the end you work in the last level.</html>");
      JPanel all = new JPanel(new BorderLayout(0, 8));
      all.add(head, BorderLayout.NORTH);
      JPanel mid = new JPanel(new BorderLayout(0, 4));
      mid.add(sp, BorderLayout.CENTER);
      mid.add(tools, BorderLayout.SOUTH);
      all.add(mid, BorderLayout.CENTER);
      all.add(opts, BorderLayout.SOUTH);
      return all;
    });
    JLabel err = new JLabel(" ");
    err.setForeground(new java.awt.Color(0xB0, 0x20, 0x20));
    while (true) {
      if (!Ui.form("Mesh study  -  levels from " + c.simBase(), p)) throw new Cancel(null);
      String problem = Ui.edt(() -> {
        javax.swing.JTable t = tableBox[0];
        if (t.isEditing()) t.getCellEditor().stopCellEditing();
        return null;
      });
      List<MeshLevel> out = new ArrayList<>();
      Set<String> seen = new HashSet<>();
      for (int i = 0; i < model.getRowCount() && problem == null; i++) {
        if (!Boolean.TRUE.equals(model.getValueAt(i, 0))) continue;
        String n = String.valueOf(model.getValueAt(i, 1)).trim();
        double k = Fmt.parse(String.valueOf(model.getValueAt(i, 2)));
        try { n = checkLabel(n); } catch (Cancel x) { problem = "level '" + n + "': " + x.getMessage(); break; }
        if (n.isEmpty()) problem = "row " + (i + 1) + ": a level name is needed (it goes in the file name)";
        else if (!seen.add(n)) problem = "two levels are called '" + n + "'";
        else if (!(k > 0)) problem = "level '" + n + "': " + MESH_SCALE + " must be a number > 0";
        if (problem != null) break;
        MeshLevel L = new MeshLevel(n, k);
        for (int j = 0; j < names.size(); j++) {
          if (!edited.get(i).contains(fixed + j)) continue;
          double v = Fmt.parse(String.valueOf(model.getValueAt(i, fixed + j)));
          if (!(v > 0)) { problem = "level '" + n + "', " + cols[fixed + j] + ": a size > 0 is needed"; break; }
          L.sizes.put(names.get(j), v);
        }
        out.add(L);
      }
      if (problem == null && out.isEmpty()) problem = "tick at least one level";
      if (problem == null) return new Object[] {out, now.isSelected()};
      final String msg = problem;
      Ui.edt(() -> {
        if (err.getParent() == null) p.add(err, BorderLayout.EAST);
        err.setText("<html><div style='width:180px'>" + Html.esc(msg) + "</div></html>");
        return null;
      });
    }
  }

  /** Saves one case per level: p_meshScale and the edited sizes set, solution cleared, mesh generated (or cleared),
   *  case folder + identity (parent, mesh_study, mesh_level, mesh_scale, cells). */
  void createMeshLevels(CaseInfo c, Map<String, ScalarGlobalParameter> params, List<MeshLevel> levels, boolean mesh) throws IOException {
    ScalarGlobalParameter scale = findParam(MESH_SCALE);
    Map<String, Double> base = new LinkedHashMap<>();
    for (Map.Entry<String, ScalarGlobalParameter> e : params.entrySet()) base.put(e.getKey(), e.getValue().getQuantity().getValue());
    double scale0 = scale.getQuantity().getValue();
    Map<String, String> pid = readIdentity(c);
    String study = c.caseId + " " + Fmt.iso();
    star.meshing.MeshPipelineController pipe = sim.get(star.meshing.MeshPipelineController.class);
    List<String> made = new ArrayList<>();
    CaseInfo last = null;
    log.line("mesh study from " + c.simBase() + ": " + levels.size() + " level(s)" + (mesh ? ", meshed now" : ", saved without a mesh"));
    for (MeshLevel L : levels) {
      for (Map.Entry<String, Double> e : base.entrySet()) params.get(e.getKey()).getQuantity().setValue(e.getValue());
      scale.getQuantity().setValue(L.scale);
      List<String> set = new ArrayList<>();
      for (Map.Entry<String, Double> e : L.sizes.entrySet()) {   // size = p * scale  ->  p = size / scale
        params.get(e.getKey()).getQuantity().setValue(e.getValue() / L.scale);
        set.add(e.getKey().substring("p_mesh_".length()) + "=" + shortNum(e.getValue()));
      }
      try { sim.getSolution().clearSolution(); } catch (Throwable t) { log.na("clear the solution", t); }
      String cells = "";
      if (mesh) {
        log.line("  " + L.name + ": meshing with " + MESH_SCALE + " = " + shortNum(L.scale) + " ...");
        pipe.generateVolumeMesh();
        String n = cellCount();
        cells = n == null ? "" : shortNum(Fmt.parse(n));
      } else {
        try { pipe.clearGeneratedMeshes(); } catch (Throwable t) { log.na("clear the mesh", t); }
      }
      CaseInfo n = createCaseFolders(c.casesDir, c.proj, L.name);
      sim.saveState(Fs.guard(n.simFile).toString());
      Map<String, String> id = new LinkedHashMap<>();
      id.put("case_id", n.caseId);
      id.put("description", (pid.getOrDefault("description", "").isEmpty() ? c.caseId : pid.get("description"))
          + " - mesh study: " + L.name + " (" + MESH_SCALE + " " + shortNum(L.scale) + ")");
      id.put("configuration", pid.getOrDefault("configuration", ""));
      id.put("parent_case", c.caseId);
      id.put("parent_file", c.simFile.toString());
      id.put("created", Fmt.iso());
      id.put("created_by", Env.user());
      id.put("forked_v01", versionId(c));
      id.put("mesh_study", study);
      id.put("mesh_level", L.name);
      id.put("mesh_scale", shortNum(L.scale));
      if (!cells.isEmpty()) id.put("mesh_cells", cells);
      if (!set.isEmpty()) id.put("mesh_sizes_set", String.join("; ", set));
      putLast(id, n);
      writeIdentity(n, id);
      writeAudit(n);
      log.line(String.format("  %-8s %-10s %s = %-6s %s%s", n.caseId, L.name, MESH_SCALE, shortNum(L.scale),
          cells.isEmpty() ? "(no mesh)" : cells + " cells", set.isEmpty() ? "" : "   sizes set: " + String.join(", ", set)));
      made.add(n.caseId + " " + L.name + (cells.isEmpty() ? "" : " " + cells + " cells"));
      last = n;
    }
    updateCaseList(c.casesDir);
    log.line("mesh levels saved; " + c.simBase() + " is unchanged on disk (" + MESH_SCALE + " = " + shortNum(scale0) + ").\n"
        + "Run each level, Export results, then Calculators > Grid convergence (GCI) > From cases.");
    result(made.size() + " mesh level(s): " + String.join(", ", made) + (last == null ? "" : ". Now in " + last.simFile.getFileName()));
  }

  /** Looks for the known size objects in a values manager (default, custom or custom-prism values). */
  void scanMeshValues(Object values, String op, String control, List<MeshSetting> found, List<String> relative,
      List<String> already) {
    for (String[] s : MESH_SIZES) {
      Class<?> k = Reflect.cls(s[0]);
      if (k == null) continue;
      Object v;
      try { v = Reflect.invoke(values, "get", k); } catch (Throwable t) { continue; }   // not present / not activated
      if (v == null) continue;
      String where = op + (control.isEmpty() ? "" : "/" + control) + " " + s[1];
      Object q = v;
      if (Reflect.has(v, "getRelativeOrAbsoluteOption")) {
        Object opt = Reflect.tryCall(Reflect.tryCall(v, "getRelativeOrAbsoluteOption"), "getSelectedElement");
        if (opt == null || !String.valueOf(opt).toUpperCase(Locale.ROOT).contains("ABSOLUTE")) {
          relative.add(where);
          continue;
        }
        q = Reflect.tryCall(v, "getAbsoluteSizeValue");
      }
      if (q == null || !Reflect.has(q, "setDefinition", String.class)) continue;
      Object def = Reflect.tryCall(q, "getDefinition");
      if (def != null && String.valueOf(def).contains("$")) {
        already.add(where + " = " + def);
        continue;
      }
      MeshSetting m = new MeshSetting();
      m.op = op;
      m.control = control;
      m.what = s[1];
      m.kind = s[2];
      m.q = q;
      Object raw = Reflect.tryCall(q, "getRawValue");
      m.raw = raw instanceof Number ? ((Number) raw).doubleValue() : Double.NaN;
      m.units = Reflect.tryCall(q, "getUnits");
      m.pName = "p_mesh_" + Fs.fileSafe(op).replaceAll("[^A-Za-z0-9]", "")
          + (control.isEmpty() ? "" : "_" + Fs.fileSafe(control).replaceAll("[^A-Za-z0-9]", "")) + "_" + s[1];
      if (Double.isNaN(m.raw)) continue;
      found.add(m);
    }
  }

  ScalarGlobalParameter findParam(String name) {
    for (Object o : sim.get(GlobalParameterManager.class).getObjects()) {
      if (o instanceof ScalarGlobalParameter && name.equals(nm(o))) return (ScalarGlobalParameter) o;
    }
    return null;
  }

  /** New scalar parameter: dimensionless (dim == null) or a length with the given units. [U10][U30][U31] */
  ScalarGlobalParameter newParam(String name, String dim, double value, Object units) {
    ScalarGlobalParameter p = (ScalarGlobalParameter) sim.get(GlobalParameterManager.class)
        .createGlobalParameter(ScalarGlobalParameter.class, "Scalar");                                   // [U10]
    p.setPresentationName(name);
    if ("length".equals(dim)) {
      try { p.setDimensions(Dimensions.Builder().length(1).build()); }                                   // [U30]
      catch (Throwable t) { log.na("dimensions of " + name, t); }
      if (units instanceof Units) {
        try { p.getQuantity().setUnits((Units) units); }                                                 // [U31]
        catch (Throwable t) { log.na("units of " + name, t); }
      }
    }
    p.getQuantity().setValue(value);                                                                     // [U10]
    return p;
  }

  // =====================================================================================================
  // Export results
  // =====================================================================================================

  /** Monitor plots -> results/csv, scenes -> results/img, report values -> results/csv. */
  void toolExport() throws IOException {
    CaseInfo c = requireCase();
    Path csv = c.sub("results/csv"), img = c.sub("results/img");
    Fs.mkdirs(csv);
    Fs.mkdirs(img);
    exportPlots(c, csv);
    exportScenes(c, img);
    Map<String, String> values = exportReports(c, csv);
    Map<String, Stats> avg = averagedResults(c, csv, gui);
    writeKeyResults(c, values, avg);
    updateCaseList(c.casesDir);
    result("results exported to " + c.sub("results"));
  }

  /** Monitor plots -> results/csv/<sim>_<plot>.csv  [U13] */
  void exportPlots(CaseInfo c, Path dir) {
    int n = 0;
    try {
      for (Object p : sim.getPlotManager().getObjects()) {
        if (!(p instanceof MonitorPlot)) continue;
        try {
          Path f = Fs.guard(dir.resolve(c.simBase() + "_" + Fs.fileSafe(nm(p)) + ".csv"));
          ((MonitorPlot) p).export(f.toString(), ",");                                                   // [U13]
          n++;
        } catch (Throwable t) { log.na("plot " + nm(p), t); }
      }
    } catch (Throwable t) { log.na("plots", t); }
    log.line("plots exported: " + n + " -> " + dir);
  }

  /** Scenes -> results/img/<sim>_<scene>.png at IMG_W x IMG_H  [U14] */
  void exportScenes(CaseInfo c, Path dir) {
    int n = 0;
    try {
      for (Scene s : sim.getSceneManager().getScenes()) {
        try {
          Path f = Fs.guard(dir.resolve(c.simBase() + "_" + Fs.fileSafe(nm(s)) + ".png"));
          s.printAndWait(f.toString(), 1, IMG_W, IMG_H);                                                 // [U14]
          n++;
        } catch (Throwable t) { log.na("scene " + nm(s), t); }
      }
    } catch (Throwable t) { log.na("scenes", t); }
    log.line("scenes exported: " + n + " -> " + dir);
  }

  /** Values of all reports -> results/csv/<sim>_reports_<time>.csv */
  Map<String, String> exportReports(CaseInfo c, Path dir) throws IOException {
    List<String[]> rows = new ArrayList<>();
    Map<String, String> values = new LinkedHashMap<>();
    try {
      for (Object r : sim.getReportManager().getObjects()) {
        String v = reportValue(r);
        rows.add(new String[] {nm(r), cls(r), v});
        values.put(nm(r), v);
      }
    } catch (Throwable t) { log.na("reports", t); }
    Path f = dir.resolve(c.simBase() + "_reports_" + Fmt.ts() + ".csv");
    Csv.write(f, new String[] {"report", "type", "value"}, rows);
    log.line("report values: " + rows.size() + " report(s) -> " + f);
    return values;
  }

  // =====================================================================================================
  // Monitor histories: averaged results
  // =====================================================================================================

  /** Statistics of a monitor over a window. */
  static final class Stats {
    String name = "", axis = "";
    int n;
    double from, to, mean, std, min, max, last, driftPct;
    String verdict = "";
  }

  /** History {x, y} of a monitor (x = iteration or physical time), or null if not available. [U25] */
  static double[][] history(Object m) {
    try {
      Object d = Reflect.invoke(m, "getAllSamples");                                                    // [U25]
      double[] x = (double[]) Reflect.invoke(d, "getXValues"), y = (double[]) Reflect.invoke(d, "getYValues");
      if (x == null || y == null) return null;
      int n = Math.min(x.length, y.length);
      return n == 0 ? null : new double[][] {Arrays.copyOf(x, n), Arrays.copyOf(y, n)};
    } catch (Throwable t) {
      return null;
    }
  }

  static boolean timeAxis(Object m) {
    Object a = Reflect.tryCall(m, "getXAxisName");
    return a != null && a.toString().toLowerCase(Locale.ROOT).contains("time");
  }

  /** Stats of y over x >= from (at least 5 samples: else the last 5). Drift = difference between the means of the
   *  last and the first fifth of the window, in % of max(|mean|, half the range): < 1 % stable, < 5 % slow drift. */
  static Stats stats(String name, double[] x, double[] y, double from) {
    int i0 = 0;
    while (i0 < x.length && x[i0] < from) i0++;
    if (x.length - i0 < 5) i0 = Math.max(0, x.length - 5);
    Stats s = new Stats();
    s.name = name;
    s.n = x.length - i0;
    s.from = x[i0];
    s.to = x[x.length - 1];
    s.min = Double.POSITIVE_INFINITY;
    s.max = Double.NEGATIVE_INFINITY;
    double sum = 0, sq = 0;
    for (int i = i0; i < x.length; i++) {
      sum += y[i];
      sq += y[i] * y[i];
      s.min = Math.min(s.min, y[i]);
      s.max = Math.max(s.max, y[i]);
    }
    s.mean = sum / s.n;
    s.std = Math.sqrt(Math.max(0, sq / s.n - s.mean * s.mean));
    s.last = y[x.length - 1];
    int b = Math.max(1, s.n / 5);
    double first = 0, lastB = 0;
    for (int i = 0; i < b; i++) {
      first += y[i0 + i];
      lastB += y[x.length - b + i];
    }
    double scale = Math.max(Math.abs(s.mean), 0.5 * (s.max - s.min));
    s.driftPct = scale > 0 ? 100 * (lastB - first) / b / scale : 0;
    double d = Math.abs(s.driftPct);
    s.verdict = s.n < 10 ? "too few samples" : d < 1 ? "stable" : d < 5 ? "slow drift" : "drifting";
    return s;
  }

  /** Start of the averaging window: unsteady -> case_identity avg_start_s (asked once in the GUI), else the middle of
   *  the run; steady -> last 20 % of the iterations. */
  double windowStart(CaseInfo c, double xmin, double xmax, boolean time, boolean ask) {
    if (!time) return xmin + 0.8 * (xmax - xmin);
    Map<String, String> id = c == null ? new LinkedHashMap<>() : readIdentity(c);
    double t0 = Fmt.parse(id.get("avg_start_s"));
    if (ask) {
      String def = Double.isNaN(t0) ? Fmt.num(xmin + 0.5 * (xmax - xmin)) : Fmt.num(t0);
      String in = Ui.text("Averaging window", "Unsteady run from t = " + Fmt.num(xmin) + " s to " + Fmt.num(xmax)
          + " s.\nAverage the monitors from t = (s):", def);
      double v = Fmt.parse(in);
      if (Double.isNaN(v)) throw new Cancel("the start of the averaging window must be a number (s)");
      t0 = v;
      if (c != null) {
        try {
          id.put("avg_start_s", Fmt.num(t0));
          writeIdentity(c, id);
        } catch (Throwable e) { log.na("remember the averaging window", e); }
      }
    }
    return Double.isNaN(t0) ? xmin + 0.5 * (xmax - xmin) : t0;
  }

  /** Statistics of every report monitor over the averaging window -> results/csv/<sim>_averages.csv. */
  Map<String, Stats> averagedResults(CaseInfo c, Path dir, boolean ask) {
    Map<String, Stats> out = new LinkedHashMap<>();
    try {
      List<Object> mons = new ArrayList<>();
      for (Object m : sim.getMonitorManager().getObjects()) if (m instanceof ReportMonitor) mons.add(m);
      if (mons.isEmpty()) return out;
      double xmin = Double.POSITIVE_INFINITY, xmax = Double.NEGATIVE_INFINITY;
      boolean time = false;
      for (Object m : mons) {
        double[][] h = history(m);
        if (h == null) continue;
        time |= timeAxis(m);
        xmin = Math.min(xmin, h[0][0]);
        xmax = Math.max(xmax, h[0][h[0].length - 1]);
      }
      if (xmin > xmax) {
        log.line("averaged results: no monitor history yet (run the simulation first)");
        return out;
      }
      double from = windowStart(c, xmin, xmax, time, ask && time);
      List<String[]> rows = new ArrayList<>();
      for (Object m : mons) {
        double[][] h = history(m);
        if (h == null) continue;
        String rep = nm(m);
        try { rep = nm(((ReportMonitor) m).getReport()); } catch (Throwable t) { /* keep the monitor name */ }
        Stats s = stats(rep, h[0], h[1], timeAxis(m) == time ? from : h[0][0] + 0.8 * (h[0][h[0].length - 1] - h[0][0]));
        s.axis = timeAxis(m) ? "time" : "iteration";
        out.put(rep, s);
        rows.add(new String[] {rep, Fmt.num(s.mean), Fmt.num(s.std), Fmt.num(s.min), Fmt.num(s.max), Fmt.num(s.last),
            s.axis, Fmt.num(s.from), Fmt.num(s.to), String.valueOf(s.n), Fmt.num(s.driftPct), s.verdict});
      }
      String window = time ? "time window from " + Fmt.num(from) + " s" : "last 20 % of iterations";
      if (dir == null || c == null) {   // statistics only, no file
        log.line("monitor statistics over the " + window);
        return out;
      }
      Path f = dir.resolve(c.simBase() + "_averages_" + Fmt.ts() + ".csv");
      Csv.write(f, new String[] {"report", "mean", "std", "min", "max", "last", "axis", "window_from", "window_to",
          "samples", "drift_pct", "verdict"}, rows);
      log.line("averaged results (" + window + "): " + rows.size() + " monitor(s) -> " + f);
    } catch (Cancel x) {
      log.line("averaged results skipped" + (x.getMessage() == null ? "" : ": " + x.getMessage()));
    } catch (Throwable t) {
      log.na("averaged results", t);
    }
    return out;
  }

  /** record/key_results.csv: one value per report (unsteady: mean over the window; steady: final value). */
  void writeKeyResults(CaseInfo c, Map<String, String> values, Map<String, Stats> avg) {
    try {
      List<String[]> rows = new ArrayList<>();
      for (Map.Entry<String, String> e : values.entrySet()) {
        Stats s = avg.get(e.getKey());
        boolean mean = s != null && "time".equals(s.axis);
        rows.add(new String[] {e.getKey(), mean ? Fmt.num(s.mean) : e.getValue(), mean ? "mean t>=" + Fmt.num(s.from) : "final",
            s == null ? "" : s.verdict, c.simBase(), Fmt.iso()});
      }
      String cells = cellCount();   // for the grid-convergence helper (Calculators > Mesh & GCI > From cases...)
      if (cells != null) rows.add(new String[] {"cells", cells, "mesh", "", c.simBase(), Fmt.iso()});
      ScalarGlobalParameter ms = findParam(MESH_SCALE);
      if (ms != null) rows.add(new String[] {MESH_SCALE, Fmt.num(ms.getQuantity().getRawValue()), "parameter", "", c.simBase(), Fmt.iso()});
      String[] head = {"report", "value", "how", "verdict", "sim", "date"};
      Csv.write(c.sub("record").resolve(KEY_RESULTS), head, rows);                                    // latest (case list)
      Csv.write(c.sub("record").resolve("key_results_" + c.simBase() + ".csv"), head, rows);          // this version (tracker)
      log.line("key results -> " + c.sub("record").resolve(KEY_RESULTS));
    } catch (Throwable t) {
      log.na("key results", t);
    }
  }

  /** Number of cells of all regions, through a temporary cell-count report (removed again); null if n/a. */
  String cellCount() {
    ElementCountReport r = null;
    try {
      r = sim.getReportManager().createReport(ElementCountReport.class);
      r.getParts().setObjects(sim.getRegionManager().getRegions());
      return Fmt.num(r.getReportMonitorValue());
    } catch (Throwable t) {
      log.na("cell count", t);
      return null;
    } finally {
      if (r != null) try { sim.getReportManager().removeObjects(r); } catch (Throwable t) { /* left in the tree */ }
    }
  }

  // =====================================================================================================
  // Other tools
  // =====================================================================================================

  /** Guideline: opens the links of config/links.csv in the browser. */
  void toolLinks() {
    List<String[]> links = config("links.csv", new String[] {"label", "url"}, null);
    List<String[]> ok = new ArrayList<>();
    for (String[] l : links) if (l[1].matches("(?i)^(https?|file):.*") && !l[1].contains("REPLACE")) ok.add(l);
    if (ok.isEmpty()) throw new Cancel("no link set yet in " + Fs.config("links.csv") + " (ask the toolkit owner)");
    String[] labels = new String[ok.size()];
    for (int i = 0; i < labels.length; i++) labels[i] = ok.get(i)[0];
    int[] pick = ok.size() == 1 ? new int[] {0} : Ui.pick("Guideline", "Open in the browser:", labels, false);
    for (int i : pick) {
      try {
        Ui.browse(ok.get(i)[1]);
        log.line("opened " + ok.get(i)[0]);
        result("opened " + ok.get(i)[0]);
      } catch (Throwable t) {
        log.na("open " + ok.get(i)[1], t);
      }
    }
  }

  /** About: version, share, release, config files found. */
  void toolAbout() {
    String vfile;
    try { vfile = Fs.read(Fs.inKit(VERSION_FILE)).trim(); } catch (IOException e) { vfile = "n/a (VERSION.txt not found)"; }
    StringBuilder sb = new StringBuilder();
    sb.append(VERSION).append(" (this macro)\nVERSION.txt: ").append(vfile)
        .append("\nSimKit root: ").append(Fs.kit()).append(" (folder of SimKit.java)")
        .append("\nSTAR-CCM+: ").append(starVersion()).append("\nJava: ").append(System.getProperty("java.version"))
        .append("\nuser: ").append(Env.user()).append("\n").append(contextLine()).append("\n\nconfig files:");
    for (String f : new String[] {"projects.csv", "naming_rules.csv", "links.csv"}) {
      sb.append("\n  ").append(Files.isRegularFile(Fs.config(f)) ? "found    " : "missing  ").append(f);
    }
    log.line(sb.toString());
  }

  /** Calculators = lib/generic/SimKitCalc.java (separate macro, played in this session). */
  void toolCalc() {
    Path p = Fs.inKit(CALC_MACRO);
    String fallback = "Run " + p + " with File > Macro > Play Macro";
    if (!Files.isRegularFile(p)) {
      log.line("not found: " + p + "\n" + fallback);
      Ui.showText("Calculators", log.take());
      return;
    }
    try {
      log.line("running " + p);
      runMacroFile(p);                                                                                  // [U09]
      log.line("finished SimKitCalc");
    } catch (Throwable t) {
      log.na("SimKitCalc", t);
      log.line(fallback);
      Ui.showText("Calculators", log.take());
    }
  }

  // =====================================================================================================
  // Configuration (three small CSV files in _SIMKIT/config, built-in defaults when missing)
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

  /** projects.csv: project code, description, and optional configuration suggestions (separated by ;). */
  List<String[]> projects() {
    List<String[]> p = config("projects.csv", new String[] {"project", "description", "configurations"}, DEFAULT_PROJECTS);
    if (p.isEmpty()) throw new Cancel("projects.csv has no rows");
    return p;
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
          || ((t.equals("surface") || t.equals("surfaces")) && type.equals("part_surface"))
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

    /** Tool number requested by a toolbar button macro (system property simkit.tool, cleared once read); 0 = menu. */
    static int directTool() {
      String t = System.getProperty("simkit.tool");
      if (t == null || t.trim().isEmpty()) return 0;
      System.clearProperty("simkit.tool");
      try {
        int id = Integer.parseInt(t.trim());
        return id >= 1 && id < TOOL_NAMES.length ? id : 0;
      } catch (NumberFormatException e) {
        return 0;
      }
    }

    /** "Always on top" preference (per user, shared with the calculator; default off). */
    static boolean onTop() { return "true".equals(pref("alwaysOnTop")); }

    /** Saves the preference and applies it at once to every open SimKit / calculator window. */
    static void setOnTop(boolean on) {
      setPref("alwaysOnTop", String.valueOf(on));
      for (java.awt.Window w : java.awt.Window.getWindows()) {
        if (w.isDisplayable() && w instanceof javax.swing.RootPaneContainer
            && Boolean.TRUE.equals(((javax.swing.RootPaneContainer) w).getRootPane().getClientProperty("simkit.window"))) {
          try { w.setAlwaysOnTop(on); } catch (Throwable t) { /* not supported by this window system */ }
        }
      }
    }

    /** Marks a window as a SimKit window and applies the preference to it. */
    static void track(java.awt.Window w) {
      if (w instanceof javax.swing.RootPaneContainer) ((javax.swing.RootPaneContainer) w).getRootPane().putClientProperty("simkit.window", Boolean.TRUE);
      try { w.setAlwaysOnTop(onTop()); } catch (Throwable t) { /* not supported by this window system */ }
    }

    /** Per-user remembered value (last cases folder); null if not available. */
    static String pref(String key) {
      try { return java.util.prefs.Preferences.userRoot().node("simkit").get(key, null); } catch (Throwable t) { return null; }
    }

    static void setPref(String key, String value) {
      try { java.util.prefs.Preferences.userRoot().node("simkit").put(key, value); } catch (Throwable t) { /* not kept */ }
    }
  }

  /** File system: kit location, write guard, small read/write helpers. */
  static final class Fs {
    /** True on Windows (10/11), false on Linux (RHEL 7/9). */
    static final boolean WINDOWS = java.io.File.separatorChar == '\\';

    /** The SimKit root = the folder of SimKit.java (set in execute() from resolvePath). Every kit file is reached
     *  from it with inKit("<relative/path>"); nothing of the kit is addressed in any other way. */
    static Path ROOT;

    static Path kit() { return ROOT; }

    /** A file or folder of the kit, relative to the root with '/' (same text on Windows and Linux). */
    static Path inKit(String rel) {
      Path p = kit();
      for (String part : rel.split("/")) if (!part.isEmpty()) p = p.resolve(part);
      return p;
    }

    static Path config(String file) { return inKit(CONFIG_DIR + "/" + file); }

    /** Folders SimKit may write in: the cases folders of the cases it handles in this session. */
    static final List<Path> ALLOWED = new ArrayList<>();

    static void allow(Path dir) {
      Path d = dir.toAbsolutePath().normalize();
      for (Path a : ALLOWED) if (same(a, d)) return;
      ALLOWED.add(d);
    }

    /** Comparison key of a path: case-insensitive on Windows only (Linux file systems are case-sensitive). */
    static String key(Path p) {
      String s = p.toAbsolutePath().normalize().toString();
      return java.io.File.separatorChar == '\\' ? s.toLowerCase(Locale.ROOT) : s;
    }
    static boolean same(Path a, Path b) { return key(a).equals(key(b)); }

    /** Write guard: SimKit writes only inside a cases folder it is working on, and never inside the kit. */
    static Path guard(Path p) throws IOException {
      if (under(p, kit()) || same(p, kit())) throw new IOException("refused to write inside the kit folder: " + p);
      for (Path a : ALLOWED) if (under(p, a) || same(p, a)) return p.toAbsolutePath().normalize();
      throw new IOException("refused to write outside the cases folder: " + p);
    }

    static boolean under(Path p, Path root) {
      String r = key(root), sep = java.io.File.separator;
      return key(p).startsWith(r.endsWith(sep) ? r : r + sep);
    }

    static void write(Path p, String text) throws IOException {
      Path g = guard(p);
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

    static void write(Path p, String[] header, List<String[]> rows) throws IOException {
      StringBuilder sb = new StringBuilder(row(header));
      for (String[] r : rows) sb.append(row(r));
      Fs.write(p, sb.toString());
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

  /** A {label, value} row of a report: Description and Comment formatted (Md), the rest as text. */
  static String identCell(String[] r) {
    return r[0].equals("Description") || r[0].equals("Comment") ? "<div class=\"md\">" + Md.toHtml(r[1]) + "</div>" : Html.esc(r[1]);
  }

  static final String MD_CSS = ".md p{margin:0 0 4px}.md ul,.md ol{margin:0 0 4px;padding-left:20px}";

  /** Light formatting of comments and descriptions, typed as plain marks so the text stays readable in
   *  case_identity.txt, the CSV files and Google Sheets: **bold**, *italic*, a line "- text" = bullet,
   *  a line "1. text" = numbered item. Shown formatted in the tracker and the case report. */
  static final class Md {
    static final Pattern BULLET = Pattern.compile("^\\s*[-*•]\\s+(.*)$");
    static final Pattern NUMBER = Pattern.compile("^\\s*(\\d+)[.)]\\s+(.*)$");

    /** One line: HTML-escaped, then **bold** and *italic*. */
    static String inline(String s) {
      String h = Html.esc(s);
      h = h.replaceAll("\\*\\*(\\S(?:.*?\\S)?)\\*\\*", "<b>$1</b>");
      h = h.replaceAll("(?<![*\\w])\\*(\\S(?:.*?\\S)?)\\*(?![*\\w])", "<i>$1</i>");
      return h;
    }

    /** The whole text as HTML: paragraphs (blank line between), line breaks, bullet and numbered lists. */
    static String toHtml(String text) {
      if (text == null || text.trim().isEmpty()) return "";
      StringBuilder out = new StringBuilder();
      String list = null;
      boolean para = false;
      for (String line : text.split("\n", -1)) {
        Matcher b = BULLET.matcher(line), n = NUMBER.matcher(line);
        String kind = b.matches() ? "ul" : n.matches() ? "ol" : null;
        if (kind != null) {
          if (para) { out.append("</p>"); para = false; }
          if (!kind.equals(list)) {
            if (list != null) out.append("</").append(list).append('>');
            out.append('<').append(kind).append('>');
            list = kind;
          }
          out.append("<li>").append(inline(kind.equals("ul") ? b.group(1) : n.group(2))).append("</li>");
          continue;
        }
        if (list != null) { out.append("</").append(list).append('>'); list = null; }
        if (line.trim().isEmpty()) {
          if (para) { out.append("</p>"); para = false; }
          continue;
        }
        out.append(para ? "<br>" : "<p>").append(inline(line));
        para = true;
      }
      if (list != null) out.append("</").append(list).append('>');
      if (para) out.append("</p>");
      return out.toString();
    }

    static List<String> lines(String text) {
      List<String> out = new ArrayList<>();
      if (text != null) for (String l : text.split("\n")) if (!l.trim().isEmpty()) out.add(l);
      return out;
    }

    /** The first line formatted, for a table cell ("&bull; " for a bullet, "1. " kept). */
    static String firstLineHtml(String text) {
      List<String> ls = lines(text);
      if (ls.isEmpty()) return "";
      Matcher b = BULLET.matcher(ls.get(0)), n = NUMBER.matcher(ls.get(0));
      if (b.matches()) return "&bull; " + inline(b.group(1));
      if (n.matches()) return Html.esc(n.group(1)) + ". " + inline(n.group(2));
      return inline(ls.get(0).trim());
    }

    /** Text without the marks on one line (lines joined by " / "), for one-line places. */
    static String plain(String text) {
      List<String> parts = new ArrayList<>();
      for (String l : lines(text)) {
        Matcher b = BULLET.matcher(l);
        String t = b.matches() ? "• " + b.group(1) : l.trim();
        parts.add(t.replaceAll("\\*\\*(\\S(?:.*?\\S)?)\\*\\*", "$1").replaceAll("(?<![*\\w])\\*(\\S(?:.*?\\S)?)\\*(?![*\\w])", "$1"));
      }
      return String.join(" / ", parts);
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

    /** The STAR-CCM+ main window (largest visible frame), so SimKit windows float over it; null if none. */
    static java.awt.Frame owner() {
      java.awt.Frame best = null;
      long area = -1;
      for (java.awt.Frame f : java.awt.Frame.getFrames()) {
        if (!f.isVisible()) continue;
        long a = (long) f.getWidth() * f.getHeight();
        if (a > area) { area = a; best = f; }
      }
      return best;
    }

    /** Runs a dialog on the Swing thread (tools run in a background thread) and returns its result. */
    static <T> T edt(java.util.function.Supplier<T> s) {
      if (javax.swing.SwingUtilities.isEventDispatchThread()) return s.get();
      final Object[] box = new Object[1];
      final RuntimeException[] err = new RuntimeException[1];
      try {
        javax.swing.SwingUtilities.invokeAndWait(() -> {
          try { box[0] = s.get(); } catch (RuntimeException e) { err[0] = e; }
        });
      } catch (Exception e) {
        throw new Cancel("dialog interrupted (" + e + ")");
      }
      if (err[0] != null) throw err[0];
      @SuppressWarnings("unchecked") T r = (T) box[0];
      return r;
    }

    /** Result text in a floating window (does not block STAR-CCM+ or SimKit). */
    static void showText(String title, String text) {
      edt(() -> {
        JDialog w = new JDialog(owner(), title, java.awt.Dialog.ModalityType.MODELESS);
        w.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        JPanel p = new JPanel(new BorderLayout(4, 4));
        p.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        p.add(scroll(text), BorderLayout.CENTER);
        JButton ok = new JButton("Close");
        ok.addActionListener(e -> w.dispose());
        JPanel s = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT));
        s.add(ok);
        p.add(s, BorderLayout.SOUTH);
        w.setContentPane(p);
        w.pack();
        w.setLocationRelativeTo(owner());
        Env.track(w);
        w.setVisible(true);
        return null;
      });
    }

    /** OK / Cancel form around a panel; true on OK. */
    static boolean form(String title, JPanel p) {
      return edt(() -> JOptionPane.showConfirmDialog(owner(), p, title, JOptionPane.OK_CANCEL_OPTION,
          JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION);
    }

    static boolean confirm(String title, String msg) {
      return edt(() -> JOptionPane.showConfirmDialog(owner(), msg, title, JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION);
    }

    static String choose(String title, String msg, String[] options, String def) {
      if (options.length == 0) throw new Cancel("nothing to choose from");
      Object r = edt(() -> JOptionPane.showInputDialog(owner(), msg, title, JOptionPane.QUESTION_MESSAGE, null, options,
          def != null ? def : options[0]));
      if (r == null) throw new Cancel(null);
      return (String) r;
    }

    static String text(String title, String msg, String def) {
      Object r = edt(() -> JOptionPane.showInputDialog(owner(), msg, title, JOptionPane.QUESTION_MESSAGE, null, null, def));
      if (r == null) throw new Cancel(null);
      return String.valueOf(r).trim();
    }

    static int option(String title, String msg, String[] opts) {
      int r = edt(() -> JOptionPane.showOptionDialog(owner(), msg, title, JOptionPane.DEFAULT_OPTION,
          JOptionPane.QUESTION_MESSAGE, null, opts, opts[0]));
      if (r < 0) throw new Cancel(null);
      return r;
    }

    static int[] pick(String title, String msg, String[] items, boolean multi) {
      return edt(() -> {
        JList<String> list = new JList<>(items);
        list.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        list.setSelectionMode(multi ? ListSelectionModel.MULTIPLE_INTERVAL_SELECTION : ListSelectionModel.SINGLE_SELECTION);
        if (multi) list.setSelectionInterval(0, items.length - 1); else list.setSelectedIndex(0);
        JScrollPane sp = new JScrollPane(list);
        sp.setPreferredSize(new java.awt.Dimension(820, 360));
        JPanel p = new JPanel(new BorderLayout(4, 4));
        p.add(new JLabel(msg), BorderLayout.NORTH);
        p.add(sp, BorderLayout.CENTER);
        int r = JOptionPane.showConfirmDialog(owner(), p, title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (r != JOptionPane.OK_OPTION || list.isSelectionEmpty()) throw new Cancel(null);
        return list.getSelectedIndices();
      });
    }

    static Path chooseDir(String title, Path dir) {
      return edt(() -> {
        JFileChooser fc = new JFileChooser(dir.toFile());
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fc.setDialogTitle(title);
        if (fc.showOpenDialog(owner()) != JFileChooser.APPROVE_OPTION) throw new Cancel(null);
        return fc.getSelectedFile().toPath().toAbsolutePath().normalize();
      });
    }

    static Path chooseFile(Path dir) {
      return edt(() -> {
        JFileChooser fc = new JFileChooser(dir.toFile());
        if (fc.showOpenDialog(owner()) != JFileChooser.APPROVE_OPTION) throw new Cancel(null);
        return fc.getSelectedFile().toPath();
      });
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
