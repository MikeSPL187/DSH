package com.dsh.benchmark

import android.content.Intent
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TARGET = "app.dsh.mobile.benchmark"

@RunWith(AndroidJUnit4::class)
class DshStartupBenchmark {
    @get:Rule val benchmark = MacrobenchmarkRule()

    // Initial display only: this does not assert Host readiness or onboarding completion.
    @Test fun initialDisplay() = benchmark.measureRepeated(
        packageName = TARGET, metrics = listOf(StartupTimingMetric()), iterations = 10,
        startupMode = StartupMode.COLD, compilationMode = CompilationMode.None(),
        setupBlock = { pressHome() },
    ) { startActivityAndWait() }
}

@RunWith(AndroidJUnit4::class)
class DshRenderingBenchmark {
    @get:Rule val benchmark = MacrobenchmarkRule()

    @Test fun directory100() = scroll("files", 100)
    @Test fun directory1000() = scroll("files", 1000)
    @Test fun directory10000() = scroll("files", 10000)
    @Test fun longChat() = scroll("chat")
    @Test fun streamingChat() = scroll("stream")

    private fun scroll(scenario: String, count: Int = 1000) = benchmark.measureRepeated(
        packageName = TARGET, metrics = listOf(FrameTimingMetric()), iterations = 5,
        compilationMode = CompilationMode.None(),
        setupBlock = {
            killProcess()
            startActivityAndWait(Intent().setClassName(TARGET, "com.dsh.PerformanceFixtureActivity")
                .putExtra("scenario", scenario).putExtra("count", count))
            val loaded = if (scenario == "files") By.textStartsWith("folder-") else By.textContains("answer-399")
            assertTrue("Workload failed to become visible", device.wait(Until.hasObject(loaded), 30_000))
        },
    ) {
        val x = device.displayWidth / 2
        val top = device.displayHeight * 30 / 100
        val bottom = device.displayHeight * 75 / 100
        repeat(6) {
            device.swipe(x, top, x, bottom, 30)
            device.swipe(x, bottom, x, top, 30)
        }
    }
}
