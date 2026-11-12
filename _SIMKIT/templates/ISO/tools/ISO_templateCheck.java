// Compare the open sim with the ISO template specification (config\template_objects_ISO.csv): missing / extra objects (SimKit 0.1, untested starter)
//
// CSV (UTF-8, comma, header row): object_type, name, required [, configs]
//   object_type: geometry_part | part_surface | volumetric_shape | region | boundary | interface | continuum |
//                parameter | field_function | coordinate_system | derived_part | report | monitor | plot | scene |
//                tag | mesh_operation | mesh_control | stopping_criterion | view | simulation_operation
//                Other types (boundary_rule, auto_save, simulation_guide, ...) are listed as CHECK (manual).
//   name:        exact name. Also understood: "a / b" (both required), "CAL_tcProbe_1..7" (range),
//                "OPEN_*" (wildcard), "(...)" remarks (ignored), a leading ^ (regular expression).
//                part_surface may be <part>/<surface>; boundary may be <region>/<boundary>.
//   required:    yes -> FAIL when missing; anything else -> WARN.
//   configs:     all | list of configuration codes (C1;C2). Rows for other configurations are skipped.
//                The configuration comes from the case ID of the sim (ISO-<CFG>-<NNN>); otherwise it is asked.
// Objects of a checked type that are not in the CSV are reported as EXTRA (information only).
// Output: Output window, plus audit\templatecheck_<CASEID>_<STAGE>_v<nn>.csv when the sim is in a SimKit case folder.
// Read-only: changes nothing in the sim. Uncertain API calls are tagged [Unn]; see _SIMKIT\TESTING.txt.
package macro;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

import star.base.neo.*;
import star.base.report.*;
import star.common.*;
import star.meshing.*;
import star.vis.*;

public class ISO_templateCheck extends StarMacro {

  static final String PROJ = "ISO";
  static final Pattern SIM_NAME = Pattern.compile("^(([A-Z][A-Z0-9]{1,9})-(?:([A-Z0-9]{2,3})-)?[0-9]{3})(?:_[A-Za-z][A-Za-z0-9]{0,19})?_v[0-9]{2,3}\\.sim$");
  /** Types compared case-insensitively (STAR default names such as "Maximum Physical Time"). */
  static final List<String> CASE_INSENSITIVE = Arrays.asList("stopping_criterion");

  Simulation sim;
  final StringBuilder out = new StringBuilder();

  @Override
  public void execute() {
    sim = getActiveSimulation();
    ROOT = findRoot(this, "../../..");   // this file is <root>/templates/ISO/tools/
    Path csv = inKit(kit(), "templates/" + PROJ + "/tools/template_objects_" + PROJ + ".csv");
    List<String[]> spec;
    try {
      spec = readCsv(csv);
    } catch (IOException e) {
      say("cannot read " + csv + " (" + e.getMessage() + ")");
      return;
    }
    String cfg = configuration();
    if (cfg == null) { say("cancelled"); return; }
    say("template " + csv + " -- configuration " + cfg);
    List<String[]> rows = compare(spec, inventory(), cfg);
    int fail = 0, warn = 0, manual = 0;
    for (String[] r : rows) {
      say(String.format("%-5s %-20s %-40s %s", r[0], r[1], r[2], r[3]));
      if (r[0].equals("FAIL")) fail++;
      if (r[0].equals("WARN")) warn++;
      if (r[0].equals("CHECK")) manual++;
    }
    say("template check: " + fail + " FAIL, " + warn + " WARN, " + manual + " CHECK (manual), "
        + (rows.size() - fail - warn - manual) + " EXTRA");
    writeReport(rows);
    if (!headless()) {
      JTextArea ta = new JTextArea(out.toString(), 25, 110);
      ta.setEditable(false);
      JOptionPane.showMessageDialog(null, new JScrollPane(ta), "ISO_templateCheck", JOptionPane.INFORMATION_MESSAGE);
    }
  }

