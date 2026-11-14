package com.phoneproof.diagnostics.registry

import com.phoneproof.diagnostics.model.DiagnosticTest
import com.phoneproof.diagnostics.model.RunDepth
import com.phoneproof.diagnostics.model.RunMode
import com.phoneproof.diagnostics.tests.auto.BatteryHealthTest
import com.phoneproof.diagnostics.tests.auto.CellularRadioTest
import com.phoneproof.diagnostics.tests.auto.ChargingTest
import com.phoneproof.diagnostics.tests.auto.CounterfeitTest
import com.phoneproof.diagnostics.tests.auto.DeviceAgeTest
import com.phoneproof.diagnostics.tests.auto.DisplayCapabilityTest
import com.phoneproof.diagnostics.tests.auto.GpsTest
import com.phoneproof.diagnostics.tests.auto.IdentityTest
import com.phoneproof.diagnostics.tests.auto.IntegrityTest
import com.phoneproof.diagnostics.tests.auto.SensorInventoryTest
import com.phoneproof.diagnostics.tests.auto.StorageSpeedTest
import com.phoneproof.diagnostics.tests.auto.ThermalTest
import com.phoneproof.diagnostics.tests.auto.WirelessTest

/**
 * Assembles the test list for a given [RunMode] and [RunDepth].
 *
 * Time budget is the constraint that shapes this: the product promise is a complete run in under
 * eight minutes, so the long tests (thermal, deep storage) are only included at [RunDepth.DEEP],
 * and [RunDepth.QUICK] drops everything that needs a human.
 */
object TestCatalogue {

    /** Every automatic test, in the order they should normally execute. */
    fun automaticTests(depth: RunDepth): List<DiagnosticTest> = buildList {
        add(IdentityTest())
        add(IntegrityTest())
        add(DisplayCapabilityTest())
        add(SensorInventoryTest())
        add(CounterfeitTest())
        add(BatteryHealthTest(allowLoadMeasurement = depth == RunDepth.DEEP))
        add(ChargingTest())
        add(StorageSpeedTest(payloadBytes = storagePayloadFor(depth)))
        add(CellularRadioTest())
        add(WirelessTest())
        add(DeviceAgeTest())

        if (depth != RunDepth.QUICK) {
            add(GpsTest())
        }
        if (depth == RunDepth.DEEP) {
            add(ThermalTest(durationMs = DEEP_THERMAL_MS))
        } else if (depth == RunDepth.STANDARD) {
            add(ThermalTest(durationMs = STANDARD_THERMAL_MS))
        }
    }

    /** Guided tests are supplied by the wizard module, which owns their UI. */
    fun testsFor(
        mode: RunMode,
        depth: RunDepth,
        guidedTests: List<DiagnosticTest> = emptyList(),
    ): List<DiagnosticTest> {
        val automatic = automaticTests(depth)
        return if (depth == RunDepth.QUICK) automatic else automatic + guidedTests
    }

    private fun storagePayloadFor(depth: RunDepth): Long = when (depth) {
        RunDepth.QUICK -> 64L * 1024 * 1024
        RunDepth.STANDARD -> 256L * 1024 * 1024
        RunDepth.DEEP -> 512L * 1024 * 1024
    }

    private const val STANDARD_THERMAL_MS = 90_000L
    private const val DEEP_THERMAL_MS = 180_000L
}
