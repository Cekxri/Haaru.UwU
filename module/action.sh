#!/system/bin/sh
# KernelSU's play button opens the installed native companion.
if pm path dev.cekxri.haaru >/dev/null 2>&1; then
    am start --user 0 -n dev.cekxri.haaru/.MainActivity >/dev/null 2>&1 || {
        printf '%s\n' 'Haaru 暫時未能開啟，請從桌面開啟 App。'
        exit 1
    }
else
    printf '%s\n' '請先安裝與模組同版本的 Haaru APK，再按此按鈕。'
fi
