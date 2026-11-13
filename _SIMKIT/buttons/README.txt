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

  SimKit_InstallButtons.java   puts the buttons above on the user's Macro toolbar (play once per user)
  icons/                       16 x 16 icon of each button

Set-up (each user, once per STAR-CCM+ version): File > Macro > Play Macro > <SimKit root>/buttons/SimKit_InstallButtons.java.
Tick the buttons wanted (all by default), Install. STAR-CCM+ keeps 10 favourite-macro buttons per user; the installer
uses only free slots and slots already holding a SimKit button, so the user's own favourites are kept (unless
"Replace my own favourites too" is ticked). Play it again to update or remove the SimKit buttons. If the buttons do
not show at once, close and reopen STAR-CCM+. The setting is STAR-CCM+'s own, saved for that user and version in
  Windows  %LOCALAPPDATA%\Siemens\STAR-CCM+ <version>\config\settings.props
  Linux    ~/.star-<version>/config/settings.props
(keys MacroPlayFavoriteAction0..9 = "tooltip ; macro file ; icon file").
The buttons point at THESE files on the share (<SimKit root>/buttons/...). Do not copy them elsewhere: each button
finds the root from its own location and always runs the current kit. No environment variable and no path to set.
Cases are not stored in the SimKit root: the first Save new version of a sim asks the user for the folder.
