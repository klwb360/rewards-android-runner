package com.aliothmoon.maafw.service

import android.Manifest
import android.app.AppOpsManager
import android.app.Notification
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Process
import timber.log.Timber

/**
 * specialUse FGS 预检：appop 被 ROM 或管控工具拒绝时 startForeground 必抛
 * SecurityException，把整个进程掀翻。调用方拿到 denied 就降级为无 FGS 运行
 */
object SpecialUseFgsGate {

    @Suppress("DEPRECATION")
    fun isDenied(context: Context): Boolean {
        if (context.checkSelfPermission(Manifest.permission.FOREGROUND_SERVICE) != PackageManager.PERMISSION_GRANTED) {
            return true
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        val permission = Manifest.permission.FOREGROUND_SERVICE_SPECIAL_USE
        val granted = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        val mode = AppOpsManager.permissionToOp(permission)?.let { op ->
            try {
                context.getSystemService(AppOpsManager::class.java)
                    .unsafeCheckOpRawNoThrow(op, Process.myUid(), context.packageName)
            } catch (e: RuntimeException) {
                Timber.w(e, "SpecialUseFgsGate: read appop failed")
                null
            }
        }
        val denied = isDenied(mode, granted)
        if (denied) Timber.w("SpecialUseFgsGate: denied, mode=%s granted=%s", mode, granted)
        return denied
    }

    /** 对齐 AOSP checkAppOpPermission；FOREGROUND 等放行，交给 startForeground 的 catch 兜底 */
    internal fun isDenied(mode: Int?, permissionGranted: Boolean): Boolean = when (mode) {
        AppOpsManager.MODE_IGNORED, AppOpsManager.MODE_ERRORED -> true
        AppOpsManager.MODE_DEFAULT, null -> !permissionGranted
        else -> false
    }

    /** No notification permission is required to start FGS on Android 13. */
    internal fun hasForegroundCapability(
        sdk: Int,
        declaredAndEnabled: Boolean,
        basePermissionGranted: Boolean,
        specialPermissionGranted: Boolean,
        specialUseTypeDeclared: Boolean,
    ): Boolean = declaredAndEnabled && basePermissionGranted &&
        (sdk < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            (specialPermissionGranted && specialUseTypeDeclared))

    @Suppress("DEPRECATION")
    private fun requireDeclaredCapability(service: Service) {
        val info = try {
            service.packageManager.getServiceInfo(ComponentName(service, service.javaClass), 0)
        } catch (failure: PackageManager.NameNotFoundException) {
            throw SecurityException("Foreground service is not declared", failure)
        } catch (failure: RuntimeException) {
            throw SecurityException("Foreground service declaration cannot be verified", failure)
        }
        val sdk = Build.VERSION.SDK_INT
        val allowed = hasForegroundCapability(
            sdk = sdk,
            declaredAndEnabled = info.packageName == service.packageName && info.enabled && info.applicationInfo.enabled,
            basePermissionGranted = service.checkSelfPermission(Manifest.permission.FOREGROUND_SERVICE) == PackageManager.PERMISSION_GRANTED,
            specialPermissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
                service.checkSelfPermission(Manifest.permission.FOREGROUND_SERVICE_SPECIAL_USE) == PackageManager.PERMISSION_GRANTED,
            specialUseTypeDeclared = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                info.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE != 0,
        )
        if (!allowed || isDenied(service)) {
            throw SecurityException("Foreground service capability is unavailable")
        }
    }

    /** Unregistered legacy services fail closed; this helper never grants a capability. */
    fun startForeground(service: Service, id: Int, notification: Notification) {
        requireDeclaredCapability(service)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            service.startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            service.startForeground(id, notification)
        }
    }
}
