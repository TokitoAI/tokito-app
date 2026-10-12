package dev.tokito.physical.model

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager

data class DeviceResourceSnapshot(
    val availableMemoryBytes: Long,
    val lowMemory: Boolean,
    val thermalStatus: Int,
)

data class HeavyWorkAdmission(
    val allowed: Boolean,
    val maxConcurrency: Int,
    val reason: String? = null,
)

/** Fail-closed admission for heavyweight local inference. */
class DeviceResourceGovernor(
    private val snapshot: () -> DeviceResourceSnapshot,
    private val memoryReserveBytes: Long = 512L * 1024 * 1024,
) {
    init {
        require(memoryReserveBytes >= 0)
    }

    fun admit(requiredBytes: Long): HeavyWorkAdmission {
        require(requiredBytes >= 0)
        val state = snapshot()
        if (state.lowMemory || state.availableMemoryBytes < requiredBytes + memoryReserveBytes) {
            return HeavyWorkAdmission(false, 0, "memory_limited")
        }
        return when {
            state.thermalStatus >= PowerManager.THERMAL_STATUS_CRITICAL ->
                HeavyWorkAdmission(false, 0, "thermal_limited")
            state.thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE ->
                HeavyWorkAdmission(true, 1, "thermal_constrained")
            else -> HeavyWorkAdmission(true, 2)
        }
    }

    companion object {
        fun android(context: Context): DeviceResourceGovernor {
            val application = context.applicationContext
            val activity = application.getSystemService(ActivityManager::class.java)
            val power = application.getSystemService(PowerManager::class.java)
            return DeviceResourceGovernor(snapshot = {
                val memory = ActivityManager.MemoryInfo().also(activity::getMemoryInfo)
                DeviceResourceSnapshot(
                    availableMemoryBytes = memory.availMem,
                    lowMemory = memory.lowMemory,
                    thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        power.currentThermalStatus
                    } else {
                        PowerManager.THERMAL_STATUS_NONE
                    },
                )
            })
        }
    }
}
