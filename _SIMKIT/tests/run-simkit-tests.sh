#!/bin/bash
# =====================================================================================================
# SimKit - automated batch validation on a licensed STAR-CCM+ installation, LINUX (RHEL 7 / RHEL 9).
# Same steps and same PASTE_THIS.txt as tests/Run-SimKitTests.ps1 (Windows 10/11):
#   00 version | 01 build test sim (tests/BuildSimKitTestSim.java) | 02 SimKitCalc self-check |
#   03 precheck | 04 audit | 05 export | 07 factory sim | 08 snapshot_factory | 09 lint | 10 nondefault |
#   11 paramsync_apply | 12 paramsync | 13 audit again | optional 06 short solver run.
# Needs only bash >= 4.2, GNU coreutils/grep/sed/awk, tar (all standard on RHEL 7 and 9).
#
# Usage:
#   bash run-simkit-tests.sh --star /opt/Siemens/21.04.009 [--license 1999@licserver | /path/license.dat]
#        [--sim /path/real.sim] [--test-root ~/SimKitTest] [--np 1] [--star-args "-power -podkey XXX"]
#        [--run-solver] [--interactive] [--skip-sync] [--kit-source /path/_SIMKIT]
#   --star     launcher (.../star/bin/starccm+) OR an installation root searched for star/bin/starccm+
#   --license  sets CDLMD_LICENSE_FILE for the STAR-CCM+ processes of this script only. The licence file is
#              never read or printed; its location is masked as <licence> in PASTE_THIS.txt and the archive.
# Output: <test-root>/results/PASTE_THIS.txt (paste it into the chat) + <test-root>/simkit_test_results_<ts>.tar.gz
# =====================================================================================================
set -u
STAR=""; SIM=""; LICENSE=""; STAR_ARGS=""; TEST_ROOT="$HOME/SimKitTest"; NP=1
RUN_SOLVER=0; INTERACTIVE=0; SKIP_SYNC=0; KIT_SOURCE=""

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
    --skip-sync) SKIP_SYNC=1; shift ;;
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
export SIMKIT_SHARE="$TEST_ROOT"
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

CASE_ID="ISO-CF-001"
CASE="$TEST_ROOT/PROJECTS/ISO/03_cases/$CASE_ID"
for d in sim macros run results/csv results/img audit; do mkdir -p "$CASE/$d"; done
mkdir -p "$TEST_ROOT/PROJECTS/ISO/00_admin/register_inbox"
CASE_SIM="$CASE/sim/${CASE_ID}_COLD_v01.sim"
cp "$SIM" "$CASE_SIM" || die "cannot copy the sim"
cat > "$CASE/sim/case_identity.txt" <<EOF
case_id=$CASE_ID
project=ISO
config=CF
parent_case=
description=SimKit batch validation ($STAMP)
created=$(date +%Y-%m-%dT%H:%M:%S)
created_by=${USER:-unknown}
stage=COLD
version=1
template=ISO_template_v01.simt
EOF
log "test case     : $CASE_SIM"

