#!/system/bin/sh
umask 077
MODDIR=$1
PHASE=${2:-once}
active_module() {
    [ -f "$MODDIR/module.prop" ] && [ ! -f "$MODDIR/remove" ] &&
    [ ! -f "$MODDIR/disable" ] && [ ! -f "$MODDIR/update" ]
}
pending() {
    [ ! -L "$MODDIR/data" ] && [ ! -L "$MODDIR/data/companion-install.json" ] || return 1
    mkdir -p "$MODDIR/data" || return 1
    printf '%s\n' '{"state":"pending","message":"Android is not ready; App installation will continue after boot","verified":false}' > "$MODDIR/data/companion-install.json"
}
attempt() {
    if [ ! -x /system/bin/app_process ] || [ "$(getprop sys.boot_completed)" != 1 ]; then
        pending
        return 3
    fi
    HAARU_INSTALL_RESULT=$(CLASSPATH="$MODDIR/bin/haaru.dex" /system/bin/app_process /system/bin \
        com.github.haaru.ksu.CompanionInstallation --module "$MODDIR" 2>/dev/null)
    printf '%s\n' "$HAARU_INSTALL_RESULT"
    case "$HAARU_INSTALL_RESULT" in
        *'"state":"installed"'*|*'"state":"ready"'*|*'"state":"newer-kept"'*) return 0 ;;
        *'"state":"error"'*) return 2 ;;
        *'"state":"pending"'*) return 3 ;;
        *) pending; return 3 ;;
    esac
}
[ -f "$MODDIR/module.prop" ] && [ -f "$MODDIR/app/install.prop" ] && [ -f "$MODDIR/app/Haaru.apk" ] || exit 2
if [ "$PHASE" != boot ]; then
    attempt
    exit $?
fi
HAARU_INSTALL_ATTEMPTS=0
while active_module && [ "$HAARU_INSTALL_ATTEMPTS" -lt 40 ]; do
    attempt
    HAARU_INSTALL_CODE=$?
    [ "$HAARU_INSTALL_CODE" -eq 3 ] || exit "$HAARU_INSTALL_CODE"
    HAARU_INSTALL_ATTEMPTS=$((HAARU_INSTALL_ATTEMPTS + 1))
    sleep 15
done
