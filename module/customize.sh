#!/system/bin/sh
SKIPUNZIP=0
ui_print "- Haaru"
API=$(getprop ro.build.version.sdk)
[ "$API" -ge 29 ] 2>/dev/null || abort "Requires Android 10+ with supported telephony getter interfaces"
[ -f "$MODPATH/bin/haaru.dex" ] || abort "Missing compiled helper; install the built release ZIP"
[ -f "$MODPATH/bin/hook.dex" ] || abort "Missing compiled getter hooks; install the built release ZIP"
ABI=$(getprop ro.product.cpu.abi)
[ -f "$MODPATH/zygisk/$ABI.so" ] || abort "No compiled Zygisk library for this ABI"
[ -f "$MODPATH/bin/haaru-upgrade-$ABI" ] || abort "Missing compiled Rust settings migrator for this ABI"
chmod 0755 "$MODPATH/bin/haaru-upgrade-$ABI" || abort "Cannot enable the Rust settings migrator"
[ -f "$MODPATH/bin/haaru-status-$ABI" ] || abort "Missing Haaru card observer for this ABI"
# Legacy source discovery is based on verified module metadata, not an old ID.
touch "$MODPATH/skip_mount"
mkdir -p "$MODPATH/data" || abort "Cannot prepare module-owned runtime storage"
. "$MODPATH/bin/discover-upgrade.sh"
HAARU_OLDMOD=$(haaru_find_upgrade /data/adb/modules haaru) || abort "Cannot select one verified Haaru settings source."
HAARU_UPGRADE=0
if [ -n "$HAARU_OLDMOD" ] && [ "$MODPATH" != "$HAARU_OLDMOD" ]; then
    ui_print "- Preserving saved SIM profiles and appearance from the installed module."
    # KSU may discover this stage after an interrupted installation. Keep it
    # disabled until the helper's final, verified lifecycle-marker commit.
    touch "$MODPATH/disable" || abort "Cannot protect the staged settings migration."
    if [ -x /system/bin/timeout ]; then
        HAARU_RESULT=$(/system/bin/timeout 45 "$MODPATH/bin/haaru-upgrade-$ABI" "$HAARU_OLDMOD" "$MODPATH") || abort "Settings migration failed; the installed settings were not changed. $HAARU_RESULT"
    else
        HAARU_RESULT=$("$MODPATH/bin/haaru-upgrade-$ABI" "$HAARU_OLDMOD" "$MODPATH") || abort "Settings migration failed; the installed settings were not changed. $HAARU_RESULT"
    fi
    case "$HAARU_RESULT" in
        *'"ok":true'*) ;;
        *) abort "Settings migration was not successful; the installed module was not changed." ;;
    esac
    case "$HAARU_RESULT" in
        *'"verified":true'*) HAARU_UPGRADE=1 ;;
        *) abort "Settings migration was not verified; the installed module was not changed." ;;
    esac
fi
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm_recursive "$MODPATH/data" 0 0 0700 0600
set_perm "$MODPATH/bin/haaructl" 0 0 0755
set_perm "$MODPATH/bin/monitor.sh" 0 0 0755
set_perm "$MODPATH/bin/companion.sh" 0 0 0755
set_perm "$MODPATH/bin/install-companion.sh" 0 0 0755
set_perm "$MODPATH/bin/finalize-upgrade.sh" 0 0 0755
set_perm "$MODPATH/action.sh" 0 0 0755
for HAARU_MIGRATOR in "$MODPATH"/bin/haaru-upgrade-*; do
    [ ! -f "$HAARU_MIGRATOR" ] || set_perm "$HAARU_MIGRATOR" 0 0 0755
done
for HAARU_OBSERVER in "$MODPATH"/bin/haaru-status-*; do
    [ ! -f "$HAARU_OBSERVER" ] || set_perm "$HAARU_OBSERVER" 0 0 0755
done
if [ "$HAARU_UPGRADE" = 1 ]; then
    ui_print "- Saved profiles, enabled choices and appearance were preserved."
    ui_print "- Runtime acknowledgments and original-value caches will be rebuilt."
    ui_print "- Reboot now to finish updating; do not edit the old version before reboot."
