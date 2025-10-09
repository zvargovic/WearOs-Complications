#!/usr/bin/env bash
set -euo pipefail

PKG="com.example.complicationprovider"
OUT_DIR="./device_logs"

# Internal i External (app-specific) putanje
INT_DIR="files/logs"                                        # /data/data/<pkg>/files/logs
EXT_DIR="/sdcard/Android/data/$PKG/files/logs"              # app-specific external

adb start-server >/dev/null || true
echo "===> Provjeravam uređaj…"
adb get-state 1>/dev/null

mkdir -p "$OUT_DIR"

# Helper: tar preko run-as i raspakiraj lokalno
pull_via_run_as () {
  local REMOTE_DIR="$1"
  local DEST_DIR="$2"
  echo "===> Pokušavam: $REMOTE_DIR (run-as $PKG)"
  # Ako dir ne postoji, `cd` će failati; preuzmi to kao signal
  if adb exec-out run-as "$PKG" sh -c "cd \"$REMOTE_DIR\" 2>/dev/null && tar -cf - . 2>/dev/null" | tar -xf - -C "$DEST_DIR" 2>/dev/null; then
    echo "===> OK: Povučeno iz $REMOTE_DIR → $DEST_DIR"
    return 0
  else
    return 1
  fi
}

# 1) Internal (/data/data/…)
if pull_via_run_as "$INT_DIR" "$OUT_DIR"; then
  echo "===> Gotovo. Logovi su u: $OUT_DIR"
  exit 0
fi

# 2) External app-specific (/sdcard/Android/data/<pkg>/files/…)
#   Napomena: direktni `adb pull` često ne radi na 11+, zato opet preko run-as (app UID ima pristup).
if pull_via_run_as "$EXT_DIR" "$OUT_DIR"; then
  echo "===> Gotovo. Logovi su u: $OUT_DIR"
  exit 0
fi

echo "!! Nisam uspio povući logove."
echo "   Dijagnostika:"
echo "   - Je li build debuggable?  (run-as radi samo na debuggable)"
echo "       adb shell run-as $PKG id"
echo "   - Postoji li uopće logs dir?"
echo "       adb shell run-as $PKG ls -R files 2>/dev/null | sed -n '1,200p'"
echo "       adb shell run-as $PKG ls -R /sdcard/Android/data/$PKG/files 2>/dev/null | sed -n '1,200p'"
exit 1
