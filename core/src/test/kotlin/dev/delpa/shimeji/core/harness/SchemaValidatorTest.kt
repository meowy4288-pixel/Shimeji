package dev.delpa.shimeji.core.harness

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SchemaValidatorTest {

    private val schema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("name") { put("type", "string") }
            putJsonObject("count") { put("type", "integer") }
        }
        put("required", buildJsonArray { add("name") })
    }

    @Test
    fun `required field present but null is malformed`() {
        val params = buildJsonObject { put("name", JsonNull) }
        val errors = SchemaValidator.validateObjectParams(params, schema)
        assertEquals(1, errors.size)
        assertTrue(errors.single() is SchemaValidator.ValidationError.MissingProperty)
    }

    @Test
    fun `missing required property is still reported`() {
        val params = buildJsonObject { put("count", 1) }
        val errors = SchemaValidator.validateObjectParams(params, schema)
        assertEquals(1, errors.size)
        assertTrue(errors.single() is SchemaValidator.ValidationError.MissingProperty)
    }

    @Test
    fun `top-level null with a typed schema is a type mismatch`() {
        val errors = SchemaValidator.validate(JsonNull, schema)
        assertEquals(1, errors.size)
        assertTrue(errors.single() is SchemaValidator.ValidationError.TypeMismatch)
    }

    @Test
    fun `top-level null without type schema passes`() {
        val untyped = buildJsonObject {}
        assertEquals(emptyList(), SchemaValidator.validate(null, untyped))
    }
}