# ------------------------------------------------------------------ 2-13. batch runs
declare -A RCS
invoke_star 02_SimKitCalc_selfcheck "" ${COMMON[@]+"${COMMON[@]}"} -batch "$KIT/lib/generic/SimKitCalc.java" "$CASE_SIM"; RCS[calc]=$RC
invoke_star 03_SimKit_precheck precheck ${COMMON[@]+"${COMMON[@]}"} -batch "$KIT/SimKit.java" "$CASE_SIM"; RCS[precheck]=$RC
invoke_star 04_SimKit_audit audit ${COMMON[@]+"${COMMON[@]}"} -batch "$KIT/SimKit.java" "$CASE_SIM"; RCS[audit]=$RC
invoke_star 05_SimKit_export export ${COMMON[@]+"${COMMON[@]}"} -batch "$KIT/SimKit.java" "$CASE_SIM"; RCS[export]=$RC
if [ "$SKIP_SYNC" -eq 0 ]; then
  FACTORY_SIM="$TEST_ROOT/scratch/SimKit_factory.sim"
  mkdir -p "$TEST_ROOT/scratch"
  export SIMKIT_TESTSIM="$FACTORY_SIM" SIMKIT_BUILD_MODE=factory
  invoke_star 07_build_factory_sim "" ${COMMON[@]+"${COMMON[@]}"} -new -batch "$KIT/tests/BuildSimKitTestSim.java"
  unset SIMKIT_TESTSIM SIMKIT_BUILD_MODE
  if [ -f "$FACTORY_SIM" ]; then
    export SIMKIT_OWNER=1
    invoke_star 08_snapshot_factory snapshot_factory ${COMMON[@]+"${COMMON[@]}"} -batch "$KIT/SimKit.java" "$FACTORY_SIM"; RCS[snapshot]=$RC
    unset SIMKIT_OWNER
  else
    log "factory sim not built: see 07_build_factory_sim.log"
  fi
  invoke_star 09_SimKit_lint lint ${COMMON[@]+"${COMMON[@]}"} -batch "$KIT/SimKit.java" "$CASE_SIM"; RCS[lint]=$RC
  invoke_star 10_SimKit_nondefault nondefault ${COMMON[@]+"${COMMON[@]}"} -batch "$KIT/SimKit.java" "$CASE_SIM"; RCS[nondefault]=$RC
  invoke_star 11_SimKit_paramsync_apply paramsync_apply ${COMMON[@]+"${COMMON[@]}"} -batch "$KIT/SimKit.java" "$CASE_SIM"; RCS[sync_apply]=$RC
  invoke_star 12_SimKit_paramsync_after paramsync ${COMMON[@]+"${COMMON[@]}"} -batch "$KIT/SimKit.java" "$CASE_SIM"; RCS[sync_after]=$RC
  invoke_star 13_SimKit_audit_after audit ${COMMON[@]+"${COMMON[@]}"} -batch "$KIT/SimKit.java" "$CASE_SIM"; RCS[audit2]=$RC
fi
if [ "$RUN_SOLVER" -eq 1 ]; then
  invoke_star 06_SimKit_run_audit_export "" ${COMMON[@]+"${COMMON[@]}"} -batch "run,$KIT/SimKit.java" -batch-report "$CASE_SIM"; RCS[run]=$RC
fi

# ------------------------------------------------------------------ PASTE_THIS.txt (raw, redacted below)
PASTE="$RESULTS/PASTE_THIS.txt"
RAW="$RESULTS/.paste_raw.txt"
PATTERNS='\[BuildTestSim\]|error:|Exception|Error:|\[SimKit\]|\[SimKitCalc\]|n/a \(|FAIL|WARN|PASS|batch:|licen|License|Could not|cannot|Self-check|GCI|Gamma|X=|p=|parameter sync|NONDEFAULT|PARAMSYNC|written|wrote|saved|exported|register|\b(S1|S2|S3|S4|S4b|S5|NP|NB|NA|OFF)\b|pending|p_(MESH|SOLV|STOP|BC|PHYS)_|naming issue|rule:|snapshot|non-default|after apply|no readable instance|inactive'
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
  for f in "$TEST_ROOT"/PROJECTS/ISO/00_admin/register_inbox/*; do [ -f "$f" ] && echo "register_inbox/$(basename "$f")"; done
  echo
  for pat in 'precheck_*.csv' 'setup_audit_*.csv' 'paramsync_*.csv'; do
    f=$(ls -t "$CASE"/audit/$pat 2>/dev/null | head -n 1)
    if [ -n "$f" ]; then
      echo "----- $(basename "$f") (first 60 lines; n/a rows first) -----"
      na=$(grep 'n/a' "$f" | head -n 30)
      nna=$(printf '%s' "$na" | grep -c .)
      [ -n "$na" ] && echo "$na"
      head -n $((60 - nna)) "$f"
      echo
    fi
  done
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
cp -R "$RESULTS" "$TMP/results"; cp -R "$TEST_ROOT/PROJECTS" "$TMP/PROJECTS"
find "$TMP" \( -name '*.sim' -o -name '*.sim~' \) -type f -delete
find "$TMP" -type f \( -name '*.log' -o -name '*.txt' -o -name '*.csv' -o -name '*.html' \) | while read -r f; do
  redact "$f" "$f.red" && mv "$f.red" "$f"
done
ARCHIVE="$TEST_ROOT/simkit_test_results_$STAMP.tar.gz"
tar -czf "$ARCHIVE" -C "$TMP" results PROJECTS
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
