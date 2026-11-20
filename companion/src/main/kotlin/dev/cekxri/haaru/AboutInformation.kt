/* SPDX-License-Identifier: Apache-2.0 */
package dev.cekxri.haaru

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.shapes.RoundedRectangle
import haaru.ui.HaaruTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal data class ProjectCredit(val name: String, val licence: String, val url: String)

/** Public project links verified against dependency metadata and NOTICE. */
internal val projectCredits = listOf(
    ProjectCredit("Miuix", "Apache-2.0", "https://github.com/compose-miuix-ui/miuix"),
    ProjectCredit("Backdrop · AndroidLiquidGlass", "Apache-2.0", "https://github.com/Kyant0/AndroidLiquidGlass"),
    ProjectCredit("Shapes · Kyant", "Apache-2.0", "https://github.com/Kyant0/Shapes"),
    ProjectCredit("AndroidX Compose", "Apache-2.0", "https://github.com/androidx/androidx"),
    ProjectCredit("Material Color Utilities", "Apache-2.0", "https://github.com/material-foundation/material-color-utilities"),
    ProjectCredit("MaterialKolor", "MIT", "https://github.com/jordond/materialkolor"),
    ProjectCredit("LSPlant · LSPosed", "LGPL-3.0", "https://github.com/LSPosed/LSPlant"),
    ProjectCredit("Rust", "MIT / Apache-2.0", "https://github.com/rust-lang/rust"),
    ProjectCredit("Kotlin", "Apache-2.0", "https://github.com/JetBrains/kotlin"),
    ProjectCredit("kotlinx.coroutines", "Apache-2.0", "https://github.com/Kotlin/kotlinx.coroutines"),
    ProjectCredit("kotlinx.serialization", "Apache-2.0", "https://github.com/Kotlin/kotlinx.serialization"),
    ProjectCredit("DexBuilder", "LGPL-3.0", "https://github.com/LSPosed/DexBuilder"),
    ProjectCredit("parallel-hashmap", "Apache-2.0", "https://github.com/greg7mdp/parallel-hashmap"),
    ProjectCredit("Dobby Android", "Apache-2.0", "https://github.com/vvb2060/dobby-android"),
    ProjectCredit("nlohmann/json", "MIT", "https://github.com/nlohmann/json"),
    ProjectCredit("Zygisk API · Magisk", "Permissive", "https://github.com/topjohnwu/zygisk-module-sample"),
    ProjectCredit("KernelSU · 模組平台", "GPL-3.0", "https://github.com/tiann/KernelSU")
)

private const val ProjectUrl = "https://github.com/Cekxri/Haaru.UwU"

/** A familiar settings row; the shared sculpture is owned by the page renderer. */
@Composable
internal fun AboutInformation(moduleVersion: String, onOpen: () -> Unit) {
    val colours = HaaruTheme.colours
    Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 16.dp,
        insideMargin = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        colors = CardDefaults.defaultColors(colours.surfaceContainerLow, colours.onSurface), onClick = onOpen) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("關於信息", style = MiuixTheme.textStyles.main,
                    fontWeight = FontWeight.SemiBold, color = colours.onSurface)
                Text("Haaru · ${moduleVersion.ifBlank { BuildConfig.VERSION_NAME }}",
                    style = MiuixTheme.textStyles.footnote1, color = colours.onSurfaceVariant)
            }
            Icon(HaaruIcons.Chevron, null, Modifier.size(20.dp), tint = colours.onSurfaceVariant)
        }
    }
}

