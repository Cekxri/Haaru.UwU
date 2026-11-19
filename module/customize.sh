#!/system/bin/sh
SKIPUNZIP=0
ui_print "☕ Haaru 咖啡廳，開始準備囉 / Haaru café — getting ready"
API=$(getprop ro.build.version.sdk)
[ "$API" -ge 29 ] 2>/dev/null || abort "🍞 需要 Android 10 或更新版本 / Android 10 or later is needed"
[ -f "$MODPATH/bin/haaru.dex" ] || abort "🍞 安裝包不完整，請重新下載 ZIP / The ZIP is incomplete; please download it again"
[ -f "$MODPATH/bin/hook.dex" ] || abort "🍞 安裝包不完整，請重新下載 ZIP / The ZIP is incomplete; please download it again"
ABI=$(getprop ro.product.cpu.abi)
[ -f "$MODPATH/zygisk/$ABI.so" ] || abort "🍞 此 CPU 架構沒有對應成品 / No matching build is available for this CPU architecture"
[ -f "$MODPATH/bin/haaru-upgrade-$ABI" ] || abort "🍞 搬家工具不完整，舊設定保留 / The settings mover is missing; previous settings are safe"
chmod 0755 "$MODPATH/bin/haaru-upgrade-$ABI" || abort "🍞 搬家工具暫時不能啟動 / The settings mover cannot start yet"
[ -f "$MODPATH/bin/haaru-status-$ABI" ] || abort "🍞 安裝包少了狀態小幫手 / The ZIP is missing its status helper"
# Legacy source discovery is based on verified module metadata, not an old ID.
touch "$MODPATH/skip_mount"
mkdir -p "$MODPATH/data" || abort "🍞 暫時無法準備設定空間 / The settings space could not be prepared"
. "$MODPATH/bin/discover-upgrade.sh"
HAARU_OLDMOD=$(haaru_find_upgrade /data/adb/modules haaru) || abort "🍞 找到多個或無法確認的舊模組，沒有猜選 / Several or unverified previous modules were found; none was guessed"
HAARU_UPGRADE=0
if [ -n "$HAARU_OLDMOD" ] && [ "$MODPATH" != "$HAARU_OLDMOD" ]; then
    ui_print "☕ 正在替設定和外觀搬家 / Moving your saved settings and appearance"
    # KSU may discover this stage after an interrupted installation. Keep it
    # disabled until the helper's final, verified lifecycle-marker commit.
    touch "$MODPATH/disable" || abort "🍞 搬家保護沒有準備好，先停一下 / The move could not be protected; pausing here"
    if [ -x /system/bin/timeout ]; then
        HAARU_RESULT=$(/system/bin/timeout 45 "$MODPATH/bin/haaru-upgrade-$ABI" "$HAARU_OLDMOD" "$MODPATH") || abort "🍞 搬家沒有完成，舊設定留好了 / The move did not finish; previous settings are safe"
    else
        HAARU_RESULT=$("$MODPATH/bin/haaru-upgrade-$ABI" "$HAARU_OLDMOD" "$MODPATH") || abort "🍞 搬家沒有完成，舊設定留好了 / The move did not finish; previous settings are safe"
    fi
    case "$HAARU_RESULT" in
        *'"ok":true'*) ;;
        *) abort "🍞 搬家沒有完成，舊模組保留 / The move did not finish; the previous module was kept" ;;
    esac
    case "$HAARU_RESULT" in
        *'"verified":true'*) HAARU_UPGRADE=1 ;;
        *) abort "🍞 尚未確認搬家完成，舊模組保留 / The move is not confirmed; the previous module was kept" ;;
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
    ui_print "🍞 設定、啟用選項與外觀都留好了 / Your settings, choices and appearance have been kept"
    ui_print "☁️ 開機後會重新讀取狀態 / Status will be read afresh after reboot"
    ui_print "☕ 完整重新開機就能完成更新；先讓舊版休息 / Fully reboot to finish the update; let the old version rest until then"
else
    ui_print "🍞 新安裝還沒選地區，等你慢慢挑 / A fresh install has no chosen region; take your time"
fi
ui_print "☁️ 請保持 Zygisk 正常運作 / Keep your Zygisk provider running"
ui_print "☕ 同版本 Haaru App 已帶來囉 / The matching Haaru App is included"
HAARU_APP_INSTALL_RESULT=$(/system/bin/sh "$MODPATH/bin/install-companion.sh" "$MODPATH" provision 2>/dev/null)
HAARU_APP_INSTALL_CODE=$?
case "$HAARU_APP_INSTALL_CODE" in
    0)
        case "$HAARU_APP_INSTALL_RESULT" in
            *'"state":"newer-kept"'*) ui_print "🍧 相容的較新 App 留好了，不會降版 / Your compatible newer App was kept; no downgrade" ;;
            *'"state":"ready"'*) ui_print "🍧 同版本 App 已經在這裡囉 / The matching App is already here" ;;
            *) ui_print "🍧 App 安裝確認完成，Nya / App installation is confirmed, Nya" ;;
        esac ;;
    3) ui_print "☁️ Android 還在暖機，開機後會繼續安裝 App / Android is still warming up; App installation will continue after boot" ;;
    *) ui_print "🍞 App 安裝尚未確認；可按模組執行鍵重試，本機診斷已保留 / App installation is not confirmed yet; use the module action to retry. The local diagnostic is kept" ;;
