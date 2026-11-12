# SimKit 0.2.1: independent review and proposal for "SimKit Lite" (phase 1)

Reviewer role: senior CFD team lead, independent of the authors. Date: 11 November 2026.
Scope: read-only review of the guideline v0.2, the workbook sources, `SimKit.java`, the calculators, the ISO macros, `TESTING.txt` and the Apps Script. Nothing was run in STAR-CCM+.

File references use these short names:

| Short name | File |
|---|---|
| GL | `guideline.md` (source of `Google\STAR-CCM+_Simulation_Guidelines_SimKit_v0.2.docx`) |
| SK | `_SIMKIT\SimKit.java` |
| CALC | `_SIMKIT\lib\generic\SimKitCalc.java` |
| TEST | `_SIMKIT\TESTING.txt` |
| WB | `build_workbook.py` and `data_v02.py` in the scratchpad (sources of the workbook sheets) |
| GS | `_SIMKIT\google\register_import.gs` |
| cfg | `_SIMKIT\config\*.csv` |

Note on sources: the brief says `data_v02.py` holds every sheet. It holds only the settings catalogue, fluids, materials and emissivity. The checklists, register columns, dictionary and sheet list are in `build_workbook.py` (same folder), which I read instead.

---

## 1. Verdict

**The owner's concern is justified, but only for the part of the kit that users see.** The engine is sound and most of it should be kept. The problem is how much of it is exposed on day one, in what order, and in what state of validation.

**What is right and should not be lost.** Case identity, folders and file names are generated, not typed (SK ~l.495–535). Every saved version silently gets a set-up audit, a difference list and a case card (SK `toolSaveVersion` ~l.616–643). The rules are data in CSV files, not code (cfg). Tools fail safe: writes are confined to the project tree, renames need a preview, and a tool error never kills the session (SK header l.22–36). This is the right architecture for "the tool does the work" (GL §1).

**Where it is too complex for a team starting from zero.**

| Load on a normal user | Count | Source |
|---|---|---|
| Menu tools | 20 in 7 groups | SK l.190–197 |
| Tool runs in the daily workflow | 10 named tools in 8 steps, plus 2 actions in Google Sheets | GL §3 |
| Naming patterns | 22 (19 enforced by regex, plus a default-name detector) | GL App. A; `naming_rules.csv` |
| Dictionary tokens | 42 rows | WB `DICT` l.57–100 |
| Tags / tag rules | 11 / 19 | GL App. B; `tag_rules.csv` |
| Tree folders / group rules | 18 folders / 22 rules | `group_rules.csv`; SK l.2906–2908 |
| Stages / statuses / register events | 8 / 7 / 5 | `stages.csv`; WB l.21–22 |
| Register columns | 21 | WB `REGISTER_HEAD` l.345 |
| Workbook sheets | 22 | WB l.375–401 |
| Parameter-sync states | 11 (S0, S1, S2, S3, S4, S4b, S5, NP, NB, NA, OFF) | SK `classify` ~l.3047–3095 |
| Settings watched by the sync | 33 | `settings_catalogue.csv` |
| Calculator tabs | 10 | CALC l.7–10 |
| Environment variables | 4 (`SIMKIT_SHARE`, `_ACTION`, `_OWNER`, `_GUI`) | SK l.228, 279, 3266–3275 |

The guideline is 16 sections and 4 appendices, and asks for half a day of training (GL §16). For a team with no habits at all, that is a tool to learn, not a guideline to follow. The owner's phrase "almost a standalone tool" is accurate.

**Five specific problems, in order of importance.**

