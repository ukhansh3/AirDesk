package com.pleos.airdesk

import org.json.JSONArray
import org.json.JSONObject

/** Strict evaluator of every assertion keyword currently used by AirDesk schemas.
 * Unsupported assertions fail closed. This is not a general JSON Schema library. */
class Phase2Schema(private val schemas: List<JSONObject>) {
    fun accepts(value: JSONObject): Boolean = schemas.any { matches(it, value) }

    private fun matches(schema: JSONObject, value: Any?): Boolean {
        val supported = setOf("\$schema", "title", "description", "type", "const", "enum", "properties",
            "required", "additionalProperties", "oneOf", "minimum", "maximum", "minLength", "maxLength",
            "pattern", "items", "minItems", "maxItems", "uniqueItems")
        if (schema.keys().asSequence().any { it !in supported }) return false
        if (schema.has("const") && !same(schema.get("const"), value)) return false
        schema.optJSONArray("enum")?.let { choices ->
            if ((0 until choices.length()).none { same(choices.get(it), value) }) return false
        }
        val types = when (val type = schema.opt("type")) {
            null -> emptyList()
            is String -> listOf(type)
            is JSONArray -> (0 until type.length()).map { type.optString(it) }
            else -> return false
        }
        if (types.isNotEmpty() && types.none { type -> when (type) {
            "object" -> value is JSONObject
            "array" -> value is JSONArray
            "string" -> value is String
            "boolean" -> value is Boolean
            "null" -> value == null || value === JSONObject.NULL
            "number" -> value is Number && value.toDouble().isFinite()
            "integer" -> integralLong(value) != null
            else -> false
        } }) return false
        if (value is Number) {
            val number = value.toDouble()
            if (!number.isFinite()) return false
            if (schema.has("minimum") && number < schema.getDouble("minimum")) return false
            if (schema.has("maximum") && number > schema.getDouble("maximum")) return false
        }
        if (value is String) {
            if (!validUnicode(value)) return false
            val length = value.codePointCount(0, value.length)
            if (length < schema.optInt("minLength", 0) || length > schema.optInt("maxLength", Int.MAX_VALUE)) return false
            if (schema.has("pattern") && !Regex(schema.getString("pattern")).containsMatchIn(value)) return false
        }
        if (value is JSONObject) {
            val props = schema.optJSONObject("properties") ?: JSONObject()
            schema.optJSONArray("required")?.let { required ->
                if ((0 until required.length()).any { !value.has(required.getString(it)) }) return false
            }
            if (schema.opt("additionalProperties") == false && value.keys().asSequence().any { !props.has(it) }) return false
            for (key in props.keys()) if (value.has(key) && !matches(props.getJSONObject(key), value.get(key))) return false
        }
        if (value is JSONArray) {
            if (value.length() < schema.optInt("minItems", 0) || value.length() > schema.optInt("maxItems", Int.MAX_VALUE)) return false
            val values = (0 until value.length()).map { value.get(it) }
            if (schema.optBoolean("uniqueItems", false) && values.indices.any { i -> (0 until i).any { same(values[it], values[i]) } }) return false
            schema.optJSONObject("items")?.let { item -> if (values.any { !matches(item, it) }) return false }
        }
        schema.optJSONArray("oneOf")?.let { variants ->
            if ((0 until variants.length()).count { matches(variants.getJSONObject(it), value) } != 1) return false
        }
        return true
    }

    private fun same(a: Any?, b: Any?): Boolean = when {
        a is Number && b is Number -> a.toDouble() == b.toDouble()
        a is JSONObject && b is JSONObject -> a.keys().asSequence().toSet() == b.keys().asSequence().toSet() && a.keys().asSequence().all { same(a.get(it), b.get(it)) }
        a is JSONArray && b is JSONArray -> a.length() == b.length() && (0 until a.length()).all { same(a.get(it), b.get(it)) }
        else -> a == b
    }
}

internal fun validUnicode(text: String): Boolean {
    var i = 0
    while (i < text.length) {
        when {
            Character.isHighSurrogate(text[i]) -> {
                if (i + 1 >= text.length || !Character.isLowSurrogate(text[i + 1])) return false
                i += 2
            }
            Character.isLowSurrogate(text[i]) -> return false
            else -> i++
        }
    }
    return true
}

internal fun JSONObject.exactKeys(vararg names: String) = keys().asSequence().toSet() == names.toSet()
internal fun JSONObject.integer(name: String): Long? = integralLong(opt(name))
internal fun integralLong(value: Any?): Long? = when (value) {
    is Int -> value.toLong()
    is Long -> value
    is Number -> {
        val number = value.toDouble()
        if (!number.isFinite() || kotlin.math.abs(number) > 9007199254740991.0) null
        else runCatching { value.toString().toBigDecimal().toBigIntegerExact().longValueExact() }.getOrNull()
    }
    else -> null
}
