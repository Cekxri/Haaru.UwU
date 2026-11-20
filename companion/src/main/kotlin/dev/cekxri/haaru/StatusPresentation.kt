/* SPDX-License-Identifier: Apache-2.0 */
package dev.cekxri.haaru

internal data class StatusPresentation(val title: String, val description: String, val error: Boolean, val confirmed: Boolean)

/** Stored plans and a live phone-engine acknowledgement stay separate in the UI. */
internal fun presentStatus(state: HaaruState): StatusPresentation {
    val snapshot = state.snapshot
    val engineDetail = snapshot.globalEngine?.error.orEmpty()
    val engineError = engineDetail.isNotBlank()
    val partial = snapshot.hasBlockedPlans
    val confirmed = state.globalConfirmed
    val statusError = state.statusReadError
    val title = when {
        state.busy && !state.fresh -> "正在連接模組"
        state.fresh && snapshot.upgradePending -> "更新完成，等待重啟"
        state.fresh && snapshot.readOnly -> "模組目前已停用"
        !state.fresh && moduleIsAbsent(statusError) -> "尚未安裝模組"
        !state.fresh && statusError.isBlank() && snapshot.moduleVersion.isNotBlank() -> "模組狀態待更新"
        !state.fresh -> "等待模組連接"
        confirmed && snapshot.enabled -> "方案正在運行"
        confirmed -> "已還原原始 SIM"
        partial -> "有已存方案未能套用"
        snapshot.globalConfirmed -> "系統回報待更新"
        engineError -> "引擎尚未就緒"
        !snapshot.enabled -> "從一張 SIM 開始"
        else -> "方案已存，等待確認"
    }
    val description = when {
        state.fresh && snapshot.upgradePending -> "重新啟動手機後，即可使用新版。"
        state.fresh && snapshot.readOnly -> "請在模組管理器確認 Haaru 已啟用。"
        !state.fresh && state.busy -> "正在讀取模組與 SIM 狀態。"
        !state.fresh && statusError.isNotBlank() -> friendlyError(statusError)
        !state.fresh -> "正在等待最新的模組與 SIM 狀態。"
        confirmed -> if (snapshot.enabled) "已由電話引擎確認；你仍可切換或還原方案。" else "已停止套用，已存方案仍保留。"
        partial -> "請確認原本的 SIM 是否就緒，或到工具頁查看未套用的方案。"
        snapshot.globalConfirmed -> "正在等待電話引擎重新確認目前狀態。"
        engineError -> friendlyEngineError(engineDetail)
        !snapshot.enabled -> "選擇地區與電信商，再儲存這張 SIM 的方案。"
        else -> "保存成功與確認生效不同；正在等待引擎回報。"
    }
    return StatusPresentation(title, description, state.fresh && (partial || engineError), confirmed)
}