  /** Configuration code from the sim name; else asked (GUI) or "all" (batch: only rows valid for all configs). */
  String configuration() {
    try {
      Matcher m = SIM_NAME.matcher(Paths.get(sim.getSessionPath()).getFileName().toString());
      if (m.matches() && m.group(3) != null) return m.group(3);   // old case IDs carried the configuration code
    } catch (Throwable t) { /* unsaved sim */ }
    if (headless()) return "all";
    Object r = JOptionPane.showInputDialog(null, "Configuration of this sim (not a SimKit case file name):",
        "ISO_templateCheck", JOptionPane.QUESTION_MESSAGE, null, new String[] {"CF", "C1", "C2", "C3", "VV", "all"}, "C1");
    return r == null ? null : (String) r;
  }

  /** Rows {level, object_type, name, detail}: FAIL/WARN missing, CHECK manual, EXTRA not in the template. */
  static List<String[]> compare(List<String[]> spec, Map<String, Set<String>> have, String cfg) {
    List<String[]> rows = new ArrayList<>();
    Map<String, List<String>> listed = new LinkedHashMap<>();
    for (String[] s : spec) {
      String type = s[0].toLowerCase(Locale.ROOT);
      if (type.isEmpty() || type.startsWith("#") || s[1].isEmpty() || !appliesTo(s[3], cfg)) continue;
      if (!have.containsKey(type)) { rows.add(new String[] {"CHECK", type, s[1], "manual check (type not inventoried)"}); continue; }
      for (String name : expand(s[1])) {
        listed.computeIfAbsent(type, k -> new ArrayList<>()).add(name);
        boolean found = false;
        for (String h : have.get(type)) if (matches(name, h, CASE_INSENSITIVE.contains(type))) found = true;
        if (!found) rows.add(new String[] {"yes".equalsIgnoreCase(s[2]) ? "FAIL" : "WARN", type, name, "missing"});
      }
    }
    for (Map.Entry<String, List<String>> e : listed.entrySet()) {
      for (String h : have.get(e.getKey())) {
        boolean known = false;
        for (String n : e.getValue()) if (matches(n, h, CASE_INSENSITIVE.contains(e.getKey()))) known = true;
        if (!known) rows.add(new String[] {"EXTRA", e.getKey(), h, "not in the template specification"});
      }
    }
    return rows;
  }

  /** configs column: empty / all -> every configuration; else a list of codes. cfg "all" = only rows for all. */
  static boolean appliesTo(String configs, String cfg) {
    String c = configs.trim();
    if (c.isEmpty() || c.equalsIgnoreCase("all")) return true;
    for (String code : c.split("[;, ]+")) if (code.equalsIgnoreCase(cfg)) return true;
    return false;
  }

  /** "a / b" -> a, b; "x_1..7" -> x_1 ... x_7; "(remark)" removed. Regex names (^...) are kept whole. */
  static List<String> expand(String spec) {
    List<String> out = new ArrayList<>();
    if (spec.startsWith("^")) { out.add(spec); return out; }
    for (String part : spec.replaceAll("\\([^)]*\\)", "").split("/")) {
      String p = part.trim();
      Matcher m = Pattern.compile("^(.*?)([0-9]+)\\.\\.([0-9]+)(.*)$").matcher(p);
      if (m.matches()) {
        for (int i = Integer.parseInt(m.group(2)); i <= Integer.parseInt(m.group(3)); i++) out.add(m.group(1) + i + m.group(4));
      } else if (!p.isEmpty()) {
        out.add(p);
      }
    }
    return out;
  }

