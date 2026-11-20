/* SPDX-License-Identifier: Apache-2.0 */
package dev.cekxri.haaru

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/** Manrope's variable outlines are subset-free; Android supplies the CJK fallback. */
internal val HaaruDisplayFont = FontFamily(
    Font(R.font.manrope, FontWeight.Normal),
    Font(R.font.manrope, FontWeight.Medium),
    Font(R.font.manrope, FontWeight.SemiBold),
    Font(R.font.manrope, FontWeight.Bold)
)
