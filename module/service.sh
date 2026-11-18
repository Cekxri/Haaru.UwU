#!/system/bin/sh
MODDIR=${0%/*}
/system/bin/sh "$MODDIR/bin/finalize-upgrade.sh" "$MODDIR"
"$MODDIR/bin/monitor.sh" "$MODDIR" &
/system/bin/sh "$MODDIR/bin/companion.sh" "$MODDIR" &
/system/bin/sh "$MODDIR/bin/install-companion.sh" "$MODDIR" boot >/dev/null 2>&1 &