  /** Spec name vs inventory name "<parent>/<name>" or "<name>"; ^... regex, * wildcard. */
  static boolean matches(String spec, String have, boolean ignoreCase) {
    String leaf = have.contains("/") ? have.substring(have.lastIndexOf('/') + 1) : have;
    String regex = spec.startsWith("^") ? spec
        : spec.contains("*") ? "^" + Pattern.quote(spec).replace("*", "\\E.*\\Q") + "$" : null;
    if (regex != null) {
      try {
        Pattern p = Pattern.compile(regex, ignoreCase ? Pattern.CASE_INSENSITIVE : 0);
        return p.matcher(have).find() || p.matcher(leaf).find();
      } catch (RuntimeException e) {
        return false;
      }
    }
    return ignoreCase ? spec.equalsIgnoreCase(have) || spec.equalsIgnoreCase(leaf) : spec.equals(have) || spec.equals(leaf);
  }

  /** Names per object type; each category fails independently ("n/a" logged). */
  Map<String, Set<String>> inventory() {
    Map<String, Set<String>> m = new LinkedHashMap<>();
    try { put(m, "parameter", sim.get(GlobalParameterManager.class).getObjects()); } catch (Throwable t) { na("parameter", t); }
    try { inventoryParts(m); } catch (Throwable t) { na("geometry_part", t); }
    try {
      for (Region r : sim.getRegionManager().getRegions()) {
        set(m, "region").add(nm(r));
        for (Boundary b : r.getBoundaryManager().getBoundaries()) set(m, "boundary").add(nm(r) + "/" + nm(b));
      }
    } catch (Throwable t) { na("region", t); }
    try { put(m, "interface", sim.getInterfaceManager().getObjects()); } catch (Throwable t) { na("interface", t); }
    try {
      for (Object c : sim.getContinuumManager().getObjects()) if (c instanceof PhysicsContinuum) set(m, "continuum").add(nm(c));
    } catch (Throwable t) { na("continuum", t); }
    try {
      for (Object f : sim.getFieldFunctionManager().getObjects()) if (f instanceof UserFieldFunction) set(m, "field_function").add(nm(f));
    } catch (Throwable t) { na("field_function", t); }
    try {                                                                                                  // [U18]
      put(m, "coordinate_system",
          sim.getCoordinateSystemManager().getLabCoordinateSystem().getLocalCoordinateSystemManager().getObjects());
    } catch (Throwable t) { na("coordinate_system", t); }
    try { put(m, "derived_part", sim.getPartManager().getObjects()); } catch (Throwable t) { na("derived_part", t); }
    try { put(m, "report", sim.getReportManager().getObjects()); } catch (Throwable t) { na("report", t); }
    try {
      for (Object o : sim.getMonitorManager().getObjects()) if (o instanceof ReportMonitor) set(m, "monitor").add(nm(o));
    } catch (Throwable t) { na("monitor", t); }
    try {
      for (Object o : sim.getPlotManager().getObjects()) if (!o.getClass().getSimpleName().equals("ResidualPlot")) set(m, "plot").add(nm(o));
    } catch (Throwable t) { na("plot", t); }
    try { put(m, "scene", sim.getSceneManager().getScenes()); } catch (Throwable t) { na("scene", t); }
    try {
      for (Object o : sim.get(TagManager.class).getObjects()) if (o instanceof UserTag) set(m, "tag").add(nm(o)); // [U04]
    } catch (Throwable t) { na("tag", t); }
    try { inventoryMesh(m); } catch (Throwable t) { na("mesh_operation", t); }
    try { put(m, "stopping_criterion", sim.getSolverStoppingCriterionManager().getObjects()); }
    catch (Throwable t) { na("stopping_criterion", t); }
    reflectInventory(m, "view", new String[] {}, "getViewManager");                                         // [U34]
    reflectInventory(m, "simulation_operation",
        new String[] {"star.common.SimulationOperationManager", "star.automation.SimulationOperationManager"}, null); // [U23]
    return m;
  }

  /** Geometry parts (simple shape parts count as volumetric_shape) and their part surfaces. [U02] */
  void inventoryParts(Map<String, Set<String>> m) {
    for (Object p : sim.get(SimulationPartManager.class).getParts()) {
      String c = p.getClass().getSimpleName();
      boolean shape = c.startsWith("Simple") && c.endsWith("Part");
      set(m, shape ? "volumetric_shape" : "geometry_part").add(nm(p));
      if (!shape && p instanceof GeometryPart) {
        for (Object s : ((GeometryPart) p).getPartSurfaces()) set(m, "part_surface").add(nm(p) + "/" + nm(s));
      }
    }
  }

