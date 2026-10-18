package dev.gladimdim.superdesktop.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gladimdim.superdesktop.Label
import dev.gladimdim.superdesktop.SdHost
import dev.gladimdim.superdesktop.bridge.Bridge
import dev.gladimdim.superdesktop.live.SdTerminals
import dev.gladimdim.superdesktop.live.Terminal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** The tile's text size: as large as fits the widest line, between these. */
private val MAX_SP = 11f
private val MIN_SP = 7f

/** A monospace cell is about this much of the font size wide; measured from the real font when possible. */
private const val CELL_EM = 0.6f

/** A frame ready to draw: its styled lines (unchanged ones keep their instance) and the width of its widest, in cells. */
private class Rendered(
    val lines: List<AnnotatedString>,
    val columns: Int,
)

/** At most this often a pane lays out a new frame; frames in between are skipped, never the last one. */
private const val FRAME_MS = 80L

/**
 * Harness [id]'s live terminal on [b], fitted to the tile. Until [active] (the ⌨ button or a tap makes it so)
 * it only scrolls up and down, so swipes still turn home pages; once active it also pans sideways.
 */
@Composable
internal fun TerminalPane(
    b: Bridge,
    id: String,
    host: SdHost,
    active: Boolean,
    onActivate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = host.colors
    // One stream per harness, shared with every other tile showing it.
    var source by remember { mutableStateOf<StateFlow<Terminal>?>(null) }
    DisposableEffect(b.key, id) {
        source = SdTerminals.acquire(b, id)
        onDispose { SdTerminals.release(b, id) }
    }
    val none = remember { MutableStateFlow(Terminal()) }
    val terminal by (source ?: none).collectAsState()

    val palette = c.ansi.takeIf { it.size >= 16 } ?: DEFAULT_PALETTE
    val renderer = remember(palette, c.foreground, c.background) { AnsiRenderer(palette, c.foreground, c.background) }
    // Parsing and styling 300 lines happen off the main thread; a busy agent's frames are thinned to one per FRAME_MS.
    val rendered by produceState<Rendered?>(null, source, renderer) {
        val flow = source ?: return@produceState
        flow.map { it.tail }.filterNotNull().distinctUntilChanged().conflate().collect { tail ->
            value = withContext(Dispatchers.Default) { render(tail, renderer) }
            delay(FRAME_MS)
        }
    }
    val lines = rendered?.lines.orEmpty()
    val columns = rendered?.columns ?: 80

    val shape = host.shape(8.dp)
    BoxWithConstraints(
        modifier
            .background(c.background.copy(alpha = 0.7f), shape)
            .border(if (active) 2.dp else 1.dp, if (active) c.focusBorder else c.border, shape),
    ) {
        val density = LocalDensity.current
        // The real advance of a monospace cell at the readable size, measured once per font.
        val measurer = rememberTextMeasurer()
        val readable = remember(host.mono) { TextStyle(fontFamily = host.mono, fontSize = MAX_SP.sp, lineHeight = (MAX_SP * 1.25f).sp) }
        val cellEm =
            remember(host.mono, density) {
                val px = measurer.measure("M".repeat(40), readable).size.width / 40f
                (px / with(density) { MAX_SP.sp.toPx() }).takeIf { it.isFinite() && it > 0 } ?: CELL_EM
            }
        val innerWidth = maxWidth - 16.dp
        val innerHeight = maxHeight - 16.dp

        // Ask the computer to size the pane to this tile at the readable font, so lines fit without panning.
        // The size waits for 150 ms of calm: the keyboard and resize animations change it many times.
        val grid =
            with(density) {
                TerminalViewportGrid.fit(innerWidth.toPx(), innerHeight.toPx(), cellEm * MAX_SP.sp.toPx(), (MAX_SP * 1.25f).sp.toPx())
            }
        val owner = remember { Any() }
        LaunchedEffect(b.key, id, grid) {
            delay(150)
            SdTerminals.size(b, id, owner, grid)
        }
        DisposableEffect(b.key, id) { onDispose { SdTerminals.size(b, id, owner, null) } }

        val fit = with(density) { (innerWidth.toPx() / (columns * cellEm)).toSp().value }
        val size = fit.coerceIn(MIN_SP, MAX_SP).sp
        val style = remember(c.foreground, host.mono, size) { TextStyle(color = c.foreground, fontFamily = host.mono, fontSize = size, lineHeight = size * 1.25f) }
        // Wide enough for the widest line, so panning reaches its end when a line is still wider than the tile.
        val contentWidth = maxOf(maxWidth, with(density) { (columns * cellEm * size.toPx() * 1.02f).toDp() } + 16.dp)
        val list = rememberLazyListState()
        val horizontal = rememberScrollState()
        // Follow new output while at the bottom; stay put once scrolled up to read.
        var follow by remember { mutableStateOf(true) }
        LaunchedEffect(list.isScrollInProgress) {
            if (!list.isScrollInProgress) follow = !list.canScrollForward
        }
        LaunchedEffect(lines, follow) { if (follow && lines.isNotEmpty()) list.scrollToItem(lines.lastIndex) }

        Box(
            Modifier
                .fillMaxSize()
                .clickable(enabled = !active, indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = onActivate)
                .horizontalScroll(horizontal, enabled = active),
        ) {
            // Only the lines on screen are composed and laid out; an unchanged line is skipped.
            LazyColumn(Modifier.width(contentWidth).fillMaxHeight(), state = list, contentPadding = PaddingValues(8.dp)) {
                // Keyed by position: content keys measured slower on a phone (more list bookkeeping per frame).
                items(lines.size) { i -> BasicText(lines[i], style = style, softWrap = false, maxLines = 1) }
            }
        }
        when {
            terminal.ended -> Badge("session ended", host, c.muted, Modifier.align(Alignment.Center))
            rendered == null -> Badge("connecting…", host, c.muted, Modifier.align(Alignment.Center))
            terminal.reconnecting -> Badge("reconnecting…", host, c.yellow, Modifier.align(Alignment.TopEnd))
        }
    }
}

@Composable
private fun Badge(
    text: String,
    host: SdHost,
    color: Color,
    modifier: Modifier,
) = Box(modifier.padding(6.dp).background(host.colors.background.copy(alpha = 0.85f)).padding(horizontal = 6.dp, vertical = 2.dp)) {
    Label(text, host, color, 10)
}

/** [tail] as it's drawn: without tmux's empty lines below the cursor, styled, with its widest line measured. Off the main thread. */
private fun render(
    tail: String,
    renderer: AnsiRenderer,
): Rendered {
    val lines = tail.split('\n').dropLastWhile { TerminalAnsi.plain(it).isBlank() }
    val columns = lines.maxOfOrNull { TerminalAnsi.plain(it).length }?.coerceIn(20, 300) ?: 80
    return Rendered(renderer.renderLines(lines), columns)
}

/** Tokyo Night's terminal colors, for a launcher palette without all sixteen. */
private val DEFAULT_PALETTE =
    listOf(
        0xFF15161E, 0xFFF7768E, 0xFF9ECE6A, 0xFFE0AF68, 0xFF7AA2F7, 0xFFBB9AF7, 0xFF7DCFFF, 0xFFA9B1D6,
        0xFF414868, 0xFFF7768E, 0xFF9ECE6A, 0xFFE0AF68, 0xFF7AA2F7, 0xFFBB9AF7, 0xFF7DCFFF, 0xFFC0CAF5,
    ).map { Color(it) }
