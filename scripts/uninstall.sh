#!/usr/bin/env bash
# Removes AppleTV Wake and restores what scripts/setup.sh changed.
#
#   scripts/uninstall.sh <tv-ip>
set -euo pipefail

PKG=io.github.gauthierhuguenin.atvwake
SERVICE=$PKG/$PKG.WakeService
ROOT=$(cd "$(dirname "$0")/.." && pwd)

[ $# -ge 1 ] || { sed -n '2,4p' "$0"; exit 1; }
S=$1:5555
STATE=$ROOT/build/setup-state-$1.txt
tv() { adb -s "$S" shell "$@" </dev/null | tr -d '\r'; }

adb connect "$S" >/dev/null

# Packages disabled to replace the launcher (recorded by setup.sh), or the usual Google TV ones.
if [ -f "$STATE" ]; then pkgs=$(cat "$STATE"); else
    pkgs="com.google.android.apps.tv.launcherx com.google.android.tvlauncher com.google.android.tungsten.setupwraith"
fi
for p in $pkgs; do
    tv pm path "$p" >/dev/null 2>&1 && { echo "Enabling $p"; tv pm enable "$p" >/dev/null; }
done

current=$(tv settings get secure enabled_accessibility_services)
list=$(echo "$current" | tr ':' '\n' | grep -vx "$SERVICE" | paste -sd: - || true)
tv settings put secure enabled_accessibility_services "${list:-null}"
[ -n "$list" ] || tv settings put secure accessibility_enabled 0

tv dumpsys deviceidle whitelist -"$PKG" >/dev/null 2>&1 || true
adb -s "$S" uninstall "$PKG" | tail -1
rm -f "$STATE"
echo "AppleTV Wake removed. Press Home on the TV to pick the stock launcher again if asked."