  /** Mesh operations and custom controls of automated mesh operations. [U03] */
  void inventoryMesh(Map<String, Set<String>> m) {
    set(m, "mesh_control");
    for (Object op : sim.get(MeshOperationManager.class).getObjects()) {
      set(m, "mesh_operation").add(nm(op));
      if (op instanceof AutoMeshOperation) {
        try { put(m, "mesh_control", ((AutoMeshOperation) op).getCustomMeshControls().getObjects()); }
        catch (Throwable t) { na("mesh_control of " + nm(op), t); }
      }
    }
  }

  /** Objects of a manager reached by reflection: sim.<getter>() or sim.get(<class>). Type absent if not found. */
  void reflectInventory(Map<String, Set<String>> m, String type, String[] classNames, String getter) {
    Object mgr = getter == null ? null : call(sim, getter);
    for (String cn : classNames) {
      if (mgr != null) break;
      try { mgr = call(sim, "get", Class.forName(cn)); } catch (Throwable t) { /* next */ }
    }
    Object objs = call(mgr, "getObjects");
    if (!(objs instanceof Iterable)) { say("  " + type + ": n/a (manager not found in this build)"); return; }
    set(m, type);
    for (Object o : (Iterable<?>) objs) set(m, type).add(nm(o));
  }

  static Object call(Object target, String method, Object... args) {
    if (target == null) return null;
    for (java.lang.reflect.Method mt : target.getClass().getMethods()) {
      if (!mt.getName().equals(method) || mt.getParameterCount() != args.length) continue;
      boolean ok = true;
      for (int i = 0; i < args.length; i++) if (!mt.getParameterTypes()[i].isInstance(args[i])) ok = false;
      if (!ok) continue;
      try { return mt.invoke(target, args); } catch (Throwable t) { return null; }
    }
    return null;
  }

  static Set<String> set(Map<String, Set<String>> m, String type) { return m.computeIfAbsent(type, k -> new LinkedHashSet<>()); }

  static void put(Map<String, Set<String>> m, String type, Collection<?> objs) { set(m, type); for (Object o : objs) set(m, type).add(nm(o)); }

  static String nm(Object o) {
    try { return ((ClientServerObject) o).getPresentationName(); } catch (Throwable t) { return String.valueOf(o); }
  }

  /** Writes record/templatecheck_<simBase>.csv if the sim is a SimKit case (<CASEID>/sim/<file>). */
  void writeReport(List<String[]> rows) {
    try {
      Path sim0 = Paths.get(sim.getSessionPath()).toAbsolutePath().normalize();
      Path caseDir = caseDirOf(sim0);
      if (caseDir == null) {
        say("not in a SimKit case folder: CSV not written");
        return;
      }
      StringBuilder sb = new StringBuilder("level,object_type,name,detail\r\n");
      for (String[] r : rows) sb.append(q(r[0])).append(',').append(q(r[1])).append(',').append(q(r[2])).append(',').append(q(r[3])).append("\r\n");
      Path f = caseDir.resolve("record").resolve("templatecheck_" + sim0.getFileName().toString().replaceFirst("\\.sim$", "") + ".csv");
      Files.createDirectories(f.getParent());
      Files.write(f, sb.toString().getBytes(StandardCharsets.UTF_8));
      say("written " + f);
    } catch (Throwable t) {
      say("report CSV: n/a (" + t + ")");
    }
  }

  static String q(String s) {
    return s.contains(",") || s.contains("\"") || s.contains("\n") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
  }

  void na(String what, Throwable t) { say("  " + what + ": n/a (" + t.getClass().getSimpleName() + ": " + t.getMessage() + ")"); }

