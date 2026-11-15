# SimKit: team helper for STAR-CCM+ projects

SimKit is a set of STAR-CCM+ Java macros that keeps the native STAR-CCM+ workflow and adds a few
team tools on top of it: projects with numbered, named runs (one folder per run, the run number stored
inside the simulation so cluster copies stay traceable), Run on cluster (bsub), a tracker with a family
tree, standard reports and scenes, a mesh study helper and engineering calculators.

> **Status (2026-11-12): version 0.3.** Batch-validated on STAR-CCM+ 2606.0002 (21.04.009, Windows).
> The GUI test, STAR-CCM+ 2402 and Linux (RHEL 7/9) runs are still pending (see `_SIMKIT/TESTING.txt`).
> The full 0.2.1 kit (tags, groups, parameter sync, register script, batch-script writer) is kept at the
> git tag `v0.2.1` for a later update.

## Repository layout

| Path | Content |
|---|---|
| `_SIMKIT/` | **The kit.** Copy this folder as-is to the team share. Its inner layout must be kept: every macro finds the kit root from its own location. |
| `_SIMKIT/SimKit.java` | The macro users play: Save as new run, Run on cluster, Tracker and the other tools. |
| `_SIMKIT/buttons/` | One small macro per toolbar button, their icons and `SimKit_InstallButtons.java` (see `_SIMKIT/buttons/README.txt`). |
| `_SIMKIT/config/` | Naming prefixes, report names, standard reports and cluster settings (CSV, edited by the owner). |
| `_SIMKIT/lib/generic/` | `SimKitCalc.java` (Calculators window) and its property tables in `data/`. |
| `_SIMKIT/tests/` | Batch validation runners (Windows PowerShell and Linux bash) and the test-sim builder. |
| `_SIMKIT/TESTING.txt` | Site set-up, batch validation and GUI test procedure (toolkit owner only). |
| `docs/` | Case guideline for the team (`.docx`). |

## Site set-up (once)

1. Copy the `_SIMKIT` folder anywhere on the share (e.g. `S:\CFD\_SIMKIT` on Windows = `/mnt/cfd/_SIMKIT` on Linux).
   No environment variable and no path in the macros need to be set.
2. Edit the files in `config/` where the team's settings differ: `naming.csv`, `report_names.csv`, `standard_reports.csv`,
   `cluster.csv`.
3. Each user plays `_SIMKIT/buttons/SimKit_InstallButtons.java` once (File > Macro > Play Macro, from the share): a SimKit toolbar appears with the buttons (STAR-CCM+ favourite macros) and their icons, pointing at the files on the share. Own favourites are kept.

Cases are never stored in the kit: the first *Save as new* of a sim asks the user for the folder that will
hold the case folders.

## Validation

Batch validation (no GUI, about 10 minutes):

```
# Windows 10/11
powershell -ExecutionPolicy Bypass -File <SimKit root>\tests\Run-SimKitTests.ps1 -Star "<STAR install>" ^
    -LicenseFile "<licence file or port@server>" -TestRoot C:\SimKitTest

# Linux RHEL 7/9
bash <SimKit root>/tests/run-simkit-tests.sh --star /opt/Siemens/<release> --license <port@server> \
    --test-root ~/SimKitTest
```

The GUI test and the expected results are in `_SIMKIT/TESTING.txt`.

## Versions

| Tag | Content |
|---|---|
| `v0.2.1` | Full kit (20 tools, tags, groups, parameter sync, project workbook, register Apps Script). |
| `v0.3` | Simplified kit: New case, Save version, Export results, Calculators, Case list, Guideline, About. Later: projects and runs replace cases; Check, Compare versions, Export results and Case report removed. |

`_SIMKIT/VERSION.txt` holds the current version line. Tag each release `v<version>`.

## Licence

[The Unlicense](LICENSE): public domain. Anyone may use, copy, modify, fork and distribute SimKit for any purpose, without crediting the origin. No warranty.