/** Transparent foreground for the persistent GLES sculpture, followed by native project tasks. */
@Composable
internal fun AboutPage(moduleVersion: String, onBack: () -> Unit, onScroll: (Float) -> Unit = {}) {
    val colours = HaaruTheme.colours
    val openLink = rememberAboutLinkOpener()
    val listState = rememberLazyListState()
    val itemHeights = remember { mutableStateMapOf<Int, Int>() }
    val currentOnScroll by rememberUpdatedState(onScroll)
    val collapseDistance = with(LocalDensity.current) { 90.dp.roundToPx() }
    val compact by remember(listState, collapseDistance) {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > collapseDistance }
    }
    val reduced = LocalHaaruReducedMotion.current
    val compactProgress by animateFloatAsState(if (compact) 1f else 0f,
        if (reduced) snap() else tween(180), label = "aboutHeader")
    var creditsShown by remember { mutableStateOf(false) }
    val statusInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navigationInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    // Measured item heights include their own padding. No guessed index-to-distance multiplier.
    LaunchedEffect(listState) {
        snapshotFlow {
            val index = listState.firstVisibleItemIndex
            (0 until index).sumOf { itemHeights[it] ?: 0 }.toFloat() + listState.firstVisibleItemScrollOffset
        }.distinctUntilChanged().collect { currentOnScroll(it) }
    }
    BackHandler(onBack = onBack)
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp,
                top = statusInset + 75.dp, bottom = navigationInset + 30.dp)) {
            item("brand-stage") {
                AboutBrandStage(moduleVersion, Modifier.onSizeChanged { itemHeights[0] = it.height })
            }
            item("project") {
                AboutTaskGroup("專案", Modifier.onSizeChanged { itemHeights[1] = it.height }.padding(top = 28.dp)) {
                    AboutTaskRow("Haaru.UwU", "源碼與問題回報") { openLink(ProjectUrl) }
                    AboutRowDivider()
                    AboutTaskRow("版本更新", "GitHub Releases") { openLink("$ProjectUrl/releases") }
                    AboutRowDivider()
                    AboutTaskRow("作者", "Cekxri") { openLink("https://github.com/Cekxri") }
                }
            }
            item("open-source") {
                AboutTaskGroup("開源與資料來源", Modifier.onSizeChanged { itemHeights[2] = it.height }.padding(top = 28.dp)) {
                    AboutTaskRow("引用與鳴謝", "開源專案、預設資料與隨附授權") { creditsShown = true }
                    AboutRowDivider()
                    AboutTaskRow("完整授權與來源說明", "NOTICE") { openLink("$ProjectUrl/blob/main/NOTICE") }
                }
            }
            item("signature") {
                Column(Modifier.fillMaxWidth().onSizeChanged { itemHeights[3] = it.height }.padding(top = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("Nya^ ܸ⩌⩊⩌ ܸ^", style = MiuixTheme.textStyles.footnote1, color = colours.onSurfaceVariant)
                    Text("Made by Cekxri · Apache-2.0", style = MiuixTheme.textStyles.footnote1, color = colours.onSurfaceVariant)
                }
            }
        }
        Row(Modifier.fillMaxWidth().background(colours.background.copy(alpha = compactProgress))
            .statusBarsPadding().height(75.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(CircleShape).background(colours.surfaceContainer)
                .clickable(role = Role.Button, onClick = onBack).semantics { contentDescription = "返回設定" },
                contentAlignment = Alignment.Center) {
                Icon(HaaruIcons.Chevron, null, Modifier.size(23.dp).graphicsLayer { rotationZ = 180f }, tint = colours.onSurface)
            }
            Spacer(Modifier.width(16.dp))
            Text("關於信息", color = colours.onBackground,
                style = MiuixTheme.textStyles.title3.copy(fontSize = 22.sp, lineHeight = 30.sp),
                fontWeight = FontWeight.SemiBold)
        }
    }
    if (creditsShown) AboutCreditsSheet(openLink) { creditsShown = false }
}

/** The sculpture fills the upper stage; its text rests on the quiet lower-left plane. */
@Composable
private fun AboutBrandStage(moduleVersion: String, modifier: Modifier = Modifier) {
    val colours = HaaruTheme.colours
    Box(modifier.fillMaxWidth().heightIn(min = 280.dp), contentAlignment = Alignment.BottomStart) {
        Column(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("Haaru", color = colours.onBackground,
                style = MiuixTheme.textStyles.headline1.copy(fontSize = 44.sp, lineHeight = 51.sp, letterSpacing = (-1.1).sp),
                fontWeight = FontWeight.SemiBold)
            Text("城堡之下的SIM國度.咖啡廳☕", color = colours.onSurfaceVariant,
                style = MiuixTheme.textStyles.footnote1.copy(fontSize = 13.sp, lineHeight = 20.sp))
            Text("App ${BuildConfig.VERSION_NAME} · 模組 ${moduleVersion.ifBlank { "尚未連接" }}",
                color = colours.onSurfaceVariant, style = MiuixTheme.textStyles.footnote1.copy(fontSize = 12.sp, lineHeight = 19.sp))
        }
    }
}

@Composable
private fun AboutTaskGroup(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colours = HaaruTheme.colours
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, Modifier.padding(start = 4.dp), style = MiuixTheme.textStyles.main,
            fontWeight = FontWeight.SemiBold, color = colours.onSurface)
        Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 16.dp,
            insideMargin = PaddingValues(horizontal = 20.dp, vertical = 2.dp),
            colors = CardDefaults.defaultColors(colours.surfaceContainerLow, colours.onSurface), content = content)
    }
}

@Composable
private fun AboutTaskRow(title: String, detail: String, onClick: () -> Unit) {
    val colours = HaaruTheme.colours
    Row(Modifier.fillMaxWidth().clip(RoundedRectangle(12.dp)).clickable(role = Role.Button, onClick = onClick)
        .heightIn(min = 76.dp).padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = colours.onSurface, style = MiuixTheme.textStyles.main)
            Text(detail, color = colours.onSurfaceVariant, style = MiuixTheme.textStyles.footnote1)
        }
        Icon(HaaruIcons.Chevron, null, Modifier.size(18.dp), tint = colours.onSurfaceVariant)
    }
}

@Composable
private fun AboutRowDivider() {
    HorizontalDivider(color = HaaruTheme.colours.outlineVariant)
}

