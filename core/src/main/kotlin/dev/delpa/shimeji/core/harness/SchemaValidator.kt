package dev.delpa.shimeji.core.harness

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Minimal JSON Schema validator matching the documented supported subset.
 *
 * Supported keywords (everything advertised in tool schemas must validate here):
 *  - type: string | integer | number | boolean | array | object
 *  - properties + required (objects)
 *  - items (arrays), with the same object/primitive support recursively
 *  - minimum / maximum (numeric)
 *  - enum (exact JSON equality)
 *  - minLength / maxLength / pattern (strings)
 *
 * Keywords NOT implemented (and therefore never advertised): oneOf/anyOf/allOf,
 * patternProperties, additionalProperties (default allow), format, if/then/else,
 * const, not, $ref. The catalog JSON documents this subset.
 */
object SchemaValidator {

    sealed interface ValidationError {
        data class MissingProperty(val path: String, val property: String) : ValidationError
        data class TypeMismatch(val path: String, val expected: String, val actual: String) : ValidationError
        data class OutOfRange(val path: String, val message: String) : ValidationError
        data class NotInEnum(val path: String) : ValidationError
        data class BadString(val path: String, val message: String) : ValidationError
    }

    fun validate(value: JsonElement?, schema: JsonObject): List<ValidationError> {
        if (value == null || value is JsonNull) return emptyList()
        val errors = mutableListOf<ValidationError>()
        validateNode(value, schema, "\$", errors)
        return errors
    }

    /** Validate a full parameter object against a schema whose root type is object. */
    fun validateObjectParams(params: JsonObject, schema: JsonObject): List<ValidationError> {
        val rootSchema = schema
        val type = rootSchema[SchemaKeywords.TYPE]?.jsonPrimitive?.content
        if (type != null && type != "object") {
            return listOf(ValidationError.TypeMismatch("\$", "object", type))
        }
        val errors = mutableListOf<ValidationError>()
        validateObjectProperties(params, rootSchema, "\$", errors)
        return errors
    }

    private fun validateNode(value: JsonElement, schema: JsonObject, path: String, errors: MutableList<ValidationError>) {
        val type = schema[SchemaKeywords.TYPE]?.jsonPrimitive?.content
        if (type != null) {
            if (!typeMatches(value, type)) {
                errors += ValidationError.TypeMismatch(path, type, typeOf(value))
                return
            }
        }
        when {
            value is JsonObject -> validateObjectProperties(value, schema, path, errors)
            value is JsonArray -> validateArrayItems(value, schema, path, errors)
            value is JsonPrimitive -> validatePrimitive(value, schema, path, errors)
        }
    }

    private fun validateObjectProperties(
        obj: JsonObject,
        schema: JsonObject,
        path: String,
        errors: MutableList<ValidationError>,
    ) {
        val props = schema[SchemaKeywords.PROPERTIES]?.jsonObject ?: JsonObject(emptyMap())
        val required = schema[SchemaKeywords.REQUIRED]
            ?.jsonArray
            ?.mapNotNull { (it as? JsonPrimitive)?.content }
            ?: emptyList()

        for (req in required) {
            if (!obj.containsKey(req)) {
                errors += ValidationError.MissingProperty(path, req)
            }
        }

        for ((key, propSchema) in props) {
            val child = obj[key]
            if (child == null || child is JsonNull) continue
            val childSchema = propSchema.jsonObject
            validateNode(child, childSchema, "$path.$key", errors)
        }
    }

    private fun validateArrayItems(value: JsonArray, schema: JsonObject, path: String, errors: MutableList<ValidationError>) {
        val itemSchema = schema[SchemaKeywords.ITEMS]?.jsonObject ?: return
        value.forEachIndexed { i, item ->
            validateNode(item, itemSchema, "$path[$i]", errors)
        }
    }

    private fun validatePrimitive(value: JsonPrimitive, schema: JsonObject, path: String, errors: MutableList<ValidationError>) {
        val content = value.content
        schema[SchemaKeywords.MINIMUM]?.jsonPrimitive?.let { min ->
            val d = min.doubleOrNull ?: return@let
            val v = value.doubleOrNull
            if (v != null && v < d) errors += ValidationError.OutOfRange(path, "expected >= $d, got $v")
        }
        schema[SchemaKeywords.MAXIMUM]?.jsonPrimitive?.let { max ->
            val d = max.doubleOrNull ?: return@let
            val v = value.doubleOrNull
            if (v != null && v > d) errors += ValidationError.OutOfRange(path, "expected <= $d, got $v")
        }
        schema[SchemaKeywords.MIN_LENGTH]?.jsonPrimitive?.let {
            val min = it.intOrNull ?: return@let
            if (content.length < min) errors += ValidationError.BadString(path, "minLength $min")
        }
        schema[SchemaKeywords.MAX_LENGTH]?.jsonPrimitive?.let {
            val max = it.intOrNull ?: return@let
            if (content.length > max) errors += ValidationError.BadString(path, "maxLength $max")
        }
        schema[SchemaKeywords.PATTERN]?.jsonPrimitive?.let {
            val regex = runCatching { Regex(it.content) }.getOrNull() ?: return@let
            if (!regex.matches(content)) errors += ValidationError.BadString(path, "pattern ${it.content}")
        }
        schema[SchemaKeywords.ENUM]?.jsonArray?.let { allowed ->
            if (allowed.none { it == (value as JsonElement) }) {
                errors += ValidationError.NotInEnum(path)
            }
        }
    }

    private fun typeMatches(value: JsonElement, type: String): Boolean = when (type) {
        "object" -> value is JsonObject
        "array" -> value is JsonArray
        "string" -> value is JsonPrimitive && value.isString
        "integer" -> value is JsonPrimitive && value.intOrNull != null
        "number" -> value is JsonPrimitive && !value.isString && (value.intOrNull != null || value.doubleOrNull != null)
        "boolean" -> value is JsonPrimitive && value.booleanOrNull != null
        else -> false
    }

    private fun typeOf(value: JsonElement): String = when (value) {
        is JsonObject -> "object"
        is JsonArray -> "array"
        is JsonPrimitive -> when {
            value.booleanOrNull != null -> "boolean"
            value.longOrNull != null -> "integer"
            value.doubleOrNull != null -> "number"
            else -> "string"
        }
        is JsonNull -> "null"
    }
}