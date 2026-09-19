package stream.kleeamp.mobile.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

enum class OutputKind { Speaker, Headphones, Bluetooth, Usb, Other }

data class AudioOutput(
    val id: Int,
    val name: String,
    val kind: OutputKind,
)

/**
 * The audio sinks Android offers for media playback, and the one that is
 * actually in use. The system owns routing; the app only expresses a
 * preference, which the service hands to ExoPlayer.
 */
object AudioOutputs {

    fun list(context: Context): List<AudioOutput> {
        val manager = context.getSystemService(AudioManager::class.java) ?: return emptyList()
        return sinkDevices(manager)
            .map(::outputOf)
            .distinctBy { it.kind to it.name.lowercase() }
            .sortedWith(compareBy({ kindRank(it.kind) }, { it.name.lowercase() }))
    }

    fun current(context: Context, preferredId: Int): AudioOutput? {
        val manager = context.getSystemService(AudioManager::class.java) ?: return null
        if (preferredId >= 0) {
            sinkDevices(manager).firstOrNull { it.id == preferredId }?.let { return outputOf(it) }
        }
        val system = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            manager.getAudioDevicesForAttributes(mediaAttributes())
                .firstOrNull { it.isSink && it.type != AudioDeviceInfo.TYPE_TELEPHONY }
        } else {
            sinkDevices(manager).minByOrNull { kindRank(kindFor(it.type)) }
        }
        return system?.let(::outputOf)
    }

    fun find(context: Context, id: Int): AudioDeviceInfo? {
        if (id < 0) return null
        val manager = context.getSystemService(AudioManager::class.java) ?: return null
        return sinkDevices(manager).firstOrNull { it.id == id }
    }

    fun kindFor(type: Int): OutputKind = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE,
        -> OutputKind.Speaker

        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST,
        -> OutputKind.Bluetooth

        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_HEARING_AID,
        -> OutputKind.Headphones

        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        -> OutputKind.Usb

        else -> OutputKind.Other
    }

    fun kindLabel(kind: OutputKind): String = when (kind) {
        OutputKind.Speaker -> "speaker"
        OutputKind.Headphones -> "headphones"
        OutputKind.Bluetooth -> "bluetooth"
        OutputKind.Usb -> "usb"
        OutputKind.Other -> "output"
    }

    fun typeLabel(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE,
        -> "speaker"

        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST,
        -> "bluetooth"

        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        -> "wired"

        AudioDeviceInfo.TYPE_HEARING_AID -> "hearing aid"

        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        -> "usb"

        AudioDeviceInfo.TYPE_HDMI,
        AudioDeviceInfo.TYPE_HDMI_ARC,
        AudioDeviceInfo.TYPE_HDMI_EARC,
        -> "hdmi"

        AudioDeviceInfo.TYPE_DOCK,
        AudioDeviceInfo.TYPE_DOCK_ANALOG,
        -> "dock"

        AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_LINE_DIGITAL,
        AudioDeviceInfo.TYPE_AUX_LINE,
        -> "line"

        else -> "output"
    }

    private fun sinkDevices(manager: AudioManager): List<AudioDeviceInfo> =
        manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter {
            it.isSink &&
                it.type != AudioDeviceInfo.TYPE_TELEPHONY &&
                it.type != AudioDeviceInfo.TYPE_REMOTE_SUBMIX &&
                it.type != AudioDeviceInfo.TYPE_FM &&
                it.type != AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
        }

    private fun outputOf(info: AudioDeviceInfo): AudioOutput {
        val product = info.productName?.toString()?.trim().orEmpty()
        return AudioOutput(info.id, product.ifBlank { typeLabel(info.type) }, kindFor(info.type))
    }

    private fun kindRank(kind: OutputKind): Int = when (kind) {
        OutputKind.Bluetooth -> 0
        OutputKind.Usb -> 1
        OutputKind.Headphones -> 2
        OutputKind.Speaker -> 3
        OutputKind.Other -> 4
    }

    private fun mediaAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
}