esac
ui_print "☕ App 透過模組連線，不必另外授予 Root / The App connects through the module; no separate Root grant is needed"
ui_print "🍧 模組執行鍵會打開 Haaru App / The module action opens Haaru"
ui_print "☁️ 設定生效要等介面確認，先喝口咖啡 / Wait for the confirmed applied status; coffee first"
ui_print "🍞 不寫入 SIM 或 eSIM 的永久資料 / Permanent SIM and eSIM data are not written"
ui_print "☕ 設定留在模組裡，移除模組時一起清理 / Settings stay in the module and are cleared when it is removed"
if [ "$HAARU_UPGRADE" = 1 ] && [ "${HAARU_OLDMOD##*/}" != haaru ]; then
    HAARU_SOURCE_ID=${HAARU_OLDMOD##*/}
    HAARU_ORIGINAL_DISABLE=0
    [ ! -f "$HAARU_OLDMOD/disable" ] || HAARU_ORIGINAL_DISABLE=1
    [ ! -L "$HAARU_OLDMOD/disable" ] || abort "🍞 舊模組狀態無法安全確認 / The previous module state could not be safely checked"
    HAARU_SOURCE_VERSION=$(haaru_property "$HAARU_OLDMOD/module.prop" version)
    HAARU_SOURCE_CODE=$(haaru_property "$HAARU_OLDMOD/module.prop" versionCode)
    case "$HAARU_SOURCE_VERSION" in *[!A-Za-z0-9._+-]*|'') abort "🍞 舊版本資料無法確認 / The previous version could not be checked" ;; esac
    case "$HAARU_SOURCE_CODE" in *[!0-9]*|'') abort "🍞 舊版本資料無法確認 / The previous version could not be checked" ;; esac
    HAARU_HANDOFF="$MODPATH/data/upgrade-handoff.properties"
    [ ! -L "$HAARU_HANDOFF" ] || abort "🍞 搬家交接路徑不安全 / The move handoff path is unsafe"
    HAARU_SOURCE_STATE=$(haaru_file_hash "$HAARU_OLDMOD/data/state.properties") || abort "🍞 舊設定快照無法確認，先保留原樣 / The previous settings snapshot could not be checked; keeping it as it is"
    HAARU_SOURCE_PREFS_PATH=$(haaru_source_prefs_path "$HAARU_OLDMOD") || abort "外觀檔案有衝突，舊設定保留。 / Appearance files conflict; previous settings were kept."
    HAARU_SOURCE_PREFS=$(haaru_file_hash "$HAARU_OLDMOD/$HAARU_SOURCE_PREFS_PATH") || abort "🍞 舊外觀快照無法確認，先保留原樣 / The previous appearance snapshot could not be checked; keeping it as it is"
    HAARU_TARGET_STATE=$(haaru_file_hash "$MODPATH/data/state.properties") || abort "🍞 新設定尚未確認完成 / The copied settings are not confirmed yet"
    HAARU_TARGET_PREFS=$(haaru_file_hash "$MODPATH/data/appearance.json") || abort "🍞 新外觀尚未確認完成 / The copied appearance is not confirmed yet"
    printf 'sourceId=%s\nsourceVersion=%s\nsourceVersionCode=%s\ntargetVersion=%s\ntargetVersionCode=%s\nsourcePrefsPath=%s\nsourceStateSha256=%s\nsourcePrefsSha256=%s\ntargetStateSha256=%s\ntargetPrefsSha256=%s\n' \
        "$HAARU_SOURCE_ID" "$HAARU_SOURCE_VERSION" "$HAARU_SOURCE_CODE" \
        "$(haaru_property "$MODPATH/module.prop" version)" "$(haaru_property "$MODPATH/module.prop" versionCode)" \
        "$HAARU_SOURCE_PREFS_PATH" "$HAARU_SOURCE_STATE" "$HAARU_SOURCE_PREFS" "$HAARU_TARGET_STATE" "$HAARU_TARGET_PREFS" \
        > "$HAARU_HANDOFF" || abort "🍞 搬家交接尚未保存完成 / The move handoff could not be saved"
    ui_print "☁️ 舊模組先休息，新版開機確認設定後才完成移除 / The previous module will rest; removal follows only after the new module boots with checked settings"
    if ! touch "$HAARU_OLDMOD/disable"; then
        rm -f "$HAARU_HANDOFF"
        [ "$HAARU_ORIGINAL_DISABLE" = 1 ] || rm -f "$HAARU_OLDMOD/disable"
        abort "🍞 無法讓舊模組休息，搬家先停下 / The previous module could not be paused; stopping the move"
    fi
fi