1. **The kit's main payoff depends on a template that does not exist yet.** `templates\ISO\` contains only a `.docx` text for the Simulation Guide; there is no `.simt`. The guideline itself puts "Build the ISO_template_v01" in phase 2 (GL §17). Without the template, tags, groups, query selections, required parameters and the template baseline have nothing to act on. Phase 1 as written therefore asks users to learn the rules before they can see the benefit.
2. **Pre-run check will fail on every existing sim.** Any naming issue is a FAIL (SK l.1303), and the check hard-wires template objects: `p_tAvgStart` (l.1365), required ISO parameters (l.1344), G00 balance reports (l.1407). A check that is always red is ignored within a week.
3. **Parameter sync changes the user's sim at save time.** It creates, relinks and deletes global parameters through an 11-state machine, with a preview table at every save when something is pending (SK `syncBeforeSave` l.1973). As delivered it is also inert: no `factory_defaults_*.csv` and no template baseline are in `config\`, so every setting classifies as "no baseline: report only" (SK l.3058). It is the most complex feature and the least ready.
4. **The register needs infrastructure the team may not have.** It needs an Apps Script pasted into each project workbook, a Drive folder ID, and either Google Drive for desktop syncing a network-share folder or users dropping CSV files by hand (GL §5.3, §17; GS l.1–15). The script is marked "UNTESTED STARTER" (GS l.2). The register is also an event log (one row per save or export), yet users are told to set a per-case status in it (GL §3 step 8). A case with six versions has eight or more rows; which one carries the status is undefined.
5. **Validation is incomplete for the stated target.** Batch runs passed on 2606 on Windows only. GUI tests, a run on 2402 and any run on Linux are all pending (`VERSION.txt`; TEST §J, §K). The `SimKit.java` header still says "UNTESTED STARTER" (SK l.1), which contradicts `VERSION.txt`. 33 distinct uncertain-API tags remain in `SimKit.java`.

**What users must learn vs what the owner alone deals with.**

| Users must learn (today) | Owner only |
|---|---|
| 20-tool menu and the order to use it | CSV export of rule sheets, releases, Git |
| Case ID, 8 stages, version vs stage vs branch | Settings catalogue and its reflection paths |
| 22 naming patterns and the token dictionary | Baseline snapshots (factory, template) |
| 11 tags and when hand-tagging is allowed | Template specification (111 objects, 37 parameters) |
| Parameter-sync preview (states and actions) | TESTING procedure (438 lines; sync test alone ~45 min) |
| Register import menu and 7 statuses | Apps Script installation per workbook |
| `SIMKIT_SHARE` on a non-default mount | Uncertain-API fixes per release |

The right-hand column is acceptable for one owner. The left-hand column is the problem. **Lite reduces it to 4 buttons, 5 rules and one line in a sheet per case.**

---

## 2. Findings

