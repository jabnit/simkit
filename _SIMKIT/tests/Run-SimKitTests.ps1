<#
.SYNOPSIS
  SimKit 0.3 - automated batch validation on a licensed STAR-CCM+ installation (Windows 10/11).

.DESCRIPTION
  Runs, without any GUI interaction, every check of TESTING.txt that works in batch:
    0. environment and STAR-CCM+ version
    1. test share set-up (copy of _SIMKIT + a ready-made test case ISO-001 built from your sim)
    2. SimKitCalc.java in batch  -> compiles the calculator macro and prints its self-check
    3. (Check removed)
    4. SimKit.java  record       -> set-up record + case card
    7. (optional, -RunSolver)    -> starccm+ -batch run,SimKit.java : short solver run + record + export
  Then it writes  <TestRoot>\results\PASTE_THIS.txt  (paste it into the chat) and a zip with all logs/outputs.
  The interactive tools (case initialisation dialogs, renaming previews, parameter-sync preview, calculator
  window) still need the GUI: use -Interactive at the end, or section C of TESTING.txt.

.PARAMETER Star
  STAR-CCM+ launcher (...\star\bin\starccm+.bat) OR the installation root (e.g. "C:\Program Files\Siemens\19.02.009");
  the script searches the root for star\bin\starccm+.bat.
.PARAMETER Sim
  Optional. A real simulation file to test on (regions, mesh, some reports/plots/scenes); it is COPIED, never
  modified. If omitted, the script first builds a small test case with tests\BuildSimKitTestSim.java
  (starccm+ -new -batch ...) into <TestRoot>\scratch\SimKit_testcase.sim and tests on it.
.PARAMETER LicenseFile
  Optional. Licence file or server (e.g. "C:\licenses\license.dat" or "1999@licserver"); sets CDLMD_LICENSE_FILE
  for the STAR-CCM+ processes started by this script only. The script never reads or prints the licence file;
  its location is masked as <licence> in PASTE_THIS.txt and in the zipped logs.
.PARAMETER StarArgs
  Optional extra launcher arguments, e.g. "-power -podkey XXXXXXXX" or "-licpath 1999@licserver".
.PARAMETER TestRoot
  Test share (default C:\SimKitTest). It is created; existing test outputs are moved to a dated backup folder.
.PARAMETER Np
  Number of processes for the batch runs (default 1).
.PARAMETER RunSolver
  Also run the solver for the stopping criteria set in the copy (use a tiny case or a sim with few steps).
.PARAMETER Interactive
  At the end, open STAR-CCM+ on the test case with the SimKit menu (for the GUI part of TESTING.txt).
