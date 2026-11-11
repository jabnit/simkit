// Flame-to-solid hand-off: export xyz + time-averaged heat flux, HTC / reference temperature, incident radiation on T_handoff boundaries (SimKit 0.1, untested starter)
//
// Output: <case>\results\csv\handoff_<CASEID>_<time>.csv  (xyz + values, one row per boundary face, from an
//         XYZ internal table named tbl_handoff, created once and re-used; nothing is ever deleted)
//         + handoff_<CASEID>_<time>_meta.txt listing which field function was used for each column.
// Boundaries: those tagged T_handoff. If tags cannot be read in this build, the macro falls back to boundaries
//             whose name contains "SPC_" (specimen zone) and says so.
// Field functions are looked up by name, first match wins (edit the candidate lists below for your sim):
//   heat flux      : a time-averaged one first (ff_qwMean, or any name containing "mean" + "heat flux");
//                    if none exists, the instantaneous "Boundary Heat Flux" is used and flagged INSTANTANEOUS.
//   HTC / T_ref    : "Heat Transfer Coefficient" / "Heat Transfer Reference Temperature" variants, if available.
//   incident rad.  : "Boundary Incident Radiation" / "Irradiation" variants, if available.
// Requires the sim to be in a SimKit case folder (<SHARE>\PROJECTS\<PROJ>\03_cases\<CASEID>\sim).
// Uncertain API calls are tagged [Unn]; see _SIMKIT\TESTING.txt.
package macro;

import java.awt.GraphicsEnvironment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.JOptionPane;

import star.base.neo.*;
import star.common.*;

public class ISO_exportHandoff extends StarMacro {

  static final String SHARE_ROOT_WINDOWS = "S:\\CFD";
  /** Team share on Linux (RHEL 7/9): mount point of the same share. Keep in step with SHARE_ROOT_WINDOWS. */
  static final String SHARE_ROOT_LINUX = "/mnt/cfd";
  /** Default share of this OS (SIMKIT_SHARE / -Dsimkit.share override it on both). */
  static final String SHARE_ROOT = java.io.File.separatorChar == '\\' ? SHARE_ROOT_WINDOWS : SHARE_ROOT_LINUX;
  static final String TAG = "T_handoff";
  static final String FALLBACK_NAME_PART = "SPC_";
  static final String TABLE_NAME = "tbl_handoff";
  static final Pattern SIM_NAME = Pattern.compile("^(([A-Z]{2,5})-[A-Z0-9]{2,3}-[0-9]{3})_[A-Z]+_v[0-9]{2,3}\\.sim$");

  /** Candidate field-function names per column: {column, exact names..., "kw:" keyword sets (all words must occur)}. */
  static final String[][] COLUMNS = {
      {"q_wall_mean", "ff_qwMean", "kw:mean heat flux", "kw:average heat flux"},
      {"htc", "ff_htcMean", "kw:mean heat transfer coefficient", "Heat Transfer Coefficient",
          "Local Heat Transfer Coefficient", "Specified Y+ Heat Transfer Coefficient"},
      {"t_ref", "ff_TrefMean", "kw:mean heat transfer reference temperature", "Heat Transfer Reference Temperature",
          "Local Heat Transfer Reference Temperature", "Specified Y+ Heat Transfer Reference Temperature"},
      {"q_rad_incident", "ff_qIncMean", "kw:mean incident radiation", "Boundary Incident Radiation Flux",
          "Boundary Irradiation", "kw:incident radiation", "kw:irradiation"}};
  static final String INSTANTANEOUS_HEAT_FLUX = "Boundary Heat Flux";

  Simulation sim;
  final StringBuilder meta = new StringBuilder();

  @Override
  public void execute() {
    sim = getActiveSimulation();
    try {
      run();
    } catch (Throwable t) {
      say("stopped: " + t.getClass().getSimpleName() + ": " + t.getMessage());
    }
  }

