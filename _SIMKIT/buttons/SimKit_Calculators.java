// SimKit toolbar button: calculators for y+, Reynolds number, time step and fluid properties.
//
// Play it from <root>/buttons/ on the team share, not from a copy. It finds the SimKit root with
// resolvePath("..") and plays lib/generic/SimKitCalc.java from there.
package macro;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import star.common.*;

public class SimKit_Calculators extends StarMacro {

  static final String TOOL = "";
  /** Macro to play, relative to the SimKit root, with '/' as separator on every OS. */
  static final String MACRO = "lib/generic/SimKitCalc.java";

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
    if (!TOOL.isEmpty()) System.setProperty("simkit.tool", TOOL);
    try {
      new StarScript(getActiveRootObject(), f.toFile()).play();
    } finally {
      System.clearProperty("simkit.tool");
    }
  }
}