else
    ui_print "- No country is enabled on a new installation."
fi
ui_print "- Requires a working Zygisk API 4 provider."
ui_print "- This ZIP includes the matching signed Haaru App."
/system/bin/sh "$MODPATH/bin/install-companion.sh" "$MODPATH" once >/dev/null 2>&1
HAARU_APP_INSTALL_CODE=$?
case "$HAARU_APP_INSTALL_CODE" in
    0) ui_print "- App installation was verified, or a newer compatible App was kept." ;;
    3) ui_print "- Android is not ready. App installation will continue automatically after boot." ;;
    *) ui_print "- App installation needs attention. Check data/companion-install.json; existing App was kept." ;;
esac
ui_print "- App connects through the module; no separate Root grant is needed."
ui_print "- The module play button opens the Haaru App."
ui_print "- Wait for the central phone engine to confirm application."
ui_print "- No persistent CarrierConfig, subscription database or eSIM changes."
ui_print "- Settings stay inside this module. KernelSU removes them with the module."
if [ "$HAARU_UPGRADE" = 1 ] && [ "${HAARU_OLDMOD##*/}" != haaru ]; then
    HAARU_SOURCE_ID=${HAARU_OLDMOD##*/}
    HAARU_ORIGINAL_DISABLE=0
    [ ! -f "$HAARU_OLDMOD/disable" ] || HAARU_ORIGINAL_DISABLE=1
    [ ! -L "$HAARU_OLDMOD/disable" ] || abort "Unsafe source lifecycle marker."
    HAARU_SOURCE_VERSION=$(haaru_property "$HAARU_OLDMOD/module.prop" version)
    HAARU_SOURCE_CODE=$(haaru_property "$HAARU_OLDMOD/module.prop" versionCode)
    case "$HAARU_SOURCE_VERSION" in *[!A-Za-z0-9._+-]*|'') abort "Unsafe source version metadata." ;; esac
    case "$HAARU_SOURCE_CODE" in *[!0-9]*|'') abort "Unsafe source version code." ;; esac
    HAARU_HANDOFF="$MODPATH/data/upgrade-handoff.properties"
    [ ! -L "$HAARU_HANDOFF" ] || abort "Unsafe migration handoff path."
    HAARU_SOURCE_STATE=$(haaru_file_hash "$HAARU_OLDMOD/data/state.properties") || abort "Cannot bind source settings snapshot."
    HAARU_SOURCE_PREFS=$(haaru_file_hash "$HAARU_OLDMOD/webroot/appearance.json") || abort "Cannot bind source appearance snapshot."
    HAARU_TARGET_STATE=$(haaru_file_hash "$MODPATH/data/state.properties") || abort "Cannot verify copied settings snapshot."
    HAARU_TARGET_PREFS=$(haaru_file_hash "$MODPATH/webroot/appearance.json") || abort "Cannot verify copied appearance snapshot."
    printf 'sourceId=%s\nsourceVersion=%s\nsourceVersionCode=%s\ntargetVersion=%s\ntargetVersionCode=%s\nsourceStateSha256=%s\nsourcePrefsSha256=%s\ntargetStateSha256=%s\ntargetPrefsSha256=%s\n' \
        "$HAARU_SOURCE_ID" "$HAARU_SOURCE_VERSION" "$HAARU_SOURCE_CODE" \
        "$(haaru_property "$MODPATH/module.prop" version)" "$(haaru_property "$MODPATH/module.prop" versionCode)" \
        "$HAARU_SOURCE_STATE" "$HAARU_SOURCE_PREFS" "$HAARU_TARGET_STATE" "$HAARU_TARGET_PREFS" \
        > "$HAARU_HANDOFF" || abort "Cannot save verified migration handoff."
    ui_print "- Previous Haaru module will be paused; removal is finalized only after this module boots with verified settings."
    if ! touch "$HAARU_OLDMOD/disable"; then
        rm -f "$HAARU_HANDOFF"
        [ "$HAARU_ORIGINAL_DISABLE" = 1 ] || rm -f "$HAARU_OLDMOD/disable"
        abort "Cannot pause the verified source module."
    fi
fi

