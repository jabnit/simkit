// Builds a small test simulation that exercises the SimKit tools. Test use only.
//
// Starting from an empty simulation it creates an air channel with a named part, split part surfaces, a fluid
// region, a physics continuum (unsteady, ideal gas, segregated flow and energy, SST k-omega), an automated mesh
// operation with non-default values and a surface control, a mass-flow inlet, pressure outlets and walls, a global
// parameter, reports (one left with its default name), monitors, plots, a plane section, a scene and stopping
// criteria for a short run. The volume mesh is generated.
// Run: starccm+ -new -batch BuildSimKitTestSim.java. The sim is written to %SIMKIT_TESTSIM%, else to
// <session dir>/SimKit_testcase.sim. Run-SimKitTests.ps1 runs this when -Sim is omitted.
// Calls whose name or signature differ between releases go through the reflection helper R, so the file compiles
// in 2402 and later. A failed step is logged as "FAIL <step>: <reason>" and the build continues. The log is
// printed and written next to the sim as <sim>.build.log.
package macro;

import java.io.File;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import star.base.neo.DoubleVector;
import star.base.neo.NeoObjectVector;
import star.base.neo.StringVector;
import star.common.Simulation;
import star.common.StarMacro;

public class BuildSimKitTestSim extends StarMacro {

  static final double LX = 1.0, LY = 0.2, LZ = 0.2;   // channel size [m]
  static final double BASE = 0.025;                   // mesh base size [m], not the default value

  Simulation sim;
  /** SIMKIT_BUILD_MODE=factory keeps every catalogue setting at its STAR-CCM+ default, for "Snapshot baseline
   *  > factory defaults". Boundary types are then set by surface order instead of by centroid, and no mesh is made. */
  final boolean factory = "factory".equalsIgnoreCase(String.valueOf(System.getenv("SIMKIT_BUILD_MODE")));
  final List<String> log = new ArrayList<>();
  int nOk = 0, nFail = 0;
  Object units_m, part, continuum, region, autoMesh;

  interface Step { void run() throws Exception; }

  void step(String name, Step s) {
    try {
      s.run();
      nOk++;
      say("OK   " + name);
    } catch (Throwable t) {
      Throwable c = (t instanceof InvocationTargetException && t.getCause() != null) ? t.getCause() : t;
      nFail++;
      say("FAIL " + name + ": " + c.getClass().getSimpleName() + ": " + c.getMessage());
    }
  }

  void say(String s) {
    log.add(s);
    try { sim.println("[BuildTestSim] " + s); } catch (Throwable t) { System.out.println("[BuildTestSim] " + s); }
  }

