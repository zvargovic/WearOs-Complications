#!/usr/bin/env bash
set -euo pipefail

# Ako proslijediš IP i port kao argument, koristi ih, inače pitaj.
if [ $# -ge 1 ]; then
  TARGET="$1"
else
  read -rp "Unesi device IP:PORT (npr. 192.168.1.100:5555): " TARGET
fi

if [ -z "$TARGET" ]; then
  echo "Nije unesen IP:PORT. Prekidam."
  exit 2
fi

echo "Killing existing adb processes..."
# pokušaj pkill/killall, fallback na ps+kill
if command -v pkill >/dev/null 2>&1; then
  pkill -f adb || true
elif command -v killall >/dev/null 2>&1; then
  killall adb || true
else
  ps aux | grep -i '[a]db' | awk '{print $2}' | xargs -r kill -9 || true
fi

sleep 0.2

echo "Starting adb server..."
adb start-server

sleep 0.3

echo "Pairing with $TARGET (if supported by adb)..."
if adb help | grep -q "pair"; then
  # adb pair će obično ispisati PIN ili tražiti interakciju
  # pokušaj izvršiti pair i prikaži izlaz korisniku
  adb pair "$TARGET" || echo "adb pair nije uspio ili nije podržan za ovaj uređaj."
else
  echo "adb pair nije dostupan u ovoj adb verziji. Preskačem pair korak."
fi

echo "Connecting to $TARGET..."
adb connect "$TARGET"

echo "adb devices:"
adb devices -l

echo "Gotovo."
