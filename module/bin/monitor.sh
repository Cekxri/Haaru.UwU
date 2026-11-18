#!/system/bin/sh
umask 077
MODDIR=$1
ABI=$(getprop ro.product.cpu.abi)
CARD_PID=
start_card_observer() {
    if [ -x "$MODDIR/bin/haaru-status-$ABI" ]; then
        "$MODDIR/bin/haaru-status-$ABI" --watch "$MODDIR" > /dev/null 2>&1 &
        CARD_PID=$!
    fi
}
ensure_card_observer() {
    if [ -z "$CARD_PID" ] || ! kill -0 "$CARD_PID" 2>/dev/null; then
        start_card_observer
    fi
}
start_card_observer
n=0
while [ "$(getprop sys.boot_completed)" != 1 ] && [ "$n" -lt 180 ]; do
    [ -f "$MODDIR/module.prop" ] || exit 0
    [ ! -f "$MODDIR/remove" ] && [ ! -f "$MODDIR/disable" ] && [ ! -f "$MODDIR/update" ] || exit 0
    ensure_card_observer
    sleep 1
    n=$((n + 1))
done
n=0
while [ -d "$MODDIR" ]; do
    [ -f "$MODDIR/module.prop" ] || break
    ensure_card_observer
    if [ -f "$MODDIR/disable" ] || [ -f "$MODDIR/remove" ] || [ -f "$MODDIR/update" ]; then
        break
    fi
    # Keep the original Binder/runtime refresh at 60s. Card liveness is cheap
    # and checked separately; the Rust observer reads module files every 2s.
    if [ "$n" = 0 ]; then
        "$MODDIR/bin/haaructl" status > /dev/null 2>&1
    fi
    sleep 2
    n=$(((n + 1) % 30))
done
