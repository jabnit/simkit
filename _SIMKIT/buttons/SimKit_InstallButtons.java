// SimKit: install (or remove) the SimKit buttons on your STAR-CCM+ Macro toolbar -- play once per user
//
// STAR-CCM+ has 10 favourite-macro buttons per user (Macro toolbar). This macro puts the SimKit buttons there,
// pointing at the macros in this buttons/ folder on the team share (so a kit update never needs the buttons redone),
// each with its own icon from buttons/icons/. It uses STAR-CCM+'s own setting (the one behind the toolbar's
// favourite-macro dialog), saved in the user's STAR-CCM+ folder for this STAR-CCM+ version:
//   Windows  %LOCALAPPDATA%\Siemens\STAR-CCM+ <version>\config\settings.props
//   Linux    ~/.star-<version>/config/settings.props
// Only free slots and slots that already hold a SimKit button are used: your own favourites are kept unless you
// tick "Replace my own favourites". Play it again after installing a new STAR-CCM+ version, or to remove them.
// Batch (test): -Dsimkit.buttons=install (default) or remove, or SIMKIT_ACTION=installbuttons / removebuttons;
// never replaces your own favourites.
package macro;

import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;

import star.common.*;

public class SimKit_InstallButtons extends StarMacro {

  /** Toolbar order: file in buttons/ (without .java), tooltip. Icons: buttons/icons/<file>.png. */
  static final String[][] BUTTONS = {
      {"SimKit_Menu", "SimKit (all tools)"},
      {"SimKit_SaveNewVersion", "SimKit: Save new version"},
      {"SimKit_Check", "SimKit: Check before a run"},
      {"SimKit_ExportResults", "SimKit: Export results"},
      {"SimKit_CaseTracker", "SimKit: Case tracker"},
      {"SimKit_Calculators", "SimKit: Calculators"},
      {"SimKit_CaseReport", "SimKit: Case report"},
      {"SimKit_StandardReports", "SimKit: Standard reports"},
      {"SimKit_StandardScenes", "SimKit: Standard scenes"},
      {"SimKit_MeshStudy", "SimKit: Mesh study"},
  };
  static final int SLOTS = 10;
  static final String KEY = "MacroPlayFavoriteAction";   // + 0..9, value "tooltip ; macro file ; icon file"

  Simulation sim;

  void say(String s) {
    if (sim != null) sim.println("[SimKit] " + s); else System.out.println("[SimKit] " + s);
  }

  /** The macro file of a slot value ("" if empty). */
  static String macroOf(String value) {
    if (value == null) return "";
    String[] p = value.split(";", -1);
    return p.length >= 2 ? p[1].trim() : "";
  }

  /** A SimKit button: SimKit_<Name>.java in a folder named buttons (any share, any older location). */
  static boolean isSimKit(String value) {
    String[] parts = macroOf(value).replace('\\', '/').split("/");
    return parts.length >= 2 && parts[parts.length - 1].matches("SimKit_[A-Za-z]+\\.java") && parts[parts.length - 2].equals("buttons");
  }

  /** File name of a slot's macro, for display. */
  static String nameOf(String value) {
    String m = macroOf(value).replace('\\', '/');
    return m.substring(m.lastIndexOf('/') + 1);
  }

