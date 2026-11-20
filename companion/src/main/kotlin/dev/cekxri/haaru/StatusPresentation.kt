/* SPDX-License-Identifier: Apache-2.0 */
package dev.cekxri.haaru

internal data class StatusPresentation(val title: String, val description: String, val error: Boolean, val confirmed: Boolean)

/** Stored plans and a live phone-engine acknowledgement stay separate in the UI. */
internal fun presentStatus(state: HaaruState): StatusPresentation {
    val snapshot = state.snapshot
    val engineError = snapshot.globalEngine?.error?.isNotBlank() == true
    val title = when {
        state.busy && !state.fresh -> "正在連接模組"
        snapshot.upgradePending -> "更新完成，等待重啟"
        snapshot.readOnly -> "模組目前已停用"
        !state.fresh && state.diagnostics.contains("安裝") -> "尚未安裝模組"
        !state.fresh -> "等待模組連接"
        snapshot.globalConfirmed && snapshot.enabled -> "方案正在運行"
        snapshot.globalConfirmed -> "已還原原始 SIM"
        engineError -> "引擎尚未就緒"
        !snapshot.enabled -> "從一張 SIM 開始"
        else -> "方案已存，等待確認"
    }
    val description = when {
        snapshot.upgradePending -> "重新啟動手機後，即可使用新版。"
        snapshot.readOnly -> "請在模組管理器確認 Haaru 已啟用。"
        !state.fresh && state.busy -> "正在讀取模組與 SIM 狀態。"
        !state.fresh -> friendlyError(state.diagnostics)
        snapshot.globalConfirmed -> if (snapshot.enabled) "已由電話引擎確認；你仍可切換或還原方案。" else "已停止套用，已存方案仍保留。"
        engineError -> friendlyEngineError(snapshot.globalEngine?.error.orEmpty())
        !snapshot.enabled -> "選擇地區與電信商，再儲存這張 SIM 的方案。"
        else -> "保存成功與確認生效不同；正在等待引擎回報。"
    }
    return StatusPresentation(title, description, state.fresh && engineError, snapshot.globalConfirmed)
}
