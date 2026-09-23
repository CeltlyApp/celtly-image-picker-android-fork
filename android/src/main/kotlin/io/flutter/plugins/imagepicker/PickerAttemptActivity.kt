// Copyright 2026 Celtly contributors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.
package io.flutter.plugins.imagepicker

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** One immutable launch identity per Android Activity instance/result token. */
class PickerAttemptActivity : Activity() {
    private lateinit var store: PickerAttemptStore
    private lateinit var delegate: ImagePickerDelegate
    private lateinit var cache: ImagePickerCache
    private lateinit var attemptId: String
    private var epoch = 0L
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            attemptId = intent.getStringExtra("id") ?: error("attempt_missing")
            epoch = intent.getLongExtra("epoch", 0)
            store = PickerAttemptStore(this)
            if (!store.matches(attemptId, epoch)) { finish(); return }
            cache = ImagePickerCache(this, "image_picker_attempt_$epoch")
            delegate = ImagePickerDelegate(this, ImageResizer(this, ExifDataCopier()), cache)
            val options = ImageSelectionOptions(
                if (intent.hasExtra("maxWidth")) intent.getDoubleExtra("maxWidth", 0.0) else null,
                if (intent.hasExtra("maxHeight")) intent.getDoubleExtra("maxHeight", 0.0) else null,
                intent.getLongExtra("quality", 100))
            val callback: (Result<List<String>>) -> Unit = { result ->
                try {
                    if (store.complete(attemptId, epoch, result.getOrNull(), result.isFailure,
                            (result.exceptionOrNull() as? FlutterError)?.code)) {
                        PickerAttemptBridge.publish(store, attemptId)
                    }
                } catch (_: Exception) { /* Failed durable completion stays unresolved. */ }
                runOnUiThread { finish() }
            }
            if (store.current()!!.getBoolean("externalLaunched")) {
                // Restore only the original callback/options; NEVER relaunch.
                delegate.restoreAttemptCallback(options, callback)
            } else {
                delegate.setCameraDevice(if (intent.getBooleanExtra("front", false))
                    ImagePickerDelegate.CameraDevice.FRONT else ImagePickerDelegate.CameraDevice.REAR)
                if (intent.getBooleanExtra("camera", false)) delegate.takeImageWithCamera(options, callback)
                else delegate.chooseImageFromGallery(options, intent.getBooleanExtra("photoPicker", true), callback)
            }
        } catch (_: Exception) { failSafely() }
    }
    private fun failSafely() {
        try {
            if (::store.isInitialized && ::attemptId.isInitialized &&
                store.complete(attemptId, epoch, null, true)) PickerAttemptBridge.publish(store, attemptId)
        } catch (_: Exception) { /* A failed journal cannot acknowledge recovery. */ }
        finish()
    }
    override fun onDestroy() {
        if (::delegate.isInitialized) delegate.closeAttemptExecutor()
        super.onDestroy()
    }
    @Deprecated("Legacy callback is scoped to this immutable attempt Activity")
    override fun startActivityForResult(intent: Intent, requestCode: Int) {
        delegate.saveStateBeforeResult()
        cache.flushDurably()
        check(store.externalLaunch(attemptId, epoch)) { "attempt_obsolete" }
        super.startActivityForResult(intent, requestCode)
    }
    @Deprecated("Legacy callback is scoped to this immutable attempt Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        try {
            if (!store.processing(attemptId, epoch)) { finish(); return }
            delegate.onActivityResult(requestCode, resultCode, data)
        } catch (_: Exception) { failSafely() }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        try {
            if (!store.matches(attemptId, epoch)) { finish(); return }
            delegate.onRequestPermissionsResult(requestCode, permissions, grantResults)
        } catch (_: Exception) { failSafely() }
    }
}