  @Override
  public void execute() {
    try { sim = getActiveSimulation(); } catch (Throwable t) { sim = null; }
    Path dir = Paths.get(resolvePath(".")).toAbsolutePath().normalize();   // this file is <root>/buttons/
    StarSettings st = StarSettings.getSingleton();
    String[] slot = new String[SLOTS];
    for (int i = 0; i < SLOTS; i++) slot[i] = st.getFavoriteMacro(KEY + i) == null ? "" : st.getFavoriteMacro(KEY + i).trim();
    boolean gui = !java.awt.GraphicsEnvironment.isHeadless() && System.getProperty("simkit.buttons") == null
        && System.getenv("SIMKIT_ACTION") == null;

    List<Integer> own = new ArrayList<>();   // slots holding the user's own favourites
    int simkitNow = 0;
    for (int i = 0; i < SLOTS; i++) {
      if (isSimKit(slot[i])) simkitNow++;
      else if (!macroOf(slot[i]).isEmpty()) own.add(i);
    }

    boolean remove = "remove".equals(System.getProperty("simkit.buttons", "install")) || "removebuttons".equals(System.getenv("SIMKIT_ACTION"));
    boolean[] want = new boolean[BUTTONS.length];
    java.util.Arrays.fill(want, true);
    boolean replaceOwn = false;
    if (gui) {
      Object[] r = ask(slot, own, simkitNow, dir);
      if (r == null) { say("toolbar buttons: nothing changed"); return; }
      remove = (Boolean) r[0];
      want = (boolean[]) r[1];
      replaceOwn = (Boolean) r[2] && !remove;   // Remove only ever takes SimKit buttons away
    }

    // missing button files are skipped (an older kit); icons are optional
    List<String[]> chosen = new ArrayList<>();
    for (int b = 0; b < BUTTONS.length; b++) {
      if (remove || !want[b]) continue;
      Path f = dir.resolve(BUTTONS[b][0] + ".java");
      if (!Files.isRegularFile(f)) { say("not found, skipped: " + f); continue; }
      Path icon = dir.resolve("icons").resolve(BUTTONS[b][0] + ".png");
      chosen.add(new String[] {BUTTONS[b][1].replace(";", ","), f.toString(), Files.isRegularFile(icon) ? icon.toString() : ""});
    }

    // free = empty or SimKit slots (+ the user's own if they asked to replace them)
    List<Integer> free = new ArrayList<>();
    for (int i = 0; i < SLOTS; i++) if (!own.contains(i) || replaceOwn) free.add(i);
    if (chosen.size() > free.size()) {
      String msg = chosen.size() + " button(s) chosen but only " + free.size() + " free slot(s) (your own favourites are in "
          + own.size() + "): the last " + (chosen.size() - free.size()) + " are left out.";
      say(msg);
      if (gui) JOptionPane.showMessageDialog(null, msg, "SimKit toolbar buttons", JOptionPane.WARNING_MESSAGE);
      chosen = chosen.subList(0, free.size());
    }

    int cleared = 0;
    for (int i : free) {
      if (isSimKit(slot[i]) || replaceOwn && !macroOf(slot[i]).isEmpty()) { st.setFavoriteMacro(KEY + i, ""); cleared++; }
    }
    for (int k = 0; k < chosen.size(); k++) {
      String[] c = chosen.get(k);
      st.setFavoriteMacro(KEY + free.get(k), c[0] + " ; " + c[1] + " ; " + c[2]);
      say("button " + (free.get(k) + 1) + ": " + c[0] + " -> " + c[1]);
    }
    String done = remove ? "SimKit buttons removed (" + cleared + ")" : chosen.size() + " SimKit button(s) on the Macro toolbar"
        + (own.isEmpty() || replaceOwn ? "" : "; your own favourites kept (" + own.size() + ")");
    String restart = remove ? "If they still show, close and reopen STAR-CCM+." : "If they do not show yet, close and reopen STAR-CCM+.";
    say("toolbar buttons: " + done + ". " + restart);
    if (gui) {
      JOptionPane.showMessageDialog(null, remove ? done + ".\n\n" + restart
          : done + ".\n\nThey are on the Macro toolbar (View > Toolbars > Macro if it is hidden).\n" + restart
          + "\nThey point at the kit on the share, so a kit update needs nothing.\n"
          + "After installing a new STAR-CCM+ version, play this macro again.", "SimKit toolbar buttons", JOptionPane.INFORMATION_MESSAGE);
    }
  }

  /** The dialog: buttons to put on the toolbar (all ticked), replace own favourites, or remove. null = cancel. */
  Object[] ask(String[] slot, List<Integer> own, int simkitNow, Path dir) {
    final Object[][] out = new Object[1][];
    Runnable r = () -> {
      JPanel list = new JPanel(new GridLayout(0, 1, 0, 2));
      JCheckBox[] box = new JCheckBox[BUTTONS.length];
      for (int b = 0; b < BUTTONS.length; b++) {
        box[b] = new JCheckBox(BUTTONS[b][1], true);
        Path icon = dir.resolve("icons").resolve(BUTTONS[b][0] + ".png");
        if (Files.isRegularFile(icon)) {
          JPanel row = new JPanel(new BorderLayout(6, 0));
          row.add(box[b], BorderLayout.CENTER);
          row.add(new JLabel(new ImageIcon(icon.toString())), BorderLayout.WEST);
          list.add(row);
        } else list.add(box[b]);
      }
      JCheckBox replace = new JCheckBox("Replace my own favourites too");
      StringBuilder ownTxt = new StringBuilder();
      for (int i : own) ownTxt.append("\n   ").append(i + 1).append(": ").append(nameOf(slot[i]));
      JPanel p = new JPanel(new BorderLayout(0, 8));
      p.add(new JLabel("<html>STAR-CCM+ keeps 10 favourite-macro buttons per user on the Macro toolbar.<br>"
          + "Tick the SimKit buttons you want" + (simkitNow > 0 ? " (the " + simkitNow + " SimKit button(s) already there are updated)" : "")
          + ".</html>"), BorderLayout.NORTH);
      p.add(list, BorderLayout.CENTER);
      JPanel south = new JPanel(new BorderLayout(0, 4));
      if (!own.isEmpty()) {
        javax.swing.JTextArea t = new javax.swing.JTextArea("Your own favourites (kept unless ticked below):" + ownTxt);
        t.setEditable(false);
        t.setOpaque(false);
        t.setFont(new JLabel().getFont());
        south.add(t, BorderLayout.CENTER);
        south.add(replace, BorderLayout.SOUTH);
      }
      p.add(south, BorderLayout.SOUTH);
      p.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
      String[] opts = simkitNow > 0 ? new String[] {"Install", "Remove SimKit buttons", "Cancel"} : new String[] {"Install", "Cancel"};
      int k = JOptionPane.showOptionDialog(null, p, "SimKit toolbar buttons", JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE,
          null, opts, opts[0]);
      if (k < 0 || opts[k].equals("Cancel")) return;
      boolean[] want = new boolean[BUTTONS.length];
      for (int b = 0; b < BUTTONS.length; b++) want[b] = box[b].isSelected();
      out[0] = new Object[] {opts[k].startsWith("Remove"), want, replace.isSelected()};
    };
    try {
      if (javax.swing.SwingUtilities.isEventDispatchThread()) r.run(); else javax.swing.SwingUtilities.invokeAndWait(r);
    } catch (Exception e) {
      return null;
    }
    return out[0];
  }
}
