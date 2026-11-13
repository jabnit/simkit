// SimKit: Save as new (next version or a new case, in one window; not a case yet: starts it as v01)
//
// SimKit 0.3 toolbar button. Play it from the buttons/ folder of the SimKit root on the team share (do not copy
// it elsewhere): it finds the root from its own location (STAR-CCM+ resolvePath("..")) and plays SimKit.java
// from there, running that tool directly (no menu). No environment variable or path to set.
package macro;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import star.common.*;

public class SimKit_SaveNewVersion extends StarMacro {

  static final int TOOL = 2;
  /** Macro to play, relative to the SimKit root ('/' on every OS). */
  static final String MACRO = "SimKit.java";

  @Override
  public void execute() {
    Simulation sim = getActiveSimulation();
    Path root = Paths.get(resolvePath("..")).toAbsolutePath().normalize();   // this file is <root>/buttons/
    Path f = root;
    for (String part : MACRO.split("/")) f = f.resolve(part);
    if (!Files.isRegularFile(f)) {
      sim.println("[SimKit] not found: " + f + " -- play this button from the buttons folder of the SimKit root");
      return;
    }
    if (TOOL > 0) System.setProperty("simkit.tool", String.valueOf(TOOL));
    try {
      new StarScript(getActiveRootObject(), f.toFile()).play();
    } finally {
      System.clearProperty("simkit.tool");
    }
  }
}
