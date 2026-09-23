// Copyright 2026 Celtly contributors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.
package io.flutter.plugins.imagepicker

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Looper
import io.flutter.plugin.common.BinaryMessenger
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 33, 36])
@LooperMode(LooperMode.Mode.PAUSED)
class PickerAttemptTest {
    private lateinit var context: Context
    private lateinit var store: PickerAttemptStore
    private val a = "a".repeat(48)
    private val b = "b".repeat(48)
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(PickerAttemptStore.NAME, Context.MODE_PRIVATE).edit().clear().commit()
        PickerAttemptStore.resetPoisonForTest()
        store = PickerAttemptStore(context)
    }
    private fun launch(id: String = a): Long {
        val epoch = store.begin(id).getLong("epoch")
        store.launch(id)
        return epoch
    }
    @Test fun registrationIsDurableBeforeLaunch() {
        store.begin(a)
        val restored = PickerAttemptStore(context).current()!!
        assertEquals(a, restored.getString("id")); assertEquals("prepared", restored.getString("state"))
    }
    @Test fun cannotRegisterConcurrentAttempt() {
        launch(); assertThrows(IllegalStateException::class.java) { store.begin(b) }
    }
    @Test fun malformedIdRejected() {
        assertThrows(IllegalArgumentException::class.java) { store.begin("route") }
    }
    @Test fun requiresBeginBeforeLaunch() {
        assertThrows(IllegalStateException::class.java) { store.launch(a) }
    }
    @Test fun authoritativeCancellationIsTerminal() {
        val epoch = launch(); assertTrue(store.complete(a, epoch, emptyList()))
        assertEquals("cancelled", store.current()!!.getString("state")); store.begin(b)
    }
    @Test fun emptyUnfinishedStateIsNotCancellation() {
        launch(); assertEquals("launched", PickerAttemptStore(context).current()!!.getString("state"))
        assertThrows(IllegalStateException::class.java) { store.begin(b) }
    }
    @Test fun invalidationPersistsBeforeNewBegin() {
        launch(); store.invalidate(a)
        assertEquals("invalidated", PickerAttemptStore(context).current()!!.getString("state"))
        store.begin(b)
    }
    @Test fun invalidationIsIdempotent() {
        launch(); store.invalidate(a); store.invalidate(a)
        assertEquals("invalidated", store.current()!!.getString("state"))
    }
    @Test fun invalidatedLateResultCannotCompleteNewAttempt() {
        val epochA = launch(); store.invalidate(a); val epochB = launch(b)
        assertFalse(store.complete(a, epochA, listOf("old")))
        assertEquals(b, store.current()!!.getString("id"))
        assertTrue(store.complete(b, epochB, listOf("new")))
        assertEquals("new", store.current()!!.getJSONArray("paths").getString(0))
    }
    @Test fun oldInvalidationCannotInvalidateNewAttempt() {
        launch(); store.invalidate(a); launch(b); store.invalidate(a)
        assertEquals("launched", store.current()!!.getString("state"))
    }
    @Test fun processRecreationPreservesResultIdentity() {
        val epoch = launch(); val restored = PickerAttemptStore(context)
        assertTrue(restored.complete(a, epoch, listOf("recovered")))
        assertEquals(a, restored.current()!!.getString("id"))
    }
    @Test fun processRecreationPreservesInvalidation() {
        val epoch = launch(); store.invalidate(a)
        assertFalse(PickerAttemptStore(context).complete(a, epoch, listOf("old")))
    }
    @Test fun duplicateCompletionIsRejected() {
        val epoch = launch(); assertTrue(store.complete(a, epoch, listOf("first")))
        assertFalse(store.complete(a, epoch, listOf("duplicate")))
        assertEquals("first", store.current()!!.getJSONArray("paths").getString(0))
    }
    @Test fun consumeIsIdempotentAndClearsRecoverablePaths() {
        val epoch = launch(); store.complete(a, epoch, listOf("photo")); store.consume(a); store.consume(a)
        assertEquals(0, store.current()!!.getJSONArray("paths").length())
        assertFalse(store.complete(a, epoch, listOf("duplicate")))
    }
    @Test fun invalidateAfterCompletionRevokesUndeliveredResult() {
        val epoch = launch(); store.complete(a, epoch, listOf("photo")); store.invalidate(a)
        assertEquals(0, store.current()!!.getJSONArray("paths").length())
    }
    @Test fun wrongEpochRejectedEvenWithSameId() {
        val epoch = launch(); assertFalse(store.complete(a, epoch + 1, listOf("wrong")))
    }
    @Test fun duplicateNativeResultDoesNotScheduleSecondWorker() {
        val epoch = launch(); assertTrue(store.processing(a, epoch)); assertFalse(store.processing(a, epoch))
    }
    @Test fun originalActivityIntentRetainsOriginalOwnerAfterNewBegin() {
        val epoch = launch()
        val intent = Intent(context, PickerAttemptActivity::class.java).putExtra("id", a).putExtra("epoch", epoch)
        val controller = Robolectric.buildActivity(PickerAttemptActivity::class.java, intent).create()
        val original = controller.get()
        assertNotNull(shadowOf(original).nextStartedActivityForResult)
        store.invalidate(a); launch(b)
        assertEquals(a, original.intent.getStringExtra("id"))
        // Actual Activity boundary drops the old cancellation/result before any delegate worker.
        PickerAttemptActivity::class.java.getDeclaredMethod("onActivityResult", Integer.TYPE, Integer.TYPE, Intent::class.java)
            .apply { isAccessible = true }
            .invoke(original, ImagePickerDelegate.REQUEST_CODE_CHOOSE_IMAGE_FROM_GALLERY, Activity.RESULT_OK, Intent())
        assertEquals(b, store.current()!!.getString("id"))
        assertEquals("launched", store.current()!!.getString("state"))
    }
    @Test fun recreatedActivityDoesNotRelaunchExternalPicker() {
        val epoch = launch(); store.externalLaunch(a, epoch)
        val intent = Intent(context, PickerAttemptActivity::class.java).putExtra("id", a).putExtra("epoch", epoch)
        val restored = Robolectric.buildActivity(PickerAttemptActivity::class.java, intent).create().get()
        assertNull(shadowOf(restored).nextStartedActivityForResult)
    }
    @Test fun completionInvalidationRaceIsLinearizable() {
        repeat(100) { index ->
            setup(); val epoch = launch(); val start = CountDownLatch(1)
            val errors = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
            val complete = thread { try { start.await(); store.complete(a, epoch, listOf("old")) } catch (e: Throwable) { errors.add(e) } }
            val invalidate = thread { try { start.await(); store.invalidate(a) } catch (e: Throwable) { errors.add(e) } }
            start.countDown(); complete.join(); invalidate.join()
            assertTrue("race $index", errors.isEmpty())
            assertEquals("invalidated", store.current()!!.getString("state")); launch(b)
            assertFalse(store.complete(a, epoch, listOf("late")))
        }
    }
    @Test fun lateNativePublicationNeverUsesNewDartCallback() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val bridge = PickerAttemptBridge(activity, mock(BinaryMessenger::class.java))
        var receivedA: List<String>? = null
        var receivedB: List<String>? = null
        val epochA = store.begin(a).getLong("epoch")
        bridge.pick(activity, SourceSpecification(SourceType.GALLERY, null), ImageSelectionOptions(null, null, 100),
            GeneralOptions(false, true, null)) { receivedA = it.getOrThrow() }
        val intentA = shadowOf(activity).nextStartedActivity
        store.invalidate(a)
        val epochB = store.begin(b).getLong("epoch")
        bridge.pick(activity, SourceSpecification(SourceType.GALLERY, null), ImageSelectionOptions(null, null, 100),
            GeneralOptions(false, true, null)) { receivedB = it.getOrThrow() }
        val intentB = shadowOf(activity).nextStartedActivity
        assertEquals(a, intentA.getStringExtra("id")); assertEquals(b, intentB.getStringExtra("id"))
        assertFalse(store.complete(a, epochA, listOf("old")))
        PickerAttemptBridge.publish(store, a); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(emptyList<String>(), receivedA); assertNull(receivedB)
        assertTrue(store.complete(b, epochB, listOf("new")))
        PickerAttemptBridge.publish(store, b); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("new"), receivedB)
        bridge.close()
    }
    @Test fun completionQueuedBeforeInvalidationIsRecheckedBeforeDartDelivery() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val bridge = PickerAttemptBridge(activity, mock(BinaryMessenger::class.java))
        var received: List<String>? = null
        val epoch = store.begin(a).getLong("epoch")
        bridge.pick(activity, SourceSpecification(SourceType.CAMERA, SourceCamera.REAR), ImageSelectionOptions(null, null, 100),
            GeneralOptions(false, true, null)) { received = it.getOrThrow() }
        assertTrue(store.complete(a, epoch, listOf("old")))
        PickerAttemptBridge.publish(store, a)
        store.invalidate(a); store.begin(b)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(emptyList<String>(), received)
        assertEquals("prepared", store.current()!!.getString("state"))
        bridge.close()
    }
    @Test fun failedCommitCannotAcknowledgeOrPermitNextBegin() {
        val preferences = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java)
        `when`(preferences.edit()).thenReturn(editor)
        `when`(editor.putString(anyString(), anyString())).thenReturn(editor)
        `when`(editor.commit()).thenReturn(false)
        val failing = PickerAttemptStore(preferences)
        assertThrows(IllegalStateException::class.java) { failing.begin(a) }
        assertThrows(IllegalStateException::class.java) { failing.begin(b) }
        assertThrows(IllegalStateException::class.java) { failing.current() }
    }
    @Test fun malformedPersistentJournalFailsClosed() {
        context.getSharedPreferences(PickerAttemptStore.NAME, Context.MODE_PRIVATE).edit().putString("record", "{}").commit()
        assertThrows(Exception::class.java) { store.current() }
        assertThrows(Exception::class.java) { store.begin(a) }
    }
    @Test fun lateInvalidatedResizeCannotOverwriteSameNamedNewGalleryImage() {
        val epochA = launch()
        val first = Robolectric.buildActivity(PickerAttemptActivity::class.java,
            Intent(context, PickerAttemptActivity::class.java).putExtra("id", a).putExtra("epoch", epochA)).create().get()
        val scopeA = first.processingContext()
        store.invalidate(a)
        val epochB = launch(b)
        val second = Robolectric.buildActivity(PickerAttemptActivity::class.java,
            Intent(context, PickerAttemptActivity::class.java).putExtra("id", b).putExtra("epoch", epochB)).create().get()
        val scopeB = second.processingContext()
        assertNotEquals(scopeA.cacheDir.canonicalPath, scopeB.cacheDir.canonicalPath)
        assertEquals(scopeA.cacheDir.canonicalPath, first.processingContext().cacheDir.canonicalPath)
        assertEquals(context.cacheDir.canonicalPath, first.cacheDir.canonicalPath)
        assertEquals(context.cacheDir.canonicalPath, second.cacheDir.canonicalPath)
        fun resized(scope: Context): File {
            val input = File(scope.cacheDir, "same.png")
            javaClass.classLoader!!.getResourceAsStream("pngImage.png")!!.use { source ->
                input.outputStream().use { source.copyTo(it) }
            }
            return File(ImageResizer(scope, ExifDataCopier()).resizeImageIfNeeded(input.path, 1.0, 1.0, 90))
        }
        val outputA = resized(scopeA)
        val outputB = resized(scopeB)
        assertEquals("scaled_same.png", outputA.name)
        assertEquals("scaled_same.png", outputB.name)
        assertNotEquals(outputA.canonicalPath, outputB.canonicalPath)
        val bytesB = outputB.readBytes()
        outputA.writeText("late invalidated worker")
        assertArrayEquals(bytesB, outputB.readBytes())
        assertEquals(b, store.current()!!.getString("id"))
    }
}
