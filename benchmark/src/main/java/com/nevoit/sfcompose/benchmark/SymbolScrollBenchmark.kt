package com.nevoit.sfcompose.benchmark

import android.content.Intent
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SymbolScrollBenchmark {
    @get:Rule val benchmark = MacrobenchmarkRule()

    @Test
    fun sfSymbol() = measure(useVector = false)

    @Test
    fun painterResource() = measure(useVector = true)

    private fun measure(useVector: Boolean) = benchmark.measureRepeated(
        packageName = "com.nevoit.sfcompose.benchmark",
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Full(),
        iterations = 5,
        setupBlock = {
            startActivityAndWait(Intent.makeRestartActivityTask(
                android.content.ComponentName("com.nevoit.sfcompose.benchmark", "com.nevoit.sfcompose.BenchmarkGridActivity"),
            ).putExtra("vectorDrawable", useVector))
            check(device.wait(Until.hasObject(By.text("Start benchmark")), 30_000)) { "Grid warmup did not finish" }
        },
        measureBlock = {
            checkNotNull(device.findObject(By.text("Start benchmark"))).click()
            check(device.wait(Until.hasObject(By.text("Scroll complete")), 30_000)) { "Grid scroll did not finish" }
        },
    )
}
