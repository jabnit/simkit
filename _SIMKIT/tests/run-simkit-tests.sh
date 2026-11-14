#!/bin/bash
# =====================================================================================================
# SimKit - automated batch validation on a licensed STAR-CCM+ installation, LINUX (RHEL 7 / RHEL 9).
# Same steps and same PASTE_THIS.txt as tests/Run-SimKitTests.ps1 (Windows 10/11):
#   00 version | 01 build test sim (tests/BuildSimKitTestSim.java) | 02 SimKitCalc self-check |
#   07 reports, mesh, mesh study | 08 buttons | optional 09 short solver run | 10-11 runs, tracker.
# Needs only bash >= 4.2, GNU coreutils/grep/sed/awk, tar (all standard on RHEL 7 and 9).
#
# Usage:
#   bash run-simkit-tests.sh --star /opt/Siemens/21.04.009 [--license 1999@licserver | /path/license.dat]
#        [--sim /path/real.sim] [--test-root ~/SimKitTest] [--np 1] [--star-args "-power -podkey XXX"]
#        [--run-solver] [--interactive] [--kit-source /path/_SIMKIT]
#   --star     launcher (.../star/bin/starccm+) OR an installation root searched for star/bin/starccm+
#   --license  sets CDLMD_LICENSE_FILE for the STAR-CCM+ processes of this script only. The licence file is
#              never read or printed; its location is masked as <licence> in PASTE_THIS.txt and the archive.
# Output: <test-root>/results/PASTE_THIS.txt (paste it into the chat) + <test-root>/simkit_test_results_<ts>.tar.gz
# =====================================================================================================
set -u
STAR=""; SIM=""; LICENSE=""; STAR_ARGS=""; TEST_ROOT="$HOME/SimKitTest"; NP=1
RUN_SOLVER=0; INTERACTIVE=0; KIT_SOURCE=""

die() { echo "ERROR: $*" >&2; exit 1; }
while [ $# -gt 0 ]; do
  case "$1" in
    --star) STAR="$2"; shift 2 ;;
    --sim) SIM="$2"; shift 2 ;;
    --license|--licence) LICENSE="$2"; shift 2 ;;
    --star-args) STAR_ARGS="$2"; shift 2 ;;
    --test-root) TEST_ROOT="$2"; shift 2 ;;
    --np) NP="$2"; shift 2 ;;
    --run-solver) RUN_SOLVER=1; shift ;;
    --interactive) INTERACTIVE=1; shift ;;
    --kit-source) KIT_SOURCE="$2"; shift 2 ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) die "unknown argument: $1 (see --help)" ;;
  esac
done
[ -n "$STAR" ] || die "--star is required (launcher or installation root)"

STAMP=$(date +%Y%m%d-%H%M%S)
SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
[ -n "$KIT_SOURCE" ] || KIT_SOURCE=$(dirname "$SCRIPT_DIR")
[ -f "$KIT_SOURCE/SimKit.java" ] || die "SimKit.java not found in kit source '$KIT_SOURCE'"
[ -z "$SIM" ] || [ -f "$SIM" ] || die "sim file not found: $SIM"

# ------------------------------------------------------------------ launcher
if [ -f "$STAR" ]; then
  LAUNCHER="$STAR"
elif [ -d "$STAR" ]; then
  LAUNCHER=$(find "$STAR" -path '*/star/bin/starccm+' -type f 2>/dev/null | head -n 1)
  [ -n "$LAUNCHER" ] || die "no star/bin/starccm+ found under $STAR"
else
  die "STAR-CCM+ path not found: $STAR"
fi

# ------------------------------------------------------------------ test share
if [ -e "$TEST_ROOT" ]; then
  mv "$TEST_ROOT" "${TEST_ROOT}_backup_${STAMP}" || die "cannot move the existing $TEST_ROOT"
  echo "Existing test share moved to ${TEST_ROOT}_backup_${STAMP}"
fi
RESULTS="$TEST_ROOT/results"
mkdir -p "$RESULTS" || die "cannot create $RESULTS"

log() { echo "$*"; echo "$*" >> "$RESULTS/script.log"; }

