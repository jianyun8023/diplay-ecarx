package com.shilapi.xcertplay

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class SystemMediaSessionDiagnosticsTest {
    private val context = mock(Context::class.java)
    private val manager = mock(MediaSessionManager::class.java)

    @Test fun deniedPermissionNeverQueriesTheManager() {
        `when`(context.checkSelfPermission(Manifest.permission.MEDIA_CONTENT_CONTROL))
            .thenReturn(PackageManager.PERMISSION_DENIED)
        assertEquals("System media sessions: permission-denied", SystemMediaSessionDiagnostics.report(context))
        verify(context, never()).getSystemService(MediaSessionManager::class.java)
    }

    @Test fun preservesSystemOrderAndMarksTheFirstAndOwnPackage() {
        grant()
        `when`(context.packageName).thenReturn("com.shihab.diplay.hudtest")
        val other = mock(MediaController::class.java)
        val own = mock(MediaController::class.java)
        `when`(other.packageName).thenReturn("com.example.player")
        `when`(own.packageName).thenReturn("com.shihab.diplay.hudtest")
        `when`(other.playbackState).thenReturn(PlaybackState.Builder()
            .setState(PlaybackState.STATE_PLAYING, 0, 1f)
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE).build())
        `when`(manager.getActiveSessions(null)).thenReturn(listOf(other, own))

        val report = SystemMediaSessionDiagnostics.report(context)
        assertTrue(report.contains("count=2 first=com.example.player firstIsDiPlay=false"))
        assertTrue(report.indexOf("[0] package=com.example.player") <
            report.indexOf("[1] package=com.shihab.diplay.hudtest"))
        assertTrue(report.contains("state=3 actions=0x6 ownPackage=false"))
        assertTrue(report.contains("[1] package=com.shihab.diplay.hudtest state=none actions=none ownPackage=true"))
        verify(other, never()).metadata
        verify(own, never()).metadata
    }

    @Test fun emptyListReportsNoFirstSession() {
        grant()
        `when`(manager.getActiveSessions(null)).thenReturn(emptyList())
        assertEquals("System media sessions: count=0 first=none firstIsDiPlay=false",
            SystemMediaSessionDiagnostics.report(context))
    }

    @Test fun querySecurityExceptionReportsItsTypeWithoutItsMessage() {
        grant()
        `when`(manager.getActiveSessions(null)).thenThrow(SecurityException("secret-media-token"))
        val report = SystemMediaSessionDiagnostics.report(context)
        assertEquals("System media sessions: query-denied=SecurityException", report)
        assertFalse(report.contains("secret-media-token"))
    }

    private fun grant() {
        `when`(context.checkSelfPermission(Manifest.permission.MEDIA_CONTENT_CONTROL))
            .thenReturn(PackageManager.PERMISSION_GRANTED)
        `when`(context.getSystemService(MediaSessionManager::class.java)).thenReturn(manager)
    }
}
