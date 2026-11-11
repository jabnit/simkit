// Engineering calculators: y+ and prism layers, dimensionless numbers, turbulence inlet, time step and statistics, GCI, fluid/material properties, heat transfer, combustion and spray, units
//
// SimKitCalc.java -- SimKit 0.2 (2026-11-11) -- UNTESTED IN STAR-CCM+ (written for Simcenter STAR-CCM+ 2402 / 19.02).
// Spec: SIMKIT_SPEC_v02.md section B. See _SIMKIT\TESTING.txt.
//
// Purpose
//   One window with 10 tabs of engineering calculators used while setting up and post-processing CFD cases:
//    1 Wall & prism layers     2 Dimensionless numbers    3 Turbulence inlet       4 Time step & statistics
//    5 Mesh & GCI              6 Fluid properties         7 Material properties    8 Heat transfer
//    9 Combustion & spray     10 Units
//   Every result is printed with its formula, its source and its validity range; inputs outside a validity range
//   give a WARNING (property data are clamped to [T_min, T_max] of the library row; correlations are evaluated as
//   they are and the extrapolation is stated).
//   Buttons: Compute (per sub-panel), From parameter... / From report... (fill an input from the open sim),
//   To parameter... (create/update a global parameter p_<name> from a result), Copy (results to clipboard),
//   Self-check (documented reference examples), Close.
//   Traceability: every Compute appends a row (timestamp, user, tab, inputs, outputs) to
//   <case>\audit\calc_log.csv when the sim lives in <SHARE>\PROJECTS\<PROJ>\03_cases\<CASEID>\sim\, and always
//   prints it to the Output window. Without a case folder nothing is written to disk.
//
// Version 0.2 (2026-11-11). The physics (class Core) and the data loading (class Library) have NO STAR-CCM+
// imports and were unit-tested with a plain JDK 11 compiler; the STAR-CCM+ calls are UNTESTED in STAR.
//
// Data (read at start; "Choose config folder..." on tab 6 reloads them):
//   <SHARE>\_SIMKIT\config\fluids.csv, materials.csv, emissivity.csv.  <SHARE> = env SIMKIT_SHARE, else
//   -Dsimkit.share, else SHARE_ROOT below (same rule as SimKit.java).
//
// Threading: the window is a modal dialog shown from the macro thread (like SimKit). Compute runs pure Java on the
//   Swing thread (only Simulation.println is called there, [U52]). Buttons that read or write the sim (From
//   parameter, From report, To parameter) hide the window, the macro thread does the STAR calls, then the window
//   re-opens with all inputs kept.
//
// Uncertain STAR-CCM+ API calls (each wrapped in try/catch(Throwable); failures are logged "n/a (<reason>)"):
//   [U10] GlobalParameterManager.getObjects(), createGlobalParameter(ScalarGlobalParameter.class, "Scalar");
//         ScalarGlobalParameter.getQuantity(); ScalarPhysicalQuantity.getRawValue(), getUnits(), setValue(double)
//   [U19] Simulation.getReportManager().getObjects(); Report.getReportMonitorValue()
//   [U30] Dimensions.Builder().mass(n).length(n).time(n).temperature(n).build()  (exponent 0 passed explicitly)
//   [U31] ScalarGlobalParameter.setDimensions(Dimensions); ScalarPhysicalQuantity.setUnits(Units);
//         Simulation.getUnitsManager().getObject("<unit name>")
//   [U50] ScalarPhysicalQuantity.getSIValue() [reflection]; fallback getRawValue() + units name (with a warning)
//   [U51] UnitsManager.getPreferredUnits(Dimensions) [reflection], used only if no unit name matches
//   [U52] Simulation.println(String) called from the Swing event thread on Compute; fallback System.out
//   [U53] Report.getUnits() [reflection], only to display the units of a report value
//   (U10/U19/U30/U31 are the same calls as in SimKit.java / ISO_parameters.java.)
//
// Rules (SimKit): Java 11, no external libraries, never deletes anything, writes only <case>\audit\calc_log.csv.
package macro;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;

import star.base.neo.*;
import star.base.report.*;
import star.common.*;

public class SimKitCalc extends StarMacro {

  static final String VERSION = "SimKitCalc 0.2 (2026-11-11)";
  /** Team share. Overridden by env SIMKIT_SHARE or -Dsimkit.share=... (same rule as SimKit.java). */
  static final String SHARE_ROOT_WINDOWS = "S:\\CFD";
  /** Team share on Linux (RHEL 7/9): mount point of the same share. Keep in step with SHARE_ROOT_WINDOWS. */
  static final String SHARE_ROOT_LINUX = "/mnt/cfd";
  /** Default share of this OS (SIMKIT_SHARE / -Dsimkit.share override it on both). */
  static final String SHARE_ROOT = java.io.File.separatorChar == '\\' ? SHARE_ROOT_WINDOWS : SHARE_ROOT_LINUX;
  static final Pattern CASE_ID = Pattern.compile("^([A-Z]{2,5})-([A-Z0-9]{2,3})-([0-9]{3})$");
  static final Pattern PARAM_NAME = Pattern.compile("^p_[a-z][A-Za-z0-9]*$");
  static final String[] CALC_LOG_HEADER = {"timestamp", "user", "tab", "inputs", "outputs"};
  static final String ENTERED = "(entered values)";

  // =====================================================================================================
  // State and entry point
  // =====================================================================================================

  Simulation sim;
  Log log;
  Library lib;
  Path caseDir;            // null when the sim is not in a SimKit case folder
  String caseNote = "";
  JDialog dlg;
  JTabbedPane tabsPane;
  Runnable pending;        // STAR action requested by a button, run on the macro thread
  final List<Tab> tabs = new ArrayList<>();
  final List<JComboBox<String>> mixedFluidCombos = new ArrayList<>();   // ENTERED + fluids
  final List<JComboBox<String>> fluidCombos = new ArrayList<>();        // fluids only
  final List<JComboBox<String>> materialCombos = new ArrayList<>();

  @Override
  public void execute() {
    sim = getActiveSimulation();
    log = new Log(sim);
    String batchReason = headlessReason();
    try {
      lib = Library.load(Fs.kit().resolve("config"));
    } catch (RuntimeException e) {
      lib = new Library();
      lib.errors.add("library not loaded: " + reason(e));
    }
    detectCase();
    log.line(VERSION + " | share " + Fs.share() + " | " + (batchReason == null ? "interactive" : "batch: " + batchReason));
    log.line(lib.summary());
    log.line(caseNote);
    if (batchReason != null) {
      log.line("No window in batch mode: printing the self-check instead.");
      log.line(Core.selfCheck());
      return;
    }
    buildWindow();
    while (true) {
      pending = null;
      dlg.setVisible(true);          // modal: returns when the window is hidden (STAR action) or closed
      Runnable job = pending;
      if (job == null) break;
      try {
        job.run();
      } catch (Cancel c) {
        if (c.getMessage() != null) log.line("cancelled: " + c.getMessage());
      } catch (Throwable t) {
        log.line("ERROR: " + reason(t));
        JOptionPane.showMessageDialog(null, "ERROR: " + reason(t), "SimKitCalc", JOptionPane.ERROR_MESSAGE);
      }
    }
    dlg.dispose();
    log.line("SimKitCalc closed.");
  }

  /** Same heuristics as SimKit.java; env SIMKIT_GUI=1 forces the window. */
  static String headlessReason() {
    if ("1".equals(System.getenv("SIMKIT_GUI"))) return null;
    try { if (GraphicsEnvironment.isHeadless()) return "Java is headless"; } catch (Throwable t) { return "no display"; }
    try {
      String cmd = ProcessHandle.current().info().commandLine().orElse("");
      if (Pattern.compile("\\s-batch(\\s|$)").matcher(cmd).find()) return "process started with -batch";
    } catch (Throwable t) { /* unknown */ }
    try {
      for (java.awt.Frame f : java.awt.Frame.getFrames()) if (f.isVisible()) return null;
      return "no visible window in this JVM (set env SIMKIT_GUI=1 to force the window)";
    } catch (Throwable t) {
      return null;
    }
  }

  /** Case folder from the sim path: <SHARE>\PROJECTS\<PROJ>\03_cases\<CASEID>\sim\<file>.sim */
  void detectCase() {
    String path = "";
    try { path = sim.getSessionPath(); } catch (Throwable t) { /* unsaved */ }
    caseDir = caseDirOf(path, Fs.projectsDir());
    if (caseDir != null) {
      caseNote = "case folder " + caseDir + " -> every Compute is appended to audit\\calc_log.csv";
    } else {
      caseNote = "calc_log: the sim is not in a SimKit case folder (" + (path == null || path.isEmpty() ? "unsaved sim" : path)
          + "): results go to the Output window only, nothing is written to disk";
    }
  }

  /** Case directory for a sim path, or null. The case must lie below projectsDir (write guard, as in SimKit). */
  static Path caseDirOf(String simPath, Path projectsDir) {
    if (simPath == null || simPath.trim().isEmpty()) return null;
    try {
      Path f = Paths.get(simPath).toAbsolutePath().normalize();
      Path simDir = f.getParent();
      if (simDir == null || !simDir.getFileName().toString().equalsIgnoreCase("sim")) return null;
      Path cd = simDir.getParent();
      if (cd == null || !CASE_ID.matcher(cd.getFileName().toString()).matches()) return null;
      Path cases = cd.getParent();
      if (cases == null || !cases.getFileName().toString().equalsIgnoreCase("03_cases")) return null;
      if (!Fs.under(cd, projectsDir)) return null;
      return cd;
    } catch (Throwable t) {
      return null;
    }
  }

  // =====================================================================================================
  // Window
  // =====================================================================================================

  /** One calculator tab: sub-panels (forms) on the left, results on the right. */
  final class Tab {
    final String name;
    final List<Form> forms = new ArrayList<>();
    final JTextArea out = new JTextArea();
    Core.R last;
    String lastForm = "";

    Tab(String name) {
      this.name = name;
      out.setEditable(false);
      out.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
      out.setText(name + "\n\nEnter the inputs and press Compute. Results show the formula, the source and the validity range.\n"
          + "Units are SI unless stated next to the field. Leave optional inputs empty.");
    }
  }

  /** One labelled input field. 'scale' converts the field unit to SI (NaN = not an SI-convertible unit). */
  static final class Field {
    String key, label, unit;
    double scale;
    JTextField tf;
  }

  /** A sub-panel with inputs, choices and a Compute button. */
  final class Form {
    final Tab tab;
    final String title;
    final Map<String, Field> fields = new LinkedHashMap<>();
    final Map<String, JComboBox<String>> combos = new LinkedHashMap<>();
    final List<JComponent> extras = new ArrayList<>();
    final List<String> order = new ArrayList<>();
    Function<Form, Core.R> calc;
    String computeLabel = "Compute";

    Form(Tab tab, String title) {
      this.tab = tab;
      this.title = title;
      tab.forms.add(this);
    }

    Form f(String key, String label, String unit, String def) {
      Field f = new Field();
      f.key = key;
      f.label = label;
      f.unit = unit;
      f.scale = scaleOf(unit);
      f.tf = new JTextField(def, 12);
      fields.put(key, f);
      order.add("f:" + key);
      return this;
    }

    JComboBox<String> c(String key, String label, String... options) {
      JComboBox<String> cb = new JComboBox<>(options);
      cb.setName(label);
      combos.put(key, cb);
      order.add("c:" + key);
      return cb;
    }

    boolean has(String key) { return fields.containsKey(key); }

    Field field(String key) {
      Field f = fields.get(key);
      if (f == null) throw new IllegalStateException("no field " + key);
      return f;
    }

    String text(String key) { return field(key).tf.getText().trim(); }

    /** SI value of a field; NaN if empty. */
    double v(String key) {
      Field f = field(key);
      String s = f.tf.getText().trim();
      if (s.isEmpty()) return Double.NaN;
      double x;
      try {
        x = Double.parseDouble(s);
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException(f.label + ": '" + s + "' is not a number (use a dot as decimal separator)");
      }
      return Double.isNaN(f.scale) ? x : x * f.scale;
    }

    double req(String key) {
      double x = v(key);
      if (Double.isNaN(x)) throw new IllegalArgumentException(field(key).label + " is required");
      return x;
    }

    int reqInt(String key) {
      double x = req(key);
      if (x != Math.rint(x)) throw new IllegalArgumentException(field(key).label + " must be an integer");
      return (int) x;
    }

    String s(String key) {
      Object o = combos.get(key).getSelectedItem();
      return o == null ? "" : o.toString();
    }

    int idx(String key) { return combos.get(key).getSelectedIndex(); }

    /** Writes an SI value back into a field (used for properties taken from the library). */
    void put(String key, double si) {
      Field f = fields.get(key);
      if (f == null) return;
      f.tf.setText(Double.isNaN(si) ? "" : Fmt.num(Double.isNaN(f.scale) ? si : si / f.scale));
    }

    /** key=value unit; ... for calc_log. */
    String inputs() {
      List<String> parts = new ArrayList<>();
      for (String o : order) {
        String k = o.substring(2);
        if (o.startsWith("f:")) {
          Field f = fields.get(k);
          String t = f.tf.getText().trim();
          if (!t.isEmpty()) parts.add(k + "=" + t + (f.unit.isEmpty() || f.unit.equals("-") ? "" : " " + f.unit));
        } else {
          parts.add(k + "=" + s(k));
        }
      }
      return String.join("; ", parts);
    }
  }

  static final Set<String> SI_UNITS = new HashSet<>(Arrays.asList("", "-", "m", "s", "K", "Pa", "m/s", "kg/s", "kg/m^3",
      "Pa-s", "m^2/s", "W/m^2", "W/m^2-K", "W/m-K", "J/kg-K", "J/kg", "N/m", "m^2", "m^3", "Hz", "m/s^2", "1/K",
      "kg/kmol", "J/kg-K", "cells", "s/step", "cores", "W", "1/s"));

  static double scaleOf(String unit) {
    switch (unit) {
      case "um": return 1e-6;
      case "mm": return 1e-3;
      case "g/s": return 1e-3;
      case "%": return 0.01;
      case "kPa": return 1e3;
      case "MJ/kg": return 1e6;
      case "kW/m^2": return 1e3;
      default: return SI_UNITS.contains(unit) ? 1.0 : Double.NaN;
    }
  }

  Tab tab(String name) {
    Tab t = new Tab(name);
    tabs.add(t);
    return t;
  }

  void buildWindow() {
    buildTabs();
    dlg = new JDialog((java.awt.Frame) null, VERSION + "  -  " + simName(), true);
    dlg.setDefaultCloseOperation(JDialog.HIDE_ON_CLOSE);
    JPanel root = new JPanel(new BorderLayout(6, 6));
    root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
    root.add(new JLabel(caseNote), BorderLayout.NORTH);
    tabsPane = new JTabbedPane();
    for (int i = 0; i < tabs.size(); i++) tabsPane.addTab((i + 1) + " " + tabs.get(i).name, layoutTab(tabs.get(i)));
    root.add(tabsPane, BorderLayout.CENTER);

    JPanel south = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
    south.add(button("From parameter...", e -> starAction(() -> fromParameter(currentTab()))));
    south.add(button("From report...", e -> starAction(() -> fromReport(currentTab()))));
    south.add(button("To parameter...", e -> starAction(() -> toParameter(currentTab()))));
    south.add(button("Copy results", e -> copyResults(currentTab())));
    south.add(button("Self-check", e -> {
      Tab t = currentTab();
      t.out.setText(Core.selfCheck());
      t.out.setCaretPosition(0);
    }));
    south.add(button("Close", e -> { pending = null; dlg.setVisible(false); }));
    root.add(south, BorderLayout.SOUTH);
    dlg.setContentPane(root);
    dlg.setSize(1320, 800);
    dlg.setLocationRelativeTo(null);
    dlg.setAlwaysOnTop(true);
  }

  static JButton button(String text, java.awt.event.ActionListener a) {
    JButton b = new JButton(text);
    b.addActionListener(a);
    return b;
  }

  Tab currentTab() { return tabs.get(Math.max(0, tabsPane.getSelectedIndex())); }

  /** Hides the window; the macro thread runs the job (STAR calls) and re-opens the window. */
  void starAction(Runnable job) {
    pending = job;
    dlg.setVisible(false);
  }

  String simName() {
    try { return sim.getPresentationName(); } catch (Throwable t) { return "?"; }
  }

