package com.pleos.airdesk

import org.json.JSONObject

/** Evaluates the keyword subset used by the two bundled diagnostic schemas.
 * Unsupported assertion keywords fail closed so schema changes require review. */
class DiagnosticSchema(private val schemas: List<JSONObject>) {
    fun accepts(value: JSONObject): Boolean = schemas.any { matches(it, value) }

    private fun matches(schema: JSONObject, value: Any?): Boolean {
        val supported = setOf("\u0024schema", "title", "description", "type", "const", "enum",
            "properties", "required", "additionalProperties", "oneOf", "minimum", "maximum",
            "minLength", "maxLength", "pattern")
        if (schema.keys().asSequence().any { it !in supported }) return false
        if (schema.has("const") && schema.get("const") != value) return false
        schema.optJSONArray("enum")?.let { values ->
            if ((0 until values.length()).none { values.get(it) == value }) return false
        }
        when (schema.optString("type")) {
            "object" -> if (value !is JSONObject) return false
            "string" -> if (value !is String) return false
            "integer" -> if (value !is Number || !value.toDouble().isFinite() || value.toDouble() % 1.0 != 0.0) return false
            "number" -> if (value !is Number || !value.toDouble().isFinite()) return false
            "" -> Unit
            else -> return false
        }
        if (value is Number) {
            if (!value.toDouble().isFinite()) return false
            if (schema.has("minimum") && value.toDouble() < schema.getDouble("minimum")) return false
            if (schema.has("maximum") && value.toDouble() > schema.getDouble("maximum")) return false
        }
        if (value is String) {
            if (value.length < schema.optInt("minLength", 0) || value.length > schema.optInt("maxLength", Int.MAX_VALUE)) return false
            if (schema.has("pattern") && !Regex(schema.getString("pattern")).containsMatchIn(value)) return false
        }
        if (value is JSONObject) {
            val properties = schema.optJSONObject("properties") ?: JSONObject()
            val required = schema.optJSONArray("required")
            if (required != null && (0 until required.length()).any { !value.has(required.getString(it)) }) return false
            if (schema.opt("additionalProperties") == false && value.keys().asSequence().any { !properties.has(it) }) return false
            for (key in properties.keys()) {
                if (value.has(key) && !matches(properties.getJSONObject(key), value.get(key))) return false
            }
        }
        schema.optJSONArray("oneOf")?.let { variants ->
            if ((0 until variants.length()).count { matches(variants.getJSONObject(it), value) } != 1) return false
        }
        return true
    }
}
