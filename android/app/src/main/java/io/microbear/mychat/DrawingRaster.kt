package io.microbear.mychat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.util.Base64
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

object DrawingRaster {
    fun pngFromBody(body: JsonObject): ByteArray? {
        body.get("imageData")?.takeIf { it.isJsonPrimitive }?.asString?.let { src ->
            val marker = ";base64,"
            val i = src.indexOf(marker)
            if (src.startsWith("data:image/") && i >= 0) {
                return try {
                    Base64.decode(src.substring(i + marker.length), Base64.DEFAULT)
                } catch (_: Exception) {
                    null
                }
            }
        }
        val strokes = body.getAsJsonArray("strokes") ?: return null
        val w = (body.get("w")?.asInt ?: 300).coerceIn(1, 1600)
        val h = (body.get("h")?.asInt ?: 300).coerceIn(1, 1600)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        for (el in strokes) {
            if (el.isJsonObject) paintStroke(canvas, el.asJsonObject)
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return out.toByteArray()
    }

    fun decodeBitmap(png: ByteArray): Bitmap? =
        BitmapFactory.decodeByteArray(png, 0, png.size)

    private fun paintStroke(canvas: Canvas, stroke: JsonObject) {
        val type = str(stroke, "t", "type", "pen")
        val pts = floats(stroke.get("p") ?: stroke.get("points"))
        val col = parseColor(str(stroke, "c", "color", "#0f172a"))
        val sz = num(stroke, "s", "size", 4f)

        if (type == "text") {
            val text = str(stroke, "tx", "text", "").take(80)
            if (text.isEmpty() || pts.size < 2) return
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = col
                textSize = max(12f, sz * 3f)
                isFakeBoldText = true
            }
            canvas.drawText(text, pts[0], pts[1] + paint.textSize, paint)
            return
        }
        if (pts.size < 2) return

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = if (type == "erase") max(sz * 2f, 8f) else sz
            color = col
            if (type == "erase") {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                color = Color.BLACK
            }
        }

        val shapes = setOf("line", "arrow", "rect", "circle", "oval")
        if (type in shapes) {
            if (pts.size < 4) return
            val x1 = pts[0]
            val y1 = pts[1]
            val x2 = pts[2]
            val y2 = pts[3]
            when (type) {
                "rect" -> canvas.drawRect(
                    min(x1, x2),
                    min(y1, y2),
                    max(x1, x2) + 0.01f,
                    max(y1, y2) + 0.01f,
                    paint,
                )
                "oval", "circle" -> {
                    var left = min(x1, x2)
                    var top = min(y1, y2)
                    var w = abs(x2 - x1).coerceAtLeast(1f)
                    var h = abs(y2 - y1).coerceAtLeast(1f)
                    if (type == "circle") {
                        val side = max(w, h)
                        if (x2 < x1) left = x1 - side
                        if (y2 < y1) top = y1 - side
                        w = side
                        h = side
                    }
                    canvas.drawOval(left, top, left + w, top + h, paint)
                }
                else -> {
                    canvas.drawLine(x1, y1, x2, y2, paint)
                    if (type == "arrow") {
                        val angle = atan2(y2 - y1, x2 - x1)
                        val head = max(10f, sz * 3f)
                        val fill = Paint(paint).apply { style = Paint.Style.FILL }
                        val path = android.graphics.Path()
                        path.moveTo(x2, y2)
                        path.lineTo(
                            x2 - head * cos(angle - Math.PI / 6).toFloat(),
                            y2 - head * sin(angle - Math.PI / 6).toFloat(),
                        )
                        path.lineTo(
                            x2 - head * cos(angle + Math.PI / 6).toFloat(),
                            y2 - head * sin(angle + Math.PI / 6).toFloat(),
                        )
                        path.close()
                        canvas.drawPath(path, fill)
                    }
                }
            }
            return
        }

        if (pts.size == 2) {
            paint.style = Paint.Style.FILL
            canvas.drawCircle(pts[0], pts[1], paint.strokeWidth / 2f, paint)
            return
        }
        val path = android.graphics.Path()
        path.moveTo(pts[0], pts[1])
        var i = 2
        while (i + 1 < pts.size) {
            path.lineTo(pts[i], pts[i + 1])
            i += 2
        }
        canvas.drawPath(path, paint)
    }

    private fun str(obj: JsonObject, a: String, b: String, fallback: String): String {
        val v = obj.get(a) ?: obj.get(b)
        return if (v != null && v.isJsonPrimitive) v.asString else fallback
    }

    private fun num(obj: JsonObject, a: String, b: String, fallback: Float): Float {
        val v = obj.get(a) ?: obj.get(b)
        return if (v != null && v.isJsonPrimitive) v.asFloat else fallback
    }

    private fun floats(el: com.google.gson.JsonElement?): FloatArray {
        if (el == null || !el.isJsonArray) return floatArrayOf()
        val arr: JsonArray = el.asJsonArray
        val out = FloatArray(arr.size())
        for (i in 0 until arr.size()) {
            out[i] = if (arr[i].isJsonPrimitive) arr[i].asFloat else 0f
        }
        return out
    }

    private fun parseColor(raw: String): Int = try {
        Color.parseColor(raw)
    } catch (_: Exception) {
        Color.parseColor("#0f172a")
    }
}
