SimKit toolbar buttons
======================
One small macro per button. Each one finds the SimKit root from its own location (the folder above buttons/)
and runs the current version of the tool, so the buttons never need updating when the kit changes.

  SimKit_Menu.java             the SimKit window (all tools)
  SimKit_SaveNewVersion.java   Save new version (next version, or a new case; creates the case the first time)
  SimKit_Check.java            Check
  SimKit_ExportResults.java    Export results
  SimKit_StandardReports.java  Standard reports
  SimKit_MeshStudy.java        Mesh study (parametrize only, or one case per mesh level)
  SimKit_StandardScenes.java   Standard scenes
  SimKit_CaseReport.java       Case report
  SimKit_CaseTracker.java      Case tracker
  SimKit_Calculators.java      Calculators

Set-up: add the buttons you want to the users' STAR-CCM+ macro toolbar, pointing at THESE files on the share
(<SimKit root>/buttons/...). Do not copy them elsewhere. No environment variable and no path need to be set.
Cases are not stored in the SimKit root: the first Save new version of a sim asks the user for the folder.
