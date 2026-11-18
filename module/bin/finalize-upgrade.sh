#!/system/bin/sh
umask 077
MODDIR=$1
JOURNAL="$MODDIR/data/upgrade-handoff.properties"
[ -f "$JOURNAL" ] && [ ! -L "$JOURNAL" ] || exit 0
[ "$MODDIR" = /data/adb/modules/haaru ] && [ ! -L "$MODDIR" ] &&
[ ! -f "$MODDIR/disable" ] && [ ! -f "$MODDIR/remove" ] && [ ! -f "$MODDIR/update" ] || exit 0
. "$MODDIR/bin/discover-upgrade.sh"
[ "$(haaru_property "$JOURNAL" targetVersion)" = "$(haaru_property "$MODDIR/module.prop" version)" ] &&
[ "$(haaru_property "$JOURNAL" targetVersionCode)" = "$(haaru_property "$MODDIR/module.prop" versionCode)" ] || exit 2
[ "$(haaru_file_hash "$MODDIR/data/state.properties")" = "$(haaru_property "$JOURNAL" targetStateSha256)" ] &&
[ "$(haaru_file_hash "$MODDIR/webroot/appearance.json")" = "$(haaru_property "$JOURNAL" targetPrefsSha256)" ] || exit 2
HAARU_SOURCE_ID=$(haaru_property "$JOURNAL" sourceId)
case "$HAARU_SOURCE_ID" in *[!A-Za-z0-9_.-]*|''|haaru) exit 2 ;; esac
HAARU_SOURCE="/data/adb/modules/$HAARU_SOURCE_ID"
if [ ! -e "$HAARU_SOURCE" ]; then rm -f "$JOURNAL"; exit 0; fi
[ -d "$HAARU_SOURCE" ] && [ ! -L "$HAARU_SOURCE" ] && [ ! -L "$HAARU_SOURCE/module.prop" ] &&
[ "$(haaru_property "$HAARU_SOURCE/module.prop" id)" = "$HAARU_SOURCE_ID" ] &&
[ "$(haaru_property "$HAARU_SOURCE/module.prop" name)" = Haaru ] &&
[ "$(haaru_property "$HAARU_SOURCE/module.prop" author)" = Cekxri ] &&
[ "$(haaru_property "$HAARU_SOURCE/module.prop" version)" = "$(haaru_property "$JOURNAL" sourceVersion)" ] &&
[ "$(haaru_property "$HAARU_SOURCE/module.prop" versionCode)" = "$(haaru_property "$JOURNAL" sourceVersionCode)" ] &&
[ -f "$HAARU_SOURCE/disable" ] && [ ! -L "$HAARU_SOURCE/remove" ] || exit 2
[ "$(haaru_file_hash "$HAARU_SOURCE/data/state.properties")" = "$(haaru_property "$JOURNAL" sourceStateSha256)" ] &&
[ "$(haaru_file_hash "$HAARU_SOURCE/webroot/appearance.json")" = "$(haaru_property "$JOURNAL" sourcePrefsSha256)" ] || exit 2
touch "$HAARU_SOURCE/remove" || exit 2
rm -f "$JOURNAL"