# runs the launcher with SIMKIT_ACTION=$2; remaining args are passed as they are; rc in RC
invoke_star() {
  local name="$1" action="$2"; shift 2
  local lg="$RESULTS/$name.log" t0 t1
  log "--- $name: starccm+ $*   (SIMKIT_ACTION='$action')"
  t0=$(date +%s)
  SIMKIT_ACTION="$action" "$LAUNCHER" "$@" > "$lg" 2>&1
  RC=$?
  t1=$(date +%s)
  log "    exit code $RC, $((t1 - t0)) s, log $lg"
}

log "SimKit batch validation  $STAMP"
log "launcher      : $LAUNCHER"
log "test sim      : ${SIM:-built by BuildSimKitTestSim.java}"
log "kit source    : $KIT_SOURCE"
log "test share    : $TEST_ROOT"
log "licence       : $([ -n "$LICENSE" ] && echo 'CDLMD_LICENSE_FILE set by script' || echo 'not set by script (existing environment)')"
log "extra args    : $STAR_ARGS"
log "linux / bash  : $(cat /etc/redhat-release 2>/dev/null || uname -sr) / $BASH_VERSION"

[ -n "$LICENSE" ] && export CDLMD_LICENSE_FILE="$LICENSE"
unset SIMKIT_HOME   # the kit finds its own root
export SIMKIT_GUI=""

"$LAUNCHER" -version > "$RESULTS/00_version.log" 2>&1
log "star version  : $(grep -m1 -E '[0-9]+\.[0-9]+\.[0-9]+' "$RESULTS/00_version.log")"

# ------------------------------------------------------------------ 1. kit copy + ready-made case
cp -R "$KIT_SOURCE" "$TEST_ROOT/_SIMKIT" || die "cannot copy the kit"
KIT="$TEST_ROOT/_SIMKIT"

COMMON=()
[ "$NP" -gt 1 ] 2>/dev/null && COMMON+=(-np "$NP")
# shellcheck disable=SC2206
[ -n "$STAR_ARGS" ] && COMMON+=($STAR_ARGS)

if [ -z "$SIM" ]; then
  mkdir -p "$TEST_ROOT/scratch"
  SIM="$TEST_ROOT/scratch/SimKit_testcase.sim"
  export SIMKIT_TESTSIM="$SIM"
  invoke_star 01_build_testsim "" ${COMMON[@]+"${COMMON[@]}"} -new -batch "$KIT/tests/BuildSimKitTestSim.java"
  unset SIMKIT_TESTSIM
  if [ ! -f "$SIM" ]; then
    log "ERROR: the test sim was not created; see results/01_build_testsim.log (paste its end into the chat)."
    tail -n 60 "$RESULTS/01_build_testsim.log" > "$RESULTS/PASTE_THIS.txt"
    die "test sim build failed - paste $RESULTS/PASTE_THIS.txt"
  fi
  log "test sim      : built $SIM ($(du -h "$SIM" | cut -f1))"
fi

# the test project: cases/SIMKIT_TEST/simkit_project.txt, run R001_base/R001_base.sim + run.txt
CASE="$TEST_ROOT/cases/SIMKIT_TEST"
RUN_DIR="$CASE/R001_base"
mkdir -p "$RUN_DIR"
CASE_SIM="$RUN_DIR/R001_base.sim"
cp "$SIM" "$CASE_SIM" || die "cannot copy the sim"
NOW=$(date '+%Y-%m-%d %H:%M:%S')
cat > "$CASE/simkit_project.txt" <<EOF
project=SIMKIT_TEST
description=SimKit batch validation ($STAMP)
created=$NOW
created_by=${USER:-unknown}
EOF
cat > "$RUN_DIR/run.txt" <<EOF
run=R001
name=base
comment=
based_on=
created=$NOW
created_by=${USER:-unknown}
file=R001_base/R001_base.sim
EOF
log "test run      : $CASE_SIM"

