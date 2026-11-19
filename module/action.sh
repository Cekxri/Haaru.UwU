#!/system/bin/sh
# The module action opens only the native App, with a bounded installation retry.
MODDIR=${0%/*}
installed() {
    pm path --user 0 dev.cekxri.haaru 2>/dev/null | grep '^package:' >/dev/null
}
if ! installed; then
    if [ -f "$MODDIR/disable" ] || [ -f "$MODDIR/remove" ] || [ -f "$MODDIR/update" ]; then
        printf '%s\n' '🍞 Haaru 休息中，請先啟用模組並完成重啟。' \
            'Haaru is resting. Enable the module and finish rebooting first.'
        exit 2
    fi
    printf '%s\n' '☕ 正在為你準備同版本 App，Nya～' \
        'Preparing the matching App for you, Nya~'
    /system/bin/sh "$MODDIR/bin/install-companion.sh" "$MODDIR" retry >/dev/null 2>&1
    INSTALL_CODE=$?
    if ! installed; then
        if [ "$INSTALL_CODE" = 3 ]; then
            printf '%s\n' '☕ Android 還在暖機，開機完成後會繼續安裝。' \
                'Android is still warming up. Installation will continue after boot.'
        else
            printf '%s\n' '🍛 App 尚未裝好；安裝原因已留在模組的診斷檔。' \
                'The App is not installed yet; its installation details are in the module diagnostics.'
        fi
        exit 2
    fi
fi
am start --user 0 -n dev.cekxri.haaru/.MainActivity >/dev/null 2>&1 || {
    printf '%s\n' '🍞 App 已在，請從桌面開啟 Haaru。' \
        'The App is installed. Please open Haaru from your launcher.'
    exit 1
}
