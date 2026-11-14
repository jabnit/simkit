// Engineering calculators: inlet and outlet, orifice, turbulence inlet, y+ and prism layers, time step, averaging window, dimensionless numbers, pipe pressure drop, GCI, convection, thermocouple, stoichiometry, units
//
// SimKitCalc.java -- SimKit 0.2 (2026-11-11) -- UNTESTED IN STAR-CCM+ (written for Simcenter STAR-CCM+ 2402 / 19.02).
// Spec: SIMKIT_SPEC_v02.md section B. See _SIMKIT\TESTING.txt.
//
// Purpose
//   One window of engineering calculators used while setting up and post-processing CFD cases. Sidebar groups:
//   Set-up, Mesh, Time, Flow, Verification, Heat transfer, Fire test, Combustion, Units.
//   Each page: Inputs (left) and Outputs (right). Outputs are computed again on every change of an input; the main
//   output is shown first, the formula, source and validity under "Formula and source". Inputs outside a validity
//   range give a warning (property data are clamped to [T_min, T_max] of the library row).
//   Buttons: From parameter... / From report... (fill an input from the open sim), To parameter... (create/update a
//   global parameter p_<name> from an output), Copy value for STAR-CCM+..., Copy all results, Close.
//   The self-check of the reference examples runs in batch (starccm+ -batch SimKitCalc.java).
//   Traceability: "Log this" next to the result appends a row (timestamp, user, page, inputs, outputs) to
//   calc_log.csv of the project and prints it to the Output window. Without a project nothing is written to disk.
//
// Version 0.2 (2026-11-11). The physics (class Core) and the data loading (class Library) have NO STAR-CCM+
// imports and were unit-tested with a plain JDK 11 compiler; the STAR-CCM+ calls are UNTESTED in STAR.
//
// Data (read at start): <root>/lib/generic/data/fluids.csv. <root> = the SimKit root, found from this
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
  boolean filling;                 // true while SimKit writes a field (no new computation for that)
  boolean detailsOpen;             // "Formula and source" open (remembered)
  String lastLogged = "";
  final List<JComboBox<String>> mixedFluidCombos = new ArrayList<>();   // ENTERED + fluids
  final List<JComboBox<String>> fluidCombos = new ArrayList<>();        // fluids only

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

  /** One calculation page (sidebar entry): its forms, the Outputs view and the last result. */
  final class Tab {
    final String name, group, help;
    final boolean more;
    final List<Form> forms = new ArrayList<>();
    /** Plain-text result (what "All results" copies); a message written here replaces the Outputs view. */
    final JTextArea out = new JTextArea();
    final javax.swing.JTextPane view = new javax.swing.JTextPane();
    final JLabel status = new JLabel(" ");
    final JButton copyBtn = Styled.link("Copy"), logBtn = Styled.link("Log this"), moreBtn = Styled.link("");
    Core.R last;
    String lastForm = "", lastInputs = "", error;
    boolean styledSet;   // true while showResult() writes 'out'

    Tab(String group, String name, String help) {
      this.name = name;
      this.group = group;
      this.help = help;
      this.more = Arrays.asList(MORE_GROUPS).contains(group);
      out.setEditable(false);
      view.setEditable(false);
      view.setOpaque(true);                          // STAR's look-and-feel makes it transparent otherwise
      view.setBackground(java.awt.Color.WHITE);
      view.setMargin(new Insets(10, 14, 10, 14));
      status.setForeground(Styled.MUTED);
      status.setBorder(BorderFactory.createEmptyBorder(4, 2, 0, 2));
      copyBtn.setToolTipText("Copies the value as STAR-CCM+ fields accept it");
      copyBtn.addActionListener(e -> copyHead());
      logBtn.setToolTipText("Adds the inputs and outputs of this page to calc_log.csv of the project (one row per click)");
      logBtn.addActionListener(e -> logThis());
      moreBtn.addActionListener(e -> {
        detailsOpen = !detailsOpen;
        try { java.util.prefs.Preferences.userRoot().node("simkit").putBoolean("calcDetails", detailsOpen); } catch (Throwable x) { /* not kept */ }
        for (Tab t : tabs) t.render();
      });
      out.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
        void sync() { if (!styledSet) javax.swing.SwingUtilities.invokeLater(() -> message(out.getText())); }
        @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { sync(); }
        @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { sync(); }
        @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { sync(); }
      });
    }

    /** A plain message (library folders read, errors) in place of the outputs. */
    void message(String text) {
      if (styledSet) return;
      javax.swing.text.StyledDocument d = new javax.swing.text.DefaultStyledDocument();
      Styled.add(d, "Outputs\n\n", Styled.style(12, true, Styled.MUTED, null));
      Styled.add(d, text, Styled.style(13, false, Styled.INK, null));
      view.setStyledDocument(d);
      view.setCaretPosition(0);
    }

    /** One line under the outputs (copied value, parameter written). */
    void note(String s) { status.setText(s); }

    void showResult(String inputs, Core.R r) {
      last = r;
      lastInputs = inputs;
      error = null;
      styledSet = true;
      out.setText(name + "  [" + Fmt.iso() + "]\nInputs: " + inputs + "\n\n" + r.text());
      styledSet = false;
      render();
    }

    void showError(String msg) {
      error = msg;
      render();
    }

    /** Outputs: main value, other outputs, warnings, then formula and source (folded). */
    void render() {
      javax.swing.text.StyledDocument d = new javax.swing.text.DefaultStyledDocument();
      Styled.add(d, "Outputs\n", Styled.style(12, true, Styled.MUTED, null));
      Core.R r = last;
      if (error != null) {
        Styled.add(d, "\n" + error + "\n", Styled.style(13, false, Styled.RED, null));
        view.setStyledDocument(d);
        return;
      }
      if (r == null || r.items.isEmpty()) {
        Styled.add(d, "\n-\n", Styled.style(13, false, Styled.MUTED, null));
        view.setStyledDocument(d);
        return;
      }
      Core.Item h = r.headline();
      Styled.add(d, "\n" + h.desc + "\n", Styled.style(12, false, Styled.MUTED, null));
      Styled.add(d, Core.g(h.v), Styled.style(26, true, Styled.INK, null));
      Styled.add(d, "  " + showUnit(h.unit) + "     ", Styled.style(15, false, Styled.MUTED, null));
      Styled.component(d, copyBtn);
      Styled.add(d, "   ", Styled.style(12, false, Styled.MUTED, null));
      Styled.component(d, logBtn);
      Styled.add(d, "\n\n", Styled.style(6, false, null, null));
      if (r.showFormula && !r.tex.isEmpty()) {
        Styled.formulas(d, r);
        Styled.add(d, "\n", Styled.style(6, false, null, null));
      }
      for (String w : r.warns) Styled.add(d, " " + w + " \n", Styled.style(12, false, new java.awt.Color(0x6B, 0x4E, 0x00), Styled.AMBER));
      if (!r.warns.isEmpty()) Styled.add(d, "\n", Styled.style(6, false, null, null));
      int start = d.getLength();
      for (Core.Item it : r.items) {
        if (it == h) continue;
        Styled.add(d, it.desc + "\t", Styled.style(13, false, Styled.INK, null));
        Styled.add(d, Core.g(it.v), Styled.style(13, true, Styled.INK, null));
        Styled.add(d, "\t" + showUnit(it.unit) + "\n", Styled.style(13, false, Styled.MUTED, null));
      }
      Styled.rows(d, start);
      Styled.add(d, "\n", Styled.style(8, false, null, null));
      moreBtn.setText((detailsOpen ? "\u25BE  " : "\u25B8  ") + "Formula and source");
      Styled.component(d, moreBtn);
      Styled.add(d, "\n", Styled.style(12, false, null, null));
      if (detailsOpen) Styled.details(d, r, lastInputs, caseNote);
      view.setStyledDocument(d);
      view.setCaretPosition(0);
    }

    /** One row in calc_log.csv: this page, the inputs and outputs shown now. The same values twice: one row. */
    void logThis() {
      if (last == null || last.items.isEmpty()) return;
      String key = name + "|" + lastInputs + "|" + last.outputs();
      if (key.equals(lastLogged)) {
        note("Already logged (same inputs and outputs).");
        return;
      }
      lastLogged = key;
      note(calcLog(name, lastInputs, last.outputs()));
    }

    void copyHead() {
      if (last == null || last.items.isEmpty()) return;
      Core.Item h = last.headline();
      String v = starNumber(h.v);
      try {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(v), null);
        note("Copied " + v + "  (STAR-CCM+ unit: " + starUnit(h.unit) + ")");
      } catch (Throwable e) {
        note("Copy failed: " + reason(e));
      }
    }
  }

  /** One labelled input field. 'scale' converts the field unit to SI (NaN = not an SI-convertible unit). */
  static final class Field {
    String key, label, name, sym, unit, hint;
    boolean opt, libOnly;   // optional input; used only with a library fluid (temperature, pressure)
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

  /** A set of inputs and its calculation; computed again on every change. */
  final class Form {
    final Tab tab;
    final String title;
    final Map<String, Field> fields = new LinkedHashMap<>();
    final Map<String, JComboBox<String>> combos = new LinkedHashMap<>();
    final List<JComponent> extras = new ArrayList<>();
    final List<String> order = new ArrayList<>();
    final List<String> shownOrder = new ArrayList<>();             // order on screen (optional inputs last)
    final Map<String, List<JComponent>> rows = new LinkedHashMap<>();
    Function<Form, Core.R> calc;
    /** Keys not used with the current choices (hidden). Null: all shown. */
    Function<Form, Set<String>> hidden;
    Field lastField;
    javax.swing.Timer timer;

    Form(Tab tab, String title) {
      this.tab = tab;
      this.title = title;
      tab.forms.add(this);
    }

    /** Required input: name, symbol (small TeX), unit, default value. */
    Form in(String key, String name, String sym, String unit, String def) { return add(key, name, sym, unit, def, "", false); }

    /** Optional input, listed under "Optional"; 'hint' is shown in the empty field. */
    Form opt(String key, String name, String sym, String unit, String def, String hint) { return add(key, name, sym, unit, def, hint, true); }

    /** The last input is used only with a library fluid (temperature or pressure of the property evaluation). */
    Form lib() {
      lastField.libOnly = true;
      return this;
    }

    Form section(String title) {
      order.add("s:" + title);
      return this;
    }

    Form add(String key, String name, String sym, String unit, String def, String hint, boolean opt) {
      Field f = new Field();
      f.key = key;
      f.name = name;
      f.sym = sym;
      f.unit = unit;
      f.hint = hint;
      f.opt = opt;
      f.label = name + (sym.isEmpty() ? "" : " " + TeX.plain(sym));
      f.scale = scaleOf(unit);
      HintField hf = new HintField(def, 10);
      hf.hint = hint;
      f.tf = hf;
      fields.put(key, f);
      order.add("f:" + key);
      lastField = f;
      return this;
    }

    JComboBox<String> c(String key, String label, String... options) {
      JComboBox<String> cb = new JComboBox<>(options);
      cb.setName(label);
      combos.put(key, cb);
      order.add("c:" + key);
      return cb;
    }

    Set<String> hiddenKeys() {
      return hidden == null ? java.util.Collections.<String>emptySet() : hidden.apply(this);
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
        throw new IllegalArgumentException(f.label + ": '" + s + "' is not a number (decimal separator: dot)");
      }
      return Double.isNaN(f.scale) ? x : x * f.scale;
    }

    double req(String key) {
      double x = v(key);
      if (Double.isNaN(x)) throw new IllegalArgumentException(field(key).label + ": required");
      return x;
    }

    int reqInt(String key) {
      double x = req(key);
      if (x != Math.rint(x)) throw new IllegalArgumentException(field(key).label + ": integer required");
      return (int) x;
    }

    String s(String key) {
      Object o = combos.get(key).getSelectedItem();
      return o == null ? "" : o.toString();
    }

    int idx(String key) { return combos.get(key).getSelectedIndex(); }

    /** Writes an SI value into a field (property taken from the library); no new computation for that. */
    void put(String key, double si) {
      Field f = fields.get(key);
      if (f == null) return;
      String t = Double.isNaN(si) ? "" : Fmt.num(Double.isNaN(f.scale) ? si : si / f.scale);
      if (t.equals(f.tf.getText())) return;
      filling = true;
      try { f.tf.setText(t); } finally { filling = false; }
    }

    /** key=value unit; ... for calc_log (inputs used with the current choices). */
    String inputs() {
      Set<String> hide = hiddenKeys();
      List<String> parts = new ArrayList<>();
      for (String o : order) {
        String k = o.substring(2);
        if (o.startsWith("s:") || hide.contains(k)) continue;
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

  static Set<String> keys(String... k) { return new HashSet<>(Arrays.asList(k)); }

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

  /** Sidebar groups (MORE_GROUPS: under a "More" row, none at present). */
  static final String[] MAIN_GROUPS = {"Set-up", "Mesh", "Time", "Flow", "Verification", "Heat transfer", "Fire test", "Combustion", "Units"};
  static final String[] MORE_GROUPS = {};
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
    try { detailsOpen = java.util.prefs.Preferences.userRoot().node("simkit").getBoolean("calcDetails", false); } catch (Throwable x) { /* closed */ }
    for (Tab t : tabs) {
      for (Form fm : t.forms) {
        fluidFields(fm);
        compute(fm);
      }
    }

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
    right.add(button("Close", e -> dlg.dispose()));
    JPanel south = new JPanel(new BorderLayout());
    south.add(left, BorderLayout.WEST);
    south.add(right, BorderLayout.EAST);
    root.add(south, BorderLayout.SOUTH);
    dlg.setContentPane(root);
    dlg.setSize(1300, 820);
    dlg.setLocationRelativeTo(starFrame());
    dlg.getRootPane().putClientProperty("simkit.window", Boolean.TRUE);

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
          if (q.isEmpty() && MORE_GROUPS.length > 0) sideModel.addElement(MORE_ROW);
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

  /** A page: title and one line of use, Inputs (left) and Outputs (right). */
  JComponent layoutPage(Tab t) {
    JPanel head = new JPanel(new java.awt.GridLayout(0, 1, 0, 2));
    head.setBorder(BorderFactory.createEmptyBorder(2, 2, 10, 2));
    JLabel title = new JLabel(t.name);
    title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));
    head.add(title);
    if (!t.help.isEmpty()) {
      JLabel help = new JLabel(t.help);
      help.setFont(help.getFont().deriveFont(Font.PLAIN, 12f));
      help.setForeground(Styled.MUTED);
      head.add(help);
    }
    JPanel forms = new JPanel();
    forms.setOpaque(false);
    forms.setLayout(new BoxLayout(forms, BoxLayout.Y_AXIS));
    for (Form fm : t.forms) {
      if (t.forms.size() > 1) forms.add(sectionLabel(fm.title, forms.getComponentCount() == 0 ? 0 : 14));
      JPanel p = layoutForm(fm);
      p.setAlignmentX(0f);
      forms.add(p);
    }
    JLabel ih = new JLabel("Inputs");
    ih.setFont(ih.getFont().deriveFont(Font.BOLD, 12f));
    ih.setForeground(Styled.MUTED);
    ih.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
    JPanel west = new JPanel(new BorderLayout());
    west.setOpaque(false);
    west.add(forms, BorderLayout.WEST);
    JPanel inner = new JPanel(new BorderLayout());
    inner.setBackground(java.awt.Color.WHITE);
    inner.setBorder(BorderFactory.createEmptyBorder(10, 14, 12, 14));
    inner.add(ih, BorderLayout.NORTH);
    JPanel north = new JPanel(new BorderLayout());
    north.setOpaque(false);
    north.add(west, BorderLayout.NORTH);
    inner.add(north, BorderLayout.CENTER);
    JScrollPane ls = new JScrollPane(inner);
    ls.setBorder(BorderFactory.createLineBorder(Styled.LINE));
    ls.getViewport().setBackground(java.awt.Color.WHITE);
    ls.getVerticalScrollBar().setUnitIncrement(16);
    JScrollPane rs = new JScrollPane(t.view);
    rs.setBorder(BorderFactory.createLineBorder(Styled.LINE));
    rs.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
    rs.getViewport().setBackground(java.awt.Color.WHITE);
    rs.getVerticalScrollBar().setUnitIncrement(16);
    JPanel outCard = new JPanel(new BorderLayout());
    outCard.add(rs, BorderLayout.CENTER);
    outCard.add(t.status, BorderLayout.SOUTH);
    JSplitPane sp = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, ls, outCard);
    sp.setBorder(BorderFactory.createEmptyBorder());
    sp.setDividerSize(10);
    sp.setResizeWeight(0.5);
    sp.setDividerLocation(NAME_W + SYM_W + FIELD_W + UNIT_W + 90);
    JPanel page = new JPanel(new BorderLayout());
    page.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 0));
    page.add(head, BorderLayout.NORTH);
    page.add(sp, BorderLayout.CENTER);
    return page;
  }

  static final int NAME_W = 210, SYM_W = 48, FIELD_W = 120, UNIT_W = 64;

  static <C extends JComponent> C fixed(C c, int w) {
    java.awt.Dimension d = new java.awt.Dimension(w, c.getPreferredSize().height);
    c.setPreferredSize(d);
    c.setMinimumSize(d);
    return c;
  }

  static JLabel sectionLabel(String text, int top) {
    JLabel s = new JLabel(text);
    s.setFont(s.getFont().deriveFont(Font.BOLD, 12f));
    s.setForeground(Styled.MUTED);
    s.setBorder(BorderFactory.createEmptyBorder(top, 0, 2, 0));
    return s;
  }

  static JLabel nameLabel(String text) {
    JLabel l = new JLabel(text);
    l.setFont(l.getFont().deriveFont(Font.PLAIN, 13f));
    l.setForeground(Styled.INK);
    l.setToolTipText(text);
    return l;
  }

  /** Inputs of a form: name | symbol | value | unit; choices first where declared; optional inputs last. */
  JPanel layoutForm(Form fm) {
    JPanel p = new JPanel(new GridBagLayout());
    p.setOpaque(false);
    GridBagConstraints g = new GridBagConstraints();
    g.anchor = GridBagConstraints.WEST;
    List<String> main = new ArrayList<>(), opt = new ArrayList<>();
    for (String o : fm.order) (o.startsWith("f:") && fm.fields.get(o.substring(2)).opt ? opt : main).add(o);
    if (!opt.isEmpty()) {
      main.add("s:Optional");
      main.addAll(opt);
    }
    fm.shownOrder.clear();
    fm.shownOrder.addAll(main);
    int row = 0;
    for (String o : main) {
      String k = o.substring(2);
      List<JComponent> cs = new ArrayList<>();
      g.gridy = row;
      g.gridx = 0;
      g.gridwidth = 1;
      g.insets = new Insets(3, 0, 3, 8);
      if (o.startsWith("s:")) {
        JLabel s = sectionLabel(k, row == 0 ? 0 : 12);
        g.gridwidth = 4;
        g.insets = new Insets(0, 0, 2, 0);
        p.add(s, g);
        cs.add(s);
      } else if (o.startsWith("c:")) {
        JComboBox<String> cb = fm.combos.get(k);
        JLabel l = fixed(nameLabel(cb.getName()), NAME_W);
        p.add(l, g);
        cs.add(l);
        g.gridx = 1;
        g.gridwidth = 3;
        p.add(fixed(cb, SYM_W + FIELD_W + UNIT_W + 16), g);
        cs.add(cb);
        cb.addActionListener(e -> {
          applyVisibility(fm);
          schedule(fm);
        });
      } else {
        Field f = fm.fields.get(k);
        JLabel l = fixed(nameLabel(f.name), NAME_W);
        l.setToolTipText(f.label + (f.hint.isEmpty() ? "" : "  (" + f.hint + ")"));
        p.add(l, g);
        cs.add(l);
        g.gridx = 1;
        JLabel sy = new JLabel();
        if (!f.sym.isEmpty()) {
          try { sy.setIcon(TeX.icon(f.sym, 15f)); } catch (Throwable x) { sy.setText(TeX.plain(f.sym)); }
        }
        p.add(fixed(sy, SYM_W), g);
        cs.add(sy);
        g.gridx = 2;
        p.add(fixed(f.tf, FIELD_W), g);
        cs.add(f.tf);
        g.gridx = 3;
        JLabel u = new JLabel(showUnit(f.unit));
        u.setFont(u.getFont().deriveFont(Font.PLAIN, 12f));
        u.setForeground(Styled.MUTED);
        p.add(fixed(u, UNIT_W), g);
        cs.add(u);
        f.tf.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
          void changed() { if (!filling) schedule(fm); }
          @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { changed(); }
          @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { changed(); }
          @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { changed(); }
        });
      }
      fm.rows.put(o, cs);
      row++;
    }
    if (!fm.extras.isEmpty()) {
      JPanel b = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
      b.setOpaque(false);
      for (JComponent c : fm.extras) {
        b.add(c);
        b.add(javax.swing.Box.createHorizontalStrut(16));
      }
      g.gridy = row;
      g.gridx = 0;
      g.gridwidth = 4;
      g.insets = new Insets(14, 0, 0, 0);
      p.add(b, g);
    }
    applyVisibility(fm);
    return p;
  }

  /** Shows the inputs used with the current choices; a section heading only if one of its inputs is shown. */
  void applyVisibility(Form fm) {
    Set<String> hide = fm.hiddenKeys();
    List<String> so = fm.shownOrder;
    for (int i = 0; i < so.size(); i++) {
      String o = so.get(i);
      List<JComponent> cs = fm.rows.get(o);
      if (cs == null) continue;
      boolean v;
      if (o.startsWith("s:")) {
        v = false;
        for (int j = i + 1; j < so.size() && !so.get(j).startsWith("s:"); j++) v |= !hide.contains(so.get(j).substring(2));
      } else {
        v = !hide.contains(o.substring(2));
      }
      for (JComponent c : cs) c.setVisible(v);
    }
    for (List<JComponent> cs : fm.rows.values()) {
      if (!cs.isEmpty() && cs.get(0).getParent() != null) {
        cs.get(0).getParent().revalidate();
        cs.get(0).getParent().repaint();
        break;
      }
    }
  }

  /** Computes again 0.3 s after the last change. */
  void schedule(Form fm) {
    if (fm.timer == null) {
      fm.timer = new javax.swing.Timer(300, e -> {
        fluidFields(fm);
        compute(fm);
      });
      fm.timer.setRepeats(false);
    }
    fm.timer.restart();
  }

  /** Pure Java on the Swing thread: outputs shown (calc_log is written on Log this). */
  void compute(Form fm) {
    Tab t = fm.tab;
    try {
      Core.R r = fm.calc.apply(fm);
      t.lastForm = fm.title;
      String inputs = fm.inputs();
      t.showResult(inputs, r);
    } catch (IllegalArgumentException e) {
      t.showError(e.getMessage());
    } catch (Throwable e) {
      t.showError("Error: " + reason(e));
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
      t.note("No output to copy.");
      return;
    }
    String[] labels = new String[t.last.items.size()];
    for (int i = 0; i < labels.length; i++) {
      Core.Item it = t.last.items.get(i);
      labels[i] = it.desc + " = " + Core.g(it.v) + " " + showUnit(it.unit);
    }
    Object c = JOptionPane.showInputDialog(dlg, "Output:", "Copy value for STAR-CCM+", JOptionPane.QUESTION_MESSAGE, null, labels, labels[0]);
    if (c == null) return;
    Core.Item it = t.last.items.get(java.util.Arrays.asList(labels).indexOf(c));
    String v = starNumber(it.v);
    try {
      Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(v), null);
      t.note("Copied " + v + "  (STAR-CCM+ unit: " + starUnit(it.unit) + ")");
    } catch (Throwable e) {
      t.note("Copy failed: " + reason(e) + "; value " + v);
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
      t.note("Copied: all outputs (text)");
    } catch (Throwable e) {
      t.note("Copy failed: " + reason(e));
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
      boolean libOnly = f.libOnly;
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
    f.tf.setToolTipText(on ? null : "From the library fluid (\"" + ENTERED + "\": values typed in)");
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
    buildWall();
    buildTimeStep();
    buildDimensionless();
    buildPipe();
    buildGci();
    buildHeat();
    buildCombustion();
    buildUnits();
  }

  void buildInlet() {
    Form a = new Form(page("Set-up", "Inlet and outlet", "Boundary values of a uniform section from one flow quantity."), "Inlet and outlet");
    a.c("given", "Given quantity", Core.INLET_GIVEN);
    a.in("value", "Value", "", "", "0.05");
    fluidCombo(a, "Fluid");
    a.in("p", "Static pressure, absolute", "p", "Pa", "101325").in("T", "Static temperature", "T", "K", "300");
    a.in("pref", "Reference pressure", "p_{ref}", "Pa", "101325");
    a.in("D", "Diameter", "D", "m", "0.05");
    a.in("rho", "Density", "\\rho", "kg/m^3", "1.177").in("mu", "Dynamic viscosity", "\\mu", "Pa-s", "1.846e-5");
    a.in("cp", "Specific heat", "c_p", "J/kg-K", "1007");
    a.opt("A", "Flow area", "A", "m^2", "", "if D empty");
    a.opt("gamma", "Ratio of specific heats", "\\gamma", "-", "", "cp/(cp - R)");
    a.calc = fm -> {
      double p = fm.req("p"), T = fm.req("T");
      Core.Props pr = props(fm, p);
      double[] x = pr.at(T);
      Core.R r = Core.inletOutlet(fm.idx("given"), fm.req("value"), p, T, fm.req("pref"), fm.v("D"), fm.v("A"), x[0], x[1], x[3],
          fm.v("gamma"), gasConstant(fm, x[0], p, T));
      r.absorb(pr);
      writeBack(fm, r);
      return r.main(fm.idx("given") <= 1 ? "U" : "mdot");
    };

    Form b = new Form(page("Set-up", "Orifice", "Flow through holes from the pressures, or the pressure or diameter for a flow."), "Orifice");
    b.c("solve", "Solve for", Core.ORIFICE_SOLVE);
    fluidCombo(b, "Fluid");
    b.in("p", "Upstream total pressure, absolute", "p_0", "Pa", "300000").in("pb", "Downstream static pressure, absolute", "p_b", "Pa", "101325");
    b.in("T", "Upstream total temperature", "T_0", "K", "300");
    b.in("D", "Hole diameter", "d", "mm", "2").in("n", "Number of holes", "n", "-", "1");
    b.in("Cd", "Discharge coefficient", "C_d", "-", "0.61");
    b.in("mdot", "Mass flow, all holes", "\\dot m", "kg/s", "0.005");
    b.in("rho", "Density, upstream", "\\rho", "kg/m^3", "3.48").in("cp", "Specific heat", "c_p", "J/kg-K", "1007");
    b.opt("gamma", "Ratio of specific heats", "\\gamma", "-", "", "cp/(cp - R)");
    b.hidden = fm -> fm.idx("solve") == 0 ? keys("mdot") : fm.idx("solve") == 1 ? keys("p") : keys("D");
    b.calc = fm -> {
      int solve = fm.idx("solve");
      double p = solve == 1 || Double.isNaN(fm.v("p")) ? 101325 : fm.v("p"), T = fm.req("T");
      Core.Props pr = props(fm, p);
      double[] x = pr.at(T);
      Core.R r = Core.orifice(solve, solve == 0 ? Double.NaN : fm.req("mdot"), solve == 1 ? Double.NaN : fm.req("p"), fm.req("pb"), T,
          solve == 2 ? Double.NaN : fm.req("D"), fm.req("n"), fm.req("Cd"), x[0], x[3], fm.v("gamma"), gasConstant(fm, x[0], p, T));
      r.absorb(pr);
      writeBack(fm, r);
      return r.main(solve == 0 ? "mdot" : solve == 1 ? "p0" : "D");
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

  void buildTurbulence() {
    Form a = new Form(page("Set-up", "Turbulence inlet", "k, epsilon and omega at an inlet (C_mu = 0.09)."), "Turbulence inlet");
    a.in("U", "Mean velocity", "U", "m/s", "10").in("nu", "Kinematic viscosity", "\\nu", "m^2/s", "1.57e-5");
    a.opt("I", "Turbulence intensity", "I", "%", "5", "0.16 Re^(-1/8)");
    a.opt("l", "Turbulent length scale", "l", "m", "", "0.07 D_h");
    a.opt("VR", "Turbulent viscosity ratio", "\\nu_t/\\nu", "-", "", "if l empty");
    a.opt("Dh", "Hydraulic diameter", "D_h", "m", "0.05", "");
    a.opt("A", "Duct area", "A", "m^2", "", "D_h = 4A/P");
    a.opt("P", "Wetted perimeter", "P", "m", "", "");
    a.calc = fm -> Core.turbulenceInlet(fm.req("U"), fm.v("I"), fm.v("l"), fm.v("VR"), fm.req("nu"), fm.v("Dh"),
        fm.v("A"), fm.v("P")).main("k");
  }

  void buildWall() {
    Form a = new Form(page("Mesh", "y+ and prism layers", "First layer thickness for a target y+, and the prism stack."), "y+ and prism layers");
    fluidCombo(a, "Fluid");
    a.in("T", "Temperature", "T", "K", "300").lib().in("p", "Pressure", "p", "Pa", "101325").lib();
    a.in("rho", "Density", "\\rho", "kg/m^3", "1.177").in("mu", "Dynamic viscosity", "\\mu", "Pa-s", "1.846e-5");
    a.in("U", "Velocity", "U", "m/s", "10").in("L", "Reference length", "L", "m", "0.1");
    a.c("corr", "Correlation", Core.WALL_CORR);
    a.in("yplus", "Target y+", "y^+", "-", "1");
    a.section("Prism layers");
    a.c("mode", "Solve for", "Total thickness", "Growth ratio", "Number of layers");
    a.in("r", "Growth ratio", "r", "-", "1.2").in("N", "Number of layers", "N", "-", "15");
    a.in("Ttot", "Total thickness", "T_{tot}", "mm", "5");
    a.opt("core", "Core cell size", "\\Delta x", "mm", "", "last layer / core: 0.5-1");
    a.hidden = fm -> fm.idx("mode") == 0 ? keys("Ttot") : fm.idx("mode") == 1 ? keys("r") : keys("N");
    a.calc = fm -> {
      Core.Props pr = props(fm, fm.v("p"));
      pr.needKcp = false;
      double[] x = pr.at(fm.req("T"));
      Core.R r = Core.wallFirstCell(fm.req("U"), fm.req("L"), x[0], x[1], fm.req("yplus"), fm.idx("corr"));
      r.absorb(pr);
      writeBack(fm, r);
      double y1 = r.get("firstLayer");
      Core.R p;
      switch (fm.idx("mode")) {
        case 1: p = Core.prismSolveR(y1, fm.req("Ttot"), fm.reqInt("N"), fm.v("core")); break;
        case 2: p = Core.prismSolveN(y1, fm.req("r"), fm.req("Ttot"), fm.v("core")); break;
        default: p = Core.prismForward(y1, fm.req("r"), fm.reqInt("N"), fm.v("core"));
      }
      return r.merge(p).main("firstLayer");
    };
  }

  void buildTimeStep() {
    Form a = new Form(page("Time", "Time step and cost", "Time step for a Courant number, run length and core-hours."), "Time step and cost");
    a.in("Co", "Courant number", "Co", "-", "1").in("dx", "Cell size", "\\Delta x", "m", "5e-4").in("U", "Velocity", "U", "m/s", "20");
    a.opt("Lft", "Flow-through length", "L", "m", "0.5", "");
    a.opt("tInit", "Initial transient", "t_{init}", "s", "0.05", "");
    a.opt("tAvg", "Averaging time", "t_{avg}", "s", "0.25", "");
    a.opt("sps", "Wall-clock time per step", "", "s/step", "10", "");
    a.opt("cores", "Cores", "", "cores", "64", "");
    a.calc = fm -> Core.timeStep(fm.req("Co"), fm.req("dx"), fm.req("U"), fm.v("Lft"), fm.v("tInit"), fm.v("tAvg"), fm.v("sps"), fm.v("cores"));

    Form b = new Form(page("Time", "Flow-through and averaging", "Start and end of the averaging window of an unsteady run."),
        "Flow-through and averaging");
    b.in("mdot", "Mass flow through the domain", "\\dot m", "kg/s", "0.046").in("rho", "Mean density", "\\rho", "kg/m^3", "1.2");
    b.in("Ninit", "Flow-throughs to discard", "N_{init}", "-", "2").in("Nft", "Flow-throughs to average", "N_{avg}", "-", "3");
    b.opt("L", "Main flow path length", "L", "m", "1.0", "with A");
    b.opt("A", "Flow cross-section", "A", "m^2", "0.01", "with L");
    b.opt("V", "Fluid volume", "V", "m^3", "", "if L, A empty");
    b.opt("dt", "Time step", "\\Delta t", "s", "", "");
    b.calc = fm -> Core.flowThrough(fm.req("mdot"), fm.req("rho"), fm.v("V"), fm.v("L"), fm.v("A"), fm.req("Ninit"),
        fm.req("Nft"), fm.v("dt")).main("tEnd");
  }

  /** Inputs of the dimensionless numbers: key, name, symbol, unit, default. */
  static final String[][] DIM_FIELDS = {
      {"rho", "Density", "\\rho", "kg/m^3", "1.177"}, {"U", "Velocity", "U", "m/s", "10"}, {"L", "Length", "L", "m", "0.1"},
      {"mu", "Dynamic viscosity", "\\mu", "Pa-s", "1.846e-5"}, {"T", "Temperature", "T", "K", "300"},
      {"gamma", "Ratio of specific heats", "\\gamma", "-", "1.4"}, {"M", "Molar mass", "M", "kg/kmol", "28.965"},
      {"g", "Gravity", "g", "m/s^2", "9.81"}, {"lam", "Mean free path", "\\lambda", "m", "6.8e-8"},
      {"f", "Frequency", "f", "Hz", "100"}, {"dt", "Time step", "\\Delta t", "s", "1e-5"}, {"dx", "Cell size", "\\Delta x", "m", "5e-4"},
      {"p", "Local pressure", "p", "Pa", "101000"}, {"pinf", "Reference pressure", "p_\\infty", "Pa", "101325"},
      {"dp", "Pressure difference", "\\Delta p", "Pa", "100"}, {"tauw", "Wall shear stress", "\\tau_w", "Pa", "0.5"},
      {"h", "Heat transfer coefficient", "h", "W/m^2-K", "50"}, {"k", "Fluid thermal conductivity", "k", "W/m-K", "0.0263"},
      {"ks", "Solid thermal conductivity", "k_s", "W/m-K", "16"}, {"cp", "Specific heat", "c_p", "J/kg-K", "1007"},
      {"dT", "Temperature difference", "\\Delta T", "K", "50"}, {"beta", "Thermal expansion coefficient", "\\beta", "1/K", "0.00333"},
      {"sigma", "Surface tension", "\\sigma", "N/m", "0.072"}, {"drho", "Density difference", "\\Delta\\rho", "kg/m^3", "998"},
      {"rhop", "Particle density", "\\rho_p", "kg/m^3", "1000"}, {"d", "Particle diameter", "d", "um", "10"},
      {"Dm", "Mass diffusivity", "D", "m^2/s", "2e-5"}, {"hm", "Mass transfer coefficient", "h_m", "m/s", "0.01"}};

  void buildDimensionless() {
    Form a = new Form(page("Flow", "Dimensionless numbers", "Definition and value of the common dimensionless numbers."), "Dimensionless numbers");
    String[] names = new String[Core.DIMLESS.length];
    for (int i = 0; i < names.length; i++) names[i] = Core.DIMLESS[i][1] + "  (" + Core.DIMLESS[i][0] + ")";
    a.c("num", "Number", names);
    for (String[] f : DIM_FIELDS) a.in(f[0], f[1], f[2], f[3], f[4]);
    a.hidden = fm -> {
      Set<String> h = new HashSet<>();
      for (String[] f : DIM_FIELDS) h.add(f[0]);
      h.removeAll(Arrays.asList(Core.DIMLESS[fm.idx("num")][2].split(",")));
      return h;
    };
    a.calc = fm -> {
      Map<String, Double> v = new LinkedHashMap<>();
      for (String k : Core.DIMLESS[fm.idx("num")][2].split(",")) v.put(k, fm.req(k));
      return Core.dimless(fm.idx("num"), v);
    };
  }

  void buildPipe() {
    Form a = new Form(page("Flow", "Pipe pressure drop", "Darcy-Weisbach pressure drop of a straight round pipe, minor losses included."),
        "Pipe pressure drop");
    JComboBox<String> fc = fluidCombo(a, "Fluid");
    if (lib.fluids.containsKey("water")) fc.setSelectedItem("water");
    a.in("T", "Temperature", "T", "K", "300").lib().in("p", "Pressure", "p", "Pa", "101325").lib();
    a.in("rho", "Density", "\\rho", "kg/m^3", "998").in("mu", "Dynamic viscosity", "\\mu", "Pa-s", "1.0e-3");
    a.c("given", "Given", "Mean velocity", "Mass flow");
    a.in("U", "Mean velocity", "U", "m/s", "2").in("mdot", "Mass flow", "\\dot m", "kg/s", "0.5");
    a.in("D", "Inner diameter", "D", "mm", "20").in("Lp", "Pipe length", "L", "m", "1");
    a.opt("eps", "Wall roughness", "\\varepsilon", "mm", "0.0015", "smooth: 0");
    a.opt("K", "Sum of minor loss coefficients", "\\Sigma K", "-", "0", "");
    a.hidden = fm -> fm.idx("given") == 0 ? keys("mdot") : keys("U");
    a.calc = fm -> {
      Core.Props pr = props(fm, fm.v("p"));
      pr.needKcp = false;
      double[] x = pr.at(fm.req("T"));
      double D = Core.pos("Inner diameter", fm.req("D"));
      double U = fm.idx("given") == 0 ? fm.req("U") : fm.req("mdot") / (Core.pos("Density", x[0]) * Math.PI * D * D / 4);
      Core.R r = Core.pipe(U, D, fm.req("Lp"), fm.v("eps"), x[0], x[1], fm.v("K"));
      r.absorb(pr);
      writeBack(fm, r);
      return r;
    };
  }

  void buildGci() {
    Form a = new Form(page("Verification", "Grid convergence (GCI)", "Discretisation uncertainty from three meshes (Celik et al. 2008)."),
        "Grid convergence (GCI)");
    a.in("N1", "Cells, fine mesh", "N_1", "cells", "18000").in("N2", "Cells, medium mesh", "N_2", "cells", "8000");
    a.in("N3", "Cells, coarse mesh", "N_3", "cells", "4500");
    a.in("phi1", "Value, fine mesh", "\\phi_1", "-", "6.063").in("phi2", "Value, medium mesh", "\\phi_2", "-", "5.972");
    a.in("phi3", "Value, coarse mesh", "\\phi_3", "-", "5.863");
    a.in("dim", "Dimension (2 or 3)", "", "-", "3");
    a.calc = fm -> Core.gci(fm.req("N1"), fm.req("N2"), fm.req("N3"), fm.req("phi1"), fm.req("phi2"), fm.req("phi3"),
        fm.reqInt("dim")).main("GCIfine21");
  }

  void buildHeat() {
    Form a = new Form(page("Heat transfer", "Convection coefficient", "h and Nu from standard correlations (properties at the film temperature)."),
        "Convection coefficient");
    a.c("corr", "Correlation", Core.CONV_CORR);
    fluidCombo(a, "Fluid");
    a.in("p", "Pressure", "p", "Pa", "101325").lib();
    a.in("U", "Velocity (jet: nozzle exit)", "U", "m/s", "10").in("L", "Diameter, or plate length", "D, L", "m", "0.01");
    a.in("Tinf", "Fluid temperature", "T_\\infty", "K", "300").in("Ts", "Surface temperature", "T_s", "K", "350");
    a.in("rho", "Density", "\\rho", "kg/m^3", "1.1").in("mu", "Dynamic viscosity", "\\mu", "Pa-s", "1.9e-5");
    a.in("k", "Thermal conductivity", "k", "W/m-K", "0.027").in("cp", "Specific heat", "c_p", "J/kg-K", "1007");
    a.in("r", "Averaging radius", "r", "m", "0.05").in("H", "Nozzle-to-plate distance", "H", "m", "0.06");
    a.in("g", "Gravity", "g", "m/s^2", "9.81");
    a.opt("beta", "Thermal expansion coefficient", "\\beta", "1/K", "", "1/T_film");
    a.hidden = fm -> {
      int c = fm.idx("corr");
      return c == 5 ? keys("g", "beta") : c == 6 ? keys("U", "r", "H") : keys("r", "H", "g", "beta");
    };
    a.calc = fm -> {
      Core.Props pr = props(fm, fm.v("p"));
      Core.R r = Core.convection(fm.idx("corr"), fm.v("U"), fm.req("L"), fm.req("Tinf"), fm.req("Ts"), pr, fm.v("r"),
          fm.v("H"), fm.v("g"), fm.v("beta"));
      writeBack(fm, r);
      return r.main("h");
    };

    Form c = new Form(page("Fire test", "Thermocouple correction", "Thermocouple reading from the gas temperature, or the reverse (radiation error)."),
        "Thermocouple correction");
    c.c("mode", "Direction", "Gas T to thermocouple T", "Thermocouple T to gas T");
    fluidCombo(c, "Gas");
    c.in("T", "Known temperature", "T", "K", "1200").in("eps", "Bead emissivity", "\\varepsilon", "-", "0.85");
    c.in("d", "Probe diameter", "d", "m", "0.0015").in("U", "Gas velocity", "U", "m/s", "10");
    c.in("p", "Pressure", "p", "Pa", "101325").lib();
    c.in("Tsur", "Surroundings temperature", "T_{sur}", "K", "600");
    c.in("rho", "Density", "\\rho", "kg/m^3", "0.29").in("mu", "Dynamic viscosity", "\\mu", "Pa-s", "4.6e-5");
    c.in("k", "Thermal conductivity", "k", "W/m-K", "0.075").in("cp", "Specific heat", "c_p", "J/kg-K", "1175");
    c.opt("G", "Incident radiation", "G", "W/m^2", "", "4 sigma T_sur^4");
    c.calc = fm -> {
      Core.Props pr = props(fm, fm.v("p"));
      boolean fwd = fm.idx("mode") == 0;
      Core.R r = Core.thermocouple(fwd, fm.req("T"), fm.req("eps"), fm.v("G"), fm.v("Tsur"), fm.req("d"), fm.req("U"), pr);
      writeBack(fm, r);
      return r.main(fwd ? "Ttc" : "Tg");
    };
  }

  /** Atom count: empty field = 0. */
  static double atoms(Form fm, String k) {
    double v = fm.v(k);
    return Double.isNaN(v) ? 0 : v;
  }

  void buildCombustion() {
    Form a = new Form(page("Combustion", "Stoichiometry", "Mixture fraction and equivalence ratio of a CxHyOz fuel in air."), "Stoichiometry");
    a.in("x", "Carbon atoms", "x", "-", "12").in("y", "Hydrogen atoms", "y", "-", "23").in("z", "Oxygen atoms", "z", "-", "0");
    a.c("given", "Mixture given by", "Fuel and air mass flows", "Equivalence ratio", "Mixture fraction");
    a.in("mFuel", "Fuel mass flow", "\\dot m_f", "g/s", "1.89").in("mAir", "Air mass flow", "\\dot m_a", "kg/s", "0.046");
    a.in("phi", "Equivalence ratio", "\\phi", "-", "1").in("Z", "Mixture fraction", "Z", "-", "0.0642");
    a.hidden = fm -> fm.idx("given") == 0 ? keys("phi", "Z") : fm.idx("given") == 1 ? keys("mAir", "Z") : keys("mAir", "phi");
    a.calc = fm -> {
      int g = fm.idx("given");
      return Core.stoich(atoms(fm, "x"), atoms(fm, "y"), atoms(fm, "z"), g, fm.v("mFuel"), g == 0 ? fm.req("mAir") : Double.NaN,
          g == 1 ? fm.req("phi") : Double.NaN, g == 2 ? fm.req("Z") : Double.NaN);
    };
  }

  void buildUnits() {
    Form a = new Form(page("Units", "Unit conversions", "Heat flux, power, pressure, speed and length (US and SI)."), "Unit conversions");
    a.c("conv", "Conversion", Core.CONV_NAMES);
    a.in("v", "Value", "", "", "1");
    a.calc = fm -> Core.convert(fm.idx("conv"), fm.req("v"));

    Form c = new Form(page("Units", "US gal/h to kg/s", "Liquid fuel mass flow from US gallons per hour."), "US gal/h to kg/s");
    c.in("Q", "Volume flow", "Q", "US gal/h", "1").in("rho", "Liquid density", "\\rho", "kg/m^3", "800");
    c.calc = fm -> Core.galPerHour(fm.req("Q"), fm.req("rho")).main("mdot");

    Form d = new Form(page("Units", "SCFM to kg/s", "Gas mass flow from standard cubic feet per minute."), "SCFM to kg/s");
    d.in("Q", "Standard volume flow", "Q", "SCFM", "10").in("TF", "Standard temperature", "T_{std}", "degF", "60");
    d.in("ppsi", "Standard pressure", "p_{std}", "psi", "14.696").in("M", "Molar mass", "M", "kg/kmol", "28.965");
    d.calc = fm -> Core.scfm(fm.req("Q"), fm.req("TF"), fm.req("ppsi"), fm.req("M")).main("mdot");
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
    t.note(line);
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
    t.note(line);
    log.line(line);
  }

  void toParameter(Tab t) {
    if (t.last == null || t.last.items.isEmpty()) throw new Cancel("no output on page '" + t.name + "'");
    List<String> labels = new ArrayList<>();
    for (Core.Item it : t.last.items) labels.add(it.desc + " = " + Core.g(it.v) + " " + showUnit(it.unit) + "   [" + it.key + "]");
    String c = Ui.choose("To parameter...", "Output of '" + t.lastForm + "':", labels.toArray(new String[0]));
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
    t.note(line);
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

      String main;            // key of the main output (null: the first)
      boolean showFormula;    // formula drawn under the main value (definitions)

      R(String title) { this.title = title; }

      R main(String key) {
        main = key;
        return this;
      }

      Item headline() {
        if (main != null) for (Item it : items) if (it.key.equals(main)) return it;
        return items.get(0);
      }

      /** Appends the outputs, formulas, notes and warnings of another result (keys already present are kept). */
      R merge(R o) {
        for (Item it : o.items) if (!has(it.key)) items.add(it);
        tex.addAll(o.tex);
        for (String l : o.lines) if (!lines.contains(l)) lines.add(l);
        for (String w : o.warns) warn(w);
        return this;
      }

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
      r.put("Re", Re, "-", "Reynolds number");
      if (corr == 1) {
        double f = 0.316 * Math.pow(Re, -0.25);
        tau = f / 8 * rho * U * U;
        r.put("fDarcy", f, "-", "Darcy friction factor (Blasius)");
        r.put("cf", f / 4, "-", "Skin-friction coefficient");
        r.f("f = 0.316\\,Re^{-0.25} ; \\tau_w = \\frac{f}{8} \\rho U^2 \\quad \\text{(U: bulk velocity, L: hydraulic diameter)}");
        r.src("Blasius (1913), smooth pipe; e.g. White, Fluid Mechanics");
        r.valid("smooth pipe, turbulent, 4e3 <= Re_Dh <= 1e5");
        r.range("Re_Dh", Re, 4e3, 1e5);
      } else if (corr == 2) {
        double cf = 0.664 / Math.sqrt(Re);
        tau = cf * qdyn;
        r.put("cf", cf, "-", "Skin-friction coefficient at x = L");
        r.f("c_f = \\frac{0.664}{\\sqrt{Re_x}} ; \\tau_w = c_f \\frac{1}{2} \\rho U^2");
        r.src("Blasius laminar flat-plate solution; Schlichting, Boundary-Layer Theory");
        r.valid("laminar flat plate, zero pressure gradient, Re_x < 5e5");
        r.range("Re_x", Re, 0, 5e5);
      } else {
        double cf = Math.pow(2 * Math.log10(Re) - 0.65, -2.3);
        tau = cf * qdyn;
        r.put("cf", cf, "-", "Skin-friction coefficient at x = L");
        r.f("c_f = \\left(2 \\log_{10} Re_x - 0.65\\right)^{-2.3} ; \\tau_w = c_f \\frac{1}{2} \\rho U^2");
        r.src("Schlichting, Boundary-Layer Theory (turbulent flat plate, smooth)");
        r.valid("turbulent flat plate, smooth, 5e5 <= Re_x <= 1e9 (below 5e5 the layer is laminar unless tripped)");
        r.range("Re_x", Re, 5e5, 1e9);
      }
      double uTau = Math.sqrt(tau / rho), y = yPlus * nu / uTau;
      r.put("tauW", tau, "Pa", "Wall shear stress");
      r.put("uTau", uTau, "m/s", "Friction velocity");
      r.put("nu", nu, "m^2/s", "Kinematic viscosity");
      r.put("rho", rho, "kg/m^3", "Density");
      r.put("mu", mu, "Pa-s", "Dynamic viscosity");
      r.put("yCentroid", y, "m", "Wall distance of the first cell centroid");
      r.put("firstLayer", 2 * y, "m", "First layer thickness");
      r.f("u_\\tau = \\sqrt{\\frac{\\tau_w}{\\rho}} ; y = \\frac{y^+ \\nu}{u_\\tau} ; \\text{first layer} = 2y");
      r.line("y+ evaluated by STAR-CCM+ at the wall-cell centroid: first layer thickness = 2 y");
      r.line("c_f: local value at x = L (higher near the leading edge and at impingement)");
      return r;
    }

    static double prismTotal(double y1, double r, int n) {
      return Math.abs(r - 1) < 1e-12 ? n * y1 : y1 * (Math.pow(r, n) - 1) / (r - 1);
    }

    static R prismForward(double y1, double r, int n, double core) {
      pos("y1", y1); pos("r", r);
      if (n < 1) throw new IllegalArgumentException("N must be >= 1");
      R res = new R("Prism stack: total thickness");
      res.put("total", prismTotal(y1, r, n), "m", "Total thickness");
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
      res.put("r", r, "-", "Growth ratio");
      res.put("total", prismTotal(y1, r, n), "m", "Total thickness");
      prismCommon(res, y1, r, n, core);
      return res;
    }

    static R prismSolveN(double y1, double r, double total, double core) {
      pos("y1", y1); pos("r", r); pos("T", total);
      R res = new R("Prism stack: number of layers N for given y1, r, T");
      double exact = Math.abs(r - 1) < 1e-12 ? total / y1 : Math.log(1 + total * (r - 1) / y1) / Math.log(r);
      if (r < 1) res.warn("r < 1: layers shrink away from the wall");
      int n = Math.max(1, (int) Math.ceil(exact - 1e-9));
      res.put("N", n, "-", "Number of layers");
      res.put("total", prismTotal(y1, r, n), "m", "Total thickness");
      res.f("N = \\left\\lceil \\frac{\\ln\\left(1 + T (r - 1)/y_1\\right)}{\\ln r} \\right\\rceil");
      prismCommon(res, y1, r, n, core);
      return res;
    }

    static void prismCommon(R res, double y1, double r, int n, double core) {
      double last = y1 * Math.pow(r, n - 1);
      res.put("last", last, "m", "Last layer thickness");
      res.f("T = y_1 \\frac{r^N - 1}{r - 1} ; \\text{last layer} = y_1 r^{N-1}");
      if (given(core)) {
        pos("core cell size", core);
        double ratio = last / core;
        res.put("lastToCore", ratio, "-", "Last layer / core cell size");
        if (ratio < 0.5 || ratio > 1) res.warn("last layer / core cell = " + g(ratio) + ": aim 0.5-1 for a smooth transition");
      }
      res.range("growth ratio r", r, 1.0, 1.5);
      res.src("geometric series; transition advice: common practice (STAR-CCM+ prism layer mesher)");
      res.valid("r in (1, 1.5] recommended (<= 1.2 for heat transfer / separation); r <= 3 accepted by the solver here");
    }

    // ----- 2. Dimensionless numbers -------------------------------------------------------------------

    // ----- 3. Turbulence inlet -------------------------------------------------------------------------

    static R turbulenceInlet(double U, double I, double l, double VR, double nu, double Dh, double A, double P) {
      pos("U", U); pos("nu", nu);
      R r = new R("Turbulence inlet values (C_mu = 0.09)");
      if (!given(Dh) && given(A) && given(P)) {
        Dh = 4 * pos("A", A) / pos("P", P);
        r.put("Dh", Dh, "m", "Hydraulic diameter");
      }
      if (given(Dh)) r.put("ReDh", U * pos("D_h", Dh) / nu, "-", "Reynolds number on D_h");
      if (!given(I)) {
        if (!given(Dh)) throw new IllegalArgumentException("enter I, or D_h (or A and P) for the pipe estimate");
        I = 0.16 * Math.pow(r.get("ReDh"), -0.125);
        r.line("I from the developed-pipe estimate I = 0.16 Re_Dh^(-1/8)");
      }
      pos("I", I);
      r.put("I", I, "-", "Turbulence intensity (fraction)");
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
      r.put("l", l, "m", "Turbulent length scale");
      r.put("k", k, "J/kg", "Turbulent kinetic energy");
      r.put("eps", eps, "m^2/s^3", "Turbulent dissipation rate");
      r.put("omega", Math.sqrt(k) / (Math.pow(CMU, 0.25) * l), "1/s", "Specific dissipation rate");
      r.put("nutRatio", nut / nu, "-", "Turbulent viscosity ratio");
      r.f("k = 1.5\\,(U I)^2 ; \\varepsilon = \\frac{C_\\mu^{0.75} k^{1.5}}{l} ; \\omega = \\frac{k^{0.5}}{C_\\mu^{0.25} l} ; \\nu_t = \\frac{C_\\mu k^2}{\\varepsilon}");
      r.f("\\text{pipe estimates:}\\quad I = 0.16\\,Re_{D_h}^{-1/8}, \\quad l = 0.07\\,D_h");
      r.src("standard k-epsilon relations (Launder & Spalding 1974); pipe estimates: common CFD practice (e.g. Versteeg & Malalasekera)");
      r.valid("pipe estimates for fully developed turbulent pipe flow; STAR-CCM+ 'Turbulent Length Scale' uses the same l definition");
      return r;
    }

    // ----- 4. Time step & statistics -------------------------------------------------------------------

    static R timeStep(double co, double dx, double U, double lft, double tInit, double tAvg, double sps, double cores) {
      pos("Courant number", co); pos("cell size", dx); pos("velocity", U);
      R r = new R("Time step and cost");
      double dt = co * dx / U;
      r.put("dt", dt, "s", "Time step");
      if (given(lft)) r.put("tFlowThrough", pos("flow-through length", lft) / U, "s", "Flow-through time");
      double tTot = (given(tInit) ? tInit : 0) + (given(tAvg) ? tAvg : 0);
      if (tTot > 0) {
        double steps = Math.ceil(tTot / dt);
        r.put("tTotal", tTot, "s", "Physical time to run");
        r.put("steps", steps, "-", "Time steps");
        if (given(lft) && given(tAvg)) r.put("nFlowThrough", tAvg * U / lft, "-", "Flow-throughs averaged");
        if (given(sps) && given(cores)) {
          r.put("coreHours", steps * sps * cores / 3600, "core-h", "Core-hours");
          r.put("wallHours", steps * sps / 3600, "h", "Wall-clock time");
        }
      }
      r.f("\\Delta t = \\frac{Co\\,\\Delta x}{U} ; t_{ft} = \\frac{L}{U} ; \\text{core-h} = \\frac{N_{steps} \\cdot \\text{s/step} \\cdot \\text{cores}}{3600}");
      r.src("definitions; Co is the convective Courant number");
      r.valid("Co ~ 1 for scale-resolving simulations (LES, DES); implicit URANS accepts a larger Co");
      return r.main("dt");
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
      r.put("r21", r21, "-", "Refinement ratio, meshes 2-1").put("r32", r32, "-", "Refinement ratio, meshes 3-2");
      r.put("p", p, "-", "Apparent order");
      r.put("phiExt21", ext21, "", "Extrapolated value");
      r.put("phiExt32", ext32, "", "Extrapolated value, meshes 2-3");
      r.put("ea21", 100 * ea21, "%", "Approximate relative error");
      r.put("eext21", 100 * eext21, "%", "Extrapolated relative error");
      r.put("GCIfine21", 100 * gci21, "%", "GCI, fine mesh");
      r.put("GCI32", 100 * gci32, "%", "GCI, medium mesh");
      r.put("asymptotic", gci32 / (r21p * gci21), "-", "Asymptotic range check (about 1)");
      r.f("h = \\left(\\frac{1}{N}\\right)^{1/dim} ; p = \\frac{\\left|\\,\\ln\\left|e_{32}/e_{21}\\right| + q(p)\\,\\right|}{\\ln r_{21}} ; q = \\ln \\frac{r_{21}^p - s}{r_{32}^p - s} ; s = \\text{sign}(e_{32}/e_{21})");
      r.f("\\phi_{ext}^{21} = \\frac{r_{21}^p \\phi_1 - \\phi_2}{r_{21}^p - 1} ; GCI_{fine}^{21} = \\frac{1.25\\,e_a^{21}}{r_{21}^p - 1}");
      r.src("Celik et al. 2008, J. Fluids Eng. 130:078001 (ASME V&V procedure)");
      r.valid("monotonic convergence, r > 1.3, solutions in the asymptotic range, same discretisation on all grids");
      r.line("Celik example (N = 18000/8000/4500) is 2-D: dimension 2 gives p = 1.53, r21 = 1.5; phi_ext and GCI do not depend on the dimension");
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
      r.put("Q", Q, "m^3/s", "Volume flow");
      double tRef = Double.NaN;
      if (given(V)) {
        double tRes = pos("volume", V) / Q;
        r.put("tResidence", tRes, "s", "Mean residence time");
        tRef = tRes;
      }
      if (given(L) && given(A)) {
        double U = Q / pos("cross-section", A), tFt = pos("length", L) / U;
        r.put("Ubulk", U, "m/s", "Bulk velocity");
        r.put("tFlowThrough", tFt, "s", "Flow-through time");
        tRef = tFt;
      }
      if (!given(tRef)) throw new IllegalArgumentException("give the domain volume V, or the length L and the cross-section A");
      double tInit = nInit * tRef, tAvg = nFt * tRef;
      r.put("tDiscard", tInit, "s", "Start-up time to discard");
      r.put("tAvgStart", tInit, "s", "Averaging start time");
      r.put("tAvg", tAvg, "s", "Averaging window");
      r.put("tEnd", tInit + tAvg, "s", "Physical time to run");
      if (given(dt)) r.put("steps", Math.ceil((tInit + tAvg) / pos("time step", dt)), "-", "Time steps to run");
      if (nFt < 2) r.warn("fewer than 2 flow-throughs averaged: mean not converged in general");
      r.f("Q = \\frac{|\\dot m|}{\\rho} ; t_{res} = \\frac{V}{Q} ; t_{ft} = \\frac{L}{Q/A} ; \\text{window} = N \\times t_{ref} \\quad \\text{(}t_{ref} = t_{ft} \\text{ if L and A are given)}");
      r.src("common practice for statistically steady unsteady RANS / LES: discard 1-3, average 2-5 flow-throughs");
      r.valid("one main flow path; recirculation zones need longer windows");
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
      r.put("mdot", mdot, "kg/s", "Mass flow (Mass Flow Inlet)");
      r.put("U", U, "m/s", "Mean velocity (Velocity Inlet)");
      if (gas) {
        double Ma = U / c, fac = 1 + 0.5 * (gam - 1) * Ma * Ma;
        double p0 = p * Math.pow(fac, gam / (gam - 1)), T0 = T * fac;
        r.put("p0_gauge", p0 - pref, "Pa", "Total pressure, gauge (Stagnation Inlet)");
        r.put("T0", T0, "K", "Total temperature (Stagnation Inlet)");
        r.put("p_gauge", p - pref, "Pa", "Static pressure, gauge (Pressure Outlet)");
        r.put("Ma", Ma, "-", "Mach number");
        r.put("Q", U * area, "m^3/s", "Volume flow at p, T");
        r.put("Q_lpm", U * area * 60000, "l/min", "Volume flow at p, T");
        r.put("Qn_lpm", mdot / (P_STD / (Rg * T_NORMAL)) * 60000, "Nl/min", "Standard flow, 0 degC, 1 atm");
        r.put("SCFM", mdot / (P_STD / (Rg * T_SCFM)) * 60 / FT3, "SCFM", "Standard flow, 60 degF, 14.696 psi");
        r.put("p0_gauge_incomp", p - pref + q, "Pa", "Total pressure, gauge, incompressible");
        r.put("q", q, "Pa", "Dynamic pressure");
        double mStar = area * p0 * Math.sqrt(gam / (Rg * T0)) * Math.pow(2 / (gam + 1), (gam + 1) / (2 * (gam - 1)));
        r.put("mdot_choked", mStar, "kg/s", "Choked mass flow of the section");
        r.put("c", c, "m/s", "Speed of sound");
        r.put("gamma", gam, "-", "Ratio of specific heats");
        r.put("R", Rg, "J/kg-K", "Gas constant");
        if (Ma >= 1) r.warn("Ma = " + g(Ma) + " >= 1: the section cannot carry this flow subsonically; check the area, the flow or the pressure");
        else if (Ma > 0.7) r.warn("Ma = " + g(Ma) + " > 0.7: close to choking; the flow reacts strongly to the outlet pressure");
        else if (Ma > 0.3) {
          r.warn("Ma = " + g(Ma) + " > 0.3: use an ideal-gas (compressible) density in STAR-CCM+; the incompressible total pressure is "
              + g(100 * (p0 - p - q) / (p0 - p)) + " % low");
        }
      } else {
        r.put("p0_gauge", p - pref + q, "Pa", "Total pressure, gauge (Stagnation Inlet)");
        r.put("p_gauge", p - pref, "Pa", "Static pressure, gauge (Pressure Outlet)");
        r.put("Q", U * area, "m^3/s", "Volume flow");
        r.put("Q_lpm", U * area * 60000, "l/min", "Volume flow");
        r.put("q", q, "Pa", "Dynamic pressure");
      }
      r.put("rho", rho, "kg/m^3", "Density");
      if (given(mu) && mu > 0) r.put("Re_D", rho * U * dEq / mu, "-", (given(D) ? "Reynolds number" : "Reynolds number, equivalent diameter"));
      r.put("A", area, "m^2", "Flow area");
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
      r.put("mdot", mdot, "kg/s", "Mass flow, all holes");
      r.put("p0", p0, "Pa", "Upstream total pressure");
      r.put("dp", p0 - pb, "Pa", "Pressure drop");
      r.put("D", D, "m", "Hole diameter");
      r.put("A", A, "m^2", "Total hole area");
      if (gas) {
        double prc = Math.pow(2 / (gam + 1), gam / (gam - 1)), pr = pb / p0;
        boolean choked = pr <= prc;
        double Ma = choked ? 1 : Math.sqrt(2 / (gam - 1) * (Math.pow(p0 / pb, (gam - 1) / gam) - 1));
        double Tt = T0 / (1 + 0.5 * (gam - 1) * Ma * Ma);
        r.put("choked", choked ? 1 : 0, "-", "Choked (1 = yes)");
        r.put("pr", pr, "-", "Pressure ratio p_b/p_0");
        r.put("pr_crit", prc, "-", "Critical pressure ratio");
        r.put("U_jet", Ma * Math.sqrt(gam * Rg * Tt), "m/s", "Jet velocity, ideal");
        r.put("Ma_jet", Ma, "-", "Jet Mach number, ideal");
        r.put("T_jet", Tt, "K", "Jet static temperature");
        r.f("\\text{choked}\\;(p_b/p_0 \\le (p_b/p_0)_{crit}): \\quad \\dot m = C_d A\\,p_0 \\sqrt{\\frac{\\gamma}{R T_0}} \\left(\\frac{2}{\\gamma + 1}\\right)^{\\frac{\\gamma + 1}{2(\\gamma - 1)}}");
        r.f("\\text{not choked:}\\quad \\dot m = C_d A\\,p_0 \\sqrt{\\frac{2\\gamma}{(\\gamma - 1) R T_0} \\left[\\left(\\frac{p_b}{p_0}\\right)^{2/\\gamma} - \\left(\\frac{p_b}{p_0}\\right)^{(\\gamma + 1)/\\gamma}\\right]}");
        r.line("ideal gas, R = " + g(Rg) + " J/kg-K, gamma = " + g(gam));
      } else {
        r.put("U_jet", Math.sqrt(2 * (p0 - pb) / rho), "m/s", "Jet velocity, ideal");
        r.f("\\dot m = C_d A \\sqrt{2 \\rho\\,(p_0 - p_b)} \\quad \\text{(liquid, incompressible)}");
      }
      r.put("Cd", Cd, "-", "Discharge coefficient");
      r.line("Cd: sharp-edged orifice 0.60-0.62; short tube 0.8; rounded nozzle 0.95-0.99");
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
      r.put("Tfilm", tf, "K", "Film temperature");
      r.put("rho", rho, "kg/m^3", "Density").put("mu", mu, "Pa-s", "Dynamic viscosity").put("k", k, "W/m-K", "Thermal conductivity").put("cp", cp, "J/kg-K", "Specific heat");
      r.put("Pr", Pr, "-", "Prandtl number");
      double re = Double.NaN;
      if (corr != 6) {
        pos("U", U);
        re = rho * U * L / mu;
        r.put("Re", re, "-", "Reynolds number");
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
          r.put("f", Math.pow(0.790 * Math.log(re) - 1.64, -2), "-", "Friction factor (Petukhov)");
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
          r.put("Ra", ra, "-", "Rayleigh number");
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
      r.put("h", h, "W/m^2-K", "Heat transfer coefficient");
      r.put("q", h * (tS - tInf), "W/m^2", "Heat flux, surface to fluid");
      r.absorb(pr);
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
      r.put("Tg", tg, "K", "Gas temperature");
      r.put("Ttc", ttc, "K", "Thermocouple temperature");
      r.put("bias", tg - ttc, "K", "Radiation error T_g - T_tc");
      r.put("G", G, "W/m^2", "Incident radiation");
      r.put("h", h, "W/m^2-K", "Heat transfer coefficient");
      r.put("Re", o[0], "-", "Probe Reynolds number").put("Pr", o[1], "-", "Prandtl number").put("Nu", o[2], "-", "Nusselt number");
      r.put("rho", o[3], "kg/m^3", "Density").put("mu", o[4], "Pa-s", "Dynamic viscosity").put("k", o[5], "W/m-K", "Thermal conductivity").put("cp", o[6], "J/kg-K", "Specific heat");
      r.f("h\\,(T_g - T_{tc}) = \\varepsilon \\left(\\sigma T_{tc}^4 - \\frac{G}{4}\\right) ; \\text{G/4: irradiation of a sphere or cylinder in a field of incident radiation G}");
      r.f("h \\text{ from } Nu_{CB}(Re_d, Pr), \\quad \\text{properties at } \\frac{T_g + T_{tc}}{2}");
      r.src("steady energy balance of the junction (conduction along the wires neglected); Churchill & Bernstein 1977");
      r.valid("Re_d Pr >= 0.2; steady state (no time constant); grey bead");
      r.range("Re Pr", o[0] * o[1], 0.2, Double.POSITIVE_INFINITY);
      r.absorb(pr);
      return r;
    }

    // ----- 9. Combustion & spray -----------------------------------------------------------------------

    /** CxHyOz in dry air. given: 0 mass flows, 1 equivalence ratio, 2 mixture fraction; an empty atom count is 0. */
    static R stoich(double x, double y, double z, int given, double mFuel, double mAir, double phiIn, double zIn) {
      if (x < 0 || y < 0 || z < 0) throw new IllegalArgumentException("Atom counts: >= 0 required");
      double o2 = x + y / 4 - z / 2;
      if (o2 <= 0) throw new IllegalArgumentException("x + y/4 - z/2 <= 0: no oxygen demand");
      StringBuilder fn = new StringBuilder();
      if (x > 0) fn.append("C").append(x == 1 ? "" : g(x));
      if (y > 0) fn.append("H").append(y == 1 ? "" : g(y));
      if (z > 0) fn.append("O").append(z == 1 ? "" : g(z));
      R r = new R("Stoichiometry of " + fn);
      double mf = 12.011 * x + 1.008 * y + 15.999 * z;
      double afrSt = o2 / 0.2095 * 28.965 / mf, zSt = 1 / (1 + afrSt), afr;
      if (given == 0) {
        afr = pos("air mass flow", mAir) / pos("fuel mass flow", mFuel);
      } else if (given == 1) {
        afr = afrSt / pos("equivalence ratio", phiIn);
      } else {
        if (!(zIn > 0 && zIn < 1)) throw new IllegalArgumentException("Mixture fraction: 0 < Z < 1 required");
        afr = (1 - zIn) / zIn;
      }
      double z1 = 1 / (1 + afr), phi = afrSt / afr;
      r.put("phi", phi, "-", "Equivalence ratio");
      r.put("Z", z1, "-", "Mixture fraction");
      r.put("Zst", zSt, "-", "Stoichiometric mixture fraction");
      r.put("AFR", afr, "-", "Air/fuel mass ratio");
      r.put("AFRst", afrSt, "-", "Stoichiometric air/fuel mass ratio");
      if (given != 0 && given(mFuel)) r.put("mAir", pos("fuel mass flow", mFuel) * afr, "kg/s", "Air mass flow");
      r.put("Mfuel", mf, "kg/kmol", "Fuel molar mass");
      r.put("O2", o2, "-", "O2 demand, kmol per kmol fuel");
      r.f("AFR_{st} = \\frac{x + y/4 - z/2}{0.2095} \\cdot \\frac{M_{air}}{M_{fuel}} ; Z = \\frac{\\dot m_f}{\\dot m_f + \\dot m_a} ; Z_{st} = \\frac{1}{1 + AFR_{st}} ; \\phi = \\frac{AFR_{st}}{AFR} = \\frac{Z\\,(1 - Z_{st})}{Z_{st}\\,(1 - Z)}");
      r.src("mass balance; dry air 20.95 % O2 by volume, M_air = 28.965 kg/kmol; M_fuel = 12.011x + 1.008y + 15.999z");
      r.line("Z: mass fraction of the fuel stream (fuel stream: pure fuel; oxidiser stream: air)");
      if (phi > 3 || phi < 0.2) r.warn("phi = " + g(phi) + ": outside the usual flammability range of hydrocarbons (about 0.5-3)");
      return r.main(given == 0 ? "phi" : given == 1 ? "Z" : "phi");
    }

    // ----- Dimensionless numbers ------------------------------------------------------------------------

    /** symbol, name, input keys (as in DIM_FIELDS of the window). */
    static final String[][] DIMLESS = {
        {"Re", "Reynolds", "rho,U,L,mu"}, {"Ma", "Mach", "U,T,gamma,M"}, {"Fr", "Froude", "U,g,L"}, {"Kn", "Knudsen", "lam,L"},
        {"St", "Strouhal", "f,L,U"}, {"Co", "Courant", "U,dt,dx"}, {"Cp", "Pressure coefficient", "p,pinf,rho,U"},
        {"Eu", "Euler", "dp,rho,U"}, {"cf", "Skin-friction coefficient", "tauw,rho,U"}, {"Nu", "Nusselt", "h,L,k"},
        {"Pr", "Prandtl", "mu,cp,k"}, {"Bi", "Biot", "h,L,ks"}, {"Pe", "Peclet", "U,L,rho,cp,k"}, {"Ec", "Eckert", "U,cp,dT"},
        {"Gr", "Grashof", "g,beta,dT,L,rho,mu"}, {"Ra", "Rayleigh", "g,beta,dT,L,rho,mu,cp,k"}, {"Ri", "Richardson", "g,beta,dT,L,U"},
        {"We", "Weber", "rho,U,L,sigma"}, {"Ca", "Capillary", "mu,U,sigma"}, {"Eo", "Eotvos (Bond)", "drho,g,L,sigma"},
        {"Stk", "Stokes", "rhop,d,mu,U,L"}, {"Sc", "Schmidt", "mu,rho,Dm"}, {"Sh", "Sherwood", "hm,L,Dm"}};

    static R dimless(int i, Map<String, Double> v) {
      String id = DIMLESS[i][0];
      R r = new R(DIMLESS[i][1] + " number");
      java.util.function.ToDoubleFunction<String> q = k -> v.get(k);
      java.util.function.ToDoubleFunction<String> p = k -> pos(k, v.get(k));
      double val;
      switch (id) {
        case "Re": {
          val = q.applyAsDouble("rho") * q.applyAsDouble("U") * q.applyAsDouble("L") / p.applyAsDouble("mu");
          r.put("nu", q.applyAsDouble("mu") / p.applyAsDouble("rho"), "m^2/s", "Kinematic viscosity");
          r.f("Re = \\frac{\\rho U L}{\\mu}");
          r.line("pipe flow: laminar below about 2300, turbulent above about 4000; flat plate: transition at Re_x about 5e5");
          break;
        }
        case "Ma": {
          double c = Math.sqrt(p.applyAsDouble("gamma") * RU / p.applyAsDouble("M") * p.applyAsDouble("T"));
          val = q.applyAsDouble("U") / c;
          r.put("c", c, "m/s", "Speed of sound");
          r.f("Ma = \\frac{U}{c} ; c = \\sqrt{\\gamma \\frac{R_u}{M} T}");
          if (Math.abs(val) > 0.3) r.warn("Ma > 0.3: compressibility effects (density change above about 5 %)");
          break;
        }
        case "Fr":
          val = q.applyAsDouble("U") / Math.sqrt(p.applyAsDouble("g") * p.applyAsDouble("L"));
          r.f("Fr = \\frac{U}{\\sqrt{g L}}");
          break;
        case "Kn":
          val = q.applyAsDouble("lam") / p.applyAsDouble("L");
          r.f("Kn = \\frac{\\lambda}{L}");
          r.line("continuum below 0.01; slip flow 0.01-0.1; transitional 0.1-10; free molecular above 10");
          if (val > 0.01) r.warn("Kn > 0.01: no-slip continuum model not valid");
          break;
        case "St":
          val = q.applyAsDouble("f") * q.applyAsDouble("L") / p.applyAsDouble("U");
          r.f("St = \\frac{f L}{U}");
          r.line("vortex shedding behind a cylinder: St about 0.2 (300 < Re < 2e5)");
          break;
        case "Co":
          val = q.applyAsDouble("U") * q.applyAsDouble("dt") / p.applyAsDouble("dx");
          r.f("Co = \\frac{U \\Delta t}{\\Delta x}");
          break;
        case "Cp": {
          double qd = 0.5 * p.applyAsDouble("rho") * Math.pow(q.applyAsDouble("U"), 2);
          val = (q.applyAsDouble("p") - q.applyAsDouble("pinf")) / pos("dynamic pressure", qd);
          r.put("q", qd, "Pa", "Dynamic pressure");
          r.f("C_p = \\frac{p - p_\\infty}{\\frac{1}{2} \\rho U^2}");
          break;
        }
        case "Eu":
          val = q.applyAsDouble("dp") / (p.applyAsDouble("rho") * Math.pow(p.applyAsDouble("U"), 2));
          r.f("Eu = \\frac{\\Delta p}{\\rho U^2}");
          break;
        case "cf":
          val = q.applyAsDouble("tauw") / (0.5 * p.applyAsDouble("rho") * Math.pow(p.applyAsDouble("U"), 2));
          r.put("fDarcy", 4 * val, "-", "Darcy friction factor (pipe, 4 c_f)");
          r.f("c_f = \\frac{\\tau_w}{\\frac{1}{2} \\rho U^2} ; f_D = 4 c_f");
          break;
        case "Nu":
          val = q.applyAsDouble("h") * q.applyAsDouble("L") / p.applyAsDouble("k");
          r.f("Nu = \\frac{h L}{k}");
          break;
        case "Pr":
          val = q.applyAsDouble("mu") * q.applyAsDouble("cp") / p.applyAsDouble("k");
          r.f("Pr = \\frac{\\mu c_p}{k}");
          break;
        case "Bi":
          val = q.applyAsDouble("h") * q.applyAsDouble("L") / p.applyAsDouble("ks");
          r.f("Bi = \\frac{h L}{k_s}");
          r.line("L = V/A for a lumped body; Bi < 0.1: uniform temperature in the solid (lumped)");
          break;
        case "Pe": {
          double alpha = q.applyAsDouble("k") / (p.applyAsDouble("rho") * p.applyAsDouble("cp"));
          val = q.applyAsDouble("U") * q.applyAsDouble("L") / pos("thermal diffusivity", alpha);
          r.put("alpha", alpha, "m^2/s", "Thermal diffusivity");
          r.f("Pe = \\frac{U L}{\\alpha} = Re\\,Pr ; \\alpha = \\frac{k}{\\rho c_p}");
          break;
        }
        case "Ec":
          val = Math.pow(q.applyAsDouble("U"), 2) / (p.applyAsDouble("cp") * pos("temperature difference", Math.abs(q.applyAsDouble("dT"))));
          r.f("Ec = \\frac{U^2}{c_p \\Delta T}");
          r.line("Ec not small (above about 0.1): viscous heating matters");
          break;
        case "Gr":
        case "Ra":
        case "Ri": {
          double nu = q.applyAsDouble("mu") / pos("rho", v.containsKey("rho") ? v.get("rho") : Double.NaN);
          double gb = q.applyAsDouble("g") * q.applyAsDouble("beta") * Math.abs(q.applyAsDouble("dT"));
          double L = p.applyAsDouble("L");
          if (id.equals("Ri")) {
            val = gb * L / Math.pow(p.applyAsDouble("U"), 2);
            r.f("Ri = \\frac{g \\beta \\Delta T L}{U^2} = \\frac{Gr}{Re^2}");
            r.line("Ri below about 0.1: forced convection; above about 10: natural convection");
            break;
          }
          double gr = gb * L * L * L / (nu * nu);
          if (id.equals("Gr")) {
            val = gr;
            r.f("Gr = \\frac{g \\beta \\Delta T L^3}{\\nu^2}");
          } else {
            double pr = q.applyAsDouble("mu") * q.applyAsDouble("cp") / p.applyAsDouble("k");
            val = gr * pr;
            r.put("Gr", gr, "-", "Grashof number");
            r.put("Pr", pr, "-", "Prandtl number");
            r.f("Ra = Gr\\,Pr = \\frac{g \\beta \\Delta T L^3}{\\nu \\alpha}");
            r.line("vertical plate: laminar below Ra about 1e9, turbulent above");
          }
          r.put("nu", nu, "m^2/s", "Kinematic viscosity");
          r.line("ideal gas: beta = 1/T (K)");
          break;
        }
        case "We":
          val = q.applyAsDouble("rho") * Math.pow(q.applyAsDouble("U"), 2) * q.applyAsDouble("L") / p.applyAsDouble("sigma");
          r.f("We = \\frac{\\rho U^2 L}{\\sigma}");
          r.line("drop break-up: bag break-up from We about 12 (gas density, drop diameter)");
          break;
        case "Ca":
          val = q.applyAsDouble("mu") * q.applyAsDouble("U") / p.applyAsDouble("sigma");
          r.f("Ca = \\frac{\\mu U}{\\sigma}");
          break;
        case "Eo":
          val = q.applyAsDouble("drho") * q.applyAsDouble("g") * Math.pow(q.applyAsDouble("L"), 2) / p.applyAsDouble("sigma");
          r.f("Eo = \\frac{\\Delta\\rho\\, g L^2}{\\sigma}");
          break;
        case "Stk": {
          double tau = q.applyAsDouble("rhop") * Math.pow(q.applyAsDouble("d"), 2) / (18 * p.applyAsDouble("mu"));
          val = tau * q.applyAsDouble("U") / p.applyAsDouble("L");
          r.put("tauP", tau, "s", "Particle relaxation time");
          r.f("Stk = \\frac{\\tau_p U}{L} ; \\tau_p = \\frac{\\rho_p d^2}{18 \\mu}");
          r.line("Stk << 1: particles follow the flow; Stk >> 1: ballistic (Stokes drag, Re_p < 1)");
          break;
        }
        case "Sc":
          val = q.applyAsDouble("mu") / (p.applyAsDouble("rho") * p.applyAsDouble("Dm"));
          r.f("Sc = \\frac{\\mu}{\\rho D}");
          break;
        case "Sh":
          val = q.applyAsDouble("hm") * q.applyAsDouble("L") / p.applyAsDouble("Dm");
          r.f("Sh = \\frac{h_m L}{D}");
          break;
        default:
          throw new IllegalArgumentException("unknown number " + id);
      }
      r.put(id, val, "-", DIMLESS[i][1] + " number");
      r.src("definitions (e.g. White, Fluid Mechanics; Incropera, Fundamentals of Heat and Mass Transfer)");
      r.showFormula = true;
      return r.main(id);
    }

    // ----- Pipe pressure drop --------------------------------------------------------------------------

    static R pipe(double U, double D, double L, double eps, double rho, double mu, double K) {
      pos("Mean velocity", Math.abs(U)); pos("Inner diameter", D); pos("Pipe length", L); pos("Density", rho); pos("Dynamic viscosity", mu);
      double e = given(eps) ? eps : 0, k = given(K) ? K : 0;
      if (e < 0 || k < 0) throw new IllegalArgumentException("Roughness and loss coefficients: >= 0 required");
      R r = new R("Pipe pressure drop");
      double Re = rho * Math.abs(U) * D / mu, rr = e / D, f, fH;
      fH = Math.pow(-1.8 * Math.log10(Math.pow(rr / 3.7, 1.11) + 6.9 / Re), -2);
      if (Re < 2300) {
        f = 64 / Re;
      } else {
        double xf = 1 / Math.sqrt(fH);
        for (int i = 0; i < 60; i++) xf = -2 * Math.log10(rr / 3.7 + 2.51 * xf / Re);
        f = 1 / (xf * xf);
      }
      double q = 0.5 * rho * U * U, dpF = f * L / D * q, dpK = k * q;
      r.put("dp", dpF + dpK, "Pa", "Pressure drop");
      r.put("dpFriction", dpF, "Pa", "Friction pressure drop");
      if (k > 0) r.put("dpMinor", dpK, "Pa", "Minor-loss pressure drop");
      r.put("Re", Re, "-", "Reynolds number");
      r.put("fDarcy", f, "-", Re < 2300 ? "Darcy friction factor (laminar)" : "Darcy friction factor (Colebrook)");
      if (Re >= 2300) r.put("fHaaland", fH, "-", "Darcy friction factor (Haaland)");
      r.put("relRough", rr, "-", "Relative roughness");
      r.put("U", U, "m/s", "Mean velocity");
      r.put("mdot", rho * U * Math.PI * D * D / 4, "kg/s", "Mass flow");
      r.put("q", q, "Pa", "Dynamic pressure");
      r.put("headLoss", (dpF + dpK) / (rho * 9.80665), "m", "Head loss");
      r.f("\\Delta p = \\left(f \\frac{L}{D} + \\Sigma K\\right) \\frac{1}{2} \\rho U^2 ; \\frac{1}{\\sqrt{f}} = -2 \\log_{10}\\left(\\frac{\\varepsilon/D}{3.7} + \\frac{2.51}{Re \\sqrt{f}}\\right) ; f = \\frac{64}{Re} \\quad (Re < 2300)");
      r.src("Darcy-Weisbach; Colebrook (1939); Haaland (1983); e.g. White, Fluid Mechanics");
      r.valid("fully developed incompressible flow in a round pipe; Colebrook for Re > 4000");
      if (Re >= 2300 && Re < 4000) r.warn("Re = " + g(Re) + ": laminar-turbulent transition, friction factor uncertain");
      if (rho * U * U / 2 > 0.05 * 101325 && rho < 50) r.warn("gas with a large dynamic pressure: compressibility not included");
      return r.main("dp");
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
      r.put("value", out, u, "Result");
      if (u.equals("kW/m^2")) r.put("SI", out * 1000, "W/m^2", "SI value");   // SI items for To parameter
      else if (u.equals("mm")) r.put("SI", out / 1000, "m", "SI value");
      r.f("x_{out} = " + g(CONV_F[idx]) + " \\cdot x_{in}").src("1 BTU/(ft^2 s) = 11.3565 kW/m^2 ; 1 BTU/h = 0.29307107 W ; 1 psi = 6894.757 Pa ; 1 ft/min = 0.00508 m/s ; 1 in = 25.4 mm");
      return r;
    }

    static R galPerHour(double q, double rho) {
      pos("density", rho);
      R r = new R("US gal/h -> kg/s");
      double v = q * 3.785411784e-3 / 3600;
      r.put("Vdot", v, "m^3/s", "Volume flow").put("mdot", v * rho, "kg/s", "Mass flow");
      r.f("\\dot m = Q\\,[\\text{gal/h}] \\cdot \\frac{3.785411784 \\times 10^{-3}}{3600} \\cdot \\rho").src("1 US gal = 3.785411784 L (exact)");
      return r;
    }

    static R scfm(double q, double tF, double pPsi, double M) {
      pos("p_std", pPsi); pos("M", M);
      R r = new R("SCFM -> kg/s");
      double T = (tF - 32) / 1.8 + 273.15, p = pPsi * 6894.757293, rho = p * M / (RU * pos("T_std", T)), v = q * 0.028316846592 / 60;
      r.put("Tstd", T, "K", "Standard temperature").put("pstd", p, "Pa", "Standard pressure");
      r.put("rhoStd", rho, "kg/m^3", "Standard density");
      r.put("Vdot", v, "m^3/s", "Standard volume flow").put("mdot", v * rho, "kg/s", "Mass flow");
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
      R c1 = stoich(12, 23, 0, 1, Double.NaN, Double.NaN, 1, Double.NaN), c2 = stoich(10, 20, 0, 1, Double.NaN, Double.NaN, 1, Double.NaN);
      R c3 = stoich(0, 2, 0, 1, Double.NaN, Double.NaN, 1, Double.NaN);
      R c4 = stoich(12, 23, 0, 0, 1.89e-3, 0.046, Double.NaN, Double.NaN);
      sb.append(String.format(Locale.ROOT, "Stoichiometry: C12H23 AFR_st = %.3f, Z_st = %.5f ; C10H20 AFR_st = %.3f ; H2 (x = 0) AFR_st = %.3f (34.3) ; "
          + "C12H23 1.89 g/s in 0.046 kg/s: Z = %.5f, phi = %.4f%n", c1.get("AFRst"), c1.get("Zst"), c2.get("AFRst"), c3.get("AFRst"),
          c4.get("Z"), c4.get("phi")));
      sb.append(String.format(Locale.ROOT, "Churchill-Bernstein Re=1e4 Pr=0.7 -> Nu = %.3f%n", churchillBernstein(1e4, 0.7)));
      R ftr = flowThrough(0.046, 1.2, 0.01, 1.0, 0.01, 2, 3, 1e-3);
      sb.append(String.format(Locale.ROOT, "Flow-through mdot 0.046, rho 1.2, L 1, A 0.01 -> t_ft = %.4f s (0.2609), "
          + "window start %.4f s (0.5217), end %.4f s (1.3043)%n", ftr.get("tFlowThrough"), ftr.get("tAvgStart"), ftr.get("tEnd")));
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
      R pd = pipe(1, 0.05, 10, 0, 1000, 1e-3, 0), pl = pipe(0.01, 0.05, 10, 0, 1000, 1e-3, 0);
      sb.append(String.format(Locale.ROOT, "Pipe water 1 m/s, D 50 mm, 10 m, smooth: Re = %.0f, f = %.5f (Colebrook, 0.0209), dp = %.1f Pa ; "
          + "laminar Re 500: f = %.4f (0.128)%n", pd.get("Re"), pd.get("fDarcy"), pd.get("dp"), pl.get("fDarcy")));
      Map<String, Double> m = new LinkedHashMap<>();
      m.put("rho", 1.2); m.put("U", 10.0); m.put("L", 0.1); m.put("mu", 1.8e-5);
      sb.append(String.format(Locale.ROOT, "Re rho 1.2, U 10, L 0.1, mu 1.8e-5 -> %.1f (66666.7)%n", dimless(0, m).get("Re")));
      return sb.toString();
    }
  }

  // =====================================================================================================
  // Library: data/fluids.csv (no STAR types)
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

    final Map<String, Map<String, Prop>> fluids = new LinkedHashMap<>();
    final List<String> errors = new ArrayList<>();
    final List<String> loaded = new ArrayList<>();
    Path dir;

    static Library load(Path configDir) {
      Library l = new Library();
      l.dir = configDir;
      String[] files = {"fluids.csv"};
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
        for (Prop pr : parseProps(text, fn, l.errors)) l.fluids.computeIfAbsent(pr.owner, k -> new LinkedHashMap<>()).put(pr.prop, pr);
      }
      return l;
    }

    String summary() {
      StringBuilder sb = new StringBuilder("library " + dir + ": " + fluids.size() + " fluids (" + String.join(", ", loaded) + ")");
      for (String e : errors) sb.append("\n  library WARNING: ").append(e);
      return sb.toString();
    }

    /** Parses fluids.csv; every unparseable row is reported in 'errors' and skipped. */
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

    /** Name of a property key as shown in the outputs. */
    static String propName(String key) {
      switch (key) {
        case "rho": return "Density";
        case "mu": return "Dynamic viscosity";
        case "nu": return "Kinematic viscosity";
        case "k": return "Thermal conductivity";
        case "k_x": return "Thermal conductivity, x";
        case "k_y": return "Thermal conductivity, y";
        case "k_z": return "Thermal conductivity, z";
        case "cp": return "Specific heat";
        case "M": return "Molar mass";
        case "eps": return "Emissivity";
        case "sigma": return "Surface tension";
        case "E": return "Young's modulus";
        default: return key;
      }
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
        INK = new java.awt.Color(0x1D, 0x27, 0x33), MUTED = new java.awt.Color(0x6B, 0x75, 0x80), RED = new java.awt.Color(0xB0, 0x30, 0x30),
        BLUE = new java.awt.Color(0x1F, 0x5F, 0xAF), LINE = new java.awt.Color(0xDD, 0xE2, 0xE8);

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

    /** Paragraph tab stops of the outputs table: name | value (right-aligned) | unit. */
    static void rows(javax.swing.text.StyledDocument d, int start) {
      javax.swing.text.SimpleAttributeSet p = new javax.swing.text.SimpleAttributeSet();
      javax.swing.text.StyleConstants.setTabSet(p, new javax.swing.text.TabSet(new javax.swing.text.TabStop[] {
          new javax.swing.text.TabStop(335, javax.swing.text.TabStop.ALIGN_RIGHT, javax.swing.text.TabStop.LEAD_NONE),
          new javax.swing.text.TabStop(344)}));
      javax.swing.text.StyleConstants.setSpaceAbove(p, 2);
      javax.swing.text.StyleConstants.setSpaceBelow(p, 2);
      d.setParagraphAttributes(start, Math.max(1, d.getLength() - start), p, false);
    }

    static void component(javax.swing.text.StyledDocument d, JComponent c) {
      javax.swing.text.SimpleAttributeSet a = new javax.swing.text.SimpleAttributeSet();
      javax.swing.text.StyleConstants.setComponent(a, c);
      add(d, " ", a);
    }

    /** A flat blue text button. */
    static JButton link(String text) {
      JButton b = new JButton(text);
      b.setForeground(BLUE);
      b.setFont(b.getFont().deriveFont(Font.PLAIN, 12f));
      b.setBorderPainted(false);
      b.setContentAreaFilled(false);
      b.setFocusPainted(false);
      b.setOpaque(false);
      b.setMargin(new Insets(0, 0, 0, 0));
      b.setBorder(BorderFactory.createEmptyBorder(1, 0, 1, 0));
      b.setAlignmentY(0.8f);
      b.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
      return b;
    }

    static JButton link(String text, java.awt.event.ActionListener a) {
      JButton b = link(text);
      b.addActionListener(a);
      return b;
    }

    /** Formula (drawn), source, validity, notes, inputs, log. */
    static void details(javax.swing.text.StyledDocument d, Core.R r, String inputs, String logNote) {
      if (!r.showFormula) formulas(d, r);
      for (String l : r.lines) {
        if (!r.tex.isEmpty() && l.startsWith("Formula:")) continue;   // drawn
        int c = l.indexOf(':');
        if (!l.isEmpty() && c > 0 && c < 10) {
          add(d, l.substring(0, c + 1), style(11, true, GREY, null));
          add(d, l.substring(c + 1) + "\n", style(11, false, GREY, null));
        } else {
          add(d, l + "\n", style(11, false, GREY, null));
        }
      }
      add(d, "\nInputs: " + inputs + "\n" + logNote + "\n", style(10, false, new java.awt.Color(0x99, 0x99, 0x99), null));
    }

    /** The formulas of a result, drawn side by side. */
    static void formulas(javax.swing.text.StyledDocument d, Core.R r) {
      if (!r.tex.isEmpty()) {
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
        javax.swing.text.StyleConstants.setLeftIndent(ind, 6);
        javax.swing.text.StyleConstants.setSpaceAbove(ind, 6);
        javax.swing.text.StyleConstants.setSpaceBelow(ind, 3);
        d.setParagraphAttributes(f0, d.getLength() - f0, ind, false);
        add(d, "\n", style(4, false, null, null));
      }
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