# ------------------------------------------------------------------ 2-13. batch runs
declare -A RCS
invoke_star 02_SimKitCalc_selfcheck "" ${COMMON[@]+"${COMMON[@]}"} -batch "$KIT/lib/generic/SimKitCalc.java" "$CASE_SIM"; RCS[calc]=$RC
invoke_star 07a_SimKit_reports reports ${COMMON[@]+"${COMMON[@]}"} -batch "$KIT/SimKit.java" "$CASE_SIM"; RCS[reports]=$RC
invoke_star 07b_SimKit_reports_again reports ${COMMON[@]+"${COMMON[@]}"} -batch "$KIT/SimKit.java" "$CASE_SIM"; RCS[reports2]=$RC
invoke_star 07c_SimKit_meshparams meshparams ${COMMON[@]+"${COMMON[@]}"} -batch "$KIT/SimKit.java" "$CASE_SIM"; RCS[meshpar]=$RC
invoke_star 07e_SimKit_meshstudy meshstudy ${COMMON[@]+"${COMMON[@]}"} -batch "$KIT/SimKit.java" "$CASE_SIM"; RCS[meshstudy]=$RC
invoke_star 07f_SimKit_names names ${COMMON[@]+"${COMMON[@]}"} -batch "$KIT/SimKit.java" "$CASE_SIM"; RCS[names]=$RC
invoke_star 07g_SimKit_presubmit presubmit ${COMMON[@]+"${COMMON[@]}"} -batch "$KIT/SimKit.java" "$CASE_SIM"; RCS[presubmit]=$RC
invoke_star 07h_SimKit_chttest chttest ${COMMON[@]+"${COMMON[@]}"} -batch "$KIT/SimKit.java" "$CASE_SIM"; RCS[chttest]=$RC
invoke_star 08_button_Tracker "" ${COMMON[@]+"${COMMON[@]}"} -batch "$KIT/buttons/SimKit_CaseTracker.java" "$CASE_SIM"; RCS[button]=$RC

# Toolbar buttons: install, then remove. STAR-CCM+ keeps them in the user's settings.props (which also holds licence
# keys): every settings.props under ~/.star-* is copied aside first and put back afterwards, and only the
# MacroPlayFavoriteAction lines are ever logged.
star_settings() { find "$HOME" -maxdepth 3 -path "$HOME/.star-*/config/settings.props" -type f 2>/dev/null; }
log_buttons() {
  local f n ok
  while IFS= read -r f; do
    [ -n "$f" ] || continue
    n=$(grep -c '^MacroPlayFavoriteAction[0-9]=' "$f")
    # STAR-CCM+ reads "macro ; icon ; tooltip": a button only shows when the first field is the macro file
    ok=$(grep '^MacroPlayFavoriteAction[0-9]=' "$f" | sed 's/^[^=]*=//' | awk -F';' '{gsub(/[ \t]+$/, "", $1)} $1 ~ /\.java$/' | wc -l)
    log "    $1: $n favourite-macro line(s), $ok with a macro file first (button shown), in $f"
    grep '^MacroPlayFavoriteAction[0-9]=' "$f" | while IFS= read -r l; do log "      $l"; done
    tb="$(dirname "$f")/Toolbars/SimKit"
    log "    $1: SimKit toolbar $([ -d "$tb" ] && echo present || echo absent), $(ls "$tb"/*.shadow 2>/dev/null | wc -l) button link(s)"
  done < <(star_settings)
}
BK="$TEST_ROOT/_settings_backup"
mkdir -p "$BK"
declare -A SAVED=()
declare -A TBSAVED=()
i=0
while IFS= read -r f; do
  [ -n "$f" ] || continue
  cp -p "$f" "$BK/settings_$i.props" && SAVED["$f"]="$BK/settings_$i.props"
  tb="$(dirname "$f")/Toolbars/SimKit"   # the SimKit toolbar the installer makes
  if [ -d "$tb" ]; then cp -rp "$tb" "$BK/toolbar_$i" && TBSAVED["$tb"]="$BK/toolbar_$i"; else TBSAVED["$tb"]=""; fi
  i=$((i + 1))
