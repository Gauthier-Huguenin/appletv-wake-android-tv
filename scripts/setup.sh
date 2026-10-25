#!/usr/bin/env bash
# Installs and configures AppleTV Wake on an Android / Google TV.
#
#   scripts/setup.sh <tv-ip> [path/to/AppleTVWake.apk]
#
# Needs adb on the computer, and uv (https://docs.astral.sh/uv/) or Python 3.9-3.13 for the one-time
# Apple TV pairing (done with pyatv). Set CREDENTIALS_FILE to reuse existing Companion credentials.
set -euo pipefail

PKG=io.github.gauthierhuguenin.atvwake
REPO=Gauthier-Huguenin/appletv-wake-android-tv
SERVICE=$PKG/$PKG.WakeService
ROOT=$(cd "$(dirname "$0")/.." && pwd)

[ $# -ge 1 ] || { sed -n '2,8p' "$0"; exit 1; }
TV_IP=$1
APK=${2:-}
S=$TV_IP:5555
STATE=$ROOT/build/setup-state-$TV_IP.txt
mkdir -p "$ROOT/build"

say() { printf '\n\033[1m%s\033[0m\n' "$*"; }
ask() { local a; read -r -p "$1 " a; echo "$a"; }
# </dev/null: adb shell would otherwise swallow the answers typed for the next questions.
tv() { adb -s "$S" shell "$@" </dev/null | tr -d '\r'; }

command -v adb >/dev/null || { echo "adb not found (macOS: brew install android-platform-tools)"; exit 1; }

# --- 1. Connect ---------------------------------------------------------------------------
say "1/6 Connecting to the TV at $TV_IP"
adb connect "$S" >/dev/null || true
for _ in $(seq 1 30); do
    state=$(adb devices | awk -v d="$S" '$1==d {print $2}')
    [ "$state" = "device" ] && break
    [ "$state" = "unauthorized" ] && echo "Accept the 'Allow USB debugging' prompt on the TV (tick 'Always allow')..."
    sleep 2
    adb connect "$S" >/dev/null || true
done
[ "$(adb devices | awk -v d="$S" '$1==d {print $2}')" = "device" ] || { echo "Cannot connect. Is USB debugging on?"; exit 1; }
echo "Connected: $(tv getprop ro.product.brand) $(tv getprop ro.product.model), Android $(tv getprop ro.build.version.release)"

# --- 2. Install ---------------------------------------------------------------------------
say "2/6 Installing the app"
if [ -z "$APK" ]; then
    if [ -f "$ROOT/dist/AppleTVWake.apk" ]; then
        APK=$ROOT/dist/AppleTVWake.apk
    else
        APK=$ROOT/build/AppleTVWake.apk
        curl -sSfL -o "$APK" "https://github.com/$REPO/releases/latest/download/AppleTVWake.apk"
    fi
fi
adb -s "$S" install -r "$APK" </dev/null | tail -1

# --- 3. Inputs ----------------------------------------------------------------------------
say "3/6 Choosing the inputs"
raw=$(tv am broadcast -n "$PKG/.ConfigReceiver" --ez list_inputs true | sed -n 's/.*data="\(.*\)"/\1/p')
[ -n "$raw" ] || { echo "No HDMI input reported by the TV."; exit 1; }
IDS=(); LABELS=(); i=0
while IFS= read -r line; do
    IFS='|' read -r id label cec <<< "$line"
    i=$((i + 1))
    IDS+=("$id")
    LABELS+=("${cec:-$label}")
    printf '  %d) %-8s %s\n' "$i" "$label" "${cec:+($cec)}"
done <<< "$(echo "$raw" | tr ';' '\n' | sort -t'|' -k2)"
main_i=$(ask "Number of the Apple TV input:")
second_i=$(ask "Number of the second device (empty if none):")
MAIN_INPUT=${IDS[$((main_i - 1))]}
MAIN_LABEL="Apple TV"
SECOND_INPUT=""; SECOND_LABEL=""
if [ -n "$second_i" ]; then
    SECOND_INPUT=${IDS[$((second_i - 1))]}
    SECOND_LABEL=${LABELS[$((second_i - 1))]}
    l=$(ask "Name shown when switching to it [$SECOND_LABEL]:")
    SECOND_LABEL=${l:-$SECOND_LABEL}
fi

# --- 4. Apple TV pairing ------------------------------------------------------------------
say "4/6 Pairing with the Apple TV"
if command -v uvx >/dev/null; then
    ATVREMOTE=(uvx --python 3.12 --from pyatv atvremote)
else
    PY=""
    for c in python3.13 python3.12 python3.11 python3.10 python3.9 python3; do
        if command -v "$c" >/dev/null && "$c" -c 'import sys; sys.exit(not (3, 9) <= sys.version_info[:2] <= (3, 13))'; then
            PY=$c; break
        fi
    done
    [ -n "$PY" ] || { echo "Install uv, or Python 3.9-3.13 (pyatv does not run on 3.14 yet)."; exit 1; }
    [ -x "$ROOT/build/venv/bin/atvremote" ] || { "$PY" -m venv "$ROOT/build/venv" && "$ROOT/build/venv/bin/pip" -q install pyatv; }
    ATVREMOTE=("$ROOT/build/venv/bin/atvremote")
fi
echo "Looking for Apple TVs..."
scan=$("${ATVREMOTE[@]}" scan </dev/null 2>/dev/null || true)
names=(); addrs=()
while IFS= read -r l; do
    case "$l" in
        *"Name: "*) n=${l#*Name: } ;;
        *"Model/SW: "*) m=${l#*Model/SW: } ;;
        *"Address: "*) a=${l#*Address: }; case "$m" in *"Apple TV"*) names+=("$n"); addrs+=("$a") ;; esac ;;
    esac
done <<< "$scan"
[ ${#names[@]} -gt 0 ] || { echo "No Apple TV found on the network."; exit 1; }
for k in "${!names[@]}"; do printf '  %d) %s (%s)\n' $((k + 1)) "${names[$k]}" "${addrs[$k]}"; done
k=0
[ ${#names[@]} -gt 1 ] && k=$(( $(ask "Number of your Apple TV:") - 1 ))
ATV_NAME=${names[$k]}
ATV_ADDR=${addrs[$k]}

if [ -n "${CREDENTIALS_FILE:-}" ]; then
    CREDENTIALS=$(tr -d '[:space:]' < "$CREDENTIALS_FILE")
else
    if echo "$scan" | grep -A12 "Address: $ATV_ADDR" | grep "Protocol: Companion" | grep -q "Pairing: Disabled"; then
        echo "The Apple TV refuses pairing. On the Apple TV: Settings > AirPlay and HomeKit > Allow Access >"
        echo "'Everyone on the Same Network'. You can set it back once paired."
        ask "Press Enter when done." >/dev/null
    fi
    echo "A 4-digit code will appear on the Apple TV (switch the TV to it)."
    out=$("${ATVREMOTE[@]}" -s "$ATV_ADDR" --protocol companion pair | tee /dev/tty)
    CREDENTIALS=$(echo "$out" | sed -n 's/.*credentials: *\([0-9a-fA-F:]*\).*/\1/p' | tail -1)
    [ -n "$CREDENTIALS" ] || { echo "Pairing failed."; exit 1; }
fi

tv "am broadcast -n $PKG/.ConfigReceiver --es credentials '$CREDENTIALS' --es apple_tv_name '$ATV_NAME' \
    --es main_input '$MAIN_INPUT' --es main_label '$MAIN_LABEL' \
    --es second_input '$SECOND_INPUT' --es second_label '$SECOND_LABEL'" | grep -q 'data="ok"' \
    || { echo "The app did not accept the configuration."; exit 1; }
echo "Configuration saved on the TV."

# --- 5. Permissions -----------------------------------------------------------------------
say "5/6 Letting the app run in the background"
# TCL firmware blocks apps from starting at boot unless they hold this app-op.
tv appops set "$PKG" APP_AUTO_START allow >/dev/null 2>&1 || true
# Keeps network access in Doze, so the Apple TV connection survives standby.
tv dumpsys deviceidle whitelist +"$PKG" >/dev/null
current=$(tv settings get secure enabled_accessibility_services)
case "$current" in null|"") list=$SERVICE ;; *"$SERVICE"*) list=$current ;; *) list=$current:$SERVICE ;; esac
# Some firmwares only bind a newly enabled service after the list is cleared and written again.
tv settings put secure enabled_accessibility_services null
tv settings put secure accessibility_enabled 0
sleep 3
tv settings put secure enabled_accessibility_services "$list"
tv settings put secure accessibility_enabled 1
sleep 8
if tv dumpsys accessibility | grep "Bound services" | grep -q "AppleTV Wake"; then
    echo "Service running."
else
    echo "The service is enabled but not running yet. On TCL: Safety Guard > Permission Shield >"
    echo "Auto Launch Permission > allow AppleTV Wake, then run this script again."
fi

# --- 6. Optional Home replacement ---------------------------------------------------------
say "6/6 Home screen (optional)"
echo "AppleTV Wake can replace the stock launcher, so the TV never shows it and always goes to the Apple TV."
if [ "$(ask "Replace the launcher? [y/N]")" = "y" ]; then
    : > "$STATE"
    tv cmd package set-home-activity "$PKG/.HomeActivity" >/dev/null
    for _ in 1 2 3; do
        home=$(tv cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1)
        hpkg=${home%%/*}
        [ "$hpkg" = "$PKG" ] && break
        case "$hpkg" in com.android.tv.settings|com.android.settings|android|"")
            echo "Home resolves to $hpkg, which must stay enabled. Stopping here."; break ;; esac
        if [ "$hpkg" = "com.google.android.tungsten.setupwraith" ] && \
           tv cmd wifi status | grep -q "connected" && \
           [ "$(tv dumpsys wifi | grep -c 'cname=com.google.android.tungsten.setupwraith')" -gt 0 ]; then
            echo "Warning: the setup wizard owns a saved Wi-Fi network; disabling it deletes that network."
            echo "If it is the network in use, forget and re-join it in Settings first."
            [ "$(ask "Disable the setup wizard anyway? [y/N]")" = "y" ] || break
        fi
        echo "Disabling $hpkg"
        tv pm disable-user --user 0 "$hpkg" >/dev/null
        echo "$hpkg" >> "$STATE"
    done
    home=$(tv cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1)
    echo "Home is now: $home"
fi

say "Done."
echo "- Turn the Apple TV off with its remote, wait a few minutes, wake it: the TV turns on with it."
[ -n "$SECOND_INPUT" ] && echo "- Double press mute on the Apple TV remote to switch between Apple TV and $SECOND_LABEL."
echo "- Log: adb -s $S shell cat /sdcard/Android/data/$PKG/files/log.txt"
echo "- Undo everything: scripts/uninstall.sh $TV_IP"