  void run() throws Exception {
    Path caseDir = caseDir();
    String caseId = caseDir.getFileName().toString();
    List<Boundary> bs = handoffBoundaries();
    if (bs.isEmpty()) throw new IllegalStateException("no hand-off boundaries (tag " + TAG + ")");
    List<FieldFunction> ffs = new ArrayList<>();
    List<String> cols = new ArrayList<>();
    pickFieldFunctions(ffs, cols);
    if (ffs.isEmpty()) throw new IllegalStateException("none of the hand-off field functions exists in this sim");
    String time = timeLabel();
    Path dir = caseDir.resolve("results").resolve("csv");
    Files.createDirectories(dir);
    Path csv = dir.resolve("handoff_" + caseId + "_" + time + ".csv");
    exportTable(bs, ffs, csv);
    meta.insert(0, "hand-off export " + csv.getFileName() + "\nwritten " + new Date() + " by " + System.getProperty("user.name")
        + "\nsim " + sim.getSessionPath() + "\nboundaries (" + bs.size() + "): " + names(bs) + "\ncolumns: x, y, z (m) + "
        + String.join(", ", cols) + " (units: the field functions' default units)\n");
    Files.write(dir.resolve("handoff_" + caseId + "_" + time + "_meta.txt"), meta.toString().getBytes(StandardCharsets.UTF_8));
    say("written " + csv + "\n" + meta);
    if (!headless()) JOptionPane.showMessageDialog(null, "Written " + csv + "\n\n" + meta, "ISO_exportHandoff", JOptionPane.INFORMATION_MESSAGE);
  }

  /** Case folder from the sim path; refuses to write anywhere else. */
  Path caseDir() {
    Path f = Paths.get(sim.getSessionPath()).toAbsolutePath().normalize();
    Matcher m = SIM_NAME.matcher(f.getFileName().toString());
    if (!m.matches()) throw new IllegalStateException("sim name is not <CASEID>_<STAGE>_v<nn>.sim");
    Path expected = share().resolve("PROJECTS").resolve(m.group(2)).resolve("03_cases").resolve(m.group(1))
        .toAbsolutePath().normalize();
    Path caseDir = f.getParent().getParent();
    if (!expected.toString().equalsIgnoreCase(caseDir.toString())) {
      throw new IllegalStateException("sim is not in " + expected.resolve("sim") + " (check SIMKIT_SHARE)");
    }
    return caseDir;
  }

  /** Boundaries tagged T_handoff; fallback on names containing SPC_ if tags are not readable. [U05] */
  List<Boundary> handoffBoundaries() {
    List<Boundary> tagged = new ArrayList<>(), fallback = new ArrayList<>();
    boolean tagsReadable = false;
    for (Region r : sim.getRegionManager().getRegions()) {
      for (Boundary b : r.getBoundaryManager().getBoundaries()) {
        List<String> t = tagsOf(b);
        if (t != null) tagsReadable = true;
        if (t != null && t.contains(TAG)) tagged.add(b);
        if (b.getPresentationName().contains(FALLBACK_NAME_PART)) fallback.add(b);
      }
    }
    if (tagsReadable) {
      meta.append("boundary selection: tag ").append(TAG).append('\n');
      return tagged;
    }
    say("WARN tags are not readable in this build: using boundaries whose name contains " + FALLBACK_NAME_PART);
    meta.append("boundary selection: FALLBACK name contains ").append(FALLBACK_NAME_PART).append(" (tags not readable)\n");
    return fallback;
  }

  /** [U05] Tag names on an object via the getters tried in SimKit.tagsOf; null if none works. */
  List<String> tagsOf(Object o) {
    Object res = null;
    try { res = call(sim.get(TagManager.class), "getObjectTags", o); } catch (Throwable t) { /* next */ }
    if (res == null) res = call(o, "getTags");
    if (res == null) res = call(call(o, "getTagGroup"), "getObjects");
    if (!(res instanceof Iterable)) return null;
    List<String> out = new ArrayList<>();
    for (Object t : (Iterable<?>) res) out.add(((ClientServerObject) t).getPresentationName());
    return out;
  }