done < <(star_settings)
invoke_star 08b_buttons_install installbuttons ${COMMON[@]+"${COMMON[@]}"} -batch "$KIT/buttons/SimKit_InstallButtons.java" "$CASE_SIM"; RCS[buttons]=$RC
log_buttons "after install"
invoke_star 08c_buttons_remove removebuttons ${COMMON[@]+"${COMMON[@]}"} -batch "$KIT/buttons/SimKit_InstallButtons.java" "$CASE_SIM"; RCS[buttonsrm]=$RC
log_buttons "after remove"
for f in "${!SAVED[@]}"; do cp -p "${SAVED[$f]}" "$f"; done
for tb in "${!TBSAVED[@]}"; do rm -rf "$tb"; [ -n "${TBSAVED[$tb]}" ] && cp -rp "${TBSAVED[$tb]}" "$tb"; done
while IFS= read -r f; do
  [ -n "$f" ] && [ -z "${SAVED[$f]+x}" ] && log "    new settings file left by the test (not removed): $f"
done < <(star_settings)
rm -rf "$BK"
log "    user STAR-CCM+ settings restored (${#SAVED[@]} file(s))"
if [ "$RUN_SOLVER" -eq 1 ]; then
  invoke_star 09_SimKit_run "" ${COMMON[@]+"${COMMON[@]}"} -batch "run,$KIT/SimKit.java" -batch-report "$CASE_SIM"; RCS[run]=$RC
fi
invoke_star 10b_SimKit_saverun saverun ${COMMON[@]+"${COMMON[@]}"} -batch "$KIT/SimKit.java" "$CASE_SIM"; RCS[saverun]=$RC
invoke_star 11_SimKit_tracker tracker ${COMMON[@]+"${COMMON[@]}"} -batch "$KIT/SimKit.java" "$CASE_SIM"; RCS[tracker]=$RC
invoke_star 11b_SimKit_opensim opensim ${COMMON[@]+"${COMMON[@]}"} -batch "$KIT/SimKit.java" "$CASE_SIM"; RCS[opensim]=$RC
invoke_star 11c_SimKit_study studytest ${COMMON[@]+"${COMMON[@]}"} -batch "$KIT/SimKit.java" "$CASE_SIM"; RCS[study]=$RC

