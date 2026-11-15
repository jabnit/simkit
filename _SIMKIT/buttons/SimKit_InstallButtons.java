// SimKit: installs or removes the SimKit buttons on the STAR-CCM+ toolbar. Play once per user.
//
// STAR-CCM+ has 10 favourite-macro slots per user. This macro puts the SimKit buttons in free slots or in slots
// that already hold a SimKit button and shows them on a toolbar named SimKit. The buttons point at the macros in
// this buttons/ folder on the team share, so a kit update needs no reinstall. Icons are read from buttons/icons/.
// In the STAR-CCM+ window the slots are set through the toolbar actions and take effect at once. In batch the
// setting is written directly to settings.props of this STAR-CCM+ version:
//   Windows  %LOCALAPPDATA%\Siemens\STAR-CCM+ <version>\config\settings.props
//   Linux    ~/.star-<version>/config/settings.props
// Slots with other favourites are replaced only when "Replace my own favourites too" is ticked. Play it again
// after installing a new STAR-CCM+ version.
// Batch: -Dsimkit.buttons=install|remove or SIMKIT_ACTION=installbuttons|removebuttons. Batch never replaces
// other favourites.
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

  /** Toolbar order. Each entry is {file in buttons/ without .java, tooltip}, icon buttons/icons/<file>.png. */
  static final String[][] BUTTONS = {
      {"SimKit_Menu", "SimKit (all tools)"},
      {"SimKit_SaveNewVersion", "SimKit: Save as new run"},
      {"SimKit_RunOnCluster", "SimKit: Run on cluster"},
      {"SimKit_CaseTracker", "SimKit: Tracker"},
      {"SimKit_Calculators", "SimKit: Calculators"},
      {"SimKit_Names", "SimKit: Names"},
      {"SimKit_StandardReports", "SimKit: Reports"},
      {"SimKit_MeshStudy", "SimKit: Mesh study"},
  };
  static final int SLOTS = 10;
  /** Setting key plus slot 0..9. Value "macro file ; icon file ; tooltip", in the order STAR-CCM+ reads. */
  static final String KEY = "MacroPlayFavoriteAction";

  Simulation sim;

  void say(String s) {
    if (sim != null) sim.println("[SimKit] " + s); else System.out.println("[SimKit] " + s);
  }

  /** Macro file of a slot value, "" if the slot is empty. */
  static String macroOf(String value) {
    if (value == null) return "";
    return value.split(";", -1)[0].trim();
  }

  static boolean isSimKitFile(String m) {
    String[] parts = m.trim().replace('\\', '/').split("/");
    return parts.length >= 2 && parts[parts.length - 1].matches("SimKit_[A-Za-z]+\\.java") && parts[parts.length - 2].equals("buttons");
  }

  /** A SimKit button is SimKit_<Name>.java in a folder named buttons, at any location. Also matches slots with the
   *  fields in the order "tooltip ; macro ; icon", so that playing the installer again repairs them. */
  static boolean isSimKit(String value) {
    if (value == null) return false;
    String[] p = value.split(";", -1);
    return isSimKitFile(p[0]) || p.length >= 2 && isSimKitFile(p[1]);
  }

  /** Sets slot i through the toolbar action of STAR-CCM+, as the favourite-macro dialog does, so the button changes
   *  at once and STAR-CCM+ saves the setting. Returns false in batch (no toolbar action) or when a call fails. */
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
          // The button is created on first use and then reloads the stored setting, so create it before setting values.
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

  // ----- SimKit toolbar. STAR-CCM+ (NetBeans) shows one toolbar per folder of <config>/Toolbars. Each button is a
  // link file <action>.shadow that holds the address of the action, as Customize > drag and drop writes it.

  static final String TOOLBAR = "SimKit";
  static final String FAV = "star-coremodule-actions-MacroPlayFavoriteAction";

  static String shadowTarget(int i) {
    return "nbfs://nbhost/SystemFileSystem/Actions/File/Macro/" + FAV + i + ".instance";
  }

  /** Puts these favourite slots on the SimKit toolbar in the given order. An empty list removes the toolbar. In the
   *  window this uses its file system and takes effect at once, otherwise files are written to the config folder. */
  String setToolbar(List<Integer> slots, boolean gui) {
    if (gui) {
      try {
        setToolbarLive(slots);
        return "in the window";
      } catch (Throwable t) {
        say("SimKit toolbar: not set in the window (" + t + "); writing the files instead");
      }
    }
    try {
      return "in " + setToolbarFiles(slots) + " (shown from the next start)";
    } catch (Throwable t) {
      say("SimKit toolbar NOT written: " + t);
      return "NOT written";
    }
  }

  void setToolbarLive(final List<Integer> slots) throws Exception {
    ClassLoader cl = favClass(0).getClassLoader();
    final Class<?> fu = Class.forName("org.openide.filesystems.FileUtil", true, cl);
    final Class<?> fo = Class.forName("org.openide.filesystems.FileObject", true, cl);
    final Object root = fu.getMethod("getConfigFile", String.class).invoke(null, "Toolbars");
    if (root == null) throw new IllegalStateException("no Toolbars folder");
    final Exception[] err = new Exception[1];
    Runnable r = () -> {   // one atomic action, so the toolbar is rebuilt once and never shows a half-written link
      try {
        Object dir = fo.getMethod("getFileObject", String.class).invoke(root, TOOLBAR);
        if (dir != null) {
          for (Object ch : (Object[]) fo.getMethod("getChildren").invoke(dir)) fo.getMethod("delete").invoke(ch);
        }
        if (slots.isEmpty()) {
          if (dir != null) fo.getMethod("delete").invoke(dir);
          return;
        }
        if (dir == null) dir = fo.getMethod("createFolder", String.class).invoke(root, TOOLBAR);
        int pos = 100;
        for (int i : slots) {
          Object f = fo.getMethod("createData", String.class, String.class).invoke(dir, FAV + i, "shadow");
          try (java.io.OutputStream os = (java.io.OutputStream) fo.getMethod("getOutputStream").invoke(f)) {
            os.write(shadowTarget(i).getBytes("UTF-8"));
          }
          fo.getMethod("setAttribute", String.class, Object.class).invoke(f, "position", pos);
          pos += 100;
        }
      } catch (Exception e) {
        err[0] = e;
      }
    };
    fu.getMethod("runAtomicAction", Runnable.class).invoke(null, r);
    if (err[0] != null) throw err[0];
  }

  /** Writes the toolbar as files, for batch or when the file system of the window is not available. Returns the folder. */
  static Path setToolbarFiles(List<Integer> slots) throws Exception {
    java.lang.reflect.Method m = star.base.neo.BaseSettings.class.getDeclaredMethod("getConfigDir");
    m.setAccessible(true);
    Path dir = ((java.io.File) m.invoke(null)).toPath().resolve("Toolbars").resolve(TOOLBAR);
    if (Files.isDirectory(dir)) {
      try (java.nio.file.DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
        for (Path p : ds) Files.delete(p);
      }
    }
    if (slots.isEmpty()) {
      Files.deleteIfExists(dir);
      return dir;
    }
    Files.createDirectories(dir);
    StringBuilder attrs = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE attributes PUBLIC "
        + "\"-//NetBeans//DTD DefaultAttributes 1.0//EN\" \"http://www.netbeans.org/dtds/attributes-1_0.dtd\">\n<attributes version=\"1.0\">\n");
    int pos = 100;
    for (int i : slots) {
      Files.write(dir.resolve(FAV + i + ".shadow"), shadowTarget(i).getBytes("UTF-8"));
      attrs.append("    <fileobject name=\"").append(FAV).append(i).append(".shadow\">\n        <attr name=\"position\" intvalue=\"")
          .append(pos).append("\"/>\n    </fileobject>\n");
      pos += 100;
    }
    Files.write(dir.resolve(".nbattrs"), attrs.append("</attributes>\n").toString().getBytes("UTF-8"));
    return dir;
  }

  static Class<?> favClass(int i) throws ClassNotFoundException {
    try {
      return Class.forName("star.coremodule.actions.MacroPlayFavoriteAction" + i, true, StarSettings.class.getClassLoader());
    } catch (ClassNotFoundException e) {
      return Class.forName("star.coremodule.actions.MacroPlayFavoriteAction" + i, true, Thread.currentThread().getContextClassLoader());
    }
  }

  /** Sets slot i, live in the window or else in the settings. Returns true if the live change took effect. */
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

    List<Integer> own = new ArrayList<>();   // slots with favourites that are not SimKit buttons
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
      replaceOwn = (Boolean) r[2] && !remove;   // remove never clears other favourites
    }

    // Missing button files are skipped. Icons are optional.
    List<String[]> chosen = new ArrayList<>();
    for (int b = 0; b < BUTTONS.length; b++) {
      if (remove || !want[b]) continue;
      Path f = dir.resolve(BUTTONS[b][0] + ".java");
      if (!Files.isRegularFile(f)) { say("not found, skipped: " + f); continue; }
      Path icon = dir.resolve("icons").resolve(BUTTONS[b][0] + ".png");
      chosen.add(new String[] {BUTTONS[b][1].replace(";", ","), f.toString(), Files.isRegularFile(icon) ? icon.toString() : ""});
    }

    // Free slots are empty or hold a SimKit button, plus all other slots when replaceOwn is set.
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
    if (gui) say("favourite macros set " + (live == chosen.size() ? "in the window" : live + " in the window, the rest in the settings"));

    // A favourite macro is shown only on a toolbar that holds a link to it.
    List<Integer> onBar = new ArrayList<>(free.subList(0, chosen.size()));
    String how = setToolbar(onBar, gui);
    say("SimKit toolbar: " + onBar.size() + " button(s), " + how);

    String done = remove ? "SimKit buttons removed (" + cleared + ")" : chosen.size() + " button(s) on the SimKit toolbar"
        + (own.isEmpty() || replaceOwn ? "" : "; your own favourites kept (" + own.size() + ")");
    String restart = remove ? "If they still show, close and reopen STAR-CCM+."
        : how.startsWith("in the window") ? "" : "Close and reopen STAR-CCM+ to see them.";
    say("toolbar buttons: " + done + ". " + restart);
    if (gui) {
      JOptionPane.showMessageDialog(null, remove ? done + ".\n\n" + restart
          : done + ".\n\nThey are on a toolbar named SimKit (View > Toolbars > SimKit if it is hidden; drag it where you like).\n" + restart
          + "\nThey point at the kit on the share, so a kit update needs nothing.\n"
          + "After installing a new STAR-CCM+ version, play this macro again.", "SimKit toolbar buttons", JOptionPane.INFORMATION_MESSAGE);
    }
  }

  /** Shows the install dialog. Returns {remove, buttons ticked, replace own favourites}, or null on cancel. */
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
