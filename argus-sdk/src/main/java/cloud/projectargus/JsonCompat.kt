package cloud.projectargus

import org.json.JSONArray
import org.json.JSONObject

/**
 * Serialises a decoded Firestore value (a `Map`/`List` tree of scalars) back to
 * a JSON string using `org.json` (#215).
 *
 * Why: the real-time listener hands the resolver decoded document data as Kotlin
 * `Map`/`List`s, but the flag cache stores everything as a `String`, and the
 * structured getters re-parse object-valued flags with `org.json.JSONObject`.
 * Serialising via `org.json` here guarantees the cached string round-trips
 * through exactly the parser the OkHttp path already feeds, keeping both
 * channels byte-for-byte compatible downstream.
 */
internal object JsonCompat {

    fun stringify(value: Any?): String = toJsonValue(value).toString()

    private fun toJsonValue(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is Map<*, *> -> {
            val obj = JSONObject()
            for ((k, v) in value) {
                if (k != null) obj.put(k.toString(), toJsonValue(v))
            }
            obj
        }
        is List<*> -> {
            val arr = JSONArray()
            for (item in value) arr.put(toJsonValue(item))
            arr
        }
        else -> value
    }
}
