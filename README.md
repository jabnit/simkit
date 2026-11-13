# SimKit: team helper for STAR-CCM+ cases

SimKit is a set of STAR-CCM+ Java macros that keeps the native STAR-CCM+ workflow and adds a few
team tools on top of it: generated case identity and file names, versioned saves with a set-up
record and a change list, a pre-run check, results export, standard reports and scenes, a mesh
study helper, a case tracker and engineering calculators.

> **Status (2026-11-12): version 0.3.** Batch-validated on STAR-CCM+ 2606.0002 (21.04.009, Windows).
> The GUI test, STAR-CCM+ 2402 and Linux (RHEL 7/9) runs are still pending (see `_SIMKIT/TESTING.txt`).
> The full 0.2.1 kit (tags, groups, parameter sync, register script, batch-script writer) is kept at the
> git tag `v0.2.1` for a later update.

## Repository layout

| Path | Content |
|---|---|
| `_SIMKIT/` | **The kit.** Copy this folder as-is to the team share. Its inner layout must be kept: every macro finds the kit root from its own location. |
| `_SIMKIT/SimKit.java` | The macro users play: Save new version, Check, Export results and the other tools. |
| `_SIMKIT/buttons/` | One small macro per toolbar button, their icons and `SimKit_InstallButtons.java` (see `_SIMKIT/buttons/README.txt`). |
| `_SIMKIT/config/` | Projects, naming rules, Guideline links and MatML material folders (CSV, edited by the owner). |
| `_SIMKIT/lib/generic/` | `SimKitCalc.java` (Calculators window) and its property tables in `data/`. |
| `_SIMKIT/templates/ISO/` | ISO template guide text and the template-owner macros. |
| `_SIMKIT/tests/` | Batch validation runners (Windows PowerShell and Linux bash) and the test-sim builder. |
| `_SIMKIT/TESTING.txt` | Site set-up, batch validation and GUI test procedure (toolkit owner only). |
| `Google/` | Case list workbook (import into Google Sheets) and the case guideline (put in Google Docs). |
| `docs/review/` | Independent review of SimKit 0.2.1 and the proposal that led to 0.3. |

## Site set-up (once)

1. Copy the `_SIMKIT` folder anywhere on the share (e.g. `S:\CFD\_SIMKIT` on Windows = `/mnt/cfd/_SIMKIT` on Linux).
   No environment variable and no path in the macros need to be set.
2. Edit `config/projects.csv` (projects, configuration codes) and `config/links.csv` (Google Doc / Sheet links).
3. Each user plays `_SIMKIT/buttons/SimKit_InstallButtons.java` once (File > Macro > Play Macro, from the share): the SimKit buttons go to their STAR-CCM+ Macro toolbar (favourite macros), with icons, pointing at the files on the share. Own favourites are kept.
4. Import `Google/SimKit_Case_List_ISO.xlsx` into Google Sheets and put the guideline `.docx` in Google Docs.

Cases are never stored in the kit: the first *Save new version* of a sim asks the user for the folder that will
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
| `v0.3` | Simplified kit: New case, Save version, Check, Export results, Calculators, Compare versions, Case list, Guideline, About. |

`_SIMKIT/VERSION.txt` holds the current version line. Tag each release `v<version>`.

## Licence

**Not yet selected.** No `LICENSE` file is included, because choosing a licence is the owner's decision and
must not be made silently. Until a licence is added, all rights are reserved by default.
