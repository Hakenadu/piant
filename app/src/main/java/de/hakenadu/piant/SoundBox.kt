package de.hakenadu.piant

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Soft marimba-like plinks in a pentatonic scale, so any combination of touches sounds nice.
 * The notes are synthesized once into small WAV files, no audio assets needed.
 */
class SoundBox(context: Context) {

    var enabled = true

    private val pool = SoundPool.Builder()
        .setMaxStreams(8)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()

    private val soundIds = IntArray(NOTES.size)
    private var lastPlay = 0L

    init {
        val dir = context.cacheDir
        thread(name = "piant-sounds", isDaemon = true) {
            NOTES.forEachIndexed { i, freq ->
                val file = File(dir, "piant_note_v1_$i.wav")
                if (!file.exists()) file.writeBytes(synthesize(freq))
                soundIds[i] = pool.load(file.path, 1)
            }
        }
    }

    /** Plays a note picked by horizontal position (0 = low, 1 = high). */
    fun note(fraction: Float, volume: Float = 0.6f) {
        if (!enabled) return
        val now = SystemClock.uptimeMillis()
        if (now - lastPlay < 40) return
        lastPlay = now
        val index = (fraction.coerceIn(0f, 0.999f) * NOTES.size).toInt()
        val id = soundIds[index]
        if (id != 0) pool.play(id, volume, volume, 1, 0, 1f)
    }

    /** Little two-note "bloop" for picking a color. */
    fun pick(slot: Int) {
        note((slot + 2f) / (PiantColors.SLOT_COUNT + 3f), 0.5f)
    }

    fun release() = pool.release()

    private fun synthesize(freq: Double): ByteArray {
        val rate = 22050
        val samples = (rate * 0.8).toInt()
        val buffer = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray())
        buffer.putInt(36 + samples * 2)
        buffer.put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16)
        buffer.putShort(1.toShort()) // PCM
        buffer.putShort(1.toShort()) // mono
        buffer.putInt(rate)
        buffer.putInt(rate * 2)
        buffer.putShort(2.toShort())
        buffer.putShort(16.toShort())
        buffer.put("data".toByteArray())
        buffer.putInt(samples * 2)
        for (i in 0 until samples) {
            val t = i.toDouble() / rate
            val envelope = min(1.0, t / 0.004) * exp(-t * 5.5)
            val w = 2.0 * PI * freq * t
            val s = sin(w) + 0.12 * sin(2.0 * w) + 0.25 * sin(4.0 * w) * exp(-t * 20.0)
            val v = (s * envelope * 0.42 * Short.MAX_VALUE).toInt().coerceIn(-32768, 32767)
            buffer.putShort(v.toShort())
        }
        return buffer.array()
    }

    private companion object {
        // C major pentatonic, two octaves: C5 D5 E5 G5 A5 C6 D6 E6 G6
        val NOTES = doubleArrayOf(523.25, 587.33, 659.25, 783.99, 880.0, 1046.5, 1174.66, 1318.51, 1567.98)
    }
}
