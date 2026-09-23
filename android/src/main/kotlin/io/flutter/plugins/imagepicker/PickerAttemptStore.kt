// Copyright 2026 Celtly contributors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.
package io.flutter.plugins.imagepicker

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** One durable current attempt. Older epochs are permanently obsolete.
 * All transitions linearize at a checked synchronous commit under one lock.
 * No account, route, or application destination is stored in this journal.
 */
internal class PickerAttemptStore(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences(NAME, Context.MODE_PRIVATE))
    companion object {
        const val NAME = "image_picker_attempt_journal_v1"
        private val lock = Any()
        private var poisoned = false
        private val terminal = setOf("cancelled", "invalidated", "consumed", "failed")
        internal fun resetPoisonForTest() { synchronized(lock) { poisoned = false } }
    }
    private fun read(): JSONObject? {
        check(!poisoned) { "attempt_storage" }
        return preferences.getString("record", null)?.let {
            JSONObject(it).also { row ->
                check(row.getInt("version") == 1 && row.getLong("epoch") > 0 &&
                    row.getString("id").matches(Regex("[a-f0-9]{48}"))) { "attempt_record" }
                check(row.getString("state") in terminal + setOf("prepared", "launched", "processing", "completed"))
            }
        }
    }
    private fun save(row: JSONObject): JSONObject {
        if (!preferences.edit().putString("record", row.toString()).commit()) {
            poisoned = true
            error("attempt_storage")
        }
        return row
    }
    fun current(): JSONObject? = synchronized(lock) { read() }
    fun begin(id: String): JSONObject = synchronized(lock) {
        require(id.matches(Regex("[a-f0-9]{48}"))) { "attempt_id" }
        val old = read()
        check(old == null || old.getString("state") in terminal) { "attempt_active" }
        check(old == null || old.getString("id") != id) { "attempt_reused" }
        val epoch = (old?.getLong("epoch") ?: 0L) + 1
        check(epoch > 0) { "attempt_exhausted" }
        save(JSONObject().put("version", 1).put("id", id).put("epoch", epoch)
            .put("state", "prepared").put("externalLaunched", false))
    }
    fun launch(id: String): JSONObject = synchronized(lock) {
        val row = read() ?: error("attempt_missing")
        check(row.getString("id") == id && row.getString("state") == "prepared")
        save(row.put("state", "launched"))
    }
    fun matches(id: String, epoch: Long): Boolean = synchronized(lock) {
        val row = read()
        row != null && row.getString("id") == id && row.getLong("epoch") == epoch &&
            row.getString("state") in setOf("launched", "processing")
    }
    fun externalLaunch(id: String, epoch: Long): Boolean = synchronized(lock) {
        val row = read() ?: return@synchronized false
        if (!matches(id, epoch) || row.getBoolean("externalLaunched")) return@synchronized false
        save(row.put("externalLaunched", true))
        true
    }
    fun processing(id: String, epoch: Long): Boolean = synchronized(lock) {
        val row = read() ?: return@synchronized false
        if (!matches(id, epoch) || row.getString("state") != "launched") return@synchronized false
        save(row.put("state", "processing"))
        true
    }
    fun complete(id: String, epoch: Long, paths: List<String>?, failed: Boolean = false, errorCode: String? = null): Boolean = synchronized(lock) {
        val row = read() ?: return@synchronized false
        if (!matches(id, epoch)) return@synchronized false
        save(row.put("state", if (failed) "failed" else if (paths.isNullOrEmpty()) "cancelled" else "completed")
            .put("errorCode", errorCode ?: "attempt_unavailable")
            .put("paths", JSONArray(if (failed) emptyList<String>() else paths ?: emptyList())))
        true
    }
    fun invalidate(id: String): JSONObject = synchronized(lock) {
        val row = read()
        if (row == null || row.getString("id") != id) {
            // Only the current epoch can ever complete; absent/old IDs cannot resurrect.
            return@synchronized JSONObject().put("id", id).put("state", "obsolete")
        }
        save(row.put("state", "invalidated").put("paths", JSONArray()))
    }
    fun consume(id: String): JSONObject = synchronized(lock) {
        val row = read() ?: error("attempt_missing")
        check(row.getString("id") == id && row.getString("state") in setOf("completed", "consumed"))
        save(row.put("state", "consumed").put("paths", JSONArray()))
    }
}