  JComponent layoutTab(Tab t) {
    JPanel left = new JPanel();
    left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
    for (Form fm : t.forms) left.add(layoutForm(fm));
    JPanel holder = new JPanel(new BorderLayout());
    holder.add(left, BorderLayout.NORTH);
    JScrollPane ls = new JScrollPane(holder);
    ls.getVerticalScrollBar().setUnitIncrement(16);
    JSplitPane sp = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, ls, new JScrollPane(t.out));
    sp.setDividerLocation(520);
    return sp;
  }

  JPanel layoutForm(Form fm) {
    JPanel p = new JPanel(new GridBagLayout());
    p.setBorder(BorderFactory.createTitledBorder(fm.title));
    GridBagConstraints g = new GridBagConstraints();
    g.insets = new Insets(1, 3, 1, 3);
    g.anchor = GridBagConstraints.WEST;
    int row = 0;
    for (String o : fm.order) {
      String k = o.substring(2);
      g.gridy = row++;
      g.gridx = 0;
      g.weightx = 0;
      g.fill = GridBagConstraints.NONE;
      g.gridwidth = 1;
      if (o.startsWith("f:")) {
        Field f = fm.fields.get(k);
        p.add(new JLabel(f.label), g);
        g.gridx = 1;
        g.weightx = 1;
        g.fill = GridBagConstraints.HORIZONTAL;
        p.add(f.tf, g);
        g.gridx = 2;
        g.weightx = 0;
        g.fill = GridBagConstraints.NONE;
        p.add(new JLabel(f.unit), g);
      } else {
        JComboBox<String> cb = fm.combos.get(k);
        p.add(new JLabel(cb.getName()), g);
        g.gridx = 1;
        g.gridwidth = 2;
        g.weightx = 1;
        g.fill = GridBagConstraints.HORIZONTAL;
        p.add(cb, g);
      }
    }
    JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    for (JComponent c : fm.extras) buttons.add(c);
    buttons.add(button(fm.computeLabel, e -> compute(fm)));
    g.gridy = row;
    g.gridx = 0;
    g.gridwidth = 3;
    g.fill = GridBagConstraints.NONE;
    p.add(buttons, g);
    return p;
  }

  /** Compute: pure Java on the Swing thread; results + calc_log. */
  void compute(Form fm) {
    Tab t = fm.tab;
    String head = "[" + Fmt.iso() + "] " + t.name + " / " + fm.title;
    try {
      Core.R r = fm.calc.apply(fm);
      t.last = r;
      t.lastForm = fm.title;
      String inputs = fm.inputs();
      String logNote = calcLog(t.name + " / " + fm.title, inputs, r.outputs());
      t.out.setText(head + "\nInputs: " + inputs + "\n\n" + r.text() + "\n" + logNote);
    } catch (IllegalArgumentException e) {
      t.out.setText(head + "\n\nINPUT ERROR: " + e.getMessage());
    } catch (Throwable e) {
      t.out.setText(head + "\n\nERROR: " + reason(e));
    }
    t.out.setCaretPosition(0);
  }

  /** Appends one row to <case>\audit\calc_log.csv (if a case folder exists) and prints it. Returns a status line. */
  String calcLog(String tabName, String inputs, String outputs) {
    log.line("calc | " + tabName + " | in: " + inputs + " | out: " + outputs);    // [U52] on the Swing thread
    if (caseDir == null) return "calc_log: not written (no case folder) -- logged in the Output window only.";
    Path f = caseDir.resolve("audit").resolve("calc_log.csv");
    try {
      Fs.appendCsv(f, caseDir, CALC_LOG_HEADER, new String[] {Fmt.iso(), Fs.user(), tabName, inputs, outputs});
      return "calc_log: appended to " + f;
    } catch (Throwable e) {
      log.line("calc_log: n/a (" + reason(e) + ")");
      return "calc_log: NOT written (" + reason(e) + ")";
    }
  }

  void copyResults(Tab t) {
    try {
      Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(t.out.getText()), null);
    } catch (Throwable e) {
      t.out.append("\n(copy failed: " + reason(e) + ")");
    }
  }

  // ----- fluid helpers ------------------------------------------------------------------------------------

  String[] fluidNames(boolean withEntered) {
    List<String> n = new ArrayList<>();
    if (withEntered) n.add(ENTERED);
    n.addAll(lib.fluids.keySet());
    return n.toArray(new String[0]);
  }

  JComboBox<String> fluidCombo(Form fm, String label) {
    JComboBox<String> cb = fm.c("fluid", label, fluidNames(true));
    mixedFluidCombos.add(cb);
    if (lib.fluids.containsKey("air")) cb.setSelectedItem("air");
    return cb;
  }

  /** Properties: library fluid (at a temperature chosen by Core) or the entered rho, mu, k, cp. */
  Core.Props props(Form fm, double p) {
    String fl = fm.s("fluid");
    if (fl.isEmpty() || fl.equals(ENTERED)) {
      return Core.Props.constant(fm.has("rho") ? fm.v("rho") : Double.NaN, fm.has("mu") ? fm.v("mu") : Double.NaN,
          fm.has("k") ? fm.v("k") : Double.NaN, fm.has("cp") ? fm.v("cp") : Double.NaN);
    }
    return lib.fluidProps(fl, p);
  }

  /** After a library-based compute: show the property values used in the entry fields. */
  static void writeBack(Form fm, Core.R r) {
    if (fm.s("fluid").equals(ENTERED)) return;
    for (String k : new String[] {"rho", "mu", "k", "cp"}) if (r.has(k)) fm.put(k, r.get(k));
  }

  void refreshLibraryCombos() {
    for (JComboBox<String> cb : mixedFluidCombos) refill(cb, fluidNames(true));
    for (JComboBox<String> cb : fluidCombos) refill(cb, fluidNames(false));
    for (JComboBox<String> cb : materialCombos) refill(cb, lib.materials.keySet().toArray(new String[0]));
  }

  static void refill(JComboBox<String> cb, String[] items) {
    Object sel = cb.getSelectedItem();
    cb.removeAllItems();
    for (String s : items) cb.addItem(s);
    if (sel != null) cb.setSelectedItem(sel);
  }

  // =====================================================================================================
  // The 10 tabs
  // =====================================================================================================

  void buildTabs() {
    buildWall();
    buildDimensionless();
    buildTurbulence();
    buildTimeStep();
    buildGci();
    buildFluid();
    buildMaterial();
    buildHeat();
    buildCombustion();
    buildUnits();
  }

  void buildWall() {
    Tab t = tab("Wall & prism layers");
    Form a = new Form(t, "First cell from target y+");
    fluidCombo(a, "fluid (library) or entered");
    a.f("T", "temperature (library)", "K", "300").f("p", "pressure (library)", "Pa", "101325");
    a.f("rho", "density rho", "kg/m^3", "1.177").f("mu", "dynamic viscosity mu", "Pa-s", "1.846e-5");
    a.f("U", "velocity U", "m/s", "10").f("L", "reference length L or hydraulic diameter", "m", "0.1");
    a.f("yplus", "target y+", "-", "1");
    a.c("corr", "correlation", Core.WALL_CORR);
    a.calc = fm -> {
      Core.Props pr = props(fm, fm.v("p"));
      pr.needKcp = false;
      double[] x = pr.at(fm.req("T"));
      Core.R r = Core.wallFirstCell(fm.req("U"), fm.req("L"), x[0], x[1], fm.req("yplus"), fm.idx("corr"));
      r.absorb(pr);
      writeBack(fm, r);
      return r;
    };

    Form b = new Form(t, "Prism stack");
    b.c("mode", "solve", "total thickness from y1, r, N", "growth r from y1, T, N", "layers N from y1, r, T");
    b.f("y1", "first layer thickness y1", "m", "5e-5").f("r", "growth (stretching) ratio r", "-", "1.2");
    b.f("N", "number of layers N", "-", "15").f("T", "total thickness T", "m", "");
    b.f("core", "core cell size next to the stack (optional)", "m", "");
    b.calc = fm -> {
      switch (fm.idx("mode")) {
        case 1: return Core.prismSolveR(fm.req("y1"), fm.req("T"), fm.reqInt("N"), fm.v("core"));
        case 2: return Core.prismSolveN(fm.req("y1"), fm.req("r"), fm.req("T"), fm.v("core"));
        default: return Core.prismForward(fm.req("y1"), fm.req("r"), fm.reqInt("N"), fm.v("core"));
      }
    };

    Form c = new Form(t, "y+ correction (From report... fills y+ measured)");
    c.f("y1old", "current first layer thickness", "m", "5e-5").f("target", "target y+", "-", "1");
    c.f("measured", "measured y+ (e.g. area average)", "-", "");
    c.calc = fm -> Core.yplusCorrection(fm.req("y1old"), fm.req("target"), fm.req("measured"));
  }

  void buildDimensionless() {
    Tab t = tab("Dimensionless numbers");
    Form a = new Form(t, "Re, Pr, Sc, Pe");
    a.f("U", "velocity U", "m/s", "10").f("L", "length L", "m", "0.1").f("rho", "density", "kg/m^3", "1.177");
    a.f("mu", "dynamic viscosity", "Pa-s", "1.846e-5").f("cp", "cp (for Pr)", "J/kg-K", "1007");
    a.f("k", "conductivity k (for Pr)", "W/m-K", "0.0263").f("D", "mass diffusivity (for Sc)", "m^2/s", "");
    a.calc = fm -> Core.flowNumbers(fm.req("U"), fm.req("L"), fm.req("rho"), fm.req("mu"), fm.v("cp"), fm.v("k"), fm.v("D"));

    Form b = new Form(t, "Mach number");
    b.f("U", "velocity U", "m/s", "100").f("gamma", "gamma", "-", "1.4").f("R", "gas constant R", "J/kg-K", "287.05");
    b.f("T", "static temperature", "K", "300");
    b.calc = fm -> Core.mach(fm.req("U"), fm.req("gamma"), fm.req("R"), fm.req("T"));

    Form c = new Form(t, "Fr, Ri, Gr, Ra");
    c.f("U", "velocity U (for Fr, Ri)", "m/s", "1").f("L", "length L", "m", "0.5").f("g", "gravity g", "m/s^2", "9.81");
    c.f("dT", "temperature difference dT", "K", "50").f("Tref", "reference temperature", "K", "300");
    c.f("beta", "expansion coeff. beta (empty: 1/T_ref)", "1/K", "");
    c.f("nu", "kinematic viscosity nu", "m^2/s", "1.57e-5").f("alpha", "thermal diffusivity (for Ra)", "m^2/s", "2.2e-5");
    c.calc = fm -> Core.buoyancy(fm.v("U"), fm.req("L"), fm.req("g"), fm.req("dT"), fm.req("Tref"), fm.v("beta"),
        fm.req("nu"), fm.v("alpha"));

    Form d = new Form(t, "Strouhal number <-> frequency");
    d.f("L", "length L", "m", "0.05").f("U", "velocity U", "m/s", "10").f("St", "St (empty: from f)", "-", "0.2");
    d.f("f", "frequency f (used if St empty)", "Hz", "");
    d.calc = fm -> Core.strouhal(fm.req("L"), fm.req("U"), fm.v("St"), fm.v("f"));

    Form e = new Form(t, "Weber and Ohnesorge");
    e.f("rho", "liquid density", "kg/m^3", "800").f("U", "relative velocity", "m/s", "50");
    e.f("d", "drop / jet diameter", "um", "50").f("sigma", "surface tension", "N/m", "0.0255");
    e.f("mu", "liquid viscosity (for Oh)", "Pa-s", "1.3e-3");
    e.calc = fm -> Core.droplet(fm.req("rho"), fm.req("U"), fm.req("d"), fm.req("sigma"), fm.v("mu"));

    Form f = new Form(t, "Biot and Fourier");
    f.f("h", "heat transfer coefficient h", "W/m^2-K", "50").f("L", "length L (V/A for lumped)", "m", "0.002");
    f.f("k", "solid conductivity k", "W/m-K", "16").f("alpha", "solid diffusivity (for Fo)", "m^2/s", "4e-6");
    f.f("t", "time t (for Fo)", "s", "10");
    f.calc = fm -> Core.biotFourier(fm.req("h"), fm.req("L"), fm.req("k"), fm.v("alpha"), fm.v("t"));

    Form g = new Form(t, "Droplet relaxation time and Stokes number");
    g.f("rhoP", "particle density", "kg/m^3", "800").f("d", "particle diameter", "um", "40");
    g.f("mu", "gas viscosity", "Pa-s", "1.846e-5").f("rhoG", "gas density (for Re_p)", "kg/m^3", "1.177");
    g.f("Urel", "slip velocity (for Re_p)", "m/s", "5").f("U", "flow velocity U (for Stk)", "m/s", "10");
    g.f("L", "flow length L (for Stk)", "m", "0.05");
    g.c("sn", "drag", "Stokes (no correction)", "Schiller-Naumann factor (1 + 0.15 Re_p^0.687)");
    g.calc = fm -> Core.particle(fm.req("rhoP"), fm.req("d"), fm.req("mu"), fm.v("rhoG"), fm.v("Urel"), fm.v("U"),
        fm.v("L"), fm.idx("sn") == 1);
  }

  void buildTurbulence() {
    Tab t = tab("Turbulence inlet");
    Form a = new Form(t, "k, epsilon, omega at an inlet (C_mu = 0.09)");
    a.f("U", "mean velocity U", "m/s", "10").f("I", "intensity I (empty: 0.16 Re_Dh^-1/8)", "%", "5");
    a.f("l", "length scale l (empty: from ratio or 0.07 D_h)", "m", "");
    a.f("VR", "viscosity ratio nu_t/nu (used if l empty)", "-", "");
    a.f("nu", "kinematic viscosity nu", "m^2/s", "1.57e-5");
    a.f("Dh", "hydraulic diameter D_h (optional)", "m", "0.05");
    a.f("A", "duct area A (optional, D_h = 4A/P)", "m^2", "").f("P", "wetted perimeter P (optional)", "m", "");
    a.calc = fm -> Core.turbulenceInlet(fm.req("U"), fm.v("I"), fm.v("l"), fm.v("VR"), fm.req("nu"), fm.v("Dh"),
        fm.v("A"), fm.v("P"));
  }

  void buildTimeStep() {
    Tab t = tab("Time step & statistics");
    Form a = new Form(t, "Time step, periods, cost");
    a.f("Co", "target Courant number", "-", "1").f("dx", "cell size dx", "m", "5e-4").f("U", "velocity U", "m/s", "20");
    a.f("f", "frequency of interest f (optional)", "Hz", "200").f("Lft", "flow-through length (optional)", "m", "0.5");
    a.f("tInit", "initial transient to discard", "s", "0.05").f("Ncyc", "cycles to average (of f)", "-", "50");
    a.f("sps", "wall-clock seconds per time step", "s/step", "10").f("cores", "cores", "cores", "64");
    a.calc = fm -> Core.timeStep(fm.req("Co"), fm.req("dx"), fm.req("U"), fm.v("f"), fm.v("Lft"), fm.v("tInit"),
        fm.v("Ncyc"), fm.v("sps"), fm.v("cores"));

    Form b = new Form(t, "From monitor CSV (batch means, integral time scale)");
    b.f("file", "CSV file (SimKit export; column 1 = time)", "", "");
    JComboBox<String> col = b.c("col", "column");
    b.f("tDiscard", "discard samples before time", "s", "0").f("nb", "number of batches", "-", "10");
    JButton browse = button("Browse...", e -> {
      JFileChooser fc = new JFileChooser(caseDir != null ? caseDir.resolve("results").resolve("csv").toFile() : null);
      if (fc.showOpenDialog(dlg) != JFileChooser.APPROVE_OPTION) return;
      b.field("file").tf.setText(fc.getSelectedFile().toString());
      try {
        loadColumns(b, col);
      } catch (IllegalArgumentException ex) {
        t.out.setText("Monitor CSV: " + ex.getMessage());
      }
    });
    b.extras.add(browse);
    b.calc = fm -> {
      if (col.getItemCount() == 0) loadColumns(fm, col);
      String[] head = new String[1];
      double[][] tx = readMonitorCsv(Paths.get(fm.text("file")), col.getSelectedIndex() + 1, head);
      Core.R r = Core.batchMeans(tx[0], tx[1], Double.isNaN(fm.v("tDiscard")) ? Double.NEGATIVE_INFINITY : fm.v("tDiscard"),
          fm.reqInt("nb"));
      r.line("Data: " + fm.text("file") + ", column '" + head[0] + "'");
      return r;
    };
  }

  void loadColumns(Form fm, JComboBox<String> col) {
    try {
      String s = fm.text("file");
      if (s.isEmpty()) throw new IllegalArgumentException("choose a CSV file first (Browse...)");
      List<String[]> rows = Csv.parse(Fs.read(Paths.get(s)));
      if (rows.isEmpty()) throw new IllegalArgumentException("empty file");
      col.removeAllItems();
      String[] h = rows.get(0);
      for (int i = 1; i < h.length; i++) col.addItem(h[i]);
      if (h.length < 2) throw new IllegalArgumentException("the CSV needs a time column and at least one value column");
    } catch (IOException e) {
      throw new IllegalArgumentException("cannot read the file: " + reason(e));
    }
  }

  /** Reads columns 0 (time) and 'col' of a monitor CSV; rows with a non-numeric cell are skipped. */
  static double[][] readMonitorCsv(Path f, int col, String[] headerOut) {
    List<String[]> rows;
    try {
      rows = Csv.parse(Fs.read(f));
    } catch (IOException e) {
      throw new IllegalArgumentException("cannot read " + f + ": " + reason(e));
    }
    if (rows.size() < 2) throw new IllegalArgumentException("no data rows in " + f);
    if (col < 1 || col >= rows.get(0).length) throw new IllegalArgumentException("choose a column");
    headerOut[0] = rows.get(0)[col];
    double[] t = new double[rows.size()], x = new double[rows.size()];
    int n = 0;
    for (int i = 1; i < rows.size(); i++) {
      String[] r = rows.get(i);
      if (r.length <= col) continue;
      double a = Fmt.parse(r[0]), b = Fmt.parse(r[col]);
      if (Double.isNaN(a) || Double.isNaN(b)) continue;
      t[n] = a;
      x[n] = b;
      n++;
    }
    return new double[][] {Arrays.copyOf(t, n), Arrays.copyOf(x, n)};
  }

  void buildGci() {
    Tab t = tab("Mesh & GCI");
    Form a = new Form(t, "Grid convergence index (Celik et al. 2008)");
    a.f("N1", "N1 cells (fine)", "cells", "18000").f("N2", "N2 cells (medium)", "cells", "8000");
    a.f("N3", "N3 cells (coarse)", "cells", "4500");
    a.f("phi1", "phi1 (fine)", "-", "6.063").f("phi2", "phi2 (medium)", "-", "5.972").f("phi3", "phi3 (coarse)", "-", "5.863");
    a.f("dim", "dimension (3 = 3-D, 2 = 2-D)", "-", "3");
    a.calc = fm -> Core.gci(fm.req("N1"), fm.req("N2"), fm.req("N3"), fm.req("phi1"), fm.req("phi2"), fm.req("phi3"),
        fm.reqInt("dim"));

    Form b = new Form(t, "Cell count estimate");
    b.f("V", "fluid volume V", "m^3", "0.01").f("dx", "cell size dx", "m", "1e-3");
    b.calc = fm -> Core.cellCount(fm.req("V"), fm.req("dx"));
  }

  void buildFluid() {
    Tab t = tab("Fluid properties");
    Form a = new Form(t, "Fluid from config\\fluids.csv");
    JComboBox<String> cb = a.c("fluidName", "fluid", fluidNames(false));
    fluidCombos.add(cb);
    a.f("T", "temperature T", "K", "300").f("p", "pressure p (for ideal-gas density)", "Pa", "101325");
    JButton reload = button("Choose config folder...", e -> {
      JFileChooser fc = new JFileChooser(lib.dir == null ? null : lib.dir.toFile());
      fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
      if (fc.showOpenDialog(dlg) != JFileChooser.APPROVE_OPTION) return;
      lib = Library.load(fc.getSelectedFile().toPath());
      refreshLibraryCombos();
      t.out.setText(lib.summary());
    });
    a.extras.add(reload);
    a.calc = fm -> lib.fluidReport(fm.s("fluidName"), fm.req("T"), fm.v("p"));
  }

  void buildMaterial() {
    Tab t = tab("Material properties");
    Form a = new Form(t, "Solid from config\\materials.csv (+ emissivity.csv)");
    JComboBox<String> cb = a.c("material", "material", lib.materials.keySet().toArray(new String[0]));
    materialCombos.add(cb);
    a.f("T", "temperature T (degC rows are converted)", "K", "300");
    a.calc = fm -> lib.materialReport(fm.s("material"), fm.req("T"));
  }

  void buildHeat() {
    Tab t = tab("Heat transfer");
    Form a = new Form(t, "Convection coefficient h (properties at the film temperature)");
    a.c("corr", "correlation", Core.CONV_CORR);
    fluidCombo(a, "fluid (library) or entered");
    a.f("p", "pressure (library)", "Pa", "101325");
    a.f("U", "velocity U (jet: nozzle exit)", "m/s", "10").f("L", "D (cylinder/sphere/pipe/jet) or L (plate)", "m", "0.01");
    a.f("Tinf", "fluid temperature T_inf", "K", "300").f("Ts", "surface temperature T_s", "K", "350");
    a.f("rho", "density (entered)", "kg/m^3", "1.1").f("mu", "viscosity (entered)", "Pa-s", "1.9e-5");
    a.f("k", "conductivity (entered)", "W/m-K", "0.027").f("cp", "cp (entered)", "J/kg-K", "1007");
    a.f("r", "jet: averaging radius r", "m", "0.05").f("H", "jet: nozzle-to-plate distance H", "m", "0.06");
    a.f("g", "natural conv.: gravity", "m/s^2", "9.81").f("beta", "natural conv.: beta (empty: 1/T_film)", "1/K", "");
    a.calc = fm -> {
      Core.Props pr = props(fm, fm.v("p"));
      Core.R r = Core.convection(fm.idx("corr"), fm.v("U"), fm.req("L"), fm.req("Tinf"), fm.req("Ts"), pr, fm.v("r"),
          fm.v("H"), fm.v("g"), fm.v("beta"));
      writeBack(fm, r);
      return r;
    };

    Form b = new Form(t, "Radiation between a surface and its surroundings");
    b.f("eps", "emissivity", "-", "0.85").f("T", "surface temperature", "K", "800").f("Tsur", "surroundings temperature", "K", "300");
    b.calc = fm -> Core.radiation(fm.req("eps"), fm.req("T"), fm.req("Tsur"));

    Form c = new Form(t, "Thermocouple (virtual instrument): h(Tg - Ttc) = eps(sigma Ttc^4 - G/4)");
    c.c("mode", "direction", "gas T -> thermocouple T (Newton)", "thermocouple T -> gas T (explicit)");
    fluidCombo(c, "gas (library) or entered");
    c.f("T", "known temperature (Tg or Ttc)", "K", "1200").f("eps", "bead / sheath emissivity", "-", "0.85");
    c.f("d", "probe diameter", "m", "0.0015").f("U", "gas velocity", "m/s", "10").f("p", "pressure (library)", "Pa", "101325");
    c.f("G", "incident radiation G (empty: 4 sigma T_sur^4)", "W/m^2", "").f("Tsur", "effective T_sur (if G empty)", "K", "600");
    c.f("rho", "density (entered)", "kg/m^3", "0.29").f("mu", "viscosity (entered)", "Pa-s", "4.6e-5");
    c.f("k", "conductivity (entered)", "W/m-K", "0.075").f("cp", "cp (entered)", "J/kg-K", "1175");
    c.calc = fm -> {
      Core.Props pr = props(fm, fm.v("p"));
      Core.R r = Core.thermocouple(fm.idx("mode") == 0, fm.req("T"), fm.req("eps"), fm.v("G"), fm.v("Tsur"),
          fm.req("d"), fm.req("U"), pr);
      writeBack(fm, r);
      return r;
    };

    Form d = new Form(t, "Adiabatic surface temperature (Wickstrom)");
    d.f("qinc", "incident radiative flux q_inc (empty: sigma T_sur^4)", "W/m^2", "").f("Tsur", "black environment T_sur", "K", "1100");
    d.f("h", "convection coefficient h", "W/m^2-K", "50").f("Tg", "gas temperature T_g", "K", "1000");
    d.f("eps", "surface emissivity", "-", "0.9").f("Ts", "a surface temperature for q_net (optional)", "K", "");
    d.calc = fm -> Core.ast(fm.v("qinc"), fm.v("Tsur"), fm.req("h"), fm.req("Tg"), fm.req("eps"), fm.v("Ts"));

    Form e = new Form(t, "Water-cooled calorimeter");
    e.c("mode", "direction", "flux from water heat-up (Q = m cp dT)", "water dT from flux");
    e.c("area", "area", "projected A = D L", "full surface A = pi D L");
    e.f("mdot", "water mass flow", "kg/s", "0.05").f("cp", "water cp", "J/kg-K", "4180");
    e.f("dT", "water temperature rise", "K", "5").f("flux", "heat flux (inverse mode)", "kW/m^2", "100");
    e.f("D", "tube diameter D", "m", "0.0127").f("L", "exposed length L", "m", "0.1");
    e.calc = fm -> Core.calorimeter(fm.idx("mode") == 0, fm.req("mdot"), fm.req("cp"), fm.v("dT"), fm.v("flux"),
        fm.req("D"), fm.req("L"), fm.idx("area") == 0);
  }

  void buildCombustion() {
    Tab t = tab("Combustion & spray");
    Form a = new Form(t, "Stoichiometry of CxHyOz");
    a.f("x", "C atoms x", "-", "12").f("y", "H atoms y", "-", "23").f("z", "O atoms z", "-", "0");
    a.f("mAir", "air mass flow (for phi)", "kg/s", "").f("mFuel", "fuel mass flow (phi, heat release)", "g/s", "1.89");
    a.f("LHV", "lower heating value", "MJ/kg", "43.2");
    a.calc = fm -> Core.stoich(fm.req("x"), fm.req("y"), fm.req("z"), fm.v("mAir"), fm.v("mFuel"), fm.v("LHV"));

    Form b = new Form(t, "Rosin-Rammler distribution");
    b.c("mode", "given", "SMD (Sauter mean) -> X", "X (characteristic size) -> SMD");
    b.f("size", "SMD or X", "um", "38").f("n", "spread exponent n", "-", "3.5");
    b.f("d", "diameter d for mass fraction above d (optional)", "um", "100");
    b.f("F", "exceedance mass fraction for d_F (optional)", "-", "0.1");
    b.calc = fm -> Core.rosinRammler(fm.idx("mode") == 0, fm.req("size"), fm.req("n"), fm.v("d"), fm.v("F"));

    Form c = new Form(t, "Flow number of a pressure atomiser");
    c.f("mdot", "fuel mass flow", "g/s", "1.89").f("rho", "liquid density", "kg/m^3", "800").f("dP", "pressure drop", "Pa", "689476");
    c.calc = fm -> Core.flowNumber(fm.req("mdot"), fm.req("rho"), fm.req("dP"));

    Form d = new Form(t, "Weber and Ohnesorge numbers");
    d.f("rho", "liquid density", "kg/m^3", "800").f("U", "relative velocity", "m/s", "30");
    d.f("d", "drop diameter", "um", "40").f("sigma", "surface tension", "N/m", "0.0255").f("mu", "liquid viscosity", "Pa-s", "1.3e-3");
    d.calc = fm -> Core.droplet(fm.req("rho"), fm.req("U"), fm.req("d"), fm.req("sigma"), fm.v("mu"));
  }

  void buildUnits() {
    Tab t = tab("Units");
    Form a = new Form(t, "Linear conversions");
    a.c("conv", "conversion", Core.CONV_NAMES);
    a.f("v", "value", "(as selected)", "1");
    a.calc = fm -> Core.convert(fm.idx("conv"), fm.req("v"));

    Form b = new Form(t, "Temperature");
    b.c("unit", "unit of the value", "K", "degC", "degF");
    b.f("v", "value", "(as selected)", "60");
    b.calc = fm -> Core.temperature(fm.req("v"), fm.idx("unit"));

    Form c = new Form(t, "US gal/h -> kg/s");
    c.f("Q", "volume flow", "US gal/h", "1").f("rho", "liquid density", "kg/m^3", "800");
    c.calc = fm -> Core.galPerHour(fm.req("Q"), fm.req("rho"));

    Form d = new Form(t, "SCFM -> kg/s (ideal gas at the standard conditions)");
    d.f("Q", "standard volume flow", "SCFM", "10").f("TF", "standard temperature", "degF", "60");
    d.f("ppsi", "standard pressure", "psi", "14.696").f("M", "molar mass", "kg/kmol", "28.965");
    d.calc = fm -> Core.scfm(fm.req("Q"), fm.req("TF"), fm.req("ppsi"), fm.req("M"));
  }

  // =====================================================================================================
  // STAR actions (macro thread): From parameter / From report / To parameter
  // =====================================================================================================

  /** Asks which input field of the tab receives a value. */
  Field chooseField(Tab t, String title) {
    List<String> labels = new ArrayList<>();
    List<Field> fs = new ArrayList<>();
    for (Form fm : t.forms) {
      for (Field f : fm.fields.values()) {
        if (f.key.equals("file")) continue;
        labels.add(fm.title + ":  " + f.label + (f.unit.isEmpty() ? "" : "  [" + f.unit + "]"));
        fs.add(f);
      }
    }
    String c = Ui.choose(title, "Input field of tab '" + t.name + "':", labels.toArray(new String[0]));
    return fs.get(labels.indexOf(c));
  }

  /** Writes an SI value into a field (converted to the field unit when it is SI-convertible). */
  static String setFieldSI(Field f, double si) {
    if (Double.isNaN(f.scale)) {
      f.tf.setText(Fmt.num(si));
      return "  WARNING: field unit '" + f.unit + "' is not SI-convertible: the SI value was inserted unchanged";
    }
    f.tf.setText(Fmt.num(si / f.scale));
    return "";
  }

  void fromParameter(Tab t) {
    Field f = chooseField(t, "From parameter...");
    List<ScalarGlobalParameter> ps = new ArrayList<>();
    List<String> labels = new ArrayList<>();
    for (Object o : sim.get(GlobalParameterManager.class).getObjects()) {                                 // [U10]
      if (!(o instanceof ScalarGlobalParameter)) continue;
      ScalarGlobalParameter p = (ScalarGlobalParameter) o;
      ps.add(p);
      labels.add(p.getPresentationName() + " = " + paramText(p));
    }
    if (ps.isEmpty()) throw new Cancel("the sim has no scalar global parameters");
    String c = Ui.choose("From parameter...", "Global parameter for '" + f.label + "':", labels.toArray(new String[0]));
    ScalarGlobalParameter p = ps.get(labels.indexOf(c));
    StringBuilder msg = new StringBuilder();
    double si = paramSI(p, msg);
    String w = setFieldSI(f, si);
    String line = "From parameter: " + f.label + " <- " + p.getPresentationName() + " = " + Fmt.num(si) + " (SI)" + msg + w;
    t.out.append("\n" + line);
    log.line(line);
  }

  /** [U50] SI value of a scalar parameter; falls back to the raw value (warning appended to 'msg'). */
  static double paramSI(ScalarGlobalParameter p, StringBuilder msg) {
    ScalarPhysicalQuantity q = p.getQuantity();                                                            // [U10]
    Object si = Reflect.tryCall(q, "getSIValue");                                                         // [U50]
    if (si instanceof Number) return ((Number) si).doubleValue();
    double raw = q.getRawValue();                                                                          // [U10]
    String u = "?";
    try { u = q.getUnits().getPresentationName(); } catch (Throwable e) { /* n/a */ }
    msg.append("  WARNING: getSIValue n/a [U50]: raw value used, in units '").append(u).append("' -- convert if not SI");
    return raw;
  }

  static String paramText(ScalarGlobalParameter p) {
    try {
      ScalarPhysicalQuantity q = p.getQuantity();                                                          // [U10]
      String u = "";
      try { u = " " + q.getUnits().getPresentationName(); } catch (Throwable e) { /* dimensionless */ }
      return Fmt.num(q.getRawValue()) + u;
    } catch (Throwable e) {
      return "n/a (" + reason(e) + ")";
    }
  }

  void fromReport(Tab t) {
    Field f = chooseField(t, "From report...");
    List<Report> rs = new ArrayList<>();
    List<String> labels = new ArrayList<>();
    for (Object o : sim.getReportManager().getObjects()) {                                                 // [U19]
      if (o instanceof Report) {
        rs.add((Report) o);
        labels.add(((Report) o).getPresentationName());
      }
    }
    if (rs.isEmpty()) throw new Cancel("the sim has no reports");
    String c = Ui.choose("From report...", "Report for '" + f.label + "' (it is evaluated now):", labels.toArray(new String[0]));
    Report r = rs.get(labels.indexOf(c));
    double v = r.getReportMonitorValue();                                                                  // [U19]
    String units = "";
    Object u = Reflect.tryCall(r, "getUnits");                                                            // [U53]
    if (u != null) {
      Object n = Reflect.tryCall(u, "getPresentationName");
      units = n == null ? "" : n.toString();
    }
    f.tf.setText(Fmt.num(v));
    String line = "From report: " + f.label + " <- " + c + " = " + Fmt.num(v) + (units.isEmpty() ? "" : " " + units)
        + "  (value in the report's display units; the field expects " + (f.unit.isEmpty() ? "-" : f.unit) + ")";
    t.out.append("\n" + line);
    log.line(line);
  }

  void toParameter(Tab t) {
    if (t.last == null || t.last.items.isEmpty()) throw new Cancel("press Compute first: no result in tab '" + t.name + "'");
    List<String> labels = new ArrayList<>();
    for (Core.Item it : t.last.items) labels.add(it.key + " = " + Core.g(it.v) + " " + it.unit + "   " + it.desc);
    String c = Ui.choose("To parameter...", "Result of '" + t.lastForm + "':", labels.toArray(new String[0]));
    Core.Item it = t.last.items.get(labels.indexOf(c));
    String def = "p_" + Character.toLowerCase(it.key.charAt(0)) + it.key.substring(1).replaceAll("[^A-Za-z0-9]", "");
    String name = def;
    while (true) {
      Object in = JOptionPane.showInputDialog(null, "Global parameter name (p_[a-z][A-Za-z0-9]*):", "To parameter...",
          JOptionPane.QUESTION_MESSAGE, null, null, name);
      if (in == null) throw new Cancel(null);
      name = in.toString().trim();
      if (PARAM_NAME.matcher(name).matches()) break;
      JOptionPane.showMessageDialog(null, "'" + name + "' does not match p_[a-z][A-Za-z0-9]*  (e.g. p_firstLayer)");
    }
    List<String> report = new ArrayList<>();
    setParameter(name, it, report);
    String line = "To parameter: " + String.join(" | ", report);
    t.out.append("\n" + line);
    log.line(line);
  }

  /** Creates or updates p_<name> with the SI value of a result (dimensions, units where possible). */
  void setParameter(String name, Core.Item it, List<String> report) {
    GlobalParameterManager gpm = sim.get(GlobalParameterManager.class);
    ScalarGlobalParameter p = null;
    for (Object o : gpm.getObjects()) {                                                                    // [U10]
      if (o instanceof ScalarGlobalParameter && name.equals(((ScalarGlobalParameter) o).getPresentationName())) {
        p = (ScalarGlobalParameter) o;
      }
    }
    boolean isNew = p == null;
    if (!isNew && !Ui.confirm("To parameter...", name + " exists (" + paramText(p) + ").\nOverwrite its value with "
        + Core.g(it.v) + " " + it.unit + "?")) {
      throw new Cancel(null);
    }
    int[] e = Core.dimsOf(it.unit);
    if (isNew) {
      p = (ScalarGlobalParameter) gpm.createGlobalParameter(ScalarGlobalParameter.class, "Scalar");        // [U10]
      p.setPresentationName(name);
      report.add("created " + name);
      try {
        p.setDimensions(Dimensions.Builder().mass(e[0]).length(e[1]).time(e[2]).temperature(e[3]).build()); // [U30][U31]
      } catch (Throwable t) {
        report.add("dimensions n/a (" + reason(t) + ") [U30/U31]");
      }
    } else {
      report.add("updated " + name + " (keeps its dimensions)");
    }
    ScalarPhysicalQuantity q = p.getQuantity();                                                            // [U10]
    if (!it.unit.isEmpty() && !it.unit.equals("-") && !it.unit.equals("%") && e != null && !isDimless(e)) {
      Units u = unitsFor(it.unit, e, report);
      if (u != null) {
        try {
          q.setUnits(u);                                                                                   // [U31]
        } catch (Throwable t) {
          report.add("setUnits n/a (" + reason(t) + ")");
        }
      }
    }
    q.setValue(it.v);                                                                                      // [U10]
    StringBuilder msg = new StringBuilder();
    double back = paramSI(p, msg);
    boolean ok = Math.abs(back - it.v) <= 1e-9 * Math.max(1, Math.max(Math.abs(back), Math.abs(it.v)));
    report.add("value " + Core.g(it.v) + " " + it.unit + (ok ? " (read back OK)" : " -- CHECK: read back " + Core.g(back) + msg));
  }

  static boolean isDimless(int[] e) { return e[0] == 0 && e[1] == 0 && e[2] == 0 && e[3] == 0; }

  /** [U31] STAR unit object for an SI unit name; [U51] preferred units for the dimensions as a fallback. */
  Units unitsFor(String unit, int[] e, List<String> report) {
    List<String> names = new ArrayList<>(Arrays.asList(unit));
    if (unit.equals("1/s")) names.addAll(Arrays.asList("/s", "Hz"));
    if (unit.equals("Hz")) names.addAll(Arrays.asList("/s", "1/s"));
    for (String n : names) {
      try {
        Object u = sim.getUnitsManager().getObject(n);                                                     // [U31]
        if (u instanceof Units) return (Units) u;
      } catch (Throwable t) { /* try next */ }
    }
    try {
      Object d = Dimensions.Builder().mass(e[0]).length(e[1]).time(e[2]).temperature(e[3]).build();       // [U30]
      Object u = Reflect.tryCall(sim.getUnitsManager(), "getPreferredUnits", d);                          // [U51]
      if (u instanceof Units) {
        report.add("unit '" + unit + "' not found by name; preferred units " + ((Units) u).getPresentationName() + " used");
        return (Units) u;
      }
    } catch (Throwable t) { /* n/a */ }
    report.add("WARNING unit '" + unit + "' not found: value set in the parameter's current units -- check it");
    return null;
  }

  static String reason(Throwable t) {
    String m = t.getMessage();
    return t.getClass().getSimpleName() + (m == null ? "" : ": " + Fmt.cut(m.split("\\r?\\n")[0], 160));
  }

  // =====================================================================================================
  // Core: all physics. NO STAR-CCM+ types here (unit-testable with a plain JDK).
  // =====================================================================================================

  static final class Core {
    static final double SIGMA = 5.670374419e-8;   // Stefan-Boltzmann, W/m^2-K^4 (CODATA 2018)
    static final double RU = 8314.462618;         // J/kmol-K
    static final double CMU = 0.09;

    static final String[] WALL_CORR = {"flat plate, turbulent (Schlichting)", "pipe (Blasius)", "flat plate, laminar (Blasius)"};
    static final String[] CONV_CORR = {"cylinder in cross-flow (Churchill-Bernstein)", "flat plate, laminar average",
        "flat plate, mixed average (Re_c = 5e5)", "sphere (Whitaker, mu/mu_s = 1)", "pipe, turbulent (Gnielinski)",
        "single round impinging jet (Martin 1977)", "vertical plate, natural convection (Churchill-Chu)"};

    /** One numeric result. Values are SI except unit "%" (percent) and "core-h". */
    static final class Item {
      final String key, unit, desc;
      final double v;

      Item(String key, double v, String unit, String desc) {
        this.key = key;
        this.v = v;
        this.unit = unit;
        this.desc = desc;
      }
    }

    /** Result of a calculator: items + formula/source/validity lines + warnings. */
    static final class R {
      final String title;
      final List<Item> items = new ArrayList<>();
      final List<String> lines = new ArrayList<>();
      final List<String> warns = new ArrayList<>();

      R(String title) { this.title = title; }

      R put(String key, double v, String unit, String desc) {
        for (int i = 0; i < items.size(); i++) {
          if (items.get(i).key.equals(key)) { items.set(i, new Item(key, v, unit, desc)); return this; }
        }
        items.add(new Item(key, v, unit, desc));
        return this;
      }

      boolean has(String key) {
        for (Item it : items) if (it.key.equals(key)) return true;
        return false;
      }

      double get(String key) {
        for (Item it : items) if (it.key.equals(key)) return it.v;
        throw new IllegalArgumentException("no result " + key);
      }

      R f(String s) { lines.add("Formula:  " + s); return this; }
      R src(String s) { lines.add("Source:   " + s); return this; }
      R valid(String s) { lines.add("Validity: " + s); return this; }
      R line(String s) { lines.add("Note:     " + s); return this; }

      R warn(String s) {
        if (!warns.contains(s)) warns.add(s);
        return this;
      }

      /** Warns when v is outside [lo, hi]. */
      R range(String what, double v, double lo, double hi) {
        if (v < lo || v > hi) warn(what + " = " + g(v) + " is outside the validity range [" + g(lo) + ", " + g(hi) + "]");
        return this;
      }

      /** Adds property-data sources and clamping warnings. */
      R absorb(Props pr) {
        for (String s : pr.sources) lines.add("Data:     " + s);
        for (String w : pr.warns) warn(w);
        return this;
      }

      String outputs() {
        List<String> p = new ArrayList<>();
        for (Item it : items) p.add(it.key + "=" + String.format(Locale.ROOT, "%.8g", it.v) + (it.unit.isEmpty() || it.unit.equals("-") ? "" : " " + it.unit));
        if (!warns.isEmpty()) p.add("warnings=" + warns.size());
        return String.join("; ", p);
      }

      String text() {
        StringBuilder sb = new StringBuilder("== " + title + " ==\n");
        for (Item it : items) {
          sb.append(String.format(Locale.ROOT, "  %-14s = %-13s %-9s %s%n", it.key, g(it.v),
              it.unit.equals("-") ? "" : it.unit, it.desc));
        }
        sb.append('\n');
        for (String l : lines) sb.append(l).append('\n');
        for (String w : warns) sb.append("WARNING:  ").append(w).append('\n');
        if (warns.isEmpty()) sb.append("(no warnings)\n");
        return sb.toString();
      }
    }

    static String g(double v) {
      if (Double.isNaN(v)) return "n/a";
      if (Double.isInfinite(v)) return v > 0 ? "inf" : "-inf";
      if (v == 0) return "0";
      double a = Math.abs(v);
      if (a >= 1e-3 && a < 1e6) {
        String s = String.format(Locale.ROOT, "%.5g", v);
        if (s.contains(".") && !s.contains("e")) s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        return s;
      }
      return String.format(Locale.ROOT, "%.4e", v);
    }

    static double pos(String name, double v) {
      if (Double.isNaN(v) || Double.isInfinite(v) || v <= 0) throw new IllegalArgumentException(name + " must be > 0 (got " + g(v) + ")");
      return v;
    }

    static boolean given(double v) { return !Double.isNaN(v); }

    /** Fluid properties at a temperature: {rho, mu, k, cp}; NaN where not available. */
    abstract static class Props {
      final List<String> warns = new ArrayList<>();
      final List<String> sources = new ArrayList<>();
      /** false: only rho and mu are evaluated (k, cp returned as NaN), so unused rows give no warnings. */
      boolean needKcp = true;

      abstract double[] at(double T);

      static Props constant(double rho, double mu, double k, double cp) {
        Props p = new Props() {
          @Override
          double[] at(double T) { return new double[] {rho, mu, k, cp}; }
        };
        p.sources.add("properties entered by the user (constant)");
        return p;
      }
    }

    static double[] need(Props pr, double T, boolean kcp) {
      double[] x = pr.at(T);
      pos("density rho", x[0]);
      pos("viscosity mu", x[1]);
      if (kcp) {
        pos("conductivity k", x[2]);
        pos("specific heat cp", x[3]);
      }
      return x;
    }

    // ----- 1. Wall & prism layers ------------------------------------------------------------------------

    static R wallFirstCell(double U, double L, double rho, double mu, double yPlus, int corr) {
      pos("U", U); pos("L", L); pos("density", rho); pos("viscosity", mu); pos("y+", yPlus);
      R r = new R("First cell height for target y+ = " + g(yPlus));
      double Re = rho * U * L / mu, nu = mu / rho, qdyn = 0.5 * rho * U * U, tau;
      r.put("Re", Re, "-", "Reynolds number rho U L / mu");
      if (corr == 1) {
        double f = 0.316 * Math.pow(Re, -0.25);
        tau = f / 8 * rho * U * U;
        r.put("fDarcy", f, "-", "Darcy friction factor (Blasius)");
        r.put("cf", f / 4, "-", "Fanning friction coefficient f/4");
        r.f("f = 0.316 Re^-0.25 ; tau_w = f/8 rho U^2   (U = bulk velocity, L = hydraulic diameter)");
        r.src("Blasius (1913), smooth pipe; e.g. White, Fluid Mechanics");
        r.valid("smooth pipe, turbulent, 4e3 <= Re_Dh <= 1e5");
        r.range("Re_Dh", Re, 4e3, 1e5);
      } else if (corr == 2) {
        double cf = 0.664 / Math.sqrt(Re);
        tau = cf * qdyn;
        r.put("cf", cf, "-", "local skin-friction coefficient at x = L");
        r.f("cf = 0.664 / sqrt(Re_x) ; tau_w = cf 1/2 rho U^2");
        r.src("Blasius laminar flat-plate solution; Schlichting, Boundary-Layer Theory");
        r.valid("laminar flat plate, zero pressure gradient, Re_x < 5e5");
        r.range("Re_x", Re, 0, 5e5);
      } else {
        double cf = Math.pow(2 * Math.log10(Re) - 0.65, -2.3);
        tau = cf * qdyn;
        r.put("cf", cf, "-", "local skin-friction coefficient at x = L");
        r.f("cf = (2 log10(Re_x) - 0.65)^-2.3 ; tau_w = cf 1/2 rho U^2");
        r.src("Schlichting, Boundary-Layer Theory (turbulent flat plate, smooth)");
        r.valid("turbulent flat plate, smooth, 5e5 <= Re_x <= 1e9 (below 5e5 the layer is laminar unless tripped)");
        r.range("Re_x", Re, 5e5, 1e9);
      }
      double uTau = Math.sqrt(tau / rho), y = yPlus * nu / uTau;
      r.put("tauW", tau, "Pa", "wall shear stress");
      r.put("uTau", uTau, "m/s", "friction velocity sqrt(tau_w/rho)");
      r.put("nu", nu, "m^2/s", "kinematic viscosity");
      r.put("rho", rho, "kg/m^3", "density used");
      r.put("mu", mu, "Pa-s", "viscosity used");
      r.put("yCentroid", y, "m", "wall distance of the first cell centroid y = y+ nu / u_tau");
      r.put("firstLayer", 2 * y, "m", "FIRST PRISM LAYER THICKNESS = 2 y");
      r.f("u_tau = sqrt(tau_w / rho) ; y = y+ nu / u_tau ; first layer = 2 y");
      r.line("STAR-CCM+ evaluates y+ at the centroid of the wall cell, i.e. at half the first layer thickness: "
          + "the prism first-layer thickness is therefore 2 y.");
      r.line("cf is the local value at x = L; nearer the leading edge (or at flow impingement) tau_w and y+ are higher.");
      return r;
    }

    static double prismTotal(double y1, double r, int n) {
      return Math.abs(r - 1) < 1e-12 ? n * y1 : y1 * (Math.pow(r, n) - 1) / (r - 1);
    }

    static R prismForward(double y1, double r, int n, double core) {
      pos("y1", y1); pos("r", r);
      if (n < 1) throw new IllegalArgumentException("N must be >= 1");
      R res = new R("Prism stack: total thickness");
      res.put("total", prismTotal(y1, r, n), "m", "total thickness T = y1 (r^N - 1)/(r - 1)");
      prismCommon(res, y1, r, n, core);
      return res;
    }

    static R prismSolveR(double y1, double total, int n, double core) {
      pos("y1", y1); pos("T", total);
      if (n < 2) throw new IllegalArgumentException("N must be >= 2 to solve for r");
      R res = new R("Prism stack: growth ratio r for given y1, T, N");
      if (total <= n * y1 * (1 + 1e-12)) {
        throw new IllegalArgumentException("T <= N y1 (" + g(n * y1) + " m): no growth ratio r > 1 exists; reduce N or y1");
      }
      double lo = 1, hi = 3;
      if (prismTotal(y1, hi, n) < total) {
        res.warn("the required r is above 3 (outside the bisection range (1, 3]): r CLAMPED to 3 -- increase N or y1");
        lo = hi;
      } else {
        for (int i = 0; i < 200; i++) {
          double m = 0.5 * (lo + hi);
          if (prismTotal(y1, m, n) < total) lo = m; else hi = m;
        }
      }
      double r = 0.5 * (lo + hi);
      res.put("r", r, "-", "growth ratio (bisection on (1, 3])");
      res.put("total", prismTotal(y1, r, n), "m", "total thickness with this r");
      prismCommon(res, y1, r, n, core);
      return res;
    }

    static R prismSolveN(double y1, double r, double total, double core) {
      pos("y1", y1); pos("r", r); pos("T", total);
      R res = new R("Prism stack: number of layers N for given y1, r, T");
      double exact = Math.abs(r - 1) < 1e-12 ? total / y1 : Math.log(1 + total * (r - 1) / y1) / Math.log(r);
      if (r < 1) res.warn("r < 1: layers shrink away from the wall");
      int n = Math.max(1, (int) Math.ceil(exact - 1e-9));
      res.put("N", n, "-", "layers (ceil of " + g(exact) + ")");
      res.put("total", prismTotal(y1, r, n), "m", "total thickness with N layers (>= T)");
      res.f("N = ceil( ln(1 + T (r - 1)/y1) / ln r )");
      prismCommon(res, y1, r, n, core);
      return res;
    }

    static void prismCommon(R res, double y1, double r, int n, double core) {
      double last = y1 * Math.pow(r, n - 1);
      res.put("last", last, "m", "last layer thickness y1 r^(N-1)");
      res.f("T = y1 (r^N - 1)/(r - 1) ; last layer = y1 r^(N-1)");
      if (given(core)) {
        pos("core cell size", core);
        double ratio = last / core;
        res.put("lastToCore", ratio, "-", "last layer / core cell size (aim 0.5-1)");
        if (ratio < 0.5 || ratio > 1) res.warn("last layer / core cell = " + g(ratio) + ": aim 0.5-1 for a smooth transition");
      }
      res.range("growth ratio r", r, 1.0, 1.5);
      res.src("geometric series; transition advice: common practice (STAR-CCM+ prism layer mesher)");
      res.valid("r in (1, 1.5] recommended (<= 1.2 for heat transfer / separation); r <= 3 accepted by the solver here");
    }

    static R yplusCorrection(double y1old, double target, double measured) {
      pos("current thickness", y1old); pos("target y+", target); pos("measured y+", measured);
      R r = new R("y+ correction of the first layer");
      r.put("y1new", y1old * target / measured, "m", "new first layer thickness");
      r.put("factor", target / measured, "-", "scale factor");
      r.f("y1_new = y1_old * y+_target / y+_measured");
      r.valid("holds in the viscous sublayer only (y+ < ~5, where y+ is proportional to y); iterate if the change is large");
      r.src("u_tau ~ unchanged for a small change of the first cell");
      if (measured > 5 || target > 5) r.warn("y+ above ~5: outside the viscous sublayer -- the linear scaling is approximate, re-check after remeshing");
      return r;
    }

    // ----- 2. Dimensionless numbers -------------------------------------------------------------------

    static R flowNumbers(double U, double L, double rho, double mu, double cp, double k, double D) {
      pos("U", U); pos("L", L); pos("rho", rho); pos("mu", mu);
      R r = new R("Re, Pr, Sc, Pe");
      double Re = rho * U * L / mu, nu = mu / rho;
      r.put("Re", Re, "-", "rho U L / mu").put("nu", nu, "m^2/s", "mu / rho");
      r.f("Re = rho U L / mu");
      if (given(cp) && given(k)) {
        double Pr = mu * pos("cp", cp) / pos("k", k);
        r.put("Pr", Pr, "-", "mu cp / k").put("alpha", k / (rho * cp), "m^2/s", "k/(rho cp)").put("Pe", Re * Pr, "-", "Re Pr = U L / alpha");
        r.f("Pr = mu cp / k ; Pe = Re Pr");
      }
      if (given(D)) {
        r.put("Sc", nu / pos("D", D), "-", "nu / D");
        r.f("Sc = nu / D");
      }
      r.src("definitions");
      return r;
    }

    static R mach(double U, double gamma, double Rg, double T) {
      pos("gamma", gamma); pos("R", Rg); pos("T", T);
      R r = new R("Mach number");
      double c = Math.sqrt(gamma * Rg * T);
      r.put("c", c, "m/s", "speed of sound sqrt(gamma R T)").put("Ma", U / c, "-", "U / c");
      r.f("c = sqrt(gamma R T) ; Ma = U / c").src("ideal gas");
      if (U / c > 0.3) r.warn("Ma > 0.3: compressibility matters (density changes > ~5 %)");
      return r;
    }

    static R buoyancy(double U, double L, double g, double dT, double Tref, double beta, double nu, double alpha) {
      pos("L", L); pos("g", g); pos("T_ref", Tref); pos("nu", nu);
      R r = new R("Buoyancy numbers");
      double b = given(beta) ? beta : 1 / Tref;
      if (!given(beta)) r.line("beta = 1/T_ref (ideal gas)");
      r.put("beta", b, "1/K", "expansion coefficient");
      double gr = g * b * Math.abs(dT) * L * L * L / (nu * nu);
      r.put("Gr", gr, "-", "g beta dT L^3 / nu^2");
      if (given(U)) {
        pos("U", U);
        r.put("Fr", U / Math.sqrt(g * L), "-", "U / sqrt(g L)");
        double ri = g * b * Math.abs(dT) * L / (U * U);
        r.put("Ri", ri, "-", "g beta dT L / U^2 (= Gr/Re^2)");
        r.line(ri < 0.1 ? "Ri < 0.1: forced convection dominates" : ri > 10 ? "Ri > 10: natural convection dominates" : "0.1 < Ri < 10: mixed convection");
      }
      if (given(alpha)) {
        double ra = g * b * Math.abs(dT) * L * L * L / (nu * pos("alpha", alpha));
        r.put("Ra", ra, "-", "g beta dT L^3 / (nu alpha)").put("Pr", nu / alpha, "-", "nu / alpha");
        if (ra > 1e9) r.line("Ra > 1e9: turbulent natural convection on a vertical plate");
      }
      r.f("Fr = U/sqrt(gL) ; Ri = g beta dT L/U^2 ; Gr = g beta dT L^3/nu^2 ; Ra = Gr Pr").src("definitions");
      return r;
    }

    static R strouhal(double L, double U, double St, double f) {
      pos("L", L); pos("U", U);
      R r = new R("Strouhal number");
      if (given(St)) r.put("f", St * U / L, "Hz", "f = St U / L").put("St", St, "-", "given");
      else if (given(f)) r.put("St", f * L / U, "-", "St = f L / U").put("f", f, "Hz", "given");
      else throw new IllegalArgumentException("enter St or f");
      r.put("period", L / (r.get("St") * U), "s", "1/f");
      r.f("St = f L / U").src("definition (cylinder vortex shedding St ~ 0.2 for 300 < Re < 2e5)");
      return r;
    }

    static R droplet(double rho, double U, double d, double sigma, double mu) {
      pos("rho", rho); pos("U", U); pos("d", d); pos("sigma", sigma);
      R r = new R("Weber and Ohnesorge numbers");
      double we = rho * U * U * d / sigma;
      r.put("We", we, "-", "rho U^2 d / sigma");
      if (given(mu)) {
        double oh = pos("mu", mu) / Math.sqrt(rho * sigma * d);
        r.put("Oh", oh, "-", "mu / sqrt(rho sigma d)").put("Re", rho * U * d / mu, "-", "rho U d / mu (liquid)");
      }
      r.f("We = rho U^2 d / sigma ; Oh = mu / sqrt(rho sigma d) = sqrt(We)/Re");
      r.src("definitions; breakup regimes: Pilch & Erdman 1987 (We_crit ~ 12 for Oh < 0.1)");
      if (we > 12) r.line("We > ~12: secondary (bag) breakup expected for Oh < 0.1");
      return r;
    }

    static R biotFourier(double h, double L, double k, double alpha, double t) {
      pos("h", h); pos("L", L); pos("k", k);
      R r = new R("Biot and Fourier numbers");
      double bi = h * L / k;
      r.put("Bi", bi, "-", "h L / k");
      if (given(alpha) && given(t)) r.put("Fo", alpha * t / (L * L), "-", "alpha t / L^2");
      r.f("Bi = h L / k ; Fo = alpha t / L^2").src("definitions");
      r.line(bi < 0.1 ? "Bi < 0.1: lumped capacitance acceptable (L = V/A)" : "Bi >= 0.1: internal gradients matter");
      return r;
    }

    static R particle(double rhoP, double d, double mu, double rhoG, double urel, double U, double L, boolean sn) {
      pos("particle density", rhoP); pos("d", d); pos("mu", mu);
      R r = new R("Droplet relaxation time and Stokes number");
      double tauSt = rhoP * d * d / (18 * mu), fac = 1;
      r.put("tauStokes", tauSt, "s", "rho_p d^2 / (18 mu)");
      if (given(rhoG) && given(urel)) {
        double rep = rhoG * Math.abs(urel) * d / mu;
        r.put("Rep", rep, "-", "particle Reynolds number rho_g |u_rel| d / mu");
        if (sn) {
          fac = 1 + 0.15 * Math.pow(rep, 0.687);
          r.put("fSN", fac, "-", "Schiller-Naumann drag factor");
          r.range("Re_p (Schiller-Naumann)", rep, 0, 1000);
        } else if (rep > 1) {
          r.warn("Re_p = " + g(rep) + " > 1: Stokes drag underestimates drag; select Schiller-Naumann");
        }
      } else if (sn) {
        throw new IllegalArgumentException("Schiller-Naumann needs the gas density and the slip velocity");
      }
      double tau = tauSt / fac;
      r.put("tauP", tau, "s", "relaxation time used");
      if (given(U) && given(L)) {
        double stk = tau * U / pos("L", L);
        r.put("Stk", stk, "-", "tau_p U / L");
        r.line(stk < 0.1 ? "Stk << 1: droplets follow the flow" : stk > 10 ? "Stk >> 1: ballistic droplets" : "Stk ~ 1: partial response");
      }
      r.f("tau_p = rho_p d^2/(18 mu) / (1 + 0.15 Re_p^0.687) ; Stk = tau_p U / L");
      r.src("Stokes drag; Schiller & Naumann 1933 (valid Re_p < 1000)");
      return r;
    }

    // ----- 3. Turbulence inlet -------------------------------------------------------------------------

    static R turbulenceInlet(double U, double I, double l, double VR, double nu, double Dh, double A, double P) {
      pos("U", U); pos("nu", nu);
      R r = new R("Turbulence inlet values (C_mu = 0.09)");
      if (!given(Dh) && given(A) && given(P)) {
        Dh = 4 * pos("A", A) / pos("P", P);
        r.put("Dh", Dh, "m", "hydraulic diameter 4A/P");
      }
      if (given(Dh)) r.put("ReDh", U * pos("D_h", Dh) / nu, "-", "U D_h / nu");
      if (!given(I)) {
        if (!given(Dh)) throw new IllegalArgumentException("enter I, or D_h (or A and P) for the pipe estimate");
        I = 0.16 * Math.pow(r.get("ReDh"), -0.125);
        r.line("I from the developed-pipe estimate I = 0.16 Re_Dh^(-1/8)");
      }
      pos("I", I);
      r.put("I", I, "-", "turbulence intensity (fraction)");
      if (I > 0.2 || I < 0.001) r.warn("I = " + g(100 * I) + " % is unusual (typical 1-10 %)");
      double k = 1.5 * (U * I) * (U * I), eps, nut;
      if (given(l)) {
        pos("l", l);
        eps = Math.pow(CMU, 0.75) * Math.pow(k, 1.5) / l;
        r.line("length scale given");
      } else if (given(VR)) {
        nut = pos("viscosity ratio", VR) * nu;
        eps = CMU * k * k / nut;
        l = Math.pow(CMU, 0.75) * Math.pow(k, 1.5) / eps;
        r.line("length scale from the viscosity ratio");
      } else if (given(Dh)) {
        l = 0.07 * Dh;
        eps = Math.pow(CMU, 0.75) * Math.pow(k, 1.5) / l;
        r.line("length scale l = 0.07 D_h (developed pipe)");
      } else {
        throw new IllegalArgumentException("enter l, the viscosity ratio, or D_h");
      }
      nut = CMU * k * k / eps;
      r.put("l", l, "m", "turbulent length scale");
      r.put("k", k, "J/kg", "turbulent kinetic energy 1.5 (U I)^2");
      r.put("eps", eps, "m^2/s^3", "dissipation C_mu^0.75 k^1.5 / l");
      r.put("omega", Math.sqrt(k) / (Math.pow(CMU, 0.25) * l), "1/s", "specific dissipation k^0.5/(C_mu^0.25 l)");
      r.put("nutRatio", nut / nu, "-", "viscosity ratio nu_t/nu, nu_t = C_mu k^2/eps");
      r.f("k = 1.5 (U I)^2 ; eps = C_mu^0.75 k^1.5 / l ; omega = k^0.5 / (C_mu^0.25 l) ; nu_t = C_mu k^2 / eps");
      r.f("pipe estimates: I = 0.16 Re_Dh^(-1/8), l = 0.07 D_h");
      r.src("standard k-epsilon relations (Launder & Spalding 1974); pipe estimates: common CFD practice (e.g. Versteeg & Malalasekera)");
      r.valid("pipe estimates for fully developed turbulent pipe flow; STAR-CCM+ 'Turbulent Length Scale' uses the same l definition");
      return r;
    }

    // ----- 4. Time step & statistics -------------------------------------------------------------------

    static R timeStep(double co, double dx, double U, double f, double lft, double tInit, double nCyc, double sps, double cores) {
      pos("Courant number", co); pos("dx", dx); pos("U", U);
      R r = new R("Time step and cost");
      double dt = co * dx / U;
      r.put("dt", dt, "s", "Co dx / U");
      double tAvg = 0;
      if (given(f)) {
        double spp = 1 / (pos("f", f) * dt);
        r.put("stepsPerPeriod", spp, "-", "1/(f dt)");
        if (spp < 20) r.warn("only " + g(spp) + " steps per period of f: aim >= 20-50 to resolve it");
        if (given(nCyc)) {
          tAvg = nCyc / f;
          r.put("tAvg", tAvg, "s", "averaging time N_cycles / f");
        }
      }
      if (given(lft)) r.put("tFlowThrough", lft / U, "s", "flow-through time L / U");
      double tTot = (given(tInit) ? tInit : 0) + tAvg;
      if (tTot > 0) {
        double steps = Math.ceil(tTot / dt);
        r.put("tTotal", tTot, "s", "initial transient + averaging");
        r.put("steps", steps, "-", "total time steps");
        if (given(sps) && given(cores)) {
          r.put("coreHours", steps * sps * cores / 3600, "core-h", "steps x s/step x cores / 3600");
          r.put("wallHours", steps * sps / 3600, "h", "wall-clock hours");
        }
      }
      r.f("dt = Co dx / U ; steps/period = 1/(f dt) ; t_avg = N/f ; core-h = steps * s/step * cores / 3600");
      r.src("definitions; Co is the convective Courant number");
      r.valid("Co ~ 1 for scale-resolving (LES/DES); implicit URANS tolerates larger Co if the frequency is resolved");
      return r;
    }

    /** Two-sided 95 % Student t value for 'dof' degrees of freedom (table 1-30, asymptotic series above). */
    static double tStudent975(int dof) {
      final double[] T = {12.706, 4.303, 3.182, 2.776, 2.571, 2.447, 2.365, 2.306, 2.262, 2.228, 2.201, 2.179, 2.160,
          2.145, 2.131, 2.120, 2.110, 2.101, 2.093, 2.086, 2.080, 2.074, 2.069, 2.064, 2.060, 2.056, 2.052, 2.048, 2.045, 2.042};
      if (dof < 1) throw new IllegalArgumentException("dof must be >= 1");
      if (dof <= 30) return T[dof - 1];
      double z = 1.959964, n = dof;
      return z + (z * z * z + z) / (4 * n) + (5 * Math.pow(z, 5) + 16 * z * z * z + 3 * z) / (96 * n * n);
    }

    static R batchMeans(double[] t, double[] x, double tDiscard, int nb) {
      if (nb < 2) throw new IllegalArgumentException("number of batches must be >= 2");
      int i0 = 0;
      while (i0 < t.length && t[i0] < tDiscard) i0++;
      int n = t.length - i0;
      if (n < 2 * nb) throw new IllegalArgumentException(n + " samples after the discard time: need at least " + 2 * nb);
      R r = new R("Batch means and integral time scale");
      int b = n / nb, start = t.length - nb * b;   // drop the earliest remainder
      double[] bm = new double[nb];
      double mean = 0;
      for (int k = 0; k < nb; k++) {
        double s = 0;
        for (int i = 0; i < b; i++) s += x[start + k * b + i];
        bm[k] = s / b;
        mean += bm[k] / nb;
      }
      double var = 0;
      for (double v : bm) var += (v - mean) * (v - mean);
      double sb = Math.sqrt(var / (nb - 1)), tv = tStudent975(nb - 1), half = tv * sb / Math.sqrt(nb);
      int m = nb * b;
      double span = t[t.length - 1] - t[start], dt = span / (m - 1);
      r.put("samples", m, "-", "samples used (after discard, multiple of the batch count)");
      r.put("mean", mean, "", "mean of the signal");
      r.put("sBatch", sb, "", "standard deviation of the batch means");
      r.put("tValue", tv, "-", "Student t (95 %, " + (nb - 1) + " dof)");
      r.put("ci95", half, "", "95 % confidence half-width t s_b / sqrt(n_b)");
      if (mean != 0) r.put("ci95pct", 100 * half / Math.abs(mean), "%", "half-width / |mean|");
      // uniform sampling check
      double maxDev = 0;
      for (int i = start + 1; i < t.length; i++) maxDev = Math.max(maxDev, Math.abs((t[i] - t[i - 1]) - dt));
      if (maxDev > 0.01 * Math.abs(dt)) r.warn("non-uniform sampling: the integral time scale assumes a constant step (mean dt used)");
      // autocorrelation to the first zero crossing (trapezoid)
      double mu = 0;
      for (int i = start; i < t.length; i++) mu += x[i];
      mu /= m;
      double c0 = 0;
      for (int i = start; i < t.length; i++) c0 += (x[i] - mu) * (x[i] - mu);
      double tau = Double.NaN;
      if (c0 > 0) {
        double integ = 0, prev = 1;
        boolean crossed = false;
        for (int lag = 1; lag < m / 2; lag++) {
          double c = 0;
          for (int i = start; i + lag < t.length; i++) c += (x[i] - mu) * (x[i + lag] - mu);
          double rho = c / c0;
          if (rho <= 0) {
            integ += 0.5 * prev * (prev / (prev - rho));   // linear interpolation to the zero crossing
            crossed = true;
            break;
          }
          integ += 0.5 * (prev + rho);
          prev = rho;
        }
        tau = integ * dt;
        if (!crossed) r.warn("autocorrelation has no zero crossing within half the record: tau_int is a lower bound -- record too short");
        r.put("tauInt", tau, "s", "integral time scale (autocorrelation integrated to its first zero)");
        r.put("Neff", span / (2 * tau), "-", "effective independent samples T / (2 tau_int)");
        double batchLen = b * dt;
        if (batchLen < 10 * tau) r.warn("batch length " + g(batchLen) + " s < 10 tau_int: batch means are correlated, the CI is optimistic -- use fewer batches or a longer record");
      } else {
        r.warn("constant signal: no autocorrelation");
      }
      r.put("T", span, "s", "record length used");
      if (mean != 0 && half > 0.03 * Math.abs(mean)) r.warn("95 % CI = " + g(100 * half / Math.abs(mean)) + " % of the mean (> 3 %): average longer");
      r.f("CI95 = t(0.975, n_b - 1) s_b / sqrt(n_b) ; tau_int = int_0^(first zero) rho(tau) dtau ; N_eff = T / (2 tau_int)");
      r.src("batch means: e.g. Law & Kelton, Simulation Modeling and Analysis; integral time scale: Pope, Turbulent Flows; Student t table");
      r.valid("statistically stationary signal after the discard time; batches longer than ~10 tau_int");
      return r;
    }

    // ----- 5. Mesh & GCI -------------------------------------------------------------------------------

    static R gci(double n1, double n2, double n3, double f1, double f2, double f3, int dim) {
      pos("N1", n1); pos("N2", n2); pos("N3", n3);
      if (dim < 1 || dim > 3) throw new IllegalArgumentException("dimension must be 1, 2 or 3");
      if (!(n1 > n2 && n2 > n3)) throw new IllegalArgumentException("order the grids fine -> coarse: N1 > N2 > N3");
      R r = new R("Grid convergence index (Celik et al. 2008)");
      double h1 = Math.pow(1 / n1, 1.0 / dim), h2 = Math.pow(1 / n2, 1.0 / dim), h3 = Math.pow(1 / n3, 1.0 / dim);
      double r21 = h2 / h1, r32 = h3 / h2, e21 = f2 - f1, e32 = f3 - f2;
      if (e21 == 0 || e32 == 0) throw new IllegalArgumentException("phi1 = phi2 or phi2 = phi3: the order cannot be computed");
      double s = Math.signum(e32 / e21), a = Math.log(Math.abs(e32 / e21));
      double p = Math.abs(a) / Math.log(r21);
      boolean conv = false;
      for (int i = 0; i < 500; i++) {
        double num = Math.pow(r21, p) - s, den = Math.pow(r32, p) - s;
        if (num / den <= 0) throw new IllegalArgumentException("q(p) undefined (oscillatory data with these ratios): no apparent order");
        double pn = Math.abs(a + Math.log(num / den)) / Math.log(r21);
        if (Math.abs(pn - p) < 1e-12) { p = pn; conv = true; break; }
        p = pn;
      }
      if (!conv) r.warn("fixed-point iteration for p did not converge in 500 iterations");
      double r21p = Math.pow(r21, p), r32p = Math.pow(r32, p);
      double ext21 = (r21p * f1 - f2) / (r21p - 1), ext32 = (r32p * f2 - f3) / (r32p - 1);
      double ea21 = Math.abs((f1 - f2) / f1), eext21 = Math.abs((ext21 - f1) / ext21);
      double gci21 = 1.25 * ea21 / (r21p - 1), ea32 = Math.abs((f2 - f3) / f2), gci32 = 1.25 * ea32 / (r32p - 1);
      r.put("r21", r21, "-", "h2/h1").put("r32", r32, "-", "h3/h2");
      r.put("p", p, "-", "apparent order");
      r.put("phiExt21", ext21, "", "extrapolated value (r21^p phi1 - phi2)/(r21^p - 1)");
      r.put("phiExt32", ext32, "", "extrapolated value from grids 2-3");
      r.put("ea21", 100 * ea21, "%", "approximate relative error |(phi1 - phi2)/phi1|");
      r.put("eext21", 100 * eext21, "%", "extrapolated relative error |(phi_ext - phi1)/phi_ext|");
      r.put("GCIfine21", 100 * gci21, "%", "fine-grid GCI = 1.25 e_a21/(r21^p - 1)");
      r.put("GCI32", 100 * gci32, "%", "medium-grid GCI = 1.25 e_a32/(r32^p - 1)");
      r.put("asymptotic", gci32 / (r21p * gci21), "-", "GCI32/(r21^p GCI21): ~1 in the asymptotic range");
      r.f("h = (1/N)^(1/dim) ; p = |ln|e32/e21| + q(p)| / ln r21 ; q = ln((r21^p - s)/(r32^p - s)) ; s = sign(e32/e21)");
      r.f("phi_ext21 = (r21^p phi1 - phi2)/(r21^p - 1) ; GCI_fine21 = 1.25 e_a21/(r21^p - 1)");
      r.src("Celik et al. 2008, J. Fluids Eng. 130:078001 (ASME V&V procedure)");
      r.valid("monotonic convergence, r > 1.3, solutions in the asymptotic range, same discretisation on all grids");
      r.line("Celik's published example (N = 18000/8000/4500) is 2-D: dimension 2 reproduces p = 1.53, r21 = 1.5. "
          + "phi_ext and GCI do not depend on the dimension (r^p is invariant), only p does.");
      if (s < 0) r.warn("oscillatory convergence (e32/e21 < 0): GCI is unreliable");
      if (r21 < 1.3) r.warn("r21 = " + g(r21) + " < 1.3 (Celik recommends r > 1.3)");
      if (r32 < 1.3) r.warn("r32 = " + g(r32) + " < 1.3 (Celik recommends r > 1.3)");
      if (p > 4 || p < 0.5) r.warn("apparent order p = " + g(p) + " is far from the formal order (~2): grids may not be in the asymptotic range");
      return r;
    }

    static R cellCount(double V, double dx) {
      pos("V", V); pos("dx", dx);
      R r = new R("Cell count estimate");
      double n = V / (dx * dx * dx);
      r.put("Nhex", n, "-", "hex-equivalent cells V/dx^3");
      r.put("NpolyLow", n / 5, "-", "polyhedral, approx. (/5)").put("NpolyHigh", n / 3, "-", "polyhedral, approx. (/3)");
      r.f("N = V / dx^3 (uniform hexahedra)");
      r.line("polyhedral meshes need roughly 3-5x fewer cells for the same face resolution (approximate)");
      r.src("geometric estimate; prism layers and refinement zones come on top");
      return r;
    }

    // ----- 8. Heat transfer ----------------------------------------------------------------------------

    static double churchillBernstein(double re, double pr) {
      return 0.3 + 0.62 * Math.sqrt(re) * Math.cbrt(pr) / Math.pow(1 + Math.pow(0.4 / pr, 2.0 / 3), 0.25)
          * Math.pow(1 + Math.pow(re / 282000, 5.0 / 8), 4.0 / 5);
    }

    static double churchillChu(double ra, double pr) {
      double x = 0.825 + 0.387 * Math.pow(ra, 1.0 / 6) / Math.pow(1 + Math.pow(0.492 / pr, 9.0 / 16), 8.0 / 27);
      return x * x;
    }

    static double gnielinski(double re, double pr) {
      double f = Math.pow(0.790 * Math.log(re) - 1.64, -2);
      return (f / 8) * (re - 1000) * pr / (1 + 12.7 * Math.sqrt(f / 8) * (Math.pow(pr, 2.0 / 3) - 1));
    }

    static double martinJet(double re, double pr, double dOverR, double hOverD) {
      double fRe = 2 * Math.sqrt(re) * Math.sqrt(1 + 0.005 * Math.pow(re, 0.55));
      double gg = dOverR * (1 - 1.1 * dOverR) / (1 + 0.1 * (hOverD - 6) * dOverR);
      return Math.pow(pr, 0.42) * gg * fRe;
    }

    static R convection(int corr, double U, double L, double tInf, double tS, Props pr, double rad, double H, double g, double beta) {
      pos("L / D", L); pos("T_inf", tInf); pos("T_s", tS);
      R r = new R("Convection: " + CONV_CORR[corr]);
      double tf = 0.5 * (tInf + tS);
      double[] x = need(pr, tf, true);
      double rho = x[0], mu = x[1], k = x[2], cp = x[3], nu = mu / rho, alpha = k / (rho * cp), Pr = mu * cp / k, nuS;
      r.put("Tfilm", tf, "K", "film temperature (T_inf + T_s)/2");
      r.put("rho", rho, "kg/m^3", "at T_film").put("mu", mu, "Pa-s", "at T_film").put("k", k, "W/m-K", "at T_film").put("cp", cp, "J/kg-K", "at T_film");
      r.put("Pr", Pr, "-", "mu cp / k");
      double re = Double.NaN;
      if (corr != 6) {
        pos("U", U);
        re = rho * U * L / mu;
        r.put("Re", re, "-", "rho U L / mu");
      }
      switch (corr) {
        case 0:
          nuS = churchillBernstein(re, Pr);
          r.f("Nu = 0.3 + 0.62 Re^0.5 Pr^(1/3) / [1 + (0.4/Pr)^(2/3)]^0.25 * [1 + (Re/282000)^(5/8)]^(4/5)");
          r.src("Churchill & Bernstein 1977, J. Heat Transfer 99:300 (Incropera eq. 7.54)");
          r.valid("Re Pr >= 0.2, properties at T_film");
          r.range("Re Pr", re * Pr, 0.2, Double.POSITIVE_INFINITY);
          break;
        case 1:
          nuS = 0.664 * Math.sqrt(re) * Math.cbrt(Pr);
          r.f("Nu_avg = 0.664 Re_L^0.5 Pr^(1/3)").src("Incropera eq. 7.30 (laminar flat plate, average)");
          r.valid("Re_L <= 5e5, Pr >= 0.6");
          r.range("Re_L", re, 0, 5e5).range("Pr", Pr, 0.6, Double.POSITIVE_INFINITY);
          break;
        case 2:
          nuS = (0.037 * Math.pow(re, 0.8) - 871) * Math.cbrt(Pr);
          r.f("Nu_avg = (0.037 Re_L^0.8 - 871) Pr^(1/3)").src("Incropera eq. 7.38 (mixed boundary layer, Re_x,c = 5e5)");
          r.valid("5e5 < Re_L <= 1e8, 0.6 <= Pr <= 60");
          r.range("Re_L", re, 5e5, 1e8).range("Pr", Pr, 0.6, 60);
          break;
        case 3:
          nuS = 2 + (0.4 * Math.sqrt(re) + 0.06 * Math.pow(re, 2.0 / 3)) * Math.pow(Pr, 0.4);
          r.f("Nu = 2 + (0.4 Re^0.5 + 0.06 Re^(2/3)) Pr^0.4 (mu/mu_s)^(1/4), mu/mu_s = 1");
          r.src("Whitaker 1972, AIChE J. 18:361 (Incropera eq. 7.56)");
          r.valid("3.5 <= Re <= 7.6e4, 0.71 <= Pr <= 380; Whitaker evaluates properties at T_inf (here T_film)");
          r.range("Re", re, 3.5, 7.6e4).range("Pr", Pr, 0.71, 380);
          break;
        case 4:
          nuS = gnielinski(re, Pr);
          r.put("f", Math.pow(0.790 * Math.log(re) - 1.64, -2), "-", "Petukhov friction factor");
          r.f("f = (0.790 ln Re - 1.64)^-2 ; Nu = (f/8)(Re - 1000) Pr / (1 + 12.7 (f/8)^0.5 (Pr^(2/3) - 1))");
          r.src("Gnielinski 1976, Int. Chem. Eng. 16:359 (Incropera eq. 8.62)");
          r.valid("3000 <= Re_D <= 5e6, 0.5 <= Pr <= 2000, fully developed; properties at the bulk temperature (here T_film)");
          r.range("Re_D", re, 3000, 5e6).range("Pr", Pr, 0.5, 2000);
          break;
        case 5: {
          pos("r", rad); pos("H", H);
          double rd = rad / L, hd = H / L;
          nuS = martinJet(re, Pr, 1 / rd, hd);
          r.put("rOverD", rd, "-", "r/D").put("HOverD", hd, "-", "H/D");
          r.f("Nu = Pr^0.42 G F ; F = 2 Re^0.5 (1 + 0.005 Re^0.55)^0.5 ; G = (D/r)(1 - 1.1 D/r)/(1 + 0.1 (H/D - 6) D/r)");
          r.src("Martin 1977, Adv. Heat Transfer 13:1 (Incropera eq. 7.79-7.80), area average over radius r");
          r.valid("2000 <= Re <= 4e5, 2 <= H/D <= 12, 2.5 <= r/D <= 7.5; Re with the nozzle exit velocity and diameter");
          r.range("Re", re, 2000, 4e5).range("H/D", hd, 2, 12).range("r/D", rd, 2.5, 7.5);
          break;
        }
        case 6: {
          pos("g", g);
          double b = given(beta) ? beta : 1 / tf;
          if (!given(beta)) r.line("beta = 1/T_film (ideal gas)");
          double ra = g * b * Math.abs(tS - tInf) * L * L * L / (nu * alpha);
          r.put("Ra", ra, "-", "g beta |T_s - T_inf| L^3 / (nu alpha)");
          nuS = churchillChu(ra, Pr);
          r.f("Nu = {0.825 + 0.387 Ra^(1/6) / [1 + (0.492/Pr)^(9/16)]^(8/27)}^2");
          r.src("Churchill & Chu 1975, Int. J. Heat Mass Transfer 18:1323 (Incropera eq. 9.26)");
          r.valid("vertical plate, all Ra (laminar and turbulent), Ra <= 1e12");
          r.range("Ra", ra, 0.1, 1e12);
          break;
        }
        default: throw new IllegalArgumentException("unknown correlation");
      }
      double h = nuS * k / L;
      r.put("Nu", nuS, "-", "Nusselt number");
      r.put("h", h, "W/m^2-K", "heat transfer coefficient Nu k / L");
      r.put("q", h * (tS - tInf), "W/m^2", "h (T_s - T_inf), positive = surface to fluid");
      r.absorb(pr);
      return r;
    }

    static R radiation(double eps, double T, double tSur) {
      if (eps < 0 || eps > 1) throw new IllegalArgumentException("emissivity must be in [0, 1]");
      pos("T", T); pos("T_sur", tSur);
      R r = new R("Radiation exchange (small surface in large surroundings)");
      double q = eps * SIGMA * (Math.pow(T, 4) - Math.pow(tSur, 4));
      r.put("q", q, "W/m^2", "net radiative flux leaving the surface");
      r.put("hRad", Math.abs(T - tSur) < 1e-9 ? 4 * eps * SIGMA * T * T * T : q / (T - tSur), "W/m^2-K", "equivalent radiative h");
      r.f("q = eps sigma (T^4 - T_sur^4) ; sigma = 5.670374e-8 W/m^2K^4").src("Stefan-Boltzmann, grey diffuse surface");
      r.valid("small grey surface enclosed by large isothermal surroundings");
      return r;
    }

    /** Thermocouple temperature from the gas temperature: h(Tg - T) = eps(sigma T^4 - G/4), Newton. */
    static double tcTtc(double tg, double eps, double G, double h) {
      double tRad = Math.pow(Math.max(G, 0) / (4 * SIGMA), 0.25);
      double T = Math.max(tg, tRad);           // F(T) <= 0 here and F is concave decreasing -> monotone Newton
      for (int i = 0; i < 200; i++) {
        double F = h * (tg - T) - eps * (SIGMA * Math.pow(T, 4) - G / 4), dF = -h - 4 * eps * SIGMA * T * T * T;
        double dT = F / dF;
        T -= dT;
        if (Math.abs(dT) < 1e-12 * Math.max(1, T)) break;
      }
      return T;
    }

    /** Gas temperature from the thermocouple temperature (explicit). */
    static double tcTg(double ttc, double eps, double G, double h) {
      return ttc + eps * (SIGMA * Math.pow(ttc, 4) - G / 4) / h;
    }

    static double hCylinder(Props pr, double T, double d, double U, double[] out) {
      double[] x = need(pr, T, true);
      double re = x[0] * U * d / x[1], prn = x[1] * x[3] / x[2], nu = churchillBernstein(re, prn);
      if (out != null) { out[0] = re; out[1] = prn; out[2] = nu; System.arraycopy(x, 0, out, 3, 4); }
      return nu * x[2] / d;
    }

    static R thermocouple(boolean forward, double tKnown, double eps, double G, double tSur, double d, double U, Props pr) {
      pos("known temperature", tKnown); pos("probe diameter", d); pos("U", U);
      if (eps < 0 || eps > 1) throw new IllegalArgumentException("emissivity must be in [0, 1]");
      R r = new R("Thermocouple (virtual instrument): " + (forward ? "T_g -> T_tc" : "T_tc -> T_g"));
      if (!given(G)) {
        G = 4 * SIGMA * Math.pow(pos("T_sur (or enter G)", tSur), 4);
        r.line("G = 4 sigma T_sur^4 with T_sur = " + g(tSur) + " K");
      }
      double tg = tKnown, ttc = tKnown, h = 0;
      double[] o = new double[7];
      boolean conv = false;
      for (int it = 0; it < 100; it++) {
        double tf = 0.5 * (tg + ttc);
        h = hCylinder(pr, tf, d, U, o);
        double nw;
        if (forward) {
          nw = tcTtc(tg, eps, G, h);
          if (Math.abs(nw - ttc) < 1e-9 * tg) { ttc = nw; conv = true; break; }
          ttc = nw;
        } else {
          nw = tcTg(ttc, eps, G, h);
          if (Math.abs(nw - tg) < 1e-9 * ttc) { tg = nw; conv = true; break; }
          tg = nw;
        }
      }
      if (!conv) r.warn("film-temperature iteration did not converge");
      h = hCylinder(pr, 0.5 * (tg + ttc), d, U, o);
      r.put("Tg", tg, "K", forward ? "gas temperature (given)" : "gas temperature (computed)");
      r.put("Ttc", ttc, "K", forward ? "thermocouple temperature (computed)" : "thermocouple temperature (given)");
      r.put("bias", tg - ttc, "K", "T_g - T_tc");
      r.put("G", G, "W/m^2", "incident radiation");
      r.put("h", h, "W/m^2-K", "Churchill-Bernstein on the probe diameter");
      r.put("Re", o[0], "-", "probe Reynolds number").put("Pr", o[1], "-", "at T_film").put("Nu", o[2], "-", "Churchill-Bernstein");
      r.put("rho", o[3], "kg/m^3", "at T_film").put("mu", o[4], "Pa-s", "at T_film").put("k", o[5], "W/m-K", "at T_film").put("cp", o[6], "J/kg-K", "at T_film");
      r.f("h (T_g - T_tc) = eps (sigma T_tc^4 - G/4) ; G/4 = irradiation on a sphere/cylinder in a field of incident radiation G");
      r.f("h from Nu_CB(Re_d, Pr), properties at (T_g + T_tc)/2");
      r.src("steady energy balance of the junction (conduction along the wires neglected); Churchill & Bernstein 1977");
      r.valid("Re_d Pr >= 0.2; steady state (no time constant); grey bead");
      r.range("Re Pr", o[0] * o[1], 0.2, Double.POSITIVE_INFINITY);
      r.absorb(pr);
      return r;
    }

    static R ast(double qinc, double tSur, double h, double tg, double eps, double tS) {
      pos("h", h); pos("T_g", tg);
      if (eps < 0 || eps > 1) throw new IllegalArgumentException("emissivity must be in [0, 1]");
      R r = new R("Adiabatic surface temperature (Wickstrom)");
      if (!given(qinc)) {
        qinc = SIGMA * Math.pow(pos("T_sur (or enter q_inc)", tSur), 4);
        r.line("q_inc = sigma T_sur^4 (black environment at T_sur = " + g(tSur) + " K)");
      }
      double T = Math.max(tg, Math.pow(Math.max(qinc, 0) / SIGMA, 0.25));
      for (int i = 0; i < 200; i++) {
        double F = eps * (qinc - SIGMA * Math.pow(T, 4)) + h * (tg - T), dF = -4 * eps * SIGMA * T * T * T - h, dT = F / dF;
        T -= dT;
        if (Math.abs(dT) < 1e-12 * Math.max(1, T)) break;
      }
      r.put("qinc", qinc, "W/m^2", "incident radiative flux");
      r.put("TAST", T, "K", "adiabatic surface temperature");
      if (given(tS)) r.put("qNet", eps * (qinc - SIGMA * Math.pow(tS, 4)) + h * (tg - tS), "W/m^2", "net flux into a surface at T_s");
      r.f("eps (q_inc - sigma T_AST^4) + h (T_g - T_AST) = 0  (Newton) ; q_net(T_s) = eps (q_inc - sigma T_s^4) + h (T_g - T_s)");
      r.src("Wickstrom, Duthinh & McGrattan 2007, 'Adiabatic surface temperature for calculating heat transfer to fire exposed structures', Interflam 2007");
      r.valid("plane grey surface, absorptivity = emissivity");
      return r;
    }

    static R calorimeter(boolean forward, double mdot, double cp, double dT, double flux, double D, double L, boolean projected) {
      pos("mass flow", mdot); pos("cp", cp); pos("D", D); pos("L", L);
      R r = new R("Water-cooled calorimeter");
      double A = projected ? D * L : Math.PI * D * L;
      r.put("A", A, "m^2", projected ? "projected area D L" : "surface area pi D L");
      if (forward) {
        double q = mdot * cp * pos("dT", dT);
        r.put("Q", q, "W", "m cp dT").put("flux", q / A, "W/m^2", "Q / A").put("fluxkW", q / A / 1000, "kW/m^2", "Q / A");
      } else {
        double q = pos("flux", flux) * A;
        r.put("Q", q, "W", "flux A").put("dT", q / (mdot * cp), "K", "Q / (m cp)");
      }
      r.f("Q = m cp dT ; flux = Q / A").src("steady energy balance of the cooling water");
      r.valid("steady state, no heat loss from the water lines; the area choice must match the reference flux definition");
      return r;
    }

    // ----- 9. Combustion & spray -----------------------------------------------------------------------

    static R stoich(double x, double y, double z, double mAir, double mFuel, double lhv) {
      if (x < 0 || y < 0 || z < 0 || x + y + z == 0) throw new IllegalArgumentException("x, y, z must be >= 0");
      R r = new R("Stoichiometry of C" + g(x) + "H" + g(y) + "O" + g(z));
      double mf = 12.011 * x + 1.008 * y + 15.999 * z, o2 = x + y / 4 - z / 2;
      if (o2 <= 0) throw new IllegalArgumentException("x + y/4 - z/2 <= 0: no oxygen demand");
      double afr = o2 / 0.2095 * 28.965 / mf;
      r.put("Mfuel", mf, "kg/kmol", "12.011x + 1.008y + 15.999z");
      r.put("O2", o2, "-", "kmol O2 per kmol fuel x + y/4 - z/2");
      r.put("AFRst", afr, "-", "stoichiometric air/fuel mass ratio (dry air, 20.95 % O2, M = 28.965)");
      r.put("FARst", 1 / afr, "-", "fuel/air");
      double turns = o2 * 4.76 * 28.85 / mf;
      r.put("AFRstTurns", turns, "-", "same with 4.76 kmol air/kmol O2 and M_air = 28.85 (Turns convention)");
      r.f("AFR_st = (x + y/4 - z/2)/0.2095 * M_air/M_fuel ; M_air = 28.965 ; phi = AFR_st / (m_air/m_fuel)");
      r.src("mass balance; SimKit spec B.2.9; textbook variant (Turns, An Introduction to Combustion) shown for comparison");
      r.line("the two conventions differ by ~0.7 %: the textbook 14.57 (C12H23) / 14.69 (C10H20) use 4.76 x 28.85");
      if (given(mAir) && given(mFuel)) {
        double phi = afr / (pos("air flow", mAir) / pos("fuel flow", mFuel));
        r.put("phi", phi, "-", "equivalence ratio AFR_st / AFR");
        r.put("AFR", mAir / mFuel, "-", "actual air/fuel");
      }
      if (given(mFuel) && given(lhv)) r.put("Qrelease", mFuel * lhv, "W", "m_fuel LHV (complete combustion)");
      return r;
    }

    // Lanczos approximation (g = 7, n = 9)
    static final double[] LANCZOS = {0.99999999999980993, 676.5203681218851, -1259.1392167224028, 771.32342877765313,
        -176.61502916214059, 12.507343278686905, -0.13857109526572012, 9.9843695780195716e-6, 1.5056327351493116e-7};

    static double gamma(double x) {
      if (x == Math.rint(x) && x <= 0) throw new IllegalArgumentException("Gamma undefined at " + x);
      if (x < 0.5) return Math.PI / (Math.sin(Math.PI * x) * gamma(1 - x));
      x -= 1;
      double a = LANCZOS[0], t = x + 7.5;
      for (int i = 1; i < 9; i++) a += LANCZOS[i] / (x + i);
      return Math.sqrt(2 * Math.PI) * Math.pow(t, x + 0.5) * Math.exp(-t) * a;
    }

    static R rosinRammler(boolean fromSmd, double size, double n, double d, double F) {
      pos("size", size);
      if (!(n > 1)) throw new IllegalArgumentException("n must be > 1 (SMD = X / Gamma(1 - 1/n) needs n > 1)");
      R r = new R("Rosin-Rammler");
      double gm = gamma(1 - 1 / n), X = fromSmd ? size * gm : size, smd = X / gm;
      r.put("X", X, "m", "characteristic diameter (63.2 % of the mass below)");
      r.put("SMD", smd, "m", "Sauter mean diameter X / Gamma(1 - 1/n)");
      r.put("Gamma", gm, "-", "Gamma(1 - 1/n) (Lanczos)");
      r.put("d50", X * Math.pow(Math.log(2), 1 / n), "m", "mass median diameter X (ln 2)^(1/n)");
      if (given(d)) r.put("fracAbove", Math.exp(-Math.pow(pos("d", d) / X, n)), "-", "mass fraction above d = exp(-(d/X)^n)");
      if (given(F)) {
        if (!(F > 0 && F < 1)) throw new IllegalArgumentException("exceedance fraction must be in (0, 1)");
        r.put("dF", X * Math.pow(-Math.log(F), 1 / n), "m", "diameter exceeded by mass fraction F: X (-ln F)^(1/n)");
      }
      r.f("Q(>d) = exp(-(d/X)^n) ; SMD = X / Gamma(1 - 1/n) ; d_F = X (-ln F)^(1/n)");
      r.src("Rosin & Rammler 1933; SMD relation e.g. Lefebvre & McDonell, Atomization and Sprays");
      r.valid("n > 1; typical pressure atomisers n = 1.5-4");
      if (n > 6) r.warn("n > 6 is unusually narrow for a spray");
      return r;
    }

    static R flowNumber(double mdot, double rho, double dp) {
      pos("mass flow", mdot); pos("density", rho); pos("pressure drop", dp);
      R r = new R("Flow number");
      double fn = mdot / Math.sqrt(rho * dp);
      r.put("FN", fn, "m^2", "m / sqrt(rho_L dP)");
      r.put("FNus", (mdot * 7936.641439) / Math.sqrt(dp / 6894.757293), "lb/h/psi^0.5", "US flow number (lb/h)/sqrt(psi)");
      r.f("FN = m_dot / sqrt(rho_L dP)").src("Lefebvre & McDonell, Atomization and Sprays");
      r.valid("pressure (simplex) atomiser; FN constant at fixed geometry for turbulent nozzle flow");
      return r;
    }

    // ----- 10. Units --------------------------------------------------------------------------------------

    static final String[] CONV_NAMES = {"BTU/(ft^2 s) -> kW/m^2", "kW/m^2 -> BTU/(ft^2 s)", "BTU/h -> W", "W -> BTU/h",
        "W/cm^2 -> kW/m^2", "kW/m^2 -> W/cm^2", "psi -> Pa", "Pa -> psi", "ft/min -> m/s", "m/s -> ft/min", "in -> mm", "mm -> in"};
    static final double[] CONV_F = {11.3565, 1 / 11.3565, 0.29307107, 1 / 0.29307107, 10, 0.1, 6894.757, 1 / 6894.757,
        0.00508, 1 / 0.00508, 25.4, 1 / 25.4};
    static final String[] CONV_OUT = {"kW/m^2", "BTU/ft2s", "W", "BTU/h", "kW/m^2", "W/cm^2", "Pa", "psi", "m/s", "ft/min", "mm", "in"};

    static R convert(int idx, double v) {
      R r = new R(CONV_NAMES[idx]);
      String u = CONV_OUT[idx];
      double out = v * CONV_F[idx];
      // SI items for To parameter
      if (u.equals("kW/m^2")) r.put("value", out * 1000, "W/m^2", g(out) + " kW/m^2");
      else if (u.equals("mm")) r.put("value", out / 1000, "m", g(out) + " mm");
      else if (u.equals("W") || u.equals("Pa") || u.equals("m/s")) r.put("value", out, u, "");
      else r.put("value", out, "-", u + " (not SI)");
      r.line(g(v) + " " + CONV_NAMES[idx].split(" -> ")[0] + " = " + g(out) + " " + u);
      r.f("factor " + g(CONV_F[idx])).src("1 BTU/(ft^2 s) = 11.3565 kW/m^2 ; 1 BTU/h = 0.29307107 W ; 1 psi = 6894.757 Pa ; 1 ft/min = 0.00508 m/s ; 1 in = 25.4 mm");
      return r;
    }

    static R temperature(double v, int unit) {
      double k = unit == 0 ? v : unit == 1 ? v + 273.15 : (v - 32) / 1.8 + 273.15;
      if (k < 0) throw new IllegalArgumentException("below absolute zero");
      R r = new R("Temperature");
      r.put("K", k, "K", "kelvin").put("degC", k - 273.15, "-", "degC (not SI)").put("degF", (k - 273.15) * 1.8 + 32, "-", "degF (not SI)");
      r.f("T[K] = T[degC] + 273.15 ; T[degF] = 1.8 T[degC] + 32").src("definition");
      return r;
    }

    static R galPerHour(double q, double rho) {
      pos("density", rho);
      R r = new R("US gal/h -> kg/s");
      double v = q * 3.785411784e-3 / 3600;
      r.put("Vdot", v, "m^3/s", "volume flow").put("mdot", v * rho, "kg/s", "mass flow");
      r.f("m = Q[gal/h] * 3.785411784e-3 / 3600 * rho").src("1 US gal = 3.785411784 L (exact)");
      return r;
    }

    static R scfm(double q, double tF, double pPsi, double M) {
      pos("p_std", pPsi); pos("M", M);
      R r = new R("SCFM -> kg/s");
      double T = (tF - 32) / 1.8 + 273.15, p = pPsi * 6894.757293, rho = p * M / (RU * pos("T_std", T)), v = q * 0.028316846592 / 60;
      r.put("Tstd", T, "K", "standard temperature").put("pstd", p, "Pa", "standard pressure");
      r.put("rhoStd", rho, "kg/m^3", "ideal-gas density p M / (R_u T)");
      r.put("Vdot", v, "m^3/s", "standard volume flow").put("mdot", v * rho, "kg/s", "mass flow");
      r.f("rho_std = p M / (R_u T), R_u = 8314.462618 J/kmol-K ; m = SCFM * 0.028316846592/60 * rho_std");
      r.src("1 ft^3 = 0.028316846592 m^3 ; 1 psi = 6894.757 Pa ; standard conditions are an input (60 degF, 14.696 psi by default)");
      r.line("air at 60 degF / 14.696 psi: rho = 1.2228 kg/m^3 with M = 28.965 (the often quoted 1.2232 uses M = 28.97 / older R)");
      return r;
    }

    // ----- units -> dimensions (M, L, T, Theta) for To parameter ----------------------------------------

    static int[] dimsOf(String unit) {
      switch (unit) {
        case "m": return new int[] {0, 1, 0, 0};
        case "m^2": return new int[] {0, 2, 0, 0};
        case "m^3": return new int[] {0, 3, 0, 0};
        case "s": return new int[] {0, 0, 1, 0};
        case "K": return new int[] {0, 0, 0, 1};
        case "kg/s": return new int[] {1, 0, -1, 0};
        case "Pa": return new int[] {1, -1, -2, 0};
        case "m/s": return new int[] {0, 1, -1, 0};
        case "kg/m^3": return new int[] {1, -3, 0, 0};
        case "Pa-s": return new int[] {1, -1, -1, 0};
        case "m^2/s": return new int[] {0, 2, -1, 0};
        case "m^3/s": return new int[] {0, 3, -1, 0};
        case "W/m^2": return new int[] {1, 0, -3, 0};
        case "W/m^2-K": return new int[] {1, 0, -3, -1};
        case "W/m-K": return new int[] {1, 1, -3, -1};
        case "J/kg-K": return new int[] {0, 2, -2, -1};
        case "J/kg": return new int[] {0, 2, -2, 0};
        case "m^2/s^3": return new int[] {0, 2, -3, 0};
        case "1/s": case "Hz": return new int[] {0, 0, -1, 0};
        case "W": return new int[] {1, 2, -3, 0};
        case "N/m": return new int[] {1, 0, -2, 0};
        case "1/K": return new int[] {0, 0, 0, -1};
        case "kg/kmol": return new int[] {0, 0, 0, 0};   // molar mass: kept dimensionless (no mole dimension used here)
        default: return new int[] {0, 0, 0, 0};          // "-", "%", "", "core-h", "h" (hours), ...: dimensionless
      }
    }

    // ----- self-check (documented reference examples) -----------------------------------------------------

    static String selfCheck() {
      StringBuilder sb = new StringBuilder("SimKitCalc self-check (Core, reference examples)\n");
      R a = gci(18000, 8000, 4500, 6.063, 5.972, 5.863, 2);
      sb.append(String.format(Locale.ROOT, "GCI Celik 2008 Table 1 (2-D, r21=1.5): p=%.4f (1.53) phi_ext=%.4f (6.1685) e_a=%.2f%% (1.5) GCI=%.2f%% (2.2)%n",
          a.get("p"), a.get("phiExt21"), a.get("ea21"), a.get("GCIfine21")));
      sb.append(String.format(Locale.ROOT, "Gamma(0.5)=%.12f (sqrt(pi)=%.12f)  Gamma(1)=%.12f%n", gamma(0.5), Math.sqrt(Math.PI), gamma(1)));
      R c1 = stoich(12, 23, 0, Double.NaN, Double.NaN, Double.NaN), c2 = stoich(10, 20, 0, Double.NaN, Double.NaN, Double.NaN);
      sb.append(String.format(Locale.ROOT, "AFR C12H23 = %.3f (spec formula) / %.3f (4.76 x 28.85: 14.57) ; C10H20 = %.3f / %.3f (14.69)%n",
          c1.get("AFRst"), c1.get("AFRstTurns"), c2.get("AFRst"), c2.get("AFRstTurns")));
      sb.append(String.format(Locale.ROOT, "Rosin-Rammler SMD 38 um, n 3.5 -> X = %.2f um (~48)%n",
          1e6 * rosinRammler(true, 38e-6, 3.5, Double.NaN, Double.NaN).get("X")));
      sb.append(String.format(Locale.ROOT, "Flow number 1.89 g/s, 800 kg/m3, 689476 Pa -> %.4e m^2 (~8.0e-8)%n",
          flowNumber(1.89e-3, 800, 689476).get("FN")));
      sb.append(String.format(Locale.ROOT, "Churchill-Bernstein Re=1e4 Pr=0.7 -> Nu = %.3f%n", churchillBernstein(1e4, 0.7)));
      sb.append(String.format(Locale.ROOT, "AST with q_inc = sigma Tg^4, Tg = 1000 K -> %.6f K%n",
          ast(SIGMA * 1e12, Double.NaN, 25, 1000, 0.9, Double.NaN).get("TAST")));
      R pf = prismForward(1e-5, 1.2, 15, Double.NaN);
      sb.append(String.format(Locale.ROOT, "Prism y1=1e-5 r=1.2 N=15: T=%.6e ; solve r -> %.9f ; solve N -> %d%n", pf.get("total"),
          prismSolveR(1e-5, pf.get("total"), 15, Double.NaN).get("r"), (int) prismSolveN(1e-5, 1.2, pf.get("total"), Double.NaN).get("N")));
      return sb.toString();
    }
  }

  // =====================================================================================================
  // Library: config\fluids.csv, materials.csv, emissivity.csv (no STAR types)
  // =====================================================================================================

  static final class Library {
    static final String[] PROP_COLS = {"property", "model", "coefficients", "T_unit", "T_min", "T_max", "unit", "source", "note"};
    static final Set<String> MODELS = new HashSet<>(Arrays.asList("const", "table", "linear", "sutherland", "andrade", "idealgas", "polyC"));

    /** One property row. */
    static final class Prop {
      String owner, prop, model, coeffText, tUnit, unit, source, note;
      double[] c;              // coefficients (table: T values in tab[0], values in tab[1])
      double[][] tab;
      double tMin = Double.NaN, tMax = Double.NaN;
      boolean celsius;

      String range() {
        return (Double.isNaN(tMin) && Double.isNaN(tMax)) ? "any T"
            : (Double.isNaN(tMin) ? "-" : Core.g(tMin)) + ".." + (Double.isNaN(tMax) ? "-" : Core.g(tMax)) + " " + (celsius ? "degC" : "K");
      }
    }

    static final class Emis {
      String surface, source, note;
      double typ, min, max;
    }

    final Map<String, Map<String, Prop>> fluids = new LinkedHashMap<>();
    final Map<String, Map<String, Prop>> materials = new LinkedHashMap<>();
    final List<Emis> emissivity = new ArrayList<>();
    final List<String> errors = new ArrayList<>();
    final List<String> loaded = new ArrayList<>();
    Path dir;

    static Library load(Path configDir) {
      Library l = new Library();
      l.dir = configDir;
      String[] files = {"fluids.csv", "materials.csv", "emissivity.csv"};
      for (String fn : files) {
        Path p = configDir.resolve(fn);
        String text;
        try {
          text = Fs.read(p);
        } catch (IOException e) {
          l.errors.add(fn + ": not found / unreadable at " + p + " (" + e.getClass().getSimpleName() + ")");
          continue;
        }
        l.loaded.add(fn);
        if (fn.equals("emissivity.csv")) l.emissivity.addAll(parseEmissivity(text, fn, l.errors));
        else {
          Map<String, Map<String, Prop>> target = fn.equals("fluids.csv") ? l.fluids : l.materials;
          for (Prop pr : parseProps(text, fn, l.errors)) target.computeIfAbsent(pr.owner, k -> new LinkedHashMap<>()).put(pr.prop, pr);
        }
      }
      return l;
    }

    String summary() {
      StringBuilder sb = new StringBuilder("library " + dir + ": " + fluids.size() + " fluids, " + materials.size()
          + " materials, " + emissivity.size() + " emissivity rows (" + String.join(", ", loaded) + ")");
      for (String e : errors) sb.append("\n  library WARNING: ").append(e);
      return sb.toString();
    }

    /** Parses fluids.csv / materials.csv; every unparseable row is reported in 'errors' and skipped. */
    static List<Prop> parseProps(String text, String file, List<String> errors) {
      List<Prop> out = new ArrayList<>();
      List<String[]> rows = Csv.parse(text);
      if (rows.isEmpty()) { errors.add(file + ": empty"); return out; }
      String[] h = rows.get(0);
      Map<String, Integer> col = new LinkedHashMap<>();
      for (int i = 0; i < h.length; i++) col.put(h[i].trim(), i);
      for (String c : PROP_COLS) {
        if (!col.containsKey(c)) { errors.add(file + ": header lacks column '" + c + "'"); return out; }
      }
      for (int i = 1; i < rows.size(); i++) {
        String[] r = rows.get(i);
        String where = file + " row " + (i + 1);
        try {
          if (r.length != h.length) throw new IllegalArgumentException(r.length + " columns, header has " + h.length);
          Prop p = new Prop();
          p.owner = r[0].trim();
          p.prop = get(r, col, "property");
          p.model = get(r, col, "model");
          p.coeffText = get(r, col, "coefficients");
          p.tUnit = get(r, col, "T_unit");
          p.unit = get(r, col, "unit");
          p.source = get(r, col, "source");
          p.note = get(r, col, "note");
          if (p.owner.isEmpty() || p.prop.isEmpty()) throw new IllegalArgumentException("empty name or property");
          if (!MODELS.contains(p.model)) throw new IllegalArgumentException("unknown model '" + p.model + "'");
          if (!p.tUnit.equals("K") && !p.tUnit.equals("C")) throw new IllegalArgumentException("T_unit must be K or C");
          p.celsius = p.tUnit.equals("C");
          p.tMin = optNum(get(r, col, "T_min"), "T_min");
          p.tMax = optNum(get(r, col, "T_max"), "T_max");
          if (!Double.isNaN(p.tMin) && !Double.isNaN(p.tMax) && p.tMin > p.tMax) throw new IllegalArgumentException("T_min > T_max");
          parseCoefficients(p);
          // evaluate once in range to catch nonsense (non-finite / non-positive where physical)
          double tTest = !Double.isNaN(p.tMin) && !Double.isNaN(p.tMax) ? 0.5 * (p.tMin + p.tMax) : !Double.isNaN(p.tMin) ? p.tMin
              : !Double.isNaN(p.tMax) ? p.tMax : (p.celsius ? 20 : 300);
          double v = eval(p, p.celsius ? tTest + 273.15 : tTest, 101325, new ArrayList<>());
          if (Double.isNaN(v) || Double.isInfinite(v)) throw new IllegalArgumentException("evaluates to " + v);
          out.add(p);
        } catch (RuntimeException e) {
          errors.add(where + ": " + e.getMessage());
        }
      }
      return out;
    }

    static String get(String[] r, Map<String, Integer> col, String name) { return r[col.get(name)].trim(); }

    static double optNum(String s, String what) {
      if (s.isEmpty()) return Double.NaN;
      try { return Double.parseDouble(s); } catch (NumberFormatException e) { throw new IllegalArgumentException(what + " '" + s + "' is not a number"); }
    }

    static double[] nums(String s, int min, int max, String model) {
      String[] parts = s.split(",");
      if (parts.length < min || parts.length > max) {
        throw new IllegalArgumentException(model + " needs " + (min == max ? "" + min : min + ".." + max) + " coefficients, got " + parts.length);
      }
      double[] c = new double[parts.length];
      for (int i = 0; i < parts.length; i++) {
        try { c[i] = Double.parseDouble(parts[i].trim()); } catch (NumberFormatException e) {
          throw new IllegalArgumentException("coefficient '" + parts[i].trim() + "' is not a number");
        }
      }
      return c;
    }

    static void parseCoefficients(Prop p) {
      switch (p.model) {
        case "const": p.c = nums(p.coeffText, 1, 1, "const"); break;
        case "idealgas": p.c = nums(p.coeffText, 1, 1, "idealgas(M)"); if (p.c[0] <= 0) throw new IllegalArgumentException("M <= 0"); break;
        case "linear": p.c = nums(p.coeffText, 3, 3, "linear(v0,T0,slope)"); break;
        case "sutherland": p.c = nums(p.coeffText, 3, 3, "sutherland(x0,T0,S)"); break;
        case "andrade": p.c = nums(p.coeffText, 2, 2, "andrade(A,B)"); break;
        case "polyC": p.c = nums(p.coeffText, 1, 12, "polyC(c0,c1,...)"); break;
        case "table": {
          String[] pts = p.coeffText.split(";");
          if (pts.length < 2) throw new IllegalArgumentException("table needs at least 2 points T:v");
          p.tab = new double[2][pts.length];
          for (int i = 0; i < pts.length; i++) {
            String[] tv = pts[i].split(":");
            if (tv.length != 2) throw new IllegalArgumentException("table point '" + pts[i] + "' is not T:v");
            try {
              p.tab[0][i] = Double.parseDouble(tv[0].trim());
              p.tab[1][i] = Double.parseDouble(tv[1].trim());
            } catch (NumberFormatException e) {
              throw new IllegalArgumentException("table point '" + pts[i] + "' is not numeric");
            }
            if (i > 0 && p.tab[0][i] <= p.tab[0][i - 1]) throw new IllegalArgumentException("table T values must increase");
          }
          break;
        }
        default: throw new IllegalArgumentException("unknown model " + p.model);
      }
    }

    /** Value of a property at T (kelvin) and p (Pa). T is clamped to [T_min, T_max] (and to the table range) with a warning. */
    static double eval(Prop p, double tK, double pPa, List<String> warns) {
      if (Double.isNaN(tK)) throw new IllegalArgumentException("temperature required");
      double T = p.celsius ? tK - 273.15 : tK, tu = T;
      String uName = p.celsius ? " degC" : " K";
      if (!Double.isNaN(p.tMin) && T < p.tMin) tu = p.tMin;
      if (!Double.isNaN(p.tMax) && T > p.tMax) tu = p.tMax;
      if (p.model.equals("table")) {
        double lo = p.tab[0][0], hi = p.tab[0][p.tab[0].length - 1];
        if (tu < lo) tu = lo;
        if (tu > hi) tu = hi;
      }
      if (tu != T) {
        warns.add(p.owner + "." + p.prop + ": T = " + Core.g(T) + uName + " outside the data range " + p.range()
            + " -> CLAMPED to " + Core.g(tu) + uName);
      }
      double tAbs = p.celsius ? tu + 273.15 : tu;
      double[] c = p.c;
      switch (p.model) {
        case "const": return c[0];
        case "linear": return c[0] + c[2] * (tu - c[1]);
        case "sutherland": return c[0] * Math.pow(tAbs / c[1], 1.5) * (c[1] + c[2]) / (tAbs + c[2]);
        case "andrade": return Math.exp(c[0] + c[1] / tAbs);
        case "idealgas":
          if (Double.isNaN(pPa)) throw new IllegalArgumentException(p.owner + "." + p.prop + ": ideal-gas density needs the pressure");
          return pPa * c[0] / (Core.RU * tAbs);
        case "polyC": {
          double v = 0, x = 1;
          for (double ci : c) { v += ci * x; x *= tu; }
          return v;
        }
        case "table": {
          double[] ts = p.tab[0], vs = p.tab[1];
          for (int i = 1; i < ts.length; i++) {
            if (tu <= ts[i]) return vs[i - 1] + (vs[i] - vs[i - 1]) * (tu - ts[i - 1]) / (ts[i] - ts[i - 1]);
          }
          return vs[vs.length - 1];
        }
        default: throw new IllegalArgumentException("unknown model " + p.model);
      }
    }

    static String describe(Prop p) {
      return p.model + "(" + p.coeffText + ")";
    }

    /** Core.Props backed by a fluid of the library: rho (or idealgas), mu (or nu*rho), k, cp. */
    Core.Props fluidProps(String name, double pPa) {
      Map<String, Prop> m = fluids.get(name);
      if (m == null) throw new IllegalArgumentException("fluid '" + name + "' not in fluids.csv");
      Core.Props pr = new Core.Props() {
        @Override
        double[] at(double T) {
          double rho = ev("rho", T), mu = m.containsKey("mu") ? ev("mu", T) : ev("nu", T) * rho;
          double k = needKcp ? ev("k", T) : Double.NaN, cp = needKcp ? ev("cp", T) : Double.NaN;
          dedupe(warns);
          return new double[] {rho, mu, k, cp};
        }

        /** Evaluates one property (NaN if the fluid has no such row) and cites its source once. */
        double ev(String key, double T) {
          Prop p = m.get(key);
          if (p == null) return Double.NaN;
          String s = name + "." + key + " = " + describe(p) + " [" + p.unit + "], valid " + p.range() + "; " + p.source;
          if (!sources.contains(s)) sources.add(s);
          return eval(p, T, pPa, warns);
        }
      };
      return pr;
    }

    static void dedupe(List<String> l) {
      List<String> u = new ArrayList<>();
      for (String s : l) if (!u.contains(s)) u.add(s);
      l.clear();
      l.addAll(u);
    }

    Core.R fluidReport(String name, double tK, double pPa) {
      Map<String, Prop> m = fluids.get(name);
      if (m == null) throw new IllegalArgumentException("choose a fluid (fluids.csv loaded: " + loaded.contains("fluids.csv") + ")");
      Core.R r = new Core.R("Fluid " + name + " at T = " + Core.g(tK) + " K" + (Double.isNaN(pPa) ? "" : ", p = " + Core.g(pPa) + " Pa"));
      Map<String, Double> v = new LinkedHashMap<>();
      for (Prop p : m.values()) {
        List<String> w = new ArrayList<>();
        try {
          double x = eval(p, tK, pPa, w);
          v.put(p.prop, x);
          r.put(p.prop, x, siUnit(p.unit), p.unit + " ; " + describe(p) + " ; valid " + p.range());
          r.lines.add("Source:   " + p.prop + ": " + p.source + (p.note.isEmpty() ? "" : " -- " + p.note));
        } catch (IllegalArgumentException e) {
          w.add(p.prop + ": " + e.getMessage());
        }
        for (String s : w) r.warn(s);
      }
      Double rho = v.get("rho"), mu = v.get("mu"), nu = v.get("nu"), k = v.get("k"), cp = v.get("cp");
      if (mu == null && nu != null && rho != null) { mu = nu * rho; r.put("mu", mu, "Pa-s", "derived nu rho"); }
      if (nu == null && mu != null && rho != null) r.put("nu", mu / rho, "m^2/s", "derived mu / rho");
      if (k != null && rho != null && cp != null) r.put("alpha", k / (rho * cp), "m^2/s", "derived k/(rho cp)");
      if (mu != null && k != null && cp != null) r.put("Pr", mu * cp / k, "-", "derived mu cp / k");
      r.valid("each row is valid in its T range (shown); outside it the value is CLAMPED and a warning is given");
      return r;
    }

    Core.R materialReport(String name, double tK) {
      Map<String, Prop> m = materials.get(name);
      if (m == null) throw new IllegalArgumentException("choose a material (materials.csv loaded: " + loaded.contains("materials.csv") + ")");
      Core.R r = new Core.R("Material " + name + " at T = " + Core.g(tK) + " K (" + Core.g(tK - 273.15) + " degC)");
      Map<String, Double> v = new LinkedHashMap<>();
      for (Prop p : m.values()) {
        List<String> w = new ArrayList<>();
        double x = eval(p, tK, Double.NaN, w);
        v.put(p.prop, x);
        r.put(p.prop, x, siUnit(p.unit), p.unit + " ; " + describe(p) + (p.celsius ? " (T in degC)" : "") + " ; valid " + p.range());
        r.lines.add("Source:   " + p.prop + ": " + p.source + (p.note.isEmpty() ? "" : " -- " + p.note));
        for (String s : w) r.warn(s);
      }
      Double rho = v.get("rho"), cp = v.get("cp");
      for (String kk : new String[] {"k", "k_zz", "k_xy"}) {
        Double k = v.get(kk);
        if (k != null && rho != null && cp != null) r.put("alpha_" + kk, k / (rho * cp), "m^2/s", "derived " + kk + "/(rho cp)");
      }
      r.valid("each row is valid in its T range (shown); outside it the value is CLAMPED and a warning is given");
      r.lines.add("");
      r.lines.add("Emissivity (config\\emissivity.csv): typical [min-max] -- source -- note");
      for (Emis e : emissivity) {
        r.lines.add(String.format(Locale.ROOT, "  %-36s %.2f [%.2f-%.2f] -- %s%s", e.surface, e.typ, e.min, e.max, e.source,
            e.note.isEmpty() ? "" : " -- " + e.note));
      }
      return r;
    }

    /** SimKit unit spelling (W/m.K, J/kg.K, Pa.s, kg/m3, m2/s) -> STAR-style name used by To parameter. */
    static String siUnit(String u) {
      switch (u) {
        case "kg/m3": return "kg/m^3";
        case "Pa.s": return "Pa-s";
        case "W/m.K": return "W/m-K";
        case "J/kg.K": return "J/kg-K";
        case "m2/s": return "m^2/s";
        default: return u;
      }
    }

    static List<Emis> parseEmissivity(String text, String file, List<String> errors) {
      List<Emis> out = new ArrayList<>();
      List<String[]> rows = Csv.parse(text);
      if (rows.isEmpty()) { errors.add(file + ": empty"); return out; }
      String[] h = rows.get(0);
      String[] need = {"surface", "typical", "min", "max", "source", "note"};
      Map<String, Integer> col = new LinkedHashMap<>();
      for (int i = 0; i < h.length; i++) col.put(h[i].trim(), i);
      for (String c : need) if (!col.containsKey(c)) { errors.add(file + ": header lacks column '" + c + "'"); return out; }
      for (int i = 1; i < rows.size(); i++) {
        String[] r = rows.get(i);
        try {
          if (r.length != h.length) throw new IllegalArgumentException(r.length + " columns, header has " + h.length);
          Emis e = new Emis();
          e.surface = get(r, col, "surface");
          e.typ = optNum(get(r, col, "typical"), "typical");
          e.min = optNum(get(r, col, "min"), "min");
          e.max = optNum(get(r, col, "max"), "max");
          e.source = get(r, col, "source");
          e.note = get(r, col, "note");
          if (e.surface.isEmpty() || Double.isNaN(e.typ) || Double.isNaN(e.min) || Double.isNaN(e.max)) {
            throw new IllegalArgumentException("surface, typical, min and max are required");
          }
          if (!(0 <= e.min && e.min <= e.typ && e.typ <= e.max && e.max <= 1)) {
            throw new IllegalArgumentException("need 0 <= min <= typical <= max <= 1");
          }
          out.add(e);
        } catch (RuntimeException ex) {
          errors.add(file + " row " + (i + 1) + ": " + ex.getMessage());
        }
      }
      return out;
    }
  }

  // =====================================================================================================
  // Helpers (same behaviour as in SimKit.java)
  // =====================================================================================================

  static final class Cancel extends RuntimeException {
    private static final long serialVersionUID = 1L;

    Cancel(String msg) { super(msg); }
  }

  static final class Log {
    final Simulation sim;

    Log(Simulation sim) { this.sim = sim; }

    void line(String s) {
      try { sim.println("[SimKitCalc] " + s); } catch (Throwable t) { System.out.println("[SimKitCalc] " + s); }   // [U52]
    }
  }

  static final class Fs {
    static Path share() {
      String s = System.getenv("SIMKIT_SHARE");
      if (s == null || s.trim().isEmpty()) s = System.getProperty("simkit.share", SHARE_ROOT);
      return Paths.get(s.trim());
    }

    static Path kit() { return share().resolve("_SIMKIT"); }
    static Path projectsDir() { return share().resolve("PROJECTS"); }
    static String user() { return System.getProperty("user.name", "unknown"); }

    /** Comparison key of a path: case-insensitive on Windows only (Linux file systems are case-sensitive). */
    static String key(Path p) {
      String s = p.toAbsolutePath().normalize().toString();
      return java.io.File.separatorChar == '\\' ? s.toLowerCase(Locale.ROOT) : s;
    }

    static boolean under(Path p, Path root) {
      String r = key(root), sep = java.io.File.separator;
      return key(p).startsWith(r.endsWith(sep) ? r : r + sep);
    }

    static String read(Path p) throws IOException {
      String s = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
      return s.startsWith("\uFEFF") ? s.substring(1) : s;
    }

    /** Appends a CSV row (header first if the file is new). Write guard: only inside the case folder. */
    static void appendCsv(Path f, Path caseDir, String[] header, String[] row) throws IOException {
      if (!under(f, caseDir)) throw new IOException("refused to write outside the case folder " + caseDir + ": " + f);
      Path g = f.toAbsolutePath().normalize();
      Files.createDirectories(g.getParent());
      String text = (Files.exists(g) ? "" : Csv.row(header)) + Csv.row(row);
      Files.write(g, text.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
  }

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
      if (row.size() == 1 && row.get(0).trim().isEmpty()) return;
      out.add(row.toArray(new String[0]));
    }
  }

  static final class Fmt {
    static String iso() { return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()); }
    static String num(double v) { return String.format(Locale.ROOT, "%.6g", v).replaceAll("(\\.\\d*?)0+(e|$)", "$1$2").replaceAll("\\.(e|$)", "$1"); }
    static String cut(String s, int n) { return s == null ? "" : s.length() <= n ? s : s.substring(0, n - 3) + "..."; }

    static double parse(String s) {
      try { return s == null ? Double.NaN : Double.parseDouble(s.trim()); } catch (NumberFormatException e) { return Double.NaN; }
    }
  }

  static final class Ui {
    static String choose(String title, String msg, String[] options) {
      if (options.length == 0) throw new Cancel("nothing to choose from");
      Object r = JOptionPane.showInputDialog(null, msg, title, JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
      if (r == null) throw new Cancel(null);
      return (String) r;
    }

    static boolean confirm(String title, String msg) {
      return JOptionPane.showConfirmDialog(null, msg, title, JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION;
    }
  }

  /** Reflection for getters whose 2402 name is not certain (as in SimKit.java). */
  static final class Reflect {
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

    static List<Object> items(Object res) {
      List<Object> out = new ArrayList<>();
      if (res instanceof Iterable) for (Object o : (Iterable<?>) res) out.add(o);
      else if (res instanceof Object[]) out.addAll(Arrays.asList((Object[]) res));
      else if (res != null) out.add(res);
      return out;
    }
  }
}
