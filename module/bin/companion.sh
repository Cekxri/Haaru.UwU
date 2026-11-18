#!/system/bin/sh
umask 077
MODDIR=$1
active_module() {
    [ -f "$MODDIR/module.prop" ] && [ -f "$MODDIR/companion.prop" ] &&
    [ ! -f "$MODDIR/disable" ] && [ ! -f "$MODDIR/remove" ] && [ ! -f "$MODDIR/update" ]
}
while active_module; do
    [ ! -L "$MODDIR/data" ] || exit 0
    mkdir -p "$MODDIR/data" || exit 0
    [ ! -L "$MODDIR/data/companion.pid" ] && [ ! -L "$MODDIR/data/companion.log" ] || exit 0
    HAARU_PID=$(cat "$MODDIR/data/companion.pid" 2>/dev/null)
    case "$HAARU_PID" in *[!0-9]*|'') HAARU_PID= ;; esac
    if [ -n "$HAARU_PID" ]; then
        HAARU_NAME=$(tr '\000' '\n' < "/proc/$HAARU_PID/cmdline" 2>/dev/null | head -n 1)
        HAARU_UID=$(sed -n 's/^Uid:[[:space:]]*\([0-9]*\).*/\1/p' "/proc/$HAARU_PID/status" 2>/dev/null)
        if [ "$HAARU_NAME" != haaru-companion ] || [ "$HAARU_UID" != 0 ]; then HAARU_PID=; fi
    fi
    if [ -z "$HAARU_PID" ] || ! kill -0 "$HAARU_PID" 2>/dev/null; then
        CLASSPATH="$MODDIR/bin/haaru.dex" /system/bin/app_process /system/bin --nice-name=haaru-companion \
            com.github.haaru.ksu.CompanionServer --module "$MODDIR" > "$MODDIR/data/companion.log" 2>&1 &
    fi
    sleep 5
done
