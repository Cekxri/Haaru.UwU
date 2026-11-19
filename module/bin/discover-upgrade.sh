#!/system/bin/sh
# Generic brand discovery; no historical module ID is embedded here.
haaru_property() {
    sed -n "s/^$2=//p" "$1" | tr -d '\r'
}
haaru_file_hash() {
    [ ! -L "$1" ] && [ ! -L "${1%/*}" ] || return 2
    if [ ! -e "$1" ]; then printf absent; return 0; fi
    [ -f "$1" ] || return 2
    HAARU_READ_HASH=$(sha256sum "$1" 2>/dev/null) || return 2
    HAARU_READ_HASH=${HAARU_READ_HASH%% *}
    case "$HAARU_READ_HASH" in *[!0-9a-f]*|'') return 2 ;; esac
    [ "${#HAARU_READ_HASH}" -eq 64 ] || return 2
    printf '%s' "$HAARU_READ_HASH"
}
haaru_source_prefs_path() {
    HAARU_NEW_PREFS=$(haaru_file_hash "$1/data/appearance.json") || return 2
    HAARU_OLD_PREFS=$(haaru_file_hash "$1/webroot/appearance.json") || return 2
    if [ "$HAARU_NEW_PREFS" != absent ] && [ "$HAARU_OLD_PREFS" != absent ] &&
       [ "$HAARU_NEW_PREFS" != "$HAARU_OLD_PREFS" ]; then return 2; fi
    if [ "$HAARU_NEW_PREFS" = absent ] && [ "$HAARU_OLD_PREFS" != absent ]; then
        printf 'webroot/appearance.json'
    else
        printf 'data/appearance.json'
    fi
}
haaru_find_upgrade() {
    HAARU_FOUND=
    HAARU_COMMITTED_SOURCE=
    HAARU_CANONICAL="$1/$2"
    HAARU_EXISTING_HANDOFF="$HAARU_CANONICAL/data/upgrade-handoff.properties"
    if [ -d "$HAARU_CANONICAL" ] && [ ! -L "$HAARU_CANONICAL" ] &&
       [ ! -L "$HAARU_CANONICAL/module.prop" ] && [ ! -L "$HAARU_CANONICAL/data" ] &&
       [ "$(haaru_property "$HAARU_CANONICAL/module.prop" id)" = "$2" ] &&
       [ "$(haaru_property "$HAARU_CANONICAL/module.prop" name)" = Haaru ] &&
       [ "$(haaru_property "$HAARU_CANONICAL/module.prop" author)" = Cekxri ] &&
       [ -f "$HAARU_EXISTING_HANDOFF" ] && [ ! -L "$HAARU_EXISTING_HANDOFF" ] &&
       [ "$(haaru_property "$HAARU_EXISTING_HANDOFF" targetVersion)" = "$(haaru_property "$HAARU_CANONICAL/module.prop" version)" ] &&
       [ "$(haaru_property "$HAARU_EXISTING_HANDOFF" targetVersionCode)" = "$(haaru_property "$HAARU_CANONICAL/module.prop" versionCode)" ]; then
        HAARU_COMMITTED_SOURCE=$(haaru_property "$HAARU_EXISTING_HANDOFF" sourceId)
    fi
    for HAARU_CANDIDATE in "$1"/*; do
        [ -d "$HAARU_CANDIDATE" ] && [ ! -L "$HAARU_CANDIDATE" ] &&
        [ -f "$HAARU_CANDIDATE/module.prop" ] && [ ! -L "$HAARU_CANDIDATE/module.prop" ] || continue
        HAARU_SOURCE_ID=${HAARU_CANDIDATE##*/}
        case "$HAARU_SOURCE_ID" in *[!A-Za-z0-9_.-]*|'') continue ;; esac
        [ "$(haaru_property "$HAARU_CANDIDATE/module.prop" id)" = "$HAARU_SOURCE_ID" ] &&
        [ "$(haaru_property "$HAARU_CANDIDATE/module.prop" name)" = Haaru ] &&
        [ "$(haaru_property "$HAARU_CANDIDATE/module.prop" author)" = Cekxri ] || continue
        [ ! -f "$HAARU_CANDIDATE/remove" ] || continue
        if [ "$HAARU_SOURCE_ID" = "$HAARU_COMMITTED_SOURCE" ] && [ -f "$HAARU_CANDIDATE/disable" ] &&
           [ "$(haaru_property "$HAARU_CANDIDATE/module.prop" version)" = "$(haaru_property "$HAARU_EXISTING_HANDOFF" sourceVersion)" ] &&
           [ "$(haaru_property "$HAARU_CANDIDATE/module.prop" versionCode)" = "$(haaru_property "$HAARU_EXISTING_HANDOFF" sourceVersionCode)" ]; then
            continue
        fi
        if [ -n "$HAARU_FOUND" ]; then
            echo "🍞 找到不止一個 Haaru，沒有猜選。 / More than one Haaru module was found; none was guessed." >&2
            return 2
        fi
        HAARU_FOUND=$HAARU_CANDIDATE
    done
    printf '%s' "$HAARU_FOUND"
}