.PARAMETER KitSource
  Folder of the delivered _SIMKIT (default: the folder that contains this script's parent).

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File Run-SimKitTests.ps1 -Star "C:\Program Files\Siemens\19.02.009" -Sim "D:\cases\test.sim" -LicenseFile "1999@licserver"
#>
param(
  [Parameter(Mandatory = $true)][string]$Star,
  [string]$Sim = "",
  [string]$LicenseFile = "",
  [string]$StarArgs = "",
  [string]$TestRoot = "C:\SimKitTest",
  [int]$Np = 1,
  [switch]$RunSolver,
  [switch]$Interactive,
  [string]$KitSource = ""
)

$ErrorActionPreference = "Stop"
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"

# ------------------------------------------------------------------ helpers
function Find-Launcher([string]$p) {
  if (Test-Path $p -PathType Leaf) { return (Resolve-Path $p).Path }
  if (-not (Test-Path $p -PathType Container)) { throw "STAR-CCM+ path not found: $p" }
  $hit = Get-ChildItem -Path $p -Recurse -Filter "starccm+.bat" -ErrorAction SilentlyContinue |
         Where-Object { $_.FullName -match "\\star\\bin\\starccm\+\.bat$" } | Select-Object -First 1
  if (-not $hit) { throw "No star\bin\starccm+.bat found under $p" }
  return $hit.FullName
}

$results = $null
function Log([string]$msg) {
  Write-Host $msg
  Add-Content -Path (Join-Path $results "script.log") -Value $msg
}

function Invoke-Star([string]$name, [string[]]$starArgs, [string]$action) {
  $log = Join-Path $results ("{0}.log" -f $name)
  $env:SIMKIT_ACTION = $action
  Log ("--- {0}: starccm+ {1}   (SIMKIT_ACTION='{2}')" -f $name, ($starArgs -join " "), $action)
  $t0 = Get-Date
  & cmd.exe /c "`"$script:launcher`" $($starArgs -join ' ') > `"$log`" 2>&1"
  $rc = $LASTEXITCODE
  $dt = [int]((Get-Date) - $t0).TotalSeconds
  Log ("    exit code {0}, {1} s, log {2}" -f $rc, $dt, $log)
  $env:SIMKIT_ACTION = ""
  return $rc
}

# ------------------------------------------------------------------ 0. environment
if ([string]::IsNullOrEmpty($KitSource)) { $KitSource = Split-Path -Parent $PSScriptRoot }
if (-not (Test-Path (Join-Path $KitSource "SimKit.java"))) { throw "SimKit.java not found in KitSource '$KitSource'" }
if ($Sim -and -not (Test-Path $Sim -PathType Leaf)) { throw "Sim file not found: $Sim" }
$script:launcher = Find-Launcher $Star

if (Test-Path $TestRoot) {
  $bak = "{0}_backup_{1}" -f $TestRoot, $stamp
  Move-Item -Path $TestRoot -Destination $bak
  Write-Host "Existing test share moved to $bak"
}
New-Item -ItemType Directory -Force -Path $TestRoot | Out-Null
$results = Join-Path $TestRoot "results"
New-Item -ItemType Directory -Force -Path $results | Out-Null

Log "SimKit batch validation  $stamp"
Log ("launcher      : {0}" -f $script:launcher)
Log ("test sim      : {0}" -f $(if ($Sim) { "$Sim ({0:N1} MB)" -f ((Get-Item $Sim).Length / 1MB) } else { "built by BuildSimKitTestSim.java" }))
Log ("kit source    : {0}" -f $KitSource)
Log ("test share    : {0}" -f $TestRoot)
Log ("licence       : {0}" -f $(if ($LicenseFile) { "CDLMD_LICENSE_FILE set by script" } else { "not set by script (existing environment)" }))
Log ("extra args    : {0}" -f $StarArgs)
Log ("windows / ps  : {0} / {1}" -f [Environment]::OSVersion.VersionString, $PSVersionTable.PSVersion)

if ($LicenseFile) { $env:CDLMD_LICENSE_FILE = $LicenseFile }
Remove-Item Env:SIMKIT_HOME -ErrorAction SilentlyContinue   # the kit finds its own root
$env:SIMKIT_GUI = ""

$verLog = Join-Path $results "00_version.log"
& cmd.exe /c "`"$script:launcher`" -version > `"$verLog`" 2>&1"
Log ("star version  : {0}" -f ((Get-Content $verLog -ErrorAction SilentlyContinue | Select-String -Pattern "\d+\.\d+\.\d+" | Select-Object -First 1).Line))

# ------------------------------------------------------------------ 1. test share + ready-made case
Copy-Item -Path $KitSource -Destination (Join-Path $TestRoot "_SIMKIT") -Recurse -Force

$common = @()
if ($Np -gt 1) { $common += "-np"; $common += "$Np" }
if ($StarArgs) { $common += $StarArgs }

if (-not $Sim) {
  $scratch = Join-Path $TestRoot "scratch"
  New-Item -ItemType Directory -Force -Path $scratch | Out-Null
  $Sim = Join-Path $scratch "SimKit_testcase.sim"
  $env:SIMKIT_TESTSIM = $Sim
  $builder = Join-Path $TestRoot "_SIMKIT\tests\BuildSimKitTestSim.java"
  $null = Invoke-Star "01_build_testsim" ($common + @("-new", "-batch", "`"$builder`"")) ""
  $env:SIMKIT_TESTSIM = ""
  if (-not (Test-Path $Sim -PathType Leaf)) {
    Log "ERROR: the test sim was not created; see results\01_build_testsim.log (paste its end into the chat)."
    Get-Content (Join-Path $results "01_build_testsim.log") -Tail 60 | Set-Content (Join-Path $results "PASTE_THIS.txt")
    throw "test sim build failed - paste $results\PASTE_THIS.txt"
  }
  Log ("test sim      : built {0} ({1:N1} MB)" -f $Sim, ((Get-Item $Sim).Length / 1MB))
}
$caseId = "ISO-001"
$case = Join-Path $TestRoot "cases\$caseId"
foreach ($d in "sim", "results\csv", "results\img", "record") {
  New-Item -ItemType Directory -Force -Path (Join-Path $case $d) | Out-Null
}
$caseSim = Join-Path $case "sim\${caseId}_v01.sim"
Copy-Item -Path $Sim -Destination $caseSim -Force
@(
  "case_id=$caseId", "description=SimKit batch validation ($stamp)", "configuration=CF cold flow", "parent_case=", "parent_file=",
  "created=$((Get-Date).ToString('yyyy-MM-dd HH:mm:ss'))", "created_by=$env:USERNAME",
  "latest_file=${caseId}_v01.sim", "latest_note=", "last_saved="
) | Set-Content -Path (Join-Path $case "sim\case_identity.txt") -Encoding ASCII
Log "test case     : $caseSim"

$kit = Join-Path $TestRoot "_SIMKIT"
$q = { param($s) "`"$s`"" }

# ------------------------------------------------------------------ 2-5. batch runs
$rc = @{}
$rc["calc"]     = Invoke-Star "02_SimKitCalc_selfcheck" ($common + @("-batch", (& $q (Join-Path $kit "lib\generic\SimKitCalc.java")), (& $q $caseSim))) ""
$rc["record"]   = Invoke-Star "04_SimKit_record"        ($common + @("-batch", (& $q (Join-Path $kit "SimKit.java")), (& $q $caseSim))) "record"
$rc["reports"]  = Invoke-Star "07a_SimKit_reports"      ($common + @("-batch", (& $q (Join-Path $kit "SimKit.java")), (& $q $caseSim))) "reports"
$rc["reports2"] = Invoke-Star "07b_SimKit_reports_again" ($common + @("-batch", (& $q (Join-Path $kit "SimKit.java")), (& $q $caseSim))) "reports"
$rc["meshpar"]  = Invoke-Star "07c_SimKit_meshparams"    ($common + @("-batch", (& $q (Join-Path $kit "SimKit.java")), (& $q $caseSim))) "meshparams"
$rc["scenes"]   = Invoke-Star "07d_SimKit_scenes"        ($common + @("-batch", (& $q (Join-Path $kit "SimKit.java")), (& $q $caseSim))) "scenes"
$rc["meshstudy"] = Invoke-Star "07e_SimKit_meshstudy"    ($common + @("-batch", (& $q (Join-Path $kit "SimKit.java")), (& $q $caseSim))) "meshstudy"
$rc["button"]   = Invoke-Star "08_button_Tracker"       ($common + @("-batch", (& $q (Join-Path $kit "buttons\SimKit_CaseTracker.java")), (& $q $caseSim))) ""

# Toolbar buttons: install, then remove. STAR-CCM+ keeps them in the user's settings.props (which also holds licence
# keys): every settings.props of the user is copied aside first and put back afterwards, and only the
# MacroPlayFavoriteAction lines are ever logged.
$setDirs = @((Join-Path $env:LOCALAPPDATA "Siemens"), (Join-Path $env:LOCALAPPDATA "CD-adapco"), (Join-Path $env:APPDATA "Siemens"))
function Get-StarSettingsFiles { foreach ($d in $setDirs) { if (Test-Path $d) { Get-ChildItem $d -Recurse -Filter "settings.props" -ErrorAction SilentlyContinue } } }
function Log-Buttons([string]$when) {
  foreach ($f in Get-StarSettingsFiles) {
    $b = @(Get-Content $f.FullName -ErrorAction SilentlyContinue | Where-Object { $_ -match "^MacroPlayFavoriteAction[0-9]=" })
    # STAR-CCM+ reads "macro ; icon ; tooltip": a button only shows when the first field is the macro file
    $ok = @($b | Where-Object { ($_ -replace "^[^=]*=", "").Split(";")[0].Trim() -match "\.java$" }).Count
    Log ("    {0}: {1} favourite-macro line(s), {2} with a macro file first (button shown), in {3}" -f $when, $b.Count, $ok, $f.FullName)
    foreach ($l in $b) { Log ("      " + $l) }
    $tb = Join-Path (Split-Path -Parent $f.FullName) "Toolbars\SimKit"
    $links = @(Get-ChildItem $tb -Filter "*.shadow" -ErrorAction SilentlyContinue)
    Log ("    {0}: SimKit toolbar {1}, {2} button link(s)" -f $when, $(if (Test-Path $tb) { "present" } else { "absent" }), $links.Count)
  }
}
$bk = Join-Path $TestRoot "_settings_backup"
New-Item -ItemType Directory -Force $bk | Out-Null
$saved = @{}
$i = 0
$tbSaved = @{}
foreach ($f in Get-StarSettingsFiles) {
  $c = Join-Path $bk ("settings_{0}.props" -f $i); Copy-Item $f.FullName $c -Force; $saved[$f.FullName] = $c
  $tb = Join-Path (Split-Path -Parent $f.FullName) "Toolbars\SimKit"   # the SimKit toolbar the installer makes
  if (Test-Path $tb) { $tc = Join-Path $bk ("toolbar_{0}" -f $i); Copy-Item $tb $tc -Recurse -Force; $tbSaved[$tb] = $tc } else { $tbSaved[$tb] = "" }
  $i++
}
try {
  $rc["buttons"]  = Invoke-Star "08b_buttons_install"   ($common + @("-batch", (& $q (Join-Path $kit "buttons\SimKit_InstallButtons.java")), (& $q $caseSim))) "installbuttons"
  Log-Buttons "after install"
  $rc["buttonsrm"] = Invoke-Star "08c_buttons_remove"   ($common + @("-batch", (& $q (Join-Path $kit "buttons\SimKit_InstallButtons.java")), (& $q $caseSim))) "removebuttons"
  Log-Buttons "after remove"
} finally {
  foreach ($k in $saved.Keys) { Copy-Item $saved[$k] $k -Force }
  foreach ($k in $tbSaved.Keys) {
    Remove-Item $k -Recurse -Force -ErrorAction SilentlyContinue
    if ($tbSaved[$k] -ne "") { Copy-Item $tbSaved[$k] $k -Recurse -Force }
  }
  foreach ($f in Get-StarSettingsFiles) { if (-not $saved.ContainsKey($f.FullName)) { Log ("    new settings file left by the test (not removed): " + $f.FullName) } }
  Remove-Item $bk -Recurse -Force -ErrorAction SilentlyContinue
  Log ("    user STAR-CCM+ settings restored ({0} file(s))" -f $saved.Count)
}
if ($RunSolver) {
  $rc["run"]    = Invoke-Star "09_SimKit_run_record_export" ($common + @("-batch", ("run," + (Join-Path $kit "SimKit.java")), "-batch-report", (& $q $caseSim))) ""
}
$rc["newver"]   = Invoke-Star "10b_SimKit_newversion"    ($common + @("-batch", (& $q (Join-Path $kit "SimKit.java")), (& $q $caseSim))) "newversion"
$rc["tracker"]  = Invoke-Star "11_SimKit_tracker"        ($common + @("-batch", (& $q (Join-Path $kit "SimKit.java")), (& $q $caseSim))) "tracker"
$rc["opensim"]  = Invoke-Star "11b_SimKit_opensim"       ($common + @("-batch", (& $q (Join-Path $kit "SimKit.java")), (& $q $caseSim))) "opensim"
$rc["study"]    = Invoke-Star "11c_SimKit_study"         ($common + @("-batch", (& $q (Join-Path $kit "SimKit.java")), (& $q $caseSim))) "studytest"

# ------------------------------------------------------------------ 6. PASTE_THIS.txt
$paste = Join-Path $results "PASTE_THIS.txt"
$out = New-Object System.Collections.Generic.List[string]
$out.Add("===== SIMKIT BATCH VALIDATION - paste everything below into the chat =====")
$out.Add("date $stamp | launcher $script:launcher")
$out.Add("star version: " + ((Get-Content $verLog -ErrorAction SilentlyContinue | Select-String -Pattern "STAR-CCM\+|Simcenter" | Select-Object -First 1).Line))
$out.Add("exit codes: " + (($rc.GetEnumerator() | Sort-Object Name | ForEach-Object { "$($_.Name)=$($_.Value)" }) -join "  "))
$out.Add("")

$patterns = @(
  "\[BuildTestSim\]", "error:(?!.*deprecat)", "Exception", "Error:", "\[SimKit\]", "\[SimKitCalc\]", "n/a \(", "FAIL", "WARN", "PASS",
  "batch:", "licen", "License", "Could not", "cannot", "Self-check", "GCI", "Gamma", "X=", "p=", "parameter sync",
  "written", "wrote", "saved", "exported", "set-up record", "case card", "list of changes", "case list",
  "plots exported", "scenes exported", "report values", "=> ", "names", "expected", "button", "kit ", "created", "monitors", "not proposed", "already exists", "boundary types", "report\(s\)", "READY", "averaged", "key results", "solution", "residuals", "mass balance", "monitors stable", "y\+ vs", "p_mesh", "follow", "Relative to the base", "mesh size", "boundary values", "scene", "case report", "cells", "case tracker", "new version", "forked", "mesh level", "mesh study", "cells", "toolbar buttons", "favourite", "study", "run R", "submit", "separator", "run label", "open run"
)
$rx = ($patterns | ForEach-Object { $_ }) -join "|"
foreach ($f in Get-ChildItem $results -Filter "0*.log" | Sort-Object Name) {
  $lines = Get-Content $f.FullName -ErrorAction SilentlyContinue
  $out.Add("----- $($f.Name)  ($($lines.Count) lines) -----")
  $hits = $lines | Select-String -Pattern $rx | ForEach-Object { $_.Line.Trim() } | Where-Object { $_ -ne "" }
  $uniq = $hits | Select-Object -Unique
  if ($uniq.Count -gt 80) { $uniq = ($uniq | Select-Object -First 70) + "... ($($uniq.Count - 70) more matching lines in the log)" }
  if (-not $uniq) { $uniq = ($lines | Select-Object -Last 25) }
  foreach ($l in $uniq) { $out.Add($l) }
  $out.Add("")
}

# n/a summary by [Unn] tag (the key to the next fixes)
$all = Get-ChildItem $results -Filter "0*.log" | ForEach-Object { Get-Content $_.FullName -ErrorAction SilentlyContinue }
$tags = $all | Where-Object { $_ -match "n/a|FAIL|Exception|error:" -and $_ -notmatch "warning:" } |
        Select-String -Pattern "\[U\d+\]" -AllMatches | ForEach-Object { $_.Matches } | ForEach-Object { $_.Value }
$out.Add("----- [Unn] tags seen with n/a or errors -----")
if ($tags) { $tags | Group-Object | Sort-Object Name | ForEach-Object { $out.Add(("{0} x{1}" -f $_.Name, $_.Count)) } } else { $out.Add("(none)") }
$out.Add("")
$dep = $all | Where-Object { $_ -match "warning: \[deprecation\]" } | ForEach-Object { ($_ -replace "^.*warning: \[deprecation\] ", "") } | Select-Object -Unique
$out.Add("----- deprecation warnings (compile OK; API deviation to note vs 2402) -----")
if ($dep) { foreach ($d in $dep) { $out.Add($d) } } else { $out.Add("(none)") }
$out.Add("")

# produced files
$out.Add("----- files produced in the test case -----")
Get-ChildItem $case -Recurse -File | Where-Object { $_.Extension -ne ".sim" } |
  ForEach-Object { $out.Add(("{0}  {1:N0} B" -f $_.FullName.Substring($case.Length + 1), $_.Length)) }
$out.Add("")

# heads of the key audit outputs
foreach ($pat in "setup_*.csv") {
  $f = Get-ChildItem (Join-Path $case "record") -Filter $pat -ErrorAction SilentlyContinue | Sort-Object LastWriteTime | Select-Object -Last 1
  if ($f) {
    $out.Add("----- $($f.Name) (first 60 lines; n/a rows first) -----")
    $c = Get-Content $f.FullName
    $na = $c | Where-Object { $_ -match "n/a" } | Select-Object -First 30
    foreach ($l in $na) { $out.Add($l) }
    foreach ($l in ($c | Select-Object -First (60 - [Math]::Min(30, @($na).Count)))) { $out.Add($l) }
    $out.Add("")
  }
}
$out.Add("===== END =====")

# ------------------------------------------------------------------ privacy: never expose the licence
# The licence file content is never read or printed by this script. Its location (and any path in a folder
# named 'confidential') is masked in PASTE_THIS.txt and in the zipped logs; licence FEATURE/SIGN lines that a
# tool might echo are dropped.
$secret = @()
if ($LicenseFile) {
  $secret += $LicenseFile
  try { $d = Split-Path -Parent $LicenseFile; if ($d) { $secret += $d } } catch { }
}
function Redact([string]$s) {
  if ($null -eq $s) { return $s }
  foreach ($x in $secret) { if ($x) { $s = $s.Replace($x, "<licence>") } }
  $s = [regex]::Replace($s, "(?i)[A-Z]:\\[^\s""']*\\confidential(\\[^\s""']*)?", "<confidential path>")
  if ($s -match "(?i)(^|\s)(FEATURE|INCREMENT|SERVER|VENDOR|USE_SERVER)\s|SIGN=|vendor_info=|HOSTID=") { return "<licence line removed>" }
  return $s
}
$out = $out | ForEach-Object { Redact $_ }
$out | Set-Content -Path $paste -Encoding UTF8

# zip everything (logs + case outputs, without .sim files)
$zip = Join-Path $TestRoot "simkit_test_results_$stamp.zip"
$tmp = Join-Path $env:TEMP "simkit_zip_$stamp"
New-Item -ItemType Directory -Force -Path $tmp | Out-Null
Copy-Item $results -Destination (Join-Path $tmp "results") -Recurse
Copy-Item (Join-Path $TestRoot "cases") -Destination (Join-Path $tmp "cases") -Recurse
Get-ChildItem $tmp -Recurse -Include "*.sim", "*.sim~" | Remove-Item -Force
Get-ChildItem $tmp -Recurse -File -Include "*.log", "*.txt", "*.csv", "*.html" | ForEach-Object {
  $lines = Get-Content $_.FullName -ErrorAction SilentlyContinue
  if ($lines) { ($lines | ForEach-Object { Redact $_ }) | Set-Content -Path $_.FullName -Encoding UTF8 }
}
Compress-Archive -Path (Join-Path $tmp "*") -DestinationPath $zip -Force
Remove-Item $tmp -Recurse -Force

Write-Host ""
Write-Host "==================================================================="
Write-Host " Done. Paste the content of:"
Write-Host "   $paste"
Write-Host " Full logs and outputs (no .sim files): $zip"
Write-Host "==================================================================="
Get-Content $paste | Select-Object -First 40 | ForEach-Object { Write-Host $_ }
Write-Host "   ... (see the file for the rest)"

# ------------------------------------------------------------------ optional GUI session
if ($Interactive) {
  $env:SIMKIT_ACTION = ""
  Write-Host "Opening STAR-CCM+ with the SimKit menu on the test case (section C of TESTING.txt)..."
  & cmd.exe /c "`"$script:launcher`" $($common -join ' ') -macro `"$(Join-Path $kit 'SimKit.java')`" `"$caseSim`""
}
