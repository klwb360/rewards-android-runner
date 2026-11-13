package com.aliothmoon.maafw.once

import kotlinx.serialization.json.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Transient bounded detail decoder. Results contain scalars only, never raw OCR. */
internal object OcrDetailDecoder {
    enum class Parse { OK, EMPTY, SIZE, UTF8, JSON, ROOT, ALL, ROW_LIMIT, ROW_SHAPE, DEPTH, DUPLICATE_KEY }
    data class Result(val parse: Parse, val bytes: Int, val quality: OcrRowsAssessment?,
                      val structure: FrameStructureEvidence.Result, val page: FramePageEvidence.Result) {
        fun transportAccepted() = parse == Parse.OK || parse == Parse.EMPTY
        fun summary() = "detail=$parse bytes=$bytes rows=${quality?.rows() ?: -1} nonempty=${quality?.nonempty() ?: -1}" +
            " lowConfidence=${quality?.lowConfidence() ?: -1} rowValidation=${quality?.reason() ?: "NOT_EVALUATED"}" +
            " counterRow=${if (structure.supported()) "SUPPORTED" else "UNSUPPORTED"}"
    }
    @JvmStatic fun decode(bytes: ByteArray, binding: FrameStructureEvidence.Binding, width: Int, height: Int): Result {
        return decode(bytes, binding, width, height, null)
    }
    /** Optional same-owner transient consumer; never exposes text through IPC or persistence. */
    @JvmStatic fun decode(bytes: ByteArray, binding: FrameStructureEvidence.Binding, width: Int, height: Int,
                         rowsConsumer: java.util.function.Consumer<List<FrameStructureEvidence.Line>>?): Result {
        fun reject(reason: Parse) = Result(reason, bytes.size.coerceAtMost(65537), null,
            FrameStructureEvidence.Result.rejected(), FramePageEvidence.Result.rejected())
        if (bytes.isEmpty() || bytes.size > 65536) return reject(Parse.SIZE)
        val text = try { StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }
            catch (_: java.nio.charset.CharacterCodingException) { return reject(Parse.UTF8) }
        // Bound recursion and reject duplicate (including escaped-equivalent)
        // object keys before Json's map can discard them. This is an explicit
        // stricter guard, not a claim about Android JSONObject duplicate semantics.
        val objects = ArrayList<MutableSet<String>?>()
        var quoted = false; var escaped = false; var stringStart = -1
        for (index in text.indices) {
            val ch=text[index]
            if (quoted) {
                if (escaped) escaped=false
                else if (ch == '\\') escaped=true
                else if (ch == '"') {
                    quoted=false
                    var next=index+1
                    while (next<text.length && text[next].isWhitespace()) next++
                    if (next<text.length && text[next]==':') {
                        val keys=objects.lastOrNull() ?: return reject(Parse.JSON)
                        val key=try { Json.parseToJsonElement(text.substring(stringStart,index+1)).jsonPrimitive.content }
                            catch (_: IllegalArgumentException) { return reject(Parse.JSON) }
                        if (!keys.add(key)) return reject(Parse.DUPLICATE_KEY)
                    }
                }
            } else if (ch == '"') {quoted=true;stringStart=index}
            else if (ch == '{' || ch == '[') {
                objects.add(if (ch=='{') HashSet() else null)
                if(objects.size>16)return reject(Parse.DEPTH)
            } else if (ch == '}' || ch == ']') {
                if(objects.isEmpty() || (ch=='}')!=(objects.last()!=null))return reject(Parse.JSON)
                objects.removeAt(objects.lastIndex)
            }
        }
        if(quoted || objects.isNotEmpty())return reject(Parse.JSON)
        val json = try { Json.parseToJsonElement(text) } catch (_: IllegalArgumentException) { return reject(Parse.JSON) }
        val root = json as? JsonObject ?: return reject(Parse.ROOT)
        val all = root["all"] as? JsonArray ?: return reject(Parse.ALL)
        if (all.size > 128) return reject(Parse.ROW_LIMIT)
        val lines = ArrayList<FrameStructureEvidence.Line>(all.size)
        for (value in all) {
            val row = value as? JsonObject ?: return reject(Parse.ROW_SHAPE)
            val label = row["text"] as? JsonPrimitive ?: return reject(Parse.ROW_SHAPE)
            val score = row["score"] as? JsonPrimitive ?: return reject(Parse.ROW_SHAPE)
            val box = row["box"] as? JsonArray ?: return reject(Parse.ROW_SHAPE)
            if (!label.isString || score.isString || box.size != 4) return reject(Parse.ROW_SHAPE)
            val confidence = score.doubleOrNull ?: return reject(Parse.ROW_SHAPE)
            val coordinates = box.map {
                val coordinate = it as? JsonPrimitive ?: return reject(Parse.ROW_SHAPE)
                if (coordinate.isString) return reject(Parse.ROW_SHAPE)
                val number = coordinate.intOrNull ?: return reject(Parse.ROW_SHAPE)
                if (number !in 0..4096) return reject(Parse.ROW_SHAPE)
                number
            }
            lines.add(FrameStructureEvidence.Line(label.content,coordinates[0],coordinates[1],coordinates[2],coordinates[3],confidence))
        }
        val quality = OcrRowsAssessment.assess(binding,binding,lines,width,height)
        // Keep malformed rows out of the bridge; low-confidence rows remain present and stop there.
        if (quality.shapeValid() || all.isEmpty()) rowsConsumer?.accept(java.util.List.copyOf(lines))
        return Result(if (all.isEmpty()) Parse.EMPTY else Parse.OK, bytes.size, quality,
            FrameStructureEvidence.parse(binding,binding,lines,width,height),
            FramePageEvidence.parse(binding,binding,lines,width,height))
    }
}