# ------------------------------------------------------------------ PASTE_THIS.txt (raw, redacted below)
PASTE="$RESULTS/PASTE_THIS.txt"
RAW="$RESULTS/.paste_raw.txt"
PATTERNS='\[BuildTestSim\]|error:|Exception|Error:|\[SimKit\]|\[SimKitCalc\]|n/a \(|FAIL|WARN|PASS|batch:|licen|License|Could not|cannot|Self-check|GCI|Gamma|X=|p=|written|wrote|saved|exported|set-up record|case card|list of changes|case list|plots exported|scenes exported|report values|=> |names|expected|button|kit |created|monitors|not proposed|already exists|boundary types|report\(s\)|READY|averaged|key results|solution|residuals|mass balance|monitors stable|y\+ vs|p_mesh|follow|Relative to the base|mesh size|boundary values|scene|case report|cells|case tracker|new version|forked|toolbar buttons|favourite|study|run R|submit|separator'
{
  echo "===== SIMKIT BATCH VALIDATION (LINUX) - paste everything below into the chat ====="
  echo "date $STAMP | launcher $LAUNCHER | $(cat /etc/redhat-release 2>/dev/null || uname -sr)"
  echo "star version: $(grep -m1 -E 'STAR-CCM\+|Simcenter' "$RESULTS/00_version.log")"
  printf 'exit codes:'; for k in $(printf '%s\n' "${!RCS[@]}" | sort); do printf '  %s=%s' "$k" "${RCS[$k]}"; done; echo
  echo
  for f in $(ls "$RESULTS"/0*.log "$RESULTS"/1*.log 2>/dev/null | sort); do
    n=$(wc -l < "$f")
    echo "----- $(basename "$f")  ($n lines) -----"
    hits=$(grep -E "$PATTERNS" "$f" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//' | grep -v '^$' | awk '!seen[$0]++')
    cnt=$(printf '%s\n' "$hits" | grep -c .)
    if [ "$cnt" -eq 0 ]; then tail -n 25 "$f"
    elif [ "$cnt" -gt 80 ]; then printf '%s\n' "$hits" | head -n 70; echo "... ($((cnt - 70)) more matching lines in the log)"
    else printf '%s\n' "$hits"; fi
    echo
  done
  echo "----- [Unn] tags seen with n/a or errors -----"
  tags=$(cat "$RESULTS"/0*.log "$RESULTS"/1*.log 2>/dev/null | grep -E 'n/a|FAIL|Exception|error:' | grep -v 'warning:' \
         | grep -oE '\[U[0-9]+\]' | sort | uniq -c | awk '{print $2" x"$1}')
  if [ -n "$tags" ]; then echo "$tags"; else echo "(none)"; fi
  echo
  echo "----- deprecation warnings (compile OK; API deviation to note vs 2402) -----"
  dep=$(cat "$RESULTS"/0*.log "$RESULTS"/1*.log 2>/dev/null | grep 'warning: \[deprecation\]' \
        | sed 's/^.*warning: \[deprecation\] //' | awk '!seen[$0]++')
  if [ -n "$dep" ]; then echo "$dep"; else echo "(none)"; fi
  echo
  echo "----- files produced in the test case -----"
  (cd "$CASE" && find . -type f ! -name '*.sim' ! -name '*.sim~' -printf '%P  %s B\n' | sort)
  echo
  echo "===== END ====="
} > "$RAW" 2>&1

# ------------------------------------------------------------------ privacy: never expose the licence
# The licence file content is never read. Its location (and its folder, and any path in a folder named
# 'confidential') is masked, and licence FEATURE/SIGN lines that a tool might echo are dropped. The secrets are
# passed to awk through the environment so that they never appear on a command line.
redact() {   # redact <in> <out>
  SK_SECRET1="$LICENSE" SK_SECRET2="$( [ -n "$LICENSE" ] && [ -e "$LICENSE" ] && dirname "$LICENSE" )" \
  awk '
    function rep(s, x, t,   i) { if (x == "") return s
      while ((i = index(s, x)) > 0) s = substr(s, 1, i - 1) t substr(s, i + length(x)); return s }
    { s = $0
      s = rep(s, ENVIRON["SK_SECRET1"], "<licence>")
      s = rep(s, ENVIRON["SK_SECRET2"], "<licence>")
      gsub(/\/[^ \t"'"'"']*\/[Cc]onfidential(\/[^ \t"'"'"']*)?/, "<confidential path>", s)
      gsub(/[A-Za-z]:\\[^ \t"'"'"']*\\[Cc]onfidential(\\[^ \t"'"'"']*)?/, "<confidential path>", s)
      if (s ~ /(^|[ \t])(FEATURE|INCREMENT|SERVER|VENDOR|USE_SERVER)[ \t]|SIGN=|vendor_info=|HOSTID=/) s = "<licence line removed>"
      print s }' "$1" > "$2"
}
redact "$RAW" "$PASTE"; rm -f "$RAW"

# archive (logs + case outputs, no .sim), every text file redacted
TMP=$(mktemp -d "${TMPDIR:-/tmp}/simkit_tar_XXXXXX")
cp -R "$RESULTS" "$TMP/results"; cp -R "$TEST_ROOT/cases" "$TMP/cases"
find "$TMP" \( -name '*.sim' -o -name '*.sim~' \) -type f -delete
find "$TMP" -type f \( -name '*.log' -o -name '*.txt' -o -name '*.csv' -o -name '*.html' \) | while read -r f; do
  redact "$f" "$f.red" && mv "$f.red" "$f"
done
ARCHIVE="$TEST_ROOT/simkit_test_results_$STAMP.tar.gz"
tar -czf "$ARCHIVE" -C "$TMP" results cases
rm -rf "$TMP"

echo
echo "==================================================================="
echo " Done. Paste the content of:"
echo "   $PASTE"
echo " Full logs and outputs (no .sim files): $ARCHIVE"
echo "==================================================================="
head -n 40 "$PASTE"; echo "   ... (see the file for the rest)"

if [ "$INTERACTIVE" -eq 1 ]; then
  echo "Opening STAR-CCM+ with the SimKit menu on the test case (section C of TESTING.txt)..."
  "$LAUNCHER" ${COMMON[@]+"${COMMON[@]}"} -macro "$KIT/SimKit.java" "$CASE_SIM"
fi
