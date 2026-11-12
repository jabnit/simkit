// Create or update the ISO global parameters from _SIMKIT\config\parameters_ISO.csv (SimKit 0.1, untested starter)
//
// CSV (UTF-8, comma, header row): name, dimension, unit, default, required, description (other columns ignored)
//   dimension: length | mass flow | temperature | time | velocity | dimensionless | pressure | heat flux | area | density
//              (+ angle, used by the workbook export)
//   unit:      a STAR-CCM+ unit name as in Tools > Units (m, mm, kg/s, K, s, m/s, Pa, W/m^2, m^2, kg/m^3, ...)
//   default:   a number in 'unit', or an expression such as ${p_dt}*100 (set as the parameter definition)
//   required / description: informational here (Pre-run check uses 'required'; descriptions are logged only,
//   because 2402 has no certain API for a parameter description).
// Existing parameters keep their values unless "Reset all to defaults" is chosen. Nothing is ever deleted.
// Uncertain API calls are tagged [Unn]; see _SIMKIT\TESTING.txt. Kit: found from this file's location.
package macro;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.swing.JOptionPane;

import star.base.neo.*;
import star.common.*;

public class ISO_parameters extends StarMacro {

  static final String PROJ = "ISO";
  static final String NAME_REGEX = "^p_[a-z][A-Za-z0-9]*$";

  Simulation sim;
  int created, updated, kept, failed;

  @Override
  public void execute() {
    sim = getActiveSimulation();
    ROOT = findRoot(this, "../../..");   // this file is <root>/templates/ISO/tools/
    Path csv = inKit(kit(), "templates/" + PROJ + "/tools/parameters_" + PROJ + ".csv");
    List<Map<String, String>> rows;
    try {
      rows = readCsv(csv);
    } catch (IOException e) {
      say("ISO_parameters: cannot read " + csv + " (" + e.getMessage() + ")");
      return;
    }
    Boolean reset = askReset(rows.size(), csv);
    if (reset == null) { say("ISO_parameters: cancelled"); return; }
    for (Map<String, String> r : rows) {
      try {
        apply(r, reset);
      } catch (Throwable t) {
        failed++;
        say("  " + r.get("name") + ": n/a (" + reason(t) + ")");
      }
    }
    String sum = String.format("ISO_parameters: %d created, %d updated, %d kept, %d failed (from %s)",
        created, updated, kept, failed, csv);
    say(sum);
    if (!headless()) JOptionPane.showMessageDialog(null, sum, "ISO_parameters", JOptionPane.INFORMATION_MESSAGE);
  }

  /** null = cancel; true = reset all to defaults; false = create missing only. Batch: create missing only. */
  Boolean askReset(int n, Path csv) {
    if (headless()) return Boolean.FALSE;
    String[] opts = {"Create missing only (keep values)", "Reset all to defaults", "Cancel"};
    int r = JOptionPane.showOptionDialog(null, n + " parameter(s) in " + csv, "ISO_parameters",
        JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, opts, opts[0]);
    return r == 0 ? Boolean.FALSE : r == 1 ? Boolean.TRUE : null;
  }

  /** Creates the parameter if missing; sets dimensions, units and default if new or if 'reset'. */
  void apply(Map<String, String> r, boolean reset) {
    String name = r.getOrDefault("name", "").trim();
    if (name.isEmpty() || name.startsWith("#")) return;
    if (!name.matches(NAME_REGEX)) say("  WARN " + name + ": does not match " + NAME_REGEX);
    ScalarGlobalParameter p = find(name);
    boolean isNew = p == null;
    if (!isNew && !reset) {
      kept++;
      say("  kept    " + name + " = " + value(p));
      return;
    }
    if (isNew) {
      p = (ScalarGlobalParameter) gpm().createGlobalParameter(ScalarGlobalParameter.class, "Scalar");     // [U10]
      p.setPresentationName(name);
    }
    p.setDimensions(dims(r.getOrDefault("dimension", "")));                                              // [U31]
    setDefault(p, r.getOrDefault("default", "").trim(), r.getOrDefault("unit", "").trim());
    if (isNew) created++; else updated++;
    say("  " + (isNew ? "created " : "reset   ") + name + " = " + value(p)
        + ("yes".equalsIgnoreCase(r.getOrDefault("required", "")) ? "  [required]" : "")
        + "  -- " + r.getOrDefault("description", ""));
  }

  GlobalParameterManager gpm() { return sim.get(GlobalParameterManager.class); }

