package stream.kleeamp.mobile.ui.components

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import stream.kleeamp.mobile.playback.AudioOutput
import stream.kleeamp.mobile.playback.AudioOutputs
import stream.kleeamp.mobile.ui.theme.KleeampShape
import stream.kleeamp.mobile.ui.theme.KleeampType
import stream.kleeamp.mobile.ui.theme.LocalPalette
import stream.kleeamp.mobile.ui.theme.Mono

const val SYSTEM_OUTPUT_ID = -1

/** The connected media sinks, kept current while devices come and go. */
@Composable
fun rememberAudioOutputs(): List<AudioOutput> {
    val context = LocalContext.current
    var outputs by remember { mutableStateOf(AudioOutputs.list(context)) }
    DisposableEffect(context) {
        val manager = context.getSystemService(AudioManager::class.java)
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                outputs = AudioOutputs.list(context)
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                outputs = AudioOutputs.list(context)
            }
        }
        manager?.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        onDispose { manager?.unregisterAudioDeviceCallback(callback) }
    }
    return outputs
}

/**
 * The compact output picker. "system default" follows Android routing; any
 * other row pins playback to that sink. The header names the device the
 * stream is actually on, so the answer is one tap away.
 */
@Composable
fun OutputMenu(
    trigger: @Composable (() -> Unit) -> Unit,
    currentName: String?,
    outputs: List<AudioOutput>,
    selectedId: Int,
    onSelect: (Int) -> Unit,
    menuWidth: Int = 236,
) {
    val p = LocalPalette.current
    var open by remember { mutableStateOf(false) }
    Box {
        trigger { open = true }
        if (open) {
            Popup(
                onDismissRequest = { open = false },
                alignment = Alignment.TopEnd,
                offset = IntOffset(0, 8),
            ) {
                Column(
                    Modifier.width(menuWidth.dp).clip(RoundedCornerShape(KleeampShape.small))
                        .background(p.ground)
                        .border(1.dp, p.hairlineRegion, RoundedCornerShape(KleeampShape.small)),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Mono("OUTPUT", KleeampType.sectionLabel, p.inkFaint, maxLines = 1)
                        Spacer(Modifier.weight(1f))
                        Mono(currentName ?: "system", KleeampType.meta, p.inkTertiary, maxLines = 1)
                    }
                    OutputRow(
                        name = "system default",
                        id = SYSTEM_OUTPUT_ID,
                        selectedId = selectedId,
                        detail = null,
                        onSelect = onSelect,
                    ) { open = false }
                    outputs.forEach { output ->
                        OutputRow(
                            name = output.name,
                            id = output.id,
                            selectedId = selectedId,
                            detail = AudioOutputs.kindLabel(output.kind),
                            onSelect = onSelect,
                        ) { open = false }
                    }
                }
            }
        }
    }
}

@Composable
private fun OutputRow(
    name: String,
    id: Int,
    selectedId: Int,
    detail: String?,
    onSelect: (Int) -> Unit,
    close: () -> Unit,
) {
    val p = LocalPalette.current
    HairlineDivider(region = true)
    Row(
        Modifier.fillMaxWidth().microPress {
            close()
            onSelect(id)
        }.padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Mono(
            name,
            KleeampType.chip,
            if (id == selectedId) p.accent else p.ink,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        if (id == selectedId) {
            Mono("ACTIVE", KleeampType.tabLabel, p.accent, maxLines = 1)
        } else if (detail != null) {
            Mono(detail, KleeampType.meta, p.inkFaint, maxLines = 1)
        }
    }
}