  @Override
  public void execute() {
    sim = getActiveSimulation();
    String out = System.getenv("SIMKIT_TESTSIM");
    if (out == null || out.trim().isEmpty()) out = System.getProperty("simkit.testsim");
    if (out == null || out.trim().isEmpty()) out = new File(sim.getSessionDir(), "SimKit_testcase.sim").getPath();
    final String outPath = out;
    say("SimKit test-sim builder | target " + outPath + (factory ? " | FACTORY mode (defaults only)" : ""));
    step("star version", () -> say("     release " + R.call(R.call(sim, "getStarVersion"), "getString", "ReleaseNumber")));

    step("units m", () -> units_m = R.call(R.call(sim, "getUnitsManager"), "getObject", "m"));

    // ---------------------------------------------------------------- global parameter
    step("parameter p_inletMdot", () -> {
      Object gpm = R.call(sim, "get", R.cls("star.common.GlobalParameterManager"));
      Object p = R.call(gpm, "createGlobalParameter", R.cls("star.common.ScalarGlobalParameter"), "Scalar");
      R.call(p, "setPresentationName", "p_inletMdot");
      Object q = R.call(p, "getQuantity");
      R.call(q, "setValue", 0.02);   // dimensionless on purpose, dimensions are tested in ISO_parameters
    });

    // ---------------------------------------------------------------- geometry
    step("block part ENV_box", () -> {
      Object factory = R.call(sim, "get", R.cls("star.meshing.MeshPartFactory"));
      Object spm = R.call(sim, "get", R.cls("star.common.SimulationPartManager"));
      part = R.call(factory, "createNewBlockPart", spm);
      try { R.call(part, "setDoNotRetessellate", true); } catch (Throwable ignored) { }
      R.call(R.call(part, "getCorner1"), "setCoordinate", units_m, units_m, units_m, new DoubleVector(new double[] {0, 0, 0}));
      R.call(R.call(part, "getCorner2"), "setCoordinate", units_m, units_m, units_m, new DoubleVector(new double[] {LX, LY, LZ}));
      R.call(part, "rebuildSimpleShapePart");
      try { R.call(part, "setDoNotRetessellate", false); } catch (Throwable ignored) { }
      R.call(part, "setPresentationName", "ENV_box");
    });
    step("split part surfaces by angle (6 faces)", () -> {
      Collection<?> ps = (Collection<?>) R.call(part, "getPartSurfaces");
      R.call(R.call(part, "getPartSurfaceManager"), "splitPartSurfacesByAngle", new NeoObjectVector(ps.toArray()), 89.0);
      int i = 1;
      for (Object s : (Collection<?>) R.call(part, "getPartSurfaces")) R.call(s, "setPresentationName", "WALL_face" + (i++));
      say("     part surfaces: " + names((Collection<?>) R.call(part, "getPartSurfaces")));
    });

    // ---------------------------------------------------------------- physics continuum
    step("continuum PH_COLD", () -> {
      continuum = R.call(R.call(sim, "getContinuumManager"), "createContinuum", R.cls("star.common.PhysicsContinuum"));
      R.call(continuum, "setPresentationName", "PH_COLD");
    });
    String[] models = {
        "star.metrics.ThreeDimensionalModel", "star.common.ImplicitUnsteadyModel", "star.material.SingleComponentGasModel",
        "star.segregatedflow.SegregatedFlowModel", "star.flow.IdealGasModel", "star.segregatedenergy.SegregatedFluidEnthalpyModel",
        "star.turbulence.TurbulentModel", "star.turbulence.RansTurbulenceModel", "star.kwturb.KOmegaTurbulence",
        "star.kwturb.SstKwTurbModel", "star.kwturb.KwAllYplusWallTreatment"};
    for (String m : models) step("model " + m.substring(m.lastIndexOf('.') + 1), () -> R.call(continuum, "enable", R.cls(m)));

    // ---------------------------------------------------------------- region
    step("region F_air (one boundary per part surface)", () -> {
      Object rm = R.call(sim, "getRegionManager");
      NeoObjectVector parts = new NeoObjectVector(new Object[] {part});
      Object[][] variants = {
          {parts, "OneRegionPerPart", null, "OneBoundaryPerPartSurface", null, "OneFeatureCurve", null, "BOUNDARY", "OneEdgeBoundaryPerPart", null},
          {parts, "OneRegionPerPart", null, "OneBoundaryPerPartSurface", null, "OneFeatureCurve", null, true},
          {parts, "OneRegionPerPart", null, "OneBoundaryPerPartSurface", null, "OneFeatureCurve", null, "BOUNDARY"}};
      R.callFirst(rm, "newRegionsFromParts", variants);
      for (Object r : (Collection<?>) R.call(rm, "getRegions")) region = r;
      R.call(region, "setPresentationName", "F_air");
      try { R.call(region, "setPhysicsContinuum", continuum); } catch (Throwable t) { say("     continuum assignment: " + t); }
    });

    // ---------------------------------------------------------------- automated mesh operation
    step("automated mesh MO_30_autoMesh", () -> {
      Object mom = R.call(sim, "get", R.cls("star.meshing.MeshOperationManager"));
      StringVector meshers = new StringVector(new String[] {
          "star.resurfacer.ResurfacerAutoMesher", "star.dualmesher.DualAutoMesher", "star.prismmesher.PrismAutoMesher"});
      autoMesh = R.call(mom, "createAutoMeshOperation", meshers, new NeoObjectVector(new Object[] {part}));
      R.call(autoMesh, "setPresentationName", "MO_30_autoMesh");
    });
    if (!factory) step("mesh default values (base size, prism layers)", () -> {
      Object dv = R.call(autoMesh, "getDefaultValues");
      R.call(R.call(dv, "get", R.cls("star.meshing.BaseSize")), "setValueAndUnits", BASE, units_m);
      Object npl = R.call(dv, "get", R.cls("star.prismmesher.NumPrismLayers"));
      try { R.call(R.call(R.call(npl, "getNumLayersValue"), "getQuantity"), "setValue", 4.0); }
      catch (Throwable t) { R.call(npl, "setNumLayers", 4); }
    });
    step("custom surface control MC_face1Refine (target 50 %)", () -> {
      Object ctrl = R.call(R.call(autoMesh, "getCustomMeshControls"), "createSurfaceControl");
      R.call(ctrl, "setPresentationName", "MC_face1Refine");
      Object first = ((Collection<?>) R.call(part, "getPartSurfaces")).iterator().next();
      R.call(R.call(ctrl, "getGeometryObjects"), "setObjects", new NeoObjectVector(new Object[] {first}));
      if (factory) return;   // keep the control at its defaults
      Object opt = R.call(R.call(ctrl, "getCustomConditions"), "get", R.cls("star.meshing.PartsTargetSurfaceSizeOption"));
      Object custom = R.enumConst("star.meshing.PartsTargetSurfaceSizeOption$Type", "CUSTOM");
      R.call(opt, "setSelected", custom);
      Object ts = R.call(R.call(ctrl, "getCustomValues"), "get", R.cls("star.meshing.PartsTargetSurfaceSize"));
      R.call(R.call(ts, "getRelativeSizeScalar"), "setValue", 50.0);
    });
    if (factory) step("boundary types by surface order (factory mode, no mesh)", () -> {
      Object ctm = R.call(sim, "get", R.cls("star.common.ConditionTypeManager"));
      String[] types = {"star.common.MassFlowBoundary", "star.common.PressureBoundary"};
      int i = 0;
      for (Object b : (Collection<?>) R.call(R.call(region, "getBoundaryManager"), "getBoundaries")) {
        if (i < types.length) R.call(b, "setBoundaryType", R.call(ctm, "get", R.cls(types[i])));
        i++;
      }
    });
    if (!factory) step("generate the mesh", () -> R.call(autoMesh, "execute"));

    // ---------------------------------------------------------------- boundary types
    if (!factory) step("boundary types (x-min inlet, x-max outlet, top open, others walls)", () -> {
      Object ffm = R.call(sim, "getFieldFunctionManager");
      Object centroid = R.call(ffm, "getFunction", "Centroid");
      Object rmgr = R.call(sim, "getReportManager");
      Object ctm = R.call(sim, "get", R.cls("star.common.ConditionTypeManager"));
      for (Object b : (Collection<?>) R.call(R.call(region, "getBoundaryManager"), "getBoundaries")) {
        double[] c = new double[3];
        for (int k = 0; k < 3; k++) {
          Object rep = R.call(rmgr, "createReport", R.cls("star.base.report.AreaAverageReport"));
          R.call(rep, "setFieldFunction", R.call(centroid, "getComponentFunction", k));
          R.call(R.call(rep, "getParts"), "setObjects", new NeoObjectVector(new Object[] {b}));
          c[k] = ((Number) R.call(rep, "getReportMonitorValue")).doubleValue();
          try { R.call(rmgr, "removeObjects", new NeoObjectVector(new Object[] {rep})); } catch (Throwable ignored) { }
        }
        String surf, type;
        if (c[0] < 0.01 * LX) { surf = "IN_air"; type = "star.common.MassFlowBoundary"; }
        else if (c[0] > 0.99 * LX) { surf = "OUT_air"; type = "star.common.PressureBoundary"; }
        else if (c[1] > 0.99 * LY) { surf = "OPEN_top"; type = "star.common.PressureBoundary"; }
        else continue;   // stays a wall (WALL_faceN)
        R.call(b, "setBoundaryType", R.call(ctm, "get", R.cls(type)));
        String old = String.valueOf(R.call(b, "getPresentationName"));
        R.call(b, "setPresentationName", "ENV_box." + surf);
        for (Object s : (Collection<?>) R.call(part, "getPartSurfaces")) {
          if (old.endsWith(String.valueOf(R.call(s, "getPresentationName")))) R.call(s, "setPresentationName", surf);
        }
        say(String.format("     %s -> %s (centroid %.3f %.3f %.3f)", old, surf, c[0], c[1], c[2]));
      }
    });
    if (!factory) step("inlet mass flow 0.02 kg/s", () -> {
      for (Object b : (Collection<?>) R.call(R.call(region, "getBoundaryManager"), "getBoundaries")) {
        if (!String.valueOf(R.call(b, "getPresentationName")).endsWith("IN_air")) continue;
        Object prof = R.call(R.call(b, "getValues"), "get", R.cls("star.flow.MassFlowRateProfile"));
        Object meth = R.call(prof, "getMethod", R.cls("star.common.ConstantScalarProfileMethod"));
        R.call(R.call(meth, "getQuantity"), "setValue", 0.02);
      }
    });

    // ---------------------------------------------------------------- solver and stopping criteria
    if (!factory) step("time step 1e-3 s", () -> {
      Object solver = R.call(R.call(sim, "getSolverManager"), "getSolver", R.cls("star.common.ImplicitUnsteadySolver"));
      R.call(R.call(solver, "getTimeStep"), "setValue", 1.0e-3);
    });
    if (!factory) step("stopping criteria (inner 5, time 0.01 s, steps 20)", () -> {
      for (Object sc : (Collection<?>) R.call(R.call(sim, "getSolverStoppingCriterionManager"), "getObjects")) {
        String cn = sc.getClass().getSimpleName();
        if (cn.equals("InnerIterationStoppingCriterion")) {
          try { R.call(R.call(R.call(sc, "getMaxIterations"), "getQuantity"), "setValue", 5.0); }
          catch (Throwable t) { R.call(sc, "setMaximumNumberInnerIterations", 5); }
        } else if (cn.equals("PhysicalTimeStoppingCriterion")) {
          R.call(R.call(sc, "getMaximumTime"), "setValue", 0.01);
        } else if (cn.equals("StepStoppingCriterion")) {
          try { R.call(R.call(R.call(sc, "getMaximumNumberStepsObject"), "getQuantity"), "setValue", 20.0); }
          catch (Throwable t) { R.call(sc, "setMaximumNumberSteps", 20); }
        }
      }
    });

    // ---------------------------------------------------------------- reports, monitors, plots
    final List<Object> reports = new ArrayList<>();
    if (!factory) step("report r_mdot_in_air (mass flow)", () -> {
      Object rep = R.call(R.call(sim, "getReportManager"), "createReport", R.cls("star.flow.MassFlowReport"));
      R.call(rep, "setPresentationName", "r_mdot_in_air");
      R.call(R.call(rep, "getParts"), "setObjects", new NeoObjectVector(boundariesEnding("IN_air").toArray()));
      reports.add(rep);
    });
    if (!factory) step("report r_T_out_avg (area average temperature)", () -> {
      Object rep = R.call(R.call(sim, "getReportManager"), "createReport", R.cls("star.base.report.AreaAverageReport"));
      R.call(rep, "setPresentationName", "r_T_out_avg");
      R.call(rep, "setFieldFunction", R.call(R.call(sim, "getFieldFunctionManager"), "getFunction", "Temperature"));
      R.call(R.call(rep, "getParts"), "setObjects", new NeoObjectVector(boundariesEnding("OUT_air").toArray()));
      reports.add(rep);
    });
    step("report left with a DEFAULT name (lint test)", () -> {
      Object rep = R.call(R.call(sim, "getReportManager"), "createReport", R.cls("star.base.report.MaxReport"));
      R.call(rep, "setFieldFunction", R.call(R.call(sim, "getFieldFunctionManager"), "getFunction", "Temperature"));
      R.call(R.call(rep, "getParts"), "setObjects", new NeoObjectVector(new Object[] {region}));
      say("     default-named report: " + R.call(rep, "getPresentationName"));
    });
    step("monitors and plots from reports (default names -> Auto-name test)", () ->
        R.call(R.call(sim, "getMonitorManager"), "createMonitorAndPlot", new NeoObjectVector(reports.toArray()), true, "%1$s Plot"));

    // ---------------------------------------------------------------- derived part and scene
    step("plane section dp_plane_midSide", () -> {
      Object pm = R.call(sim, "getPartManager");
      Object[][] variants = {
          {new NeoObjectVector(new Object[] {region}), new DoubleVector(new double[] {0, 0, 1}),
              new DoubleVector(new double[] {LX / 2, LY / 2, LZ / 2}), 0, 1, new DoubleVector(new double[] {0.0}), null},
          {new NeoObjectVector(new Object[] {region}), new DoubleVector(new double[] {0, 0, 1}),
              new DoubleVector(new double[] {LX / 2, LY / 2, LZ / 2}), 0, 1, new DoubleVector(new double[] {0.0})}};
      Object plane = R.callFirst(pm, "createImplicitPart", variants);
      R.call(plane, "setPresentationName", "dp_plane_midSide");
    });
    step("scene sc_T_midSide", () -> {
      Object smgr = R.call(sim, "getSceneManager");
      Object made = R.call(smgr, "createScalarScene", "Scalar Scene", "Outline", "Scalar");   // returns void in 2604
      Object sc = made;
      if (sc == null) for (Object s : (Collection<?>) R.call(smgr, "getScenes")) sc = s;     // the last scene is the new one
      R.call(sc, "setPresentationName", "sc_T_midSide");
    });

    // ---------------------------------------------------------------- save
    step("save " + outPath, () -> {
      new File(outPath).getAbsoluteFile().getParentFile().mkdirs();
      sim.saveState(outPath);
    });
    say(String.format("SUMMARY: %d steps OK, %d failed -> %s", nOk, nFail, outPath));
    try (PrintWriter w = new PrintWriter(new File(outPath + ".build.log"), "UTF-8")) {
      for (String s : log) w.println(s);
    } catch (Throwable t) {
      say("could not write the build log: " + t);
    }
  }

