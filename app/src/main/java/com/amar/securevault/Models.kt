package com.amar.securevault

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Entry(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val username: String = "",
    val password: String = "",
    val url: String = "",
    val notes: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)

object EntryJson {
    fun toBytes(list: List<Entry>): ByteArray {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(
                JSONObject()
                    .put("id", e.id)
                    .put("title", e.title)
                    .put("username", e.username)
                    .put("password", e.password)
                    .put("url", e.url)
                    .put("notes", e.notes)
                    .put("updatedAt", e.updatedAt)
            )
        }
        return arr.toString().toByteArray(Charsets.UTF_8)
    }

    fun fromBytes(bytes: ByteArray): List<Entry> {
        val arr = JSONArray(String(bytes, Charsets.UTF_8))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Entry(
                id = o.getString("id"),
                title = o.getString("title"),
                username = o.optString("username", ""),
                password = o.optString("password", ""),
                url = o.optString("url", ""),
                notes = o.optString("notes", ""),
                updatedAt = o.optLong("updatedAt", 0L)
            )
        }
    }
}