@Composable
private fun rememberAboutLinkOpener(): (String) -> Unit {
    val context = LocalContext.current
    return remember(context) {
        { url: String ->
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.onFailure {
                Toast.makeText(context, "尚未找到可開啟連結的應用程式", Toast.LENGTH_SHORT).show()
            }
            Unit
        }
    }
}

private data class LicenceDocument(val title: String, val text: String)

@Composable
private fun AboutCreditsSheet(openLink: (String) -> Unit, dismissed: () -> Unit) {
    val context = LocalContext.current
    var selectedLicence by remember { mutableStateOf<String?>(null) }
    val assetNames by produceState<List<String>>(emptyList(), context) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.assets.list("licences").orEmpty().filter {
                it.endsWith(".txt") && '/' !in it && '\\' !in it
            }.sorted() }.getOrDefault(emptyList())
        }
    }
    val document by produceState<LicenceDocument?>(null, selectedLicence) {
        value = null
        val filename = selectedLicence ?: return@produceState
        value = withContext(Dispatchers.IO) {
            LicenceDocument(filename.removeSuffix(".txt"), runCatching {
                context.assets.open("licences/$filename").bufferedReader(Charsets.UTF_8).use { it.readText() }
            }.getOrElse { "這份授權文字暫時無法讀取，請稍後再試。" })
        }
    }
    HaaruSheet(LocalHaaruReducedMotion.current, dismissed) { close ->
        Column(Modifier.fillMaxWidth().heightIn(max = 680.dp).padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (selectedLicence != null) HaaruTextButton({ selectedLicence = null }) { Text("返回") }
                Text(if (selectedLicence == null) "引用與鳴謝" else "授權全文",
                    Modifier.weight(1f), style = MiuixTheme.textStyles.title2, color = HaaruTheme.colours.onSurface)
                HaaruTextButton(close) { Text("關閉") }
            }
            if (selectedLicence == null) {
                LazyColumn(Modifier.weight(1f, fill = false), contentPadding = PaddingValues(bottom = 24.dp)) {
                    item("projects-title") { SectionTitle("開源專案") }
                    items(projectCredits, key = { it.url }) { credit -> CreditLink(credit, openLink) }
                    item("presets-title") { SectionTitle("預設資料來源") }
                    item("presets") {
                        CreditLink(ProjectCredit("Nrfr · Ackites", "Apache-2.0", "https://github.com/Ackites/Nrfr"), openLink)
                        CreditLink(ProjectCredit("預設資料參考版本", "Apache-2.0",
                            "https://github.com/a5533348/Nrfr/tree/6c05f224dc802d0df952db93a6d2852a91c9edb0"), openLink)
                    }
                    item("notice") {
                        CreditLink(ProjectCredit("完整授權與來源說明", "NOTICE", "$ProjectUrl/blob/main/NOTICE"), openLink)
                    }
                    item("licences-title") { SectionTitle("App 隨附授權 · 離線閱讀") }
                    items(assetNames, key = { "asset-$it" }) { filename ->
                        LicenceAssetRow(filename) { selectedLicence = filename }
                    }
                    if (assetNames.isEmpty()) item("licences-empty") {
                        Text("正在讀取隨附授權…", Modifier.padding(vertical = 16.dp),
                            color = HaaruTheme.colours.onSurfaceVariant)
                    }
                }
            } else {
                key(selectedLicence) {
                    LazyColumn(Modifier.weight(1f, fill = false), contentPadding = PaddingValues(bottom = 24.dp)) {
                        item("document-title") {
                            Text(document?.title ?: selectedLicence.orEmpty().removeSuffix(".txt"),
                                Modifier.padding(vertical = 12.dp), fontWeight = FontWeight.SemiBold,
                                color = HaaruTheme.colours.onSurface)
                        }
                        item("document-text") {
                            Text(document?.text ?: "正在讀取授權…", fontSize = 12.sp, lineHeight = 19.sp,
                                color = HaaruTheme.colours.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LicenceAssetRow(filename: String, onClick: () -> Unit) {
    val colours = HaaruTheme.colours
    Row(Modifier.fillMaxWidth().clip(RoundedRectangle(14.dp)).clickable(onClick = onClick)
        .heightIn(min = 64.dp).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(filename.removeSuffix(".txt").replace('-', ' '), color = colours.onSurface)
            Text("完整隨附授權文本", style = MiuixTheme.textStyles.footnote1, color = colours.onSurfaceVariant)
        }
        Icon(HaaruIcons.Chevron, null, Modifier.size(18.dp), tint = colours.onSurfaceVariant)
    }
}

@Composable
private fun CreditLink(credit: ProjectCredit, open: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedRectangle(14.dp)).clickable { open(credit.url) }
        .heightIn(min = 64.dp).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            Text(credit.name, color = HaaruTheme.colours.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(credit.licence, style = MiuixTheme.textStyles.footnote1, color = HaaruTheme.colours.onSurfaceVariant)
        }
        Icon(HaaruIcons.Chevron, null, Modifier.size(18.dp), tint = HaaruTheme.colours.onSurfaceVariant)
    }
}
