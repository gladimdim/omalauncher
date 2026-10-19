package dev.gladimdim.superdesktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import dev.gladimdim.superdesktop.bridge.Bridge
import dev.gladimdim.superdesktop.live.Feed
import dev.gladimdim.superdesktop.live.SdLive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** A live feed per computer in [bridges] while this composes; SdLive shares the connections between tiles. */
@Composable
internal fun rememberFeeds(bridges: List<Bridge>): Map<String, Feed> {
    val context = LocalContext.current.applicationContext
    val feeds = remember { mutableStateMapOf<String, Feed>() }
    val scope = rememberCoroutineScope()
    for (b in bridges) {
        key(b.key) {
            DisposableEffect(b) {
                val flow = SdLive.acquire(b)
                val job =
                    scope.launch {
                        flow.collect {
                            feeds[b.key] = it
                            it.doc?.wake?.let { wake -> launch(Dispatchers.IO) { SdWake.rememberIfChanged(context, b, wake) } }
                        }
                    }
                onDispose {
                    job.cancel()
                    SdLive.release(b)
                }
            }
        }
    }
    return feeds
}
