#!/system/bin/sh
umask 077
MODDIR=$1
PHASE=${2:-retry}
active_module() {
    [ -f "$MODDIR/module.prop" ] && [ ! -f "$MODDIR/remove" ] &&
    [ ! -f "$MODDIR/disable" ] && [ ! -f "$MODDIR/update" ]
}
pending() {
    [ ! -L "$MODDIR/data" ] && [ ! -L "$MODDIR/data/companion-install.json" ] || return 1
    mkdir -p "$MODDIR/data" || return 1
    printf '%s\n' '{"state":"pending","stage":"android-ready","message":"Android 還在暖機，開機後會繼續安裝 App。 / Android is still warming up; App installation will continue after boot.","verified":false}' > "$MODDIR/data/companion-install.json"
}
failure() {
    [ ! -L "$MODDIR/data" ] && [ ! -L "$MODDIR/data/companion-install.json" ] || return 1
    mkdir -p "$MODDIR/data" || return 1
    if [ "${2:-app-process}" = payload ]; then
        printf '{"state":"error","stage":"payload","message":"App 安裝包不完整，請重新下載模組 ZIP。 / The App payload is incomplete; please download the module ZIP again.","verified":false,"exitCode":%s}\n' "$1" > "$MODDIR/data/companion-install.json"
    else
        printf '{"state":"error","stage":"app-process","message":"App 安裝程序沒有回報完成；本機診斷已保留。 / The App installer did not return a valid result; the local diagnostic has been kept.","verified":false,"exitCode":%s}\n' "$1" > "$MODDIR/data/companion-install.json"
    fi
}
attempt() {
    if [ ! -x /system/bin/app_process ] || [ "$(getprop sys.boot_completed)" != 1 ]; then
        pending
        return 3
    fi
    [ ! -L "$MODDIR/data" ] && [ ! -L "$MODDIR/data/companion-install-stderr.log" ] || return 2
    mkdir -p "$MODDIR/data" || return 2
    HAARU_INSTALL_OUT=$(mktemp "$MODDIR/data/.companion-output.XXXXXX") || return 2
    HAARU_INSTALL_EXIT=$(mktemp "$MODDIR/data/.companion-exit.XXXXXX") || { rm -f "$HAARU_INSTALL_OUT"; return 2; }
    (
        if [ -x /system/bin/timeout ]; then
            CLASSPATH="$MODDIR/bin/haaru.dex" /system/bin/timeout 180 /system/bin/app_process /system/bin \
                com.github.haaru.ksu.CompanionInstallation --module "$MODDIR" 2>&1 > "$HAARU_INSTALL_OUT"
        else
            CLASSPATH="$MODDIR/bin/haaru.dex" /system/bin/app_process /system/bin \
                com.github.haaru.ksu.CompanionInstallation --module "$MODDIR" 2>&1 > "$HAARU_INSTALL_OUT"
        fi
        printf '%s' "$?" > "$HAARU_INSTALL_EXIT"
    ) | { head -c 16384 > "$MODDIR/data/companion-install-stderr.log"; cat >/dev/null; }
    HAARU_INSTALL_CODE=$(cat "$HAARU_INSTALL_EXIT")
    HAARU_INSTALL_RESULT=$(head -c 16384 "$HAARU_INSTALL_OUT")
    rm -f "$HAARU_INSTALL_OUT" "$HAARU_INSTALL_EXIT"
    printf '%s\n' "$HAARU_INSTALL_RESULT"
    case "$HAARU_INSTALL_RESULT" in
        *'"state":"installed"'*|*'"state":"ready"'*|*'"state":"newer-kept"'*) [ "$HAARU_INSTALL_CODE" = 0 ] && return 0 ;;
        *'"state":"error"'*) return 2 ;;
        *'"state":"pending"'*) [ "$HAARU_INSTALL_CODE" = 0 ] && return 3 ;;
    esac
    case "$HAARU_INSTALL_CODE" in *[!0-9]*|'') HAARU_INSTALL_CODE=2 ;; esac
    failure "$HAARU_INSTALL_CODE"
    return 2
}
[ -f "$MODDIR/module.prop" ] || exit 2
case "$PHASE" in provision|retry|boot) ;; *) exit 2 ;; esac
if [ "$PHASE" != provision ] && ! active_module; then
    [ "$PHASE" != boot ] || exit 0
    exit 2
fi
if [ ! -f "$MODDIR/app/install.prop" ] || [ ! -f "$MODDIR/app/Haaru.apk" ]; then
    failure 2 payload
    exit 2
fi
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
