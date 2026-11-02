package de.hermi.voice

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import de.hermi.voice.audio.MicrophoneSource
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Smoke-Test der echten Mikrofonaufnahme (AudioRecord).
 * Nimmt ~2 s auf und prüft, dass Sample-Pakete ankommen.
 */
@RunWith(AndroidJUnit4::class)
class MicrophoneSourceInstrumentedTest {

    @get:Rule
    val micPermission: GrantPermissionRule =
        GrantPermissionRule.grant(android.Manifest.permission.RECORD_AUDIO)

    @Test
    fun capturesAudioSamples() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val mic = MicrophoneSource()
        val received = AtomicInteger(0)
        val sawAmplitude = AtomicBoolean(false)
        val done = CountDownLatch(1)

        mic.start(
            onSamples = { received.incrementAndGet() },
            onAmplitude = { if (it > 0f) sawAmplitude.set(true) }
        )

        // 2 s aufnehmen
        Thread.sleep(2000)

        mic.stop {
            done.countDown()
        }
        assertTrue("stop() wurde nicht aufgerufen", done.await(3, TimeUnit.SECONDS))

        assertTrue("Keine Sample-Pakete empfangen — AudioRecord liefert nichts",
            received.get() > 0)
        println("AUDIO_PACKETS=${received.get()} sawAmplitude=${sawAmplitude.get()}")
    }
}
