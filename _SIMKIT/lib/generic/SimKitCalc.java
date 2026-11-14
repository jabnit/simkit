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
//   To parameter... (create/update a global parameter p_<name> from a result), Copy value for STAR-CCM+...
//   (one result as a bare number + the STAR unit to select), Copy all results, Close. The self-check of the
//   reference examples runs in batch (starccm+ -batch SimKitCalc.java).
//   Traceability: every Compute appends a row (timestamp, user, tab, inputs, outputs) to
//   <case>/record/calc_log.csv when the sim is a SimKit case (<CASEID>/sim/), and always
//   prints it to the Output window. Without a case folder nothing is written to disk.
//
// Version 0.2 (2026-11-11). The physics (class Core) and the data loading (class Library) have NO STAR-CCM+
// imports and were unit-tested with a plain JDK 11 compiler; the STAR-CCM+ calls are UNTESTED in STAR.
//
// Data (read at start; "Choose data folder..." on the fluid tab reloads them; MatML *.xml material files are read
//   from the folders listed in config/material_folders.csv and from the user's own folder, "MatML folder..."):
//   <root>/lib/generic/data/fluids.csv, materials.csv, emissivity.csv. <root> = the SimKit root, found from this
//   file's own location (two folders up); no variable or path to set.
//
// Threading: the window is NON-modal and floats next to STAR-CCM+; the macro ends as soon as it is shown, so the
//   model stays usable. Compute is pure Java. From parameter / From report / To parameter call STAR-CCM+ directly
//   from the button (short calls, as in a Simulation Assistant).
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
// Rules (SimKit): Java 11, no external libraries, never deletes anything, writes only <case>/record/calc_log.csv.
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
  /** SimKit root, found from this file's own location (STAR-CCM+ resolvePath; no variable to set). */
  static Path ROOT;
  /** Property tables, relative to the SimKit root. */
  static final String DATA_DIR = "lib/generic/data";
  static final Pattern CASE_ID = Pattern.compile("^([A-Z][A-Z0-9]{1,9})-(?:([A-Z0-9]{2,3})-)?([0-9]{3})$");   // ISO-007 (old ISO-CF-001)
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
  final List<Tab> tabs = new ArrayList<>();
  Tab current;                                   // page shown
  JPanel cards;                                  // one card per page
  javax.swing.JList<Object> side;                // sidebar: group headings, pages, the "More" row
  final javax.swing.DefaultListModel<Object> sideModel = new javax.swing.DefaultListModel<>();
  JTextField filter;
  boolean moreOpen, sideSync;
  final List<JComboBox<String>> mixedFluidCombos = new ArrayList<>();   // ENTERED + fluids
  final List<JComboBox<String>> fluidCombos = new ArrayList<>();        // fluids only
  final List<JComboBox<String>> materialCombos = new ArrayList<>();

  @Override
  public void execute() {
    int preloaded = preloadOwnClasses(getClass());   // before anything else: see preloadOwnClasses
    sim = getActiveSimulation();
    log = new Log(sim);
    ROOT = Paths.get(resolvePath("../..")).toAbsolutePath().normalize();   // this file is <root>/lib/generic/
    String batchReason = headlessReason();
    try {
      lib = Library.load(Fs.inKit(DATA_DIR));
    } catch (RuntimeException e) {
      lib = new Library();
      lib.errors.add("library not loaded: " + reason(e));
    }
    loadMatMLFolders();
    detectCase();
    log.line(VERSION + " | kit " + Fs.kit() + " | " + (batchReason == null ? "interactive" : "batch: " + batchReason)
        + " | classes preloaded: " + preloaded);
    log.line(lib.summary());
    log.line(caseNote);
    if (batchReason != null) {
      log.line("No window in batch mode: printing the self-check instead.");
      log.line(Core.selfCheck());
      return;
    }
    Object old = System.getProperties().get(WINDOW_KEY);   // one calculator window per session
    if (old instanceof java.awt.Window) ((java.awt.Window) old).dispose();
    buildWindow();
    System.getProperties().put(WINDOW_KEY, dlg);
    dlg.setVisible(true);   // non-modal: the macro ends here and STAR-CCM+ stays free to use
    log.line("Calculators open (the window floats next to STAR-CCM+).");
  }

  static final String WINDOW_KEY = "simkit.calc.window";

  /** "Always on top" preference, shared with SimKit.java (per user; default off). */
  static boolean onTopPref() {
    try { return "true".equals(java.util.prefs.Preferences.userRoot().node("simkit").get("alwaysOnTop", null)); }
    catch (Throwable t) { return false; }
  }

  /** Saves the preference and applies it to every open SimKit / calculator window. */
  static void setOnTopPref(boolean on) {
    try { java.util.prefs.Preferences.userRoot().node("simkit").put("alwaysOnTop", String.valueOf(on)); } catch (Throwable t) { /* not kept */ }
    for (java.awt.Window w : java.awt.Window.getWindows()) {
      if (w.isDisplayable() && w instanceof javax.swing.RootPaneContainer
          && Boolean.TRUE.equals(((javax.swing.RootPaneContainer) w).getRootPane().getClientProperty("simkit.window"))) {
        w.setAlwaysOnTop(on);
      }
    }
  }

  /** The STAR-CCM+ main window (largest visible frame), so the calculator floats over it. */
  static java.awt.Frame starFrame() {
    java.awt.Frame best = null;
    long area = -1;
    for (java.awt.Frame f : java.awt.Frame.getFrames()) {
      if (!f.isVisible()) continue;
      long a = (long) f.getWidth() * f.getHeight();
      if (a > area) { area = a; best = f; }
    }
    return best;
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

  /** calc_log.csv: at the root of a project, or in record/ of a case folder. */
  Path logFile() {
    return Files.isRegularFile(caseDir.resolve("simkit_project.txt")) || Files.isRegularFile(caseDir.resolve("simkit_study.txt"))
        ? caseDir.resolve("calc_log.csv")
        : caseDir.resolve("record").resolve("calc_log.csv");
  }

  /** Case folder from the sim path: <any folder>/<CASEID>/sim/<file>.sim (SimKit case) */
  void detectCase() {
    String path = "";
    try { path = sim.getSessionPath(); } catch (Throwable t) { /* unsaved */ }
    caseDir = caseDirOf(path);
    if (caseDir != null) {
      caseNote = "Log: " + logFile();
    } else {
      caseNote = "Log: Output window only (no project)";
    }
  }

  /** Case directory for a sim path, or null (the sim must be <CASEID>/sim/<CASEID>..._v<nn>.sim). */
  static Path caseDirOf(String simPath) {
    if (simPath == null || simPath.trim().isEmpty()) return null;
    try {
      Path f = Paths.get(simPath).toAbsolutePath().normalize();
      Path d = f.getParent();   // a study (simkit_study.txt in the folder or up to 5 above): its record/ holds calc_log.csv
      for (int k = 0; d != null && k < 6; k++, d = d.getParent()) if (Files.isRegularFile(d.resolve("simkit_project.txt")) || Files.isRegularFile(d.resolve("simkit_study.txt"))) return d;
      Path simDir = f.getParent();
      if (simDir == null || !simDir.getFileName().toString().equalsIgnoreCase("sim")) return null;
      Path cd = simDir.getParent();
      if (cd == null || !CASE_ID.matcher(cd.getFileName().toString()).matches()) return null;
      if (!f.getFileName().toString().startsWith(cd.getFileName().toString())) return null;
      return cd;
    } catch (Throwable t) {
      return null;
    }
  }

  // =====================================================================================================
  // Window
  // =====================================================================================================

  /** One calculator tab: sub-panels (forms) on the left, results on the right. */
  /** One calculation page (sidebar entry): a form, its result view and its last result. */
  final class Tab {
    final String name, group, help;
    final boolean more;
    final List<Form> forms = new ArrayList<>();
    /** Plain-text results (what "Copy all results" copies); every change is shown in 'view' as formatted HTML. */
    final JTextArea out = new JTextArea();
    /** Formatted results (styled text: no HTML engine, robust in STAR's look-and-feel). */
    final javax.swing.JTextPane view = new javax.swing.JTextPane();
    Core.R last;
    String lastForm = "";
    boolean styledSet;   // true while showResult() writes 'out' (the view already holds the formatted result)

    Tab(String group, String name, String help) {
      this.name = name;
      this.group = group;
      this.help = help;
      this.more = Arrays.asList(MORE_GROUPS).contains(group);
      out.setEditable(false);
      view.setEditable(false);
      view.setOpaque(true);                          // STAR's look-and-feel makes it transparent otherwise
      view.setBackground(java.awt.Color.WHITE);
      view.setMargin(new Insets(8, 10, 8, 10));
      out.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
        void sync() { if (!styledSet) javax.swing.SwingUtilities.invokeLater(() -> showText(out.getText())); }
        @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { sync(); }
        @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { sync(); }
        @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { sync(); }
      });
      out.setText(name + "\n\nEnter the inputs, then Compute.");
      showText(out.getText());   // at once (the listener only refreshes later changes)
    }

    /** Any plain message (errors, From cases..., Copy value...) shown as wrapped text. */
    void showText(String text) {
      if (styledSet) return;
      javax.swing.text.StyledDocument d = new javax.swing.text.DefaultStyledDocument();
      Styled.add(d, text, Styled.style(13, false, java.awt.Color.DARK_GRAY, null));
      view.setStyledDocument(d);
      view.setCaretPosition(0);
    }

    /** A computed result: styled table in the view, plain text in 'out' for copying. */
    void showResult(String head, String inputs, Core.R r, String logNote) {
      styledSet = true;
      out.setText(head + "\nInputs: " + inputs + "\n\n" + r.text() + "\n" + logNote);
      styledSet = false;
      view.setStyledDocument(Styled.result(head, inputs, r, logNote));
      view.setCaretPosition(0);
    }
  }

  /** One labelled input field. 'scale' converts the field unit to SI (NaN = not an SI-convertible unit). */
  static final class Field {
    String key, label, unit;
    double scale;
    JTextField tf;
  }

  /** A text field showing a grey hint while it is empty ("optional", "empty: from the area" ...). */
  static final class HintField extends JTextField {
    private static final long serialVersionUID = 1L;
    String hint = "";

    HintField(String text, int cols) { super(text, cols); }

    @Override protected void paintComponent(java.awt.Graphics g) {
      super.paintComponent(g);
      if (getText().isEmpty() && !hint.isEmpty() && !isFocusOwner()) {
        java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
        g2.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setColor(new java.awt.Color(0xA8, 0xB0, 0xBA));
        g2.setFont(getFont().deriveFont(Font.ITALIC));
        java.awt.Insets in = getInsets();
        java.awt.FontMetrics fm = g2.getFontMetrics();
        String h = hint;
        while (h.length() > 3 && fm.stringWidth(h) > getWidth() - in.left - in.right - 4) h = h.substring(0, h.length() - 2);
        if (!h.equals(hint)) h = h.substring(0, h.length() - 1) + "\u2026";
        g2.drawString(h, in.left + 2, (getHeight() + fm.getAscent() - fm.getDescent()) / 2);
        g2.dispose();
      }
    }
  }

  /** "mass flow (From report: r_mdot_in)" -> {"mass flow", "From report: r_mdot_in"}: label and hint. */
  static String[] labelHint(String label) {
    int i = label.indexOf(" (");
    if (i > 0 && label.endsWith(")")) return new String[] {label.substring(0, i), label.substring(i + 2, label.length() - 1)};
    return new String[] {label, ""};
  }

  /** A unit as shown: kg/m^3 -> kg/m\u00B3, Pa-s -> Pa\u00B7s, "-" (no unit) -> nothing. */
  static String showUnit(String u) {
    if (u == null || u.equals("-")) return "";
    return u.replace("^2", "\u00B2").replace("^3", "\u00B3").replace("^4", "\u2074").replaceAll("(?<=[A-Za-z\u00B2\u00B3\u2074])-(?=[A-Za-z])", "\u00B7");
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
      HintField hf = new HintField(def, 12);
      hf.hint = labelHint(label)[1];
      if (!hf.hint.isEmpty()) hf.setToolTipText(label);
      f.tf = hf;
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

  /** Sidebar groups: the frequent ones always visible, the specialist ones under "More". */
  static final String[] MAIN_GROUPS = {"Set-up", "Mesh", "Time & averaging", "Verification", "Heat transfer", "Fire test"};
  static final String[] MORE_GROUPS = {"Flow numbers", "Combustion & spray", "Units"};
  static final String MORE_ROW = "(more)";

  Tab page(String group, String name, String help) {
    if (!Arrays.asList(MAIN_GROUPS).contains(group) && !Arrays.asList(MORE_GROUPS).contains(group)) {
      throw new IllegalStateException("unknown group " + group);
    }
    Tab t = new Tab(group, name, help);
    tabs.add(t);
    return t;
  }

  void buildWindow() {
    buildTabs();
    dlg = new JDialog(starFrame(), VERSION + "  -  " + simName(), java.awt.Dialog.ModalityType.MODELESS);
    dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
    dlg.addWindowListener(new java.awt.event.WindowAdapter() {
      @Override public void windowClosed(java.awt.event.WindowEvent e) {
        if (System.getProperties().get(WINDOW_KEY) == dlg) System.getProperties().remove(WINDOW_KEY);
        log.line("SimKitCalc closed.");
      }
    });
    JPanel root = new JPanel(new BorderLayout(6, 6));
    root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
    JLabel note = new JLabel(caseNote);
    note.setForeground(java.awt.Color.GRAY);
    root.add(note, BorderLayout.NORTH);
    cards = new JPanel(new java.awt.CardLayout());
    for (int i = 0; i < tabs.size(); i++) cards.add(layoutPage(tabs.get(i)), "p" + i);
    root.add(cards, BorderLayout.CENTER);
    root.add(buildSidebar(), BorderLayout.WEST);
    String last = null;
    try { last = java.util.prefs.Preferences.userRoot().node("simkit").get("calcPage", null); } catch (Throwable x) { /* none */ }
    Tab first = tabs.get(0);
    for (Tab t : tabs) if (t.name.equals(last)) first = t;
    moreOpen = first.more;
    selectPage(first);

    JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
    left.add(groupLabel("Simulation:"));
    left.add(button("From parameter...", e -> starAction(() -> fromParameter(currentTab()))));
    left.add(button("From report...", e -> starAction(() -> fromReport(currentTab()))));
    left.add(button("To parameter...", e -> starAction(() -> toParameter(currentTab()))));
    left.add(javax.swing.Box.createHorizontalStrut(18));
    left.add(groupLabel("Copy:"));
    left.add(button("Value for STAR-CCM+...", e -> copyValue(currentTab())));
    left.add(button("All results", e -> copyResults(currentTab())));
    JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 2));
    javax.swing.JCheckBox onTop = new javax.swing.JCheckBox("Keep in front of other apps", onTopPref());
    onTop.setToolTipText("Keep the SimKit windows above every other window (remembered for next time). "
        + "Off: they stay above STAR-CCM+ only.");
    onTop.addActionListener(e -> setOnTopPref(onTop.isSelected()));
    right.add(onTop);
    right.add(button("Close", e -> dlg.dispose()));
    JPanel south = new JPanel(new BorderLayout());
    south.add(left, BorderLayout.WEST);
    south.add(right, BorderLayout.EAST);
    root.add(south, BorderLayout.SOUTH);
    dlg.setContentPane(root);
    dlg.setSize(1180, 820);
    dlg.setLocationRelativeTo(starFrame());
    dlg.getRootPane().putClientProperty("simkit.window", Boolean.TRUE);
    try { dlg.setAlwaysOnTop(onTopPref()); } catch (Throwable t) { /* not supported */ }
  }

  static JLabel groupLabel(String s) {
    JLabel l = new JLabel(s);
    l.setForeground(java.awt.Color.GRAY);
    return l;
  }

  static JButton button(String text, java.awt.event.ActionListener a) {
    JButton b = new JButton(text);
    b.addActionListener(a);
    return b;
  }

  Tab currentTab() { return current != null ? current : tabs.get(0); }

  // ----- sidebar ------------------------------------------------------------------------------------------

  JComponent buildSidebar() {
    filter = new JTextField();
    filter.setToolTipText("Type to find a calculation (searches every page, also those under More)");
    filter.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
      @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { refillSide(); }
      @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { refillSide(); }
      @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { refillSide(); }
    });
    filter.addActionListener(e -> {   // Enter: open the first match
      for (int i = 0; i < sideModel.size(); i++) {
        if (sideModel.get(i) instanceof Tab) { selectPage((Tab) sideModel.get(i)); return; }
      }
    });
    side = new javax.swing.JList<>(sideModel);
    side.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
    side.setCellRenderer(new SideRenderer());
    side.addListSelectionListener(e -> {
      if (e.getValueIsAdjusting() || sideSync) return;
      int i = side.getSelectedIndex();
      if (i < 0) return;
      Object v = sideModel.get(i);
      if (v instanceof Tab) {
        showPage((Tab) v);
      } else if (MORE_ROW.equals(v)) {
        moreOpen = !moreOpen;
        javax.swing.SwingUtilities.invokeLater(this::refillSide);
      } else {   // a heading: move on to the next page in the direction of travel (keyboard), else stay
        int cur = sideModel.indexOf(current), dir = i > cur ? 1 : -1;
        for (int k = i; k >= 0 && k < sideModel.size(); k += dir) {
          if (sideModel.get(k) instanceof Tab) { final Tab t = (Tab) sideModel.get(k); javax.swing.SwingUtilities.invokeLater(() -> selectPage(t)); return; }
        }
        javax.swing.SwingUtilities.invokeLater(this::refillSide);
      }
    });
    JLabel find = new JLabel("Find");
    find.setForeground(java.awt.Color.GRAY);
    JPanel top = new JPanel(new BorderLayout(6, 0));
    top.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
    top.add(find, BorderLayout.WEST);
    top.add(filter, BorderLayout.CENTER);
    JScrollPane sp = new JScrollPane(side);
    sp.getVerticalScrollBar().setUnitIncrement(16);
    JPanel p = new JPanel(new BorderLayout());
    p.add(top, BorderLayout.NORTH);
    p.add(sp, BorderLayout.CENTER);
    p.setPreferredSize(new java.awt.Dimension(250, 10));
    return p;
  }

  boolean matches(Tab t, String q) {
    StringBuilder sb = new StringBuilder(t.name).append(' ').append(t.group).append(' ').append(t.help);
    for (Form fm : t.forms) {
      sb.append(' ').append(fm.title);
      for (Field f : fm.fields.values()) sb.append(' ').append(f.label);
      for (JComboBox<String> cb : fm.combos.values()) {
        for (int i = 0; i < cb.getItemCount() && i < 12; i++) sb.append(' ').append(cb.getItemAt(i));
      }
    }
    String all = sb.toString().toLowerCase(Locale.ROOT);
    for (String w : q.split("\\s+")) if (!w.isEmpty() && !all.contains(w)) return false;
    return true;
  }

  /** Rebuilds the sidebar (filter, More open or closed) and selects the page shown. */
  void refillSide() {
    if (side == null) return;
    String q = filter.getText().trim().toLowerCase(Locale.ROOT);
    sideSync = true;
    try {
      sideModel.clear();
      List<String> groups = new ArrayList<>(Arrays.asList(MAIN_GROUPS));
      groups.add(MORE_ROW);
      groups.addAll(Arrays.asList(MORE_GROUPS));
      boolean inMore = false;
      for (String g : groups) {
        if (g.equals(MORE_ROW)) {
          inMore = true;
          if (q.isEmpty()) sideModel.addElement(MORE_ROW);
          continue;
        }
        if (inMore && q.isEmpty() && !moreOpen) continue;
        List<Tab> in = new ArrayList<>();
        for (Tab t : tabs) if (t.group.equals(g) && (q.isEmpty() || matches(t, q))) in.add(t);
        if (in.isEmpty()) continue;
        sideModel.addElement(g);
        for (Tab t : in) sideModel.addElement(t);
      }
      if (sideModel.isEmpty()) sideModel.addElement("No calculation matches");
      int i = sideModel.indexOf(current);
      if (i >= 0) {
        side.setSelectedIndex(i);
        side.ensureIndexIsVisible(i);
      } else {
        side.clearSelection();
      }
    } finally {
      sideSync = false;
    }
  }

  /** Shows a page and remembers it (per user). */
  void showPage(Tab t) {
    current = t;
    ((java.awt.CardLayout) cards.getLayout()).show(cards, "p" + tabs.indexOf(t));
    try { java.util.prefs.Preferences.userRoot().node("simkit").put("calcPage", t.name); } catch (Throwable x) { /* not kept */ }
  }

  /** Shows a page and selects it in the sidebar (opens More if needed). */
  void selectPage(Tab t) {
    if (t.more) moreOpen = true;
    showPage(t);
    refillSide();
  }

  int moreCount() {
    int n = 0;
    for (Tab t : tabs) if (t.more) n++;
    return n;
  }

  /** Sidebar look: group headings in small grey capitals, pages indented, the More row as a toggle. */
  final class SideRenderer extends javax.swing.DefaultListCellRenderer {
    @Override
    public java.awt.Component getListCellRendererComponent(javax.swing.JList<?> l, Object v, int i, boolean sel, boolean foc) {
      if (v instanceof Tab) {
        JLabel c = (JLabel) super.getListCellRendererComponent(l, ((Tab) v).name, i, sel, false);
        c.setFont(l.getFont().deriveFont(Font.PLAIN, 13f));
        c.setBorder(BorderFactory.createEmptyBorder(3, 18, 3, 6));
        c.setToolTipText(((Tab) v).help);
        return c;
      }
      JLabel c = (JLabel) super.getListCellRendererComponent(l, "", i, false, false);
      c.setToolTipText(null);
      if (MORE_ROW.equals(v)) {
        c.setText((moreOpen ? "\u25BE  " : "\u25B8  ") + "More (" + moreCount() + ")  -  specialist");
        c.setFont(l.getFont().deriveFont(Font.BOLD, 12f));
        c.setForeground(new java.awt.Color(0x2F, 0x5B, 0x8A));
        c.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, new java.awt.Color(0xDD, 0xDD, 0xDD)),
            BorderFactory.createEmptyBorder(9, 6, 5, 6)));
        c.setToolTipText(moreOpen ? "Hide the specialist calculations" : "Show the specialist calculations (flow numbers, combustion & spray, units)");
      } else {
        c.setText(String.valueOf(v).toUpperCase(Locale.ROOT));
        c.setFont(l.getFont().deriveFont(Font.BOLD, 11f));
        c.setForeground(new java.awt.Color(0x55, 0x66, 0x77));
        c.setBorder(BorderFactory.createEmptyBorder(10, 6, 2, 6));
      }
      return c;
    }
  }

  /** Runs a short STAR-CCM+ action (read/create a parameter, read a report) from the button, as a Simulation
   *  Assistant does; the window stays open and STAR-CCM+ stays usable. */
  void starAction(Runnable job) {
    try {
      job.run();
    } catch (Cancel c) {
      if (c.getMessage() != null) log.line("cancelled: " + c.getMessage());
    } catch (Throwable t) {
      log.line("ERROR: " + reason(t));
      JOptionPane.showMessageDialog(dlg, "ERROR: " + reason(t), "SimKitCalc", JOptionPane.ERROR_MESSAGE);
    }
  }

  String simName() {
    try { return sim.getPresentationName(); } catch (Throwable t) { return "?"; }
  }

  /** A page: heading (name, when to use), the form, the result below. */
  JComponent layoutPage(Tab t) {
    JPanel head = new JPanel(new java.awt.GridLayout(0, 1, 0, 2));
    head.setBorder(BorderFactory.createEmptyBorder(2, 6, 6, 6));
    JLabel title = new JLabel(t.name);
    title.setFont(title.getFont().deriveFont(Font.BOLD, 17f));
    head.add(title);
    JLabel help = new JLabel(t.help);
    help.setFont(help.getFont().deriveFont(Font.PLAIN, 12f));
    help.setForeground(new java.awt.Color(0x44, 0x44, 0x44));
    head.add(help);
    for (Form fm : t.forms) {
      if (fm.title.equals(t.name) || t.forms.size() == 1) continue;   // one form: the page title says it
      JLabel sub = new JLabel(fm.title);
      sub.setFont(sub.getFont().deriveFont(Font.ITALIC, 11f));
      sub.setForeground(java.awt.Color.GRAY);
      head.add(sub);
    }
    JPanel forms = new JPanel();
    forms.setLayout(new BoxLayout(forms, BoxLayout.Y_AXIS));
    for (Form fm : t.forms) forms.add(layoutForm(fm));
    JPanel holder = new JPanel(new BorderLayout());
    holder.add(forms, BorderLayout.WEST);
    JPanel north = new JPanel(new BorderLayout());
    north.add(holder, BorderLayout.NORTH);
    JScrollPane ls = new JScrollPane(north);
    ls.setBorder(BorderFactory.createEmptyBorder());
    ls.getVerticalScrollBar().setUnitIncrement(16);
    JScrollPane rs = new JScrollPane(t.view);
    rs.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
    rs.setOpaque(true);
    rs.getViewport().setOpaque(true);
    rs.getViewport().setBackground(java.awt.Color.WHITE);
    JSplitPane sp = new JSplitPane(JSplitPane.VERTICAL_SPLIT, ls, rs);
    sp.setBorder(BorderFactory.createEmptyBorder());
    sp.setDividerLocation(Math.min(holder.getPreferredSize().height + 8, 430));
    sp.setResizeWeight(0);
    JPanel page = new JPanel(new BorderLayout());
    page.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 0));
    page.add(head, BorderLayout.NORTH);
    page.add(sp, BorderLayout.CENTER);
    return page;
  }

  static final int LABEL_W = 230, FIELD_W = 150, UNIT_W = 60;

  static <C extends JComponent> C fixed(C c, int w) {
    java.awt.Dimension d = new java.awt.Dimension(w, c.getPreferredSize().height);
    c.setPreferredSize(d);
    c.setMinimumSize(d);
    if (c instanceof JLabel) c.setToolTipText(((JLabel) c).getText());   // full label if cut
    return c;
  }

  JPanel layoutForm(Form fm) {
    JPanel p = new JPanel(new GridBagLayout());
    p.setBorder(BorderFactory.createEmptyBorder(4, 0, 6, 4));
    GridBagConstraints g = new GridBagConstraints();
    g.insets = new Insets(2, 3, 2, 3);
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
        JLabel lab = fixed(new JLabel(labelHint(f.label)[0]), LABEL_W);
        lab.setToolTipText(f.label);
        p.add(lab, g);
        g.gridx = 1;
        p.add(fixed(f.tf, FIELD_W), g);
        g.gridx = 2;
        JLabel u = new JLabel(showUnit(f.unit));
        u.setForeground(java.awt.Color.GRAY);
        p.add(fixed(u, UNIT_W), g);
      } else {
        JComboBox<String> cb = fm.combos.get(k);
        p.add(fixed(new JLabel(cb.getName()), LABEL_W), g);
        g.gridx = 1;
        g.gridwidth = 2;
        p.add(fixed(cb, FIELD_W + UNIT_W + 6), g);
      }
    }
    JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
    for (JComponent c : fm.extras) buttons.add(c);
    JButton go = button(fm.computeLabel, e -> compute(fm));
    go.setFont(go.getFont().deriveFont(Font.BOLD));
    go.setForeground(java.awt.Color.WHITE);
    go.setBackground(new java.awt.Color(0x1F, 0x5F, 0xAF));
    go.setOpaque(true);
    go.setBorderPainted(false);
    go.setFocusPainted(false);
    go.setMargin(new Insets(5, 18, 5, 18));
    buttons.add(go);
    g.gridy = row;
    g.gridx = 0;
    g.gridwidth = 3;
    g.weightx = 1;
    g.fill = GridBagConstraints.HORIZONTAL;
    p.add(buttons, g);
    p.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, p.getPreferredSize().height));
    fluidFields(fm);   // grey the fields that the selected fluid provides
    for (Field f : fm.fields.values()) {
      if (f.label.contains("(library)") || Arrays.asList(PROPERTY_KEYS).contains(f.key)) {
        f.tf.addActionListener(e -> fluidFields(fm));   // Enter in T / p refreshes the library values
      }
    }
    return p;
  }

  /** Compute: pure Java on the Swing thread; results + calc_log. */
  void compute(Form fm) {
    Tab t = fm.tab;
    String head = "[" + Fmt.iso() + "] " + t.name;
    try {
      Core.R r = fm.calc.apply(fm);
      t.last = r;
      t.lastForm = fm.title;
      String inputs = fm.inputs();
      String logNote = calcLog(t.name, inputs, r.outputs());
      t.showResult(head, inputs, r, logNote);
    } catch (IllegalArgumentException e) {
      t.out.setText(head + "\n\nINPUT ERROR: " + e.getMessage());
    } catch (Throwable e) {
      t.out.setText(head + "\n\nERROR: " + reason(e));
    }
  }

  /** Appends one row to <case>/record/calc_log.csv (if a case folder exists) and prints it. Returns a status line. */
  String calcLog(String tabName, String inputs, String outputs) {
    log.line("calc | " + tabName + " | in: " + inputs + " | out: " + outputs);    // [U52] on the Swing thread
    if (caseDir == null) return "calc_log: not written (no case folder) -- logged in the Output window only.";
    Path f = logFile();
    try {
      Fs.appendCsv(f, caseDir, CALC_LOG_HEADER, new String[] {Fmt.iso(), Fs.user(), tabName, inputs, outputs});
      return "calc_log: appended to " + f;
    } catch (Throwable e) {
      log.line("calc_log: n/a (" + reason(e) + ")");
      return "calc_log: NOT written (" + reason(e) + ")";
    }
  }

  /** Copies ONE result as a bare number (paste it in a STAR-CCM+ field) and says which STAR unit to select. */
  void copyValue(Tab t) {
    if (t.last == null || t.last.items.isEmpty()) {
      t.out.append("\n(press Compute first: there is no result to copy)");
      return;
    }
    String[] labels = new String[t.last.items.size()];
    for (int i = 0; i < labels.length; i++) {
      Core.Item it = t.last.items.get(i);
      labels[i] = it.key + " = " + Core.g(it.v) + " " + starUnit(it.unit) + "   " + it.desc;
    }
    Object c = JOptionPane.showInputDialog(dlg, "Value to copy (paste it in STAR-CCM+, then select the unit shown):",
        "Copy value for STAR-CCM+", JOptionPane.QUESTION_MESSAGE, null, labels, labels[0]);
    if (c == null) return;
    Core.Item it = t.last.items.get(java.util.Arrays.asList(labels).indexOf(c));
    String v = starNumber(it.v);
    try {
      Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(v), null);
      t.out.append("\nCopied " + it.key + " = " + v + "  -> paste it in STAR-CCM+ and select the unit: " + starUnit(it.unit));
    } catch (Throwable e) {
      t.out.append("\n(copy failed: " + reason(e) + "; value " + v + ")");
    }
  }

  /** Number as STAR-CCM+ fields accept it: dot decimal, 6 significant digits, no grouping (e.g. 1.23456E-5). */
  static String starNumber(double v) {
    if (v == Math.rint(v) && Math.abs(v) < 1e9) return String.valueOf((long) v);
    String s = String.format(Locale.ROOT, "%.6g", v).trim();
    if (s.contains("e")) {
      String[] p = s.split("e");
      String m = p[0].contains(".") ? p[0].replaceAll("0+$", "").replaceAll("\\.$", "") : p[0];
      return m + "E" + Integer.parseInt(p[1]);
    }
    return s.contains(".") ? s.replaceAll("0+$", "").replaceAll("\\.$", "") : s;
  }

  /** Unit as written in the STAR-CCM+ unit list: m3 -> m^3, J/kg.K -> J/kg-K, "-" -> dimensionless. */
  static String starUnit(String u) {
    if (u == null || u.isEmpty() || u.equals("-")) return "(dimensionless)";
    return u.replaceAll("([A-Za-z])([0-9])", "$1^$2").replace('.', '-');
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
    cb.addActionListener(e -> fluidFields(fm));
    return cb;
  }

  static final String[] PROPERTY_KEYS = {"rho", "mu", "k", "cp"};

  /** Library fluid selected: the property fields are filled from the library and greyed (not to be typed); the
   *  "(library)" inputs (temperature, pressure) are active. "(entered values)": the other way round. */
  void fluidFields(Form fm) {
    if (!fm.combos.containsKey("fluid")) return;
    String fl = fm.s("fluid");
    boolean entered = fl.isEmpty() || fl.equals(ENTERED);
    for (Field f : fm.fields.values()) {
      boolean prop = Arrays.asList(PROPERTY_KEYS).contains(f.key);
      boolean libOnly = f.label.contains("(library)");
      if (prop) setActive(f, entered);
      else if (libOnly) setActive(f, !entered);
    }
    if (entered) return;
    try {   // show the library values at the temperature / pressure of the form (Compute uses the right temperature)
      double T = fm.has("T") ? fm.v("T")                                                   // temperature field,
          : fm.has("Tinf") && fm.has("Ts") ? 0.5 * (fm.v("Tinf") + fm.v("Ts"))             // else film temperature,
          : fm.has("Tg") ? fm.v("Tg") : Double.NaN;                                           // else gas temperature
      double p = fm.has("p") ? fm.v("p") : 101325;
      if (Double.isNaN(T)) return;
      double[] v = lib.fluidProps(fl, Double.isNaN(p) ? 101325 : p).at(T);
      for (int i = 0; i < PROPERTY_KEYS.length && i < v.length; i++) if (fm.has(PROPERTY_KEYS[i])) fm.put(PROPERTY_KEYS[i], v[i]);
    } catch (Throwable t) { /* values appear after Compute */ }
  }

  static void setActive(Field f, boolean on) {
    f.tf.setEditable(on);
    f.tf.setEnabled(on);
    f.tf.setToolTipText(on ? null : "taken from the selected library fluid (choose \"" + ENTERED + "\" to type your own)");
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
  // The pages (sidebar order: group, then the order below)
  // =====================================================================================================

  void buildTabs() {
    buildInlet();
    buildTurbulence();
    buildFluid();
    buildMaterial();
    buildWall();
    buildTimeStep();
    buildGci();
    buildHeat();
    buildDimensionless();
    buildCombustion();
    buildUnits();
  }

  void buildInlet() {
    Form a = new Form(page("Set-up", "Inlet & outlet values",
        "Give one flow quantity of a section: get mass flow, velocity, flows, total and gauge pressures, Mach."),
        "Uniform flow through a section of known size; values ready for STAR-CCM+ boundaries");
    a.c("given", "given quantity", Core.INLET_GIVEN);
    a.f("value", "value (in the unit shown above)", "", "0.05");
    fluidCombo(a, "fluid (library) or entered");
    a.f("p", "static pressure at the section (absolute)", "Pa", "101325").f("T", "static temperature", "K", "300");
    a.f("pref", "reference pressure of the STAR model", "Pa", "101325");
    a.f("D", "diameter D (empty: use the area)", "m", "0.05").f("A", "flow area A (if D is empty)", "m^2", "");
    a.f("rho", "density", "kg/m^3", "1.177").f("mu", "viscosity (for Re)", "Pa-s", "1.846e-5").f("cp", "cp (for gamma)", "J/kg-K", "1007");
    a.f("gamma", "gamma (empty: cp / (cp - R))", "-", "");
    a.calc = fm -> {
      double p = fm.req("p"), T = fm.req("T");
      Core.Props pr = props(fm, p);
      double[] x = pr.at(T);
      Core.R r = Core.inletOutlet(fm.idx("given"), fm.req("value"), p, T, fm.req("pref"), fm.v("D"), fm.v("A"), x[0], x[1], x[3],
          fm.v("gamma"), gasConstant(fm, x[0], p, T));
      r.absorb(pr);
      writeBack(fm, r);
      return r;
    };

    Form b = new Form(page("Set-up", "Orifice / nozzle flow",
        "Mass flow through a hole from the pressures (choked or not), or the pressure or diameter for a flow."),
        "Restriction (orifice, nozzle, injector holes) fed from upstream total conditions");
    b.c("solve", "solve for", Core.ORIFICE_SOLVE);
    fluidCombo(b, "fluid (library) or entered");
    b.f("p", "upstream total pressure (absolute)", "Pa", "300000").f("pb", "downstream static pressure (absolute)", "Pa", "101325");
    b.f("T", "upstream total temperature", "K", "300");
    b.f("D", "hole diameter (each)", "mm", "2").f("n", "number of holes", "-", "1");
    b.f("Cd", "discharge coefficient Cd", "-", "0.61");
    b.f("mdot", "mass flow, all holes (to solve pressure or diameter)", "kg/s", "");
    b.f("rho", "density (upstream)", "kg/m^3", "3.48").f("cp", "cp (for gamma)", "J/kg-K", "1007");
    b.f("gamma", "gamma (empty: cp / (cp - R))", "-", "");
    b.calc = fm -> {
      int solve = fm.idx("solve");
      double p = Double.isNaN(fm.v("p")) ? 101325 : fm.v("p"), T = fm.req("T");
      Core.Props pr = props(fm, p);
      double[] x = pr.at(T);
      Core.R r = Core.orifice(solve, solve == 0 ? Double.NaN : fm.req("mdot"), solve == 1 ? Double.NaN : fm.req("p"), fm.req("pb"), T,
          solve == 2 ? Double.NaN : fm.req("D"), fm.req("n"), fm.req("Cd"), x[0], x[3], fm.v("gamma"), gasConstant(fm, x[0], p, T));
      r.absorb(pr);
      writeBack(fm, r);
      return r;
    };
  }

  /** Gas constant R [J/kg-K] of the selected fluid, NaN for a liquid. Library: ideal-gas density rows; entered values: an
   *  apparent p / (rho T) above 20 J/kg-K is taken as a gas. */
  double gasConstant(Form fm, double rho, double p, double T) {
    String fl = fm.s("fluid");
    if (!fl.isEmpty() && !fl.equals(ENTERED)) {
      Map<String, Library.Prop> m = lib.fluids.get(fl);
      Library.Prop rp = m == null ? null : m.get("rho"), mp = m == null ? null : m.get("M");
      if (rp == null || !"idealgas".equals(rp.model)) return Double.NaN;
      double M = mp != null && mp.c != null && mp.c.length > 0 ? mp.c[0] : rp.c[0];
      return Core.RU / M;
    }
    double re = p / (rho * T);
    return re > 20 ? re : Double.NaN;
  }

  void buildWall() {
    Form a = new Form(page("Mesh", "First cell from y+",
        "First prism layer thickness for a target y+, before meshing."), "First cell from target y+");
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

    Form b = new Form(page("Mesh", "Prism stack",
        "Total thickness, growth ratio or number of prism layers (two given, the third solved)."), "Prism stack");
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

    Form c = new Form(page("Mesh", "y+ correction",
        "New first layer thickness from the y+ of a run."), "y+ correction (From report... fills y+ measured)");
    c.f("y1old", "current first layer thickness", "m", "5e-5").f("target", "target y+", "-", "1");
    c.f("measured", "measured y+ (e.g. area average)", "-", "");
    c.calc = fm -> Core.yplusCorrection(fm.req("y1old"), fm.req("target"), fm.req("measured"));
  }

  void buildDimensionless() {
    Form a = new Form(page("Flow numbers", "Re, Pr, Sc, Pe",
        "Flow regime numbers from a velocity, a length and the properties."), "Re, Pr, Sc, Pe");
    a.f("U", "velocity U", "m/s", "10").f("L", "length L", "m", "0.1").f("rho", "density", "kg/m^3", "1.177");
    a.f("mu", "dynamic viscosity", "Pa-s", "1.846e-5").f("cp", "cp (for Pr)", "J/kg-K", "1007");
    a.f("k", "conductivity k (for Pr)", "W/m-K", "0.0263").f("D", "mass diffusivity (for Sc)", "m^2/s", "");
    a.calc = fm -> Core.flowNumbers(fm.req("U"), fm.req("L"), fm.req("rho"), fm.req("mu"), fm.v("cp"), fm.v("k"), fm.v("D"));

    Form c = new Form(page("Flow numbers", "Fr, Ri, Gr, Ra",
        "Buoyancy: does natural convection matter?"), "Fr, Ri, Gr, Ra");
    c.f("U", "velocity U (for Fr, Ri)", "m/s", "1").f("L", "length L", "m", "0.5").f("g", "gravity g", "m/s^2", "9.81");
    c.f("dT", "temperature difference dT", "K", "50").f("Tref", "reference temperature", "K", "300");
    c.f("beta", "expansion coeff. beta (empty: 1/T_ref)", "1/K", "");
    c.f("nu", "kinematic viscosity nu", "m^2/s", "1.57e-5").f("alpha", "thermal diffusivity (for Ra)", "m^2/s", "2.2e-5");
    c.calc = fm -> Core.buoyancy(fm.v("U"), fm.req("L"), fm.req("g"), fm.req("dT"), fm.req("Tref"), fm.v("beta"),
        fm.req("nu"), fm.v("alpha"));

    Form d = new Form(page("Flow numbers", "Strouhal and frequency",
        "Shedding frequency from St, or St from a frequency."), "Strouhal number <-> frequency");
    d.f("L", "length L", "m", "0.05").f("U", "velocity U", "m/s", "10").f("St", "St (empty: from f)", "-", "0.2");
    d.f("f", "frequency f (used if St empty)", "Hz", "");
    d.calc = fm -> Core.strouhal(fm.req("L"), fm.req("U"), fm.v("St"), fm.v("f"));

    Form f = new Form(page("Flow numbers", "Biot and Fourier",
        "Is a solid lumped? How far has heat gone into it after a time?"), "Biot and Fourier");
    f.f("h", "heat transfer coefficient h", "W/m^2-K", "50").f("L", "length L (V/A for lumped)", "m", "0.002");
    f.f("k", "solid conductivity k", "W/m-K", "16").f("alpha", "solid diffusivity (for Fo)", "m^2/s", "4e-6");
    f.f("t", "time t (for Fo)", "s", "10");
    f.calc = fm -> Core.biotFourier(fm.req("h"), fm.req("L"), fm.req("k"), fm.v("alpha"), fm.v("t"));

  }

  void buildTurbulence() {
    Form a = new Form(page("Set-up", "Turbulence at an inlet",
        "k, epsilon, omega from intensity and length scale (or viscosity ratio) for inlet boundaries."),
        "k, epsilon, omega at an inlet (C_mu = 0.09)");
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
    Form a = new Form(page("Time & averaging", "Time step & cost",
        "Time step for a Courant number or a frequency, run length and core-hours."), "Time step, periods, cost");
    a.f("Co", "target Courant number", "-", "1").f("dx", "cell size dx", "m", "5e-4").f("U", "velocity U", "m/s", "20");
    a.f("f", "frequency of interest f (optional)", "Hz", "200").f("Lft", "flow-through length (optional)", "m", "0.5");
    a.f("tInit", "initial transient to discard", "s", "0.05").f("Ncyc", "cycles to average (of f)", "-", "50");
    a.f("sps", "wall-clock seconds per time step", "s/step", "10").f("cores", "cores", "cores", "64");
    a.calc = fm -> Core.timeStep(fm.req("Co"), fm.req("dx"), fm.req("U"), fm.v("f"), fm.v("Lft"), fm.v("tInit"),
        fm.v("Ncyc"), fm.v("sps"), fm.v("cores"));

    Form ft = new Form(page("Time & averaging", "Flow-through & averaging window",
        "When to start and stop averaging an unsteady run."), "Flow-through time and averaging window (unsteady runs)");
    ft.f("mdot", "mass flow through the domain (From report: r_mdot_in)", "kg/s", "0.046");
    ft.f("rho", "mean density", "kg/m^3", "1.2");
    ft.f("V", "fluid volume of the domain (optional)", "m^3", "");
    ft.f("L", "length of the main flow path (optional)", "m", "1.0").f("A", "flow cross-section (optional)", "m^2", "0.01");
    ft.f("Ninit", "flow-throughs to discard (start-up)", "-", "2").f("Nft", "flow-throughs to average", "-", "3");
    ft.f("dt", "time step (optional)", "s", "");
    ft.calc = fm -> Core.flowThrough(fm.req("mdot"), fm.req("rho"), fm.v("V"), fm.v("L"), fm.v("A"), fm.req("Ninit"),
        fm.req("Nft"), fm.v("dt"));

    Form b = new Form(page("Time & averaging", "Statistics from monitor CSV",
        "Mean, uncertainty and integral time scale of a monitor (batch means)."), "From monitor CSV (batch means, integral time scale)");
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
        b.tab.out.setText("Monitor CSV: " + ex.getMessage());
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
    Form a = new Form(page("Verification", "Grid convergence (GCI)",
        "Discretisation uncertainty from three meshes (also directly from your cases)."), "Grid convergence index (Celik et al. 2008)");
    a.f("N1", "N1 cells (fine)", "cells", "18000").f("N2", "N2 cells (medium)", "cells", "8000");
    a.f("N3", "N3 cells (coarse)", "cells", "4500");
    a.f("phi1", "phi1 (fine)", "-", "6.063").f("phi2", "phi2 (medium)", "-", "5.972").f("phi3", "phi3 (coarse)", "-", "5.863");
    a.f("dim", "dimension (3 = 3-D, 2 = 2-D)", "-", "3");
    a.calc = fm -> Core.gci(fm.req("N1"), fm.req("N2"), fm.req("N3"), fm.req("phi1"), fm.req("phi2"), fm.req("phi3"),
        fm.reqInt("dim"));
  }

  /** key=value lines of a case_identity.txt (empty map if missing). */
  static Map<String, String> identity(Path f) {
    Map<String, String> kv = new LinkedHashMap<>();
    try {
      for (String line : Fs.read(f).split("\r?\n")) {
        int i = line.indexOf('=');
        if (i > 0 && !line.startsWith("#")) kv.put(line.substring(0, i).trim(), line.substring(i + 1).trim());
      }
    } catch (IOException e) { /* none */ }
    return kv;
  }

  void buildFluid() {
    Form a = new Form(page("Set-up", "Fluid properties",
        "Density, viscosity, conductivity and cp of a library fluid at a temperature."), "Fluid from data/fluids.csv");
    JComboBox<String> cb = a.c("fluidName", "fluid", fluidNames(false));
    fluidCombos.add(cb);
    a.f("T", "temperature T", "K", "300").f("p", "pressure p (for ideal-gas density)", "Pa", "101325");
    JButton reload = button("Choose data folder...", e -> {
      JFileChooser fc = new JFileChooser(lib.dir == null ? null : lib.dir.toFile());
      fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
      if (fc.showOpenDialog(dlg) != JFileChooser.APPROVE_OPTION) return;
      lib = Library.load(fc.getSelectedFile().toPath());
      refreshLibraryCombos();
      a.tab.out.setText(lib.summary());
    });
    a.extras.add(reload);
    a.calc = fm -> lib.fluidReport(fm.s("fluidName"), fm.req("T"), fm.v("p"));
  }

  /** Team MatML folders (config/material_folders.csv, one folder per row) and the user's own folder (remembered). */
  List<Path> matmlFolders() {
    List<Path> out = new ArrayList<>();
    try {
      Path cfg = Fs.inKit("config/material_folders.csv");
      if (Files.isRegularFile(cfg)) {
        List<String[]> rows = Csv.parse(Fs.read(cfg));
        for (int i = 1; i < rows.size(); i++) {
          String f = rows.get(i).length > 0 ? rows.get(i)[0].trim() : "";
          if (!f.isEmpty() && !f.startsWith("#")) out.add(Paths.get(f));
        }
      }
    } catch (Throwable t) { /* no team folder */ }
    try {
      String mine = java.util.prefs.Preferences.userRoot().node("simkit").get("matmlFolder", null);
      if (mine != null && !mine.isEmpty() && !out.contains(Paths.get(mine))) out.add(Paths.get(mine));
    } catch (Throwable t) { /* no own folder */ }
    return out;
  }

  void loadMatMLFolders() {
    lib.matml.clear();
    for (Path f : matmlFolders()) {
      List<String> rep = new ArrayList<>();
      MatML.loadFolder(lib, f, rep);
      lib.matml.addAll(rep);
    }
  }

  void buildMaterial() {
    Form a = new Form(page("Set-up", "Material properties",
        "Solid properties from the library and the team MatML folders, at a temperature."), "Solid: data/materials.csv and MatML folders (*.xml)");
    JComboBox<String> cb = a.c("material", "material", lib.materials.keySet().toArray(new String[0]));
    materialCombos.add(cb);
    a.f("T", "temperature T", "K", "300");
    a.extras.add(button("MatML folder...", e -> {
      JFileChooser fc = new JFileChooser(matmlFolders().isEmpty() ? null : matmlFolders().get(matmlFolders().size() - 1).toFile());
      fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
      fc.setDialogTitle("Folder with MatML material files (*.xml)");
      if (fc.showOpenDialog(dlg) != JFileChooser.APPROVE_OPTION) return;
      try { java.util.prefs.Preferences.userRoot().node("simkit").put("matmlFolder", fc.getSelectedFile().toString()); }
      catch (Throwable x) { /* not remembered */ }
      List<String> rep = new ArrayList<>();
      int n = MatML.loadFolder(lib, fc.getSelectedFile().toPath(), rep);
      lib.matml.addAll(rep);
      refreshLibraryCombos();
      a.tab.out.setText(n + " material(s) read from " + fc.getSelectedFile() + " (remembered for next time).\n\n"
          + String.join("\n", rep));
    }));
    a.extras.add(button("What was read", e -> a.tab.out.setText(lib.matml.isEmpty()
        ? "No MatML folder yet: use MatML folder... (or ask the toolkit owner to set the team folder in config/material_folders.csv)."
        : String.join("\n", lib.matml))));
    a.calc = fm -> lib.materialReport(fm.s("material"), fm.req("T"));
  }

  void buildHeat() {
    Form a = new Form(page("Heat transfer", "Convection coefficient h",
        "h and Nu from standard correlations (cylinder, plate, sphere, pipe, jet, natural convection)."),
        "Convection coefficient h (properties at the film temperature)");
    a.c("corr", "correlation", Core.CONV_CORR);
    fluidCombo(a, "fluid (library) or entered");
    a.f("p", "pressure (library)", "Pa", "101325");
    a.f("U", "velocity U (jet: nozzle exit)", "m/s", "10").f("L", "D (cylinder/sphere/pipe/jet) or L (plate)", "m", "0.01");
    a.f("Tinf", "fluid temperature T_inf", "K", "300").f("Ts", "surface temperature T_s", "K", "350");
    a.f("rho", "density", "kg/m^3", "1.1").f("mu", "viscosity", "Pa-s", "1.9e-5");
    a.f("k", "conductivity", "W/m-K", "0.027").f("cp", "cp", "J/kg-K", "1007");
    a.f("r", "jet: averaging radius r", "m", "0.05").f("H", "jet: nozzle-to-plate distance H", "m", "0.06");
    a.f("g", "natural conv.: gravity", "m/s^2", "9.81").f("beta", "natural conv.: beta (empty: 1/T_film)", "1/K", "");
    a.calc = fm -> {
      Core.Props pr = props(fm, fm.v("p"));
      Core.R r = Core.convection(fm.idx("corr"), fm.v("U"), fm.req("L"), fm.req("Tinf"), fm.req("Ts"), pr, fm.v("r"),
          fm.v("H"), fm.v("g"), fm.v("beta"));
      writeBack(fm, r);
      return r;
    };

    Form b = new Form(page("Heat transfer", "Radiation to surroundings",
        "Net radiative flux and radiative h between a surface and large surroundings."), "Radiation between a surface and its surroundings");
    b.f("eps", "emissivity", "-", "0.85").f("T", "surface temperature", "K", "800").f("Tsur", "surroundings temperature", "K", "300");
    b.calc = fm -> Core.radiation(fm.req("eps"), fm.req("T"), fm.req("Tsur"));

    Form c = new Form(page("Fire test", "Thermocouple correction",
        "Thermocouple reading from the gas temperature, or the gas temperature from a reading (radiation error)."),
        "Thermocouple (virtual instrument): h(Tg - Ttc) = eps(sigma Ttc^4 - G/4)");
    c.c("mode", "direction", "gas T -> thermocouple T (Newton)", "thermocouple T -> gas T (explicit)");
    fluidCombo(c, "gas (library) or entered");
    c.f("T", "known temperature (Tg or Ttc)", "K", "1200").f("eps", "bead / sheath emissivity", "-", "0.85");
    c.f("d", "probe diameter", "m", "0.0015").f("U", "gas velocity", "m/s", "10").f("p", "pressure (library)", "Pa", "101325");
    c.f("G", "incident radiation G (empty: 4 sigma T_sur^4)", "W/m^2", "").f("Tsur", "effective T_sur (if G empty)", "K", "600");
    c.f("rho", "density", "kg/m^3", "0.29").f("mu", "viscosity", "Pa-s", "4.6e-5");
    c.f("k", "conductivity", "W/m-K", "0.075").f("cp", "cp", "J/kg-K", "1175");
    c.calc = fm -> {
      Core.Props pr = props(fm, fm.v("p"));
      Core.R r = Core.thermocouple(fm.idx("mode") == 0, fm.req("T"), fm.req("eps"), fm.v("G"), fm.v("Tsur"),
          fm.req("d"), fm.req("U"), pr);
      writeBack(fm, r);
      return r;
    };

    Form d = new Form(page("Heat transfer", "Adiabatic surface temperature",
        "Single temperature for a surface exposed to hot gas and radiation (fire exposure boundary)."), "Adiabatic surface temperature (Wickstrom)");
    d.f("qinc", "incident radiative flux q_inc (empty: sigma T_sur^4)", "W/m^2", "").f("Tsur", "black environment T_sur", "K", "1100");
    d.f("h", "convection coefficient h", "W/m^2-K", "50").f("Tg", "gas temperature T_g", "K", "1000");
    d.f("eps", "surface emissivity", "-", "0.9").f("Ts", "a surface temperature for q_net (optional)", "K", "");
    d.calc = fm -> Core.ast(fm.v("qinc"), fm.v("Tsur"), fm.req("h"), fm.req("Tg"), fm.req("eps"), fm.v("Ts"));

    Form e = new Form(page("Fire test", "Water-cooled calorimeter",
        "Heat flux from the water heat-up, or the heat-up for a given flux."), "Water-cooled calorimeter");
    e.c("mode", "direction", "flux from water heat-up (Q = m cp dT)", "water dT from flux");
    e.c("area", "area", "projected A = D L", "full surface A = pi D L");
    e.f("mdot", "water mass flow", "kg/s", "0.05").f("cp", "water cp", "J/kg-K", "4180");
    e.f("dT", "water temperature rise", "K", "5").f("flux", "heat flux (inverse mode)", "kW/m^2", "100");
    e.f("D", "tube diameter D", "m", "0.0127").f("L", "exposed length L", "m", "0.1");
    e.calc = fm -> Core.calorimeter(fm.idx("mode") == 0, fm.req("mdot"), fm.req("cp"), fm.v("dT"), fm.v("flux"),
        fm.req("D"), fm.req("L"), fm.idx("area") == 0);
  }

  void buildCombustion() {
    Form a = new Form(page("Combustion & spray", "Stoichiometry",
        "Stoichiometric air-fuel ratio, equivalence ratio and heat release of a CxHyOz fuel."), "Stoichiometry of CxHyOz");
    a.f("x", "C atoms x", "-", "12").f("y", "H atoms y", "-", "23").f("z", "O atoms z", "-", "0");
    a.f("mAir", "air mass flow (for phi)", "kg/s", "").f("mFuel", "fuel mass flow (phi, heat release)", "g/s", "1.89");
    a.f("LHV", "lower heating value", "MJ/kg", "43.2");
    a.calc = fm -> Core.stoich(fm.req("x"), fm.req("y"), fm.req("z"), fm.v("mAir"), fm.v("mFuel"), fm.v("LHV"));

    Form b = new Form(page("Combustion & spray", "Rosin-Rammler spray",
        "Droplet size distribution parameters for injectors (SMD <-> characteristic size)."), "Rosin-Rammler distribution");
    b.c("mode", "given", "SMD (Sauter mean) -> X", "X (characteristic size) -> SMD");
    b.f("size", "SMD or X", "um", "38").f("n", "spread exponent n", "-", "3.5");
    b.f("d", "diameter d for mass fraction above d (optional)", "um", "100");
    b.f("F", "exceedance mass fraction for d_F (optional)", "-", "0.1");
    b.calc = fm -> Core.rosinRammler(fm.idx("mode") == 0, fm.req("size"), fm.req("n"), fm.v("d"), fm.v("F"));

    Form c = new Form(page("Combustion & spray", "Atomiser flow number",
        "Flow number of a pressure atomiser from the flow and the pressure drop."), "Flow number of a pressure atomiser");
    c.f("mdot", "fuel mass flow", "g/s", "1.89").f("rho", "liquid density", "kg/m^3", "800").f("dP", "pressure drop", "Pa", "689476");
    c.calc = fm -> Core.flowNumber(fm.req("mdot"), fm.req("rho"), fm.req("dP"));

    Form d = new Form(page("Combustion & spray", "Weber and Ohnesorge",
        "Break-up regime of drops and jets."), "Weber and Ohnesorge numbers");
    d.f("rho", "liquid density", "kg/m^3", "800").f("U", "relative velocity", "m/s", "30");
    d.f("d", "drop diameter", "um", "40").f("sigma", "surface tension", "N/m", "0.0255").f("mu", "liquid viscosity", "Pa-s", "1.3e-3");
    d.calc = fm -> Core.droplet(fm.req("rho"), fm.req("U"), fm.req("d"), fm.req("sigma"), fm.v("mu"));

    Form g = new Form(page("Combustion & spray", "Droplet response (Stokes)",
        "Relaxation time and Stokes number: do droplets or particles follow the flow?"), "Droplet relaxation time and Stokes number");
    g.f("rhoP", "particle density", "kg/m^3", "800").f("d", "particle diameter", "um", "40");
    g.f("mu", "gas viscosity", "Pa-s", "1.846e-5").f("rhoG", "gas density (for Re_p)", "kg/m^3", "1.177");
    g.f("Urel", "slip velocity (for Re_p)", "m/s", "5").f("U", "flow velocity U (for Stk)", "m/s", "10");
    g.f("L", "flow length L (for Stk)", "m", "0.05");
    g.c("sn", "drag", "Stokes (no correction)", "Schiller-Naumann factor (1 + 0.15 Re_p^0.687)");
    g.calc = fm -> Core.particle(fm.req("rhoP"), fm.req("d"), fm.req("mu"), fm.v("rhoG"), fm.v("Urel"), fm.v("U"),
        fm.v("L"), fm.idx("sn") == 1);
  }

  void buildUnits() {
    Form a = new Form(page("Units", "Unit conversions", "Heat flux, power, pressure, speed and length (US <-> SI)."),
        "Linear conversions");
    a.c("conv", "conversion", Core.CONV_NAMES);
    a.f("v", "value (in the unit of the conversion)", "", "1");
    a.calc = fm -> Core.convert(fm.idx("conv"), fm.req("v"));

    Form b = new Form(page("Units", "Temperature", "K, degC and degF."), "Temperature");
    b.c("unit", "unit of the value", "K", "degC", "degF");
    b.f("v", "value (in the unit selected above)", "", "60");
    b.calc = fm -> Core.temperature(fm.req("v"), fm.idx("unit"));

    Form c = new Form(page("Units", "US gal/h to kg/s", "Liquid fuel flow from US gallons per hour."), "US gal/h -> kg/s");
    c.f("Q", "volume flow", "US gal/h", "1").f("rho", "liquid density", "kg/m^3", "800");
    c.calc = fm -> Core.galPerHour(fm.req("Q"), fm.req("rho"));

    Form d = new Form(page("Units", "SCFM to kg/s", "Gas mass flow from standard cubic feet per minute."),
        "SCFM -> kg/s (ideal gas at the standard conditions)");
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
        labels.add(f.label + (f.unit.isEmpty() ? "" : "  [" + f.unit + "]"));
        fs.add(f);
      }
    }
    String c = Ui.choose(title, "Input field of '" + t.name + "':", labels.toArray(new String[0]));
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

      final List<String> tex = new ArrayList<>();   // formulas as written (a small LaTeX), drawn in the window

      /** A formula in a small LaTeX (see TeX): drawn in the window, plain text in the copied results. */
      R f(String s) {
        tex.add(s);
        lines.add("Formula:  " + TeX.plain(s));
        return this;
      }
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
        r.f("f = 0.316\\,Re^{-0.25} ; \\tau_w = \\frac{f}{8} \\rho U^2 \\quad \\text{(U: bulk velocity, L: hydraulic diameter)}");
        r.src("Blasius (1913), smooth pipe; e.g. White, Fluid Mechanics");
        r.valid("smooth pipe, turbulent, 4e3 <= Re_Dh <= 1e5");
        r.range("Re_Dh", Re, 4e3, 1e5);
      } else if (corr == 2) {
        double cf = 0.664 / Math.sqrt(Re);
        tau = cf * qdyn;
        r.put("cf", cf, "-", "local skin-friction coefficient at x = L");
        r.f("c_f = \\frac{0.664}{\\sqrt{Re_x}} ; \\tau_w = c_f \\frac{1}{2} \\rho U^2");
        r.src("Blasius laminar flat-plate solution; Schlichting, Boundary-Layer Theory");
        r.valid("laminar flat plate, zero pressure gradient, Re_x < 5e5");
        r.range("Re_x", Re, 0, 5e5);
      } else {
        double cf = Math.pow(2 * Math.log10(Re) - 0.65, -2.3);
        tau = cf * qdyn;
        r.put("cf", cf, "-", "local skin-friction coefficient at x = L");
        r.f("c_f = \\left(2 \\log_{10} Re_x - 0.65\\right)^{-2.3} ; \\tau_w = c_f \\frac{1}{2} \\rho U^2");
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
      r.f("u_\\tau = \\sqrt{\\frac{\\tau_w}{\\rho}} ; y = \\frac{y^+ \\nu}{u_\\tau} ; \\text{first layer} = 2y");
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
      res.f("N = \\left\\lceil \\frac{\\ln\\left(1 + T (r - 1)/y_1\\right)}{\\ln r} \\right\\rceil");
      prismCommon(res, y1, r, n, core);
      return res;
    }

    static void prismCommon(R res, double y1, double r, int n, double core) {
      double last = y1 * Math.pow(r, n - 1);
      res.put("last", last, "m", "last layer thickness y1 r^(N-1)");
      res.f("T = y_1 \\frac{r^N - 1}{r - 1} ; \\text{last layer} = y_1 r^{N-1}");
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
      r.f("y_{1,new} = y_{1,old} \\frac{y^+_{target}}{y^+_{measured}}");
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
      r.f("Re = \\frac{\\rho U L}{\\mu}");
      if (given(cp) && given(k)) {
        double Pr = mu * pos("cp", cp) / pos("k", k);
        r.put("Pr", Pr, "-", "mu cp / k").put("alpha", k / (rho * cp), "m^2/s", "k/(rho cp)").put("Pe", Re * Pr, "-", "Re Pr = U L / alpha");
        r.f("Pr = \\frac{\\mu c_p}{k} ; Pe = Re\\,Pr");
      }
      if (given(D)) {
        r.put("Sc", nu / pos("D", D), "-", "nu / D");
        r.f("Sc = \\frac{\\nu}{D}");
      }
      r.src("definitions");
      return r;
    }

    static R mach(double U, double gamma, double Rg, double T) {
      pos("gamma", gamma); pos("R", Rg); pos("T", T);
      R r = new R("Mach number");
      double c = Math.sqrt(gamma * Rg * T);
      r.put("c", c, "m/s", "speed of sound sqrt(gamma R T)").put("Ma", U / c, "-", "U / c");
      r.f("c = \\sqrt{\\gamma R T} ; Ma = \\frac{U}{c}").src("ideal gas");
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
      r.f("Fr = \\frac{U}{\\sqrt{g L}} ; Ri = \\frac{g \\beta \\Delta T L}{U^2} ; Gr = \\frac{g \\beta \\Delta T L^3}{\\nu^2} ; Ra = Gr\\,Pr").src("definitions");
      return r;
    }

    static R strouhal(double L, double U, double St, double f) {
      pos("L", L); pos("U", U);
      R r = new R("Strouhal number");
      if (given(St)) r.put("f", St * U / L, "Hz", "f = St U / L").put("St", St, "-", "given");
      else if (given(f)) r.put("St", f * L / U, "-", "St = f L / U").put("f", f, "Hz", "given");
      else throw new IllegalArgumentException("enter St or f");
      r.put("period", L / (r.get("St") * U), "s", "1/f");
      r.f("St = \\frac{f L}{U}").src("definition (cylinder vortex shedding St ~ 0.2 for 300 < Re < 2e5)");
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
      r.f("We = \\frac{\\rho U^2 d}{\\sigma} ; Oh = \\frac{\\mu}{\\sqrt{\\rho \\sigma d}} = \\frac{\\sqrt{We}}{Re}");
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
      r.f("Bi = \\frac{h L}{k} ; Fo = \\frac{\\alpha t}{L^2}").src("definitions");
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
      r.f("\\tau_p = \\frac{\\rho_p d^2}{18 \\mu \\left(1 + 0.15\\,Re_p^{0.687}\\right)} ; Stk = \\frac{\\tau_p U}{L}");
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
      r.f("k = 1.5\\,(U I)^2 ; \\varepsilon = \\frac{C_\\mu^{0.75} k^{1.5}}{l} ; \\omega = \\frac{k^{0.5}}{C_\\mu^{0.25} l} ; \\nu_t = \\frac{C_\\mu k^2}{\\varepsilon}");
      r.f("\\text{pipe estimates:}\\quad I = 0.16\\,Re_{D_h}^{-1/8}, \\quad l = 0.07\\,D_h");
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
      r.f("\\Delta t = \\frac{Co\\,\\Delta x}{U} ; \\text{steps per period} = \\frac{1}{f\\,\\Delta t} ; t_{avg} = \\frac{N}{f} ; \\text{core-h} = \\frac{\\text{steps} \\cdot \\text{s/step} \\cdot \\text{cores}}{3600}");
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
      r.f("CI_{95} = \\frac{t_{0.975,\\,n_b - 1}\\; s_b}{\\sqrt{n_b}} ; \\tau_{int} = \\int_0^{\\text{first zero}} \\rho(\\tau)\\,d\\tau ; N_{eff} = \\frac{T}{2\\,\\tau_{int}}");
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
      r.f("h = \\left(\\frac{1}{N}\\right)^{1/dim} ; p = \\frac{\\left|\\,\\ln\\left|e_{32}/e_{21}\\right| + q(p)\\,\\right|}{\\ln r_{21}} ; q = \\ln \\frac{r_{21}^p - s}{r_{32}^p - s} ; s = \\text{sign}(e_{32}/e_{21})");
      r.f("\\phi_{ext}^{21} = \\frac{r_{21}^p \\phi_1 - \\phi_2}{r_{21}^p - 1} ; GCI_{fine}^{21} = \\frac{1.25\\,e_a^{21}}{r_{21}^p - 1}");
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

    /** Flow-through / residence time and the averaging window of an unsteady run. */
    static R flowThrough(double mdot, double rho, double V, double L, double A, double nInit, double nFt, double dt) {
      pos("mass flow", Math.abs(mdot));
      pos("density", rho);
      R r = new R("Flow-through time and averaging window");
      double Q = Math.abs(mdot) / rho;
      r.put("Q", Q, "m^3/s", "volume flow |mdot| / rho");
      double tRef = Double.NaN;
      if (given(V)) {
        double tRes = pos("volume", V) / Q;
        r.put("tResidence", tRes, "s", "mean residence time V / Q (whole domain)");
        tRef = tRes;
      }
      if (given(L) && given(A)) {
        double U = Q / pos("cross-section", A), tFt = pos("length", L) / U;
        r.put("Ubulk", U, "m/s", "bulk velocity Q / A");
        r.put("tFlowThrough", tFt, "s", "flow-through time L / U (main flow path)");
        tRef = tFt;
      }
      if (!given(tRef)) throw new IllegalArgumentException("give the domain volume V, or the length L and the cross-section A");
      double tInit = nInit * tRef, tAvg = nFt * tRef;
      r.put("tDiscard", tInit, "s", "start-up to discard = N_init x reference time");
      r.put("tAvgStart", tInit, "s", "start of the averaging window (enter it when Export results asks)");
      r.put("tAvg", tAvg, "s", "averaging window = N_avg x reference time");
      r.put("tEnd", tInit + tAvg, "s", "physical time to run (maximum physical time)");
      if (given(dt)) r.put("steps", Math.ceil((tInit + tAvg) / pos("time step", dt)), "-", "time steps to run");
      if (nFt < 2) r.warn("fewer than 2 flow-throughs averaged: the mean is usually not converged");
      r.f("Q = \\frac{|\\dot m|}{\\rho} ; t_{res} = \\frac{V}{Q} ; t_{ft} = \\frac{L}{Q/A} ; \\text{window} = N \\times t_{ref} \\quad \\text{(}t_{ref} = t_{ft} \\text{ if L and A are given)}");
      r.src("common practice for statistically steady unsteady RANS / LES: discard 1-3, average 2-5 flow-throughs");
      r.valid("one main flow path; recirculation zones can need longer windows: check the averaged results of Export results (drift_pct)");
      return r;
    }

    // ----- Inlet & outlet values, orifice ----------------------------------------------------------------

    static final String[] INLET_GIVEN = {"mass flow [kg/s]", "mass flow [g/s]", "velocity [m/s]", "volume flow [m^3/s]",
        "volume flow [l/min]", "standard volume flow [Nl/min, 0 degC, 1 atm]", "standard volume flow [SCFM, 60 degF, 14.696 psi]",
        "total pressure, gauge [Pa] (relative to the reference)", "Mach number [-]"};
    static final double T_NORMAL = 273.15, T_SCFM = 288.70556, P_STD = 101325, FT3 = 0.028316846592;

    /** Uniform flow through a section: one quantity given, all boundary values out. Rg = NaN: liquid (constant density). */
    static R inletOutlet(int given, double value, double p, double T, double pref, double D, double A, double rho, double mu,
        double cp, double gammaIn, double Rg) {
      pos("static pressure", p); pos("static temperature", T); pos("density", rho);
      if (Double.isNaN(pref)) throw new IllegalArgumentException("reference pressure is required (STAR: Physics > Reference Values)");
      double area = given(D) ? Math.PI * pos("diameter D", D) * D / 4 : pos("area A (or a diameter)", A);
      double dEq = given(D) ? D : Math.sqrt(4 * area / Math.PI);
      boolean gas = given(Rg) && Rg > 0;
      R r = new R("Inlet & outlet values");
      double gam = Double.NaN, c = Double.NaN;
      if (gas) {
        if (given(gammaIn)) gam = gammaIn;
        else if (given(cp) && cp > Rg) gam = cp / (cp - Rg);
        else {
          gam = 1.4;
          r.warn("gamma = 1.4 assumed (no cp): enter gamma for other gases");
        }
        if (gam <= 1) throw new IllegalArgumentException("gamma must be > 1 (got " + g(gam) + ")");
        c = Math.sqrt(gam * Rg * T);
      }
      double U;
      switch (given) {
        case 0: U = value / (rho * area); break;
        case 1: U = value * 1e-3 / (rho * area); break;
        case 2: U = value; break;
        case 3: U = value / area; break;
        case 4: U = value / 60000.0 / area; break;
        case 5:
        case 6: {
          double ts = given == 5 ? T_NORMAL : T_SCFM;
          double rhoS = gas ? P_STD / (Rg * ts) : rho;
          double qs = given == 5 ? value / 60000.0 : value * FT3 / 60.0;
          U = qs * rhoS / (rho * area);
          break;
        }
        case 7: {
          double p0 = value + pref;
          if (p0 <= p) {
            throw new IllegalArgumentException("total pressure " + g(p0) + " Pa (absolute = gauge + reference) must be above the static pressure "
                + g(p) + " Pa");
          }
          U = gas ? Math.sqrt(2 / (gam - 1) * (Math.pow(p0 / p, (gam - 1) / gam) - 1)) * c : Math.sqrt(2 * (p0 - p) / rho);
          break;
        }
        case 8:
          if (!gas) throw new IllegalArgumentException("a Mach number needs a gas (an ideal-gas fluid)");
          U = value * c;
          break;
        default: throw new IllegalArgumentException("unknown given quantity");
      }
      if (!(U > 0) || Double.isInfinite(U)) throw new IllegalArgumentException("the flow must be > 0 (got U = " + g(U) + " m/s)");
      double mdot = rho * U * area, q = 0.5 * rho * U * U;
      r.put("mdot", mdot, "kg/s", "mass flow  -> Mass Flow Inlet");
      r.put("U", U, "m/s", "mean velocity  -> Velocity Inlet");
      if (gas) {
        double Ma = U / c, fac = 1 + 0.5 * (gam - 1) * Ma * Ma;
        double p0 = p * Math.pow(fac, gam / (gam - 1)), T0 = T * fac;
        r.put("p0_gauge", p0 - pref, "Pa", "total pressure (isentropic), gauge  -> Stagnation Inlet");
        r.put("T0", T0, "K", "total temperature  -> Stagnation / Mass Flow Inlet");
        r.put("p_gauge", p - pref, "Pa", "static pressure, gauge  -> Pressure Outlet");
        r.put("Ma", Ma, "-", "Mach number U / c");
        r.put("Q", U * area, "m^3/s", "actual volume flow at p, T");
        r.put("Q_lpm", U * area * 60000, "l/min", "actual volume flow at p, T");
        r.put("Qn_lpm", mdot / (P_STD / (Rg * T_NORMAL)) * 60000, "Nl/min", "standard flow at 0 degC, 1 atm");
        r.put("SCFM", mdot / (P_STD / (Rg * T_SCFM)) * 60 / FT3, "SCFM", "standard flow at 60 degF, 14.696 psi");
        r.put("p0_gauge_incomp", p - pref + q, "Pa", "total pressure p + rho U^2/2 (incompressible), gauge");
        r.put("q", q, "Pa", "dynamic pressure rho U^2 / 2");
        double mStar = area * p0 * Math.sqrt(gam / (Rg * T0)) * Math.pow(2 / (gam + 1), (gam + 1) / (2 * (gam - 1)));
        r.put("mdot_choked", mStar, "kg/s", "largest mass flow of this section at these total conditions (Ma = 1)");
        r.put("c", c, "m/s", "speed of sound sqrt(gamma R T)");
        r.put("gamma", gam, "-", "ratio of specific heats");
        r.put("R", Rg, "J/kg-K", "gas constant");
        if (Ma >= 1) r.warn("Ma = " + g(Ma) + " >= 1: the section cannot carry this flow subsonically; check the area, the flow or the pressure");
        else if (Ma > 0.7) r.warn("Ma = " + g(Ma) + " > 0.7: close to choking; the flow reacts strongly to the outlet pressure");
        else if (Ma > 0.3) {
          r.warn("Ma = " + g(Ma) + " > 0.3: use an ideal-gas (compressible) density in STAR-CCM+; the incompressible total pressure is "
              + g(100 * (p0 - p - q) / (p0 - p)) + " % low");
        }
      } else {
        r.put("p0_gauge", p - pref + q, "Pa", "total pressure p + rho U^2/2, gauge  -> Stagnation Inlet");
        r.put("p_gauge", p - pref, "Pa", "static pressure, gauge  -> Pressure Outlet");
        r.put("Q", U * area, "m^3/s", "volume flow");
        r.put("Q_lpm", U * area * 60000, "l/min", "volume flow");
        r.put("q", q, "Pa", "dynamic pressure rho U^2 / 2");
      }
      r.put("rho", rho, "kg/m^3", "density at p, T");
      if (given(mu) && mu > 0) r.put("Re_D", rho * U * dEq / mu, "-", "Reynolds number on " + (given(D) ? "D" : "the equivalent diameter"));
      r.put("A", area, "m^2", "flow area");
      r.f("\\dot m = \\rho U A ; Q = U A ; q = \\frac{1}{2} \\rho U^2 ; p_{gauge} = p_{abs} - p_{ref}");
      if (gas) {
        r.f("p_0 = p \\left(1 + \\frac{\\gamma - 1}{2} Ma^2\\right)^{\\gamma/(\\gamma - 1)} ; T_0 = T \\left(1 + \\frac{\\gamma - 1}{2} Ma^2\\right) ; \\dot m_{max} = A\\,p_0 \\sqrt{\\frac{\\gamma}{R T_0}} \\left(\\frac{2}{\\gamma + 1}\\right)^{\\frac{\\gamma + 1}{2(\\gamma - 1)}}");
        r.line("treated as an ideal gas, R = " + g(Rg) + " J/kg-K, gamma = " + g(gam));
      } else {
        r.line("treated as a liquid (constant density)");
      }
      r.line("standard flows: Nl/min at 0 degC, 101325 Pa; SCFM at 60 degF, 14.696 psi");
      r.src("mass conservation; isentropic ideal-gas relations (e.g. Anderson, Modern Compressible Flow)");
      r.valid("uniform (plug) flow over the section; p, T are static values at the section. STAR-CCM+ boundary pressures are gauge, "
          + "relative to the reference pressure (Continua > Physics > Reference Values)");
      return r;
    }

    static final String[] ORIFICE_SOLVE = {"mass flow (from the pressures)", "upstream pressure (for a mass flow)",
        "hole diameter (for a mass flow)"};

    /** Mass flow through holes of total area A from upstream total (p0, T0) to downstream static pb. */
    static double orificeFlow(double p0, double pb, double T0, double A, double Cd, double rho, double gam, double Rg) {
      if (!given(Rg)) return p0 <= pb ? 0 : Cd * A * Math.sqrt(2 * rho * (p0 - pb));
      double pr = pb / p0, prc = Math.pow(2 / (gam + 1), gam / (gam - 1));
      if (pr >= 1) return 0;
      if (pr <= prc) return Cd * A * p0 * Math.sqrt(gam / (Rg * T0)) * Math.pow(2 / (gam + 1), (gam + 1) / (2 * (gam - 1)));
      return Cd * A * p0 * Math.sqrt(2 * gam / ((gam - 1) * Rg * T0) * (Math.pow(pr, 2 / gam) - Math.pow(pr, (gam + 1) / gam)));
    }

    static R orifice(int solve, double mdot, double p0, double pb, double T0, double D, double n, double Cd, double rho, double cp,
        double gammaIn, double Rg) {
      pos("downstream pressure", pb); pos("upstream temperature", T0); pos("number of holes", n); pos("Cd", Cd);
      boolean gas = given(Rg) && Rg > 0;
      if (!gas) pos("density", rho);
      R r = new R("Orifice / nozzle flow");
      double gam = Double.NaN;
      if (gas) {
        if (given(gammaIn)) gam = gammaIn;
        else if (given(cp) && cp > Rg) gam = cp / (cp - Rg);
        else {
          gam = 1.4;
          r.warn("gamma = 1.4 assumed (no cp): enter gamma for other gases");
        }
        if (gam <= 1) throw new IllegalArgumentException("gamma must be > 1 (got " + g(gam) + ")");
      }
      if (solve == 0) {
        pos("upstream pressure", p0); pos("hole diameter", D);
        if (p0 <= pb) throw new IllegalArgumentException("upstream pressure must be above the downstream pressure");
        mdot = orificeFlow(p0, pb, T0, n * Math.PI * D * D / 4, Cd, rho, gam, Rg);
      } else if (solve == 1) {
        pos("mass flow", mdot); pos("hole diameter", D);
        double A = n * Math.PI * D * D / 4, lo = pb, hi = pb * 2;
        while (orificeFlow(hi, pb, T0, A, Cd, rho, gam, Rg) < mdot) {
          hi *= 2;
          if (hi > 1e12) throw new IllegalArgumentException("no pressure below 1e12 Pa gives this flow: check the diameter and the flow");
        }
        for (int i = 0; i < 200; i++) {
          double mid = 0.5 * (lo + hi);
          if (orificeFlow(mid, pb, T0, A, Cd, rho, gam, Rg) < mdot) lo = mid; else hi = mid;
        }
        p0 = 0.5 * (lo + hi);
      } else {
        pos("mass flow", mdot); pos("upstream pressure", p0);
        if (p0 <= pb) throw new IllegalArgumentException("upstream pressure must be above the downstream pressure");
        double perArea = orificeFlow(p0, pb, T0, 1.0, Cd, rho, gam, Rg);
        D = Math.sqrt(4 * mdot / perArea / n / Math.PI);
      }
      double A = n * Math.PI * D * D / 4;
      r.put("mdot", mdot, "kg/s", "mass flow, all holes");
      r.put("p0", p0, "Pa", "upstream total pressure (absolute)");
      r.put("dp", p0 - pb, "Pa", "pressure drop p0 - pb");
      r.put("D", D, "m", "hole diameter (each)");
      r.put("A", A, "m^2", "total hole area");
      if (gas) {
        double prc = Math.pow(2 / (gam + 1), gam / (gam - 1)), pr = pb / p0;
        boolean choked = pr <= prc;
        double Ma = choked ? 1 : Math.sqrt(2 / (gam - 1) * (Math.pow(p0 / pb, (gam - 1) / gam) - 1));
        double Tt = T0 / (1 + 0.5 * (gam - 1) * Ma * Ma);
        r.put("choked", choked ? 1 : 0, "-", choked ? "1 = choked: the flow no longer depends on the downstream pressure" : "0 = not choked");
        r.put("pr", pr, "-", "pressure ratio pb / p0");
        r.put("pr_crit", prc, "-", "critical ratio (2/(g+1))^(g/(g-1)); choked below it");
        r.put("U_jet", Ma * Math.sqrt(gam * Rg * Tt), "m/s", "ideal jet velocity at the throat");
        r.put("Ma_jet", Ma, "-", "throat Mach number (ideal)");
        r.put("T_jet", Tt, "K", "static temperature at the throat");
        r.f("\\text{choked}\\;(p_b/p_0 \\le (p_b/p_0)_{crit}): \\quad \\dot m = C_d A\\,p_0 \\sqrt{\\frac{\\gamma}{R T_0}} \\left(\\frac{2}{\\gamma + 1}\\right)^{\\frac{\\gamma + 1}{2(\\gamma - 1)}}");
        r.f("\\text{not choked:}\\quad \\dot m = C_d A\\,p_0 \\sqrt{\\frac{2\\gamma}{(\\gamma - 1) R T_0} \\left[\\left(\\frac{p_b}{p_0}\\right)^{2/\\gamma} - \\left(\\frac{p_b}{p_0}\\right)^{(\\gamma + 1)/\\gamma}\\right]}");
        r.line("ideal gas, R = " + g(Rg) + " J/kg-K, gamma = " + g(gam));
      } else {
        r.put("U_jet", Math.sqrt(2 * (p0 - pb) / rho), "m/s", "ideal jet velocity sqrt(2 dp / rho)");
        r.f("\\dot m = C_d A \\sqrt{2 \\rho\\,(p_0 - p_b)} \\quad \\text{(liquid, incompressible)}");
      }
      r.put("Cd", Cd, "-", "discharge coefficient");
      r.line("Cd: sharp-edged orifice 0.60-0.62; short tube 0.8; well-rounded nozzle 0.95-0.99 (measure it when it matters)");
      r.src("isentropic nozzle flow (e.g. Anderson, Modern Compressible Flow); ISO 5167 for orifice plates in pipes");
      r.valid("upstream velocity neglected (hole much smaller than the feed pipe, d/D < ~0.3); p0, T0 upstream total values");
      r.range("Cd", Cd, 0.3, 1.0);
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
          r.f("Nu = 0.3 + \\frac{0.62\\,Re^{1/2} Pr^{1/3}}{\\left[1 + (0.4/Pr)^{2/3}\\right]^{1/4}} \\left[1 + \\left(\\frac{Re}{282000}\\right)^{5/8}\\right]^{4/5}");
          r.src("Churchill & Bernstein 1977, J. Heat Transfer 99:300 (Incropera eq. 7.54)");
          r.valid("Re Pr >= 0.2, properties at T_film");
          r.range("Re Pr", re * Pr, 0.2, Double.POSITIVE_INFINITY);
          break;
        case 1:
          nuS = 0.664 * Math.sqrt(re) * Math.cbrt(Pr);
          r.f("Nu_{avg} = 0.664\\,Re_L^{1/2}\\,Pr^{1/3}").src("Incropera eq. 7.30 (laminar flat plate, average)");
          r.valid("Re_L <= 5e5, Pr >= 0.6");
          r.range("Re_L", re, 0, 5e5).range("Pr", Pr, 0.6, Double.POSITIVE_INFINITY);
          break;
        case 2:
          nuS = (0.037 * Math.pow(re, 0.8) - 871) * Math.cbrt(Pr);
          r.f("Nu_{avg} = \\left(0.037\\,Re_L^{0.8} - 871\\right) Pr^{1/3}").src("Incropera eq. 7.38 (mixed boundary layer, Re_x,c = 5e5)");
          r.valid("5e5 < Re_L <= 1e8, 0.6 <= Pr <= 60");
          r.range("Re_L", re, 5e5, 1e8).range("Pr", Pr, 0.6, 60);
          break;
        case 3:
          nuS = 2 + (0.4 * Math.sqrt(re) + 0.06 * Math.pow(re, 2.0 / 3)) * Math.pow(Pr, 0.4);
          r.f("Nu = 2 + \\left(0.4\\,Re^{1/2} + 0.06\\,Re^{2/3}\\right) Pr^{0.4} \\left(\\frac{\\mu}{\\mu_s}\\right)^{1/4}, \\quad \\mu/\\mu_s = 1");
          r.src("Whitaker 1972, AIChE J. 18:361 (Incropera eq. 7.56)");
          r.valid("3.5 <= Re <= 7.6e4, 0.71 <= Pr <= 380; Whitaker evaluates properties at T_inf (here T_film)");
          r.range("Re", re, 3.5, 7.6e4).range("Pr", Pr, 0.71, 380);
          break;
        case 4:
          nuS = gnielinski(re, Pr);
          r.put("f", Math.pow(0.790 * Math.log(re) - 1.64, -2), "-", "Petukhov friction factor");
          r.f("f = \\left(0.790 \\ln Re - 1.64\\right)^{-2} ; Nu = \\frac{(f/8)\\,(Re - 1000)\\,Pr}{1 + 12.7 \\sqrt{f/8}\\,\\left(Pr^{2/3} - 1\\right)}");
          r.src("Gnielinski 1976, Int. Chem. Eng. 16:359 (Incropera eq. 8.62)");
          r.valid("3000 <= Re_D <= 5e6, 0.5 <= Pr <= 2000, fully developed; properties at the bulk temperature (here T_film)");
          r.range("Re_D", re, 3000, 5e6).range("Pr", Pr, 0.5, 2000);
          break;
        case 5: {
          pos("r", rad); pos("H", H);
          double rd = rad / L, hd = H / L;
          nuS = martinJet(re, Pr, 1 / rd, hd);
          r.put("rOverD", rd, "-", "r/D").put("HOverD", hd, "-", "H/D");
          r.f("Nu = Pr^{0.42}\\,G\\,F ; F = 2\\,Re^{1/2} \\left(1 + 0.005\\,Re^{0.55}\\right)^{1/2} ; G = \\frac{D}{r} \\cdot \\frac{1 - 1.1\\,D/r}{1 + 0.1\\,(H/D - 6)\\,D/r}");
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
          r.f("Nu = \\left\\{0.825 + \\frac{0.387\\,Ra^{1/6}}{\\left[1 + (0.492/Pr)^{9/16}\\right]^{8/27}}\\right\\}^2");
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
      r.f("q = \\varepsilon \\sigma \\left(T^4 - T_{sur}^4\\right) ; \\sigma = 5.670374 \\times 10^{-8}\\;\\text{W/m}^2\\text{K}^4").src("Stefan-Boltzmann, grey diffuse surface");
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
      r.f("h\\,(T_g - T_{tc}) = \\varepsilon \\left(\\sigma T_{tc}^4 - \\frac{G}{4}\\right) ; \\text{G/4: irradiation of a sphere or cylinder in a field of incident radiation G}");
      r.f("h \\text{ from } Nu_{CB}(Re_d, Pr), \\quad \\text{properties at } \\frac{T_g + T_{tc}}{2}");
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
      r.f("\\varepsilon \\left(q_{inc} - \\sigma T_{AST}^4\\right) + h\\,(T_g - T_{AST}) = 0 \\quad \\text{(Newton)} ; q_{net}(T_s) = \\varepsilon \\left(q_{inc} - \\sigma T_s^4\\right) + h\\,(T_g - T_s)");
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
      r.f("Q = \\dot m\\,c_p \\Delta T ; \\text{flux} = \\frac{Q}{A}").src("steady energy balance of the cooling water");
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
      r.f("AFR_{st} = \\frac{x + y/4 - z/2}{0.2095} \\cdot \\frac{M_{air}}{M_{fuel}} ; M_{air} = 28.965 ; \\phi = \\frac{AFR_{st}}{\\dot m_{air}/\\dot m_{fuel}}");
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
      r.f("Q(>d) = \\exp\\left[-\\left(\\frac{d}{X}\\right)^n\\right] ; SMD = \\frac{X}{\\Gamma(1 - 1/n)} ; d_F = X\\,(-\\ln F)^{1/n}");
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
      r.f("FN = \\frac{\\dot m}{\\sqrt{\\rho_L \\Delta p}}").src("Lefebvre & McDonell, Atomization and Sprays");
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
      r.f("T\\,[\\text{K}] = T\\,[\\text{\u00B0C}] + 273.15 ; T\\,[\\text{\u00B0F}] = 1.8\\,T\\,[\\text{\u00B0C}] + 32").src("definition");
      return r;
    }

    static R galPerHour(double q, double rho) {
      pos("density", rho);
      R r = new R("US gal/h -> kg/s");
      double v = q * 3.785411784e-3 / 3600;
      r.put("Vdot", v, "m^3/s", "volume flow").put("mdot", v * rho, "kg/s", "mass flow");
      r.f("\\dot m = Q\\,[\\text{gal/h}] \\cdot \\frac{3.785411784 \\times 10^{-3}}{3600} \\cdot \\rho").src("1 US gal = 3.785411784 L (exact)");
      return r;
    }

    static R scfm(double q, double tF, double pPsi, double M) {
      pos("p_std", pPsi); pos("M", M);
      R r = new R("SCFM -> kg/s");
      double T = (tF - 32) / 1.8 + 273.15, p = pPsi * 6894.757293, rho = p * M / (RU * pos("T_std", T)), v = q * 0.028316846592 / 60;
      r.put("Tstd", T, "K", "standard temperature").put("pstd", p, "Pa", "standard pressure");
      r.put("rhoStd", rho, "kg/m^3", "ideal-gas density p M / (R_u T)");
      r.put("Vdot", v, "m^3/s", "standard volume flow").put("mdot", v * rho, "kg/s", "mass flow");
      r.f("\\rho_{std} = \\frac{p M}{R_u T}, \\quad R_u = 8314.462618\\;\\text{J/kmol K} ; \\dot m = \\text{SCFM} \\cdot \\frac{0.028316846592}{60} \\cdot \\rho_{std}");
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
      R ftr = flowThrough(0.046, 1.2, 0.01, 1.0, 0.01, 2, 3, 1e-3);
      sb.append(String.format(Locale.ROOT, "Flow-through mdot 0.046, rho 1.2, L 1, A 0.01 -> t_ft = %.4f s (0.2609), "
          + "window start %.4f s (0.5217), end %.4f s (1.3043)%n", ftr.get("tFlowThrough"), ftr.get("tAvgStart"), ftr.get("tEnd")));
      sb.append(String.format(Locale.ROOT, "AST with q_inc = sigma Tg^4, Tg = 1000 K -> %.6f K%n",
          ast(SIGMA * 1e12, Double.NaN, 25, 1000, 0.9, Double.NaN).get("TAST")));
      R io = inletOutlet(0, 0.05, 101325, 300, 101325, 0.05, Double.NaN, 101325 / (287.05 * 300), 1.846e-5, 1007, Double.NaN, 287.05);
      R io2 = inletOutlet(7, io.get("p0_gauge"), 101325, 300, 101325, 0.05, Double.NaN, 101325 / (287.05 * 300), 1.846e-5, 1007,
          Double.NaN, 287.05);
      sb.append(String.format(Locale.ROOT, "Inlet air 0.05 kg/s, D 50 mm, 1 atm, 300 K -> U = %.4f m/s (21.642), Ma = %.5f ; back from p0: "
          + "mdot = %.6f (0.05)%n", io.get("U"), io.get("Ma"), io2.get("mdot")));
      R or = orifice(0, Double.NaN, 5e5, 101325, 300, 1e-3, 1, 1, Double.NaN, Double.NaN, 1.4, 287.05);
      R or2 = orifice(1, or.get("mdot"), Double.NaN, 101325, 300, 1e-3, 1, 1, Double.NaN, Double.NaN, 1.4, 287.05);
      sb.append(String.format(Locale.ROOT, "Orifice air 5 bar, 300 K, 1 mm, Cd 1 -> mdot = %.5e kg/s (9.1630e-04, choked=%d) ; back: p0 = %.1f Pa%n",
          or.get("mdot"), (int) or.get("choked"), or2.get("p0")));
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
    final List<String> matml = new ArrayList<>();   // what was read from MatML folders (one line per file)
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
      if (!matml.isEmpty()) sb.append("\n  ").append(matml.get(0));
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
      for (String kk : new String[] {"k", "k_x", "k_y", "k_z", "k_zz", "k_xy"}) {
        Double k = v.get(kk);
        if (k != null && rho != null && cp != null) r.put("alpha_" + kk, k / (rho * cp), "m^2/s", "derived " + kk + "/(rho cp)");
      }
      r.valid("each row is valid in its T range (shown); outside it the value is CLAMPED and a warning is given");
      r.lines.add("");
      r.lines.add("Emissivity (data/emissivity.csv): typical [min-max] -- source -- note");
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
  // MatML (*.xml) material files -> library materials (no STAR types; unit-testable with a plain JDK)
  // =====================================================================================================

  /** Reads MatML 3.x files: plain MatML (values in PropertyData/Data) and ANSYS Engineering Data exports (values in
   *  ParameterValue marked "Dependent", temperature as the "Independent" parameter). Known properties are mapped to
   *  rho, cp, k (k_x, k_y, k_z if orthotropic) and eps with SI units; temperatures in C/F become K; any other numeric
   *  property is kept under its own name and unit. A temperature series becomes an interpolated table. */
  static final class MatML {
    /** Loads every *.xml below 'folder' (2 levels) into lib.materials; one report line per file in 'report'. */
    static int loadFolder(Library lib, Path folder, List<String> report) {
      int n = 0;
      if (folder == null || !Files.isDirectory(folder)) {
        report.add("MatML folder not found: " + folder);
        return 0;
      }
      List<Path> files = new ArrayList<>();
      try (java.util.stream.Stream<Path> w = Files.walk(folder, 3)) {
        w.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".xml"))
            .sorted().forEach(files::add);
      } catch (IOException e) {
        report.add("MatML folder unreadable: " + folder + " (" + e.getMessage() + ")");
        return 0;
      }
      for (Path f : files) {
        try {
          int k = loadFile(lib, f, report);
          n += k;
        } catch (Throwable t) {
          report.add(f.getFileName() + ": not read (" + t.getClass().getSimpleName() + ": " + t.getMessage() + ")");
        }
      }
      report.add(0, "MatML folder " + folder + ": " + files.size() + " file(s), " + n + " material(s)");
      return n;
    }

    static int loadFile(Library lib, Path f, List<String> report) throws Exception {
      javax.xml.parsers.DocumentBuilderFactory dbf = javax.xml.parsers.DocumentBuilderFactory.newInstance();
      dbf.setNamespaceAware(true);
      try { dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); } catch (Exception e) { /* parser default */ }
      dbf.setXIncludeAware(false);
      dbf.setExpandEntityReferences(false);
      org.w3c.dom.Document doc = dbf.newDocumentBuilder().parse(f.toFile());
      Map<String, String[]> props = details(doc, "PropertyDetails"), params = details(doc, "ParameterDetails");
      int n = 0;
      for (org.w3c.dom.Element mat : elements(doc.getDocumentElement(), "Material")) {
        org.w3c.dom.Element bulk = first(mat, "BulkDetails");
        if (bulk == null) continue;
        String name = text(first(bulk, "Name"));
        if (name.isEmpty()) name = f.getFileName().toString().replaceFirst("(?i)\\.xml$", "");
        String key = name;
        if (lib.materials.containsKey(key)) key = name + " (" + f.getFileName() + ")";
        Map<String, Library.Prop> m = new LinkedHashMap<>();
        List<String> got = new ArrayList<>(), skipped = new ArrayList<>();
        for (org.w3c.dom.Element pd : children(bulk, "PropertyData")) {
          String[] pdet = props.getOrDefault(pd.getAttribute("property"), new String[] {pd.getAttribute("property"), ""});
          double[] temps = null;
          String tUnit = "";
          List<Object[]> series = new ArrayList<>();   // {name, unit, double[] values}
          for (org.w3c.dom.Element pv : children(pd, "ParameterValue")) {
            String[] det = params.getOrDefault(pv.getAttribute("parameter"), new String[] {pv.getAttribute("parameter"), ""});
            double[] vals = numbers(text(first(pv, "Data")));
            if (vals == null) continue;
            // ANSYS writes one flag per point: "Dependent,Dependent,Dependent"
            String vt = qualifier(pv, "Variable Type").split(",")[0].trim();
            if (det[0].toLowerCase(Locale.ROOT).contains("temperature") && !vt.equalsIgnoreCase("Dependent")) {
              temps = vals;
              tUnit = det[1];
            } else if (vt.isEmpty() || vt.equalsIgnoreCase("Dependent")) {
              series.add(new Object[] {det[0], det[1], vals});
            }
          }
          if (temps != null) {   // ANSYS writes a dummy temperature (7.9e-31) when a property does not depend on T
            boolean dummy = true;
            for (double t : temps) dummy &= Math.abs(t) < 1e-20;
            if (dummy) temps = null;
          }
          if (series.isEmpty()) {
            double[] vals = numbers(text(first(pd, "Data")));
            if (vals != null) series.add(new Object[] {pdet[0], pdet[1], vals});
          }
          if (series.isEmpty()) { skipped.add(pdet[0]); continue; }
          for (Object[] sr : series) {
            String pname = (String) sr[0], unit = (String) sr[1];
            double[] vals = (double[]) sr[2];
            String k = keyFor(pname.isEmpty() ? pdet[0] : pname, pdet[0]);
            double scale = scaleToSI(k, unit);
            Library.Prop p = new Library.Prop();
            p.owner = key;
            p.prop = k;
            p.unit = siUnitOf(k, unit);
            p.source = "MatML " + f.getFileName() + " (" + pdet[0] + ")";
            p.note = "";
            p.tUnit = "K";
            double[] tk = temps == null ? null : toKelvin(temps, tUnit);
            boolean table = tk != null && tk.length == vals.length && vals.length > 1 && tk[0] > 1;
            if (table) {
              double[][] tab = sortByT(tk, vals, scale);
              p.model = "table";
              p.tab = tab;
              p.tMin = tab[0][0];
              p.tMax = tab[0][tab[0].length - 1];
              StringBuilder ct = new StringBuilder();
              for (int i = 0; i < tab[0].length; i++) ct.append(i == 0 ? "" : ";").append(Core.g(tab[0][i])).append(':').append(Core.g(tab[1][i]));
              p.coeffText = ct.toString();
            } else {
              p.model = "const";
              p.c = new double[] {vals[0] * scale};
              p.coeffText = Core.g(p.c[0]);
              if (tk != null && tk.length == 1 && tk[0] > 1) p.note = "given at " + Core.g(tk[0]) + " K";
            }
            m.put(k, p);
            got.add(k + (table ? "(T)" : ""));
          }
        }
        if (m.isEmpty()) {
          report.add(f.getFileName() + ": material '" + name + "' has no numeric property");
          continue;
        }
        lib.materials.put(key, m);
        n++;
        report.add(f.getFileName() + ": " + key + " -> " + String.join(", ", got)
            + (skipped.isEmpty() ? "" : " ; not numeric: " + String.join(", ", skipped)));
      }
      if (n == 0) report.add(f.getFileName() + ": no MatML material found");
      return n;
    }

    /** SimKit key of a property: rho, cp, k, k_x/k_y/k_z, eps; otherwise the name itself (letters/digits/_). */
    static String keyFor(String name, String propertyName) {
      String s = (name + " " + propertyName).toLowerCase(Locale.ROOT);
      if (s.contains("density")) return "rho";
      if (s.contains("specific heat")) return "cp";
      if (s.contains("conductivity")) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.matches(".*\\bx\\b.*direction.*|.*\\bx direction.*|.*\\bk_?x\\b.*")) return "k_x";
        if (n.matches(".*\\by\\b.*direction.*|.*\\by direction.*|.*\\bk_?y\\b.*")) return "k_y";
        if (n.matches(".*\\bz\\b.*direction.*|.*\\bz direction.*|.*\\bk_?z\\b.*")) return "k_z";
        return "k";
      }
      if (s.contains("emissivity")) return "eps";
      String k = name.replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_|_$", "");
      return k.isEmpty() ? "property" : k;
    }

    static String norm(String unit) { return unit.replace(" ", "").replace("^", "").toLowerCase(Locale.ROOT); }

    /** Factor to SI for the common MatML unit spellings ("kg m^-3", "g cm^-3", "J kg^-1 C^-1", "W m^-1 C^-1"...). */
    static double scaleToSI(String key, String unit) {
      String u = norm(unit);
      switch (key) {
        case "rho": return u.startsWith("gcm-3") || u.equals("g/cm3") ? 1000 : u.startsWith("tonnemm-3") ? 1e12 : 1;
        case "cp": return u.startsWith("jg-1") || u.startsWith("j/g") ? 1000 : u.startsWith("mjtonne-1") ? 1 : 1;
        case "k": case "k_x": case "k_y": case "k_z": return u.startsWith("wmm-1") ? 1000 : 1;
        default: return 1;
      }
    }

    static String siUnitOf(String key, String unit) {
      switch (key) {
        case "rho": return "kg/m3";
        case "cp": return "J/kg.K";
        case "k": case "k_x": case "k_y": case "k_z": return "W/m.K";
        case "eps": return "-";
        default: return unit.isEmpty() ? "-" : unit;
      }
    }

    static double[] toKelvin(double[] t, String unit) {
      String u = norm(unit);
      double[] k = new double[t.length];
      for (int i = 0; i < t.length; i++) {
        k[i] = u.equals("c") || u.contains("degc") || u.contains("celsius") ? t[i] + 273.15
            : u.equals("f") || u.contains("degf") || u.contains("fahrenheit") ? (t[i] - 32) * 5 / 9 + 273.15 : t[i];
      }
      return k;
    }

    static double[][] sortByT(double[] t, double[] v, double scale) {
      Integer[] idx = new Integer[t.length];
      for (int i = 0; i < idx.length; i++) idx[i] = i;
      Arrays.sort(idx, (a, b) -> Double.compare(t[a], t[b]));
      double[][] out = new double[2][t.length];
      for (int i = 0; i < idx.length; i++) {
        out[0][i] = t[idx[i]];
        out[1][i] = v[idx[i]] * scale;
      }
      return out;
    }

    /** id -> {name, units} from Metadata/PropertyDetails or ParameterDetails. */
    static Map<String, String[]> details(org.w3c.dom.Document doc, String tag) {
      Map<String, String[]> m = new LinkedHashMap<>();
      for (org.w3c.dom.Element d : elements(doc.getDocumentElement(), tag)) {
        m.put(d.getAttribute("id"), new String[] {text(first(d, "Name")), units(d)});
      }
      return m;
    }

    /** Units of a details element: Units/@name, else "kg m^-3" built from Units/Unit[@power]/Name; Unitless -> "". */
    static String units(org.w3c.dom.Element d) {
      org.w3c.dom.Element u = first(d, "Units");
      if (u == null) return "";
      if (!u.getAttribute("name").isEmpty()) return u.getAttribute("name");
      List<String> parts = new ArrayList<>();
      for (org.w3c.dom.Element one : children(u, "Unit")) {
        String pw = one.getAttribute("power");
        parts.add(text(first(one, "Name")) + (pw.isEmpty() || pw.equals("1") ? "" : "^" + pw));
      }
      return String.join(" ", parts);
    }

    static String qualifier(org.w3c.dom.Element e, String name) {
      for (org.w3c.dom.Element q : children(e, "Qualifier")) if (name.equalsIgnoreCase(q.getAttribute("name"))) return text(q);
      return "";
    }

    /** "7850, 7840" -> numbers; null if any item is not a number (e.g. "-", "Interpolation"). */
    static double[] numbers(String s) {
      if (s == null || s.trim().isEmpty()) return null;
      String[] parts = s.trim().split("\\s*[,;]\\s*");
      double[] out = new double[parts.length];
      for (int i = 0; i < parts.length; i++) {
        try { out[i] = Double.parseDouble(parts[i].trim()); } catch (NumberFormatException e) { return null; }
      }
      return out;
    }

    static String text(org.w3c.dom.Element e) { return e == null ? "" : e.getTextContent().trim(); }

    static String local(org.w3c.dom.Node n) { return n.getLocalName() != null ? n.getLocalName() : n.getNodeName(); }

    static org.w3c.dom.Element first(org.w3c.dom.Element parent, String name) {
      if (parent == null) return null;
      for (org.w3c.dom.Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
        if (n instanceof org.w3c.dom.Element && local(n).equals(name)) return (org.w3c.dom.Element) n;
      }
      return null;
    }

    static List<org.w3c.dom.Element> children(org.w3c.dom.Element parent, String name) {
      List<org.w3c.dom.Element> out = new ArrayList<>();
      for (org.w3c.dom.Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
        if (n instanceof org.w3c.dom.Element && local(n).equals(name)) out.add((org.w3c.dom.Element) n);
      }
      return out;
    }

    /** All descendants with this local name (any namespace). */
    static List<org.w3c.dom.Element> elements(org.w3c.dom.Element root, String name) {
      List<org.w3c.dom.Element> out = new ArrayList<>();
      org.w3c.dom.NodeList l = root.getElementsByTagNameNS("*", name);
      for (int i = 0; i < l.getLength(); i++) out.add((org.w3c.dom.Element) l.item(i));
      if (out.isEmpty()) {
        l = root.getElementsByTagName(name);
        for (int i = 0; i < l.getLength(); i++) out.add((org.w3c.dom.Element) l.item(i));
      }
      return out;
    }
  }

  // =====================================================================================================
  // Helpers (same behaviour as in SimKit.java)
  // =====================================================================================================

  /** Styled-text rendering of a result: warnings first, the results table (tab stops), then the details. */
  /** Formulas written in a small part of LaTeX and drawn as mathematics, with no library (STAR-CCM+ macros cannot load
   *  one without set-up): \frac{a}{b}, \sqrt{x}, x^{2}, x_{i}, \left( ... \right) (also [ ] \{ \} | \lceil \rceil),
   *  \dot m, \text{...}, \ln \log \exp, \int, Greek letters, \cdot \times \le \ge, spaces \, \; \quad. One letter is a
   *  variable (italic), several letters a name (Re, Pr, upright). The same formula gives a plain-text form, copied with
   *  the results and written in calc_log. */
  static final class TeX {
    static final Map<String, String[]> CMD = new java.util.HashMap<>();   // name -> {symbol, plain text, kind}
    static {
      String[][] s = {{"alpha", "\u03B1"}, {"beta", "\u03B2"}, {"gamma", "\u03B3"}, {"delta", "\u03B4"}, {"varepsilon", "\u03B5", "eps"},
          {"epsilon", "\u03B5", "eps"}, {"eta", "\u03B7"}, {"theta", "\u03B8"}, {"kappa", "\u03BA"}, {"lambda", "\u03BB"},
          {"mu", "\u03BC"}, {"nu", "\u03BD"}, {"pi", "\u03C0"}, {"rho", "\u03C1"}, {"sigma", "\u03C3"}, {"tau", "\u03C4"},
          {"phi", "\u03C6"}, {"omega", "\u03C9"}, {"Gamma", "\u0393"}, {"Delta", "\u0394", "d"}, {"Phi", "\u03A6"},
          {"Omega", "\u03A9"}, {"Sigma", "\u03A3"}};
      for (String[] x : s) CMD.put(x[0], new String[] {x[1], x.length > 2 ? x[2] : x[0], Character.isLowerCase(x[0].charAt(0)) ? "var" : "ord"});
      String[][] o = {{"cdot", "\u00B7", "*", "bin"}, {"times", "\u00D7", "x", "bin"}, {"pm", "\u00B1", "+/-", "bin"},
          {"le", "\u2264", "<=", "rel"}, {"ge", "\u2265", ">=", "rel"}, {"approx", "\u2248", "~", "rel"}, {"to", "\u2192", "->", "rel"},
          {"infty", "\u221E", "inf", "ord"}, {"circ", "\u00B0", "deg", "ord"}, {"lceil", "\u2308", "ceil(", "open"},
          {"rceil", "\u2309", ")", "close"}, {"{", "{", "{", "open"}, {"}", "}", "}", "close"}, {"int", "\u222B", "int", "big"}};
      for (String[] x : o) CMD.put(x[0], new String[] {x[1], x[2], x[3]});
      for (String f : new String[] {"ln", "log", "exp", "max", "min", "sin", "cos", "sign"}) CMD.put(f, new String[] {f, f, "fn"});
    }

    static final java.awt.font.FontRenderContext FRC = new java.awt.font.FontRenderContext(null, true, true);
    static final java.awt.Color INK = new java.awt.Color(0x1D, 0x27, 0x33);

    /** A laid-out piece: width, height above and below the baseline. */
    abstract static class Box {
      double w, a, d;
      abstract void draw(java.awt.Graphics2D g, double x, double y);
    }

    static Box empty() {
      return new Box() { @Override void draw(java.awt.Graphics2D g, double x, double y) { } };
    }

    static Box text(final String t, final float size, final boolean italic) {
      final Font f = new Font(Font.SERIF, italic ? Font.ITALIC : Font.PLAIN, Math.round(size));
      final Box b = new Box() {
        @Override void draw(java.awt.Graphics2D g, double x, double y) {
          g.setFont(f);
          g.drawString(t, (float) x, (float) y);
        }
      };
      java.awt.font.TextLayout tl = new java.awt.font.TextLayout(t.isEmpty() ? " " : t, f, FRC);
      java.awt.geom.Rectangle2D r = tl.getBounds();
      b.w = t.isEmpty() ? 0 : tl.getAdvance() + (italic ? size * 0.06 : 0);
      b.a = Math.max(-r.getY(), size * 0.5);
      b.d = Math.max(r.getY() + r.getHeight(), 0);
      return b;
    }

    static Box space(double w) {
      Box b = empty();
      b.w = w;
      return b;
    }

    /** A parsed formula part: drawn (box) or written as plain text. */
    abstract static class Node {
      String kind = "ord";   // ord, var, bin, rel, open, close, punct, fn, big, space
      abstract Box box(float size, int level);
      abstract String plain();
    }

    static final class Sym extends Node {
      final String t, p;
      final boolean italic;

      Sym(String t, String p, boolean italic, String kind) {
        this.t = t;
        this.p = p;
        this.italic = italic;
        this.kind = kind;
      }

      @Override Box box(float size, int level) { return text(t, kind.equals("big") ? size * 1.5f : size, italic); }

      @Override String plain() { return kind.equals("bin") || kind.equals("rel") ? " " + p + " " : kind.equals("fn") ? p + " " : p; }
    }

    static final class Gap extends Node {
      final double em;

      Gap(double em) {
        this.em = em;
        kind = "space";
      }

      @Override Box box(float size, int level) { return space(em * size); }

      @Override String plain() { return " "; }
    }

    static final class Group extends Node {
      final List<Node> ns;

      Group(List<Node> ns) { this.ns = ns; }

      @Override Box box(float size, int level) {
        final List<Box> bs = new ArrayList<>();
        final List<Double> xs = new ArrayList<>();
        double x = 0, a = 0, d = 0, m = level == 0 ? 1 : 0.65;
        String prev = "start";
        for (int i = 0; i < ns.size(); i++) {
          Node n = ns.get(i);
          String k = n.kind;
          if (k.equals("soft")) {   // a typed space: a thin space between two plain parts only
            String next = i + 1 < ns.size() ? ns.get(i + 1).kind : "end";
            if (!(prev.equals("ord") || prev.equals("var") || prev.equals("close") || prev.equals("fn"))
                || !(next.equals("ord") || next.equals("var") || next.equals("open") || next.equals("fn") || next.equals("big"))) continue;
            x += size * 0.17;
            continue;
          }
          boolean unary = k.equals("bin") && (prev.equals("start") || prev.equals("bin") || prev.equals("rel") || prev.equals("open") || prev.equals("punct"));
          if (k.equals("bin") && !unary) x += size * 0.22 * m;
          if (k.equals("rel") && !prev.equals("open")) x += size * 0.3 * m;
          Box b = n.box(size, level);
          bs.add(b);
          xs.add(x);
          x += b.w;
          a = Math.max(a, b.a);
          d = Math.max(d, b.d);
          if (k.equals("bin") && !unary) x += size * 0.22 * m;
          if (k.equals("rel")) x += size * 0.3 * m;
          if (k.equals("punct")) x += size * 0.17;
          if (k.equals("fn") && i + 1 < ns.size() && !ns.get(i + 1).kind.equals("open")) x += size * 0.12;
          prev = unary ? "ord" : k.equals("fn") ? "ord" : k;
        }
        Box g = new Box() {
          @Override void draw(java.awt.Graphics2D gr, double x0, double y) {
            for (int i = 0; i < bs.size(); i++) bs.get(i).draw(gr, x0 + xs.get(i), y);
          }
        };
        g.w = x;
        g.a = a;
        g.d = d;
        return g;
      }

      @Override String plain() {
        StringBuilder sb = new StringBuilder();
        String prev = "start";
        for (Node n : ns) {
          boolean unary = n.kind.equals("bin") && (prev.equals("start") || prev.equals("bin") || prev.equals("rel") || prev.equals("open") || prev.equals("punct"));
          sb.append(n.kind.equals("soft") ? " " : unary ? n.plain().trim() : n.plain());
          if (!n.kind.equals("soft")) prev = unary ? "ord" : n.kind;
        }
        return sb.toString();
      }
    }

    static String wrap(String p) {
      p = p.trim();
      return p.length() <= 1 || p.matches("-?[\\w.\u0080-\uFFFF]+") || p.matches("\\w*\\(.*\\)") && depthOk(p) ? p : "(" + p + ")";
    }

    static boolean depthOk(String p) {   // "(a)(b)" is not one bracket
      int d = 0;
      for (int i = 0; i < p.length(); i++) {
        if (p.charAt(i) == '(') d++;
        if (p.charAt(i) == ')' && --d == 0 && i < p.length() - 1) return false;
      }
      return true;
    }

    static float smaller(float size, int level) { return Math.max(9f, size * (level == 0 ? 0.88f : 0.8f)); }

    static final class Frac extends Node {
      final Node n, dn;

      Frac(Node n, Node dn) {
        this.n = n;
        this.dn = dn;
      }

      @Override Box box(float size, int level) {
        float s = smaller(size, level);
        final Box nb = n.box(s, level + 1), db = dn.box(s, level + 1);
        final double t = Math.max(1, size * 0.055), axis = size * 0.28, gap = size * 0.14, pad = size * 0.12;
        Box b = new Box() {
          @Override void draw(java.awt.Graphics2D g, double x, double y) {
            nb.draw(g, x + (w - nb.w) / 2, y - axis - t / 2 - gap - nb.d);
            db.draw(g, x + (w - db.w) / 2, y - axis + t / 2 + gap + db.a);
            g.fill(new java.awt.geom.Rectangle2D.Double(x + pad / 2, y - axis - t / 2, w - pad, t));
          }
        };
        b.w = Math.max(nb.w, db.w) + 2 * pad;
        b.a = axis + t / 2 + gap + nb.d + nb.a;
        b.d = Math.max(0, -axis + t / 2 + gap + db.a + db.d);
        return b;
      }

      @Override String plain() { return wrap(n.plain()) + "/" + wrap(dn.plain()); }
    }

    static final class Sqrt extends Node {
      final Node in;

      Sqrt(Node in) { this.in = in; }

      @Override Box box(float size, int level) {
        final Box ib = in.box(size, level);
        final double t = Math.max(1, size * 0.055), gap = size * 0.12, rw = size * 0.6;
        Box b = new Box() {
          @Override void draw(java.awt.Graphics2D g, double x, double y) {
            double top = y - a + t / 2, bottom = y + d - t;
            java.awt.geom.Path2D.Double p = new java.awt.geom.Path2D.Double();
            p.moveTo(x + rw * 0.05, y - (a - d) * 0.35);
            p.lineTo(x + rw * 0.25, y - (a - d) * 0.42);
            p.lineTo(x + rw * 0.55, bottom);
            p.lineTo(x + rw * 0.95, top);
            p.lineTo(x + w, top);
            java.awt.Stroke old = g.getStroke();
            g.setStroke(new java.awt.BasicStroke((float) t, java.awt.BasicStroke.CAP_BUTT, java.awt.BasicStroke.JOIN_MITER));
            g.draw(p);
            g.setStroke(old);
            ib.draw(g, x + rw + size * 0.05, y);
          }
        };
        b.w = rw + ib.w + size * 0.12;
        b.a = ib.a + gap + t;
        b.d = Math.max(ib.d, size * 0.12) + t;
        return b;
      }

      @Override String plain() { return "sqrt(" + in.plain().trim() + ")"; }
    }

    static final class Scr extends Node {
      final Node base;
      Node sup, sub;

      Scr(Node base) {
        this.base = base;
        kind = base.kind.equals("big") ? "big" : base.kind.equals("close") ? "close" : "ord";
      }

      @Override Box box(float size, int level) {
        float s = Math.max(8f, size * 0.7f);
        final Box bb = base.box(size, level);
        final Box pb = sup == null ? null : sup.box(s, level + 1), sb = sub == null ? null : sub.box(s, level + 1);
        boolean ital = base instanceof Sym && ((Sym) base).italic;
        final double u = pb == null ? 0 : Math.max(size * 0.38, bb.a - pb.a * 0.55);
        double v0 = sb == null ? 0 : Math.max(size * 0.2, sb.a - size * 0.28);
        if (pb != null && sb != null) v0 = Math.max(v0, sb.a - u + pb.d + size * 0.1);   // the two scripts apart
        final double v = v0, kern = ital ? size * 0.06 : 0;
        Box b = new Box() {
          @Override void draw(java.awt.Graphics2D g, double x, double y) {
            bb.draw(g, x, y);
            if (pb != null) pb.draw(g, x + bb.w + kern, y - u);
            if (sb != null) sb.draw(g, x + bb.w - (ital ? size * 0.04 : 0), y + v);
          }
        };
        b.w = bb.w + Math.max(pb == null ? 0 : pb.w + kern, sb == null ? 0 : sb.w) + size * 0.04;
        b.a = Math.max(bb.a, pb == null ? 0 : u + pb.a);
        b.d = Math.max(bb.d, sb == null ? 0 : v + sb.d);
        return b;
      }

      @Override String plain() {
        String s = base.plain().trim();
        if (sub != null) s += "_" + wrap(sub.plain());
        if (sup != null) s += "^" + wrap(sup.plain());
        return s;
      }
    }

    static final class Delim extends Node {
      final String open, close;
      final Node in;

      Delim(String open, String close, Node in) {
        this.open = open;
        this.close = close;
        this.in = in;
      }

      static String plainOf(String dl) { return dl.equals(".") ? "" : dl.equals("\u2308") ? "ceil(" : dl.equals("\u2309") ? ")" : dl; }

      @Override Box box(float size, int level) {
        final Box ib = in.box(size, level);
        final double h = ib.a + ib.d;
        if (h <= size * 1.05) {   // small: the ordinary characters
          List<Node> ns = new ArrayList<>();
          ns.add(new Sym(open.equals(".") ? "" : open, "", false, "open"));
          ns.add(in);
          ns.add(new Sym(close.equals(".") ? "" : close, "", false, "close"));
          return new Group(ns).box(size, level);
        }
        final double ext = size * 0.12, top0 = ib.a + ext, bot0 = ib.d + ext, t = Math.max(1, size * 0.06);
        final double ow = width(open, h, size), cw = width(close, h, size);
        Box b = new Box() {
          @Override void draw(java.awt.Graphics2D g, double x, double y) {
            java.awt.Stroke old = g.getStroke();
            g.setStroke(new java.awt.BasicStroke((float) t, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
            shape(g, open, x, ow, y - top0, y + bot0, false);
            ib.draw(g, x + ow, y);
            shape(g, close, x + ow + ib.w, cw, y - top0, y + bot0, true);
            g.setStroke(old);
          }
        };
        b.w = ow + ib.w + cw;
        b.a = top0;
        b.d = bot0;
        return b;
      }

      static double width(String dl, double h, float size) {
        if (dl.equals(".")) return 0;
        if (dl.equals("|")) return size * 0.3;
        if (dl.equals("(") || dl.equals(")")) return size * 0.32 + h * 0.06;
        return size * 0.38;
      }

      static void shape(java.awt.Graphics2D g, String dl, double x, double w, double top, double bot, boolean right) {
        double l = right ? x + w * 0.85 : x + w * 0.15, r = right ? x + w * 0.15 : x + w * 0.85, mid = (top + bot) / 2, h = bot - top;
        java.awt.geom.Path2D.Double p = new java.awt.geom.Path2D.Double();
        switch (dl) {
          case "(": case ")":
            p.moveTo(r, top);
            p.curveTo(l, top + h * 0.2, l, bot - h * 0.2, r, bot);
            break;
          case "[": case "]":
            p.moveTo(r, top); p.lineTo(l, top); p.lineTo(l, bot); p.lineTo(r, bot);
            break;
          case "\u2308": case "\u2309":
            p.moveTo(r, top); p.lineTo(l, top); p.lineTo(l, bot);
            break;
          case "{": case "}":
            double m = (l + r) / 2;
            p.moveTo(r, top);
            p.quadTo(m, top, m, top + h * 0.12);
            p.lineTo(m, mid - h * 0.1);
            p.quadTo(m, mid, l, mid);
            p.quadTo(m, mid, m, mid + h * 0.1);
            p.lineTo(m, bot - h * 0.12);
            p.quadTo(m, bot, r, bot);
            break;
          case "|":
            p.moveTo(x + w / 2, top); p.lineTo(x + w / 2, bot);
            break;
          default:
            return;
        }
        g.draw(p);
      }

      @Override String plain() { return plainOf(open) + in.plain().trim() + plainOf(close); }
    }

    static final class Dot extends Node {
      final Node base;

      Dot(Node base) { this.base = base; }

      @Override Box box(float size, int level) {
        final Box bb = base.box(size, level);
        final double r = Math.max(1.1, size * 0.065);
        Box b = new Box() {
          @Override void draw(java.awt.Graphics2D g, double x, double y) {
            bb.draw(g, x, y);
            g.fill(new java.awt.geom.Ellipse2D.Double(x + bb.w * 0.58 - r, y - bb.a - size * 0.16 - r, 2 * r, 2 * r));
          }
        };
        b.w = bb.w;
        b.a = bb.a + size * 0.2;
        b.d = bb.d;
        return b;
      }

      @Override String plain() { return base.plain().trim() + "dot"; }
    }

    static final class Txt extends Node {
      final String t;

      Txt(String t) { this.t = t; }

      @Override Box box(float size, int level) { return text(t, size, false); }

      @Override String plain() { return t; }
    }

    /** The reader of the formula text. */
    static final class P {
      final String s;
      int k;

      P(String s) { this.s = s; }

      Group list(boolean brace, boolean left) {
        List<Node> out = new ArrayList<>();
        while (k < s.length()) {
          char c = s.charAt(k);
          if (c == '}' && brace) { k++; break; }
          if (left && s.startsWith("\\right", k)) break;
          if (c == '^' || c == '_') {
            k++;
            Node arg = arg();
            Node last = out.isEmpty() ? new Txt("") : out.remove(out.size() - 1);
            Scr sc = last instanceof Scr && (c == '^' ? ((Scr) last).sup : ((Scr) last).sub) == null ? (Scr) last : new Scr(last);
            if (c == '^') sc.sup = arg; else sc.sub = arg;
            out.add(sc);
            continue;
          }
          out.add(atom(false));
        }
        return new Group(out);
      }

      Node arg() {
        while (k < s.length() && s.charAt(k) == ' ') k++;
        if (k < s.length() && s.charAt(k) == '{') {
          k++;
          return list(true, false);
        }
        return atom(true);
      }

      Node atom(boolean single) {
        char c = s.charAt(k);
        if (c == ' ') {
          while (k < s.length() && s.charAt(k) == ' ') k++;
          Gap g = new Gap(0);
          g.kind = "soft";
          return g;
        }
        if (c == '{') {
          k++;
          return list(true, false);
        }
        if (c == '\\') return command();
        if (Character.isLetter(c)) {
          int e = k + 1;
          if (!single) while (e < s.length() && Character.isLetter(s.charAt(e))) e++;
          String w = s.substring(k, e);
          k = e;
          return new Sym(w, w, w.length() == 1, w.length() == 1 ? "var" : "ord");
        }
        if (Character.isDigit(c) || c == '.' && k + 1 < s.length() && Character.isDigit(s.charAt(k + 1))) {
          int e = k + 1;
          if (!single) while (e < s.length() && (Character.isDigit(s.charAt(e)) || s.charAt(e) == '.')) e++;
          String w = s.substring(k, e);
          k = e;
          return new Sym(w, w, false, "ord");
        }
        k++;
        switch (c) {
          case '+': return new Sym("+", "+", false, "bin");
          case '-': return new Sym("\u2212", "-", false, "bin");
          case '=': case '<': case '>': return new Sym(String.valueOf(c), String.valueOf(c), false, "rel");
          case ',': case ';': case ':': return new Sym(String.valueOf(c), String.valueOf(c), false, "punct");
          case '(': case '[': return new Sym(String.valueOf(c), String.valueOf(c), false, "open");
          case ')': case ']': return new Sym(String.valueOf(c), String.valueOf(c), false, "close");
          default: return new Sym(String.valueOf(c), String.valueOf(c), false, "ord");
        }
      }

      String name() {
        k++;   // the backslash
        int e = k;
        while (e < s.length() && Character.isLetter(s.charAt(e))) e++;
        if (e == k) e = Math.min(s.length(), k + 1);
        String n = s.substring(k, e);
        k = e;
        return n;
      }

      String raw() {   // {text} as it is
        while (k < s.length() && s.charAt(k) == ' ') k++;
        if (k >= s.length() || s.charAt(k) != '{') return "";
        int e = s.indexOf('}', k);
        String t = s.substring(k + 1, e);
        k = e + 1;
        return t;
      }

      String delim() {
        while (k < s.length() && s.charAt(k) == ' ') k++;
        if (s.charAt(k) == '\\') {
          String n = name();
          return n.equals("lceil") ? "\u2308" : n.equals("rceil") ? "\u2309" : n;
        }
        return String.valueOf(s.charAt(k++));
      }

      Node command() {
        String n = name();
        switch (n) {
          case "frac": { Node a = arg(); return new Frac(a, arg()); }
          case "sqrt": return new Sqrt(arg());
          case "dot": return new Dot(arg());
          case "text": case "mathrm": return new Txt(raw());
          case "left": {
            String o = delim();
            Node in = list(false, true);
            if (s.startsWith("\\right", k)) k += 6;
            return new Delim(o, delim(), in);
          }
          case ",": return new Gap(0.17);
          case ";": return new Gap(0.28);
          case " ": return new Gap(0.25);
          case "quad": return new Gap(1.0);
          default:
            String[] c = CMD.get(n);
            if (c == null) return new Txt(n);
            return new Sym(c[0], c[1], c[2].equals("var"), c[2]);
        }
      }
    }

    static Node parse(String tex) { return new P(tex).list(false, false); }

    /** The formula as plain text (a / b, sqrt(x), x^2, rho, eps ...). */
    static String plain(String tex) {
      List<String> eqs = new ArrayList<>();
      for (String e : tex.split(" ; ")) eqs.add(parse(e).plain().replaceAll(" +", " ").replace("( ", "(").replace(" )", ")").trim());
      return String.join(" ; ", eqs);
    }

    /** One equation drawn, as an icon (for a styled text). */
    static javax.swing.Icon icon(String tex, final float size) {
      final Box b = parse(tex).box(size, 0);
      final int pad = 3;
      return new javax.swing.Icon() {
        @Override public int getIconWidth() { return (int) Math.ceil(b.w) + 2 * pad; }
        @Override public int getIconHeight() { return (int) Math.ceil(b.a + b.d) + 2 * pad; }
        @Override public void paintIcon(java.awt.Component c, java.awt.Graphics g0, int x, int y) {
          java.awt.Graphics2D g = (java.awt.Graphics2D) g0.create();
          g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
          g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
          g.setRenderingHint(java.awt.RenderingHints.KEY_FRACTIONALMETRICS, java.awt.RenderingHints.VALUE_FRACTIONALMETRICS_ON);
          g.setColor(INK);
          b.draw(g, x + pad, y + pad + b.a);
          g.dispose();
        }
      };
    }
  }

  static final class Styled {
    static final java.awt.Color GREY = new java.awt.Color(0x77, 0x77, 0x77), AMBER = new java.awt.Color(0xFF, 0xF4, 0xD6),
        HEAD = new java.awt.Color(0xE8, 0xED, 0xF3), ZEBRA = new java.awt.Color(0xF6, 0xF8, 0xFA);

    static javax.swing.text.SimpleAttributeSet style(int size, boolean bold, java.awt.Color fg, java.awt.Color bg) {
      javax.swing.text.SimpleAttributeSet a = new javax.swing.text.SimpleAttributeSet();
      javax.swing.text.StyleConstants.setFontFamily(a, Font.SANS_SERIF);
      javax.swing.text.StyleConstants.setFontSize(a, size);
      javax.swing.text.StyleConstants.setBold(a, bold);
      if (fg != null) javax.swing.text.StyleConstants.setForeground(a, fg);
      if (bg != null) javax.swing.text.StyleConstants.setBackground(a, bg);
      return a;
    }

    static void add(javax.swing.text.StyledDocument d, String text, javax.swing.text.AttributeSet a) {
      try { d.insertString(d.getLength(), text, a); } catch (javax.swing.text.BadLocationException e) { /* never */ }
    }

    /** Paragraph attributes for the results table: name | value (right-aligned) | unit | meaning. */
    static void tabs(javax.swing.text.StyledDocument d, int start, java.awt.Color bg) {
      javax.swing.text.SimpleAttributeSet p = new javax.swing.text.SimpleAttributeSet();
      javax.swing.text.StyleConstants.setTabSet(p, new javax.swing.text.TabSet(new javax.swing.text.TabStop[] {
          new javax.swing.text.TabStop(240, javax.swing.text.TabStop.ALIGN_RIGHT, javax.swing.text.TabStop.LEAD_NONE),
          new javax.swing.text.TabStop(255), new javax.swing.text.TabStop(340)}));
      javax.swing.text.StyleConstants.setSpaceAbove(p, 2);
      javax.swing.text.StyleConstants.setSpaceBelow(p, 2);
      d.setParagraphAttributes(start, Math.max(1, d.getLength() - start), p, false);
    }

    static javax.swing.text.StyledDocument result(String head, String inputs, Core.R r, String logNote) {
      javax.swing.text.StyledDocument d = new javax.swing.text.DefaultStyledDocument();
      add(d, r.title + "\n", style(15, true, java.awt.Color.BLACK, null));
      add(d, head + "\n\n", style(10, false, GREY, null));
      for (String w : r.warns) add(d, " \u26A0 " + w + " \n", style(12, false, new java.awt.Color(0x6B, 0x4E, 0x00), AMBER));
      if (!r.warns.isEmpty()) add(d, "\n", style(6, false, null, null));
      int start = d.getLength();
      add(d, "result\tvalue\tunit\tmeaning\n", style(12, true, java.awt.Color.DARK_GRAY, HEAD));
      int i = 0;
      for (Core.Item it : r.items) {
        java.awt.Color bg = i++ % 2 == 1 ? ZEBRA : null;
        add(d, it.key + "\t", style(12, false, java.awt.Color.BLACK, bg));
        add(d, Core.g(it.v), style(12, true, java.awt.Color.BLACK, bg));
        add(d, "\t" + showUnit(it.unit) + "\t", style(12, false, java.awt.Color.BLACK, bg));
        add(d, it.desc + "\n", style(12, false, new java.awt.Color(0x44, 0x44, 0x44), bg));
      }
      tabs(d, start, null);
      add(d, "\n", style(6, false, null, null));
      if (!r.tex.isEmpty()) {
        add(d, "Formula\n", style(11, true, GREY, null));
        int f0 = d.getLength();
        for (String t : r.tex) {
          for (String eq : t.split(" ; ")) {
            javax.swing.text.SimpleAttributeSet ic = new javax.swing.text.SimpleAttributeSet();
            try {
              javax.swing.text.StyleConstants.setIcon(ic, TeX.icon(eq, 17f));
              add(d, " ", ic);
            } catch (Throwable x) {   // never lose the result for a drawing problem
              add(d, TeX.plain(eq), style(12, false, java.awt.Color.DARK_GRAY, null));
            }
            add(d, "        ", style(12, false, null, null));   // equations side by side, wrapped like words
          }
        }
        add(d, "\n", style(12, false, null, null));
        javax.swing.text.SimpleAttributeSet ind = new javax.swing.text.SimpleAttributeSet();
        javax.swing.text.StyleConstants.setLeftIndent(ind, 14);
        javax.swing.text.StyleConstants.setSpaceBelow(ind, 3);
        d.setParagraphAttributes(f0, d.getLength() - f0, ind, false);
        add(d, "\n", style(4, false, null, null));
      }
      for (String l : r.lines) {
        if (!r.tex.isEmpty() && l.startsWith("Formula:")) continue;   // drawn above
        int c = l.indexOf(':');
        if (!l.isEmpty() && c > 0 && c < 10) {
          add(d, l.substring(0, c + 1), style(11, true, GREY, null));
          add(d, l.substring(c + 1) + "\n", style(11, false, GREY, null));
        } else {
          add(d, l + "\n", style(11, false, GREY, null));
        }
      }
      add(d, "\nInputs: " + inputs + "\n" + logNote + "\n", style(10, false, new java.awt.Color(0x99, 0x99, 0x99), null));
      return d;
    }
  }

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
    static Path kit() { return ROOT; }
    /** A file or folder of the kit, relative to the SimKit root with '/' (same text on Windows and Linux). */
    static Path inKit(String rel) {
      Path p = kit();
      for (String part : rel.split("/")) if (!part.isEmpty()) p = p.resolve(part);
      return p;
    }

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