  List<Object> boundariesEnding(String suffix) throws Exception {
    List<Object> l = new ArrayList<>();
    for (Object b : (Collection<?>) R.call(R.call(region, "getBoundaryManager"), "getBoundaries")) {
      if (String.valueOf(R.call(b, "getPresentationName")).endsWith(suffix)) l.add(b);
    }
    if (l.isEmpty()) throw new IllegalStateException("no boundary ending with " + suffix);
    return l;
  }

  static String names(Collection<?> objs) throws Exception {
    StringBuilder sb = new StringBuilder();
    for (Object o : objs) sb.append(sb.length() > 0 ? ", " : "").append(R.call(o, "getPresentationName"));
    return sb.toString();
  }

  // ==================================================================== reflection helper
  /** Calls public methods by name, choosing the overload whose parameters accept the given arguments
   *  (numbers are converted to the primitive type, strings to enum constants). Compiles in any release. */
  static final class R {
    static Class<?> cls(String n) throws ClassNotFoundException {
      try { return Class.forName(n); }
      catch (ClassNotFoundException e) { return Class.forName(n, true, Thread.currentThread().getContextClassLoader()); }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static Object enumConst(String cls, String name) throws ClassNotFoundException {
      return Enum.valueOf((Class<? extends Enum>) cls(cls), name);
    }

    static Object call(Object target, String name, Object... args) throws Exception {
      Throwable last = null;
      for (Method m : target.getClass().getMethods()) {
        if (!m.getName().equals(name) || m.getParameterCount() != args.length || Modifier.isStatic(m.getModifiers())) continue;
        Object[] conv = coerce(m.getParameterTypes(), args);
        if (conv == null) continue;
        try {
          m.setAccessible(true);
          return m.invoke(target, conv);
        } catch (InvocationTargetException e) {
          last = e.getCause();
        } catch (IllegalAccessException | IllegalArgumentException e) {
          last = e;
        }
      }
      if (last instanceof Exception) throw (Exception) last;
      if (last != null) throw new Exception(last);
      throw new NoSuchMethodException(target.getClass().getSimpleName() + "." + name + "/" + args.length);
    }

    /** Tries each argument list in turn; returns the first successful result. */
    static Object callFirst(Object target, String name, Object[][] variants) throws Exception {
      Exception last = null;
      for (Object[] v : variants) {
        try { return call(target, name, v); } catch (Exception e) { last = e; }
      }
      throw last;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static Object[] coerce(Class<?>[] types, Object[] args) {
      Object[] out = new Object[args.length];
      for (int i = 0; i < args.length; i++) {
        Class<?> t = types[i];
        Object a = args[i];
        if (a == null) {
          if (t.isPrimitive()) return null;
          out[i] = null;
        } else if (t.isInstance(a)) {
          out[i] = a;
        } else if ((t == double.class || t == Double.class) && a instanceof Number) {
          out[i] = ((Number) a).doubleValue();
        } else if ((t == int.class || t == Integer.class) && a instanceof Number) {
          out[i] = ((Number) a).intValue();
        } else if ((t == long.class || t == Long.class) && a instanceof Number) {
          out[i] = ((Number) a).longValue();
        } else if ((t == boolean.class || t == Boolean.class) && a instanceof Boolean) {
          out[i] = a;
        } else if (t.isEnum() && a instanceof String) {
          try { out[i] = Enum.valueOf((Class<? extends Enum>) t, (String) a); } catch (IllegalArgumentException e) { return null; }
        } else {
          return null;
        }
      }
      return out;
    }
  }
}
