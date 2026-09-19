package stream.kleeamp.mobile.playback

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioOutputsTest {

    @Test
    fun kindsMapFromDeviceTypes() {
        assertEquals(OutputKind.Speaker, AudioOutputs.kindFor(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        assertEquals(OutputKind.Bluetooth, AudioOutputs.kindFor(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        assertEquals(OutputKind.Bluetooth, AudioOutputs.kindFor(AudioDeviceInfo.TYPE_BLE_HEADSET))
        assertEquals(OutputKind.Headphones, AudioOutputs.kindFor(AudioDeviceInfo.TYPE_WIRED_HEADPHONES))
        assertEquals(OutputKind.Headphones, AudioOutputs.kindFor(AudioDeviceInfo.TYPE_HEARING_AID))
        assertEquals(OutputKind.Usb, AudioOutputs.kindFor(AudioDeviceInfo.TYPE_USB_HEADSET))
        assertEquals(OutputKind.Other, AudioOutputs.kindFor(AudioDeviceInfo.TYPE_HDMI))
    }

    @Test
    fun labelsReadAsWords() {
        assertEquals("speaker", AudioOutputs.typeLabel(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        assertEquals("bluetooth", AudioOutputs.typeLabel(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        assertEquals("wired", AudioOutputs.typeLabel(AudioDeviceInfo.TYPE_WIRED_HEADSET))
        assertEquals("usb", AudioOutputs.typeLabel(AudioDeviceInfo.TYPE_USB_DEVICE))
        assertEquals("hdmi", AudioOutputs.typeLabel(AudioDeviceInfo.TYPE_HDMI))
        assertEquals("headphones", AudioOutputs.kindLabel(OutputKind.Headphones))
        assertEquals("output", AudioOutputs.kindLabel(OutputKind.Other))
    }
}