  void say(String s) {
    out.append(s).append('\n');
    try { sim.println("[ISO_templateCheck] " + s); } catch (Throwable t) { System.out.println(s); }
  }

  /** Batch if an action is set, Java is headless, or no window of this JVM is visible (SIMKIT_GUI=1 forces dialogs). */
  static boolean headless() {
    String a = System.getProperty("simkit.action", System.getenv("SIMKIT_ACTION"));
    if (a != null && !a.trim().isEmpty()) return true;
    if ("1".equals(System.getenv("SIMKIT_GUI"))) return false;
    if (GraphicsEnvironment.isHeadless()) return true;
    for (java.awt.Frame f : java.awt.Frame.getFrames()) if (f.isVisible()) return false;
    return true;
  }

  /** SimKit root, found from this file's own location (STAR-CCM+ resolvePath; no variable to set). */
  static Path ROOT;

  /** Root from this macro's folder ('up' = path from this file's folder to the root); checks SimKit.java is there. */
  static Path findRoot(StarMacro m, String up) {
    Path r = Paths.get(m.resolvePath(up)).toAbsolutePath().normalize();
    if (!Files.isRegularFile(r.resolve("SimKit.java"))) {
      throw new IllegalStateException("SimKit root not found at " + r + " (this macro must be played from its place in the kit)");
    }
    return r;
  }

  static Path kit() { return ROOT; }

  /** A file or folder of the kit, given relative to the SimKit root with '/' (same text on Windows and Linux). */
  static Path inKit(Path root, String rel) {
    Path p = root;
    for (String part : rel.split("/")) if (!part.isEmpty()) p = p.resolve(part);
    return p;
  }

  static final char SEP = '\\';

  /** Case folder of a sim saved by SimKit (<any folder>/<CASEID>/sim/<CASEID>[_label]_v<nn>.sim), else null. */
  static Path caseDirOf(Path simFile) {
    Path f = simFile.toAbsolutePath().normalize();
    Matcher m = SIM_NAME.matcher(f.getFileName().toString());
    Path simDir = f.getParent(), cd = simDir == null ? null : simDir.getParent();
    if (!m.matches() || cd == null || !simDir.getFileName().toString().equals("sim")
        || !cd.getFileName().toString().equals(m.group(1))) return null;
    return cd;
  }

  /** CSV -> rows {object_type, name, required, configs} by header name (configs optional). */
  static List<String[]> readCsv(Path p) throws IOException {
    String s = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    if (s.startsWith("\uFEFF")) s = s.substring(1);
    List<String[]> rows = new ArrayList<>();
    String[] lines = s.split("\\r?\\n");
    if (lines.length == 0) return rows;
    List<String> head = new ArrayList<>();
    for (String h : split(lines[0])) head.add(h.trim().toLowerCase(Locale.ROOT));
    int it = head.indexOf("object_type"), in = head.indexOf("name"), ir = head.indexOf("required"), ic = head.indexOf("configs");
    if (it < 0 || in < 0) throw new IOException("header must contain object_type,name[,required][,configs]");
    for (int i = 1; i < lines.length; i++) {
      List<String> c = split(lines[i]);
      if (c.size() <= Math.max(it, in)) continue;
      rows.add(new String[] {c.get(it).trim(), c.get(in).trim(), cell(c, ir), cell(c, ic)});
    }
    return rows;
  }

  static String cell(List<String> c, int i) { return i >= 0 && i < c.size() ? c.get(i).trim() : ""; }

  /** One CSV line (no embedded newlines) with quoted fields. */
  static List<String> split(String line) {
    List<String> out = new ArrayList<>();
    StringBuilder f = new StringBuilder();
    boolean q = false;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (q) {
        if (c != '"') f.append(c);
        else if (i + 1 < line.length() && line.charAt(i + 1) == '"') { f.append('"'); i++; }
        else q = false;
      } else if (c == '"') q = true;
      else if (c == ',') { out.add(f.toString()); f.setLength(0); }
      else f.append(c);
    }
    out.add(f.toString());
    return out;
  }
}
