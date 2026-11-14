package com.myklharu.stride

import android.content.Context
import android.content.Intent
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/** Small callback so Java can receive results from the coroutine code. */
fun interface StrideCallback {
    fun done(result: String)
}

/** Reads daily step totals from Health Connect (which phones, watches and fitness apps write to). */
object HealthBridge {
    @JvmField
    val PERMISSIONS: Set<String> = setOf(HealthPermission.getReadPermission(StepsRecord::class))

    private val contract = PermissionController.createRequestPermissionResultContract()

    @JvmStatic
    fun status(ctx: Context): String = when (HealthConnectClient.getSdkStatus(ctx)) {
        HealthConnectClient.SDK_AVAILABLE -> "available"
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "update"
        else -> "unavailable"
    }

    @JvmStatic
    fun permissionIntent(ctx: Context): Intent = contract.createIntent(ctx, PERMISSIONS)

    @JvmStatic
    fun wasGranted(resultCode: Int, data: Intent?): Boolean = contract.parseResult(resultCode, data).containsAll(PERMISSIONS)

    @JvmStatic
    fun hasPermission(ctx: Context, cb: StrideCallback) {
        if (status(ctx) != "available") {
            cb.done("false"); return
        }
        CoroutineScope(Dispatchers.IO).launch {
            val ok = try {
                HealthConnectClient.getOrCreate(ctx).permissionController.getGrantedPermissions().containsAll(PERMISSIONS)
            } catch (e: Exception) {
                false
            }
            cb.done(if (ok) "true" else "false")
        }
    }

    /** Total steps for one day (yyyy-MM-dd, phone's time zone). Health Connect removes duplicates between sources. */
    @JvmStatic
    fun readSteps(ctx: Context, isoDate: String, cb: StrideCallback) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val zone = ZoneId.systemDefault()
                val day = LocalDate.parse(isoDate)
                val start = day.atStartOfDay(zone).toInstant()
                val end = day.plusDays(1).atStartOfDay(zone).toInstant()
                val result = HealthConnectClient.getOrCreate(ctx).aggregate(
                    AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(start, end))
                )
                cb.done((result[StepsRecord.COUNT_TOTAL] ?: 0L).toString())
            } catch (e: Exception) {
                cb.done("error:" + (e.message ?: "failed"))
            }
        }
    }
}
