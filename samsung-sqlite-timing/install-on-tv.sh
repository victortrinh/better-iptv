#!/usr/bin/env bash
# Packages app/ with your certificate, installs it on the TV and starts it.
# README.md, "Install it on the TV", says what must be done first.
#   ./install-on-tv.sh <TV's IP address> <certificate profile name>
set -euo pipefail
tv="${1:?Give the TV's IP address first, then your certificate profile's name}"
profile="${2:?Give your certificate profile's name after the TV's IP address}"
here="$(cd "$(dirname "$0")" && pwd)"
studio="${TIZEN_STUDIO:-$HOME/tizen-studio}"
tizen="$studio/tools/ide/bin/tizen"
sdb="$studio/tools/sdb"
[ -x "$tizen" ] && [ -x "$sdb" ] || { echo "Tizen Studio's tools aren't in $studio. If it is installed elsewhere: TIZEN_STUDIO=/path/to/tizen-studio $0 $*" >&2; exit 1; }

echo "1/4 Connecting to the TV at $tv…"
"$sdb" connect "$tv:26101"
"$sdb" devices
"$sdb" devices | grep -q "^$tv:26101[[:space:]]*device" || {
  echo "The TV did not accept the connection. Check that Developer mode is on, that Host PC IP is this Mac's address, and that the TV was restarted afterwards." >&2
  exit 1
}

echo "2/4 Packaging with the certificate profile '$profile'…"
rm -f "$here"/app/*.wgt
"$tizen" package -t wgt -s "$profile" -- "$here/app"
package="$(ls "$here"/app/*.wgt 2>/dev/null | head -1)"
[ -n "$package" ] || { echo "Packaging left no .wgt in app/. Read what 'tizen package' printed above: a wrong profile name is the usual cause." >&2; exit 1; }

echo "3/4 Installing $(basename "$package")…"
"$tizen" install -n "$(basename "$package")" -s "$tv:26101" -- "$here/app"

echo "4/4 Starting it…"
"$tizen" run -p ZugigSqlT1.SqliteTiming -s "$tv:26101"
echo "Done: look at the TV. The run starts by itself and takes from seconds to a few minutes."
