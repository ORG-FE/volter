package dev.c0redev.volter.json

import org.json.JSONArray
import org.json.JSONObject

fun JSONObject.optNullableString(name: String): String? {
    if (!has(name) || isNull(name)) return null
    return optString(name).trim().takeIf { it.isNotEmpty() }
}

fun JSONObject.optNullableBoolean(name: String): Boolean? {
    if (!has(name) || isNull(name)) return null
    return optBoolean(name)
}

fun JSONObject.optNullableInt(name: String): Int? {
    if (!has(name) || isNull(name)) return null
    return optInt(name)
}

fun JSONObject.optJSONArrayStringList(name: String): List<String>? {
    if (!has(name) || isNull(name)) return null
    val a = optJSONArray(name) ?: return null
    return List(a.length()) { i -> a.getString(i).trim() }.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }
}

fun JSONObject.putStringListIfNonempty(key: String, list: List<String>?) {
    if (list.isNullOrEmpty()) return
    put(key, JSONArray().apply { list.forEach { put(it) } })
}
