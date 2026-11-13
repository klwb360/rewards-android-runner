package com.aliothmoon.maafw.service

import android.app.AppOpsManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpecialUseFgsGateTest {

    @Test
    fun ignoredOrErroredAppOpDeniesEvenWhenInstallGranted() {
        assertTrue(SpecialUseFgsGate.isDenied(AppOpsManager.MODE_IGNORED, permissionGranted = true))
        assertTrue(SpecialUseFgsGate.isDenied(AppOpsManager.MODE_ERRORED, permissionGranted = true))
    }

    @Test
    fun defaultModeFollowsInstallGrant() {
        assertFalse(SpecialUseFgsGate.isDenied(AppOpsManager.MODE_DEFAULT, permissionGranted = true))
        assertTrue(SpecialUseFgsGate.isDenied(AppOpsManager.MODE_DEFAULT, permissionGranted = false))
    }

    @Test
    fun unreadableModeFallsBackToInstallGrant() {
        assertFalse(SpecialUseFgsGate.isDenied(null, permissionGranted = true))
        assertTrue(SpecialUseFgsGate.isDenied(null, permissionGranted = false))
    }

    @Test
    fun allowedAppOpWinsOverMissingInstallGrant() {
        assertFalse(SpecialUseFgsGate.isDenied(AppOpsManager.MODE_ALLOWED, permissionGranted = false))
    }

    @Test
    fun foregroundModeIsLeftToStartForegroundCatch() {
        assertFalse(SpecialUseFgsGate.isDenied(AppOpsManager.MODE_FOREGROUND, permissionGranted = true))
    }

    @Test
    fun undeclaredOrDisabledServiceIsRejectedAtEverySupportedApi() {
        for (sdk in listOf(28, 29, 33, 34, 37)) {
            assertFalse(SpecialUseFgsGate.hasForegroundCapability(sdk, false, true, true, true))
        }
    }

    @Test
    fun missingBasePermissionIsAlwaysRejected() {
        for (sdk in listOf(28, 33, 34, 37)) {
            assertFalse(SpecialUseFgsGate.hasForegroundCapability(sdk, true, false, true, true))
        }
    }

    @Test
    fun api34RequiresBothSpecialPermissionAndDeclaredType() {
        assertFalse(SpecialUseFgsGate.hasForegroundCapability(34, true, true, false, true))
        assertFalse(SpecialUseFgsGate.hasForegroundCapability(34, true, true, true, false))
        assertTrue(SpecialUseFgsGate.hasForegroundCapability(34, true, true, true, true))
    }

    @Test
    fun android13DoesNotRequireSpecialUseOrNotificationPermissionForForegroundStart() {
        // POST_NOTIFICATIONS intentionally is not an input to FGS capability.
        assertTrue(SpecialUseFgsGate.hasForegroundCapability(33, true, true, false, false))
        assertTrue(SpecialUseFgsGate.hasForegroundCapability(28, true, true, false, false))
    }

}