  static Object call(Object target, String method, Object... args) {
    if (target == null) return null;
    for (java.lang.reflect.Method m : target.getClass().getMethods()) {
      if (!m.getName().equals(method) || m.getParameterCount() != args.length) continue;
      try { return m.invoke(target, args); } catch (Throwable t) { return null; }
    }
    return null;
  }

  /** Fills the field functions and column names; heat flux falls back to the instantaneous value. */
  void pickFieldFunctions(List<FieldFunction> ffs, List<String> cols) {
    List<FieldFunction> all = new ArrayList<>();
    for (Object f : sim.getFieldFunctionManager().getObjects()) all.add((FieldFunction) f);
    for (String[] col : COLUMNS) {
      FieldFunction f = find(all, col);
      if (f == null && col[0].equals("q_wall_mean")) {
        f = find(all, new String[] {"q_wall_INSTANTANEOUS", INSTANTANEOUS_HEAT_FLUX});
        if (f != null) { col = new String[] {"q_wall_INSTANTANEOUS"}; say("WARN no time-averaged heat flux found: exporting the instantaneous " + INSTANTANEOUS_HEAT_FLUX); }
      }
      if (f == null) { meta.append(col[0]).append(": not available\n"); continue; }
      ffs.add(f);
      cols.add(col[0]);
      meta.append(col[0]).append(" <- ").append(f.getPresentationName()).append('\n');
    }
  }

  /** First candidate that matches: exact presentation name, or "kw:" all keywords contained (case-insensitive). */
  static FieldFunction find(List<FieldFunction> all, String[] col) {
    for (int i = 1; i < col.length; i++) {
      for (FieldFunction f : all) {
        String n = f.getPresentationName(), low = n.toLowerCase(Locale.ROOT);
        if (col[i].startsWith("kw:")) {
          boolean ok = true;
          for (String w : col[i].substring(3).split(" ")) if (!low.contains(w)) ok = false;
          if (ok) return f;
        } else if (n.equalsIgnoreCase(col[i])) {
          return f;
        }
      }
    }
    return null;
  }

  /** [U32] XYZ internal table on the boundaries, extracted and exported as CSV. */
  void exportTable(List<Boundary> bs, List<FieldFunction> ffs, Path csv) {
    XyzInternalTable t = null;
    for (Object o : sim.getTableManager().getObjects()) {                                                  // [U32]
      if (o instanceof XyzInternalTable && TABLE_NAME.equals(((XyzInternalTable) o).getPresentationName())) t = (XyzInternalTable) o;
    }
    if (t == null) {
      t = sim.getTableManager().createTable(XyzInternalTable.class);                                       // [U32]
      t.setPresentationName(TABLE_NAME);
    }
    t.setFieldFunctions(new NeoObjectVector(ffs.toArray()));                                               // [U32]
    t.getParts().setObjects(bs.toArray(new Boundary[0]));                                                  // [U32]
    t.extract();                                                                                           // [U32]
    t.export(csv.toString(), ",");                                                                         // [U32]
  }

  /** "t<physical time>s" if available [U33], else a wall-clock timestamp. */
  String timeLabel() {
    try {
      double tp = sim.getSolution().getPhysicalTime();                                                     // [U33]
      return String.format(Locale.ROOT, "t%.6fs", tp);
    } catch (Throwable t) {
      return new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
    }
  }

  static String names(List<Boundary> bs) {
    List<String> n = new ArrayList<>();
    for (Boundary b : bs) n.add(b.getRegion().getPresentationName() + "/" + b.getPresentationName());   // [U16]
    return String.join(", ", n);
  }

  void say(String s) {
    try { sim.println("[ISO_exportHandoff] " + s); } catch (Throwable t) { System.out.println(s); }
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

  static Path share() {
    String s = System.getenv("SIMKIT_SHARE");
    return Paths.get(s == null || s.trim().isEmpty() ? SHARE_ROOT : s.trim());
  }
}