| Area | What the kit does now | Cost to a normal user | Decision | Reason |
|---|---|---|---|---|
| Naming grammar | 22 patterns; 19 enforced by regex on every object type; any breach is an error (GL App. A; `naming_rules.csv`; SK `lint` ~l.1071) | High. An existing sim yields dozens of errors. Tokens must come from a 42-row dictionary. | **Simplify** | Keep only what automation needs and what is costly to fix later: part and part-surface names, and "no default names". The other prefixes become recommendations that arrive with the template. This is a config change: `lint` skips object types that have no rule row. |
| Tags | 11 tags, 19 rules, Auto-tag tool; users may hand-tag in "special cases" (GL §7.4) | Medium. A new STAR-CCM+ concept for many users, with no effect until a template uses tag queries. | **Defer** to phase 2 | Tags are a template-building mechanism. They belong to the template owner. Export already falls back to "export everything" when nothing is tagged (SK l.1779). |
| Groups | 18 standard folders, 22 rules, Auto-group tool | Low, but one more button and one more thing to remember | **Defer** to phase 2 | Cosmetic. A template ships with its folders already in place. |
| Case identity | `<PROJ>-<CFG>-<NNN>`, number assigned safely against parallel creation (SK l.495–512) | Low: pick a configuration, type one line | **Keep** | Best idea in the kit. Short, permanent, automatic. |
| Stages | 8 stage codes in the file name; a stage dialog at every save (SK l.621); separate "New stage" tool | Medium: a question at every save, and a version/stage/branch distinction to learn | **Simplify** | Keep the stage in the file name but preselect the current one. "Save new version" already allows a stage change, so tool 3 is redundant. |
| Versions | `_v<nn>` incremented by the tool; audit and diff written (SK l.616–643) | Low | **Keep** | Replaces hand-written change logs at zero effort. |
| Branch | Separate tool with a two-mode question (copy file vs save state) (SK l.646–655) | Medium: a fourth "case" button and a subtle choice | **Simplify** | Fold into "New case": if the open sim is already a case, the new case takes it as parent. |
| Case register | 21-column event log; one inbox CSV per event; import by menu in Sheets; status edited per row (GL §5.3, App. C) | High at set-up (owner and IT), medium daily (import, then find the right row) | **Simplify** now, automate in phase 2 | Phase 1: one row per case in a plain sheet, about 8 columns, pasted once. SimKit keeps writing inbox files silently so no history is lost. |
| Google Apps Script | 223-line script pasted per workbook; needs Drive folder access and a synced inbox (GS; GL §17) | None for users if it works; blocking if Drive sync of the share is not allowed | **Defer** to phase 2 | Untested, and it depends on an IT decision. Do not put it on the critical path of adoption. |
| Templates | Specification of 111 objects and 37 parameters; no `.simt` delivered (GL §8.3; `templates\ISO\`) | None yet, but its absence removes most of the kit's benefit | **Keep, resequence** | Building the template is the owner's main phase-1 task, in parallel with the Lite pilot. |
| Parameter sync, states, baselines | Auto-creates, relinks and deletes managed parameters; 11 states; preview at each save; needs two baselines (GL §9; SK l.1937–3100) | High: modifies the sim, long parameter names (`p_MESH_MO30autoMesh_nPrism`), a preview table to judge | **Defer** to phase 3; report-only first | Highest complexity, lowest readiness, inert as delivered (no baselines). The read-only "what differs from default" report is the valuable half. |
| Settings catalogue | 33 rows of reflection paths, release-dependent (`settings_catalogue.csv`; TEST §I) | None directly | **Defer** with the sync | Owner-only, but each new release needs a re-check of the paths. |
| Calculators | 10 tabs, 2,614 lines, property libraries, writes parameters (CALC; GL §10) | None if optional. It is a second product. | **Keep as optional**, out of the core guideline | Useful and independent; physics unit-tested outside STAR (TEST §G). Not a process step. Keeping it under "More" costs nothing. |
| Macro library | Launcher listing generic, project and case macros; 3 ISO macros; `lib\generic` holds only the calculators and a README (SK `toolLibrary` l.1851) | Low, but another button | **Defer** | Three macros do not need a launcher. `ISO_parameters` and `ISO_templateCheck` are template-owner tools; `ISO_exportHandoff` is a phase-2 analysis tool. |
| Checklists | 17 items: 9 auto, 5 manual, 3 owner (WB `CHECK` l.301–318) | Low | **Simplify** | Lite keeps 3 manual checks in the guideline text. Automatic ones are in the Check button. Owner items move to the owner's notes. |
| Folder layout | 6 project folders plus 6 case sub-folders, created by the tool (GL §4.1; SK l.114) | Low: created automatically | **Keep** | One rule for users: do not move or rename. See risk R1 on sims that must sit on the share. |
| 20-tool menu | Modal, always-on-top grid of 20 numbered buttons; each tool ends in a result window, then the menu reopens (SK `showMenu` l.341–372, `runTool` l.336) | High: choice overload; the modal window blocks the STAR-CCM+ tree while open | **Simplify** to 4 buttons plus "More" | See section 3. |
| Batch actions | 8 actions through `-Dsimkit.action` or `SIMKIT_ACTION`, including one that edits and saves the sim (`paramsync_apply`) and an owner action (SK `runBatch` l.228–246) | None if undocumented; risk if misused | **Simplify** | Document one: the default (audit and export after a run). `lint`, `precheck`, `nondefault` stay for the owner's tests. `paramsync_apply` and `snapshot_factory` are test and owner actions and stay out of the user guideline. |
| "Write batch run script" | Writes `.bat` and `.sh`; asks for processes, macro chain, flags (SK l.1731) | Low | **Keep under "More"** | Clusters usually have their own submission scripts. Useful, not core. |
| Compare, Setup audit, About, Links | Four separate buttons | Low each, clutter together | **Hide under "More"** | Audit and diff already run at every save. |
| Testing burden | 438-line procedure; 33 uncertain-API tags; sync test ~45 min; GUI, 2402 and Linux runs pending (TEST; `VERSION.txt`) | None directly; high indirectly (a first-week crash kills adoption) | **Simplify by reducing scope** | Lite needs tests for 4 tools plus audit and diff. Sections F (sync) and G (calculators) are not release gates for phase 1. |
| Windows / Linux | One code base; share root per OS; `SIMKIT_SHARE` override; relative paths in records (GL §4.1; TEST §K) | Low: an environment variable for non-standard mounts | **Keep**, gate by test | Design is fine. Linux has never run on a real installation (TEST §K). Pilot on the OS the pilot users work on; enable the other after its test run passes. TEST §K itself notes that recent releases may not list RHEL 7. |

---

## 3. SimKit Lite (phase 1) specification

### 3.1 Buttons

Four buttons, in the order of the working day, plus one link.

| Button | Existing tool | What the user answers | What happens silently |
|---|---|---|---|
| **New case** | 1 Initialise case, with 4 Branch folded in | Configuration (list), one-line description. Parent is filled in if the open sim is already a case. | Case number, folders, `_v01` file name, save, audit, case card, inbox row |
| **Save version** | 2 Save new version (3 New stage removed) | One confirmation. Stage list preselected on the current stage. | Next version number, save, audit, differences from previous version, case card, inbox row |
| **Check** | 9 Pre-run check | Nothing | PASS / WARN / FAIL list, saved in the case `audit` folder |
| **Export results** | 13 Export results | Nothing | Plots to CSV, scenes to PNG, report values, inbox row |
| *More…* | 12 batch script, 20 calculators, 11 compare, 15 links, 16 about | — | Opens the remaining tools that are released |

Hidden in phase 1 (code stays, button absent): 3 New stage, 4 Branch, 5 Naming check, 6 Auto-name, 7 Auto-tag, 8 Auto-group, 10 Setup audit, 14 library launcher, 17 Parameter sync, 18 Non-default report, 19 Snapshot baseline.

### 3.2 Rules that remain (five)

1. Every sim is a case. Create it with **New case**; never rename or move the file or folder.
2. Every set-up change is a new version. Use **Save version**; never File > Save As.
3. Geometry parts are named `<ZONE>_<component>`; part surfaces are named `<BC>_<feature>`. Boundaries are never renamed.
4. No default names ("Report 3", "Plane Section 2") on anything you create.
5. Run **Check** before a run and **Export results** after it. Keep your line in the case list up to date.

### 3.3 Automated silently, done by hand, or gone

| Silent (no user action) | By hand | Disappears from phase 1 |
|---|---|---|
| Case number, folders, file names | Naming parts and surfaces during CAD cleaning | Tags and Auto-tag |
| Version numbering | One line per case in the case list; status when it changes | Groups and Auto-group |
| Set-up audit, difference list, case card at each save | Looking at mesh and y+ scenes before a run | Auto-name |
| Inbox event files (kept for phase 2) | Stating averaging window and balance closure when reporting | Parameter sync, baselines, managed parameters |
| Naming check (inside Check) | | Stage as a separate decision; branch as a separate tool |
| Post-run audit and export in batch (default action) | | Register import script and the 21-column register |
| | | Library launcher; workbook rule sheets (moved to an owner workbook) |

### 3.4 Code and configuration changes

Recommendation: **one code base with a menu level, not a separate macro.** A forked "SimKitLite.java" would double the testing and drift from the main file. The batch path, the audit and the case logic must stay identical so that phase 2 is a switch, not a migration.

| # | Change | Where | Size |
|---|---|---|---|
| 1 | Menu level. Read `level=lite|full` from a small file in `config\` (owner sets it for the team); `SIMKIT_MENU=full` overrides for the owner. In lite, `showMenu` shows tools 1, 2, 9, 13 and a "More…" button listing 12, 20, 11, 15, 16. | `GROUP_NAMES`, `GROUP_TOOLS` (l.195–197), `showMenu` (l.341–372) | Small |
| 2 | Plain button labels without numbers ("New case", "Save version", "Check", "Export results"). | `TOOL_NAMES` (l.190), button text (l.356) | Trivial |
| 3 | Fold Branch into New case: when the sim is already a case, call `toolBranch` instead of raising Cancel. | `toolInitCase` (l.539–543) | Trivial |
| 4 | One dialog at save: merge the stage chooser and the confirmation; stage preselected. Hide tool 3. | `toolSaveVersion` (l.616–624) | Small |
| 5 | Switch off parameter sync in lite: no preview at save, no line in Check, no audit section. | `syncBeforeSave` (l.1973), `checkParamSync` (l.1991), `auditSync` (l.2006) | Trivial (one guard each) |
| 6 | Make Check credible on non-template sims: naming issues become WARN except part and surface names; template-specific checks (`p_tAvgStart`, required parameters, G00 balance reports) become WARN until a template is declared in `projects.csv`. List the first ten naming issues in the result. | `toolPrecheck` (l.1303), `checkRequiredParameters` (l.1344), `checkStopping` (l.1365), `checkBalances` (l.1407) | Small |
| 7 | Reduce the naming rules by configuration only: keep the rows `geometry_part`, `part_surface`, `region` and `default_name` in `naming_rules.csv`. `lint` raises nothing for object types without a rule (l.1088–1097). The file must stay present, or the 19 built-in rules apply (l.1073). | `config\naming_rules.csv` | Config only |
| 8 | After New case, put a ready-to-paste line for the case list on the clipboard (case ID, user, date, description, parent). Optional; without it the user types four cells. | `toolInitCase`, after `register` (l.563) | Small, optional |
| 9 | Correct the stale header ("UNTESTED STARTER … 2402") to match `VERSION.txt`. | SK l.1 | Trivial |

Not changed: `register()` keeps writing inbox files (l.676–712); `runBatch` keeps all actions (l.228–246); the guideline documents only the default.

Workbook: split it. Users get a two-tab sheet ("Cases", "Cheat sheet"). The other 20 sheets move to an owner workbook ("SimKit configuration").

**Release gate for Lite**: tools 1, 2, 9, 13 and the audit and diff pass TEST §C interactively, on the release the team actually uses and on the pilot users' OS. `VERSION.txt` states that GUI tests are still pending; that is the one item that must close before any user sees the kit.

---

## 4. Simplified workflow guideline (draft, ready to paste into a Google Doc)

> # STAR-CCM+ cases: how we work (SimKit Lite)
>
> *Team guideline, phase 1. Two pages. Applies to all new STAR-CCM+ work on the ISO 2685 project.*
>
> ## Why
>
> Any of us should be able to open a colleague's case, see what it is, see what changed, and continue it. Today that depends on memory and file names. From now on a small tool inside STAR-CCM+ does the bookkeeping. You keep working as you do now.
>
> ## The daily workflow in 6 steps
>
> | | Step | Where |
> |---|---|---|
> | 1 | Start your simulation as usual (File > New, or open an existing sim). | STAR-CCM+ |
> | 2 | Click **SimKit**, then **New case**. Choose the configuration and type one line saying what the case is for. Paste the line it gives you into the case list. | SimKit, then Google Sheet |
> | 3 | Set up the case. Name parts and surfaces as shown in the cheat sheet. | STAR-CCM+ |
> | 4 | Before you run: click **Check**. Fix every FAIL. Look at the mesh and y+ scenes yourself. | SimKit |
> | 5 | Each time you change the set-up, and always before a run: click **Save version**. | SimKit |
> | 6 | After the run: click **Export results**. Update your status in the case list. | SimKit, then Google Sheet |
>
> SimKit is the macro `<SHARE>\_SIMKIT\SimKit.java`. Play it once with File > Macro > Play Macro. After that it is one click on the Play Recent Macro button of the Macro toolbar.
>
> ## The five rules, and why
>
> | Rule | Reason |
> |---|---|
> | **1. Every simulation is a case.** Create it with New case. Do not rename or move the file or its folder. | The case number is how we find, cite and compare work. A moved file loses its history. |
> | **2. Every set-up change is a new version.** Use Save version, not File > Save As. | Each version gets an automatic description of the set-up and a list of what changed. You never write a change log. |
> | **3. Name parts and surfaces by the cheat sheet. Never rename boundaries.** | Boundaries take their names from parts and surfaces. Good names here make every later selection, check and report readable, and they are painful to fix after meshing. |
> | **4. No default names on what you create.** "Report 3" becomes a name that says what it is. | Exported files and plots carry these names. A colleague cannot guess what "Report 3" was. |
> | **5. Check before you run. Export after you run. Keep your line in the case list current.** | The check catches missing stopping criteria, missing auto-save and unassigned regions before they cost a night of cluster time. The export puts results where others can find them. |
>
> ## What to do when
>
> **New case.** Click New case. Choose the configuration (CF cold flow, C1 free flame with calibration instruments, C2 inert plate, C3 composite specimen, VV verification). Type one line: purpose, and what differs from the case it is based on. SimKit creates the folder, numbers the case and saves the file. If you start from a colleague's or your own earlier case, open that sim first and then click New case: the new case records it as its parent.
>
> **New version.** Click Save version and confirm. Use it whenever you change mesh settings, models, boundary conditions or solver settings, and before every run. Change the stage in the same window only when the physics moves on (for example from cold flow to spray).
>
> **New version or new case?** Same purpose, improved set-up: new version. Different purpose (another configuration, a sensitivity study, another mesh level): new case.
>
> **Before a run.**
> - Click Check and fix every FAIL. Read the WARN lines.
> - Look at the mesh scenes and the y+ on the instrumented walls.
> - Click Save version.
>
> **After a run.**
> - Click Export results. Plots go to `results\csv`, images to `results\img`.
> - Set your status in the case list: running, done, rejected, or reported.
> - If the case is reported: note the averaging window, and that mass and energy balances close.
>
> ## The case list
>
> One Google Sheet, one line per case. You add the line when you create the case and change only the status and the note afterwards.
>
> | Case | Owner | Date | Purpose (one line) | Based on | Status | Reported version | Note |
> |---|---|---|---|---|---|---|---|
> | ISO-CF-001 | jdoe | 2026-11-12 | Cold flow, nominal air 46 g/s, medium mesh | – | done | v03 | compare with PIV plane 2 |
>
> ## Where things are
>
> ```
> <SHARE>\PROJECTS\ISO\
>     01_cad\            raw and cleaned geometry
>     03_cases\<CASE>\
>         sim\           the simulation files, one per version
>         run\           run scripts and solver logs
>         results\       exported CSV files and images
>         audit\         automatic set-up descriptions, change lists, check results
>     04_post\           comparisons between cases
>     05_report\         deliverables
> ```
>
> You do not create these folders. New case does. Keep folder and file names exactly as written: Linux distinguishes upper and lower case.
>
> ## If something goes wrong
>
> SimKit never deletes anything and never renames anything in this phase. If a button reports an error, your simulation is untouched: copy the text of the message to the toolkit owner and carry on working. Suggestions for new rules or tools go to the toolkit owner, not into private macros.
>
> ---
>
> # Naming cheat sheet (one page)
>
> **Files and cases: you type nothing.**
>
> | Item | Looks like | Made by |
> |---|---|---|
> | Case | `ISO-C1-007` (project – configuration – number) | New case |
> | Simulation file | `ISO-C1-007_RAD_v04.sim` (case, stage, version) | Save version |
>
> Stages: GEO, MESH, COLD, SPRAY, REACT, RAD, CHT, POST.
>
> **Geometry: required. Do it during CAD cleaning.**
>
> | Object | Pattern | Examples |
> |---|---|---|
> | Part (body) | `<ZONE>_<component>` | `BRN_stator`, `CAL_calTube`, `SPC_panel` |
> | Part surface | `<TYPE>_<feature>` | `IN_air`, `WALL_inner`, `OPEN_side`, `IF_outer` |
> | Region | `F_<name>` fluid, `S_<name>` solid | `F_air`, `S_cone` |
>
> | ZONE | Meaning | | TYPE | Meaning |
> |---|---|---|---|---|
> | BRN | Burner | | IN | Inflow |
> | CAL | Calibration instruments | | OUT | Outflow |
> | SPC | Specimen | | OPEN | Open boundary to ambient |
> | FIX | Fixture, holder | | WALL | Wall |
> | ENV | External domain | | SYM | Symmetry |
> | CEL | Test cell | | IF | Contact face between fluid and solid |
>
> - Component and feature words: lower case first letter, no spaces, letters and digits only (`draftTube`, `face`).
> - Identical repeated bodies take a number: `CAL_tcProbe_3`.
> - Two sides of the same feature take a side word: `WALL_step_up` and `WALL_step_dn`, `WALL_vane_ps` and `WALL_vane_ss`, `WALL_face_fr` and `WALL_face_bk`.
> - Split a surface only if something differs between the pieces: boundary condition, emissivity, prism layers, or a report on one piece. If only the cell size changes, use a volumetric control.
>
> **Everything else you create: recommended prefixes.**
>
> | Object | Prefix | Example |
> |---|---|---|
> | Parameter | `p_` | `p_mdotAir` |
> | Report | `r_` | `r_T_rake_avg` |
> | Plot | `pl_` | `pl_tgt_qCal` |
> | Scene | `sc_` | `sc_T_midSide` |
> | Derived part | `dp_` | `dp_plane_x102mm` |
> | Field function | `ff_` | `ff_Ttc` |
>
> The only firm rule for these in phase 1 is rule 4: no default names.

*End of the draft guideline.* It deliberately omits tags, groups, parameter synchronisation, the calculators, the macro library, batch actions, the register import and the roles table. Those return, one at a time, in phases 2 and 3.

---

## 5. Roll-out plan

### 5.1 Phases

| Phase | Users get | Owner does in the background | Criterion to move on |
|---|---|---|---|
| **0. Gate (before week 0)** | Nothing | Apply changes 1–7 and 9 of §3.4. Run TEST §C for the 4 Lite tools interactively on the team's release and the pilot OS. Create the share folders and the two-tab case list. | All 4 tools pass with no error on a copy of a real team sim. |
| **1. Lite (weeks 0–4)** | 4 buttons, 5 rules, the cheat sheet, the case list. A 30-minute demo on one real case, not a training course. Start with 2–3 volunteers in week 0–1, the whole ISO team from week 2. | Fix every reported problem within two working days. Build `ISO_template_v01` from the specification. Run the Linux test script on a real installation. Ask IT whether Drive can see the inbox folder. | For 3 consecutive weeks: at least 80 % of new sims are SimKit cases; at least 3 engineers have each saved 5 or more versions; no open blocking bug. Template v01 exists and passes `ISO_templateCheck`. |
| **2. Template and records (about weeks 5–12)** | New cases start from the template (tags, folders, reports, scenes already inside; users still do not manage tags). The naming check extends to reports, plots and scenes. Post-run audit and export in batch. Calculators and Compare announced as supported. Register filled automatically if IT allows; otherwise the owner imports weekly. | Owner uses Auto-tag, Auto-group, Auto-name and `ISO_parameters` to build and maintain the template. Harvest factory defaults and the template baseline. Turn on the non-default settings report as **read-only** in the audit. | Two reported cases reviewed from the case card alone. Check gives no false FAIL on template cases for 4 weeks. Users ask for more automation (requests logged). |
| **3. Scale (after about 3 months)** | Parameter sync with Apply, offered as opt-in first. SimKit as a Simulation Assistant side panel (GL §11.2). Hand-off export. Second project; Fluent convention if needed. | Package the `.jar`; write the second project's rule sheets. | Decided per feature, on request and on test results. |

### 5.2 Ownership

| Item | Toolkit owner | Template owner | Users |
|---|---|---|---|
| SimKit code, config CSV files, releases, tests | Owns | Consulted | Read-only |
| Menu level (lite or full) | Decides | – | – |
| Guideline and cheat sheet | Owns | Reviews naming tokens | Propose changes |
| Template, its specification, tags and tag rules | Supports | Owns | Use |
| Case list | Owns the layout | – | Own their lines |
| Case folders and sims | – | – | Own |
| Naming of parts and surfaces | Checks through the tool | Defines tokens | Do it |

In a small team the toolkit owner and the template owner can be one person, but a named deputy is needed (see R5).

### 5.3 Measuring adoption

All of these can be read from files SimKit already writes; nobody fills in a survey to produce them.

| Measure | Source | Target at week 4 |
|---|---|---|
| Share of new sims that are SimKit cases | Case folders in `03_cases` against sims the team lead knows were started | ≥ 80 % |
| Active users per week | `user` column of the inbox files (`00_admin\register_inbox`) | All ISO engineers |
| Versions saved per case | Inbox `save` events | Median ≥ 3 (shows that Save version replaced Save As) |
| Check used before runs | `audit\precheck_*.csv` present for versions that have a run log | ≥ 70 % |
| Results exported | Inbox `results` events against cases marked done | ≥ 80 % |
| Case list kept current | Lines whose status changed in the last 2 weeks | ≥ 80 % of active cases |
| Time to first case for a new user | Observed at the demo | < 30 minutes |
| Friction | Problems and requests received by the owner | Falling week on week |

One qualitative check at week 4: ask each user which button they would remove and which they miss. If the answer to the first is "none", move on.

---

## 6. Risks of over-simplifying

| # | Value in the current kit that must not be lost | Risk if dropped | How Lite keeps the path open |
|---|---|---|---|
| 1 | Automatic audit, differences and case card at every version | This is the reviewability the team lacks today | Kept in full; it is silent, so it costs the user nothing. |
| 2 | Part and surface naming at CAD cleaning | Names are the hook for every template query (GL §6.1). Retrofitting them after 20 cases is expensive. | Kept as rule 3 with the same tokens as the full grammar. Nothing named in phase 1 needs renaming later. |
| 3 | Event history in the inbox files | A later register would start empty | `register()` is untouched. Phase 2 imports the backlog. |
| 4 | Rules as data (CSV), not code | A hard-coded Lite would need rewriting for the next project | Lite is a subset of rows and a menu level, on the same code. |
| 5 | Template with query selections, fixed colour ranges and saved views | Without it cases stay hand-wired and images are not comparable (GL §8.3) | Not deferred: it is the owner's phase-1 work and the phase-2 entry criterion. Only the user-facing tag vocabulary is deferred. |
| 6 | Visibility of non-default settings | Silent deviations from the template go unnoticed | Phase 2 brings the read-only report into the audit. The Apply half waits for phase 3. |
| 7 | Pre-run check | Lost nights on the cluster | Kept as a button. Made credible first (§3.4 change 6); a check that cries wolf is worse than none. |
| 8 | Common formulas and property data (calculators) | Each engineer keeps a private spreadsheet | Available under "More" from day one; promoted in phase 2. |
| 9 | Windows and Linux from one code base | A Windows-only habit forms and cluster runs are left out | Design unchanged; Linux enabled as soon as its test run passes. |

**Open risks that simplification does not remove; decide them during the pilot.**

- **R1. Sims must sit on the share.** Case tools refuse a sim that is not under `<SHARE>\PROJECTS\<PROJ>\03_cases\<CASEID>\sim` (SK `parseCase` l.443–452). If the team solves on cluster scratch or local disks, users will copy files out and the tools will stop working on the copies. I could not tell from the files how the team runs. Confirm in week 0; if scratch is used, define a "copy back and Save version" convention or relax the check.
- **R2. The menu is a modal, always-on-top window** (SK l.343, 368). The user cannot look at the tree while it is open, and every tool ends in a result window. Four buttons make this tolerable. The side panel in phase 3 is the real fix.
- **R3. "One click" depends on SimKit being the most recent macro** (GL §11.2). Playing any other macro displaces it. Acceptable in phase 1; mention it at the demo.
- **R4. Release drift.** Validation was on 2606 while the stated target is 2402 (`VERSION.txt`). Test on the release the team really uses before week 0, and again at each upgrade. Lite reduces the surface to re-test from 20 tools to 4.
- **R5. Single owner.** All the remaining complexity sits with one person. Name a deputy before phase 2.
- **R6. Doing less than Lite.** A guideline with no tool at all would bring back hand-typed file names and hand-written change logs, which is the tedium the owner wants to avoid. Four buttons is the floor, not a compromise.