  ScalarGlobalParameter find(String name) {
    for (Object o : gpm().getObjects()) {
      if (o instanceof ScalarGlobalParameter && name.equals(((ScalarGlobalParameter) o).getPresentationName())) {
        return (ScalarGlobalParameter) o;
      }
    }
    return null;
  }

  /** Units first, then the value, so the number is read in 'unit' whatever setUnits does to the value. [U31] */
  void setDefault(ScalarGlobalParameter p, String def, String unit) {
    ScalarPhysicalQuantity q = p.getQuantity();
    Units u = units(unit);
    if (u != null) q.setUnits(u);                                                                         // [U31]
    if (def.isEmpty()) return;
    try {
      q.setValue(Double.parseDouble(def));
    } catch (NumberFormatException expr) {
      q.setDefinition(def);                                                                               // [U31]
    }
  }

  /** [U31] Unit object by name; null (and a warning) if this sim has no such unit. */
  Units units(String name) {
    if (name.isEmpty() || name.equals("-") || name.equals("1")) return null;
    try {
      return (Units) sim.getUnitsManager().getObject(name);
    } catch (Throwable t) {
      say("  WARN unit '" + name + "' not found in Tools > Units: value set in the parameter's current units");
      return null;
    }
  }

  /** [U30] Dimensions for the supported dimension names. */
  static Dimensions dims(String d) {
    switch (d.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "")) {
      case "length": return Dimensions.Builder().length(1).build();
      case "massflow": return Dimensions.Builder().mass(1).time(-1).build();
      case "temperature": return Dimensions.Builder().temperature(1).build();
      case "time": return Dimensions.Builder().time(1).build();
      case "velocity": return Dimensions.Builder().length(1).time(-1).build();
      case "dimensionless": return Dimensions.Builder().build();
      case "pressure": return Dimensions.Builder().mass(1).length(-1).time(-2).build();
      case "heatflux": return Dimensions.Builder().mass(1).time(-3).build();
      case "area": return Dimensions.Builder().length(2).build();
      case "density": return Dimensions.Builder().mass(1).length(-3).build();
      case "angle": return Dimensions.Builder().angle(1).build();   // used by parameters_ISO.csv (p_coneHalfAngle)
      default: throw new IllegalArgumentException("unsupported dimension '" + d + "'");
    }
  }

  static String value(ScalarGlobalParameter p) {
    try {
      ScalarPhysicalQuantity q = p.getQuantity();
      return q.getRawValue() + " " + q.getUnits().getPresentationName();
    } catch (Throwable t) {
      return "n/a";
    }
  }

  // ------------------------------------------------------------------ small helpers (no SimKit dependency)

  void say(String s) {
    try { sim.println("[ISO_parameters] " + s); } catch (Throwable t) { System.out.println(s); }
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


  static String reason(Throwable t) {
    return t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage().split("\\r?\\n")[0]);
  }

  /** CSV with header row -> list of maps (keys lower-case). Quoted fields with commas/quotes supported. */
  static List<Map<String, String>> readCsv(Path p) throws IOException {
    String s = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    if (s.startsWith("\uFEFF")) s = s.substring(1);
    List<List<String>> rows = new ArrayList<>();
    List<String> row = new ArrayList<>();
    StringBuilder f = new StringBuilder();
    boolean q = false;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (q) {
        if (c != '"') f.append(c);
        else if (i + 1 < s.length() && s.charAt(i + 1) == '"') { f.append('"'); i++; }
        else q = false;
      } else if (c == '"') q = true;
      else if (c == ',') { row.add(f.toString().trim()); f.setLength(0); }
      else if (c == '\n') { row.add(f.toString().trim()); f.setLength(0); rows.add(row); row = new ArrayList<>(); }
      else if (c != '\r') f.append(c);
    }
    if (f.length() > 0 || !row.isEmpty()) { row.add(f.toString().trim()); rows.add(row); }
    List<Map<String, String>> out = new ArrayList<>();
    if (rows.isEmpty()) return out;
    List<String> head = rows.get(0);
    for (int r = 1; r < rows.size(); r++) {
      if (rows.get(r).size() == 1 && rows.get(r).get(0).isEmpty()) continue;
      Map<String, String> m = new LinkedHashMap<>();
      for (int k = 0; k < head.size(); k++) {
        m.put(head.get(k).trim().toLowerCase(Locale.ROOT), k < rows.get(r).size() ? rows.get(r).get(k) : "");
      }
      out.add(m);
    }
    return out;
  }
}
