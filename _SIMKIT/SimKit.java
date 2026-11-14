// SimKit.java -- SimKit 0.3 (2026-11-14) -- STAR-CCM+ 2402 and later, Windows 10/11 and Linux RHEL 7/9.
//
// Team helper for STAR-CCM+ work. It keeps the native STAR-CCM+ workflow; it only adds:
//   * Save as new run: a project = one folder (any name, anywhere); each run = one folder R<nnn>_<name>/ holding
//     R<nnn>_<name>.sim, run.txt (name, based on, comment, status), submit/ (scripts, logs) and what the cluster
//     sends back. The run number also lives inside the simulation (p_simkit_run). Runs may be grouped in sub-folders.
//   * Run on cluster, Tracker (runs, family tree), Calculators (lib/generic/SimKitCalc.java), Standard reports,
//     Mesh study.
//   The normal STAR-CCM+ Save (Ctrl+S) keeps saving the open file.
// Where things are: this file sits at the SimKit ROOT (one shared folder; lib/, config/, buttons/ below it). The
//   root is found from this file's own location (STAR-CCM+ resolvePath), so no environment variable or path has
//   to be set. Projects are wherever the user wants.
// Toolbar buttons: buttons/SimKit_<Tool>.java run one tool directly (no menu); see buttons/README.txt.
// Non-blocking: the SimKit window floats next to STAR-CCM+ and the macro ends at once, so the user keeps working
//   on the model. A tool runs in a background thread (STAR-CCM+ calls as in a Simulation Assistant); only its short
//   questions wait for an answer; results open in floating windows.
// Batch: SIMKIT_ACTION=<action> starccm+ -batch SimKit.java <sim> runs one test action (see runBatch).
//
// Conventions: every STAR-CCM+ call that may differ between releases runs inside try/catch and logs
// "n/a (<reason>)" instead of failing. SimKit writes only inside the project folder
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
  // Constants and tools
  // =====================================================================================================

  static final String VERSION = "SimKit 0.3 (2026-11-14)";

  /** Kit files used by SimKit, relative to the SimKit root ('/' on every OS). */
  static final String CONFIG_DIR = "config", VERSION_FILE = "VERSION.txt", CALC_MACRO = "lib/generic/SimKitCalc.java";

  /** The tools: title (button), the line under the button, tooltip. Toolbar buttons pass the enum name (simkit.tool). */
  enum Tool {
    SAVE("Save as new run\u2026", "Copy as the next run, from this one",
        "Saves the next run of the project (R008_<name>.sim) from this one; the open file is kept. No project yet: creates one"),
    CLUSTER("Run on cluster", "Submit with bsub",
        "Saves the file and submits it with the team's bsub command (config/cluster.csv), in a terminal. Linux only"),
    TRACKER("Tracker", "Runs, status, comments", "Runs of the project: status, comments, based on, family tree"),
    NAMES("Names", "Prefixes, default names",
        "Type prefixes (IN_, OUT_, WALL_ ...) on boundaries and part surfaces, default names, prefix vs boundary type; renames on request"),
    REPORTS("Standard reports", "Mass balance, p, T, y+ ...",
        "Mass balance, pressures, temperatures, wall heat, y+, Courant: reports, monitors, plots (from IN_ / OUT_ / WALL_ names)"),
    MESH("Mesh study", "Mesh levels", "Coarse / medium / fine levels as runs, for a mesh sensitivity check (GCI)"),
    CALC("Calculators", "y+, Re, time step, GCI ...", "Boundary values, y+ and prism layers, time step, dimensionless numbers, materials, GCI");

    final String title, sub, help;

    Tool(String title, String sub, String help) {
      this.title = title;
      this.sub = sub;
      this.help = help;
    }

    /** The tool a toolbar button asks for: its enum name, or the number of the buttons of SimKit 0.3 before 2026-11-14. */
    static Tool of(String t) {
      if (t == null) return null;
      String k = t.trim().toUpperCase(Locale.ROOT);
      switch (k) {
        case "2": return SAVE;
        case "5": return CALC;
        case "7": return TRACKER;
        case "10": return REPORTS;
        case "12": return MESH;
        case "15": return CLUSTER;
        default:
          for (Tool x : values()) if (x.name().equals(k)) return x;
          return null;
      }
    }
  }

  // =====================================================================================================
  // State, entry point, batch actions
  // =====================================================================================================

  Simulation sim;

  Log log;

  boolean gui;

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
    Tool direct = Env.directTool();   // set by a toolbar button macro (buttons/SimKit_<Tool>.java)
    if (direct != null) {
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

  /** Batch mode: action from -Dsimkit.action or env SIMKIT_ACTION (test actions). */
  void runBatch() {
    String action = Env.action();
    log.line("batch action: " + (action.isEmpty() ? "(none)" : action));
    try {
      switch (action) {
        case "reports": {   // test action: create the standard reports and save the sim in place
          CaseInfo c = requireCase();
          toolStandardReports();
          sim.saveState(Fs.guard(c.simFile).toString());
          log.line("saved " + c.simFile);
          break;
        }
        case "names": logNames(); break;   // test: the Names table, written to the log (nothing renamed)
        case "presubmit": for (String[] k : preSubmitChecks()) log.line("pre-submit " + k[0] + "  " + k[1]); break;
        case "tracker": toolTracker(); break;
        case "opensim": testOpenSim(); break;   // test: the tracker's Open in STAR-CCM+
        case "saverun": toolSave(); break;   // Save as new run in batch: next run, same name (asks nothing)
        case "studytest": testStudy(); break;   // test: start a study, save a run, prepare a submission (no bsub)
        case "meshstudy": toolMeshStudy(); break;   // test action: levels coarse (2) and medium (1), meshed
        case "meshparams": {   // test action: parametrize the mesh (no prism layers) and save the sim in place
          CaseInfo c = requireCase();
          toolParametrizeMesh();
          sim.saveState(Fs.guard(c.simFile).toString());
          log.line("saved " + c.simFile);
          break;
        }
        default:
          log.line(action.isEmpty() ? "no action set (SIMKIT_ACTION): nothing done" : "unknown action '" + action + "': nothing done");
      }
    } catch (Cancel c) {
      log.line("stopped: " + c.getMessage());
    } catch (Throwable t) {
      log.line("ERROR: " + reason(t));
    }
  }

  /** A toolbar button in batch: only the tools without dialogs can run. */
  void runBatchTool(Tool id) {
    log.line("button " + id.title + " (batch)");
    try {
      if (id == Tool.TRACKER) toolTracker();
      else log.line(id.title + " needs the STAR-CCM+ window: nothing done in batch");
    } catch (Cancel c) {
      log.line("stopped: " + c.getMessage());
    } catch (Throwable t) {
      log.line("ERROR: " + reason(t));
    }
  }

  /** Tools report on the status line (Details... shows their text); an error opens the text window. Everything is also
   *  in the STAR-CCM+ Output window. */
  volatile String summary;                         // one-line outcome of the tool (set by the tool)
  volatile String lastText, lastTitle;             // full text of the last tool, for Details...
  volatile String lastStatus;

  volatile int lastLevel;                          // 0 done, 1 cancelled / skipped items, 2 error

  /** Runs one tool; any failure is logged, never thrown into the session. */
  void runTool(Tool id) {
    log.take();
    summary = null;
    log.line("=== " + id.title + " ===");
    String stop = null;
    boolean error = false;
    try {
      switch (id) {
        case SAVE: toolSave(); break;
        case CLUSTER: toolSubmit(); break;
        case TRACKER: toolTracker(); break;
        case NAMES: toolNames(); break;
        case REPORTS: toolStandardReports(); break;
        case MESH: toolMeshStudy(); break;
        case CALC: toolCalc(); break;
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
    lastTitle = id.title + "  -  " + simName();
    lastText = text;
    boolean skipped = text.contains(": n/a (");
    if (error) {
      lastLevel = 2;
      lastStatus = id.title + " stopped: " + Fmt.cut(stop, 90);
    } else if (stop != null) {
      lastLevel = 1;
      lastStatus = "Cancelled: " + id.title + (stop.isEmpty() ? "" : "  -  " + Fmt.cut(stop, 90));
    } else {
      lastLevel = skipped ? 1 : 0;
      lastStatus = "Done: " + (summary != null ? summary : id.title) + (skipped ? "  (some items skipped: Details)" : "");
    }
    if (id != Tool.CALC && error) Ui.showText(lastTitle, text);
  }

  /** Shows the outcome of the last tool on the status line (amber: cancelled or items skipped, red: error). */
  void showStatus() {
    if (statusLine == null) return;
    statusLine.setText(lastStatus == null ? "Ready" : lastStatus);
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
      {"Runs", new Tool[] {Tool.SAVE, Tool.CLUSTER, Tool.TRACKER}},
      {"Set-up", new Tool[] {Tool.NAMES, Tool.REPORTS, Tool.MESH}},
      {"Tools", new Tool[] {Tool.CALC}}};

  JLabel headTitle, headSub, headPath, statusLine;

  JButton detailsButton;

  final Map<Tool, JButton> buttonById = new HashMap<>();

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
      for (Tool id : (Tool[]) sec[1]) {
        col.add(javax.swing.Box.createVerticalStrut(8));
        JButton b = new JButton(id.title);
        b.setFont(b.getFont().deriveFont(Font.BOLD, 13f));
        b.setAlignmentX(0f);
        b.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, 32));
        b.setPreferredSize(new java.awt.Dimension(190, 32));
        wire(b, id);
        col.add(b);
        JLabel sub = new JLabel(id.sub);
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

    statusLine = new JLabel("Ready");
    statusLine.setForeground(java.awt.Color.GRAY);
    JButton close = new JButton("Close");
    close.addActionListener(e -> d.dispose());
    JPanel right = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 8, 0));
    right.setOpaque(false);
    detailsButton = new JButton("Details");
    detailsButton.setToolTipText("Full text of the last tool (it is also in the STAR-CCM+ Output window)");
    detailsButton.setEnabled(lastText != null);
    detailsButton.addActionListener(e -> { if (lastText != null) Ui.showText(lastTitle, lastText); });
    right.add(detailsButton);
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
      Map<String, String> r = readIdentity(c);
      headTitle.setText("<html>" + (c.label.isEmpty() ? "Run " + c.caseId : html(c.label) + "&nbsp;&nbsp;<font color='#6B7580'>" + c.caseId + "</font>")
          + "<font color='#6B7580'>&nbsp;&nbsp;\u00B7&nbsp;&nbsp;iteration " + currentIteration() + "</font></html>");
      headTitle.setForeground(java.awt.Color.BLACK);
      String note = r.getOrDefault("comment", "");
      headSub.setText("Project " + c.caseDir.getFileName() + (r.getOrDefault("based_on", "").isEmpty() ? "" : "  \u00B7  based on "
          + r.get("based_on")) + (note.isEmpty() ? "" : "  \u00B7  " + Md.plain(note)));
      headPath.setText(path);
    } else {
      headTitle.setText("No project");
      headTitle.setForeground(new java.awt.Color(0x8A, 0x5A, 0x00));
      headSub.setText("Save as new run creates one (this simulation becomes R001).");
      headPath.setText(path.isEmpty() ? "(simulation not saved yet)" : path);
    }
  }

  void wire(JButton b, Tool id) {
    buttonById.put(id, b);
    b.setToolTipText(id.help);
    b.addActionListener(e -> startTool(id));
    toolButtons.add(b);
  }

  /** Runs a tool in a background thread (STAR-CCM+ stays responsive); one tool at a time. */
  void startTool(Tool id) {
    if (busy) return;
    busy = true;
    for (JButton b : toolButtons) b.setEnabled(false);
    if (statusLine != null) statusLine.setText("Running: " + id.title + " ...  (STAR-CCM+ stays usable)");
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
    }, "SimKit " + id.title);
    t.setDaemon(true);
    t.start();
  }

  String simName() {
    try { return sim.getPresentationName(); } catch (Throwable t) { return "?"; }
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
  // Project and runs: a project is a folder (any name, anywhere) holding simkit_project.txt and one folder per run.
  // Each saved run is R<nnn>[_<name>].sim; its number also lives INSIDE the simulation (p_simkit_run), so every copy
  // that comes back from the cluster, whatever its name, still says which run it is and what it was based on.
  // =====================================================================================================

  /** A run of a project: caseDir = the project folder, caseId = R007, nnn = 7, label = the run name, simFile = the
   *  file as it is named (the run file or a copy that came back from the cluster). */
  static final class CaseInfo {
    String caseId, label = "";
    int nnn;
    Path caseDir, simFile;

    String simBase() { return simFile.getFileName().toString().replaceFirst("\\.sim$", ""); }
    Path identityFile() { return runRecord(caseDir, nnn); }
  }

  /** Run of the open sim, or a Cancel with the remedy. */
  CaseInfo requireCase() {
    CaseInfo r = runOrNull();
    if (r != null) return r;
    throw new Cancel(sessionPath().isEmpty() ? "the simulation has not been saved yet: use Save as new run first"
        : "this needs a run of a project: use Save as new run first");
  }

  CaseInfo caseOrNull() { return runOrNull(); }

  static final String STUDY_FILE = "simkit_project.txt", OLD_STUDY_FILE = "simkit_study.txt", P_RUN = "p_simkit_run", P_FROM = "p_simkit_from",
      P_FROM_IT = "p_simkit_from_iteration", ITER_SEP = "_iter_";

  static final Pattern RUN_FILE = Pattern.compile("^R([0-9]{3,4})(?:_([A-Za-z0-9][A-Za-z0-9_-]{0,39}?))?(?:(?:_iter_|@)[^/\\\\]*)?\\.sim$");

  /** A run name as it goes in the file name: spaces become _, then letters, digits, _ and - (up to 40). */
  static String runName(String typed) {
    String n = typed == null ? "" : typed.trim().replaceAll("\\s+", "_");
    if (n.isEmpty()) throw new Cancel("Name the run (e.g. finer_mesh).");
    if (!n.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,39}") || n.contains("_iter_") || n.endsWith("_")) {
      throw new Cancel("Name: letters, digits, _ and - only (40 max).");
    }
    return n;
  }

  /** The name part of a run file (R007_finer_mesh_iter_05000.sim -> finer_mesh), or "". */
  static String nameOf(String file) {
    Matcher m = RUN_FILE.matcher(file == null ? "" : file.substring(file.lastIndexOf('/') + 1));
    return m.matches() && m.group(2) != null ? m.group(2) : "";
  }

  static String runId(int n) { return String.format(Locale.ROOT, "R%03d", n); }

  /** How deep below the project SimKit looks for runs: run.txt and .sim files up to this many folder levels (a run
   *  folder in a group folder, a job folder in the run folder ...). The same limit is used upwards from a file. */
  static final int SCAN_DEPTH = 6;

  /** The project folder holding a file (up to SCAN_DEPTH folders above it), or null. */
  static Path studyDirOf(Path file) {
    Path d = file.toAbsolutePath().normalize().getParent();
    for (int k = 0; d != null && k < SCAN_DEPTH; k++, d = d.getParent()) if (isStudy(d)) return d;
    return null;
  }

  /** The open simulation as a run of its study (number from p_simkit_run, else from the file name), or null. */
  CaseInfo runOrNull() {
    String p = sessionPath();
    if (p.isEmpty()) return null;
    Path f = Paths.get(p).toAbsolutePath().normalize();
    Path st = studyDirOf(f);
    if (st == null) return null;
    int n = -1;
    try {
      ScalarGlobalParameter q = findParam(P_RUN);
      if (q != null) n = (int) Math.round(q.getQuantity().getValue());
    } catch (Throwable t) { /* from the name */ }
    Matcher m = RUN_FILE.matcher(f.getFileName().toString());
    if (n <= 0 && m.matches()) n = Integer.parseInt(m.group(1));
    if (n <= 0) return null;
    CaseInfo c = new CaseInfo();
    c.caseId = runId(n);
    c.nnn = n;
    c.label = m.matches() && m.group(2) != null ? m.group(2) : "";
    if (c.label.isEmpty()) c.label = readIdentity(runRecord(st, n)).getOrDefault("name", "");
    c.caseDir = st;
    c.simFile = f;
    Fs.allow(st);
    return c;
  }

  static final Pattern RUN_DIR = Pattern.compile("^R([0-9]{3,4})(?:_.*)?$");

  static final String RUN_TXT = "run.txt";

  static boolean isStudy(Path dir) {
    return dir != null && (Files.isRegularFile(dir.resolve(STUDY_FILE)) || Files.isRegularFile(dir.resolve(OLD_STUDY_FILE)));
  }

  /** The project's own file (simkit_project.txt, or simkit_study.txt of the first projects). */
  static Path studyFile(Path dir) {
    return Files.isRegularFile(dir.resolve(OLD_STUDY_FILE)) && !Files.isRegularFile(dir.resolve(STUDY_FILE)) ? dir.resolve(OLD_STUDY_FILE)
        : dir.resolve(STUDY_FILE);
  }

  /** The record of every run of a project: <run folder>/run.txt (anywhere below the project, so runs can be grouped in
   *  folders), or record/R<nnn>.txt of the first projects. */
  static Map<Integer, Path> runRecords(Path study) {
    Map<Integer, Path> out = new TreeMap<>();
    for (Path f : Fs.list(study.resolve("record"), "R*.txt")) {
      Matcher x = Pattern.compile("^R([0-9]{3,4})\\.txt$").matcher(f.getFileName().toString());
      if (x.matches()) out.put(Integer.parseInt(x.group(1)), f);
    }
    out.putAll(Scan.of(study).records);
    return out;
  }

  /** The record of the run of key "<what>_v<nnn>" of a study, and the key in it ("comment", "status", "hidden"). */
  static Path runRecord(Path study, int run) {
    Path f = runRecords(study).get(run);
    return f != null ? f : study.resolve("record").resolve(runId(run) + ".txt");
  }

  /** One walk of a project (run.txt and .sim files), kept a few seconds: the tracker asks for the records and the files
   *  of every run several times per refresh, and a walk of a network share is slow. Any write by SimKit in the
   *  project clears it (Fs.write, Fs.mkdirs, saveRun); files changed by others are seen after MAX_AGE_MS. */
  static final class Scan {
    static final long MAX_AGE_MS = 10_000;
    static final Map<String, Scan> CACHE = new HashMap<>();
    final long at = System.currentTimeMillis();
    final Map<Integer, Path> records = new TreeMap<>();
    final Map<Integer, List<Path>> files = new TreeMap<>();

    static synchronized Scan of(Path study) {
      String k = Fs.key(study);
      Scan c = CACHE.get(k);
      if (c != null && System.currentTimeMillis() - c.at < MAX_AGE_MS) return c;
      c = new Scan();
      final Scan sc = c;
      try (java.util.stream.Stream<Path> w = Files.walk(study, SCAN_DEPTH)) {
        w.forEach(p -> {
          Path fn = p.getFileName();
          if (fn == null || p.getParent() == null) return;
          String n = fn.toString();
          if (n.equals(RUN_TXT)) {
            Matcher m = RUN_DIR.matcher(p.getParent().getFileName().toString());
            if (m.matches()) sc.records.put(Integer.parseInt(m.group(1)), p);
          } else if (n.endsWith(".sim") && !p.toString().contains(".simkit") && !p.getParent().getFileName().toString().equals("submit")) {
            Matcher m = RUN_FILE.matcher(n);
            if (m.matches()) sc.files.computeIfAbsent(Integer.parseInt(m.group(1)), x -> new ArrayList<>()).add(p);
          }
        });
      } catch (IOException | java.io.UncheckedIOException e) { /* what was readable */ }
      for (List<Path> l : sc.files.values()) {   // oldest first; same time (a copy keeps its date): the higher iteration last
        l.sort((x, y) -> {
          int d = Long.compare(mtime(x), mtime(y));
          return d != 0 ? d : Double.compare(iterNum(x.getFileName().toString()), iterNum(y.getFileName().toString()));
        });
      }
      CACHE.put(k, c);
      return c;
    }

    /** Forgets the walks of the projects holding p (or of every project when p is null). */
    static synchronized void clear(Path p) {
      if (p == null) { CACHE.clear(); return; }
      String k = Fs.key(p);
      CACHE.keySet().removeIf(s -> k.equals(s) || k.startsWith(s + java.io.File.separator));
    }
  }

  /** Run numbers taken in a project: <project>/.simkit/run_numbers/R<nnn>.txt, created once and kept (who, when). */
  static final String RUN_NUMBERS = ".simkit/run_numbers";

  /** Next free run number of a project (from its records, every run file below it and the numbers taken). */
  static int nextRun(Path study) {
    int max = 0;
    for (int n : runRecords(study).keySet()) max = Math.max(max, n);
    for (int n : studyFiles(study).keySet()) max = Math.max(max, n);
    for (Path f : Fs.list(Fs.inDir(study, RUN_NUMBERS), "R*.txt")) {
      Matcher m = Pattern.compile("^R([0-9]{3,4})\\.txt$").matcher(f.getFileName().toString());
      if (m.matches()) max = Math.max(max, Integer.parseInt(m.group(1)));
    }
    return max + 1;
  }

  /** Takes the next free run number of a project. The number file is created with CREATE_NEW (exclusive on a local
   *  disk, SMB and NFS): two users saving at the same time get two numbers. A number taken by a save that then fails
   *  stays taken (a gap in the numbering, never a double). */
  static int takeRun(Path study) throws IOException {
    Path dir = Fs.inDir(study, RUN_NUMBERS);
    Files.createDirectories(Fs.guard(dir));
    for (int n = nextRun(study), k = 0; k < 50; n++, k++) {
      Path f = dir.resolve(runId(n) + ".txt");
      try {
        Files.write(Fs.guard(f), ("taken_by=" + Env.user() + "\ntaken=" + Fmt.iso() + "\n").getBytes(StandardCharsets.UTF_8),
            java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE);
        Scan.clear(study);
        return n;
      } catch (FileAlreadyExistsException taken) {
        Scan.clear(study);   // someone else took it: look again
      }
    }
    throw new IOException("no free run number found in " + study + " (50 tries)");
  }

  /** The folder of a run file: the R<nnn>_... folder above it that holds run.txt, or null (first studies: sims/). */
  static Path runDirOf(Path file) {
    Path d = file.toAbsolutePath().normalize().getParent();
    for (int k = 0; d != null && k < SCAN_DEPTH; k++, d = d.getParent()) {
      if (isStudy(d)) return null;   // reached the project folder: the file is not in a run folder
      if (Files.isRegularFile(d.resolve(RUN_TXT)) && RUN_DIR.matcher(d.getFileName().toString()).matches()) return d;
    }
    return null;
  }

  /** The group (sub-folder of the study, "" = none) holding the run of a file. */
  static String groupOf(Path study, Path file) {
    Path d = runDirOf(file);
    if (d == null || d.getParent() == null || Fs.same(d.getParent(), study)) return "";
    return study.relativize(d.getParent()).toString().replace('\\', '/');
  }

  /** Run number of a tracker row of a study (the part after .v). */
  static int runOf(String versionId) {
    try { return Integer.parseInt(verOf(versionId)); } catch (NumberFormatException e) { return -1; }
  }

  /** The identity of a case, or for a study: the study's file plus each run's comment_v / status_v / hidden_v. */
  static Map<String, String> identityOf(Path dir, String caseId) {
    Map<String, String> m = readIdentity(studyFile(dir));
    for (Map.Entry<Integer, Path> rf : runRecords(dir).entrySet()) {
      String v = String.format(Locale.ROOT, "%03d", rf.getKey());
      for (Map.Entry<String, String> e : readIdentity(rf.getValue()).entrySet()) m.put(e.getKey() + "_v" + v, e.getValue());
    }
    return m;
  }

  /** Every .sim of a project by run number (the saved run and the copies that came back, anywhere below the project),
   *  oldest first. */
  static Map<Integer, List<Path>> studyFiles(Path study) {
    Map<Integer, List<Path>> out = new TreeMap<>();
    for (Map.Entry<Integer, List<Path>> e : Scan.of(study).files.entrySet()) out.put(e.getKey(), new ArrayList<>(e.getValue()));
    return out;
  }

  static long mtime(Path p) {
    try { return Files.getLastModifiedTime(p).toMillis(); } catch (IOException e) { return 0; }
  }

  static double iterNum(String file) {
    String it = iterOf(file);
    try { return it.isEmpty() ? -1 : Double.parseDouble(it); } catch (NumberFormatException e) { return -1; }
  }

  /** Iteration (or time) in a file name written during or after a run: R007_x_iter_05000.sim, or STAR's @05000. */
  static String iterOf(String file) {
    Matcher m = Pattern.compile("(?:_iter_|@)([0-9.eE+-]+?)(?:-[0-9_]+)?\\.sim$").matcher(file);
    return m.find() ? m.group(1).replaceFirst("^0+(?=[0-9])", "") : "";
  }

  // =====================================================================================================
  // Run records (run.txt, simkit_project.txt: key=value)
  // =====================================================================================================

  /** Reads a run.txt or simkit_project.txt (key=value lines). Missing file -> empty map. */
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

  /** A value for run.txt (one line per key). Text over several lines (comments, descriptions) is kept as
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

  /** The whole run.txt (or simkit_project.txt) for these keys and values. */
  static String identityText(Map<String, String> kv) {
    StringBuilder sb = new StringBuilder("# SimKit record -- written by " + VERSION + "\n");
    for (Map.Entry<String, String> e : kv.entrySet()) sb.append(e.getKey()).append('=').append(idEncode(e.getValue())).append('\n');
    return sb.toString();
  }

  /** Sets one key of a run's run.txt ("comment_v007" -> comment of R007) or of the project file, read just before
   *  writing so the other keys (a colleague's edits) are kept. */
  static void setIdentityKey(Path dir, String caseId, String key, String value) throws IOException {
    Matcher m = Pattern.compile("^([a-z]+)_v([0-9]+)$").matcher(key);
    Path f = m.matches() ? runRecord(dir, Integer.parseInt(m.group(2))) : studyFile(dir);
    Map<String, String> id = readIdentity(f);
    id.put(m.matches() ? m.group(1) : key, value == null ? "" : value);
    Fs.write(f, identityText(id));
  }

  // =====================================================================================================
  // Simulation values and SimKit parameters
  // =====================================================================================================

  int currentIteration() {
    try { return sim.getSimulationIterator().getCurrentIteration(); } catch (Throwable t) { return 0; }
  }

  double physicalTime() {
    try { return sim.getSolution().getPhysicalTime(); } catch (Throwable t) { return 0; }
  }

  /** Implicit unsteady solver, or null (steady / unknown). */
  ImplicitUnsteadySolver unsteadySolver() {
    try {
      for (Object s : sim.getSolverManager().getObjects()) if (s instanceof ImplicitUnsteadySolver) return (ImplicitUnsteadySolver) s;
    } catch (Throwable t) { /* steady or unknown */ }
    return null;
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

  void setParam(String name, double v) {
    ScalarGlobalParameter p = findParam(name);
    if (p == null) p = newParam(name, null, v, null);
    p.getQuantity().setValue(v);
  }

  ScalarGlobalParameter findParam(String name) {
    GlobalParameterManager m = sim.get(GlobalParameterManager.class);
    ScalarGlobalParameter p = findParamIn(m.getObjects(), name, 0);
    if (p == null) {   // inside a parameter folder (group), e.g. "SimKit run tracking"
      try { p = findParamIn(m.getGroupsManager().getObjects(), name, 0); } catch (Throwable t) { /* no folders */ }
    }
    return p;
  }

  static ScalarGlobalParameter findParamIn(Collection<?> objs, String name, int depth) {
    for (Object o : objs) {
      if (o instanceof ScalarGlobalParameter && name.equals(nm(o))) return (ScalarGlobalParameter) o;
      if (depth < 5 && o instanceof star.base.neo.ClientServerObjectManager) {
        ScalarGlobalParameter p = findParamIn(((star.base.neo.ClientServerObjectManager<?>) o).getObjects(), name, depth + 1);
        if (p != null) return p;
      }
    }
    return null;
  }

  static final String PARAM_FOLDER = "SimKit run tracking";

  /** SimKit's own parameters (run number, based on, iteration) in one folder of the Parameters node, apart from the
   *  simulation's own. */
  void groupTracking() {
    List<Object> ps = new ArrayList<>();
    for (String n : new String[] {P_RUN, P_FROM, P_FROM_IT}) {
      ScalarGlobalParameter p = findParam(n);
      if (p != null) ps.add(p);
    }
    toFolder(sim.get(GlobalParameterManager.class), PARAM_FOLDER, ps);
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
  // Save as new run
  // =====================================================================================================

  /** Save as new run (one button): the next run of this study, based on this one at its current iteration; not in a study
   *  yet: starts one (a folder you name) with this simulation as R001. The open file stays as it is on disk. */
  void toolSave() throws IOException {
    final CaseInfo r = runOrNull();
    final String p = sessionPath();
    final Path study = r != null ? r.caseDir : null;
    if (!gui) {
      if (study == null) throw new Cancel("not in a project yet: start one with Save as new run in the STAR-CCM+ window");
      saveRun(study, r.label.isEmpty() ? "next" : r.label, "");
      return;
    }
    if (!p.isEmpty() && unsavedChanges() && !confirmUnsaved(Paths.get(p).getFileName().toString())) throw new Cancel(null);
    final int it = currentIteration();
    String sp = Env.pref("studyParent");
    final Path parent0 = sp != null && !sp.isEmpty() && Files.isDirectory(Paths.get(sp)) ? Paths.get(sp)
        : !p.isEmpty() && Paths.get(p).getParent() != null ? Paths.get(p).toAbsolutePath().getParent() : Paths.get(System.getProperty("user.home"));
    Object[] res = Ui.edt(() -> runWindow(r, study, it, parent0));
    if (res == null) throw new Cancel(null);
    if ("newstudy".equals(res[0])) {   // ticked in Save as new run: this run becomes R001 of a new study, next to this one
      final Path p1 = study.getParent() == null ? parent0 : study.getParent();
      res = Ui.edt(() -> runWindow(r, null, it, p1));
      if (res == null) throw new Cancel(null);
      startStudy((Path) res[2], (String) res[0], (String) res[1]);
      return;
    }
    if (study == null) startStudy((Path) res[2], (String) res[0], (String) res[1]);
    else saveRun(study, (String) res[0], (String) res[1], r, null, (String) res[2]);
  }

  /** True when the open simulation has changes not saved to its file (RootObject.isModified; false if n/a). */
  boolean unsavedChanges() {
    try {
      return sim.isModified();
    } catch (Throwable t) {
      log.na("unsaved changes", t);
      return false;
    }
  }

  /** Warning before Save as new run when the open file has unsaved changes. True: continue. */
  boolean confirmUnsaved(String file) {
    String f = Html.esc(file);
    JLabel text = new JLabel("<html><div style='width:380px'><b>" + f + " has unsaved changes.</b><br><br>"
        + "The changes will be saved in the new run only. " + f + " will not be modified and keeps its last saved state.<br><br>"
        + "After saving, the new run is the open simulation.</div></html>");
    text.setFont(text.getFont().deriveFont(Font.PLAIN, 13f));
    javax.swing.Icon warn = javax.swing.UIManager.getIcon("OptionPane.warningIcon");
    JPanel body = new JPanel(new BorderLayout(14, 0));
    if (warn != null) body.add(new JLabel(warn), BorderLayout.WEST);
    body.add(text, BorderLayout.CENTER);
    boolean go = Ui.sheet("Unsaved changes", "", body, Ui.primary("Save as new run"), null);
    log.line("unsaved changes in " + file + (go ? ": saved in the new run only" : ": cancelled"));
    return go;
  }

  /** The Save as new run window (Swing thread). In a study: {name, comment}; not yet: {name, description, study folder}. */
  Object[] runWindow(final CaseInfo r, final Path study, final int it, Path parent0) {
    final boolean first = study == null;
    final JTextField name = Ui.hintField("", first ? "e.g. baseline" : "e.g. finer_mesh", 22);
    name.setFont(name.getFont().deriveFont(Font.BOLD, 15f));
    final JTextField note = Ui.hintField("", first ? "one line" : "optional", 30);
    final JTextField sname = Ui.hintField("", "e.g. ISO_rebuild_2026", 22);
    final JTextField where = new JTextField(parent0.toString(), 24);
    JButton browse = new JButton("...");
    browse.setToolTipText("Choose the folder");
    browse.addActionListener(e -> {
      JFileChooser fc = new JFileChooser(where.getText());
      fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
      fc.setDialogTitle("Folder of the project");
      if (fc.showOpenDialog(where) == JFileChooser.APPROVE_OPTION) where.setText(fc.getSelectedFile().getPath());
    });
    final int n = first ? 1 : nextRun(study);
    final JLabel file = new JLabel(" ");
    file.setFont(file.getFont().deriveFont(Font.BOLD, 13f));
    // where the run's folder goes: next to the run it comes from; the folder icon picks another place in the study
    final String[] place = {first ? "" : groupOf(study, r.simFile)};
    final JLabel inLbl = Ui.muted(" ");
    final JButton pickDir = new JButton(actionIcon(1));
    pickDir.setToolTipText("Another folder of the project");
    pickDir.setFocusable(false);
    pickDir.setContentAreaFilled(false);
    pickDir.setBorderPainted(false);
    pickDir.setMargin(new java.awt.Insets(0, 2, 0, 2));
    pickDir.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
    final Runnable showPlace = () -> {
      if (first) return;
      inLbl.setText("in  " + study.getFileName() + (place[0].isEmpty() ? "" : "/" + place[0]));
    };
    showPlace.run();
    pickDir.addActionListener(e -> {
      JFileChooser fc = new JFileChooser(place[0].isEmpty() ? study.toFile() : study.resolve(place[0]).toFile());
      fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
      fc.setDialogTitle("Folder for the new run (inside " + study.getFileName() + ")");
      if (fc.showOpenDialog(pickDir) != JFileChooser.APPROVE_OPTION) return;
      Path d = fc.getSelectedFile().toPath().toAbsolutePath().normalize();
      if (!(Fs.same(d, study) || Fs.under(d, study))) {
        JOptionPane.showMessageDialog(pickDir, "Choose a folder inside the project " + study.getFileName() + ".", "SimKit",
            JOptionPane.WARNING_MESSAGE);
        return;
      }
      if (Files.isRegularFile(d.resolve(RUN_TXT))) d = d.getParent();   // a run folder: next to it
      place[0] = Fs.same(d, study) ? "" : study.relativize(d).toString().replace('\\', '/');
      showPlace.run();
    });
    final Runnable upd = () -> {
      String t = name.getText().trim();
      if (t.isEmpty()) {
        file.setForeground(Ui.MUTED);
        file.setText(runId(n) + "_\u2026.sim");
      } else {
        try {
          file.setText(runId(n) + "_" + runName(t) + ".sim");
          file.setForeground(new java.awt.Color(0x1D, 0x27, 0x33));
        } catch (Cancel x) {
          file.setText("Name: letters, digits, _ and - only");
          file.setForeground(Ui.RED);
        }
      }
      String sep = java.io.File.separator, w = where.getText().trim(), sn = sname.getText().trim();
      String st = first ? (w.isEmpty() ? "?" : w.replaceAll("[\\\\/]+$", "")) + sep + (sn.isEmpty() ? "\u2026" : sn) : study.toString();
      file.setToolTipText(st + sep + "sims");   // text only: what is typed may not be a valid path yet
    };
    // where the new run goes: the study's family tree with a dashed card under the open run
    final RunTree tree = first ? null : new RunTree();
    JScrollPane treeView = null;
    if (tree != null) {
      final String sn0 = study.getFileName().toString();
      tree.source = () -> studyRows(study);
      tree.shown = x -> x.matches(".*\\.v[0-9]+$") ? runId(runOf(x)) : x;
      tree.openId = sn0 + ".v" + String.format(Locale.ROOT, "%03d", r.nnn);
      tree.ghost = new String[] {sn0 + ".v" + String.format(Locale.ROOT, "%03d", n), "", tree.openId};
      tree.relayout();
      treeView = new JScrollPane(tree);
      treeView.setBorder(BorderFactory.createLineBorder(new java.awt.Color(0xE3, 0xE7, 0xEC)));
      treeView.getViewport().setBackground(java.awt.Color.WHITE);
      java.awt.Dimension pd = tree.getPreferredSize();
      treeView.setPreferredSize(new java.awt.Dimension(Math.min(640, pd.width + 4), Math.min(230, pd.height + 4)));
      treeView.setAlignmentX(0f);
      final JScrollPane tv = treeView;
      javax.swing.SwingUtilities.invokeLater(() -> {
        java.awt.Rectangle gb = tree.ghostBox();
        if (gb != null) tree.scrollRectToVisible(new java.awt.Rectangle(gb.x - 260, gb.y - 40, gb.width + 300, gb.height + 80));
        tv.repaint();
      });
    }
    final Runnable upd2 = () -> {
      upd.run();
      if (tree != null) {
        String t = name.getText().trim().replaceAll("\\s+", "_");
        tree.ghost[1] = t;
        tree.repaint();
      }
    };
    javax.swing.event.DocumentListener dl = new javax.swing.event.DocumentListener() {
      @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { upd2.run(); }
      @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { upd2.run(); }
      @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { upd2.run(); }
    };
    for (JTextField k : new JTextField[] {name, sname, where}) k.getDocument().addDocumentListener(dl);
    JPanel form = new JPanel(new java.awt.GridBagLayout());
    java.awt.GridBagConstraints g = new java.awt.GridBagConstraints();
    g.insets = new java.awt.Insets(5, 0, 5, 12);
    g.anchor = java.awt.GridBagConstraints.WEST;
    g.fill = java.awt.GridBagConstraints.HORIZONTAL;
    int y = 0;
    if (first) {
      g.gridy = y++; form.add(Ui.muted("Project"), g); form.add(sname, g);
      g.gridy = y++; form.add(Ui.muted("Description"), g); form.add(note, g);
      JPanel wp = new JPanel(new BorderLayout(4, 0));
      wp.add(where, BorderLayout.CENTER);
      wp.add(browse, BorderLayout.EAST);
      g.gridy = y++; form.add(Ui.muted("Folder"), g); form.add(wp, g);
    }
    g.gridy = y++; form.add(Ui.muted(first ? "Run name (R001)" : "Name"), g); form.add(name, g);
    if (!first) { g.gridy = y; form.add(Ui.muted("Comment"), g); form.add(note, g); }
    JPanel body = new JPanel();
    body.setLayout(new javax.swing.BoxLayout(body, javax.swing.BoxLayout.Y_AXIS));
    JPanel formRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
    formRow.add(form);
    javax.swing.JSeparator sep = new javax.swing.JSeparator();
    List<javax.swing.JComponent> parts = new ArrayList<>();
    if (treeView != null) parts.add(treeView);
    final boolean[] toNew = {false};
    final JButton okB = Ui.primary(first ? "Create" : "Save");
    final JButton newRoot = first ? null : Ui.link("New project from this run…");
    if (newRoot != null) {
      newRoot.setToolTipText("R001 of a new project, coming from " + r.caseId);
      newRoot.addActionListener(e -> {
        toNew[0] = true;
        okB.doClick();   // closes this window; the New study window follows
      });
    }
    parts.addAll(Arrays.asList(formRow, sep, file));
    if (!first) {   // the folder line: "in study/sub-folder" and the folder icon
      JPanel where2 = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
      where2.add(inLbl);
      where2.add(javax.swing.Box.createHorizontalStrut(4));
      where2.add(pickDir);
      parts.add(where2);
    }
    for (javax.swing.JComponent k : parts) {
      k.setAlignmentX(0f);
      body.add(k);
      body.add(javax.swing.Box.createVerticalStrut(k == formRow || k == treeView ? 10 : 6));
    }
    upd.run();
    String title = first ? "New project" : "Save as new run";
    String sub = first ? "" : study.getFileName() + "  \u00B7  from " + r.caseId + (r.label.isEmpty() ? "" : " " + r.label) + "  \u00B7  it " + it;
    boolean go = Ui.sheet(title, sub, body, okB, () -> {
      if (toNew[0]) return null;
      try { runName(name.getText()); } catch (Cancel x) { return x.getMessage(); }
      if (!first) return null;
      String sn = sname.getText().trim();
      if (!sn.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,59}")) return "Project: letters, digits, _ . - (no spaces).";
      if (where.getText().trim().isEmpty()) return "Choose the folder.";
      Path d;
      try { d = Paths.get(where.getText().trim()).toAbsolutePath().normalize().resolve(sn); }
      catch (RuntimeException x) { return "Folder: not a valid path."; }
      if (Fs.under(d, Fs.kit()) || Fs.same(d, Fs.kit())) return "Not inside the kit folder.";
      if (isStudy(d)) return "Project " + sn + " already exists there.";
      return null;
    }, newRoot);
    if (!go) return null;
    if (toNew[0]) return new Object[] {"newstudy"};
    String nm = runName(name.getText());
    if (!first) return new Object[] {nm, Fmt.oneLine(note.getText()), place[0]};   // default: next to the run it comes from
    return new Object[] {nm, Fmt.oneLine(note.getText()),
        Paths.get(where.getText().trim()).toAbsolutePath().normalize().resolve(sname.getText().trim())};
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

  /** Saves the open simulation as the next run of a study: R<nnn>[_name].sim in sims/, numbers inside the sim, the
   *  run record (record/R<nnn>.txt), the run label on the scenes. The open file stays as it is on disk. */
  CaseInfo saveRun(Path study, String name, String comment) throws IOException {
    CaseInfo from = runOrNull();
    return saveRun(study, name, comment, from, null, from == null ? "" : groupOf(study, from.simFile));
  }

  CaseInfo saveRun(Path study, String name, String comment, CaseInfo from, Map<String, String> extra, String group) throws IOException {
    int it = currentIteration();
    double time = physicalTime();
    String source = from != null ? from.simFile.toString() : sessionPath();
    Fs.allow(study);
    int n = takeRun(study);
    String id = runId(n);
    String base = id + (name.isEmpty() ? "" : "_" + name);
    Path parent = group == null || group.isEmpty() ? study : study.resolve(group);
    Path f = parent.resolve(base).resolve(base + ".sim");   // its own folder: R008_name/R008_name.sim
    Fs.mkdirs(parent);
    try {
      Files.createDirectory(Fs.guard(f.getParent()));   // new: a folder of that name already there is not reused
    } catch (FileAlreadyExistsException e) {
      throw new Cancel(f.getParent().getFileName() + " already exists in " + parent + ": nothing saved");
    }
    setParam(P_RUN, n);
    setParam(P_FROM, from == null ? 0 : from.nnn);
    setParam(P_FROM_IT, it);
    groupTracking();
    iterSeparator();
    runLabels("Run " + id + (name.isEmpty() ? "" : "  " + name));
    sim.saveState(Fs.guard(f).toString());
    Map<String, String> r = new LinkedHashMap<>();
    r.put("run", id);
    r.put("name", name);
    r.put("comment", comment);
    boolean other = from != null && !Fs.same(from.caseDir, study);   // a run of another study: <study>/R004
    r.put("based_on", from != null ? (other ? from.caseDir.getFileName() + "/" + from.caseId : from.caseId)
        : source.isEmpty() ? "" : Paths.get(source).getFileName().toString());
    r.put("based_on_file", source);
    r.put("from_iteration", String.valueOf(it));
    if (unsteadySolver() != null) r.put("from_time", Fmt.num(time));
    r.put("created", Fmt.iso());
    r.put("created_by", Env.user());
    r.put("file", study.relativize(f).toString().replace('\\', '/'));
    if (extra != null) r.putAll(extra);
    Fs.write(f.getParent().resolve(RUN_TXT), identityText(r));
    Scan.clear(study);
    log.line("saved " + f + "\n" + id + (from == null ? "" : " is based on " + from.caseId + " at iteration " + it)
        + "; the file you came from is unchanged. Ctrl+S keeps saving " + f.getFileName() + ".");
    result("saved " + f.getFileName() + (from == null ? "" : "  (based on " + from.caseId + ", iteration " + it + ")"));
    return runOrNull();
  }

  /** Starts a study: the folder, its sub-folders and simkit_study.txt; then saves the open simulation as R001. */
  void startStudy(Path dir, String name, String desc) throws IOException {
    Fs.allow(dir);
    Fs.mkdirs(dir);
    Map<String, String> m = new LinkedHashMap<>();
    m.put("project", dir.getFileName().toString());
    m.put("description", desc);
    m.put("created", Fmt.iso());
    m.put("created_by", Env.user());
    Fs.write(dir.resolve(STUDY_FILE), identityText(m));
    Env.setPref("studyParent", dir.getParent() == null ? "" : dir.getParent().toString());
    log.line("project " + dir);
    saveRun(dir, name, "", runOrNull(), null, "");
  }

  /** Files written by STAR-CCM+ during and at the end of a run get "_iter_" instead of "@" (R007_base_iter_05000.sim). */
  void iterSeparator() {
    try {
      star.common.AutoSave a = sim.getSimulationIterator().getAutoSave();
      if (!ITER_SEP.equals(a.getSeparator())) a.setSeparator(ITER_SEP);
    } catch (Throwable t) {
      log.na("auto-save name separator", t);
    }
  }

  /** A small label on every scene: "Run R007  baseline" (images in reports then name their run), next to the
   *  iteration (or physical time) annotation of STAR-CCM+. Scenes made later get it at the next Save as new run or Export. */
  void runLabels(String text) {
    try {
      star.vis.AnnotationManager am = sim.getAnnotationManager();
      star.vis.SimpleAnnotation lab = null;
      Object it = null;
      for (Object a : am.getObjects()) {
        if (a instanceof star.vis.SimpleAnnotation && nm(a).equals("simkit_run")) lab = (star.vis.SimpleAnnotation) a;
        if (it == null && (a instanceof star.vis.PhysicalTimeAnnotation && unsteadySolver() != null
            || a instanceof star.vis.IterationAnnotation && unsteadySolver() == null)) it = a;
      }
      if (lab == null) {
        lab = am.createSimpleAnnotation();
        lab.setPresentationName("simkit_run");
      }
      lab.setText(text);
      int n = 0;
      for (Object sc : sim.getSceneManager().getObjects()) {
        try {
          Object pm = Reflect.invoke(sc, "getAnnotationPropManager");
          if (!(Boolean) Reflect.invoke(pm, "hasPropForAnnotation", lab)) {
            Object prop = Reflect.invoke(pm, "createPropForAnnotation", lab);
            try { Reflect.invoke(prop, "setPosition", new star.base.neo.DoubleVector(new double[] {0.02, 0.95, 0.0})); }
            catch (Throwable t) { /* default place */ }
          }
          if (it != null && !(Boolean) Reflect.invoke(pm, "hasPropForAnnotation", it)) Reflect.invoke(pm, "createPropForAnnotation", it);
          n++;
        } catch (Throwable t) {
          log.na("run label on " + nm(sc), t);
        }
      }
      log.line("  run label \"" + text + "\" on " + n + " scene(s)");
    } catch (Throwable t) {
      log.na("run labels", t);
    }
  }

  // =====================================================================================================
  // Run on cluster (Linux only)
  // =====================================================================================================

  /** The team's cluster settings (config/cluster.csv: key, value). */
  Map<String, String> clusterConfig() {
    Map<String, String> m = new LinkedHashMap<>();
    m.put("command", "bsubs.starccmp --input={sim} --ncpus={ncpus} --queue={queue}");
    m.put("queue_serial", "A");
    m.put("queue_parallel", "B");
    m.put("terminal", "konsole");
    for (String[] r : config("cluster.csv", new String[] {"key", "value"}, null)) {
      if (r[0] != null && !r[0].trim().isEmpty() && r[1] != null) m.put(r[0].trim(), r[1].trim());
    }
    return m;
  }

  /** {command, queue} for a file and a number of CPUs (1 = the serial queue). */
  String[] submitCommand(Path simFile, int ncpus) {
    Map<String, String> m = clusterConfig();
    String q = ncpus <= 1 ? m.get("queue_serial") : m.get("queue_parallel");
    String path = simFile.toString().replace('\\', '/');
    if (path.contains(" ")) path = "'" + path + "'";
    return new String[] {m.get("command").replace("{sim}", path).replace("{ncpus}", String.valueOf(ncpus)).replace("{queue}", q), q};
  }

  /** A small script in the run folder's submit/ (also for a returned copy in a job folder below it): goes to the
   *  file's folder, runs the command, keeps what it printed (job number). Not in a run folder: .simkit/ next to it. */
  Path writeSubmitScript(Path simFile, String cmd) throws IOException {
    Path dir = simFile.getParent();
    String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
    String base = simFile.getFileName().toString().replaceFirst("\\.sim$", "");
    Path rd = runDirOf(simFile);
    Path sd = rd != null ? rd.resolve("submit") : dir.resolve(".simkit");
    Path script = sd.resolve("submit_" + base + "_" + stamp + ".sh");
    Path out = sd.resolve("submit_" + base + "_" + stamp + ".log");
    Fs.allow(dir);
    Fs.write(script, "#!/bin/bash\n# SimKit: " + base + " submitted by " + Env.user() + " on " + Fmt.iso() + "\ncd '"
        + dir.toString().replace('\\', '/') + "' || exit 1\necho \"$ " + cmd.replace("\"", "\\\"") + "\"\n" + cmd + " 2>&1 | tee '"
        + out.toString().replace('\\', '/') + "'\n");
    return script;
  }

  /** Run on cluster (Linux only: the cluster is reached from the Linux machines): saves the file in place (with the
   *  _iter_ naming), shows the team's command for the CPUs chosen, then runs it in a terminal opened in the file's folder. */
  void toolSubmit() throws IOException {
    if (Fs.WINDOWS) throw new Cancel("Run on cluster works on the Linux machines only (bsub is not reachable from Windows). Nothing done.");
    final CaseInfo c = requireCase();
    final Path f = Paths.get(sessionPath()).toAbsolutePath().normalize();
    String pc = Env.pref("ncpus");
    final int cpus0 = pc != null && pc.matches("[0-9]+") ? Integer.parseInt(pc) : 1;
    final int[] cpus = {cpus0};
    final List<String[]> checks = preSubmitChecks();   // read here (STAR calls), shown in the window
    for (String[] k : checks) log.line("  pre-submit " + k[0] + "  " + k[1]);
    Boolean ok = Ui.edt(() -> {
      final JTextField n = new JTextField(String.valueOf(cpus0), 5);
      final JLabel queue = Ui.muted(" "), cmd = new JLabel(" ");
      cmd.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
      cmd.setForeground(new java.awt.Color(0x33, 0x3B, 0x45));
      final Runnable upd = () -> {
        String t = n.getText().trim();
        int k = t.matches("[0-9]{1,5}") ? Integer.parseInt(t) : 0;
        String[] sc = submitCommand(f, Math.max(1, k));
        queue.setText(k <= 0 ? "whole number" : (k == 1 ? "serial" : "parallel") + "  \u00B7  queue " + sc[1]);
        cmd.setText("<html><div style='width:520px'>" + Html.esc(sc[0]) + "</div></html>");
      };
      n.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
        @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { upd.run(); }
        @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { upd.run(); }
        @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { upd.run(); }
      });
      upd.run();
      JPanel row = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 0));
      row.add(new JLabel("CPUs"));
      row.add(n);
      row.add(queue);
      JPanel body = new JPanel();
      body.setLayout(new javax.swing.BoxLayout(body, javax.swing.BoxLayout.Y_AXIS));
      JButton copy = Ui.link("Copy");
      JButton go = Ui.primary("Submit");
      copy.addActionListener(e -> {
        String t = n.getText().trim();
        if (!t.matches("[0-9]{1,5}") || Integer.parseInt(t) < 1) return;
        copyText(submitCommand(f, Integer.parseInt(t))[0]);
        copy.setText("Copied");
      });
      String base = f.getFileName().toString().replaceFirst("\\.sim$", "");
      JPanel cmdRow = new JPanel(new BorderLayout(10, 0));
      cmdRow.add(cmd, BorderLayout.CENTER);
      cmdRow.add(copy, BorderLayout.EAST);
      for (javax.swing.JComponent k : new javax.swing.JComponent[] {row, cmdRow,
          Ui.muted("Returns as " + base + ITER_SEP + "<n>.sim")}) {
        k.setAlignmentX(0f);
        body.add(k);
        body.add(javax.swing.Box.createVerticalStrut(6));
      }
      int warn = 0;
      for (String[] k : checks) if (!"ok".equals(k[0])) warn++;
      JLabel ch = new JLabel("Pre-submit check: " + (checks.size() - warn) + " ok, " + warn + " warning(s). Submission is not blocked.");
      ch.setFont(ch.getFont().deriveFont(Font.BOLD, 12f));
      ch.setBorder(BorderFactory.createEmptyBorder(8, 0, 2, 0));
      ch.setAlignmentX(0f);
      body.add(ch);
      for (String[] k : checks) {
        JLabel l = new JLabel(("ok".equals(k[0]) ? "\u2713  " : "!  ") + k[1]);
        l.setForeground("ok".equals(k[0]) ? new java.awt.Color(0x2E, 0x6B, 0x2E) : new java.awt.Color(0x8A, 0x5A, 0x00));
        l.setAlignmentX(0f);
        body.add(l);
      }
      return Ui.sheet("Run on cluster", f.getFileName().toString(), body, go, () -> {
        String t = n.getText().trim();
        if (!t.matches("[0-9]{1,5}") || Integer.parseInt(t) < 1) return "CPUs: 1 or more.";
        cpus[0] = Integer.parseInt(t);
        return null;
      });
    });
    if (!Boolean.TRUE.equals(ok)) throw new Cancel(null);
    Env.setPref("ncpus", String.valueOf(cpus[0]));
    iterSeparator();
    sim.saveState(Fs.guard(f).toString());
    String[] sc = submitCommand(f, cpus[0]);
    Path script = writeSubmitScript(f, sc[0]);
    Path subs = script.getParent().resolve("submissions.csv");
    boolean fresh = !Files.isRegularFile(subs);
    try (java.io.Writer w = Files.newBufferedWriter(Fs.guard(subs), StandardCharsets.UTF_8,
        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)) {
      if (fresh) w.write(Csv.row(new String[] {"submitted", "by", "run", "file", "ncpus", "queue", "command", "script"}));
      w.write(Csv.row(new String[] {Fmt.iso(), Env.user(), c.caseId, f.toString(), String.valueOf(cpus[0]), sc[1], sc[0],
          script.toString()}));
    } catch (Throwable t) {
      log.na("submission record", t);
    }
    String term = clusterConfig().get("terminal");
    String inner = "bash '" + script + "'; echo; echo 'SimKit: this window can be closed.'; exec bash";
    List<String[]> tries = new ArrayList<>();
    tries.add(term.equals("konsole") ? new String[] {"konsole", "--workdir", f.getParent().toString(), "-e", "bash", "-c", inner}
        : new String[] {term, "-e", "bash", "-c", inner});
    tries.add(new String[] {"konsole", "--workdir", f.getParent().toString(), "-e", "bash", "-c", inner});
    tries.add(new String[] {"gnome-terminal", "--working-directory=" + f.getParent(), "--", "bash", "-c", inner});
    tries.add(new String[] {"xterm", "-e", "bash", "-c", inner});
    for (String[] t : tries) {
      try {
        new ProcessBuilder(t).directory(f.getParent().toFile()).start();
        result("submitted in a " + t[0] + " window: " + sc[0]);
        return;
      } catch (IOException e) { /* the next terminal */ }
    }
    copyText(sc[0]);
    result("no terminal found (konsole, gnome-terminal, xterm): the command is copied, paste it in a terminal");
  }

  static void copyText(String t) {
    try {
      java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(t), null);
    } catch (Throwable x) { /* no clipboard (batch) */ }
  }

  /** {"ok" | "!", text} lines: mesh, stopping criteria, auto-save, standard reports, names, run number. */
  List<String[]> preSubmitChecks() {
    List<String[]> out = new ArrayList<>();
    try {
      boolean mesh = false;
      for (Object rep : sim.getRepresentationManager().getObjects()) if (rep instanceof FvRepresentation) mesh = true;
      out.add(new String[] {mesh ? "ok" : "!", mesh ? "volume mesh present" : "no volume mesh: the job meshes first, or fails"});
    } catch (Throwable t) { out.add(new String[] {"!", "volume mesh: n/a (" + reason(t) + ")"}); }
    try {
      List<String> used = new ArrayList<>();
      for (Object c : sim.getSolverStoppingCriterionManager().getObjects()) {
        if (Boolean.TRUE.equals(Reflect.tryCall(c, "getIsUsed"))) used.add(nm(c));
      }
      out.add(used.isEmpty() ? new String[] {"!", "no stopping criterion enabled: the job runs until killed"}
          : new String[] {"ok", "stopping criteria: " + String.join(", ", used)});
    } catch (Throwable t) { out.add(new String[] {"!", "stopping criteria: n/a (" + reason(t) + ")"}); }
    try {
      AutoSave as = sim.getSimulationIterator().getAutoSave();
      Object on = Reflect.tryCall(as.getStarUpdate(), "getEnabled");
      if (on == null) on = Reflect.tryCall(as.getStarUpdate(), "isEnabled");
      Object max = Reflect.tryCall(as, "getMaxAutosavedFiles");
      if (!Boolean.TRUE.equals(on)) out.add(new String[] {"!", "auto-save off: nothing is saved before the end of the job"});
      else if (max instanceof Number && ((Number) max).intValue() == 0) out.add(new String[] {"!", "auto-save keeps every file (maximum files = 0)"});
      else out.add(new String[] {"ok", "auto-save on" + (max instanceof Number ? ", keeps " + max + " file(s)" : "")});
    } catch (Throwable t) { out.add(new String[] {"!", "auto-save: n/a (" + reason(t) + ")"}); }
    try {
      int n = 0;
      for (Object r : sim.getReportManager().getObjects()) if (nm(r).startsWith("r_")) n++;
      out.add(n > 0 ? new String[] {"ok", n + " standard report(s) (r_*)"} : new String[] {"!", "no standard reports (r_*): Standard reports adds them"});
    } catch (Throwable t) { out.add(new String[] {"!", "standard reports: n/a (" + reason(t) + ")"}); }
    try {
      int def = 0, mism = 0, nopre = 0;
      for (NameRow x : nameRows()) {
        if (x.state.startsWith("default name")) def++;
        else if (x.kind.equals("boundary") && x.state.startsWith("no type prefix")) nopre++;
        else if (x.kind.equals("boundary") && x.problem) mism++;
      }
      out.add(mism > 0 ? new String[] {"!", mism + " boundary type(s) do not match the name prefix (Names)"}
          : new String[] {"ok", "boundary types match the name prefixes"});
      if (nopre > 0) out.add(new String[] {"!", nopre + " boundary(ies) without a type prefix (Names)"});
      if (def > 0) out.add(new String[] {"!", def + " object(s) with a default name (Names)"});
    } catch (Throwable t) { out.add(new String[] {"!", "names: n/a (" + reason(t) + ")"}); }
    out.add(findParam(P_RUN) != null ? new String[] {"ok", "run number in the simulation (" + P_RUN + ")"}
        : new String[] {"!", "no run number in the simulation: Save as new run first"});
    return out;
  }

  // =====================================================================================================
  // Tracker: one row per run of the project, read from the run folders; status and comments editable
  // =====================================================================================================

  /** Tracker columns. ID = <project>.v<nnn> (one per run, shown as R<nnn>; "based on" refers to it). comment and
   *  status are the user's (editable); everything else is read from the run folders. */
  static final String[] TRACKER_COLS = {"ID", "comment", "status", "based on", "description", "saved", "file"};

  static final int COL_ID = 0, COL_COMMENT = 1, COL_STATUS = 2, COL_BASED = 3, COL_DESC = 4, COL_SAVED = 5, COL_FILE = 6;

  /** One row per run of a study: its latest file (what you continue from), comment, status, based on. */
  static List<String[]> studyRows(Path study) {
    List<String[]> rows = new ArrayList<>();
    String name = study.getFileName().toString();
    Map<Integer, List<Path>> files = studyFiles(study);
    Map<Integer, Path> recs = runRecords(study);
    Set<Integer> runs = new TreeSet<>(files.keySet());
    runs.addAll(recs.keySet());
    String desc = readIdentity(studyFile(study)).getOrDefault("description", "");
    for (int n : runs) {
      Map<String, String> r0 = recs.containsKey(n) ? readIdentity(recs.get(n)) : new HashMap<>();
      List<Path> fs = files.getOrDefault(n, Collections.emptyList());
      String[] r = new String[TRACKER_COLS.length];
      Arrays.fill(r, "");
      r[COL_ID] = name + ".v" + String.format(Locale.ROOT, "%03d", n);
      r[COL_COMMENT] = r0.getOrDefault("comment", "");
      r[COL_STATUS] = r0.getOrDefault("status", "");
      Matcher b = Pattern.compile("^R([0-9]{3,4})$").matcher(r0.getOrDefault("based_on", ""));
      r[COL_BASED] = b.matches() ? name + ".v" + String.format(Locale.ROOT, "%03d", Integer.parseInt(b.group(1))) : r0.getOrDefault("based_on", "");
      r[COL_DESC] = desc;
      Path last = fs.isEmpty() ? null : fs.get(fs.size() - 1);
      if (last != null) {
        r[COL_SAVED] = new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(mtime(last)));
        r[COL_FILE] = study.relativize(last).toString().replace('\\', '/');
      } else {
        r[COL_SAVED] = r0.getOrDefault("created", "");
        r[COL_FILE] = r0.getOrDefault("file", "");
      }
      rows.add(r);
    }
    return rows;
  }

  static String caseOf(String versionId) {
    int i = versionId.lastIndexOf(".v");
    return i < 0 ? versionId : versionId.substring(0, i);
  }

  static String verOf(String versionId) {
    int i = versionId.lastIndexOf(".v");
    return i < 0 ? "" : versionId.substring(i + 2);
  }

  /** Hidden in the tracker (files untouched): hidden_v<nnn>=yes, or hidden=yes on the project (older SimKit). */
  static boolean isHidden(Map<String, String> id, String v) {
    return "yes".equals(id.get("hidden")) || "yes".equals(id.get("hidden_v" + v));
  }

  /** File names (rows) currently hidden in a project. */
  static Set<String> hiddenFiles(Path casesDir, List<String[]> rows) {
    Set<String> out = new HashSet<>();
    Map<String, Map<String, String>> ids = new HashMap<>();
    for (String[] r : rows) {
      String cid = caseOf(r[COL_ID]);
      Map<String, String> id = ids.computeIfAbsent(cid, k -> identityOf(casesDir, k));
      if (isHidden(id, verOf(r[COL_ID]))) out.add(r[COL_FILE]);
    }
    return out;
  }

  /** The file of a tracker row (its path below the project folder). */
  static Path fileOf(Path dir, String[] r) {
    return dir.resolve(r[COL_FILE]);
  }

  /** Hides or shows again one run (v = its number), or the whole project (v == null, older SimKit); only run.txt changes. */
  static void setHidden(Path dir, String caseId, String v, boolean hide) throws IOException {
    Path f = v == null ? studyFile(dir) : runRecord(dir, Integer.parseInt(v));
    Map<String, String> m = readIdentity(f);
    if (hide) m.put("hidden", "yes"); else m.remove("hidden");
    Fs.write(f, identityText(m));
  }

  static final String[] STATUSES = {"", "planned", "meshing", "running", "diverged", "done", "dropped"};   // "reported" (older cases): still shown
  static final String TRACKER_CSV = "case_tracker.csv";

  /** One row per run of the project. Reads only files; never the simulation. */
  static List<String[]> trackerRows(Path dir) {
    return studyRows(dir);
  }

  /** Tracker: floating window of every run of the project; comment and status are saved as you edit. */
  void toolTracker() throws IOException {
    CaseInfo c = caseOrNull();
    Path dir = c != null ? c.caseDir : projectDirToOpen();
    Fs.allow(dir);
    List<String[]> rows = trackerRows(dir);
    if (!gui) {   // batch (test): write the CSV only
      writeTrackerCsv(dir, rows);
      log.line("tracker: " + rows.size() + " run(s) -> " + dir.resolve(TRACKER_CSV));
      return;
    }
    log.line("tracker: " + rows.size() + " run(s) in " + dir + " (window open)");
    result("tracker open, " + rows.size() + " run(s)");
    final String openId = c == null ? null : c.caseDir.getFileName() + ".v" + String.format(Locale.ROOT, "%03d", c.nnn);
    Ui.edt(() -> {
      TrackerWindow t = new TrackerWindow(dir, openId);
      t.w.setLocationRelativeTo(Ui.owner());
      Env.track(t.w);
      t.w.setVisible(true);   // non-modal: STAR-CCM+ stays usable
      return null;
    });
  }

  /** The open sim is not a run: the user picks a project folder (one holding simkit_project.txt, or a folder in it). */
  Path projectDirToOpen() {
    if (!gui) throw new Cancel("the open simulation is not a run of a project");
    String last = Env.pref("trackerDir");
    String p = sessionPath();
    Path start = last != null && Files.isDirectory(Paths.get(last)) ? Paths.get(last)
        : !p.isEmpty() && Paths.get(p).getParent() != null ? Paths.get(p).toAbsolutePath().getParent() : Paths.get(System.getProperty("user.home"));
    Path dir = Ui.chooseDir("Tracker: project folder (it holds " + STUDY_FILE + ")", start);
    Path st = isStudy(dir) ? dir : studyDirOf(dir.resolve("x"));
    if (st == null) throw new Cancel(dir + " is not a project folder (no " + STUDY_FILE + "): Save as new run creates one");
    Env.setPref("trackerDir", st.toString());
    return st;
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

  /** Opens a .sim in this STAR-CCM+ session (another simulation, with its own tab in the window). Returns null when it
   *  is already open here. Slow (starts a server and loads the file): never call it on the Swing thread. */
  static Simulation openSim(Path f) {
    if (isOpen(f)) return null;
    return new Simulation(f.toAbsolutePath().normalize().toString());
  }

  /** True when this .sim is open in this STAR-CCM+ session. */
  static boolean isOpen(Path f) {
    Path want = f.toAbsolutePath().normalize();
    for (Simulation s : SimulationManager.getSingleton().simulations()) {
      try {
        String p = s.getSessionPath();
        if (p != null && !p.isEmpty() && Paths.get(p).toAbsolutePath().normalize().equals(want)) return true;
      } catch (Throwable t) { /* a simulation without a file */ }
    }
    return false;
  }

  /** The window object (star.coremodule.SimulationProcessObject) of this .sim when it is open with its own tab, else null. */
  static Object simWindow(Path f) {
    try {
      Class<?> c = Class.forName("star.coremodule.SimulationProcessObject", true, StarSettings.class.getClassLoader());
      Object[] all = (Object[]) c.getMethod("activeSimulationProcessObjects").invoke(null);
      for (Object o : all) {
        Simulation s = (Simulation) c.getMethod("getSimulation").invoke(o);
        String p = s == null ? null : s.getSessionPath();
        if (p != null && !p.isEmpty() && Fs.same(Paths.get(p), f)) return o;
      }
    } catch (Throwable t) { /* no STAR-CCM+ window (batch) */ }
    return null;
  }

  /** STAR-CCM+'s own Load Simulation dialog (what File > Load shows: star.coremodule.ui.OpenOptionsDialog) with this file
   *  filled in. show = false only checks that the dialog and its calls exist (batch test). Swing thread only when shown. */
  static void loadDialog(Path f, boolean show) throws Exception {
    Class<?> c = Class.forName("star.coremodule.ui.OpenOptionsDialog", true, StarSettings.class.getClassLoader());
    java.lang.reflect.Method set = c.getMethod("setSimFile", String.class), open = null;
    for (java.lang.reflect.Method m : c.getMethods()) {
      if (m.getName().equals("show") && m.getParameterCount() == 1 && !m.getParameterTypes()[0].isPrimitive()) open = m;
    }
    if (open == null) throw new NoSuchMethodException("OpenOptionsDialog.show(RootProcessObject)");
    java.lang.reflect.Constructor<?> k = c.getConstructor(String.class, boolean.class, boolean.class);
    if (!show) return;
    Object d = k.newInstance("Load Simulation", true, true);   // as File > Load: start a server, browse allowed
    set.invoke(d, f.toAbsolutePath().normalize().toString());
    open.invoke(d, (Object) null);
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
      case "diverged": return new java.awt.Color[] {new java.awt.Color(0xFD, 0xE2, 0xE0), new java.awt.Color(0xB4, 0x23, 0x18)};
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
    String f = file == null ? "" : file.substring(file.lastIndexOf('/') + 1);
    Matcher r = RUN_FILE.matcher(f);
    if (r.matches()) {   // a run: its name, and the iteration of a file that came back from a run
      String it = iterOf(f);
      return (r.group(2) == null ? "" : r.group(2)) + (it.isEmpty() ? "" : (r.group(2) == null ? "" : "  \u00B7  ") + "it " + it);
    }
    return "";
  }

  static String html(String s) {
    return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  /** Small painted icons (no font glyphs: the same on Windows and RHEL). kind: 0 folded, 1 unfolded, 2 open in STAR, 3 blank. */
  /** Small icons of the tracker's actions: 0 results (bars), 1 folder, 2 copy, 4 hide (eye crossed), 5 show again (eye). */
  static javax.swing.Icon actionIcon(final int kind) {
    return new javax.swing.Icon() {
      @Override public int getIconWidth() { return 18; }
      @Override public int getIconHeight() { return 18; }
      @Override public void paintIcon(java.awt.Component c, java.awt.Graphics g0, int x, int y) {
        java.awt.Graphics2D g = (java.awt.Graphics2D) g0.create();
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        g.translate(x, y);
        g.setColor(c != null && !c.isEnabled() ? new java.awt.Color(0xC4, 0xCA, 0xD2) : new java.awt.Color(0x4A, 0x55, 0x63));
        g.setStroke(new java.awt.BasicStroke(1.5f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
        switch (kind) {
          case 0:
            g.fillRect(3, 10, 3, 5); g.fillRect(8, 6, 3, 9); g.fillRect(13, 3, 3, 12);
            g.drawLine(2, 16, 16, 16);
            break;
          case 1:
            g.drawPolyline(new int[] {2, 2, 7, 9, 16, 16, 2}, new int[] {15, 4, 4, 6, 6, 15, 15}, 7);
            break;
          case 2:
            g.drawRoundRect(6, 6, 9, 10, 2, 2);
            g.drawPolyline(new int[] {12, 12, 3, 3, 6}, new int[] {4, 2, 2, 12, 12}, 5);
            break;
          default:
            g.draw(new java.awt.geom.QuadCurve2D.Double(2, 9, 9, 2, 16, 9));
            g.draw(new java.awt.geom.QuadCurve2D.Double(2, 9, 9, 16, 16, 9));
            g.drawOval(7, 7, 4, 4);
            if (kind == 4) g.drawLine(3, 15, 15, 3);
        }
        g.dispose();
      }
    };
  }

  /** Runs drawn as a family tree: generations left to right, each card to the right of the run it is based on, soft
   *  curved links. Used by the tracker (Family tree) and by Save as new run, where a dashed blue card shows the run about to
   *  be saved (ghost = {id, name, parent id}). */
  static final class RunTree extends javax.swing.JComponent {
    private static final long serialVersionUID = 1L;
    static final int CW = 172, CH = 54, HG = 50, VG = 16, M = 20;
    java.util.function.Supplier<List<String[]>> source = ArrayList::new;
    java.util.function.Function<String, String> shown = x -> x;
    java.util.function.BiConsumer<String, Integer> onClick;
    /** Right click on a card: (id, event). */
    java.util.function.BiConsumer<String, java.awt.event.MouseEvent> onMenu;
    /** All runs, the hidden ones included: a run whose parent is hidden is linked to the nearest shown ancestor. */
    java.util.function.Supplier<List<String[]>> lineage;
    /** Hidden runs (drawn faded when shown). */
    java.util.function.Predicate<String> isHidden = x -> false;
    final Set<String> bridged = new HashSet<>();   // links that pass over hidden runs (dashed)
    String openId, picked;
    String[] ghost;
    final Map<String, java.awt.Rectangle> box = new LinkedHashMap<>();
    final Map<String, String> parent = new HashMap<>();
    final Map<String, String[]> byId = new HashMap<>();
    static final java.awt.Color INK = new java.awt.Color(0x1D, 0x27, 0x33), EDGE = new java.awt.Color(0xC3, 0xCB, 0xD5),
        FRAME = new java.awt.Color(0xD5, 0xD9, 0xDE);

    RunTree() {
      setOpaque(true);
      setBackground(java.awt.Color.WHITE);
      javax.swing.ToolTipManager.sharedInstance().registerComponent(this);
      addMouseListener(new java.awt.event.MouseAdapter() {
        @Override public void mousePressed(java.awt.event.MouseEvent e) { menu(e); }
        @Override public void mouseReleased(java.awt.event.MouseEvent e) { menu(e); }
        void menu(java.awt.event.MouseEvent e) {
          if (!e.isPopupTrigger() || onMenu == null) return;
          String id = at(e.getPoint());
          if (id == null || ghost != null && id.equals(ghost[0])) return;
          picked = id;
          repaint();
          onMenu.accept(id, e);
        }
        @Override public void mouseClicked(java.awt.event.MouseEvent e) {
          if (!javax.swing.SwingUtilities.isLeftMouseButton(e)) return;
          String id = at(e.getPoint());
          if (id == null || onClick == null || ghost != null && id.equals(ghost[0])) return;
          picked = id;
          repaint();
          onClick.accept(id, e.getClickCount());
        }
      });
      addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
        @Override public void mouseMoved(java.awt.event.MouseEvent e) {
          boolean hand = onClick != null && at(e.getPoint()) != null;
          setCursor(java.awt.Cursor.getPredefinedCursor(hand ? java.awt.Cursor.HAND_CURSOR : java.awt.Cursor.DEFAULT_CURSOR));
        }
      });
      // click and drag anywhere: moves the view (a click without moving still selects a box)
      final java.awt.Point[] grab = new java.awt.Point[2];   // mouse on screen, view position at the press
      addMouseListener(new java.awt.event.MouseAdapter() {
        @Override public void mousePressed(java.awt.event.MouseEvent e) {
          javax.swing.JViewport vp = (javax.swing.JViewport) javax.swing.SwingUtilities.getAncestorOfClass(javax.swing.JViewport.class, RunTree.this);
          if (vp == null) return;
          grab[0] = e.getLocationOnScreen();
          grab[1] = vp.getViewPosition();
        }
        @Override public void mouseReleased(java.awt.event.MouseEvent e) {
          grab[0] = null;
          setCursor(java.awt.Cursor.getDefaultCursor());
        }
      });
      addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
        @Override public void mouseDragged(java.awt.event.MouseEvent e) {
          javax.swing.JViewport vp = (javax.swing.JViewport) javax.swing.SwingUtilities.getAncestorOfClass(javax.swing.JViewport.class, RunTree.this);
          if (vp == null || grab[0] == null) return;
          java.awt.Point now = e.getLocationOnScreen();
          int x = grab[1].x - (now.x - grab[0].x), y = grab[1].y - (now.y - grab[0].y);
          java.awt.Dimension view = getSize(), ext = vp.getExtentSize();
          x = Math.max(0, Math.min(x, Math.max(0, view.width - ext.width)));
          y = Math.max(0, Math.min(y, Math.max(0, view.height - ext.height)));
          setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.MOVE_CURSOR));
          vp.setViewPosition(new java.awt.Point(x, y));
        }
      });
    }

    String at(java.awt.Point p) {
      for (Map.Entry<String, java.awt.Rectangle> e : box.entrySet()) if (e.getValue().contains(p)) return e.getKey();
      return null;
    }

    @Override public String getToolTipText(java.awt.event.MouseEvent e) {
      String id = at(e.getPoint());
      String[] r = id == null ? null : byId.get(id);
      if (r == null) return null;
      String c = r[COL_COMMENT];
      return "<html><head><style>p{margin-top:0;margin-bottom:4px}ul,ol{margin-top:0;margin-bottom:4px;margin-left:16px}</style></head>"
          + "<body style='width:320px'><b>" + html(shown.apply(id)) + "</b>&nbsp;&nbsp;" + html(fileLabel(r[COL_FILE]))
          + "<br><font color='#6B7580'>" + html(r[COL_FILE]) + "</font>" + (c.trim().isEmpty() ? "" : "<br><br>" + Md.toHtml(c))
          + "</body></html>";
    }

    java.awt.Rectangle ghostBox() { return ghost == null ? null : box.get(ghost[0]); }

    /** Places the cards: children in the next column, a parent centred on its children. */
    void relayout() {
      box.clear();
      parent.clear();
      byId.clear();
      bridged.clear();
      List<String> ids = new ArrayList<>();
      for (String[] r : source.get()) { ids.add(r[COL_ID]); byId.put(r[COL_ID], r); }
      Set<String> all = new HashSet<>(ids);
      Map<String, String> basedAll = new HashMap<>();
      if (lineage != null) for (String[] r : lineage.get()) basedAll.put(r[COL_ID], r[COL_BASED]);
      Map<String, List<String>> kids = new LinkedHashMap<>();
      List<String> roots = new ArrayList<>();
      for (String id : ids) {
        String b = byId.get(id)[COL_BASED];
        for (int k = 0; k < 50 && !b.isEmpty() && !all.contains(b) && basedAll.containsKey(b); k++) {   // over hidden runs
          b = basedAll.get(b);
          if (all.contains(b)) bridged.add(id);
        }
        if (!b.isEmpty() && all.contains(b) && !b.equals(id)) {
          parent.put(id, b);
          kids.computeIfAbsent(b, k -> new ArrayList<>()).add(id);
        } else {
          roots.add(id);
        }
      }
      if (ghost != null) {
        if (ghost[2] != null && all.contains(ghost[2])) {
          parent.put(ghost[0], ghost[2]);
          kids.computeIfAbsent(ghost[2], k -> new ArrayList<>()).add(ghost[0]);
        } else {
          roots.add(ghost[0]);
        }
      }
      int[] slot = {0};
      Set<String> seen = new HashSet<>();
      for (String r : roots) place(r, 0, kids, slot, seen);
      for (String id : ids) if (!seen.contains(id)) place(id, 0, kids, slot, seen);   // a loop in the records: drawn as roots
      int wMax = M, hMax = M;
      for (java.awt.Rectangle b : box.values()) { wMax = Math.max(wMax, b.x + b.width + M); hMax = Math.max(hMax, b.y + b.height + M); }
      setPreferredSize(new java.awt.Dimension(wMax, hMax));
      revalidate();
      repaint();
    }

    int place(String id, int depth, Map<String, List<String>> kids, int[] slot, Set<String> seen) {
      seen.add(id);
      List<Integer> ys = new ArrayList<>();
      for (String k : kids.getOrDefault(id, Collections.emptyList())) if (!seen.contains(k)) ys.add(place(k, depth + 1, kids, slot, seen));
      int cy = ys.isEmpty() ? M + slot[0]++ * (CH + VG) + CH / 2 : (ys.get(0) + ys.get(ys.size() - 1)) / 2;
      box.put(id, new java.awt.Rectangle(M + depth * (CW + HG), cy - CH / 2, CW, CH));
      return cy;
    }

    @Override protected void paintComponent(java.awt.Graphics g0) {
      java.awt.Graphics2D g = (java.awt.Graphics2D) g0.create();
      g.setColor(java.awt.Color.WHITE);
      g.fillRect(0, 0, getWidth(), getHeight());
      g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
      g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
      String gid = ghost == null ? null : ghost[0];
      java.awt.Stroke solid = new java.awt.BasicStroke(1.6f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND);
      java.awt.Stroke dash = new java.awt.BasicStroke(1.8f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND, 1f,
          new float[] {5f, 4f}, 0f);
      for (Map.Entry<String, String> e : parent.entrySet()) {
        java.awt.Rectangle a = box.get(e.getValue()), b = box.get(e.getKey());
        if (a == null || b == null) continue;
        boolean gh = e.getKey().equals(gid), over = bridged.contains(e.getKey());
        g.setStroke(gh || over ? dash : solid);
        g.setColor(gh ? Ui.BLUE : EDGE);
        double x1 = a.x + a.width, y1 = a.y + a.height / 2.0, x2 = b.x, y2 = b.y + b.height / 2.0, mx = (x1 + x2) / 2;
        g.draw(new java.awt.geom.CubicCurve2D.Double(x1, y1, mx, y1, mx, y2, x2, y2));
        g.fill(new java.awt.geom.Ellipse2D.Double(x2 - 3, y2 - 3, 6, 6));
      }
      Font f = getFont() == null ? new Font(Font.SANS_SERIF, Font.PLAIN, 12) : getFont();
      Font bold = f.deriveFont(Font.BOLD, 13f), small = f.deriveFont(Font.PLAIN, 11f);
      for (Map.Entry<String, java.awt.Rectangle> e : box.entrySet()) {
        String id = e.getKey();
        java.awt.Rectangle b = e.getValue();
        boolean gh = id.equals(gid);
        String[] r = byId.get(id);
        if (r == null && !gh) continue;
        String st = gh ? "" : r[COL_STATUS];
        java.awt.Color[] sc = statusColors(st);
        boolean hid = !gh && isHidden.test(id);
        java.awt.Composite comp0 = g.getComposite();
        if (hid) g.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.45f));
        boolean dropped = st.equals("dropped"), open = id.equals(openId), sel = id.equals(picked);
        java.awt.geom.RoundRectangle2D card = new java.awt.geom.RoundRectangle2D.Double(b.x, b.y, b.width, b.height, 12, 12);
        if (!gh) {
          g.setColor(new java.awt.Color(0, 0, 0, 18));   // a soft shadow
          g.fill(new java.awt.geom.RoundRectangle2D.Double(b.x + 1, b.y + 2, b.width, b.height, 12, 12));
        }
        g.setColor(gh ? Ui.PALE : dropped ? new java.awt.Color(0xF7, 0xF7, 0xF8) : java.awt.Color.WHITE);
        g.fill(card);
        if (!gh) {
          java.awt.Shape clip = g.getClip();
          g.clip(card);   // the status colour as a band on the left
          g.setColor(sc == null ? FRAME : sc[1]);
          g.fillRect(b.x, b.y, 5, b.height);
          g.setClip(clip);
        }
        g.setColor(gh || sel ? Ui.BLUE : FRAME);
        g.setStroke(gh ? dash : new java.awt.BasicStroke(sel ? 2f : 1f));
        g.draw(card);
        int tx = b.x + 14, right = b.x + b.width - 10 - (open ? 14 : 0);
        String head = shown.apply(id), nm = gh ? ghost[1] : nameOf(r[COL_FILE]);
        String itx = gh ? "" : iterOf(r[COL_FILE]);
        {   // the run number as a small tag, then its name
          g.setFont(small.deriveFont(Font.BOLD));
          int tw = g.getFontMetrics().stringWidth(head);
          g.setColor(gh ? new java.awt.Color(0xD6, 0xE4, 0xF7) : new java.awt.Color(0xEC, 0xEF, 0xF3));
          g.fill(new java.awt.geom.RoundRectangle2D.Double(tx - 3, b.y + 8, tw + 8, 17, 6, 6));
          g.setColor(gh ? Ui.BLUE : new java.awt.Color(0x4A, 0x55, 0x63));
          g.drawString(head, tx + 1, b.y + 21);
          int nx = tx + tw + 11;
          g.setFont(bold);
          g.setColor(gh ? Ui.BLUE : dropped ? new java.awt.Color(0x9A, 0xA2, 0xAB) : INK);
          g.drawString(fit(g, nm == null || nm.isEmpty() ? "\u2026" : nm, right - nx), nx, b.y + 21);
        }
        if (open) {
          g.setColor(new java.awt.Color(0x2E, 0x9E, 0x4F));
          g.fillOval(b.x + b.width - 16, b.y + 13, 8, 8);
        }
        int x2 = tx;
        if (sc != null) {
          g.setFont(small.deriveFont(Font.BOLD));
          int sw = g.getFontMetrics().stringWidth(st);
          g.setColor(sc[0]);
          g.fill(new java.awt.geom.RoundRectangle2D.Double(x2 - 3, b.y + 29, sw + 10, 16, 16, 16));
          g.setColor(sc[1]);
          g.drawString(st, x2 + 2, b.y + 41);
          x2 += sw + 14;
        }
        g.setFont(small);
        g.setColor(gh ? Ui.BLUE : Ui.MUTED);
        List<String> parts = new ArrayList<>();
        if (hid) parts.add("hidden");
        if (!itx.isEmpty()) parts.add("it " + itx);
        String cm = gh ? "" : Md.plain(r[COL_COMMENT]).replace(" / ", "  ").trim();
        if (!cm.isEmpty()) parts.add(cm);
        String line2 = gh ? "new run" : String.join("  \u00B7  ", parts);
        g.drawString(fit(g, line2, b.x + b.width - 8 - x2), x2, b.y + 41);
        g.setComposite(comp0);
      }
      if (box.isEmpty()) {
        g.setColor(Ui.MUTED);
        g.drawString("Nothing to show yet.", M, M + 20);
      }
      g.dispose();
    }

    static String fit(java.awt.Graphics2D g, String s, int w) {
      if (s == null || w <= 10) return "";
      java.awt.FontMetrics fm = g.getFontMetrics();
      if (fm.stringWidth(s) <= w) return s;
      while (s.length() > 1 && fm.stringWidth(s + "\u2026") > w) s = s.substring(0, s.length() - 1);
      return s + "\u2026";
    }
  }

  /** The standard template of a comment: four headings, each with an empty bullet. */
  static final String COMMENT_TEMPLATE = "**Goal:**\n- \n**Changes:**\n- \n**Result:**\n- \n**Next:**\n- ";

  /** Formatting bar: 0 bold, 1 italic, 2 underline, 3 colour (with a menu), 4 list (with a menu), 5 template. */
  static javax.swing.Icon fmtIcon(final int kind) {
    return new javax.swing.Icon() {
      @Override public int getIconWidth() { return kind >= 3 ? 26 : 18; }
      @Override public int getIconHeight() { return 18; }
      @Override public void paintIcon(java.awt.Component c, java.awt.Graphics g0, int x, int y) {
        java.awt.Graphics2D g = (java.awt.Graphics2D) g0.create();
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.translate(x, y);
        boolean on = c == null || c.isEnabled();
        java.awt.Color ink = on ? new java.awt.Color(0x33, 0x3B, 0x45) : new java.awt.Color(0xC4, 0xCA, 0xD2);
        g.setColor(ink);
        g.setStroke(new java.awt.BasicStroke(1.4f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
        String letter = kind == 0 ? "B" : kind == 1 ? "I" : kind == 2 ? "U" : kind == 3 ? "A" : null;
        if (letter != null) {
          Font f = kind == 1 ? new Font(Font.SERIF, Font.ITALIC | Font.BOLD, 15) : new Font(Font.SANS_SERIF, Font.BOLD, kind == 0 ? 14 : 13);
          if (kind == 2) f = new Font(Font.SANS_SERIF, Font.PLAIN, 13);
          g.setFont(f);
          java.awt.FontMetrics fm = g.getFontMetrics();
          g.drawString(letter, (18 - fm.stringWidth(letter)) / 2, kind == 3 ? 12 : 14);
          if (kind == 2) g.drawLine(5, 16, 13, 16);
          if (kind == 3) {   // the colour bar under the A
            g.setColor(on ? new java.awt.Color(0xC0, 0x39, 0x2B) : ink);
            g.fillRect(3, 14, 12, 3);
          }
        } else if (kind == 4) {   // three items: dot and line
          for (int k = 0; k < 3; k++) {
            g.fillOval(2, 3 + 5 * k, 3, 3);
            g.drawLine(8, 4 + 5 * k, 16, 4 + 5 * k);
          }
        } else {   // template: a page with lines
          g.drawPolyline(new int[] {11, 4, 4, 14, 14, 11, 11, 14}, new int[] {2, 2, 16, 16, 5, 2, 5, 5}, 8);
          g.drawLine(7, 8, 11, 8);
          g.drawLine(7, 11, 11, 11);
          g.drawLine(7, 14, 9, 14);
        }
        if (kind >= 3) {   // a small arrow: opens a menu
          g.setColor(ink);
          g.fillPolygon(new int[] {20, 25, 22}, new int[] {8, 8, 11}, 3);
        }
        g.dispose();
      }
    };
  }

  /** A run of a study: the "open" dot (or nothing), then its pin star. */
  static javax.swing.Icon runIcon(boolean open, boolean pinned) { return starIcon(trackerIcon(open ? 2 : 3), pinned, true); }

  /** An icon (arrow or dot) followed by the pin star, or the icon alone (withStar false). */
  static javax.swing.Icon starIcon(final javax.swing.Icon first, final boolean pinned, final boolean withStar) {
    return new javax.swing.Icon() {
      @Override public int getIconWidth() { return withStar ? 28 : 12; }
      @Override public int getIconHeight() { return 12; }
      @Override public void paintIcon(java.awt.Component c, java.awt.Graphics g0, int x, int y) {
        first.paintIcon(c, g0, x, y);
        if (!withStar) return;
        java.awt.Graphics2D g = (java.awt.Graphics2D) g0.create();
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        java.awt.geom.Path2D.Double star = new java.awt.geom.Path2D.Double();
        for (int k = 0; k < 10; k++) {
          double r = k % 2 == 0 ? 6.0 : 2.6, a = -Math.PI / 2 + k * Math.PI / 5;
          double px = x + 22 + r * Math.cos(a), py = y + 6.5 + r * Math.sin(a);
          if (k == 0) star.moveTo(px, py); else star.lineTo(px, py);
        }
        star.closePath();
        if (pinned) {
          g.setColor(new java.awt.Color(0xE0, 0x9A, 0x10));
          g.fill(star);
        } else {
          g.setColor(new java.awt.Color(0xC4, 0xCA, 0xD2));
          g.draw(star);
        }
        g.dispose();
      }
    };
  }

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
        "Done", "Dropped", "No status", "Diverged"});
    /** "Hidden (n)": shows the hidden runs or versions, faded (only there when something is hidden). */
    final javax.swing.JToggleButton showHidden = new javax.swing.JToggleButton("Hidden");
    final JButton undoBtn = Ui.link("Undo");
    Runnable undoAction;
    final JLabel summary = new JLabel(), message = new JLabel(" "), folderName = new JLabel(), headline = new JLabel(" "),
        areaLabel = new JLabel("Comment");
    /** The comment box: shows the formatting itself, no marks (the file keeps the marks: **bold** ...). */
    final javax.swing.JTextPane live = new javax.swing.JTextPane() {
      private static final long serialVersionUID = 1L;
      @Override protected void paintComponent(java.awt.Graphics g) {
        super.paintComponent(g);
        if (getDocument().getLength() == 0 && !isFocusOwner() && !hint.isEmpty()) {   // what the empty box is for
          g.setColor(new java.awt.Color(0xB8, 0xBE, 0xC6));
          g.setFont(getFont().deriveFont(Font.ITALIC));
          g.drawString(hint, getInsets().left, getInsets().top + g.getFontMetrics().getAscent());
        }
      }
    };
    String hint = "";
    static final String COLOUR = "simkitColour";   // the colour name of a piece of text in the box
    String liveLoaded = "", liveNorm = "";   // the text put in the box, and the box read back just after
    final javax.swing.undo.UndoManager undo = new javax.swing.undo.UndoManager();
    javax.swing.JSplitPane split;
    JPanel details;
    JScrollPane tableScroll;
    int hoverRow = -1;
    final JPanel chips = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
    final java.awt.Color hoverBg = new java.awt.Color(0xF2, 0xF6, 0xFC);
    boolean editing = false;
    final List<JButton> fmtButtons = new ArrayList<>();   // B, I, U, colour, lists: enabled when a single row is selected
    JPanel fmtBar;
    final JButton folderBtn = new JButton(actionIcon(1)), hideBtn = new JButton(actionIcon(4)),
        simBtn = new JButton("Open in STAR-CCM+..."), copyBtn = new JButton(actionIcon(2));
    boolean opening = false;
    final Set<String> pins = new LinkedHashSet<>();   // pinned case folders (this user only)

    String pinKey(String caseId) { return dir.resolve(caseId).toAbsolutePath().normalize().toString(); }

    boolean pinned(String caseId) { return pins.contains(pinKey(caseId)); }

    void togglePin(String caseId) {
      boolean on = !pins.remove(pinKey(caseId));
      if (on) pins.add(pinKey(caseId));
      Env.setPref("trackerPins", String.join("\n", pins));
      rebuild();
      say(shown(caseId) + (on ? " starred" : " unstarred"), grey);
    }
    String areaKey = null;   // what the details text area edits: version ID (comment) or "case:<id>" (description)

    /** An ID as shown: R007 for a run, the project name for the project row. */
    String shown(String id) { return id.matches(".*\\.v[0-9]+$") ? runId(runOf(id)) : id; }

    TrackerWindow(Path dir0, String openId) {
      this.dir = dir0;
      this.openId = openId;
      if (openId != null) expanded.add(caseOf(openId));
      for (String p : Env.pref("trackerPins").split("\n")) if (!p.trim().isEmpty()) pins.add(p.trim());
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
      table.setShowHorizontalLines(false);   // flat: stripes and case headers separate the rows
      table.setIntercellSpacing(new java.awt.Dimension(0, 0));
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
      table.getActionMap().put("copy", new javax.swing.AbstractAction() {   // Ctrl+C: ID and path of the selected rows
        private static final long serialVersionUID = 1L;
        @Override public void actionPerformed(java.awt.event.ActionEvent e) { copyLinks(); }
      });
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
          else if (!it.isCase() && col == T_ID && onRunStar(row, e)) togglePin(it.r[COL_ID]);
          else if (!it.isCase() && col == T_ID && e.getClickCount() == 2) openSelected();
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
          if (row != hoverRow) { hoverRow = row; table.repaint(); }   // light highlight under the mouse
        }
      });
      table.addMouseListener(new java.awt.event.MouseAdapter() {
        @Override public void mouseExited(java.awt.event.MouseEvent e) { hoverRow = -1; table.repaint(); }
      });
      // keyboard: arrows move, Enter opens, F2 writes, Space pins a case, Left / Right fold / unfold, Ctrl+F filters
      keyOn(table, "ENTER", this::openSelected);
      keyOn(table, "F2", this::startEdit);
      keyOn(table, "SPACE", () -> {
        Item it = current();
        if (it == null) return;
        if (!it.isCase()) togglePin(it.r[COL_ID]);   // the runs are starred, not the project
      });
      keyOn(table, "LEFT", () -> { Item it = current(); if (it != null && expanded.contains(it.caseId)) toggleKeep(it.caseId); });
      keyOn(table, "RIGHT", () -> { Item it = current(); if (it != null && !expanded.contains(it.caseId)) toggleKeep(it.caseId); });

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
      showHidden.setToolTipText("Show the hidden runs, faded (the files are not changed)");
      showHidden.setIcon(actionIcon(5));
      showHidden.setFocusable(false);
      showHidden.setMargin(new java.awt.Insets(2, 8, 2, 8));
      showHidden.addActionListener(e -> rebuild());
      undoBtn.setVisible(false);
      undoBtn.addActionListener(e -> {
        Runnable u = undoAction;
        undoAction = null;
        undoBtn.setVisible(false);
        if (u != null) u.run();
      });

      live.setFont(table.getFont());
      live.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
      live.getDocument().addUndoableEditListener(undo);
      live.addFocusListener(new java.awt.event.FocusAdapter() {
        @Override public void focusGained(java.awt.event.FocusEvent e) { if (areaKey != null) editing = true; live.repaint(); }
        @Override public void focusLost(java.awt.event.FocusEvent e) {
          flushArea();
          if (!e.isTemporary()) editing = false;
        }
      });
      bind(live, "ctrl B", () -> style(javax.swing.text.StyleConstants.Bold));
      bind(live, "ctrl I", () -> style(javax.swing.text.StyleConstants.Italic));
      bind(live, "ctrl U", () -> style(javax.swing.text.StyleConstants.Underline));
      bind(live, "ESCAPE", this::endEdit);
      bind(live, "ENTER", this::newLine);
      bind(live, "SPACE", this::space);
      bind(live, "ctrl Z", () -> { if (undo.canUndo()) undo.undo(); });
      bind(live, "ctrl Y", () -> { if (undo.canRedo()) undo.redo(); });
      // the formatting bar is always shown; used on the formatted text, it first opens the box for writing
      JPanel bar = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 2, 0));
      final javax.swing.JPopupMenu colours = new javax.swing.JPopupMenu();
      for (Map.Entry<String, String> c : Md.COLORS.entrySet()) {
        javax.swing.JMenuItem mi = new javax.swing.JMenuItem("<html><font color='" + c.getValue() + "'>&#9632;&nbsp;"
            + c.getKey().substring(0, 1).toUpperCase(Locale.ROOT) + c.getKey().substring(1) + "</font></html>");
        mi.addActionListener(e -> fmt(() -> colour(c.getKey())));
        colours.add(mi);
      }
      javax.swing.JMenuItem noColour = new javax.swing.JMenuItem("No colour");
      noColour.addActionListener(e -> fmt(() -> colour(null)));
      colours.addSeparator();
      colours.add(noColour);
      final javax.swing.JPopupMenu lists = new javax.swing.JPopupMenu();
      Object[][] listItems = {{"\u2022  Bullets", Boolean.FALSE}, {"1.  Numbered", Boolean.TRUE}, {"No list", null}};
      for (Object[] li : listItems) {
        javax.swing.JMenuItem mi = new javax.swing.JMenuItem((String) li[0]);
        final Boolean numbered = (Boolean) li[1];
        mi.addActionListener(e -> fmt(() -> list(numbered)));
        if (numbered == null) lists.addSeparator();
        lists.add(mi);
      }
      final javax.swing.JPopupMenu templates = new javax.swing.JPopupMenu();
      javax.swing.JMenuItem tIns = new javax.swing.JMenuItem("Insert template");
      tIns.addActionListener(e -> fmt(this::template));
      javax.swing.JMenuItem tMine = new javax.swing.JMenuItem("Use this comment as my template");
      tMine.setToolTipText("Write the template in the box (headings, bullets, colours), then choose this. For you only.");
      tMine.addActionListener(e -> fmt(this::saveTemplate));
      javax.swing.JMenuItem tBack = new javax.swing.JMenuItem("Back to the standard template");
      tBack.addActionListener(e -> {
        Env.setPref("trackerTemplate", "");
        say("Template: the standard one again", grey);
      });
      templates.add(tIns);
      templates.addSeparator();
      templates.add(tMine);
      templates.add(tBack);
      // icon buttons in groups, as in a word processor: B I U A | list | template
      Object[][] tools = {{0, "Bold (Ctrl+B)", (Runnable) () -> fmt(() -> style(javax.swing.text.StyleConstants.Bold))},
          {1, "Italic (Ctrl+I)", (Runnable) () -> fmt(() -> style(javax.swing.text.StyleConstants.Italic))},
          {2, "Underline (Ctrl+U)", (Runnable) () -> fmt(() -> style(javax.swing.text.StyleConstants.Underline))},
          {3, "Text colour", colours},
          {-1, null, null},
          {4, "List: bullets or numbered (or type - and a space at the start of a line)", lists},
          {-1, null, null},
          {5, "Template: insert it, or make this comment your own template", templates}};
      for (Object[] t : tools) {
        if ((Integer) t[0] < 0) {   // a thin line between groups
          javax.swing.JSeparator sep = new javax.swing.JSeparator(javax.swing.SwingConstants.VERTICAL);
          sep.setPreferredSize(new java.awt.Dimension(9, 18));
          bar.add(sep);
          continue;
        }
        final JButton b = new JButton(fmtIcon((Integer) t[0]));
        b.setToolTipText((String) t[1]);
        b.setMargin(new java.awt.Insets(2, 3, 2, 3));
        flat(b);
        if (t[2] instanceof javax.swing.JPopupMenu) {
          final javax.swing.JPopupMenu menu = (javax.swing.JPopupMenu) t[2];
          b.addActionListener(e -> menu.show(b, 0, b.getHeight()));
        } else {
          final Runnable run = (Runnable) t[2];
          b.addActionListener(e -> run.run());
        }
        fmtButtons.add(b);
        bar.add(b);
      }
      areaLabel.setToolTipText("Saved when you leave the box");
      fmtBar = bar;
      folderBtn.addActionListener(e -> openFolder());
      simBtn.addActionListener(e -> openSelected());
      copyBtn.addActionListener(e -> copyLinks());
      folderBtn.setToolTipText("Open the run folder");
      copyBtn.setToolTipText("Copy the ID and path (Ctrl+C), to paste in a mail or a document");
      simBtn.setToolTipText("STAR-CCM+'s Load Simulation dialog with the latest file of this run filled in (processes, parallel, "
          + "power...). Already open: its tab comes to the front. (Enter)");
      for (JButton b : new JButton[] {folderBtn, copyBtn, hideBtn}) flat(b);
      simBtn.setFont(simBtn.getFont().deriveFont(Font.BOLD));
      simBtn.setForeground(java.awt.Color.WHITE);
      simBtn.setBackground(new java.awt.Color(0x1F, 0x5F, 0xAF));
      simBtn.setOpaque(true);
      simBtn.setBorderPainted(false);
      simBtn.setFocusPainted(false);
      simBtn.setMargin(new java.awt.Insets(4, 12, 4, 12));
      hideBtn.addActionListener(e -> hideOrShow());
      hideBtn.setText("Hide");
      headline.setFont(headline.getFont().deriveFont(Font.BOLD));
      areaLabel.setForeground(grey);

      JButton change = new JButton("Change...");
      change.setToolTipText("Show the runs of another project");
      change.addActionListener(e -> changeFolder());
      JButton export = new JButton("Export CSV");
      export.setToolTipText("Write " + TRACKER_CSV + " in the project folder (Google Sheets: File > Import > Upload)");
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
      final JLabel casesIn = new JLabel("Project");
      casesIn.setForeground(grey);
      folderName.setFont(folderName.getFont().deriveFont(Font.BOLD));
      // List | Tree: the table, or the runs drawn as a family tree, in the same place
      final javax.swing.JToggleButton listB = new javax.swing.JToggleButton("List"), treeB = new javax.swing.JToggleButton("Tree", treeIcon());
      javax.swing.ButtonGroup lt = new javax.swing.ButtonGroup();
      lt.add(listB);
      lt.add(treeB);
      for (javax.swing.JToggleButton b : new javax.swing.JToggleButton[] {listB, treeB}) {
        b.setFocusable(false);
        b.setMargin(new java.awt.Insets(2, 8, 2, 8));
      }
      listB.setToolTipText("Runs as a list");
      treeB.setToolTipText("Runs as a family tree (click: select, double-click: open, drag: move the view)");
      listB.addActionListener(e -> showView("list"));
      treeB.addActionListener(e -> showView("tree"));
      viewButtons = new javax.swing.JToggleButton[] {listB, treeB};
      topLeft.add(listB);
      topLeft.add(treeB);
      topLeft.add(javax.swing.Box.createHorizontalStrut(10));
      topLeft.add(casesIn);
      topLeft.add(folderName);
      topLeft.add(change);
      topLeft.add(javax.swing.Box.createHorizontalStrut(16));
      topLeft.add(filter);
      topLeft.add(statusFilter);
      topLeft.add(showHidden);
      summary.setForeground(grey);
      JPanel topRight = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 10, 0));
      topRight.add(summary);

      JPanel top = new JPanel(new BorderLayout());
      top.add(topLeft, BorderLayout.WEST);
      top.add(topRight, BorderLayout.EAST);

      details = new JPanel(new BorderLayout(10, 6));
      details.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 2));
      JPanel acts = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 2, 0));   // the main action, then small icons
      acts.add(simBtn);
      acts.add(javax.swing.Box.createHorizontalStrut(6));
      for (JButton b : new JButton[] {folderBtn, copyBtn, hideBtn}) acts.add(b);
      JPanel head = new JPanel();   // headline (2 lines), actions, status labels: each its own height
      head.setLayout(new javax.swing.BoxLayout(head, javax.swing.BoxLayout.Y_AXIS));
      for (javax.swing.JComponent c : new javax.swing.JComponent[] {headline, acts, chips}) {
        c.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        if (c != headline) head.add(javax.swing.Box.createVerticalStrut(6));
        head.add(c);
      }
      details.add(head, BorderLayout.NORTH);
      JPanel areaBox = new JPanel(new BorderLayout(0, 2));
      JPanel areaHead = new JPanel(new BorderLayout(0, 2));
      areaHead.add(areaLabel, BorderLayout.NORTH);
      areaHead.add(fmtBar, BorderLayout.SOUTH);
      areaBox.add(areaHead, BorderLayout.NORTH);
      areaBox.add(new JScrollPane(live), BorderLayout.CENTER);
      details.add(areaBox, BorderLayout.CENTER);
      details.setMinimumSize(new java.awt.Dimension(360, 160));

      JPanel bottom = new JPanel(new BorderLayout());
      JPanel btns = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 0));
      btns.add(export);
      btns.add(close);
      JPanel msgRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 0));
      msgRow.add(message);
      msgRow.add(undoBtn);
      bottom.add(msgRow, BorderLayout.CENTER);
      bottom.add(btns, BorderLayout.EAST);

      JPanel root = new JPanel(new BorderLayout(0, 8));
      root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
      root.add(top, BorderLayout.NORTH);
      tableScroll = new JScrollPane(table);
      tableScroll.getViewport().setBackground(java.awt.Color.WHITE);
      tableScroll.setMinimumSize(new java.awt.Dimension(400, 120));
      runTree = new RunTree();
      runTree.source = () -> {
        List<String[]> l = new ArrayList<>();
        for (String[] r : rows) if (showHidden.isSelected() || !hidden(r)) l.add(r);
        return l;
      };
      runTree.lineage = () -> rows;
      runTree.isHidden = id -> { String[] r = byId(id); return r != null && hidden(r); };
      runTree.shown = this::shown;
      runTree.openId = openId;
      runTree.onClick = (id, n) -> {
        selectQuiet(id);
        if (n == 2) openSelected();
      };
      runTree.onMenu = (id, e) -> {
        selectQuiet(id);
        showMenu(runTree, e.getX(), e.getY(), true);
      };
      JScrollPane treeScroll = new JScrollPane(runTree);
      treeScroll.getViewport().setBackground(java.awt.Color.WHITE);
      treeScroll.getVerticalScrollBar().setUnitIncrement(16);
      leftCards.add(tableScroll, "list");
      leftCards.add(treeScroll, "tree");
      split = new javax.swing.JSplitPane(javax.swing.JSplitPane.HORIZONTAL_SPLIT, leftCards, details);
      split.setBorder(null);
      split.setDividerSize(7);
      split.setContinuousLayout(true);
      layoutDetails();
      root.add(split, BorderLayout.CENTER);
      root.add(bottom, BorderLayout.SOUTH);
      w.setContentPane(root);

      w.addWindowFocusListener(new java.awt.event.WindowAdapter() {
        @Override public void windowGainedFocus(java.awt.event.WindowEvent e) {
          if (!table.isEditing() && !editing) reload();
        }
      });
      // Ctrl+F anywhere in the window: the filter; Esc in the filter: clear it
      w.getRootPane().getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW).put(javax.swing.KeyStroke.getKeyStroke("ctrl F"), "simkitFilter");
      w.getRootPane().getActionMap().put("simkitFilter", new javax.swing.AbstractAction() {
        private static final long serialVersionUID = 1L;
        @Override public void actionPerformed(java.awt.event.ActionEvent e) { filter.requestFocusInWindow(); filter.selectAll(); }
      });
      keyOn(filter, "ESCAPE", () -> { filter.setText(""); table.requestFocusInWindow(); });
      reload();
      if (openId != null) jumpTo(openId);
      showView("tree".equals(Env.pref("trackerView")) ? "tree" : "list");   // the view used last time
      w.pack();
      layoutDetails();
    }

    /** Details on the right of the table (the comment box the full height). */
    void layoutDetails() {
      if (split == null) return;
      split.setResizeWeight(0.6);
      tableScroll.setPreferredSize(new java.awt.Dimension(860, 560));
      details.setPreferredSize(new java.awt.Dimension(500, 560));
      split.resetToPreferredSizes();
      split.revalidate();
    }

    /** The focused row of the table (null if none). */
    Item current() {
      int r = table.getSelectionModel().getLeadSelectionIndex();
      return r >= 0 && r < view.size() ? view.get(r) : null;
    }

    /** Fold or unfold a case from the keyboard, keeping the case row selected. */
    void toggleKeep(String caseId) {
      toggle(caseId);
      for (int i = 0; i < view.size(); i++) if (view.get(i).isCase() && view.get(i).caseId.equals(caseId)) table.setRowSelectionInterval(i, i);
    }

    void keyOn(javax.swing.JComponent c, String keyStroke, Runnable r) {
      c.getInputMap(javax.swing.JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(javax.swing.KeyStroke.getKeyStroke(keyStroke), "simkit " + keyStroke);
      c.getInputMap(javax.swing.JComponent.WHEN_FOCUSED).put(javax.swing.KeyStroke.getKeyStroke(keyStroke), "simkit " + keyStroke);
      c.getActionMap().put("simkit " + keyStroke, new javax.swing.AbstractAction() {
        private static final long serialVersionUID = 1L;
        @Override public void actionPerformed(java.awt.event.ActionEvent e) { r.run(); }
      });
    }

    /** A flat button: no border until the mouse is over it. */
    void flat(final JButton b) {
      b.setFocusable(false);
      b.setContentAreaFilled(false);
      b.setBorderPainted(false);
      b.setFocusPainted(false);
      b.addMouseListener(new java.awt.event.MouseAdapter() {
        @Override public void mouseEntered(java.awt.event.MouseEvent e) { if (b.isEnabled()) { b.setContentAreaFilled(true); b.setBorderPainted(true); } }
        @Override public void mouseExited(java.awt.event.MouseEvent e) { b.setContentAreaFilled(false); b.setBorderPainted(false); }
      });
    }

    // ----- data -----

    void reload() {
      rows = trackerRows(dir);
      byCase.clear();
      ids.clear();
      for (String[] r : rows) byCase.computeIfAbsent(caseOf(r[COL_ID]), k -> new ArrayList<>()).add(r);
      for (String cid : byCase.keySet()) ids.put(cid, identityOf(dir, cid));
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
        case 5: return s.equals("diverged");
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
      int nVers = 0, nRunning = 0, nHidden = 0;
      for (Map.Entry<String, List<String[]>> e : byCase.entrySet()) {   // one entry: the project
        String cid = e.getKey();
        List<String[]> vis = new ArrayList<>();
        for (String[] r : e.getValue()) {
          boolean h = hidden(r);
          if (h) nHidden++;
          else { nVers++; if (r[COL_STATUS].equals("running") || r[COL_STATUS].equals("meshing")) nRunning++; }
          if (h && !showHidden.isSelected()) continue;
          if (!statusOk(r[COL_STATUS])) continue;
          String all = (String.join(" ", r) + " " + cid + " " + shown(r[COL_ID])).toLowerCase(Locale.ROOT);
          if (!t.isEmpty() && !all.contains(t)) continue;
          vis.add(r);
        }
        if (vis.isEmpty()) continue;
        {   // starred runs first, each part in run order
          List<String[]> top = new ArrayList<>(), rest = new ArrayList<>();
          for (String[] r : vis) (pinned(r[COL_ID]) ? top : rest).add(r);
          vis = top;
          vis.addAll(rest);
        }
        view.add(new Item(cid, null));
        if (expanded.contains(cid) || filtering()) for (String[] r : vis) view.add(new Item(cid, r));
      }
      model.fireTableDataChanged();
      rowHeights();
      for (int i = 0; i < view.size(); i++) if (sel.contains(view.get(i).key())) table.addRowSelectionInterval(i, i);
      if (runTree != null && "tree".equals(leftView)) runTree.relayout();
      summary.setText(nVers + (nVers == 1 ? " run" : " runs")
          + (nRunning > 0 ? "  ·  " + nRunning + " meshing or running" : ""));
      if (nHidden == 0 && showHidden.isSelected()) showHidden.setSelected(false);
      showHidden.setText("Hidden (" + nHidden + ")");
      showHidden.setVisible(nHidden > 0);
      showDetails();
    }

    void toggle(String caseId) {
      if (filtering()) { say("Clear the filter to fold or unfold", grey); return; }
      if (!expanded.remove(caseId)) expanded.add(caseId);
      rebuild();
    }

    // ----- family tree: each run (or version) under the one it is based on -----

    /** A small icon: three linked boxes. */
    javax.swing.Icon treeIcon() {
      return new javax.swing.Icon() {
        @Override public int getIconWidth() { return 18; }
        @Override public int getIconHeight() { return 16; }
        @Override public void paintIcon(java.awt.Component c, java.awt.Graphics g0, int x, int y) {
          java.awt.Graphics2D g = (java.awt.Graphics2D) g0.create();
          g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
          g.translate(x, y);
          g.setColor(new java.awt.Color(0x4A, 0x55, 0x63));
          g.setStroke(new java.awt.BasicStroke(1.3f));
          g.drawRoundRect(1, 6, 5, 4, 2, 2);
          g.drawRoundRect(12, 1, 5, 4, 2, 2);
          g.drawRoundRect(12, 11, 5, 4, 2, 2);
          g.draw(new java.awt.geom.CubicCurve2D.Double(6, 8, 9, 8, 9, 3, 12, 3));
          g.draw(new java.awt.geom.CubicCurve2D.Double(6, 8, 9, 8, 9, 13, 12, 13));
          g.dispose();
        }
      };
    }

    JDialog treeWin;
    RunTree runTree;
    final java.awt.CardLayout leftLayout = new java.awt.CardLayout();
    final JPanel leftCards = new JPanel(leftLayout);
    String leftView = "list";
    javax.swing.JToggleButton[] viewButtons;

    /** List or Tree on the left (remembered for this user). */
    void showView(String v) {
      leftView = v;
      if (runTree != null && v.equals("tree")) {
        runTree.relayout();
        Item it = current();
        runTree.picked = it == null || it.isCase() ? null : it.r[COL_ID];
        runTree.repaint();
      }
      leftLayout.show(leftCards, v);
      if (viewButtons != null) viewButtons[v.equals("tree") ? 1 : 0].setSelected(true);
      Env.setPref("trackerView", v);
    }

    /** Selects a row (the details on the right follow) without changing the filter when it is visible. */
    void selectQuiet(String id) {
      for (int i = 0; i < view.size(); i++) {
        if (id.equals(view.get(i).key())) {
          table.setRowSelectionInterval(i, i);
          return;
        }
      }
      jumpTo(id);
    }

    void showTree() {
      if (treeWin != null && treeWin.isDisplayable()) {
        treeWin.toFront();
        return;
      }
      final RunTree ft = new RunTree();
      ft.source = () -> {
        List<String[]> l = new ArrayList<>();
        for (String[] r : rows) if (showHidden.isSelected() || !hidden(r)) l.add(r);
        return l;
      };
      ft.lineage = () -> rows;
      ft.isHidden = id -> { String[] r = byId(id); return r != null && hidden(r); };
      ft.shown = this::shown;
      ft.openId = openId;
      ft.onClick = (id, n) -> {
        jumpTo(id);
        if (n == 2) openSelected();
      };
      treeWin = new JDialog(w, "Family tree  -  " + dir.getFileName(), java.awt.Dialog.ModalityType.MODELESS);
      treeWin.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
      JScrollPane sp = new JScrollPane(ft);
      sp.getViewport().setBackground(java.awt.Color.WHITE);
      sp.setBorder(BorderFactory.createEmptyBorder());
      sp.getVerticalScrollBar().setUnitIncrement(16);
      sp.getHorizontalScrollBar().setUnitIncrement(16);
      JLabel hint = new JLabel("Click: select  \u00B7  double-click: open in STAR-CCM+");
      hint.setForeground(grey);
      hint.setBorder(BorderFactory.createEmptyBorder(6, 12, 8, 12));
      JPanel root = new JPanel(new BorderLayout());
      root.setBackground(java.awt.Color.WHITE);
      root.add(sp, BorderLayout.CENTER);
      root.add(hint, BorderLayout.SOUTH);
      treeWin.setContentPane(root);
      treeWin.addWindowFocusListener(new java.awt.event.WindowAdapter() {
        @Override public void windowGainedFocus(java.awt.event.WindowEvent e) { ft.relayout(); }   // after edits in the tracker
      });
      ft.relayout();
      java.awt.Dimension d = ft.getPreferredSize();
      sp.setPreferredSize(new java.awt.Dimension(Math.min(1250, d.width + 20), Math.min(720, d.height + 20)));
      treeWin.pack();
      treeWin.setLocationRelativeTo(w);
      Env.track(treeWin);
      treeWin.setVisible(true);
    }

    void jumpTo(String id) {
      String[] r = byId(id);
      if (r == null) return;
      if (hidden(r)) showHidden.setSelected(true);
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
        if (col == COL_COMMENT && r[COL_ID].equals(areaKey) && !value.equals(liveMd())) setLive(value);   // else the box would put the old text back
        afterSave("Saved: " + key + " of " + shown(r[COL_ID]));
      } catch (Throwable t) {
        say("NOT saved (" + key + " of " + shown(r[COL_ID]) + "): " + reason(t), red);
      }
    }

    void saveDescription(String caseId, String value) {
      if (value.equals(description(caseId))) return;
      try {
        setIdentityKey(dir, caseId, "description", value);
        for (String[] r : byCase.get(caseId)) r[COL_DESC] = value;
        if (("case:" + caseId).equals(areaKey) && !value.equals(liveMd())) setLive(value);
        afterSave("Saved: description of " + caseId);
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
      undoAction = null;
      undoBtn.setVisible(false);
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
      List<Item> hs = hideable(sel);
      boolean unhide = unhideMode(hs);
      hideBtn.setIcon(actionIcon(unhide ? 5 : 4));
      hideBtn.setText(unhide ? "Unhide" : "Hide");
      hideBtn.setToolTipText(hs.isEmpty() ? "Select runs to hide"
          : unhide ? "Show again in the tracker" : "Hide from the tracker (the files are not changed; Hidden shows them again)");
      hideBtn.setEnabled(!hs.isEmpty());
      folderBtn.setEnabled(!sel.isEmpty());
      copyBtn.setEnabled(!sel.isEmpty());
      simBtn.setEnabled(sel.size() == 1 && !opening);
      showChips(sel);
      String key = null, text = "";
      if (sel.isEmpty()) {
        headline.setText("Select a run to see its details");
        areaLabel.setText("Comments");
      } else if (sel.size() > 1) {
        headline.setText("<html>" + sel.size() + " rows selected&nbsp;&nbsp;<font color='#6B7580'>right-click: set their status, "
            + "copy their references</font></html>");
        areaLabel.setText("Comments");
      } else if (sel.get(0).isCase()) {
        Item it = sel.get(0);
        Map<String, String> id = ids.getOrDefault(it.caseId, new HashMap<>());
        List<String> bits = new ArrayList<>();
        bits.add(byCase.get(it.caseId).size() + " run(s)");
        if (!id.getOrDefault("parent_case", "").isEmpty()) bits.add("from case " + id.get("parent_case"));
        if (!id.getOrDefault("mesh_level", "").isEmpty()) bits.add("mesh study " + id.getOrDefault("mesh_study", "")
            + ": " + id.get("mesh_level") + (id.getOrDefault("mesh_cells", "").isEmpty() ? "" : ", " + id.get("mesh_cells") + " cells"));
        headline.setText("<html>" + html(it.caseId) + "<br><font color='#6B7580'>" + html(String.join("  ·  ", bits))
            + "</font></html>");
        areaLabel.setText("Description");
        key = it.key();
        text = description(it.caseId);
      } else {
        String[] r = sel.get(0).r;
        Map<String, String> id = ids.getOrDefault(sel.get(0).caseId, new HashMap<>());
        List<String> bits = new ArrayList<>();
        bits.add("file " + r[COL_FILE]);
        bits.add(r[COL_BASED].isEmpty() ? "first run" : "based on " + shown(r[COL_BASED]));
        bits.add("saved " + r[COL_SAVED]);
        if (!id.getOrDefault("mesh_level", "").isEmpty()) bits.add("mesh: " + id.get("mesh_level")
            + (id.getOrDefault("mesh_cells", "").isEmpty() ? "" : ", " + id.get("mesh_cells") + " cells"));
        if (r[COL_ID].equals(openId)) bits.add("open in STAR-CCM+");
        if (hidden(r)) bits.add("hidden");
        headline.setText("<html>" + html(shown(r[COL_ID])) + (!nameOf(r[COL_FILE]).isEmpty() ? "&nbsp;&nbsp;" + html(nameOf(r[COL_FILE])) : "")
            + "<br><font color='#6B7580'>" + html(String.join("  ·  ", bits))
            + "</font></html>");
        areaLabel.setText("Comments");
        key = r[COL_ID];
        text = r[COL_COMMENT];
      }
      if (key == null || !key.equals(areaKey) || !editing) {   // never overwrite what is being typed
        if (editing && (key == null || !key.equals(areaKey))) editing = false;
        areaKey = key;
        if (!text.equals(liveMd())) setLive(text);
        live.setEditable(key != null);
        live.setBackground(key != null ? java.awt.Color.WHITE : new java.awt.Color(0xF4, 0xF5, 0xF7));
        hint = key == null ? "" : key.startsWith("case:") ? "Description of the project" : "Comment";
        live.repaint();
      }
      for (JButton b : fmtButtons) b.setEnabled(key != null);
    }

    /** Status labels under the headline: click one to give it to the selection (a case row: all its versions). */
    void showChips(List<Item> sel) {
      chips.removeAll();
      if (!sel.isEmpty()) {
        String cur = sel.size() == 1 && !sel.get(0).isCase() ? sel.get(0).r[COL_STATUS] : null;
        JLabel lab = new JLabel(sel.size() == 1 && !sel.get(0).isCase() ? "Status" : "Status of all");
        lab.setForeground(grey);
        chips.add(lab);
        for (final String st : STATUSES) {
          if (st.isEmpty()) continue;
          final boolean on = st.equals(cur);
          final java.awt.Color[] c = statusColors(st);
          JLabel chip = new JLabel(st) {
            private static final long serialVersionUID = 1L;
            @Override protected void paintComponent(java.awt.Graphics g0) {
              java.awt.Graphics2D g = (java.awt.Graphics2D) g0.create();
              g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
              if (on) { g.setColor(c[0]); g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, getHeight(), getHeight()); }
              else { g.setColor(new java.awt.Color(0xD5, 0xD9, 0xDE)); g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, getHeight(), getHeight()); }
              g.dispose();
              super.paintComponent(g0);
            }
          };
          chip.setForeground(on ? c[1] : grey);
          chip.setBorder(BorderFactory.createEmptyBorder(2, 9, 2, 9));
          chip.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
          chip.setToolTipText(on ? "Current status: click to clear it" : "Set the status to " + st);
          chip.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) { setStatusAll(on ? "" : st); }
          });
          chips.add(chip);
        }
      }
      chips.revalidate();
      chips.repaint();
    }

    String viewerCss() {
      Font f = table.getFont();
      return "body{font-family:'" + f.getFamily() + "';font-size:" + f.getSize() + "pt;margin:2px}p{margin-top:0;margin-bottom:5px}"
          + "ul,ol{margin-top:0;margin-bottom:5px;margin-left:20px;margin-left-ltr:22px}li{margin-bottom:1px}"
          + ".empty{color:#B8BEC6;font-style:italic}";   // margin-left-ltr: Swing's own name for the list indent
    }

    // ----- writing in the box: formatted as in a word processor; leaving the box (or Esc) saves -----

    void startEdit() {
      if (areaKey == null || selected().size() != 1) return;
      editing = true;
      if (!live.isFocusOwner()) {
        live.requestFocusInWindow();
        live.setCaretPosition(live.getDocument().getLength());
      }
    }

    void endEdit() {
      if (!editing) return;
      flushArea();
      editing = false;
      table.requestFocusInWindow();
    }

    /** Formatting of one piece of text in the box (all keys set, so nothing is taken from the text around it). */
    javax.swing.text.SimpleAttributeSet attrs(boolean b, boolean i, boolean u, String colour) {
      javax.swing.text.SimpleAttributeSet a = new javax.swing.text.SimpleAttributeSet();
      javax.swing.text.StyleConstants.setBold(a, b);
      javax.swing.text.StyleConstants.setItalic(a, i);
      javax.swing.text.StyleConstants.setUnderline(a, u);
      javax.swing.text.StyleConstants.setForeground(a, colour == null ? java.awt.Color.BLACK : java.awt.Color.decode(Md.COLORS.get(colour)));
      a.addAttribute(COLOUR, colour == null ? "" : colour);
      return a;
    }

    /** Puts text with marks (**bold** ...) in the box at a place, formatted, without the marks. Returns its length. */
    int insertMd(int at, String md) throws javax.swing.text.BadLocationException {
      javax.swing.text.StyledDocument d = live.getStyledDocument();
      javax.swing.text.SimpleAttributeSet none = attrs(false, false, false, null);
      int p = at;
      String[] ls = md.split("\n", -1);
      for (int j = 0; j < ls.length; j++) {
        String l = ls[j];
        if (j > 0) { d.insertString(p, "\n", none); p++; }
        Matcher b = Md.BULLET.matcher(l), n = Md.NUMBER.matcher(l);
        String body = b.matches() ? b.group(1) : n.matches() ? n.group(2) : l;
        String pre = b.matches() ? l.substring(0, l.length() - l.replaceAll("^\\s+", "").length()) + "\u2022 "
            : l.substring(0, l.length() - body.length());
        d.insertString(p, pre, none);
        p += pre.length();
        for (Md.Run r : Md.runs(body)) {
          d.insertString(p, r.text, attrs(r.b, r.i, r.u, r.color));
          p += r.text.length();
        }
      }
      return p - at;
    }

    /** Shows a comment (text with marks) in the box. */
    void setLive(String md) {
      try {
        live.getDocument().remove(0, live.getDocument().getLength());
        insertMd(0, md);
      } catch (javax.swing.text.BadLocationException e) {
        live.setText(md);
      }
      liveLoaded = md;
      liveNorm = readLive();
      undo.discardAllEdits();
      live.setCaretPosition(0);
    }

    /** The box as text with marks: "- " for a bullet, **bold**, *italic*, __underline__, [red]...[/]. */
    String readLive() {
      javax.swing.text.StyledDocument d = live.getStyledDocument();
      String t = txt(live);
      List<String> out = new ArrayList<>();
      int at = 0;
      for (String l : t.split("\n", -1)) {
        Matcher b = Pattern.compile("^(\\s*)[\u2022*-]\\s+(.*)$").matcher(l), n = Md.NUMBER.matcher(l);
        String body = b.matches() ? b.group(2) : n.matches() ? n.group(2) : l;
        String pre = b.matches() ? b.group(1) + "- " : l.substring(0, l.length() - body.length());
        List<Md.Run> rs = new ArrayList<>();
        for (int k = at + l.length() - body.length(); k < at + l.length(); k++) {
          javax.swing.text.AttributeSet a = d.getCharacterElement(k).getAttributes();
          Object c = a.getAttribute(COLOUR);
          rs.add(new Md.Run(String.valueOf(t.charAt(k)), javax.swing.text.StyleConstants.isBold(a),
              javax.swing.text.StyleConstants.isItalic(a), javax.swing.text.StyleConstants.isUnderline(a),
              c instanceof String && Md.COLORS.containsKey(c) ? (String) c : null));
        }
        out.add(pre + Md.line(rs));
        at += l.length() + 1;
      }
      return String.join("\n", out);
    }

    /** The box as text with marks; unchanged since it was shown: exactly the text it was shown from. */
    String liveMd() {
      String now = readLive();
      return now.equals(liveNorm) ? liveLoaded : now;
    }

    /** Bold, italic or underline: on the selection (off if it is all so already); without one, for what is typed next. */
    void style(Object key) {
      int a = live.getSelectionStart(), b = live.getSelectionEnd();
      javax.swing.text.StyledDocument d = live.getStyledDocument();
      if (a == b) {
        javax.swing.text.MutableAttributeSet in = live.getInputAttributes();
        in.addAttribute(key, !Boolean.TRUE.equals(in.getAttribute(key)));
      } else {
        String t = txt(live);
        boolean all = true;
        for (int k = a; k < b; k++) {
          if (!Character.isWhitespace(t.charAt(k)) && !Boolean.TRUE.equals(d.getCharacterElement(k).getAttributes().getAttribute(key))) all = false;
        }
        javax.swing.text.SimpleAttributeSet s = new javax.swing.text.SimpleAttributeSet();
        s.addAttribute(key, !all);
        d.setCharacterAttributes(a, b - a, s, false);
      }
      live.requestFocusInWindow();
    }

    /** A text colour (null: none) on the selection, or for what is typed next. */
    void colour(String name) {
      javax.swing.text.SimpleAttributeSet s = new javax.swing.text.SimpleAttributeSet();
      javax.swing.text.StyleConstants.setForeground(s, name == null ? java.awt.Color.BLACK : java.awt.Color.decode(Md.COLORS.get(name)));
      s.addAttribute(COLOUR, name == null ? "" : name);
      int a = live.getSelectionStart(), b = live.getSelectionEnd();
      if (a == b) live.getInputAttributes().addAttributes(s);
      else live.getStyledDocument().setCharacterAttributes(a, b - a, s, false);
      live.requestFocusInWindow();
    }

    /** A space after "-" or "*" at the start of a line makes it a bullet, as in a word processor. */
    void space() {
      try {
        int caret = live.getCaretPosition(), ls = lineStart(live, caret);
        if (live.getSelectionStart() == live.getSelectionEnd() && live.getText(ls, caret - ls).matches("\\s*[-*]")) {
          live.getDocument().remove(caret - 1, 1);
          live.getDocument().insertString(caret - 1, "\u2022 ", attrs(false, false, false, null));
          return;
        }
      } catch (javax.swing.text.BadLocationException e) { /* an ordinary space */ }
      live.replaceSelection(" ");
    }

    /** The template: this user's own (Template > Use this comment as my template), else the team's
     *  (config/comment_template.txt, same marks as a comment), else the standard one. */
    String templateText() {
      String mine = Env.pref("trackerTemplate");
      if (mine != null && !mine.trim().isEmpty()) return mine;
      try {
        Path f = Fs.config("comment_template.txt");
        if (Files.isRegularFile(f)) {
          String t = new String(Files.readAllBytes(f), StandardCharsets.UTF_8).replace("\r", "").replaceAll("\\s+$", "");
          if (!t.trim().isEmpty()) return t;
        }
      } catch (Throwable x) { /* the standard one */ }
      return COMMENT_TEMPLATE;
    }

    /** Inserts the template; the caret goes to the first empty bullet (or the end of its first line). */
    void template() {
      String t = templateText();
      String cur = txt(live);
      try {
        int at = 0;
        if (cur.trim().isEmpty()) {
          live.getDocument().remove(0, live.getDocument().getLength());
        } else {
          at = live.getDocument().getLength();
          String gap = cur.endsWith("\n") ? "\n" : "\n\n";
          live.getDocument().insertString(at, gap, attrs(false, false, false, null));
          at += gap.length();
        }
        int n = insertMd(at, t);
        String ins = txt(live).substring(at, at + n);
        Matcher m = Pattern.compile("(?m)^\\s*(?:\u2022|\\d+\\.)\\s*$").matcher(ins);
        int first = ins.indexOf('\n');
        live.setCaretPosition(at + (m.find() ? m.end() : first < 0 ? ins.length() : first));
      } catch (javax.swing.text.BadLocationException e) { /* the box changed meanwhile */ }
      live.requestFocusInWindow();
    }

    /** Keeps the text of the box as this user's template (with its formatting). */
    void saveTemplate() {
      String t = readLive().replaceAll("\\s+$", "");
      if (t.trim().isEmpty()) {
        say("Template: write it in the box first (headings, bullets...), then choose this again", red);
        return;
      }
      Env.setPref("trackerTemplate", t);
      say("Template saved: Template > Insert template now gives this text (for you only)", green);
    }

    /** The text as the document holds it ("\n" line ends; JTextPane.getText() gives "\r\n" on Windows). */
    String txt(javax.swing.text.JTextComponent e) {
      try {
        return e.getDocument().getText(0, e.getDocument().getLength());
      } catch (javax.swing.text.BadLocationException x) {
        return e.getText();
      }
    }

    int lineStart(javax.swing.text.JTextComponent e, int off) {
      javax.swing.text.Element root = e.getDocument().getDefaultRootElement();
      return root.getElement(root.getElementIndex(off)).getStartOffset();
    }

    int lineEnd(javax.swing.text.JTextComponent e, int off) {
      javax.swing.text.Element root = e.getDocument().getDefaultRootElement();
      return Math.min(e.getDocument().getLength(), root.getElement(root.getElementIndex(off)).getEndOffset());
    }

    void replaceRange(javax.swing.text.JTextComponent e, String text, int a, int b) throws javax.swing.text.BadLocationException {
      e.getDocument().remove(a, b - a);
      e.getDocument().insertString(a, text, null);
    }

    /** A formatting button: on the formatted text it first opens the box for writing (caret at the end). */
    void fmt(Runnable r) {
      if (!editing) startEdit();
      if (editing) r.run();
    }

    void bind(javax.swing.text.JTextComponent a, String keyStroke, Runnable r) {
      a.getInputMap().put(javax.swing.KeyStroke.getKeyStroke(keyStroke), keyStroke);
      a.getActionMap().put(keyStroke, new javax.swing.AbstractAction() {
        private static final long serialVersionUID = 1L;
        @Override public void actionPerformed(java.awt.event.ActionEvent e) { r.run(); }
      });
    }

    /** Makes the selected lines (or the current one) a bullet or numbered list; again on a list: back to plain lines. */
    void list(Boolean numbered) {
      javax.swing.text.JTextComponent area = live;
      try {
        int s0 = area.getSelectionStart(), s1 = area.getSelectionEnd();
        if (s1 > s0 && lineStart(area, s1) == s1) s1--;
        int start = lineStart(area, s0), end = lineEnd(area, s1);
        if (end > start && txt(area).charAt(end - 1) == '\n') end--;
        String[] ls = txt(area).substring(start, end).split("\n", -1);
        boolean all = true;
        if (numbered != null) for (String l : ls) if (!l.trim().isEmpty()) all &= (numbered ? Md.NUMBER : Md.BULLET).matcher(l).matches();
        int[] at = new int[ls.length];
        for (int i = 0, p = start; i < ls.length; p += ls[i].length() + 1, i++) at[i] = p;
        int n = 0;
        for (String l : ls) if (!l.trim().isEmpty() || ls.length == 1) n++;
        for (int i = ls.length - 1; i >= 0; i--) {   // from the last line up: the places above stay right
          String l = ls[i];
          if (l.trim().isEmpty() && ls.length > 1) continue;
          Matcher b = Md.BULLET.matcher(l), k = Md.NUMBER.matcher(l);
          String body = b.matches() ? b.group(1) : k.matches() ? k.group(2) : l.replaceAll("^\\s+", "");
          area.getDocument().remove(at[i], l.length() - body.length());
          String pre = all ? "" : numbered ? (n--) + ". " : "\u2022 ";
          if (!all && !numbered) n--;   // (a menu choice of the same list again takes it off)
          area.getDocument().insertString(at[i], pre, attrs(false, false, false, null));
        }
        area.setCaretPosition(lineEnd(area, Math.min(area.getDocument().getLength(), start)) - (lineEnd(area, start) < area.getDocument().getLength() ? 1 : 0));
      } catch (javax.swing.text.BadLocationException e) { /* nothing selected */ }
      area.requestFocusInWindow();
    }

    /** Enter: in a list, the next line starts with the next bullet or number; Enter on an empty item ends the list. */
    void newLine() {
      javax.swing.text.JTextComponent area = live;
      try {
        int caret = area.getCaretPosition(), ls = lineStart(area, caret);
        String before = area.getText(ls, caret - ls);
        Matcher b = Pattern.compile("^(\\s*)([-*\u2022])\\s+(.*)$").matcher(before), k = Md.NUMBER.matcher(before);
        if (b.matches() || k.matches()) {
          String rest = b.matches() ? b.group(3) : k.group(2);
          if (rest.trim().isEmpty()) {   // empty item: end the list
            replaceRange(area, "", ls, caret);
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
      if (areaKey == null || !live.isEditable()) return;
      String now = readLive();
      String s = now.equals(liveNorm) ? liveLoaded : now.replace("\r", "").replaceAll("[ \\t]+\n", "\n").replaceAll("^\n+|\\s+$", "");
      liveLoaded = s;   // the box now stands for what is saved: no reload under the caret
      liveNorm = now;
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
      if (!view.isEmpty()) rowHeights();
    }

    // ----- actions -----

    /** The click is on the star of a run row (cell border 24 px, dot 12 px, star from 16 to 28 px of the icon). */
    boolean onRunStar(int row, java.awt.event.MouseEvent e) {
      int x = e.getX() - table.getCellRect(row, T_ID, false).x;
      return x >= 38 && x <= 54;
    }

    /** Copies "ID  path" of each selected row (a run: its latest .sim; the project: its folder), to paste in a mail or a document. */
    void copyLinks() {
      List<String> lines = new ArrayList<>();
      for (Item it : selected()) {
        if (it.isCase()) lines.add(it.caseId + "  " + dir.resolve(it.caseId).toAbsolutePath().normalize());
        else lines.add(shown(it.r[COL_ID]) + "  " + fileOf(dir, it.r).toAbsolutePath().normalize());
      }
      if (lines.isEmpty()) return;
      try {
        java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(
            String.join(System.lineSeparator(), lines)), null);
        say("Copied " + (lines.size() == 1 ? lines.get(0) : lines.size() + " references (ID and path)")
            + "  -  paste it in a mail or a document", green);
      } catch (Throwable t) {
        say("Cannot copy: " + reason(t), red);
      }
    }

    /** The folder of a run of a study (its run folder, else the folder of its latest file). */
    Path studyRunFolder(String[] r) {
      Path rec = runRecords(dir).get(runOf(r[COL_ID]));
      if (rec != null && rec.getFileName().toString().equals(RUN_TXT)) return rec.getParent();
      Path f = fileOf(dir, r);
      return f.getParent() == null ? dir : f.getParent();
    }

    /** Sets one status on every selected version (case rows: all their versions). */
    void setStatusAll(String status) {
      List<String[]> vs = new ArrayList<>();
      for (Item it : selected()) {
        if (it.isCase()) { for (String[] r : byCase.get(it.caseId)) if (!vs.contains(r)) vs.add(r); }
        else if (!vs.contains(it.r)) vs.add(it.r);
      }
      int n = 0;
      List<String> bad = new ArrayList<>();
      for (String[] r : vs) {
        if (r[COL_STATUS].equals(status)) continue;
        try {
          setIdentityKey(dir, caseOf(r[COL_ID]), "status_v" + verOf(r[COL_ID]), status);
          r[COL_STATUS] = status;
          n++;
        } catch (Throwable t) {
          bad.add(r[COL_ID]);
        }
      }
      try { writeTrackerCsv(dir, rows); } catch (Throwable t) { /* a copy only */ }
      if (!view.isEmpty()) model.fireTableRowsUpdated(0, view.size() - 1);
      log.line("tracker: status '" + status + "' on " + n + " run(s)");
      say(bad.isEmpty() ? "Saved: status " + (status.isEmpty() ? "(none)" : "'" + status + "'") + " on " + n + " run(s)"
          : "NOT saved on " + String.join(", ", bad), bad.isEmpty() ? green : red);
      showDetails();
    }

    /** The .sim of the selected version (a case row: its latest version). */
    Path selectedSim() {
      List<Item> sel = selected();
      if (sel.size() != 1) return null;
      String[] r = sel.get(0).r;
      if (r == null) {
        List<String[]> v = byCase.get(sel.get(0).caseId);
        if (v == null || v.isEmpty()) return null;
        r = v.get(v.size() - 1);
      }
      return fileOf(dir, r);
    }

    /** Opens the selected version in a STAR-CCM+ tab. Already open with its tab: that tab comes to the front. Else
     *  STAR-CCM+'s own Load Simulation dialog with the file filled in (processes, serial or parallel, power session,
     *  remote host: as in File > Load, which remembers your choices). A copy loaded without a tab by an earlier SimKit
     *  is closed first. Without that dialog, the path is copied for File > Load. */
    void openSelected() {
      final Path f = selectedSim();
      if (f == null || opening) return;
      if (!Files.isRegularFile(f)) { say("Not found: " + f + " (deleted? the tracker reloads when you come back to it)", red); return; }
      Object tab = simWindow(f);
      if (tab != null) {
        try {
          tab.getClass().getMethod("setActive", boolean.class).invoke(tab, true);
          say(f.getFileName() + " is already open: its tab is now in front", green);
        } catch (Throwable t) {
          say(f.getFileName() + " is already open in STAR-CCM+: pick its tab", green);
        }
        return;
      }
      if (isOpen(f)) {   // loaded without a tab (the first Open of SimKit did that): close that copy, then load properly
        opening = true;
        simBtn.setEnabled(false);
        say("Closing a copy of " + f.getFileName() + " that was loaded without a tab ...", grey);
        Thread t = new Thread(() -> {
          String err = null;
          for (Simulation s : new ArrayList<>(SimulationManager.getSingleton().simulations())) {
            try {
              String p = s.getSessionPath();
              if (p != null && Fs.same(Paths.get(p), f)) s.close(star.base.neo.ServerConnection.CloseOption.ForceClose);
            } catch (Throwable x) {
              err = reason(x);
            }
          }
          final String e2 = err;
          javax.swing.SwingUtilities.invokeLater(() -> {
            opening = false;
            showDetails();
            if (e2 != null && isOpen(f)) say("Could not close the copy without a tab (" + e2 + "): close STAR-CCM+ and open again", red);
            else showLoadDialog(f);
          });
        }, "SimKit close hidden sim");
        t.setDaemon(true);
        t.start();
        return;
      }
      showLoadDialog(f);
    }

    void showLoadDialog(Path f) {
      try {
        say("Load Simulation: " + f.getFileName() + " filled in; choose the processes and options, then OK", grey);
        loadDialog(f, true);
        log.line("tracker: Load Simulation dialog for " + f);
      } catch (Throwable t) {
        try {
          java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(f.toString()), null);
        } catch (Throwable x) { /* nothing to copy into */ }
        say("STAR-CCM+'s Load dialog could not be shown (" + reason(t) + "). Use File > Load: the path is copied, paste it there", red);
      }
    }

    void openFolder() {
      List<Item> sel = selected();
      if (sel.isEmpty()) return;
      Path fd = sel.get(0).isCase() ? dir : studyRunFolder(sel.get(0).r);
      try { Ui.browse(fd.toUri().toString()); } catch (Throwable t) { say("Cannot open: " + reason(t), red); }
    }

    /** What Hide acts on: runs (never the project itself). */
    List<Item> hideable(List<Item> sel) {
      List<Item> out = new ArrayList<>();
      for (Item it : sel) if (!it.isCase()) out.add(it);
      return out;
    }

    /** True when every item is hidden: the button unhides. */
    boolean unhideMode(List<Item> items) {
      if (items.isEmpty()) return false;
      for (Item it : items) if (!(!it.isCase() && hidden(it.r))) return false;
      return true;
    }

    /** Hide or unhide the selection at once; the message line offers Undo. */
    void hideOrShow() {
      final List<Item> items = hideable(selected());
      if (items.isEmpty()) return;
      final boolean hide = !unhideMode(items);
      try {
        String names = applyHidden(items, hide);
        sayUndo((hide ? "Hidden: " : "Shown again: ") + names + (hide && !showHidden.isSelected() ? "  (Hidden in the top bar shows it)" : ""),
            () -> {
              try {
                applyHidden(items, !hide);
                say((hide ? "Shown again: " : "Hidden again: ") + names, green);
              } catch (Throwable t) {
                say("NOT changed: " + reason(t), red);
              }
            });
      } catch (Throwable t) {
        say("NOT changed: " + reason(t), red);
      }
    }

    String applyHidden(List<Item> items, boolean hide) throws IOException {
      List<String> names = new ArrayList<>();
      for (Item it : items) {
        if (it.isCase()) {
          setHidden(dir, it.caseId, null, hide);
          names.add(it.caseId);
        } else {
          setHidden(dir, it.caseId, verOf(it.r[COL_ID]), hide);
          names.add(shown(it.r[COL_ID]));
        }
      }
      if (!hide) {   // a project hidden by an earlier SimKit: shown again with its runs
        Map<String, String> m = readIdentity(studyFile(dir));
        if (m.remove("hidden") != null) Fs.write(studyFile(dir), identityText(m));
      }
      reload();
      return String.join(", ", names);
    }

    void sayUndo(String s, Runnable undo) {
      say(s, green);
      undoAction = undo;
      undoBtn.setVisible(true);
    }

    void changeFolder() {
      try {
        Path d = Ui.chooseDir("Tracker: project folder (it holds " + STUDY_FILE + ")", dir);
        Path st = isStudy(d) ? d : studyDirOf(d.resolve("x"));
        if (st == null) {
          say(d.getFileName() + " is not a project folder (no " + STUDY_FILE + ")", red);
          return;
        }
        Fs.allow(st);
        dir = st;
        expanded.clear();
        reload();
        say("Showing the runs of " + dir.getFileName(), grey);
      } catch (Throwable t) {
        if (!(t instanceof Cancel)) say("Cannot change folder: " + reason(t), red);
      }
    }

    void popup(java.awt.event.MouseEvent e) {
      if (!e.isPopupTrigger()) return;
      int row = table.rowAtPoint(e.getPoint());
      if (row >= 0 && !table.isRowSelected(row)) table.setRowSelectionInterval(row, row);
      showMenu(table, e.getX(), e.getY(), row >= 0);
    }

    /** The context menu of the selection (table or tree). */
    void showMenu(java.awt.Component on, int x, int y, boolean hasRow) {
      Item cur = current();
      int row = hasRow && cur != null ? view.indexOf(cur) : -1;
      javax.swing.JPopupMenu m = new javax.swing.JPopupMenu();
      javax.swing.JMenuItem openSim = new javax.swing.JMenuItem("Open in STAR-CCM+...");
      openSim.addActionListener(a -> openSelected());
      openSim.setEnabled(row >= 0 && !opening);
      javax.swing.JMenuItem open = new javax.swing.JMenuItem("Open case folder");
      open.addActionListener(a -> openFolder());
      javax.swing.JMenuItem hide = new javax.swing.JMenuItem(hideBtn.getText(), hideBtn.getIcon());
      hide.addActionListener(a -> hideOrShow());
      open.setEnabled(row >= 0);
      hide.setEnabled(row >= 0 && hideBtn.isEnabled());
      javax.swing.JMenuItem ex = new javax.swing.JMenuItem("Unfold all cases");
      ex.addActionListener(a -> { expanded.addAll(byCase.keySet()); rebuild(); });
      javax.swing.JMenuItem co = new javax.swing.JMenuItem("Fold all cases");
      co.addActionListener(a -> { expanded.clear(); rebuild(); });
      javax.swing.JMenuItem copy = new javax.swing.JMenuItem("Copy reference (ID and path)   Ctrl+C");
      copy.addActionListener(a -> copyLinks());
      copy.setEnabled(row >= 0);
      int nSel = table.getSelectedRowCount();
      javax.swing.JMenu status = new javax.swing.JMenu(nSel > 1 ? "Set status of the " + nSel + " rows" : "Set status");
      for (String st : STATUSES) {
        javax.swing.JMenuItem si = new javax.swing.JMenuItem(st.isEmpty() ? "(none)" : st);
        si.addActionListener(a -> setStatusAll(st));
        status.add(si);
      }
      status.setEnabled(row >= 0);
      final boolean caseRow = row >= 0 && view.get(row).isCase();
      final String rowCase = row >= 0 ? view.get(row).caseId : null;
      final String runKey = row >= 0 && !caseRow ? view.get(row).r[COL_ID] : null;
      javax.swing.JMenuItem pin = new javax.swing.JMenuItem(runKey != null && pinned(runKey) ? "Remove star" : "Star");
      pin.addActionListener(a -> togglePin(runKey));
      pin.setEnabled(runKey != null);
      m.add(openSim);
      m.add(open);
      m.add(copy);
      m.addSeparator();
      m.add(status);
      m.add(pin);
      m.add(hide);
      if (on == table) {
        m.addSeparator();
        m.add(ex);
        m.add(co);
      }
      m.show(on, x, y);
    }

    // ----- look -----

    java.awt.Color rowBg(int row, boolean sel) {
      if (sel) return selBg;
      Item it = view.get(row);
      if (row == hoverRow) return hoverBg;
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

    /** Case rows a little taller than versions: more air between cases. */
    void rowHeights() {
      for (int i = 0; i < view.size(); i++) table.setRowHeight(i, view.get(i).isCase() ? 32 : 26);
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
        setBorder(it.isCase() && row > 0 ? BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0,
            new java.awt.Color(0xE3, 0xE6, 0xEA)), BorderFactory.createEmptyBorder(0, 6, 0, 6)) : BorderFactory.createEmptyBorder(0, 6, 0, 6));
        Font f = t.getFont();
        String s = v == null ? "" : String.valueOf(v);
        if (it.isCase()) {
          setFont(f.deriveFont(Font.BOLD));
          if (col == T_ID) {
            setIcon(starIcon(trackerIcon(expanded.contains(it.caseId) || filtering() ? 1 : 0), false, false));
            setToolTipText("Fold / unfold");
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
            s = n + (n == 1 ? " run" : " runs");
          }
          setText(s);
          return this;
        }
        boolean h = hidden(it.r);
        boolean dropped = "dropped".equals(it.r[COL_STATUS]);
        setFont(f.deriveFont(h ? Font.ITALIC : Font.PLAIN));
        if (h || dropped) setForeground(grey);
        switch (col) {
          case T_ID: {
            setBorder(BorderFactory.createEmptyBorder(0, 24, 0, 6));
            setIcon(runIcon(s.equals(openId), pinned(s)));
            setToolTipText(pinned(s) ? "Starred (for you): click the star to remove" : "Click the star to keep this run on top (for you)");
            setIconTextGap(6);
            if (s.equals(openId)) setToolTipText("Open in STAR-CCM+ now");
            String nm = nameOf(it.r[COL_FILE]), itx = iterOf(it.r[COL_FILE]);
            setText("<html>" + html(shown(s)) + (nm.isEmpty() ? "" : "&nbsp;&nbsp;<b>" + html(nm) + "</b>")
                + (itx.isEmpty() ? "" : "&nbsp;&nbsp;<font color='#8A94A0'>it " + html(itx) + "</font>") + "</html>");
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
              setText("<html><u>" + html(shown(s)) + "</u></html>");
              if (!sel && !h) setForeground(link);
              setToolTipText("Go to " + shown(s));
            } else setText(shown(s));
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

  // =====================================================================================================
  // Names (trial): type prefixes, default names, prefix vs boundary type; renames only ticked rows, on Apply
  // =====================================================================================================

  /** Type prefixes: prefix, boundary classes that fit it ("*Interface*" = any interface boundary), meaning.
   *  config/naming.csv replaces this list (same columns); Standard reports and Names use it. */
  static final String[][] DEFAULT_PREFIXES = {
      {"IN", "InletBoundary MassFlowBoundary StagnationBoundary FreeStreamBoundary", "inlet (velocity, mass flow, stagnation or free stream)"},
      {"OUT", "PressureBoundary OutletBoundary FreeStreamBoundary MassFlowBoundary", "outlet (pressure, flow-split, free stream or mass flow)"},
      {"OPEN", "PressureBoundary StagnationBoundary FreeStreamBoundary", "open boundary (pressure, stagnation or free stream)"},
      {"WALL", "WallBoundary", "wall"},
      {"SYM", "SymmetryBoundary", "symmetry plane"},
      {"IF", "*Interface*", "interface (contact between regions)"}};

  static volatile List<String[]> prefixes = Arrays.asList(DEFAULT_PREFIXES);

  static volatile Pattern prefixRx = Pattern.compile("^(IN|OUT|OPEN|WALL|SYM|IF)_");

  /** Reads config/naming.csv (built-in list when absent) into prefixes / prefixRx. */
  void loadPrefixes() {
    List<String[]> rows = new ArrayList<>();
    for (String[] r : config("naming.csv", new String[] {"prefix", "boundary_classes", "meaning"}, DEFAULT_PREFIXES)) {
      if (r[0] != null && r[0].trim().matches("[A-Za-z][A-Za-z0-9]*")) {
        rows.add(new String[] {r[0].trim(), r[1] == null ? "" : r[1].trim(), r[2] == null ? "" : r[2].trim()});
      }
    }
    if (rows.isEmpty()) rows = Arrays.asList(DEFAULT_PREFIXES);
    List<String> p = new ArrayList<>();
    for (String[] r : rows) p.add(Pattern.quote(r[0]));
    prefixes = rows;
    prefixRx = Pattern.compile("^(" + String.join("|", p) + ")_");
  }

  /** Prefix of a boundary name (IN, OUT, ...) from its surface part ("Part.IN_air" -> IN); null if none. */
  static String bcToken(String boundaryName) {
    String n = boundaryName.substring(boundaryName.lastIndexOf('.') + 1);
    Matcher m = prefixRx.matcher(n);
    return m.find() ? m.group(1) : null;
  }

  /** The prefix rule of a token, or null. */
  static String[] prefixRule(String token) {
    for (String[] r : prefixes) if (r[0].equals(token)) return r;
    return null;
  }

  /** True when the boundary class fits the prefix rule. */
  static boolean typeFits(String[] rule, String boundaryClass) {
    if (rule == null || rule[1].isEmpty()) return true;
    if (rule[1].startsWith("*")) return boundaryClass.contains(rule[1].replace("*", ""));
    return Arrays.asList(rule[1].split("\\s+")).contains(boundaryClass);
  }

  /** The prefix a boundary class suggests (first rule that lists it), or null. */
  static String prefixFor(String boundaryClass) {
    for (String[] r : prefixes) if (!r[1].startsWith("*") && Arrays.asList(r[1].split("\\s+")).contains(boundaryClass)) return r[0];
    for (String[] r : prefixes) if (r[1].startsWith("*") && boundaryClass.contains(r[1].replace("*", ""))) return r[0];
    return null;
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

  /** Default STAR-CCM+ names ("Report 3", "Plane Section 2", "Body 1", "Surface 12", "Default"). */
  static final Pattern DEFAULT_NAME = Pattern.compile("^(Default|Body|Surface|Part|Region|Interface|Report|Plot|Scene|Monitor|"
      + "Plane Section|Line Probe|Point Probe|Isosurface|Threshold|Block|Cylinder|Sphere|Cone)( [0-9]+)?$"
      + "|^[A-Z][A-Za-z]*( [A-Z][A-Za-z]*)* [0-9]+$");

  static boolean isDefaultName(String n) { return DEFAULT_NAME.matcher(n).find(); }

  /** One row of the Names table. kind: boundary, part surface, or the object type (default names only). */
  static final class NameRow {
    Object o;
    String kind, path, name, state, proposed = "", typeClass = "";
    String group = "", issue = "";   // region / part / object type; short issue ("" = ok)
    int cat;                         // 0 boundaries, 1 part surfaces, 2 default names
    boolean pick, problem;
  }

  static final String[] NAME_CATS = {"Boundaries", "Part surfaces", "Default names"};
  static final String[] NAME_CAT_HELP = {
      "The name prefix must match the boundary type. Standard reports select surfaces by prefix.",
      "Boundary names are created from part surface names. Rename before assigning parts to regions.",
      "Plots, reports and scenes display these names."};

  /** The name part a prefix goes on: the last dot-separated part of a boundary name ("Body 1.Surface 12" -> "Surface 12"). */
  static String lastPart(String n) { return n.substring(n.lastIndexOf('.') + 1); }

  /** n with the prefix p on its last part (an existing type prefix is replaced). */
  static String withPrefix(String n, String p) {
    int i = n.lastIndexOf('.');
    String head = i < 0 ? "" : n.substring(0, i + 1), last = n.substring(i + 1);
    Matcher m = prefixRx.matcher(last);
    if (m.find()) last = last.substring(m.end());
    return head + p + "_" + last.trim().replaceAll("\\s+", "_");
  }

  /** Boundaries, part surfaces, and every other object with a default name. Reads only. */
  List<NameRow> nameRows() {
    loadPrefixes();
    List<NameRow> out = new ArrayList<>();
    try {
      for (Region r : sim.getRegionManager().getRegions()) {
        for (Boundary b : r.getBoundaryManager().getBoundaries()) {
          NameRow x = new NameRow();
          x.o = b;
          x.kind = "boundary";
          x.name = nm(b);
          x.path = nm(r) + " / " + x.name;
          x.typeClass = cls(b.getBoundaryType());
          x.group = nm(r);
          x.cat = 0;
          String t = bcToken(x.name), sug = prefixFor(x.typeClass), bt = nm(b.getBoundaryType()).toLowerCase(Locale.ROOT);
          if (t == null) {
            x.state = "no type prefix (" + nm(b.getBoundaryType()) + ")";
            x.issue = "no prefix (" + bt + ")";
            x.problem = true;
            if (sug != null) x.proposed = withPrefix(x.name, sug);
          } else if (!typeFits(prefixRule(t), x.typeClass)) {
            x.state = t + "_ on a " + nm(b.getBoundaryType()) + " boundary";
            x.issue = t + "_ on " + bt;
            x.problem = true;
            if (sug != null) x.proposed = withPrefix(x.name, sug);
          } else {
            x.state = "ok";
          }
          out.add(x);
        }
      }
    } catch (Throwable t) { log.na("boundaries", t); }
    try {
      for (Object o : sim.get(SimulationPartManager.class).getParts()) {
        if (!(o instanceof GeometryPart)) continue;
        if (isDefaultName(nm(o))) out.add(defaultRow(o, "part", "Parts / " + nm(o)));
        try {
          for (Object ps : ((GeometryPart) o).getPartSurfaces()) {
            NameRow x = new NameRow();
            x.o = ps;
            x.kind = "part surface";
            x.name = nm(ps);
            x.path = nm(o) + " / " + x.name;
            x.group = nm(o);
            x.cat = 1;
            boolean def = isDefaultName(x.name);
            x.state = bcToken(x.name) != null ? (def ? "default name" : "ok") : def ? "default name, no type prefix" : "no type prefix";
            x.issue = "ok".equals(x.state) ? "" : def ? "default name" : "no prefix";
            x.problem = !"ok".equals(x.state);
            out.add(x);
          }
        } catch (Throwable t) { log.na("part surfaces of " + nm(o), t); }
      }
    } catch (Throwable t) { log.na("geometry parts", t); }
    try { for (Region r : sim.getRegionManager().getRegions()) if (isDefaultName(nm(r))) out.add(defaultRow(r, "region", "Regions / " + nm(r))); }
    catch (Throwable t) { log.na("regions", t); }
    Object[][] more = {{"report", null}, {"monitor", null}, {"plot", null}, {"scene", null}, {"derived part", null}};
    try { more[0][1] = sim.getReportManager().getObjects(); } catch (Throwable t) { log.na("reports", t); }
    try { more[1][1] = sim.getMonitorManager().getObjects(); } catch (Throwable t) { log.na("monitors", t); }
    try { more[2][1] = sim.getPlotManager().getObjects(); } catch (Throwable t) { log.na("plots", t); }
    try { more[3][1] = sim.getSceneManager().getScenes(); } catch (Throwable t) { log.na("scenes", t); }
    try { more[4][1] = sim.getPartManager().getObjects(); } catch (Throwable t) { log.na("derived parts", t); }
    for (Object[] m : more) {
      if (!(m[1] instanceof Collection)) continue;
      for (Object o : (Collection<?>) m[1]) {
        if (m[0].equals("plot") && "ResidualPlot".equals(cls(o))) continue;
        if (isDefaultName(nm(o))) out.add(defaultRow(o, (String) m[0], m[0] + " / " + nm(o)));
      }
    }
    return out;
  }

  NameRow defaultRow(Object o, String kind, String path) {
    NameRow x = new NameRow();
    x.o = o;
    x.kind = kind;
    x.name = nm(o);
    x.path = path;
    x.state = "default name";
    x.issue = "default name";
    x.cat = 2;
    x.group = kind.substring(0, 1).toUpperCase(Locale.ROOT) + kind.substring(1) + "s";
    x.problem = true;
    return x;
  }

  /** Batch test: the Names table in the log. */
  void logNames() {
    List<NameRow> rows = nameRows();
    int bad = 0;
    for (NameRow x : rows) {
      if (x.problem) bad++;
      log.line(String.format(Locale.ROOT, "names: %-13s %-45s %-36s %s", x.kind, Fmt.cut(x.path, 45), x.state,
          x.proposed.isEmpty() ? "" : "-> " + x.proposed));
    }
    log.line("names: " + rows.size() + " row(s), " + bad + " issue(s); prefixes " + prefixRx.pattern());
  }

  /** Names: the table; Apply renames the ticked rows that have a new name. */
  void toolNames() {
    final List<NameRow> rows = nameRows();
    final int[] total = {0};
    Ui.edt(() -> namesWindow(rows, list -> {
      int n = 0, bad = 0;
      for (NameRow x : list) {
        try {
          ((NamedObject) x.o).setPresentationName(x.proposed.trim());
          log.line("  renamed " + x.kind + " " + x.path + " -> " + x.proposed.trim());
          n++;
        } catch (Throwable t) {
          log.na("rename " + x.path, t);
          bad++;
        }
      }
      total[0] += n;
      return (n == 1 ? "1 object renamed." : n + " objects renamed.") + (bad > 0 ? " " + bad + " failed: see the Output window." : "")
          + " Undo is not available.";
    }, this::nameRows));
    result(total[0] == 0 ? "no objects renamed" : total[0] + " object(s) renamed");
  }

  /** The Names window (Swing thread): one category at a time (left, with its issue count), its rows grouped by
   *  region / part / object type, foldable; only the rows with issues unless asked. Rename runs 'rename' on the ticked
   *  rows with a new name in a worker thread (STAR-CCM+ calls), then 'reload' reads the rows again; the window stays
   *  open until Close. */
  Boolean namesWindow(final List<NameRow> all, final java.util.function.Function<List<NameRow>, String> rename,
      final java.util.function.Supplier<List<NameRow>> reload) {
    final int[] issues = new int[NAME_CATS.length], totals = new int[NAME_CATS.length];
    final Runnable recount = () -> {
      Arrays.fill(issues, 0);
      Arrays.fill(totals, 0);
      for (NameRow x : all) { totals[x.cat]++; if (x.problem) issues[x.cat]++; }
    };
    recount.run();
    final int[] cat = {0};
    for (int c = 0; c < NAME_CATS.length; c++) if (issues[c] > 0) { cat[0] = c; break; }
    final List<Object> view = new ArrayList<>();   // a String = a group heading, else a NameRow
    final Set<String> folded = new HashSet<>();
    final JTextField search = Ui.hintField("", "Filter by name", 20);
    final javax.swing.JCheckBox onlyIssues = new javax.swing.JCheckBox("Issues only", true);
    onlyIssues.setOpaque(false);
    final JLabel catTitle = new JLabel(" "), catHelp = Ui.muted(" "), ticked = Ui.muted(" ");
    catTitle.setFont(catTitle.getFont().deriveFont(Font.BOLD, 15f));
    final JButton apply = Ui.primary("Rename");
    apply.putClientProperty("simkit.stay", Boolean.TRUE);
    final String[] lastMsg = {null};   // the outcome of the last Rename, shown until the next tick
    final JButton useAll = Ui.link("Tick all proposed");
    useAll.setToolTipText("Ticks the rows shown that have a proposed name");
    final java.awt.Color amber = new java.awt.Color(0x8A, 0x5A, 0x00), amberBg = new java.awt.Color(0xFF, 0xF1, 0xD6),
        red = new java.awt.Color(0xB4, 0x23, 0x18), redBg = new java.awt.Color(0xFD, 0xE2, 0xE0),
        grey = new java.awt.Color(0x4A, 0x55, 0x63), greyBg = new java.awt.Color(0xEC, 0xEF, 0xF3),
        green = new java.awt.Color(0x2E, 0x6B, 0x2E), headBg = new java.awt.Color(0xF2, 0xF4, 0xF7);

    final javax.swing.table.AbstractTableModel model = new javax.swing.table.AbstractTableModel() {
      private static final long serialVersionUID = 1L;
      @Override public int getRowCount() { return view.size(); }
      @Override public int getColumnCount() { return 4; }
      @Override public String getColumnName(int c) { return new String[] {"", "Name", "Issue", "New name"}[c]; }
      @Override public Class<?> getColumnClass(int c) { return c == 0 ? Boolean.class : String.class; }
      @Override public boolean isCellEditable(int r, int c) { return c == 0 || c == 3 && view.get(r) instanceof NameRow; }
      @Override public Object getValueAt(int r, int c) {
        Object o = view.get(r);
        if (o instanceof String) {   // a heading: ticked when every row of the group is ticked
          if (c != 0) return c == 1 ? o : "";
          List<NameRow> g = groupRows((String) o);
          for (NameRow x : g) if (!x.pick) return false;
          return !g.isEmpty();
        }
        NameRow x = (NameRow) o;
        return c == 0 ? x.pick : c == 1 ? x.name : c == 2 ? x.issue : x.proposed;
      }
      @Override public void setValueAt(Object v, int r, int c) {
        Object o = view.get(r);
        boolean on = Boolean.TRUE.equals(v);
        if (o instanceof String) {   // a heading ticks / unticks every row of its group
          for (NameRow x : groupRows((String) o)) x.pick = on;
          fireTableRowsUpdated(0, view.size() - 1);   // keeps the selection
          return;
        }
        NameRow x = (NameRow) o;
        if (c == 0) x.pick = on;
        if (c == 3) { x.proposed = String.valueOf(v).trim(); if (hasNew(x)) x.pick = true; }
        fireTableRowsUpdated(0, view.size() - 1);   // the heading's tick follows; the selection stays
      }

      boolean hasNew(NameRow x) { return !x.proposed.isEmpty() && !x.proposed.equals(x.name); }

      List<NameRow> groupRows(String g) {
        List<NameRow> out = new ArrayList<>();
        for (Object k : view) if (k instanceof NameRow && ((NameRow) k).group.equals(g)) out.add((NameRow) k);
        return out;
      }
    };
    final JTable t = new JTable(model);
    final Runnable count = () -> {
      int n = 0, m = 0;
      for (NameRow x : all) {
        if (!x.pick) continue;
        if (!x.proposed.isEmpty() && !x.proposed.equals(x.name)) n++;
        else m++;
      }
      if (n + m > 0) lastMsg[0] = null;   // the message of the last Rename until the next tick
      apply.setText(n == 0 ? "Rename" : n == 1 ? "Rename 1 object" : "Rename " + n + " objects");
      apply.setEnabled(n > 0);
      ticked.setText(n + m == 0 && lastMsg[0] != null ? lastMsg[0] : n + m == 0 ? "No rows ticked."
          : (n + m == 1 ? "1 row ticked" : (n + m) + " rows ticked") + " (all categories)"
            + (m == 0 ? "." : "; " + m + " without a new name: set a prefix or enter a name."));
    };
    model.addTableModelListener(e -> count.run());
    final Runnable fill = () -> {
      if (t.isEditing()) t.getCellEditor().stopCellEditing();
      String q = search.getText().trim().toLowerCase(Locale.ROOT);
      Map<String, List<NameRow>> groups = new LinkedHashMap<>();
      for (NameRow x : all) {
        if (x.cat != cat[0] || onlyIssues.isSelected() && !x.problem) continue;
        if (!q.isEmpty() && !(x.name + " " + x.group + " " + x.proposed).toLowerCase(Locale.ROOT).contains(q)) continue;
        groups.computeIfAbsent(x.group, k -> new ArrayList<>()).add(x);
      }
      view.clear();
      for (Map.Entry<String, List<NameRow>> g : groups.entrySet()) {
        view.add(g.getKey());
        if (!folded.contains(g.getKey()) || !q.isEmpty()) view.addAll(g.getValue());
      }
      model.fireTableDataChanged();
      catTitle.setText(NAME_CATS[cat[0]]);
      catHelp.setText("<html><div style='width:560px'>" + Html.esc(NAME_CAT_HELP[cat[0]]) + "</div></html>");
      useAll.setEnabled(view.stream().anyMatch(o -> o instanceof NameRow && !((NameRow) o).proposed.isEmpty()));
    };

    // ----- table look: group headings, the issue as a tag, the new name in blue -----
    t.setRowHeight(26);
    t.setShowVerticalLines(false);
    t.setGridColor(new java.awt.Color(0xEC, 0xEE, 0xF1));
    t.setFillsViewportHeight(true);
    t.setTableHeader(null);
    t.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
    t.getColumnModel().getColumn(0).setMaxWidth(30);
    t.getColumnModel().getColumn(1).setPreferredWidth(300);
    t.getColumnModel().getColumn(2).setPreferredWidth(190);
    t.getColumnModel().getColumn(3).setPreferredWidth(300);
    t.setDefaultRenderer(String.class, new javax.swing.table.DefaultTableCellRenderer() {
      private static final long serialVersionUID = 1L;
      java.awt.Color tagBg;
      @Override public java.awt.Component getTableCellRendererComponent(JTable tb, Object v, boolean sel, boolean f, int r, int c) {
        super.getTableCellRendererComponent(tb, v, false, false, r, c);
        tagBg = null;
        setFont(tb.getFont().deriveFont(Font.PLAIN));
        setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
        Object o = view.get(r);
        boolean rowSel = tb.isRowSelected(r);
        setBackground(o instanceof String ? headBg : rowSel ? Ui.PALE : java.awt.Color.WHITE);
        setForeground(java.awt.Color.BLACK);
        if (o instanceof String) {
          if (c == 1) {
            int n = 0, k = 0;
            for (NameRow x : all) if (x.cat == cat[0] && x.group.equals(o)) { n++; if (x.problem) k++; }
            setText("<html><b>" + (folded.contains(o) ? "\u25B8 " : "\u25BE ") + Html.esc((String) o) + "</b>&nbsp;&nbsp;<font color='#6B7580'>"
                + (k == 0 ? "no issues" : k == 1 ? "1 issue" : k + " issues") + (n > k ? ", " + (n - k) + " ok" : "") + "</font></html>");
            setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 8));
          } else setText("");
          return this;
        }
        NameRow x = (NameRow) o;
        if (c == 1) {
          int i = x.name.lastIndexOf('.');
          setText(i < 0 ? x.name : "<html><font color='#8A94A0'>" + Html.esc(x.name.substring(0, i + 1)) + "</font>"
              + Html.esc(x.name.substring(i + 1)) + "</html>");
          setBorder(BorderFactory.createEmptyBorder(0, 18, 0, 8));
          setToolTipText(x.path);
        } else if (c == 2) {
          String is = x.issue.isEmpty() ? "ok" : x.issue;
          setText(is);
          setFont(tb.getFont().deriveFont(Font.BOLD, 11f));
          boolean mism = x.issue.contains("_ on ");
          setForeground(x.issue.isEmpty() ? green : mism ? red : x.issue.startsWith("default") ? grey : amber);
          tagBg = x.issue.isEmpty() ? null : mism ? redBg : x.issue.startsWith("default") ? greyBg : amberBg;
          setToolTipText(x.state);
        } else {
          boolean has = !x.proposed.isEmpty() && !x.proposed.equals(x.name);
          setText(has ? "\u2192  " + x.proposed : "\u2014");
          setForeground(has ? (x.pick ? Ui.BLUE : new java.awt.Color(0x7F, 0x9C, 0xC4)) : new java.awt.Color(0xA8, 0xB0, 0xBA));
          setFont(tb.getFont().deriveFont(has && x.pick ? Font.BOLD : Font.PLAIN));
          setToolTipText(has ? (x.pick ? "Will be renamed" : "Proposed name. Tick the row to apply it.") : "Double-click to enter a name");
        }
        return this;
      }
      @Override protected void paintComponent(java.awt.Graphics g) {
        if (tagBg != null) {
          java.awt.Graphics2D g2 = (java.awt.Graphics2D) g;
          g2.setColor(getBackground());
          g2.fillRect(0, 0, getWidth(), getHeight());
          g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
          int w = Math.min(getWidth() - 10, g2.getFontMetrics(getFont()).stringWidth(getText()) + 16);
          g2.setColor(tagBg);
          g2.fillRoundRect(4, 4, w, getHeight() - 8, getHeight() - 8, getHeight() - 8);
          setOpaque(false);
          super.paintComponent(g);
          setOpaque(true);
          return;
        }
        super.paintComponent(g);
      }
    });
    javax.swing.table.TableCellRenderer boxes = t.getDefaultRenderer(Boolean.class);
    t.setDefaultRenderer(Boolean.class, (tb, v, sel, foc, r, c) -> {
      java.awt.Component k = boxes.getTableCellRendererComponent(tb, v, false, false, r, c);
      Object o = view.get(r);
      k.setBackground(o instanceof String ? headBg : tb.isRowSelected(r) ? Ui.PALE : java.awt.Color.WHITE);
      k.setEnabled(true);
      return k;
    });
    t.addMouseListener(new java.awt.event.MouseAdapter() {
      @Override public void mouseClicked(java.awt.event.MouseEvent e) {   // a heading folds / unfolds its group
        int r = t.rowAtPoint(e.getPoint());
        if (r < 0 || !(view.get(r) instanceof String) || t.columnAtPoint(e.getPoint()) == 0) return;
        String g = (String) view.get(r);
        if (!folded.remove(g)) folded.add(g);
        fill.run();
      }
    });

    // ----- left: the categories -----
    final JPanel cats = new JPanel();
    cats.setLayout(new javax.swing.BoxLayout(cats, javax.swing.BoxLayout.Y_AXIS));
    cats.setBackground(java.awt.Color.WHITE);
    cats.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Ui.LINE),
        BorderFactory.createEmptyBorder(10, 8, 10, 8)));
    final List<JButton> catBtns = new ArrayList<>();
    final JPanel[] prefixBarRef = {null};
    final Runnable paintCats = () -> {
      for (int c = 0; c < catBtns.size(); c++) {
        JButton cb = catBtns.get(c);
        boolean on = c == cat[0];
        cb.setText("<html><b>" + NAME_CATS[c] + "</b><br><font color='" + (issues[c] > 0 ? "#8A5A00" : "#2E6B2E") + "'>"
            + (issues[c] > 0 ? (issues[c] == 1 ? "1 issue" : issues[c] + " issues") : totals[c] == 0 ? "none" : "no issues") + "</font>"
            + "<font color='#8A94A0'>" + (totals[c] > 0 ? "  \u00B7  " + totals[c] + " total" : "") + "</font></html>");
        cb.setBackground(on ? Ui.PALE : java.awt.Color.WHITE);
        cb.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 3, 0, 0, on ? Ui.BLUE : java.awt.Color.WHITE),
            BorderFactory.createEmptyBorder(8, 10, 8, 10)));
      }
    };
    for (int c = 0; c < NAME_CATS.length; c++) {
      final int cc = c;
      JButton cb = new JButton();
      cb.setHorizontalAlignment(javax.swing.SwingConstants.LEFT);
      cb.setFocusPainted(false);
      cb.setContentAreaFilled(true);
      cb.setOpaque(true);
      cb.setAlignmentX(0f);
      cb.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, 56));
      cb.addActionListener(e -> { cat[0] = cc; search.setText(""); folded.clear(); paintCats.run(); fill.run(); prefixBarRef[0].setVisible(cc != 2); });
      catBtns.add(cb);
      cats.add(cb);
      cats.add(javax.swing.Box.createVerticalStrut(4));
    }
    cats.add(javax.swing.Box.createVerticalGlue());
    JLabel src = Ui.muted("<html><div style='width:170px'>Prefixes: " + (Files.isRegularFile(Fs.config("naming.csv")) ? "config/naming.csv"
        : "built-in") + "</div></html>");
    src.setAlignmentX(0f);
    cats.add(src);
    cats.setPreferredSize(new java.awt.Dimension(210, 10));
    paintCats.run();

    // ----- right: what the category is, search and filter, the prefix buttons, the table -----
    JPanel tools = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
    tools.add(search);
    tools.add(javax.swing.Box.createHorizontalStrut(8));
    tools.add(onlyIssues);
    tools.add(javax.swing.Box.createHorizontalStrut(8));
    tools.add(useAll);
    final JPanel prefixBar = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
    prefixBarRef[0] = prefixBar;
    prefixBar.add(Ui.muted("Set prefix on ticked rows:"));
    for (String[] p : prefixes) {
      java.awt.Color[] tc = tagColors(p[0]);
      JButton b = new JButton(p[0] + "_");
      b.setToolTipText(p[0] + "_: " + p[2] + ". Applies to the ticked rows shown (none ticked: the selected rows).");
      b.setFont(b.getFont().deriveFont(Font.BOLD, 11f));
      b.setForeground(tc[1]);
      b.setBackground(tc[0]);
      b.setOpaque(true);
      b.setFocusPainted(false);
      b.setBorder(BorderFactory.createEmptyBorder(4, 9, 4, 9));
      b.addActionListener(e -> {
        if (t.isEditing()) t.getCellEditor().stopCellEditing();
        List<NameRow> on = new ArrayList<>();
        for (Object o : view) if (o instanceof NameRow && ((NameRow) o).pick) on.add((NameRow) o);
        if (on.isEmpty()) for (int i : t.getSelectedRows()) if (view.get(i) instanceof NameRow) on.add((NameRow) view.get(i));
        for (NameRow x : on) {
          if (x.cat == 2) continue;
          x.proposed = withPrefix(x.name, p[0]);
          x.pick = true;
        }
        if (!view.isEmpty()) model.fireTableRowsUpdated(0, view.size() - 1);
      });
      prefixBar.add(b);
    }
    useAll.addActionListener(e -> {
      for (Object o : view) if (o instanceof NameRow && !((NameRow) o).proposed.isEmpty()) ((NameRow) o).pick = true;
      if (!view.isEmpty()) model.fireTableRowsUpdated(0, view.size() - 1);
    });
    onlyIssues.addActionListener(e -> fill.run());
    search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
      @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { fill.run(); }
      @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { fill.run(); }
      @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { fill.run(); }
    });
    JPanel head = new JPanel();
    head.setLayout(new javax.swing.BoxLayout(head, javax.swing.BoxLayout.Y_AXIS));
    for (javax.swing.JComponent k : new javax.swing.JComponent[] {catTitle, catHelp, tools, prefixBar}) {
      k.setAlignmentX(0f);
      head.add(k);
      head.add(javax.swing.Box.createVerticalStrut(k == catTitle ? 2 : 8));
    }
    JScrollPane sp = new JScrollPane(t);
    sp.getViewport().setBackground(java.awt.Color.WHITE);
    sp.getVerticalScrollBar().setUnitIncrement(16);
    sp.setBorder(BorderFactory.createLineBorder(new java.awt.Color(0xE3, 0xE7, 0xEC)));
    JPanel right = new JPanel(new BorderLayout(0, 6));
    right.setBorder(BorderFactory.createEmptyBorder(0, 14, 0, 0));
    right.add(head, BorderLayout.NORTH);
    right.add(sp, BorderLayout.CENTER);
    right.add(ticked, BorderLayout.SOUTH);
    JPanel body = new JPanel(new BorderLayout());
    body.add(cats, BorderLayout.WEST);
    body.add(right, BorderLayout.CENTER);
    body.setPreferredSize(new java.awt.Dimension(1060, 560));
    prefixBar.setVisible(cat[0] != 2);
    fill.run();
    count.run();
    final Runnable paintCatsRef = paintCats;
    apply.addActionListener(e -> {
      if (t.isEditing()) t.getCellEditor().stopCellEditing();
      final List<NameRow> go = new ArrayList<>();
      for (NameRow x : all) {
        if (!x.pick || x.proposed.trim().isEmpty() || x.proposed.trim().equals(x.name)) continue;
        if (x.proposed.contains("/") || x.proposed.contains("\\")) {
          ticked.setText("Invalid name '" + x.proposed + "': / and \\ are not allowed.");
          ticked.setForeground(Ui.RED);
          return;
        }
        go.add(x);
      }
      if (go.isEmpty()) return;
      ticked.setForeground(Ui.MUTED);
      ticked.setText(go.size() == 1 ? "Renaming 1 object..." : "Renaming " + go.size() + " objects...");
      apply.setEnabled(false);
      Thread w = new Thread(() -> {   // STAR-CCM+ calls off the Swing thread
        String msg;
        List<NameRow> fresh = null;
        try {
          msg = rename.apply(go);
          fresh = reload == null ? null : reload.get();
        } catch (Throwable x) {
          msg = "Rename stopped: " + reason(x);
        }
        final String m = msg;
        final List<NameRow> f = fresh;
        javax.swing.SwingUtilities.invokeLater(() -> {
          if (f != null) {
            all.clear();
            all.addAll(f);
          }
          for (NameRow x : all) x.pick = false;
          recount.run();
          paintCatsRef.run();
          lastMsg[0] = m;
          fill.run();
          count.run();
        });
      }, "SimKit Names rename");
      w.setDaemon(true);
      w.start();
    });
    return Ui.sheet("Names", "Only ticked rows are renamed.", body, apply, null);
  }

  static int[] append(int[] a, int v) {
    int[] b = Arrays.copyOf(a, a.length + 1);
    b[a.length] = v;
    return b;
  }

  // =====================================================================================================
  // Standard reports (built from the surface prefixes)
  // =====================================================================================================

  /** The team's standard reports (config/standard_reports.csv, else these): name, group (one plot each), type,
   *  field (or the expression of an expression report), surfaces (prefixes joined by +, or REGIONS), condition
   *  ("", energy, unsteady, "needs <report> <report>"), meaning, and two optional columns: criterion (a stopping
   *  criterion on the report's monitor: "asymptotic <width> <samples>", "below <limit>" or "above <limit>"; created
   *  off, "... on" creates it on) and mean ("last <n>": a report <name>_mean = mean of the monitor over the last n
   *  samples). */
  static final String[] STD_COLS = {"name", "group", "type", "field", "surfaces", "condition", "meaning", "criterion", "mean"};

  static final String[][] STANDARD_REPORTS = {
      {"r_mdot_in", "massBalance", "mass flow", "", "IN", "", "mass flow entering (IN_ surfaces)"},
      {"r_mdot_out", "massBalance", "mass flow", "", "OUT+OPEN", "", "mass flow leaving (OUT_ and OPEN_)"},
      {"r_mdot_net", "massBalance", "mass flow", "", "IN+OUT+OPEN", "", "net mass flow: tends to 0 when mass is conserved"},
      {"r_mdot_imbalance_pct", "convergence", "expression", "abs(${r_mdot_net}) / abs(${r_mdot_in}) * 100", "", "needs r_mdot_net r_mdot_in", "mass imbalance in % of the inflow (converged: below 0.1)", "below 0.1", ""},
      {"r_p_in_avg", "pressure", "area average", "Pressure", "IN", "", "inlet static pressure (area average)"},
      {"r_p_out_avg", "pressure", "area average", "Pressure", "OUT", "", "outlet static pressure (area average)"},
      {"r_p0_in_avg", "pressure", "area average", "TotalPressure", "IN", "", "inlet total pressure (area average)"},
      {"r_dp_static", "pressure", "expression", "${r_p_in_avg} - ${r_p_out_avg}", "", "needs r_p_in_avg r_p_out_avg", "static pressure drop, inlet - outlet"},
      {"r_T_in_mfavg", "temperature", "mass-flow average", "Temperature", "IN", "energy", "inlet temperature (mass-flow average)"},
      {"r_T_out_mfavg", "temperature", "mass-flow average", "Temperature", "OUT+OPEN", "energy", "outlet temperature (mass-flow average)", "asymptotic 0.5 200", "last 500"},
      {"r_T_wall_max", "temperature", "maximum", "Temperature", "WALL", "energy", "hottest wall temperature"},
      {"r_Q_wall", "heat", "heat flow", "", "WALL", "energy", "heat flow through the walls", "", "last 500"},
      {"r_yplus_wall_max", "yplus", "maximum", "WallYplus", "WALL", "", "maximum wall y+"},
      {"r_yplus_wall_avg", "yplus", "area average", "WallYplus", "WALL", "", "average wall y+"},
      {"r_Co_max", "courant", "maximum", "CourantNumber", "REGIONS", "unsteady", "maximum convective Courant number"}};

  /** Report types by their plain name (a full class name works too). */
  static String reportClass(String type) {
    switch (type.trim().toLowerCase(Locale.ROOT)) {
      case "mass flow": return "star.flow.MassFlowReport";
      case "area average": return "star.base.report.AreaAverageReport";
      case "mass-flow average": return "star.flow.MassFlowAverageReport";
      case "maximum": return "star.base.report.MaxReport";
      case "minimum": return "star.base.report.MinReport";
      case "heat flow": return "star.energy.HeatTransferReport";
      case "expression": return "star.base.report.ExpressionReport";
      default: return type.trim();
    }
  }

  static String groupTitle(String g) {
    switch (g) {
      case "massBalance": return "Mass balance";
      case "convergence": return "Convergence";
      case "pressure": return "Pressure";
      case "temperature": return "Temperature";
      case "heat": return "Walls: heat";
      case "yplus": return "Walls: y+";
      case "courant": return "Unsteady";
      default: return g;
    }
  }

  /** One proposed report: its definition, the objects it is computed on, and why not (null: it can be made). */
  static final class StdReport {
    String[] def;
    List<Object> target = new ArrayList<>();
    List<Object> deflt;   // the surfaces found from the names (Default)
    String why;
    boolean on;
    Object existing;      // the report already in the simulation: on Create, the new surfaces are added to it
    int added;            // surfaces it does not have yet
  }

  /** The surfaces a report is computed on (empty if not readable). */
  static List<Object> partsOf(Object report) {
    try {
      Object objs = Reflect.invoke(Reflect.invoke(report, "getParts"), "getObjects");
      return objs instanceof Collection ? new ArrayList<Object>((Collection<?>) objs) : new ArrayList<>();
    } catch (Throwable t) {
      return new ArrayList<>();
    }
  }

  /** Standard reports (one window): the team's list grouped by purpose; each row shows the surfaces it is computed on
   *  (double-click to choose others) or why it cannot be made here. Creates the reports, a monitor each and one
   *  plot per group. Existing objects are never changed or replaced. */
  void toolStandardReports() {
    loadPrefixes();
    Map<String, List<Boundary>> bc = boundariesByToken();
    List<Object> regions = new ArrayList<>(sim.getRegionManager().getRegions());
    Set<String> existing = new HashSet<>();
    Map<String, Object> existingObj = new HashMap<>();
    for (Object r : sim.getReportManager().getObjects()) { existing.add(nm(r)); existingObj.put(nm(r), r); }
    boolean unsteady = unsteadySolver() != null;
    boolean energy = fieldFunction("Temperature") != null;
    List<StdReport> all = new ArrayList<>();
    Set<String> possible = new HashSet<>(existing);
    for (String[] r : config("standard_reports.csv", STD_COLS, STANDARD_REPORTS)) {
      if (r[0] == null || r[0].trim().isEmpty()) continue;
      for (int k = 0; k < r.length; k++) r[k] = r[k] == null ? "" : r[k].trim();
      StdReport x = new StdReport();
      x.def = r;
      boolean expr = r[2].equalsIgnoreCase("expression");
      if (r[4].equals("REGIONS")) x.target.addAll(regions);
      else if (!r[4].isEmpty()) for (String t : r[4].split("\\+")) x.target.addAll(bc.getOrDefault(t, Collections.emptyList()));
      if (existing.contains(r[0])) {   // a standard report already there: new surfaces of its prefixes are added on request
        x.why = "already in the simulation";
        if (!expr && !r[4].equals("REGIONS") && !r[4].isEmpty()) {
          List<Object> have = partsOf(existingObj.get(r[0]));
          Set<Object> union = new LinkedHashSet<>(have);
          union.addAll(x.target);
          x.added = union.size() - have.size();
          if (x.added > 0 && !have.isEmpty()) {
            x.existing = existingObj.get(r[0]);
            x.target = new ArrayList<>(union);
            x.why = null;
          }
        }
      }
      else if (!expr && x.target.isEmpty()) x.why = "no " + r[4].replace("+", "_ or ") + "_ surface";
      else if (r[5].equals("energy") && !energy) x.why = "no energy model";
      else if (r[5].equals("unsteady") && !unsteady) x.why = "steady simulation";
      else if (!expr && !r[3].isEmpty() && fieldFunction(r[3]) == null) x.why = r[3] + " not available";
      else if (Reflect.cls(reportClass(r[2])) == null) x.why = "report type not available";
      if (x.why == null && r[0].equals("r_mdot_net") && (bc.getOrDefault("IN", Collections.emptyList()).isEmpty()
          || (bc.getOrDefault("OUT", Collections.emptyList()).isEmpty() && bc.getOrDefault("OPEN", Collections.emptyList()).isEmpty()))) {
        x.why = "needs IN_ and OUT_ or OPEN_ surfaces";
      }
      if (x.why == null && r[5].startsWith("needs ")) {
        for (String n : r[5].substring(6).trim().split("\\s+")) if (!possible.contains(n)) x.why = "needs " + n;
      }
      x.on = x.why == null;
      if (x.why == null) possible.add(r[0]);
      all.add(x);
    }
    boolean any = false;
    for (StdReport x : all) any |= x.why == null;
    if (!any) {
      if (gui) Ui.sheet("Standard reports", "Nothing to add", Ui.muted("All present, or no IN_ / OUT_ / OPEN_ / WALL_ surfaces."),
          Ui.primary("OK"), null);
      log.line("nothing to add.");
      for (StdReport x : all) log.line(String.format("  %-22s %s", x.def[0], x.why));
      return;
    }
    if (gui) {
      List<Object> every = new ArrayList<>();
      for (Region r : sim.getRegionManager().getRegions()) every.addAll(r.getBoundaryManager().getBoundaries());
      if (!Ui.edt(() -> reportsWindow(all, every))) throw new Cancel(null);
    }
    Map<String, List<Object>> groups = new LinkedHashMap<>();
    Set<String> made = new LinkedHashSet<>(existing);
    Map<String, String[]> defs = new HashMap<>();
    int n = 0, upd = 0;
    for (StdReport x : all) {
      if (!x.on || x.why != null) continue;
      String[] r = x.def;
      defs.put(r[0], r);
      if (x.existing != null) {
        try {
          Reflect.invoke(Reflect.invoke(x.existing, "getParts"), "setObjects", new NeoObjectVector(x.target.toArray()));
          upd++;
          log.line(String.format("  updated %-22s +%d surface(s), %d in all", r[0], x.added, x.target.size()));
        } catch (Throwable t) {
          log.na("update of " + r[0], t);
        }
        continue;
      }
      if (r[5].startsWith("needs ")) {
        boolean ok = true;
        for (String q : r[5].substring(6).trim().split("\\s+")) ok &= made.contains(q);
        if (!ok) { log.line("  " + r[0] + ": skipped (the reports it uses were not created)"); continue; }
      }
      try {
        Object report = Reflect.invoke(sim.getReportManager(), "createReport", Reflect.cls(reportClass(r[2])));
        ((NamedObject) report).setPresentationName(r[0]);
        if (r[2].equalsIgnoreCase("expression")) {
          Reflect.invoke(report, "setDefinition", r[3]);
        } else {
          if (!r[3].isEmpty()) Reflect.invoke(report, "setFieldFunction", fieldFunction(r[3]));
          Reflect.invoke(Reflect.invoke(report, "getParts"), "setObjects", new NeoObjectVector(x.target.toArray()));
        }
        made.add(r[0]);
        n++;
        log.line(String.format("  created %-22s %s", r[0], r[6]));
        if (!r[1].isEmpty()) groups.computeIfAbsent(r[1], k -> new ArrayList<>()).add(report);
      } catch (Throwable t) {
        log.na("report " + r[0], t);
      }
    }
    List<Object> plots = new ArrayList<>();
    int crit = 0, means = 0;
    for (Map.Entry<String, List<Object>> g : groups.entrySet()) {
      Object[] mp = monitorsAndPlot(g.getValue(), "pl_" + g.getKey());
      List<Object> extra = new ArrayList<>();
      for (Object m : (List<?>) mp[0]) {
        String[] d;
        try { d = defs.get(nm(((ReportMonitor) m).getReport())); } catch (Throwable t) { d = null; }
        if (d == null) continue;
        if (d.length > 7 && d[7] != null && !d[7].trim().isEmpty() && stoppingCriterion(m, d[0], d[7].trim())) crit++;
        if (d.length > 8 && d[8] != null && !d[8].trim().isEmpty()) {
          Object mean = meanReport(m, d[0], d[8].trim());
          if (mean != null) { extra.add(mean); means++; }
        }
      }
      g.getValue().addAll(extra);
      // in the tree: reports and monitors in one folder per purpose, the plots together in one folder
      String folder = groupTitle(g.getKey());
      toFolder(sim.getReportManager(), folder, g.getValue());
      toFolder(sim.getMonitorManager(), folder, (List<?>) mp[0]);
      if (mp[1] != null) plots.add(mp[1]);
    }
    toFolder(sim.getPlotManager(), "Standard reports", plots);   // one call: adding to a folder later makes a second one
    result(n + " report(s) with monitors and " + groups.size() + " plot(s) created" + (upd > 0 ? ", " + upd + " updated" : "")
        + (crit > 0 ? ", " + crit + " stopping criterion(a)" : "") + (means > 0 ? ", " + means + " mean report(s)" : ""));
  }

  /** A stopping criterion on a report monitor: "asymptotic <width> <samples>", "below <limit>", "above <limit>";
   *  created off unless the text ends with " on". Returns true when made. */
  boolean stoppingCriterion(Object monitor, String report, String spec) {
    String[] w = spec.toLowerCase(Locale.ROOT).split("\\s+");
    boolean on = w[w.length - 1].equals("on");
    try {
      MonitorIterationStoppingCriterion c = ((Monitor) monitor).createIterationStoppingCriterion();
      if (w[0].equals("asymptotic") && w.length >= 3) {
        c.getCriterionOption().setSelected(MonitorIterationStoppingCriterionOption.Type.ASYMPTOTIC);
        MonitorIterationStoppingCriterionAsymptoticType a = (MonitorIterationStoppingCriterionAsymptoticType) c.getCriterionType();
        a.getMaxWidth().setValue(Fmt.parse(w[1]));
        a.setNumberSamples(Integer.parseInt(w[2]));
      } else if ((w[0].equals("below") || w[0].equals("above")) && w.length >= 2) {
        c.getCriterionOption().setSelected(w[0].equals("below") ? MonitorIterationStoppingCriterionOption.Type.MINIMUM
            : MonitorIterationStoppingCriterionOption.Type.MAXIMUM);
        ((MonitorIterationStoppingCriterionLimitType) c.getCriterionType()).getLimit().setValue(Fmt.parse(w[1]));
      } else {
        log.line("  criterion of " + report + ": '" + spec + "' not understood (asymptotic <width> <samples> | below <x> | above <x>)");
        c.getSolverStoppingCriterionManager().remove(c);
        return false;
      }
      c.setPresentationName("stop_" + report);
      c.setIsUsed(on);
      log.line("  stopping criterion stop_" + report + ": " + spec.replaceAll("\\s+on$", "") + (on ? " (on)" : " (off: tick it in Stopping Criteria to use it)"));
      return true;
    } catch (Throwable t) {
      log.na("stopping criterion of " + report, t);
      return false;
    }
  }

  /** <report>_mean: mean of the monitor over the last n samples ("last <n>"); null if not made. */
  Object meanReport(Object monitor, String report, String spec) {
    String[] w = spec.toLowerCase(Locale.ROOT).split("\\s+");
    if (w.length < 2 || !w[0].equals("last") || !w[1].matches("[0-9]+")) {
      log.line("  mean of " + report + ": '" + spec + "' not understood (last <n>)");
      return null;
    }
    try {
      StatisticsReport s = sim.getReportManager().createReport(StatisticsReport.class);
      s.setPresentationName(report + "_mean");
      s.setMonitor((PlotableMonitor) monitor);
      s.setStatisticOption(StatisticOption.Mean);
      s.setSampleFilterOption(SampleFilterOption.LastNSamples);
      ((LastNSamplesFilter) s.getSampleFilter()).setNSamples(Integer.parseInt(w[1]));
      log.line("  mean report " + report + "_mean: last " + w[1] + " samples of the monitor");
      return s;
    } catch (Throwable t) {
      log.na("mean of " + report, t);
      return null;
    }
  }

  /** The Standard reports window (Swing thread): the reports grouped by purpose on the left (tick to create); the
   *  surfaces of the selected report on the right (defaults ticked from the surface names; change them freely).
   *  Rows that cannot be made are grey with the reason. True on Create. */
  boolean reportsWindow(final List<StdReport> all, final List<Object> every) {
    final List<Object> rows = new ArrayList<>();   // a String = a group title, else a StdReport
    String last = null;
    for (StdReport x : all) {
      if (x.deflt == null) x.deflt = new ArrayList<>(x.target);
      if (!x.def[1].equals(last)) { rows.add(groupTitle(x.def[1])); last = x.def[1]; }
      rows.add(x);
    }
    final JButton ok = Ui.primary("Create");
    final Runnable count = () -> {
      int k = 0;
      for (StdReport x : all) if (x.on && x.why == null) k++;
      ok.setText(k == 1 ? "Create 1 report" : "Create " + k + " reports");
      ok.setEnabled(k > 0);
    };
    final java.util.function.Function<StdReport, Boolean> surf = x -> x.why == null && !x.def[2].equalsIgnoreCase("expression") && !x.def[4].equals("REGIONS");
    final java.util.function.Function<StdReport, Boolean> edited = x -> !new HashSet<>(x.target).equals(new HashSet<>(x.deflt));
    final javax.swing.table.AbstractTableModel model = new javax.swing.table.AbstractTableModel() {
      private static final long serialVersionUID = 1L;
      @Override public int getRowCount() { return rows.size(); }
      @Override public int getColumnCount() { return 4; }
      @Override public String getColumnName(int c) { return new String[] {"", "report", "gives", "on"}[c]; }
      @Override public Class<?> getColumnClass(int c) { return c == 0 ? Boolean.class : String.class; }
      @Override public boolean isCellEditable(int r, int c) { return c == 0 && rows.get(r) instanceof StdReport && ((StdReport) rows.get(r)).why == null; }
      @Override public Object getValueAt(int r, int c) {
        Object o = rows.get(r);
        if (o instanceof String) return c == 1 ? o : c == 0 ? null : "";
        StdReport x = (StdReport) o;
        switch (c) {
          case 0: return x.why == null && x.on;
          case 1: return x.def[0];
          case 2: return x.existing != null ? "update: " + x.def[6] : x.def[6];
          default:
            if (x.why != null) return x.why;
            if (x.existing != null) return "+" + x.added + (x.added == 1 ? " new surface" : " new surfaces");
            if (x.def[2].equalsIgnoreCase("expression")) return "formula";
            if (x.def[4].equals("REGIONS")) return "all regions";
            int n = x.target.size();
            return (edited.apply(x) ? "● " : "") + n + (n == 1 ? " surface" : " surfaces");
        }
      }
      @Override public void setValueAt(Object v, int r, int c) {
        ((StdReport) rows.get(r)).on = Boolean.TRUE.equals(v);
        count.run();
      }
    };
    final JTable table = new JTable(model) {
      private static final long serialVersionUID = 1L;
      @Override public java.awt.Component prepareRenderer(javax.swing.table.TableCellRenderer rr, int r, int c) {
        java.awt.Component k = super.prepareRenderer(rr, r, c);
        Object o = rows.get(r);
        boolean head = o instanceof String, off = !head && ((StdReport) o).why != null;
        boolean ed = !head && !off && edited.apply((StdReport) o);
        k.setBackground(head ? new java.awt.Color(0xF2, 0xF4, 0xF7) : isRowSelected(r) && !off ? Ui.PALE : java.awt.Color.WHITE);
        k.setForeground(off ? new java.awt.Color(0xA8, 0xB0, 0xBA) : head ? Ui.MUTED : c == 3 && ed ? Ui.BLUE : c >= 2 ? Ui.MUTED : java.awt.Color.BLACK);
        k.setFont(k.getFont().deriveFont(head ? Font.BOLD : Font.PLAIN));
        if (k instanceof javax.swing.JCheckBox) k.setEnabled(!off && !head);
        return k;
      }
    };
    javax.swing.table.TableCellRenderer boxes = table.getDefaultRenderer(Boolean.class);
    table.setDefaultRenderer(Boolean.class, (t, v, sel, foc, r, c) -> {
      if (rows.get(r) instanceof String) {
        JLabel l = new JLabel();
        l.setOpaque(true);
        l.setBackground(new java.awt.Color(0xF2, 0xF4, 0xF7));
        return l;
      }
      return boxes.getTableCellRendererComponent(t, v, sel, foc, r, c);
    });
    table.setRowHeight(24);
    table.setShowVerticalLines(false);
    table.setGridColor(new java.awt.Color(0xEC, 0xEE, 0xF1));
    table.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
    table.getColumnModel().getColumn(0).setMaxWidth(30);
    table.getColumnModel().getColumn(1).setPreferredWidth(150);
    table.getColumnModel().getColumn(2).setPreferredWidth(270);
    table.getColumnModel().getColumn(3).setPreferredWidth(110);

    // ----- the right pane: the surfaces of the selected report -----
    final JLabel title = new JLabel(" "), meaning = Ui.muted(" "), countLbl = Ui.muted(" ");
    title.setFont(title.getFont().deriveFont(Font.BOLD, 14f));
    final JTextField search = Ui.hintField("", "Search surfaces", 18);
    final JPanel list = new JPanel();
    list.setLayout(new javax.swing.BoxLayout(list, javax.swing.BoxLayout.Y_AXIS));
    list.setBackground(java.awt.Color.WHITE);
    final JLabel note = Ui.muted(" ");
    final JButton dflt = Ui.link("Default"), allB = Ui.link("All"), none = Ui.link("None");
    final StdReport[] cur = {null};
    final Runnable[] fill = {null};
    final Runnable refreshRow = () -> {   // only the row of the report: the selection (and the right pane) stays
      int i = cur[0] == null ? -1 : rows.indexOf(cur[0]);
      if (i >= 0) model.fireTableRowsUpdated(i, i);
    };
    fill[0] = () -> {
      list.removeAll();
      StdReport x = cur[0];
      boolean can = x != null && surf.apply(x);
      search.setEnabled(can);
      dflt.setEnabled(can);
      allB.setEnabled(can);
      none.setEnabled(can);
      if (x == null) {
        title.setText("Select a report");
        meaning.setText(" ");
        note.setText(" ");
        countLbl.setText(" ");
      } else {
        title.setText(x.def[0]);
        meaning.setText(x.def[6]);
        if (x.why != null) note.setText("Not possible here: " + x.why);
        else if (x.def[2].equalsIgnoreCase("expression")) note.setText("<html>Formula: " + Html.esc(x.def[3])
            + (x.def[5].startsWith("needs ") ? "<br>Uses: " + Html.esc(x.def[5].substring(6).trim().replace(" ", ", ")) : "") + "</html>");
        else if (x.def[4].equals("REGIONS")) note.setText("All regions");
        else note.setText(" ");
        if (can) {
          String q = search.getText().trim().toLowerCase(Locale.ROOT);
          // surfaces grouped by type prefix (IN_, OUT_, OPEN_, WALL_, SYM_, IF_, then the others)
          Map<String, List<Object>> groups = new LinkedHashMap<>();
          for (String t : new String[] {"IN", "OUT", "OPEN", "WALL", "SYM", "IF", ""}) groups.put(t, new ArrayList<>());
          Set<String> regions = new HashSet<>();
          for (Object b : every) {
            String n = nm(b), reg = "";
            try { reg = nm(((Boundary) b).getRegion()); } catch (Throwable t) { /* no region (offline) */ }
            regions.add(reg);
            if (!q.isEmpty() && !n.toLowerCase(Locale.ROOT).contains(q) && !reg.toLowerCase(Locale.ROOT).contains(q)) continue;
            String tok = bcToken(n);
            groups.get(tok == null ? "" : tok).add(b);
          }
          final boolean manyRegions = regions.size() > 1;
          boolean firstGroup = true;
          for (Map.Entry<String, List<Object>> g : groups.entrySet()) {
            final List<Object> members = g.getValue();
            if (members.isEmpty()) continue;
            String tok = g.getKey().isEmpty() ? null : g.getKey();
            int on = 0;
            for (Object b : members) if (x.target.contains(b)) on++;
            final javax.swing.JCheckBox gb = new javax.swing.JCheckBox("", on == members.size());
            gb.setBackground(java.awt.Color.WHITE);
            gb.setFocusPainted(false);
            gb.setToolTipText("All " + (tok == null ? "other" : tok + "_") + " surfaces");
            final JLabel tag = new JLabel(tok == null ? "OTHER" : tok);
            tag.setFont(tag.getFont().deriveFont(Font.BOLD, 11f));
            java.awt.Color[] tc = tagColors(tok == null ? "" : tok);
            if (tok == null) tc = new java.awt.Color[] {new java.awt.Color(0xF2, 0xF4, 0xF7), Ui.MUTED};
            tag.setOpaque(true);
            tag.setBackground(tc[0]);
            tag.setForeground(tc[1]);
            tag.setBorder(BorderFactory.createEmptyBorder(1, 6, 1, 6));
            final JLabel gn = Ui.muted(on + " / " + members.size());
            JPanel hrow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
            hrow.setBackground(java.awt.Color.WHITE);
            hrow.setBorder(BorderFactory.createEmptyBorder(firstGroup ? 2 : 10, 0, 2, 0));
            hrow.add(gb);
            hrow.add(tag);
            hrow.add(gn);
            hrow.setAlignmentX(0f);
            hrow.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, hrow.getPreferredSize().height));
            list.add(hrow);
            firstGroup = false;
            gb.addActionListener(e -> {
              List<Object> nt = new ArrayList<>();
              for (Object o : every) {
                boolean in = members.contains(o) ? gb.isSelected() : x.target.contains(o);
                if (in) nt.add(o);
              }
              x.target.clear();
              x.target.addAll(nt);
              fill[0].run();
              refreshRow.run();
            });
            for (final Object b : members) {
              String n = nm(b), reg = "";
              try { reg = nm(((Boundary) b).getRegion()); } catch (Throwable t) { /* offline */ }
              final javax.swing.JCheckBox cb = new javax.swing.JCheckBox(n.substring(n.lastIndexOf('.') + 1), x.target.contains(b));
              cb.setBackground(java.awt.Color.WHITE);
              cb.setFocusPainted(false);
              cb.setToolTipText(reg.isEmpty() ? n : reg + " : " + n);
              JPanel row = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
              row.setBackground(java.awt.Color.WHITE);
              row.setBorder(BorderFactory.createEmptyBorder(0, 20, 0, 0));
              row.add(cb);
              if (manyRegions && !reg.isEmpty()) row.add(Ui.muted(reg));
              row.setAlignmentX(0f);
              row.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, cb.getPreferredSize().height + 2));
              cb.addActionListener(e -> {
                if (cb.isSelected()) {   // keep the order of the model's surfaces
                  List<Object> nt = new ArrayList<>();
                  for (Object o : every) if (o == b || x.target.contains(o)) nt.add(o);
                  x.target.clear();
                  x.target.addAll(nt);
                } else {
                  x.target.remove(b);
                }
                int k = 0;
                for (Object o : members) if (x.target.contains(o)) k++;
                gb.setSelected(k == members.size());
                gn.setText(k + " / " + members.size());
                countLbl.setText(x.target.size() + " of " + every.size() + " surfaces");
                refreshRow.run();
              });
              list.add(row);
            }
          }
          countLbl.setText(x.target.size() + " of " + every.size() + " surfaces");
        } else {
          countLbl.setText(" ");
        }
      }
      list.revalidate();
      list.repaint();
    };
    dflt.addActionListener(e -> { if (cur[0] != null) { cur[0].target.clear(); cur[0].target.addAll(cur[0].deflt); fill[0].run(); refreshRow.run(); } });
    allB.addActionListener(e -> { if (cur[0] != null) { cur[0].target.clear(); cur[0].target.addAll(every); fill[0].run(); refreshRow.run(); } });
    none.addActionListener(e -> { if (cur[0] != null) { cur[0].target.clear(); fill[0].run(); refreshRow.run(); } });
    search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
      @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { fill[0].run(); }
      @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { fill[0].run(); }
      @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { fill[0].run(); }
    });
    table.getSelectionModel().addListSelectionListener(e -> {
      if (e.getValueIsAdjusting()) return;
      int r = table.getSelectedRow();
      StdReport x = r >= 0 && rows.get(r) instanceof StdReport ? (StdReport) rows.get(r) : null;
      if (x == cur[0]) return;
      cur[0] = x;
      search.setText("");
      fill[0].run();
    });
    JPanel quick = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 2, 0));
    quick.add(Ui.muted("Select:"));
    quick.add(dflt);
    quick.add(allB);
    quick.add(none);
    JPanel head = new JPanel();
    head.setLayout(new javax.swing.BoxLayout(head, javax.swing.BoxLayout.Y_AXIS));
    for (javax.swing.JComponent k : new javax.swing.JComponent[] {title, meaning, note, search, quick}) {
      k.setAlignmentX(0f);
      head.add(k);
      head.add(javax.swing.Box.createVerticalStrut(k == meaning || k == note ? 6 : 4));
    }
    search.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, search.getPreferredSize().height));
    JScrollPane ls = new JScrollPane(list);
    ls.getViewport().setBackground(java.awt.Color.WHITE);
    ls.getVerticalScrollBar().setUnitIncrement(16);
    ls.setBorder(BorderFactory.createLineBorder(new java.awt.Color(0xE3, 0xE7, 0xEC)));
    JPanel right = new JPanel(new BorderLayout(0, 6));
    right.add(head, BorderLayout.NORTH);
    right.add(ls, BorderLayout.CENTER);
    right.add(countLbl, BorderLayout.SOUTH);
    right.setPreferredSize(new java.awt.Dimension(300, 10));

    count.run();
    JScrollPane sp = new JScrollPane(table);
    int h = Math.min(24 * rows.size() + 28, 520);
    sp.setPreferredSize(new java.awt.Dimension(600, h));
    sp.getViewport().setBackground(java.awt.Color.WHITE);
    JPanel center = new JPanel(new BorderLayout(14, 0));
    center.add(sp, BorderLayout.CENTER);
    center.add(right, BorderLayout.EAST);
    JPanel body = new JPanel(new BorderLayout(0, 8));
    body.add(center, BorderLayout.CENTER);
    body.add(Ui.muted("One monitor per report, one plot per group, in folders by purpose  ·  ● surfaces changed  ·  config/standard_reports.csv"),
        BorderLayout.SOUTH);
    for (int i = 0; i < rows.size(); i++) {   // the first report that can be made, selected
      if (rows.get(i) instanceof StdReport && ((StdReport) rows.get(i)).why == null) { table.setRowSelectionInterval(i, i); break; }
    }
    return Ui.sheet("Standard reports", "", body, ok, null);
  }

  /** Colours of a surface type tag: IN green, OUT / OPEN amber, WALL grey, SYM / IF blue. */
  static java.awt.Color[] tagColors(String tok) {
    if (tok == null) return new java.awt.Color[] {java.awt.Color.WHITE, java.awt.Color.GRAY};
    switch (tok) {
      case "IN": return new java.awt.Color[] {new java.awt.Color(0xDD, 0xF2, 0xDF), new java.awt.Color(0x2E, 0x6B, 0x2E)};
      case "OUT": case "OPEN": return new java.awt.Color[] {new java.awt.Color(0xFF, 0xF1, 0xD6), new java.awt.Color(0x8A, 0x5A, 0x00)};
      case "WALL": return new java.awt.Color[] {new java.awt.Color(0xEC, 0xEF, 0xF3), new java.awt.Color(0x4A, 0x55, 0x63)};
      default: return new java.awt.Color[] {new java.awt.Color(0xDC, 0xEB, 0xFF), new java.awt.Color(0x1F, 0x4E, 0x9A)};
    }
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
  /** Puts objects in a folder (group) of their node in the tree; objects already in a folder stay where they are.
   *  Three ways are tried (the API differs between nodes and releases); the log says what was done. */
  void toFolder(Object manager, String folder, List<?> objs) {
    try {
      final star.base.neo.ClientServerObjectGroupManager gm = (star.base.neo.ClientServerObjectGroupManager) Reflect.invoke(manager, "getGroupsManager");
      List<star.base.neo.ClientServerObject> loose = new ArrayList<>();
      for (Object o : objs) {
        if (o instanceof star.base.neo.ClientServerObject && !gm.findGroup((star.base.neo.ClientServerObject) o).isPresent()) {
          loose.add((star.base.neo.ClientServerObject) o);
        }
      }
      if (loose.isEmpty()) return;
      boolean exists = false;
      for (Object g0 : gm.getObjects()) exists |= folder.equals(nm(g0));
      String how;
      if (exists) {   // the folder is there already: add to it (no "Standard reports 2")
        for (star.base.neo.ClientServerObject o : loose) Reflect.invoke(manager, "addToGroup", folder, o);
        how = "added";
      } else {
        gm.groupObjects(folder, loose);
        how = "new";
      }
      java.util.Optional<star.base.neo.ClientServerObjectGroup> g = gm.findGroup(loose.get(0));
      log.line("  folder \"" + folder + "\" (" + nm(manager) + "): " + (g.isPresent() ? loose.size() + " object(s) in " + nm(g.get()) + " (" + how + ")"
          : "NOT made (the objects stay where they are)"));
    } catch (Throwable t) {
      log.na("folder " + folder, t);
    }
  }

  /** One monitor per report and one plot showing them all; returns {monitors, plot or null}. */
  Object[] monitorsAndPlot(List<Object> reports, String plot) {
    List<Monitor> monitors = new ArrayList<>();
    for (Object r : reports) {
      try { monitors.add(((Report) r).createMonitor()); }                                                // [U24]
      catch (Throwable t) { log.na("monitor of " + nm(r), t); }
    }
    if (plot == null || monitors.isEmpty()) {
      log.line("  monitors for " + monitors.size() + " report(s)");
      return new Object[] {monitors, null};
    }
    try {
      MonitorPlot p = sim.getPlotManager().createMonitorPlot(monitors, plot);                             // [U24]
      if (!plot.equals(nm(p))) p.setPresentationName(plot);
      log.line("  monitors + plot " + plot + " (" + monitors.size() + " report(s))");
      return new Object[] {monitors, p};
    } catch (Throwable t) {
      log.na("plot " + plot + " (monitors were created)", t);
      return new Object[] {monitors, null};
    }
  }

  // =====================================================================================================
  // Mesh study (mesh sensitivity with one factor: parametrize, then one run per level)
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
    scanMesh(found, relative, already);
    StringBuilder info = new StringBuilder();
    if (!relative.isEmpty()) info.append("Relative to the base size (follow it automatically, nothing to do):\n  ")
        .append(String.join("\n  ", relative)).append('\n');
    if (!already.isEmpty()) info.append("Already driven by a parameter or expression (left as they are):\n  ")
        .append(String.join("\n  ", already)).append('\n');
    List<MeshSetting> todo = new ArrayList<>();
    for (MeshSetting m : found) if (m.kind.equals("size")) todo.add(m);   // batch: prism layers keep their thickness
    if (todo.isEmpty()) {
      log.line("no absolute mesh size to parametrize.\n" + info);
      return;
    }
    int done = linkSizes(todo);
    result(done + " mesh size(s) now follow " + MESH_SCALE + "  (Details: the list)");
    log.line(done + " mesh size(s) now follow " + MESH_SCALE + ".\n" + info);
  }

  /** Every absolute mesh length of the automated mesh operations (default values, custom controls, custom prism
   *  values); relative ones and those already set by a parameter or expression go to the two lists. */
  void scanMesh(List<MeshSetting> found, List<String> relative, List<String> already) {
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
  }

  /** Each setting gets its own parameter p_mesh_... (today's value) and the definition ${p_mesh_...} * ${p_meshScale};
   *  p_meshScale = 1 keeps today's mesh. Returns how many were linked. */
  int linkSizes(List<MeshSetting> todo) {
    ScalarGlobalParameter scale = findParam(MESH_SCALE);
    if (scale == null) {
      scale = newParam(MESH_SCALE, null, 1.0, null);
      log.line("  created " + MESH_SCALE + " = 1 (dimensionless)");
    }
    int done = 0;
    for (MeshSetting m : todo) {
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
    return done;
  }

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

  /** Mesh study (one window): the levels (coarse, medium, fine ...) as a table of mesh factors, the mesh sizes that
   *  follow the factor, then one case per level next to this one, meshed and saved. */
  void toolMeshStudy() throws IOException {
    CaseInfo c = caseOrNull();
    if (!gui) {
      if (c == null) throw new Cancel("mesh levels are saved as runs of a project: start a project first");
      if (meshParams().isEmpty() || findParam(MESH_SCALE) == null) toolParametrizeMesh();
      if (meshParams().isEmpty() || findParam(MESH_SCALE) == null) throw new Cancel("no mesh size follows " + MESH_SCALE + ": nothing to vary");
      createMeshRuns(c, meshParams(), new ArrayList<>(Arrays.asList(new MeshLevel("coarse", 2.0), new MeshLevel("medium", 1.0))), true);
      return;
    }
    if (c == null) {
      boolean go = Ui.sheet("Mesh study", "Needs a project", Ui.muted("Levels are saved as runs."),
          Ui.primary("New project..."), null);
      if (!go) throw new Cancel(null);
      toolSave();
      c = caseOrNull();
      if (c == null) throw new Cancel(null);
    }
    final List<MeshSetting> found = new ArrayList<>();
    List<String> relative = new ArrayList<>(), already = new ArrayList<>();
    scanMesh(found, relative, already);
    final Map<String, ScalarGlobalParameter> linked = meshParams();
    if (found.isEmpty() && linked.isEmpty()) {
      throw new Cancel("no absolute mesh size in the automated mesh operations (sizes relative to the base size only, or none): nothing to vary");
    }
    final CaseInfo cc = c;
    Object[] r = Ui.edt(() -> meshWindow(cc, found, linked));
    if (r == null) throw new Cancel(null);
    @SuppressWarnings("unchecked") List<MeshSetting> link = (List<MeshSetting>) r[0];
    @SuppressWarnings("unchecked") List<MeshLevel> levels = (List<MeshLevel>) r[1];
    if (!link.isEmpty()) log.line(linkSizes(link) + " mesh size(s) now follow " + MESH_SCALE);
    if (meshParams().isEmpty() || findParam(MESH_SCALE) == null) throw new Cancel("no mesh size follows " + MESH_SCALE + ": nothing to vary");
    if ((Boolean) r[3]) {
      result("mesh sizes follow " + MESH_SCALE + " (" + meshParams().size() + "); no level created");
      return;
    }
    createMeshRuns(c, meshParams(), levels, (Boolean) r[2]);
  }

  /** The mesh study window (Swing thread). Returns {sizes to link, levels, mesh now, sizes only} or null. */
  Object[] meshWindow(final CaseInfo c, final List<MeshSetting> found, final Map<String, ScalarGlobalParameter> linked) {
    // the base size (for the "cell size" column): a linked one, else a found one
    double b0 = Double.NaN;
    String bu = "";
    for (Map.Entry<String, ScalarGlobalParameter> e : linked.entrySet()) {
      if (Double.isNaN(b0) && e.getKey().endsWith("baseSize")) {
        b0 = e.getValue().getQuantity().getValue();
        try { bu = nm(e.getValue().getQuantity().getUnits()); } catch (Throwable t) { /* none */ }
      }
    }
    for (MeshSetting m : found) if (Double.isNaN(b0) && m.what.equals("baseSize")) { b0 = m.raw; bu = nm(m.units); }
    final double base = b0;
    final String unit = bu;
    // levels
    final javax.swing.table.DefaultTableModel model = new javax.swing.table.DefaultTableModel(new String[] {"", "level", "mesh factor",
        "base size" + (unit.isEmpty() ? "" : " [" + unit + "]")}, 0) {
      private static final long serialVersionUID = 1L;
      @Override public Class<?> getColumnClass(int col) { return col == 0 ? Boolean.class : String.class; }
      @Override public boolean isCellEditable(int row, int col) { return col < 3; }
      @Override public void setValueAt(Object v, int row, int col) {
        super.setValueAt(v, row, col);
        if (col == 2) {
          double k = Fmt.parse(String.valueOf(v));
          super.setValueAt(Double.isNaN(base) || Double.isNaN(k) ? "" : shortNum(base * k), row, 3);
        }
      }
    };
    final java.util.function.BiConsumer<String, Double> addRow = (n, k) -> {
      model.addRow(new Object[] {Boolean.TRUE, n, "", ""});
      model.setValueAt(shortNum(k), model.getRowCount() - 1, 2);
    };
    addRow.accept("coarse", 1.41);
    addRow.accept("medium", 1.0);
    addRow.accept("fine", 0.71);
    final javax.swing.JTable table = new javax.swing.JTable(model);
    table.setRowHeight(24);
    table.setShowVerticalLines(false);
    table.setGridColor(new java.awt.Color(0xEC, 0xEE, 0xF1));
    table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
    table.getColumnModel().getColumn(0).setMaxWidth(30);
    javax.swing.table.DefaultTableCellRenderer right = new javax.swing.table.DefaultTableCellRenderer();
    right.setHorizontalAlignment(javax.swing.SwingConstants.RIGHT);
    table.getColumnModel().getColumn(2).setCellRenderer(right);
    javax.swing.table.DefaultTableCellRenderer greyR = new javax.swing.table.DefaultTableCellRenderer();
    greyR.setHorizontalAlignment(javax.swing.SwingConstants.RIGHT);
    greyR.setForeground(Ui.MUTED);
    table.getColumnModel().getColumn(3).setCellRenderer(greyR);
    JScrollPane sp = new JScrollPane(table);
    sp.setPreferredSize(new java.awt.Dimension(440, 24 * 4 + 28));
    sp.getViewport().setBackground(java.awt.Color.WHITE);
    final JComboBox<String> ratio = new JComboBox<>(new String[] {"1.41", "1.5", "2", "1.3", "1.2"});
    ratio.setEditable(true);
    final Runnable applyRatio = () -> {
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
    };
    ratio.addActionListener(e -> applyRatio.run());
    JButton add = Ui.link("+ Add level");
    add.addActionListener(e -> {
      if (table.isEditing()) table.getCellEditor().stopCellEditing();
      double last = model.getRowCount() == 0 ? 1 : Fmt.parse(String.valueOf(model.getValueAt(model.getRowCount() - 1, 2)));
      double rr = Fmt.parse(String.valueOf(ratio.getEditor().getItem()));
      addRow.accept("level" + (model.getRowCount() + 1), (Double.isNaN(last) ? 1 : last) / (rr > 1 ? rr : 1.41));
    });
    JButton del = Ui.link("Remove");
    del.addActionListener(e -> {
      int i = table.getSelectedRow();
      if (i < 0) return;
      if (table.isEditing()) table.getCellEditor().cancelCellEditing();
      model.removeRow(i);
    });
    JPanel tools = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));
    tools.add(add);
    tools.add(del);
    tools.add(javax.swing.Box.createHorizontalStrut(16));
    tools.add(Ui.muted("refinement ratio"));
    tools.add(ratio);
    // sizes that follow the factor
    final List<javax.swing.JCheckBox> boxes = new ArrayList<>();
    final javax.swing.JCheckBox prism = new javax.swing.JCheckBox("Scale prism layers too  (off: same y+)");
    JPanel sizes = new JPanel(new GridLayout(0, 1, 0, 1));
    for (String n : linked.keySet()) {
      javax.swing.JCheckBox b = new javax.swing.JCheckBox(n.substring("p_mesh_".length()) + "   (already follows the factor)", true);
      b.setEnabled(false);
      sizes.add(b);
    }
    for (MeshSetting m : found) {
      javax.swing.JCheckBox b = new javax.swing.JCheckBox(m.op + (m.control.isEmpty() ? "" : " / " + m.control) + "   " + m.what
          + "   " + shortNum(m.raw) + " " + nm(m.units), m.kind.equals("size"));
      b.putClientProperty("prism", m.kind.equals("prism"));
      b.setEnabled(m.kind.equals("size"));
      boxes.add(b);
      sizes.add(b);
    }
    boolean anyPrism = false;
    for (MeshSetting m : found) anyPrism |= m.kind.equals("prism");
    prism.addActionListener(e -> {
      for (javax.swing.JCheckBox b : boxes) {
        if (!Boolean.TRUE.equals(b.getClientProperty("prism"))) continue;
        b.setEnabled(prism.isSelected());
        b.setSelected(prism.isSelected());
      }
    });
    final javax.swing.JCheckBox meshNow = new javax.swing.JCheckBox("Mesh now (cell counts)", true);
    final JLabel files = new JLabel(" ");
    final JButton ok = Ui.primary("Create levels");
    final boolean[] only = {false};
    final Runnable upd = () -> {
      int n = 0;
      List<String> names = new ArrayList<>();
      for (int i = 0; i < model.getRowCount(); i++) {
        if (Boolean.TRUE.equals(model.getValueAt(i, 0))) { n++; names.add(String.valueOf(model.getValueAt(i, 1)).trim()); }
      }
      ok.setText(n == 1 ? "Create 1 level" : "Create " + n + " levels");
      int no = nextRun(c.caseDir);
      List<String> fs = new ArrayList<>();
      for (int i = 0; i < names.size() && i < 4; i++) fs.add(runId(no + i) + "_" + names.get(i) + ".sim");
      files.setText(String.join(",  ", fs) + (names.size() > 4 ? ", ..." : ""));
    };
    model.addTableModelListener(e -> upd.run());
    upd.run();
    JButton onlyLink = Ui.link("Link sizes only (no levels)");
    onlyLink.setToolTipText("The sizes ticked below follow one parameter, " + MESH_SCALE + " (1 = today's mesh); vary it yourself later");
    onlyLink.addActionListener(e -> {
      only[0] = true;
      ok.doClick();
    });

    JPanel body = new JPanel();
    body.setLayout(new javax.swing.BoxLayout(body, javax.swing.BoxLayout.Y_AXIS));
    JLabel h1 = new JLabel("Levels");
    h1.setFont(h1.getFont().deriveFont(Font.BOLD));
    JLabel n1 = Ui.muted("Factor 1 = current mesh  \u00B7  1.41 = cells 1.41\u00D7 larger");
    JLabel h2 = new JLabel("Sizes scaled by the factor");
    h2.setFont(h2.getFont().deriveFont(Font.BOLD));
    JLabel n2 = Ui.muted("Relative sizes follow the base size.");
    List<javax.swing.JComponent> parts = new ArrayList<>(Arrays.asList(h1, n1, sp, tools, h2, n2, sizes));
    if (anyPrism) parts.add(prism);
    javax.swing.JSeparator sep = new javax.swing.JSeparator();
    parts.addAll(Arrays.asList(meshNow, sep, files, onlyLink));
    for (javax.swing.JComponent k : parts) {
      k.setAlignmentX(0f);
      if (k == h2 || k == meshNow || k == sep || k == onlyLink) body.add(javax.swing.Box.createVerticalStrut(12));
      body.add(k);
      body.add(javax.swing.Box.createVerticalStrut(4));
    }
    final List<MeshLevel> out = new ArrayList<>();
    boolean go = Ui.sheet("Mesh study", "Levels from " + c.caseId + (c.label.isEmpty() ? "" : " " + c.label),
        body, ok, () -> {
          if (table.isEditing()) table.getCellEditor().stopCellEditing();
          boolean any = !linked.isEmpty();
          for (javax.swing.JCheckBox b : boxes) any |= b.isSelected();
          if (!any) { only[0] = false; return "Tick at least one size that follows the factor."; }
          if (only[0]) return null;
          out.clear();
          Set<String> seen = new HashSet<>();
          for (int i = 0; i < model.getRowCount(); i++) {
            if (!Boolean.TRUE.equals(model.getValueAt(i, 0))) continue;
            String n = String.valueOf(model.getValueAt(i, 1)).trim();
            double k = Fmt.parse(String.valueOf(model.getValueAt(i, 2)));
            if (n.isEmpty()) return "Row " + (i + 1) + ": give the level a name (it goes in the file name).";
            try { n = runName(n); } catch (Cancel x) { return "Level '" + n + "': letters, digits, _ and - only."; }
            if (!seen.add(n)) return "Two levels are called '" + n + "'.";
            if (!(k > 0)) return "Level '" + n + "': the mesh factor must be a number above 0.";
            out.add(new MeshLevel(n, k));
          }
          return out.isEmpty() ? "Tick at least one level." : null;
        });
    if (!go) return null;
    List<MeshSetting> link = new ArrayList<>();
    for (int i = 0; i < found.size(); i++) if (boxes.get(i).isSelected()) link.add(found.get(i));
    return new Object[] {link, out, meshNow.isSelected(), only[0]};
  }

  /** Mesh levels in a study: one run per level (R008_coarse, R009_medium ...), each based on the run you started from,
   *  with mesh_study, mesh_level, mesh_scale and mesh_cells in its record. The open file stays as it is on disk. */
  void createMeshRuns(CaseInfo c, Map<String, ScalarGlobalParameter> params, List<MeshLevel> levels, boolean mesh) throws IOException {
    ScalarGlobalParameter scale = findParam(MESH_SCALE);
    double scale0 = scale.getQuantity().getValue();
    String study = c.caseId + " " + Fmt.iso();
    star.meshing.MeshPipelineController pipe = sim.get(star.meshing.MeshPipelineController.class);
    List<String> made = new ArrayList<>();
    CaseInfo last = null;
    log.line("mesh study from " + c.caseId + ": " + levels.size() + " level(s)" + (mesh ? ", meshed now" : ", saved without a mesh"));
    for (MeshLevel L : levels) {
      scale.getQuantity().setValue(L.scale);
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
      Map<String, String> extra = new LinkedHashMap<>();
      extra.put("mesh_study", study);
      extra.put("mesh_level", L.name);
      extra.put("mesh_scale", shortNum(L.scale));
      if (!cells.isEmpty()) extra.put("mesh_cells", cells);
      CaseInfo n = saveRun(c.caseDir, L.name, "mesh study: " + L.name + " (mesh factor " + shortNum(L.scale) + ")", c, extra,
          "mesh_study_" + c.caseId);
      made.add((n == null ? "?" : n.caseId) + " " + L.name + (cells.isEmpty() ? "" : " " + cells + " cells"));
      last = n;
    }
    log.line("mesh levels saved as runs; " + c.simFile.getFileName() + " is unchanged on disk (" + MESH_SCALE + " = " + shortNum(scale0)
        + ").");
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

  // =====================================================================================================
  // Calculators (lib/generic/SimKitCalc.java)
  // =====================================================================================================

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
  // Configuration (small CSV files in _SIMKIT/config, built-in defaults when missing)
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

  // =====================================================================================================
  // Batch tests (SIMKIT_ACTION studytest, opensim)
  // =====================================================================================================

  /** Batch test: a study next to the case of the test sim, R001 and R002 saved, a submission prepared (no bsub). */
  void testStudy() throws IOException {
    if (sessionPath().isEmpty()) throw new Cancel("the simulation has not been saved yet");
    CaseInfo c = runOrNull();
    Path base = c != null && c.caseDir.getParent() != null ? c.caseDir.getParent() : Paths.get(sessionPath()).toAbsolutePath().getParent();
    Path dir = base.resolve("STUDY_TEST_" + new SimpleDateFormat("HHmmss").format(new Date()));
    startStudy(dir, "base", "test study");
    CaseInfo r1 = requireCase();
    log.line("open run " + r1.caseId + " (" + P_RUN + " = " + findParam(P_RUN).getQuantity().getValue() + "), file " + r1.simFile.getFileName());
    try {
      GlobalParameterManager pm = sim.get(GlobalParameterManager.class);
      List<String> fold = new ArrayList<>();
      for (Object gr : pm.getGroupsManager().getObjects()) {
        List<String> in = new ArrayList<>();
        for (Object o : ((star.base.neo.ClientServerObjectManager<?>) gr).getObjects()) in.add(nm(o));
        fold.add(nm(gr) + " " + in);
      }
      log.line("parameter folders: " + fold + "; top level: " + pm.getObjects().size() + " object(s)");
    } catch (Throwable t) { log.na("parameter folders", t); }
    saveRun(dir, "finer", "test: second run");
    CaseInfo r2 = requireCase();
    log.line("open run " + r2.caseId + ", based on " + readIdentity(r2).get("based_on") + " at iteration "
        + readIdentity(r2).get("from_iteration") + "; auto-save separator "
        + sim.getSimulationIterator().getAutoSave().getSeparator());
    String[] cmd = submitCommand(r2.simFile, 4);
    log.line("submit command (not run): " + cmd[0]);
    log.line("submit script: " + writeSubmitScript(r2.simFile, cmd[0]));
    // a copy as the cluster sends it back: a job folder, the file renamed with _iter_
    Path back = r2.simFile.getParent().resolve("job_12345_host_07").resolve(r2.simFile.getFileName().toString().replace(".sim", ITER_SEP + "00050.sim"));
    Fs.mkdirs(back.getParent());
    Files.copy(r2.simFile, back, StandardCopyOption.REPLACE_EXISTING);
    setIdentityKey(dir, dir.getFileName().toString(), "comment_v002", "**finer** mesh near the liner");
    // mesh study inside the study: runs coarse and medium, based on R002
    if (meshParams().isEmpty() || findParam(MESH_SCALE) == null) toolParametrizeMesh();
    if (!meshParams().isEmpty() && findParam(MESH_SCALE) != null) {
      createMeshRuns(r2, meshParams(), new ArrayList<>(Arrays.asList(new MeshLevel("coarse", 2.0), new MeshLevel("medium", 1.0))), true);
    }
    for (String[] r : trackerRows(dir)) {
      log.line(String.format("study row %s  file %-40s based on %-8s comment %s", runId(runOf(r[COL_ID])), r[COL_FILE],
          r[COL_BASED].isEmpty() ? "-" : runOf(r[COL_BASED]) > 0 ? runId(runOf(r[COL_BASED])) : r[COL_BASED], r[COL_COMMENT].replace("\n", " | ")));
    }
    log.line("study row label of the returned file: " + fileLabel(back.getFileName().toString()));
    writeTrackerCsv(dir, trackerRows(dir));
    log.line("study tracker csv: " + dir.resolve(TRACKER_CSV));
    // Save as new run > "Root of a new study instead": the open run becomes R001 of a new study
    CaseInfo before = requireCase();
    Path dir2 = dir.resolveSibling(dir.getFileName() + "_branch");
    startStudy(dir2, "branch", "test: new study from " + before.caseId);
    log.line("new study from a run: " + requireCase().caseId + " based on " + readIdentity(requireCase()).get("based_on"));
  }

  /** Batch test of the tracker's Open in STAR-CCM+: the open file is reported as already open; another run of the
   *  project is loaded, reported and closed again. */
  void testOpenSim() {
    CaseInfo c = requireCase();
    log.line("open sim: " + c.simFile.getFileName() + " -> " + (openSim(c.simFile) == null ? "already open (not loaded twice)" : "LOADED AGAIN"));
    try {
      loadDialog(c.simFile, false);
      log.line("open sim: STAR-CCM+ Load dialog found (OpenOptionsDialog, setSimFile, show)");
    } catch (Throwable t) {
      log.line("open sim: STAR-CCM+ Load dialog NOT found (" + reason(t) + "): the tracker loads with default options");
    }
    for (String[] r : trackerRows(c.caseDir)) {
      Path f = fileOf(c.caseDir, r);
      if (Fs.same(f, c.simFile)) continue;
      Simulation s = openSim(f);
      log.line("open sim: " + f.getFileName() + " -> " + (s == null ? "already open" : "loaded as '" + s.getPresentationName()
          + "', simulations open: " + SimulationManager.getSingleton().simulations().size()));
      if (s != null) s.close(star.base.neo.ServerConnection.CloseOption.Close);
      log.line("open sim: closed again, simulations open: " + SimulationManager.getSingleton().simulations().size());
      return;
    }
    log.line("open sim: no other run in " + c.caseDir + " to open");
  }

  // =====================================================================================================
  // Helpers
  // =====================================================================================================

  static String nm(Object o) {
    try { return ((ClientServerObject) o).getPresentationName(); } catch (Throwable t) { return String.valueOf(o); }
  }

  static String cls(Object o) { return o == null ? "none" : o.getClass().getSimpleName(); }

  /** [U09] Plays another macro file inside this session. */
  void runMacroFile(Path p) {
    new StarScript(getActiveRootObject(), new java.io.File(p.toString())).play();                        // [U09]
  }

  static String reason(Throwable t) {
    String m = t.getMessage();
    return t.getClass().getSimpleName() + (m == null ? "" : ": " + Fmt.cut(m.split("\\r?\\n")[0], 160));
  }

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
    static Tool directTool() {
      String t = System.getProperty("simkit.tool");
      if (t == null || t.trim().isEmpty()) return null;
      System.clearProperty("simkit.tool");
      return Tool.of(t);
    }

    /** Marks a window as a SimKit window and applies the preference to it. */
    static void track(java.awt.Window w) {
      if (w instanceof javax.swing.RootPaneContainer) ((javax.swing.RootPaneContainer) w).getRootPane().putClientProperty("simkit.window", Boolean.TRUE);
      try { w.setAlwaysOnTop(false); } catch (Throwable t) { /* not supported by this window system */ }   // above STAR-CCM+ only (owner)
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
      throw new IOException("refused to write outside the project folder: " + p);
    }

    static boolean under(Path p, Path root) {
      String r = key(root), sep = java.io.File.separator;
      return key(p).startsWith(r.endsWith(sep) ? r : r + sep);
    }

    /** Writes a whole file in one step: the text goes to a temporary file in the same folder, which then replaces the
     *  file (atomic rename where the file system has it). A reader sees the old or the new file, never a part of it;
     *  a crash leaves the old file. Windows refuses the replace while someone else is reading the file: retried for
     *  up to 2 s, then the file is written directly (as before). */
    static void write(Path p, String text) throws IOException {
      Path g = guard(p);
      Files.createDirectories(g.getParent());
      byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
      Path tmp = g.resolveSibling("." + g.getFileName() + ".simkit-" + Long.toHexString(System.nanoTime()) + ".tmp");
      try {
        Files.write(tmp, bytes, java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE);
        for (int k = 0; ; k++) {
          try {
            try {
              Files.move(tmp, g, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
              Files.move(tmp, g, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            break;
          } catch (java.nio.file.AccessDeniedException busy) {   // open by a reader (Windows)
            if (k >= 40) {
              Files.write(g, bytes);
              break;
            }
            try { Thread.sleep(50); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
          }
        }
      } finally {
        Files.deleteIfExists(tmp);   // only SimKit's own temporary file, when the move did not happen
      }
      Scan.clear(g);
    }

    static void mkdirs(Path p) throws IOException {
      Files.createDirectories(guard(p));
      Scan.clear(p);
    }

    /** A folder below dir given with '/' (same text on Windows and Linux). */
    static Path inDir(Path dir, String rel) {
      Path p = dir;
      for (String part : rel.split("/")) if (!part.isEmpty()) p = p.resolve(part);
      return p;
    }

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

  /** Light formatting of comments and descriptions, typed as plain marks so the text stays readable in
   *  case_identity.txt, the CSV files and Google Sheets: **bold**, *italic*, a line "- text" = bullet,
   *  a line "1. text" = numbered item. Shown formatted in the tracker and the case report. */
  static final class Md {
    static final Pattern BULLET = Pattern.compile("^\\s*[-*•]\\s+(.*)$");
    static final Pattern NUMBER = Pattern.compile("^\\s*(\\d+)[.)]\\s+(.*)$");
    /** Text colours: [red]text[/]. Only these names count, so brackets in ordinary text ([m/s]) stay as they are. */
    static final Map<String, String> COLORS = new LinkedHashMap<>();
    static {
      COLORS.put("red", "#C0392B");
      COLORS.put("orange", "#D35400");
      COLORS.put("green", "#2E7D32");
      COLORS.put("blue", "#1F5FAF");
      COLORS.put("purple", "#6A3D9A");
      COLORS.put("grey", "#6B7580");
    }
    static final Pattern COLOR = Pattern.compile("\\[(" + String.join("|", COLORS.keySet()) + ")\\](.+?)\\[/\\]");

    /** A piece of one line with its formatting (colour: a name of COLORS, or null). */
    static final class Run {
      final String text;
      final boolean b, i, u;
      final String color;

      Run(String text, boolean b, boolean i, boolean u, String color) {
        this.text = text;
        this.b = b;
        this.i = i;
        this.u = u;
        this.color = color == null || color.isEmpty() ? null : color;
      }

      boolean same(Run o) { return b == o.b && i == o.i && u == o.u && java.util.Objects.equals(color, o.color); }
    }

    static boolean word(String s, int k) { return k >= 0 && k < s.length() && Character.isLetterOrDigit(s.charAt(k)); }

    static boolean space(String s, int k) { return k < 0 || k >= s.length() || Character.isWhitespace(s.charAt(k)); }

    static int run(String s, int k) {
      int n = 1;
      while (k + n < s.length() && s.charAt(k + n) == s.charAt(k)) n++;
      return n;
    }

    /** A mark later on the line that can close bold (2), italic (1) or underline ('_'). */
    static boolean closerAfter(String s, int from, char c, int what) {
      for (int k = from; k < s.length(); ) {
        if (s.charAt(k) != c) { k++; continue; }
        int n = run(s, k);
        boolean fits = c == '_' ? n == 2 : n <= 3 && (what == 2 ? n >= 2 : n != 2);
        if (fits && !space(s, k - 1)) return true;
        k += n;
      }
      return false;
    }

    /** One line read into formatted pieces. The marks switch on and off: ** bold, * italic (*** both), __ underline,
     *  [red] ... [/] a colour. A mark counts only where it can open (text right after it and a closing mark later) or
     *  close (text right before it); anywhere else it is ordinary text (2*3, a ** b, [m/s]). */
    static List<Run> runs(String s) {
      Set<Integer> plainAt = new HashSet<>();   // opening marks that were never closed: ordinary text
      for (int pass = 0; ; pass++) {
        List<Run> out = new ArrayList<>();
        boolean b = false, it = false, u = false;
        String col = null;
        int bAt = -1, iAt = -1, uAt = -1, cAt = -1;
        StringBuilder t = new StringBuilder();
        int k = 0;
        while (k < s.length()) {
          char c = s.charAt(k);
          int n = c == '*' || c == '_' ? run(s, k) : 1;
          boolean mark = false;
          if (!plainAt.contains(k) && pass < 50) {
            if (c == '*' && n <= 3) {
              boolean wb = n >= 2, wi = n != 2, ok = true;
              if (wb) ok &= b ? !space(s, k - 1) : !space(s, k + n) && closerAfter(s, k + n, '*', 2);
              if (wi) ok &= it ? !space(s, k - 1) && (n == 3 || !word(s, k + n))
                  : !space(s, k + n) && closerAfter(s, k + n, '*', 1) && (n == 3 || !word(s, k - 1));
              if (ok) {
                mark = true;
                flush(out, t, b, it, u, col);
                if (wb) { b = !b; bAt = k; }
                if (wi) { it = !it; iAt = k; }
              }
            } else if (c == '_' && n == 2) {
              if (u ? !space(s, k - 1) : !space(s, k + n) && closerAfter(s, k + n, '_', 0)) {
                mark = true;
                flush(out, t, b, it, u, col);
                u = !u;
                uAt = k;
              }
            } else if (c == '[') {
              if (col != null && s.startsWith("[/]", k)) {
                mark = true;
                flush(out, t, b, it, u, col);
                col = null;
                n = 3;
              } else if (col == null) {
                int e = s.indexOf(']', k);
                String name = e > k ? s.substring(k + 1, e) : "";
                if (COLORS.containsKey(name) && s.indexOf("[/]", e) > e) {
                  mark = true;
                  flush(out, t, b, it, u, col);
                  col = name;
                  cAt = k;
                  n = e + 1 - k;
                }
              }
            }
          }
          if (!mark) t.append(s, k, k + n);
          k += n;
        }
        flush(out, t, b, it, u, col);
        if (!b && !it && !u && col == null) return out;
        if (b) plainAt.add(bAt);   // an opening mark that is still open at the end: read it as text, then again
        if (it) plainAt.add(iAt);
        if (u) plainAt.add(uAt);
        if (col != null) plainAt.add(cAt);
      }
    }

    static void flush(List<Run> out, StringBuilder t, boolean b, boolean i, boolean u, String col) {
      if (t.length() == 0) return;
      out.add(new Run(t.toString(), b, i, u, col));
      t.setLength(0);
    }

    /** Formatted pieces back into one line with marks (the reverse of runs). A space at the edge of a formatted piece
     *  is left out of it, and italic inside a word takes the whole word, so that the marks read back the same. */
    static String line(List<Run> rs) {
      StringBuilder all = new StringBuilder();
      for (Run r : rs) all.append(r.text);
      String t = all.toString();
      int n = t.length();
      boolean[] B = new boolean[n], I = new boolean[n], U = new boolean[n];
      String[] C = new String[n];
      int k = 0;
      for (Run r : rs) for (int j = 0; j < r.text.length(); j++, k++) { B[k] = r.b; I[k] = r.i; U[k] = r.u; C[k] = r.color; }
      for (k = 0; k < n; k++) {
        if (!Character.isWhitespace(t.charAt(k))) continue;
        int p = k - 1, q = k + 1;
        while (p >= 0 && Character.isWhitespace(t.charAt(p))) p--;
        while (q < n && Character.isWhitespace(t.charAt(q))) q++;
        boolean in = p >= 0 && q < n;
        B[k] = in && B[p] && B[q];
        I[k] = in && I[p] && I[q];
        U[k] = in && U[p] && U[q];
        C[k] = in && C[p] != null && C[p].equals(C[q]) ? C[p] : null;
      }
      for (k = 0; k < n; k++) {   // italic: whole words
        if (!I[k]) continue;
        int a = k, e = k;
        while (e < n && I[e]) e++;
        while (a > 0 && Character.isLetterOrDigit(t.charAt(a - 1))) I[--a] = true;
        while (e < n && Character.isLetterOrDigit(t.charAt(e))) I[e++] = true;
        k = e;
      }
      StringBuilder o = new StringBuilder();
      for (k = 0; k <= n; k++) {
        boolean pb = k > 0 && B[k - 1], pi = k > 0 && I[k - 1], pu = k > 0 && U[k - 1];
        boolean cb = k < n && B[k], ci = k < n && I[k], cu = k < n && U[k];
        String pc = k > 0 ? C[k - 1] : null, cc = k < n ? C[k] : null;
        if (pc != null && !pc.equals(cc)) o.append("[/]");
        if (pu != cu) o.append("__");
        if (pb != cb) o.append("**");
        if (pi != ci) o.append('*');
        if (cc != null && !cc.equals(pc)) o.append('[').append(cc).append(']');
        if (k < n) o.append(t.charAt(k));
      }
      return o.toString();
    }

    /** One line: HTML-escaped, with **bold**, __underline__, *italic* and [colour]text[/]. */
    static String inline(String s) {
      StringBuilder h = new StringBuilder();
      for (Run r : runs(s)) {
        String x = Html.esc(r.text);
        if (r.i) x = "<i>" + x + "</i>";
        if (r.b) x = "<b>" + x + "</b>";
        if (r.u) x = "<u>" + x + "</u>";
        if (r.color != null) x = "<font color='" + COLORS.get(r.color) + "'>" + x + "</font>";
        h.append(x);
      }
      return h.toString();
    }

    static String unmark(String s) {
      StringBuilder t = new StringBuilder();
      for (Run r : runs(s)) t.append(r.text);
      return t.toString();
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
        parts.add(unmark(t));
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

    static final java.awt.Color BLUE = new java.awt.Color(0x1F, 0x5F, 0xAF), MUTED = new java.awt.Color(0x6B, 0x75, 0x80),
        RED = new java.awt.Color(0xB0, 0x20, 0x20), LINE = new java.awt.Color(0xD5, 0xD9, 0xDE), PALE = new java.awt.Color(0xF0, 0xF6, 0xFD);

    /** The one main button of a window: blue, white bold text. */
    static JButton primary(String text) {
      JButton b = new JButton(text);
      b.setFont(b.getFont().deriveFont(Font.BOLD));
      b.setForeground(java.awt.Color.WHITE);
      b.setBackground(BLUE);
      b.setOpaque(true);
      b.setBorderPainted(false);
      b.setFocusPainted(false);
      b.setMargin(new java.awt.Insets(5, 16, 5, 16));
      return b;
    }

    /** A button that looks like a link. */
    static JButton link(String text) {
      JButton b = new JButton(text);
      b.setForeground(BLUE);
      b.setContentAreaFilled(false);
      b.setBorderPainted(false);
      b.setFocusPainted(false);
      b.setMargin(new java.awt.Insets(0, 2, 0, 2));
      b.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
      return b;
    }

    static JLabel muted(String text) {
      JLabel l = new JLabel(text);
      l.setForeground(MUTED);
      return l;
    }

    /** A text field with a grey hint while it is empty. */
    static JTextField hintField(String text, final String hint, int cols) {
      return new JTextField(text, cols) {
        private static final long serialVersionUID = 1L;
        @Override protected void paintComponent(java.awt.Graphics g) {
          super.paintComponent(g);
          if (getText().isEmpty() && !isFocusOwner()) {
            java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
            g2.setColor(new java.awt.Color(0xA8, 0xB0, 0xBA));
            g2.setFont(getFont().deriveFont(Font.ITALIC));
            java.awt.FontMetrics fm = g2.getFontMetrics();
            g2.drawString(hint, getInsets().left + 2, (getHeight() + fm.getAscent() - fm.getDescent()) / 2);
            g2.dispose();
          }
        }
      };
    }

    /** White panels everywhere (a component with the client property "simkit.bg" keeps its own colour). */
    static void white(java.awt.Component c) {
      if (c instanceof javax.swing.JComponent && ((javax.swing.JComponent) c).getClientProperty("simkit.bg") != null) return;
      if (c instanceof JPanel || c instanceof javax.swing.JCheckBox || c instanceof javax.swing.JRadioButton) c.setBackground(java.awt.Color.WHITE);
      if (c instanceof java.awt.Container) for (java.awt.Component k : ((java.awt.Container) c).getComponents()) white(k);
    }

    /** A clean window: title, grey line, the body, a red line for problems, Cancel and one blue button (Enter).
     *  'check' runs on the button: a message keeps the window open (nothing typed is lost), null closes it. */
    static boolean sheet(String title, String sub, javax.swing.JComponent body, final JButton ok, final java.util.function.Supplier<String> check) {
      return sheet(title, sub, body, ok, check, null);
    }

    /** The same, with a second, rarer action as a link at the bottom left (it closes the window like the main button). */
    static boolean sheet(String title, String sub, javax.swing.JComponent body, final JButton ok, final java.util.function.Supplier<String> check,
        final JButton extra) {
      return edt(() -> {
        final JDialog d = new JDialog(owner(), "SimKit", java.awt.Dialog.ModalityType.APPLICATION_MODAL);
        d.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        final boolean[] done = {false};
        JPanel root = new JPanel(new BorderLayout(0, 14));
        root.setBorder(BorderFactory.createEmptyBorder(16, 20, 14, 20));
        JPanel head = new JPanel();
        head.setLayout(new javax.swing.BoxLayout(head, javax.swing.BoxLayout.Y_AXIS));
        JLabel t = new JLabel(title);
        t.setFont(t.getFont().deriveFont(Font.BOLD, 17f));
        head.add(t);
        if (sub != null && !sub.isEmpty()) {
          head.add(javax.swing.Box.createVerticalStrut(3));
          head.add(muted(sub));
        }
        root.add(head, BorderLayout.NORTH);
        root.add(body, BorderLayout.CENTER);
        final JLabel msg = new JLabel(" ");
        msg.setForeground(RED);
        final boolean stay = Boolean.TRUE.equals(ok.getClientProperty("simkit.stay"));   // acts, the window stays open
        JButton cancel = new JButton(stay ? "Close" : "Cancel");
        cancel.addActionListener(e -> d.dispose());
        if (!stay) {
          ok.addActionListener(e -> {
            String m = check == null ? null : check.get();
            if (m != null) {
              msg.setText(m);
              return;
            }
            done[0] = true;
            d.dispose();
          });
        }
        JPanel btns = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 8, 0));
        btns.add(cancel);
        btns.add(ok);
        JPanel bottom = new JPanel(new BorderLayout(10, 0));
        if (extra != null) {
          bottom.add(extra, BorderLayout.WEST);
          JPanel col = new JPanel(new BorderLayout(0, 6));   // the message above the buttons
          col.add(msg, BorderLayout.NORTH);
          col.add(bottom, BorderLayout.CENTER);
          bottom.add(btns, BorderLayout.EAST);
          root.add(col, BorderLayout.SOUTH);
        } else {
          bottom.add(msg, BorderLayout.CENTER);
          bottom.add(btns, BorderLayout.EAST);
        }
        if (extra == null) root.add(bottom, BorderLayout.SOUTH);
        white(root);
        root.setBackground(java.awt.Color.WHITE);
        d.setContentPane(root);
        if (!stay) d.getRootPane().setDefaultButton(ok);
        d.getRootPane().registerKeyboardAction(e -> d.dispose(), javax.swing.KeyStroke.getKeyStroke("ESCAPE"),
            javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW);
        d.pack();
        d.setLocationRelativeTo(owner());
        Env.track(d);
        d.setVisible(true);
        return done[0];
      });
    }

    /** A choice drawn as a card: title and one grey line; the chosen one has a blue frame. */
    static JPanel card(String title, String line) {
      JPanel p = new JPanel();
      p.setLayout(new javax.swing.BoxLayout(p, javax.swing.BoxLayout.Y_AXIS));
      p.putClientProperty("simkit.bg", Boolean.TRUE);
      JLabel t = new JLabel(title);
      t.setFont(t.getFont().deriveFont(Font.BOLD, 14f));
      p.add(t);
      p.add(javax.swing.Box.createVerticalStrut(3));
      JLabel l = muted(line);
      p.add(l);
      p.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
      chosen(p, false);
      return p;
    }

    static void chosen(JPanel card, boolean on) {
      card.setBackground(on ? PALE : java.awt.Color.WHITE);
      card.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(on ? BLUE : LINE, on ? 2 : 1),
          BorderFactory.createEmptyBorder(on ? 9 : 10, on ? 13 : 14, on ? 9 : 10, on ? 13 : 14)));
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
