package dev.omalauncher.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.gladimdim.superdesktop.SdWidgets
import dev.omalauncher.LauncherData
import dev.omalauncher.R

// Fork: SUPER DESKTOP's row in the home long-press menu, drawn like "create with …" above it.

/** Adds SUPER DESKTOP widgets/pages here, or a face left of page 1. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SdMenuRow(state: LauncherState) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 10.dp)) {
        BasicText(stringResource(R.string.sd_menu_title), style = Mono.copy(fontSize = TextSize.Item))
        // Pulled left by the buttons' own padding, so the first bracket lines up with the label.
        // Flows, as home's own row above does, for large font sizes.
        FlowRow(Modifier.offset(x = (-8).dp)) {
            BracketAction(stringResource(R.string.sd_menu_terminal), Palette.accent) {
                state.homeMenuOpen = false
                SdWidgets.addTerminal(context, LauncherData.widgetsDir(context), state.currentPage)
            }
            BracketAction(stringResource(R.string.sd_menu_usage), Palette.accent) {
                state.homeMenuOpen = false
                SdWidgets.addUsage(LauncherData.widgetsDir(context), state.currentPage)
            }
            BracketAction(stringResource(R.string.sd_menu_page), Palette.accent) {
                state.homeMenuOpen = false
                SdWidgets.addPage(LauncherData.widgetsDir(context), state.currentPage)
            }
            BracketAction(stringResource(R.string.sd_menu_face), Palette.accent) {
                state.homeMenuOpen = false
                SdWidgets.addFace(LauncherData.widgetsDir(context))
            }
        }
    }
}
