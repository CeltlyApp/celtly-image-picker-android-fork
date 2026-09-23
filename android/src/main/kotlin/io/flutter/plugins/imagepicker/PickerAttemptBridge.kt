// Copyright 2026 Celtly contributors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.
package io.flutter.plugins.imagepicker

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel
import org.json.JSONObject

/** Opt-in sideband. The regular image_picker API remains unchanged. */
internal class PickerAttemptBridge(private val context: Context, messenger: BinaryMessenger) {
    private val store by lazy { PickerAttemptStore(context) }
    private val channel = MethodChannel(messenger, "plugins.flutter.io/image_picker/attempts_v1")
    companion object {
        private val callbacks = mutableMapOf<String, (Result<List<String>>) -> Unit>()
        private val main = Handler(Looper.getMainLooper())
        internal fun publish(store: PickerAttemptStore, id: String) {
            main.post {
                // Re-read at delivery time: invalidation may have won since completion.
                val row = try { store.current() } catch (_: Exception) { null }
                val callback = callbacks.remove(id) ?: return@post
                if (row != null && row.optString("id") == id && row.optString("state") == "failed") {
                    callback(Result.failure(FlutterError(row.optString("errorCode", "attempt_unavailable"),
                        "Photo selection could not continue.", null)))
                } else if (row == null || row.optString("id") != id || row.optString("state") != "completed") {
                    callback(Result.success(emptyList()))
                } else {
                    val paths = row.getJSONArray("paths")
                    callback(Result.success(List(paths.length()) { paths.getString(it) }))
                }
            }
        }
        internal fun wire(row: JSONObject?): Map<String, Any?> {
            if (row == null) return mapOf("state" to "none")
            val paths = row.optJSONArray("paths")
            return mapOf("id" to row.getString("id"), "state" to row.getString("state"),
                "paths" to if (paths == null) emptyList<String>() else List(paths.length()) { paths.getString(it) })
        }
    }
    init {
        channel.setMethodCallHandler { call, reply ->
            try {
                val id = call.argument<String>("id")
                val row = when (call.method) {
                    "begin" -> store.begin(requireNotNull(id))
                    "query" -> store.current()
                    "invalidate" -> store.invalidate(requireNotNull(id)).also {
                        if (it.has("epoch")) context.deleteSharedPreferences("image_picker_attempt_${it.getLong("epoch")}")
                        publish(store, id)
                    }
                    "consume" -> store.consume(requireNotNull(id)).also {
                        context.deleteSharedPreferences("image_picker_attempt_${it.getLong("epoch")}")
                    }
                    else -> { reply.notImplemented(); return@setMethodCallHandler }
                }
                reply.success(wire(row))
            } catch (_: Exception) {
                reply.error("attempt_unavailable", "Photo selection could not continue.", null)
            }
        }
    }
    fun close() { channel.setMethodCallHandler(null) }
    fun enabled(): Boolean = store.current() != null
    fun pick(activity: Activity, source: SourceSpecification, options: ImageSelectionOptions,
             general: GeneralOptions, callback: (Result<List<String>>) -> Unit) {
        var registeredId: String? = null
        try {
            check(!general.allowMultiple) { "attempt_single_image_only" }
            val id = store.current()?.getString("id") ?: error("attempt_missing")
            val row = store.launch(id)
            callbacks[id] = callback
            registeredId = id
            val intent = Intent(activity, PickerAttemptActivity::class.java)
                .putExtra("id", id).putExtra("epoch", row.getLong("epoch"))
                .putExtra("camera", source.type == SourceType.CAMERA)
                .putExtra("front", source.camera == SourceCamera.FRONT)
                .putExtra("quality", options.quality)
                .putExtra("photoPicker", general.usePhotoPicker)
            options.maxWidth?.let { intent.putExtra("maxWidth", it) }
            options.maxHeight?.let { intent.putExtra("maxHeight", it) }
            // Separate standard Activity instance: Android's result token, not a
            // shared request-code callback slot, is the original launch owner.
            activity.startActivity(intent)
        } catch (_: Exception) {
            registeredId?.let { callbacks.remove(it) }
            callback(Result.failure(FlutterError("attempt_unavailable", "Photo selection could not continue.", null)))
        }
    }
}
