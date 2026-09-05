package io.microbear.mychat.data

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import java.lang.reflect.Type

fun boardGson(): Gson = GsonBuilder()
    .registerTypeAdapter(BoardStrokeDto::class.java, BoardStrokeDtoAdapter())
    .create()

/** Read `s` / `size` by key. Gson JavaBeans maps `getS()` to `S`, which drops the wire field. */
class BoardStrokeDtoAdapter : JsonDeserializer<BoardStrokeDto> {
    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext,
    ): BoardStrokeDto {
        if (!json.isJsonObject) return BoardStrokeDto()
        val o = json.asJsonObject
        val pts = mutableListOf<Float>()
        val p = o.get("p") ?: o.get("points")
        if (p != null && p.isJsonArray) {
            p.asJsonArray.forEach { el ->
                if (el.isJsonPrimitive) pts.add(runCatching { el.asFloat }.getOrDefault(0f))
            }
        }
        return BoardStrokeDto(
            t = str(o, "t", "type", "pen"),
            c = str(o, "c", "color", "#0f172a"),
            penSize = num(o, "s", "size", 4f),
            p = pts,
            tx = str(o, "tx", "text", "").ifBlank { null },
            sv = intVal(o, "sv", 0),
        )
    }

    private fun str(
        o: com.google.gson.JsonObject,
        a: String,
        b: String,
        fallback: String,
    ): String {
        val v = o.get(a) ?: o.get(b)
        return if (v != null && v.isJsonPrimitive) v.asString else fallback
    }

    private fun num(
        o: com.google.gson.JsonObject,
        a: String,
        b: String,
        fallback: Float,
    ): Float {
        val v = o.get(a) ?: o.get(b) ?: return fallback
        if (!v.isJsonPrimitive) return fallback
        val n = runCatching { v.asFloat }.getOrNull() ?: return fallback
        return if (n > 0f) n else fallback
    }

    private fun intVal(o: com.google.gson.JsonObject, key: String, fallback: Int): Int {
        val v = o.get(key) ?: return fallback
        if (!v.isJsonPrimitive) return fallback
        return runCatching { v.asInt }.getOrDefault(fallback)
    }
}
