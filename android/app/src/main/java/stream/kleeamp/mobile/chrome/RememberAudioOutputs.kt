package stream.kleeamp.mobile.chrome

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import stream.kleeamp.mobile.playback.AudioOutput
import stream.kleeamp.mobile.playback.AudioOutputs

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
