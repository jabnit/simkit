// SimKit: install (or remove) the SimKit buttons on your STAR-CCM+ Macro toolbar -- play once per user
//
// STAR-CCM+ has 10 favourite-macro buttons per user (Macro toolbar). This macro puts the SimKit buttons there,
// pointing at the macros in this buttons/ folder on the team share (so a kit update never needs the buttons redone),
// each with its own icon from buttons/icons/. In the STAR-CCM+ window it sets the toolbar's own favourite buttons
// (as their favourite-macro dialog does: they update at once and STAR-CCM+ saves them); in batch it writes the same
// setting directly. STAR-CCM+ keeps it in the user's STAR-CCM+ folder for this STAR-CCM+ version:
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
  /** + 0..9; value "macro file ; icon file ; tooltip" (the order STAR-CCM+ reads and writes, MacroPlayFavoriteAction). */
  static final String KEY = "MacroPlayFavoriteAction";

  Simulation sim;

  void say(String s) {
    if (sim != null) sim.println("[SimKit] " + s); else System.out.println("[SimKit] " + s);
  }

  /** The macro file of a slot value ("" if empty). */
  static String macroOf(String value) {
    if (value == null) return "";
    return value.split(";", -1)[0].trim();
  }

  static boolean isSimKitFile(String m) {
    String[] parts = m.trim().replace('\\', '/').split("/");
    return parts.length >= 2 && parts[parts.length - 1].matches("SimKit_[A-Za-z]+\\.java") && parts[parts.length - 2].equals("buttons");
  }

  /** A SimKit button: SimKit_<Name>.java in a folder named buttons (any share, any older location). Also the slots
   *  written by the first installer (wrong order "tooltip ; macro ; icon"), so playing it again repairs them. */
  static boolean isSimKit(String value) {
    if (value == null) return false;
    String[] p = value.split(";", -1);
    return isSimKitFile(p[0]) || p.length >= 2 && isSimKitFile(p[1]);
  }

  /** Sets slot i through STAR-CCM+'s own toolbar button, as its favourite-macro dialog does: the button changes at once
   *  and STAR-CCM+ saves the setting. false where there is no such button (batch) or the call fails. */
  static boolean setLive(int i, String macro, String icon, String tip) {
    try {
      Class<?> fav;
      try {
        fav = Class.forName("star.coremodule.actions.MacroPlayFavoriteAction" + i, true, StarSettings.class.getClassLoader());
      } catch (ClassNotFoundException e) {
        fav = Class.forName("star.coremodule.actions.MacroPlayFavoriteAction" + i, true, Thread.currentThread().getContextClassLoader());
      }
      Class<?> sa = Class.forName("org.openide.util.actions.SystemAction", true, fav.getClassLoader());
      final Object a = sa.getMethod("get", Class.class).invoke(null, fav);   // the toolbar's own instance
      final Throwable[] err = new Throwable[1];
      Runnable r = () -> {
        try {
          // the button is made on first use and then reloads the stored setting: make it first, so it cannot undo ours
          for (Class<?> c = a.getClass(); c != null; c = c.getSuperclass()) {
            try {
              java.lang.reflect.Method gb = c.getDeclaredMethod("getButton");
              gb.setAccessible(true);
              gb.invoke(a);
              break;
            } catch (NoSuchMethodException e) { /* look in the superclass */ }
          }
          a.getClass().getMethod("setMacroFileName", String.class).invoke(a, macro);
          a.getClass().getMethod("setIconFileName", String.class).invoke(a, icon);
          a.getClass().getMethod("setToolTip", String.class).invoke(a, tip);
        } catch (Throwable t) {
          err[0] = t;
        }
      };
      if (javax.swing.SwingUtilities.isEventDispatchThread()) r.run(); else javax.swing.SwingUtilities.invokeAndWait(r);
      return err[0] == null;
    } catch (Throwable t) {
      return false;
    }
  }

  /** Sets slot i (live in the window, else the setting itself) and checks what STAR-CCM+ now holds. */
  boolean setSlot(StarSettings st, boolean gui, int i, String macro, String icon, String tip) {
    if (gui && setLive(i, macro, icon, tip) && macroOf(st.getFavoriteMacro(KEY + i)).equals(macro)) return true;
    st.setFavoriteMacro(KEY + i, macro.isEmpty() ? "" : macro + " ; " + icon + " ; " + tip);
    return false;
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

    int cleared = 0, live = 0;
    for (int i : free) {
      if (isSimKit(slot[i]) || replaceOwn && !macroOf(slot[i]).isEmpty()) { setSlot(st, gui, i, "", "", ""); cleared++; }
    }
    for (int k = 0; k < chosen.size(); k++) {
      String[] c = chosen.get(k);   // tooltip, macro, icon
      if (setSlot(st, gui, free.get(k), c[1], c[2], c[0])) live++;
      say("button " + (free.get(k) + 1) + ": " + c[0] + " -> " + c[1] + "   (STAR-CCM+ now holds: " + st.getFavoriteMacro(KEY + free.get(k)) + ")");
    }
    if (gui) say("toolbar buttons set " + (live == chosen.size() ? "directly on the toolbar" : live + " directly, the rest in the settings (restart needed)"));
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
