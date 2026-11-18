/**
 * Single file, public domain Kotlin JSON parser that supports SIMD and is faster than DSLJSON.
 * Adheres to RFC 8259, but allows overriding certain behavior with zero runtime cost by modifying
 * const booleans: `private const val OPTION_...`.
 * Tested and benchmarked externally.
 * Simple usage example:
 * ```kotlin
 * data class User(val id: Long, val email: String)
 *
 * val reader = JsonReader.newReader {
 *     val id = long("id")
 *     val email = string("email")
 *     build {
 *         User(id = id(), email = email())
 *     }
 * }
 *
 * fun main() {
 *     println(reader.read("""{"id" : 123, "email" : "johndoe@email.com"}"""))
 * }
 * ```
 */
@file:Suppress("SimplifyBooleanWithConstants", "SpellCheckingInspection")

package com.varlanv.kojson

import jdk.incubator.vector.ByteVector
import jdk.incubator.vector.VectorMask
import jdk.incubator.vector.VectorOperators
import jdk.incubator.vector.VectorSpecies
import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle
import java.math.BigInteger
import java.nio.ByteOrder
import java.util.*

private const val OPTION_VALIDATE_SKIPPED_VALUES: Boolean = true
private const val OPTION_VALIDATE_UTF8: Boolean = true
private const val OPTION_REJECT_CONTROL_CHARS_IN_STRINGS: Boolean = true
private const val OPTION_REJECT_DUPLICATE_KEYS: Boolean = true
private const val OPTION_INTERNAL_CHECKS: Boolean = true
private const val OPTION_NUMBERS_ACCEPT_QUOTED: Boolean = false
private const val OPTION_INTS_ACCEPT_DECIMAL_FORM: Boolean = false
private const val OPTION_ACCEPT_NAN_INFINITY: Boolean = false
private const val OPTION_ACCEPT_TRAILING_COMMAS: Boolean = false
private const val OPTION_REJECT_UNKNOWN_KEYS: Boolean = false
private const val OPTION_USE_VECTOR_API: Boolean = true
private const val OPTION_STACKTRACE_IN_ERROR: Boolean = false

private const val MAX_NESTING_DEPTH: Int = 256
private const val JSON_INITIAL_FRAME_SLOTS: Int = 256
private const val JSON_INITIAL_ELEMENTS: Int = 64
private const val JSON_INITIAL_SCRATCH_BYTES: Int = 256
private const val JSON_GROWTH_FACTOR: Int = 2
private const val JSON_PRESENCE_WORD_SHIFT: Int = 5
private const val JSON_PRESENCE_BIT_MASK: Int = 31
private const val JSON_PRESENCE_BITS_PER_FIELD_SHIFT: Int = 1
private const val JSON_SEEN_AND_PRESENT: Long = 3L
private const val JSON_SEEN: Long = 1L
private const val JSON_PRESENT: Long = 2L
private const val JSON_HIGH_HALF_SHIFT: Int = 32
private const val JSON_LOW_HALF_MASK: Long = 0xFFFFFFFFL
private const val JSON_SPAN_ESCAPED_BIT: Long = 0x80000000L
private const val JSON_SPAN_NON_ASCII_BIT: Long = 0x40000000L
private const val JSON_SPAN_LENGTH_MASK: Long = 0x3FFFFFFFL
private const val JSON_SPAN_LENGTH_LIMIT: Int = 0x3FFFFFFF
private const val JSON_WIDE_ESCAPE: Int = Int.MIN_VALUE
private const val JSON_REPLACEMENT_CHAR: Char = '\uFFFD'
private const val JSON_INITIAL_CHARS: Int = 256
private const val JSON_NO_OFFSET: Int = -1
private const val JSON_SPAN_COUNT_MASK: Int = 0x7FFFFFFF
private const val JSON_ARRAY_READ_BIT: Long = 0x80000000L
private const val JSON_NO_FIELD: Int = -1
private const val JSON_NO_POSITION: Int = -1
private const val JSON_SLOW_KEY: Int = -1
private const val JSON_FIRST_PRINTABLE: Int = 0x20
private const val JSON_BOM_SIZE: Int = 3
private const val JSON_BOM_FIRST: Byte = 0xEF.toByte()
private const val JSON_BOM_SECOND: Byte = 0xBB.toByte()
private const val JSON_BOM_THIRD: Byte = 0xBF.toByte()
private const val JSON_QUOTE: Byte = '"'.code.toByte()
private const val JSON_BACKSLASH: Byte = '\\'.code.toByte()
private const val JSON_OBJECT_OPEN: Byte = '{'.code.toByte()
private const val JSON_ARRAY_OPEN: Byte = '['.code.toByte()
private const val JSON_TRUE_START: Byte = 't'.code.toByte()
private const val JSON_FALSE_START: Byte = 'f'.code.toByte()
private const val JSON_NULL_START: Byte = 'n'.code.toByte()
private const val JSON_VECTOR_MODULE: String = "jdk.incubator.vector"
private const val JSON_COMMA: Byte = ','.code.toByte()
private const val JSON_COLON: Byte = ':'.code.toByte()
private const val JSON_ARRAY_CLOSE: Byte = ']'.code.toByte()
private const val JSON_OBJECT_CLOSE: Byte = '}'.code.toByte()
private const val JSON_MINUS: Byte = '-'.code.toByte()
private const val JSON_DOT: Byte = '.'.code.toByte()
private const val JSON_PLUS: Byte = '+'.code.toByte()
private const val JSON_EXPONENT_LOWER: Byte = 'e'.code.toByte()
private const val JSON_EXPONENT_UPPER: Byte = 'E'.code.toByte()
private const val JSON_SLASH: Byte = '/'.code.toByte()
private const val JSON_NAN_START: Byte = 'N'.code.toByte()
private const val JSON_INFINITY_START: Byte = 'I'.code.toByte()
private const val JSON_LETTER_B: Byte = 'b'.code.toByte()
private const val JSON_LETTER_F: Byte = 'f'.code.toByte()
private const val JSON_LETTER_N: Byte = 'n'.code.toByte()
private const val JSON_LETTER_R: Byte = 'r'.code.toByte()
private const val JSON_LETTER_T: Byte = 't'.code.toByte()
private const val JSON_LETTER_U: Byte = 'u'.code.toByte()
private const val JSON_BACKSPACE: Byte = 8
private const val JSON_TAB: Byte = 9
private const val JSON_LINE_FEED: Byte = 10
private const val JSON_FORM_FEED: Byte = 12
private const val JSON_CARRIAGE_RETURN: Byte = 13
private const val JSON_FIRST_PRINTABLE_BYTE: Byte = 0x20
private const val JSON_CASE_BIT: Byte = 0x20
private const val JSON_BRACKET_OPEN_FOLDED: Byte = 0x7B
private const val JSON_BRACKET_CLOSE_FOLDED: Byte = 0x7D
private const val JSON_BYTE_MASK: Int = 0xFF
private const val JSON_BYTE_MASK_LONG: Long = 0xFFL
private const val JSON_SPACE_CODE: Int = 0x20
private const val JSON_WHITESPACE_BITS: Long = (1L shl 9) or (1L shl 10) or (1L shl 13) or (1L shl 32)
private const val JSON_ZERO: Int = '0'.code
private const val JSON_ZERO_BYTE: Byte = '0'.code.toByte()
private const val JSON_MAX_DIGIT: Int = 9
private const val JSON_DECIMAL_BASE: Int = 10
private const val JSON_MANTISSA_LIMIT: Long = 1_000_000_000_000_000_000L
private const val JSON_EXPONENT_ACCUMULATE_LIMIT: Int = 100_000_000
private const val JSON_EXPONENT_CLAMP: Long = 1_000_000_000L
private const val JSON_EXACT_MANTISSA_MAX: Long = 1L shl 53
private const val JSON_MAX_EXACT_POWER: Int = 22
private const val JSON_FLOAT_EXACT_MANTISSA_MAX: Long = 1L shl 24
private const val JSON_FLOAT_MAX_EXACT_POWER: Int = 10
private const val JSON_FLOAT_MAX_EXPONENT: Int = 38
private const val JSON_FLOAT_MIN_EXPONENT: Int = -64
private const val JSON_NO_BITS: Long = -1L
private const val JSON_POWER_MIN_EXPONENT: Int = -342
private const val JSON_POWER_MAX_EXPONENT: Int = 308
private const val JSON_POWER_COUNT: Int = JSON_POWER_MAX_EXPONENT - JSON_POWER_MIN_EXPONENT + 1
private const val JSON_POWER_BASE: Long = 5L
private const val JSON_POWER_BITS: Int = 128
private const val JSON_POWER_SMALL_LIMIT: Int = -27
private const val JSON_LOG2_SCALE: Int = 217706
private const val JSON_LOG2_SHIFT: Int = 16
private const val JSON_WORD_BITS: Int = 64
private const val JSON_DOUBLE_BIAS: Int = 1023
private const val JSON_DOUBLE_ROUND_MASK: Long = 0x1FFL
private const val JSON_DOUBLE_SHIFT: Int = 9
private const val JSON_DOUBLE_MANTISSA_BITS: Int = 53
private const val JSON_DOUBLE_FRACTION_MASK: Long = 0xFFFFFFFFFFFFFL
private const val JSON_DOUBLE_MAX_BIASED: Int = 0x7FF
private const val JSON_FLOAT_BIAS: Int = 127
private const val JSON_FLOAT_ROUND_MASK: Long = 0x3FFFFFFFFFL
private const val JSON_FLOAT_SHIFT: Int = 38
private const val JSON_FLOAT_MANTISSA_BITS: Int = 24
private const val JSON_FLOAT_FRACTION_MASK: Long = 0x7FFFFFL
private const val JSON_FLOAT_MAX_BIASED: Int = 0xFF
private const val JSON_LONG_POWER_COUNT: Int = 19
private const val JSON_LONG_DIGITS: Int = 19
private const val JSON_PACKED_DIGITS: Int = 8
private const val JSON_DECIMAL_FORM_MAX_LENGTH: Int = 128
private const val JSON_DECIMAL_FORM_MAX_DIGITS: Int = 19
private const val JSON_LONG_MAGNITUDE_BITS: Int = 63
private const val JSON_STACK_WORD_SHIFT: Int = 6
private const val JSON_STACK_BIT_MASK: Int = 63
private const val JSON_SKIP_STACK_WORDS: Int = (MAX_NESTING_DEPTH + JSON_STACK_BIT_MASK) ushr JSON_STACK_WORD_SHIFT
private const val JSON_CONTAINER_UNTERMINATED: Int = -1
private const val JSON_CONTAINER_TOO_DEEP_BASE: Int = -2
private const val JSON_KEY_TABLE_LOAD: Int = 2
private const val JSON_HASH_MULTIPLIER: Long = -0x61c8864680b583ebL
private const val JSON_HASH_SHIFT: Int = 32
private const val JSON_HASH_LENGTH_SHIFT: Int = 56
private const val JSON_HASH_SECOND_MULTIPLIER: Long = -0x3b314601e57a13adL
private const val JSON_KEY_WORD_BYTES_TWICE: Int = 16
private const val JSON_BITS_PER_BYTE: Int = 8
private const val JSON_UNICODE_ESCAPE_LENGTH: Int = 6
private const val JSON_SURROGATE_PAIR_LENGTH: Int = 12
private const val JSON_HEX_DIGITS: Int = 4
private const val JSON_ESCAPE_PREFIX_LENGTH: Int = 2
private const val JSON_TWO_BYTES: Int = 2
private const val JSON_THREE_BYTES: Int = 3
private const val JSON_FOUR_BYTES: Int = 4
private const val JSON_HEX_BITS: Int = 4
private const val JSON_HEX_LETTER_OFFSET: Int = 10
private const val JSON_HEX_LETTER_COUNT: Int = 6
private const val JSON_HIGH_SURROGATE_MIN: Int = 0xD800
private const val JSON_HIGH_SURROGATE_MAX: Int = 0xDBFF
private const val JSON_LOW_SURROGATE_MIN: Int = 0xDC00
private const val JSON_LOW_SURROGATE_MAX: Int = 0xDFFF
private const val JSON_SUPPLEMENTARY_MIN: Int = 0x10000
private const val JSON_SURROGATE_SHIFT: Int = 10
private const val JSON_ASCII_MAX: Int = 0x7F
private const val JSON_THREE_BYTE_MIN: Int = 0x800
private const val JSON_CONTINUATION_TAG: Int = 0x80
private const val JSON_CONTINUATION_PAYLOAD: Int = 0x3F
private const val JSON_BACKSLASH_CODE: Int = '\\'.code
private const val JSON_TWO_BYTE_PAYLOAD: Int = 0x1F
private const val JSON_THREE_BYTE_PAYLOAD: Int = 0x0F
private const val JSON_FOUR_BYTE_PAYLOAD: Int = 0x07
private const val JSON_SURROGATE_LOW_MASK: Int = 0x3FF
private const val JSON_CONTINUATION_BITS: Int = 6
private const val JSON_TWO_BYTE_TAG: Int = 0xC0
private const val JSON_THREE_BYTE_TAG: Int = 0xE0
private const val JSON_FOUR_BYTE_TAG: Int = 0xF0
private const val JSON_UTF8_MIN_LEAD: Int = 0xC2
private const val JSON_UTF8_MAX_LEAD: Int = 0xF4
private const val JSON_UTF8_THREE_BYTE_LEAD: Int = 0xE0
private const val JSON_UTF8_FOUR_BYTE_LEAD: Int = 0xF0
private const val JSON_UTF8_SURROGATE_LEAD: Int = 0xED
private const val JSON_UTF8_OVERLONG_THREE_MIN: Int = 0xA0
private const val JSON_UTF8_SURROGATE_SECOND_MAX: Int = 0x9F
private const val JSON_UTF8_OVERLONG_FOUR_MIN: Int = 0x90
private const val JSON_UTF8_MAX_FOUR_LEAD: Int = 0xF4
private const val JSON_UTF8_MAX_FOUR_SECOND: Int = 0x8F
private const val JSON_CONTINUATION_MIN: Int = 0x80
private const val JSON_CONTINUATION_MAX: Int = 0xBF
private const val JSON_SWAR_LOW_BITS: Long = 0x7f7f7f7f7f7f7f7fL
private const val JSON_SWAR_HIGH_BITS: Long = -0x7f7f7f7f7f7f7f80L
private const val JSON_SWAR_CONTROL_ADD: Long = 0x6060606060606060L
private const val JSON_SWAR_QUOTES: Long = 0x2222222222222222L
private const val JSON_SWAR_BACKSLASHES: Long = 0x5c5c5c5c5c5c5c5cL
private const val JSON_SWAR_COMMAS: Long = 0x2c2c2c2c2c2c2c2cL
private const val JSON_SWAR_CASE_BITS: Long = 0x2020202020202020L
private const val JSON_SWAR_BRACKET_OPENS: Long = 0x7b7b7b7b7b7b7b7bL
private const val JSON_SWAR_BRACKET_CLOSES: Long = 0x7d7d7d7d7d7d7d7dL
private const val JSON_SWAR_MASK_MULTIPLIER: Long = 0x0102040810204080L
private const val JSON_SWAR_MASK_SHIFT: Int = 56
private const val JSON_SWAR_HIGH_BIT_SHIFT: Int = 7
private const val JSON_SWAR_BYTE_SHIFT: Int = 3
private const val JSON_DIGIT_CHECK_ADD: Long = 0x4646464646464646L
private const val JSON_DIGIT_ASCII_ZEROS: Long = 0x3030303030303030L
private const val JSON_PAIR_MASK: Long = 0x00ff00ff00ff00ffL
private const val JSON_QUAD_MASK: Long = 0x0000ffff0000ffffL
private const val JSON_PAIR_SCALE: Long = 10L
private const val JSON_QUAD_SCALE: Long = 100L
private const val JSON_OCTET_SCALE: Long = 10_000L
private const val JSON_PAIR_SHIFT: Int = 8
private const val JSON_QUAD_SHIFT: Int = 16
private const val JSON_OCTET_SHIFT: Int = 32
private const val JSON_SELF_TEST_ROUNDS: Int = 96
private const val JSON_SELF_TEST_SIZE_LANES: Int = 3
private const val JSON_SELF_TEST_SIZE_EXTRA: Int = 5
private const val JSON_SELF_TEST_MULTIPLIER: Int = 1103515245
private const val JSON_SELF_TEST_INCREMENT: Int = 12345
private const val JSON_SELF_TEST_SHIFT: Int = 16
private const val JSON_MIN_VECTOR_LANES: Int = 8
private const val JSON_VECTOR_PROBE_BYTES: Int = 16
private const val JSON_MAX_VECTOR_LANES: Int = 64
private const val JSON_SELF_TEST_ALPHABET: String = "{}[],:\"\\ab01 e.-\u0001Ã©"

@PublishedApi
internal const val JSON_KIND_INT: Int = 0

@PublishedApi
internal const val JSON_KIND_LONG: Int = 1

@PublishedApi
internal const val JSON_KIND_FLOAT: Int = 2

@PublishedApi
internal const val JSON_KIND_DOUBLE: Int = 3

@PublishedApi
internal const val JSON_KIND_BOOL: Int = 4

@PublishedApi
internal const val JSON_KIND_STRING: Int = 5

@PublishedApi
internal const val JSON_KIND_ENUM: Int = 6

@PublishedApi
internal const val JSON_KIND_NUMBERS: Int = 7

@PublishedApi
internal const val JSON_KIND_READER: Int = 8

@PublishedApi
internal const val JSON_KIND_LIST: Int = 9

@PublishedApi
internal const val JSON_KIND_VALUE: Int = 10

@PublishedApi
internal const val JSON_KIND_ENTRIES: Int = 11

@PublishedApi
internal const val JSON_KIND_REST: Int = 12

private const val JSON_VALUE_STRING: Int = 0
private const val JSON_VALUE_NUMBER: Int = 1
private const val JSON_VALUE_BOOL: Int = 2
private const val JSON_VALUE_NULL: Int = 3
private const val JSON_VALUE_OBJECT: Int = 4
private const val JSON_VALUE_ARRAY: Int = 5

private val jsonEmptyBytes: ByteArray = ByteArray(0)
private val jsonEmptyInts: IntArray = IntArray(0)
private val jsonEmptyLongs: LongArray = LongArray(0)
private val jsonEmptyFloats: FloatArray = FloatArray(0)
private val jsonEmptyDoubles: DoubleArray = DoubleArray(0)
private val jsonNoLiterals: Array<ByteArray> = arrayOf()
private val jsonNoEnums: Array<Array<ByteArray>> = arrayOf()
private val jsonNoKeys: Array<ByteArray> = arrayOf()
private val jsonNoReaders: Array<JsonReader<*>> = arrayOf()
private val jsonLongView: VarHandle = MethodHandles.byteArrayViewVarHandle(
    LongArray::class.java,
    ByteOrder.LITTLE_ENDIAN,
)
private val jsonTrueTail: ByteArray = "rue".encodeToByteArray()
private val jsonFalseTail: ByteArray = "alse".encodeToByteArray()
private val jsonNullTail: ByteArray = "ull".encodeToByteArray()
private val jsonNanTail: ByteArray = "aN".encodeToByteArray()
private val jsonInfinityTail: ByteArray = "nfinity".encodeToByteArray()
private val jsonPowersOfTen: DoubleArray = doubleArrayOf(
    1e0, 1e1, 1e2, 1e3, 1e4, 1e5, 1e6, 1e7, 1e8, 1e9, 1e10, 1e11,
    1e12, 1e13, 1e14, 1e15, 1e16, 1e17, 1e18, 1e19, 1e20, 1e21, 1e22,
)
private val jsonFloatPowersOfTen: FloatArray = floatArrayOf(
    1e0f, 1e1f, 1e2f, 1e3f, 1e4f, 1e5f, 1e6f, 1e7f, 1e8f, 1e9f, 1e10f,
)
private val jsonLongPowersOfTen: LongArray = LongArray(JSON_LONG_POWER_COUNT).also { table ->
    var power = 1L
    for (index in table.indices) {
        table[index] = power
        power *= JSON_DECIMAL_BASE
    }
}
private val jsonMantissaRoom: LongArray = LongArray(JSON_PACKED_DIGITS + 1).also { table ->
    for (count in 1 until table.size) {
        table[count] = jsonLongPowersOfTen[JSON_LONG_DIGITS - count]
    }
}
private val jsonHexValues: ByteArray = ByteArray(JSON_BYTE_MASK + 1).also { table ->
    table.fill(-1)
    for (digit in 0..JSON_MAX_DIGIT) {
        table[JSON_ZERO + digit] = digit.toByte()
    }
    for (letter in 0 until JSON_HEX_LETTER_COUNT) {
        table['a'.code + letter] = (JSON_HEX_LETTER_OFFSET + letter).toByte()
        table['A'.code + letter] = (JSON_HEX_LETTER_OFFSET + letter).toByte()
    }
}

/**
 * A built reader. It is immutable and thread-safe. each with its own
 * [JsonReadContext].
 */
abstract class JsonReader<T> @PublishedApi internal constructor() {
    companion object {
        /**
         * Builds a reader for a JSON object.
         */
        inline fun <T> newReader(
            block: JsonReaderBuilder<T>.() -> JsonReader<T>,
        ): JsonReader<T> = JsonReaderBuilder<T>(JsonSchema(false)).block()

        /**
         * Builds a reader for a JSON array whose positions have fixed meaning.
         * For example, for saving bandwidth, provider may choose to encode
         * `class User(val id: Long, val email: String)` as
         * `{"users": [[1, "johdoe1@email.com"], [2, "johndoe2@email.com"]]}`
         */
        inline fun <T> newTupleReader(
            block: JsonTupleBuilder<T>.() -> JsonReader<T>,
        ): JsonReader<T> = JsonTupleBuilder<T>(JsonSchema(true)).block()
    }

    /**
     * Reads one value of this reader's shape from `bytes[offset until offset + length]`.
     * Only whitespace may surround the value.
     *
     * @throws JsonException when the input is not valid for this reader.
     */
    fun read(
        context: JsonReadContext,
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): T {
        jsonBeginRead(context = context, bytes = bytes, offset = offset, length = length)
        try {
            jsonSkipLeading(context)
            val result = readValue(context)
            jsonSkipTrailing(context)
            return result
        } finally {
            jsonReleaseRead(context)
        }
    }

    /**
     * Reads one value of this reader's shape from `String`.
     * @throws JsonException when the input is not valid for this reader.
     */
    fun read(
        string: String,
        context: JsonReadContext = JsonReadContext(),
    ): T {
        val bytes = string.encodeToByteArray()
        jsonBeginRead(context = context, bytes = bytes, offset = 0, length = bytes.size)
        try {
            jsonSkipLeading(context)
            val result = readValue(context)
            jsonSkipTrailing(context)
            return result
        } finally {
            jsonReleaseRead(context)
        }
    }

    /**
     * Reads a JSON array whose elements have this reader's shape from `bytes[offset until offset + length]`.
     *
     * @throws JsonException when the input is not valid for this reader.
     */
    fun readList(
        context: JsonReadContext,
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): List<T> {
        jsonBeginRead(context = context, bytes = bytes, offset = offset, length = length)
        try {
            jsonSkipLeading(context)
            val result = readElements(context)
            jsonSkipTrailing(context)
            return result
        } finally {
            jsonReleaseRead(context)
        }
    }

    @PublishedApi
    internal abstract fun readValue(context: JsonReadContext): T

    @PublishedApi
    internal abstract fun readElements(context: JsonReadContext): List<T>
}

/**
 * All buffers that a read needs. Created and owned by the caller, reused between reads.
 * Serves one read at a time and is not thread safe.
 */
class JsonReadContext {
    @PublishedApi
    @JvmField
    internal var bytes: ByteArray = jsonEmptyBytes

    @PublishedApi
    @JvmField
    internal var cursor: Int = 0

    @PublishedApi
    @JvmField
    internal var limit: Int = 0

    @PublishedApi
    @JvmField
    internal var slots: LongArray = LongArray(JSON_INITIAL_FRAME_SLOTS)

    @PublishedApi
    @JvmField
    internal var refs: Array<Any?> = arrayOfNulls(JSON_INITIAL_FRAME_SLOTS)

    @PublishedApi
    @JvmField
    internal var base: Int = 0

    @PublishedApi
    @JvmField
    internal var top: Int = 0

    @PublishedApi
    @JvmField
    internal var refsHighWater: Int = 0

    @PublishedApi
    @JvmField
    internal var elements: Array<Any?> = arrayOfNulls(JSON_INITIAL_ELEMENTS)

    @PublishedApi
    @JvmField
    internal var elementsTop: Int = 0

    @PublishedApi
    @JvmField
    internal var elementsHighWater: Int = 0

    @PublishedApi
    @JvmField
    internal var scratch: ByteArray = ByteArray(JSON_INITIAL_SCRATCH_BYTES)

    @PublishedApi
    @JvmField
    internal var scratchTop: Int = 0

    @PublishedApi
    @JvmField
    internal var borrowBytes: ByteArray = jsonEmptyBytes

    @PublishedApi
    @JvmField
    internal var borrowOffset: Int = 0

    @PublishedApi
    @JvmField
    internal var lastKeyOffset: Int = 0

    @PublishedApi
    @JvmField
    internal var depth: Int = 0

    @PublishedApi
    @JvmField
    internal var busy: Boolean = false

    @JvmField
    internal var skipStack: LongArray = LongArray(JSON_SKIP_STACK_WORDS)

    @JvmField
    internal var stringChars: CharArray = CharArray(JSON_INITIAL_CHARS)

    @JvmField
    internal var lastStringOffset: Int = JSON_NO_OFFSET

    @JvmField
    internal var lastStringEnd: Int = 0
}

/**
 * Reasons for a [JsonException].
 */
enum class JsonErrorType {
    /** The input ended inside a value. */
    UNEXPECTED_END,

    /** A byte that the JSON grammar does not allow at this place. */
    UNEXPECTED_BYTE,

    /** A literal that is not `true`, `false` or `null`. */
    INVALID_LITERAL,

    /** A number that does not follow the JSON number grammar. */
    INVALID_NUMBER,

    /** A number that does not fit the declared type. */
    NUMBER_OUT_OF_RANGE,

    /** A number with a fraction or exponent where an integer was declared. */
    NOT_AN_INTEGER,

    /** A broken escape sequence inside a string. */
    INVALID_ESCAPE,

    /** A raw byte below 0x20 inside a string. */
    CONTROL_CHAR_IN_STRING,

    /** A string that is not well-formed UTF-8. */
    INVALID_UTF8,

    /** A value of another JSON type than the declared one. */
    TYPE_MISMATCH,

    /** A field was read with `invoke()` but is absent. */
    MISSING_FIELD,

    /** A declared key occurs twice in one object. */
    DUPLICATE_KEY,

    /** A key that is not declared, with `REJECT_UNKNOWN_KEYS` on. */
    UNKNOWN_KEY,

    /** A string that equals none of the literals of an `enum` declaration. */
    UNKNOWN_ENUM_LITERAL,

    /** Arrays and objects are nested deeper than `MAX_NESTING_DEPTH`. */
    NESTING_TOO_DEEP,

    /** Something other than whitespace follows the root value. */
    TRAILING_DATA,
}

/**
 * Thrown for input that is not valid. [offset] is an index into the array that was passed to the read.
 */
class JsonException @PublishedApi internal constructor(
    val error: JsonErrorType,
    val offset: Int,
) : RuntimeException(
    error.name + " at byte offset " + offset,
    null,
    OPTION_STACKTRACE_IN_ERROR,
    OPTION_STACKTRACE_IN_ERROR
)

class JsonIllegalStateException @PublishedApi internal constructor(
    message: String,
) : RuntimeException(message, null, OPTION_STACKTRACE_IN_ERROR, OPTION_STACKTRACE_IN_ERROR)

/**
 * Declares the fields of a JSON object by name. Every function returns an accessor that is read inside [build].
 */
class JsonReaderBuilder<T> @PublishedApi internal constructor(
    @PublishedApi
    @JvmField
    internal val schema: JsonSchema,
) {
    /** Declares an integer field that fits `Int`. */
    fun int(field: String): JsonIntAccessor = JsonIntAccessor(
        owner = schema,
        index = named(
            field = field,
            kind = JSON_KIND_INT,
        ),
    )

    /** Declares an integer field that fits `Long`. */
    fun long(field: String): JsonLongAccessor = JsonLongAccessor(
        owner = schema,
        index = named(
            field = field,
            kind = JSON_KIND_LONG,
        ),
    )

    /** Declares a number field read as `Float`. */
    fun float(field: String): JsonFloatAccessor = JsonFloatAccessor(
        owner = schema,
        index = named(
            field = field,
            kind = JSON_KIND_FLOAT,
        ),
    )

    /** Declares a number field read as `Double`. */
    fun double(field: String): JsonDoubleAccessor = JsonDoubleAccessor(
        owner = schema,
        index = named(
            field = field,
            kind = JSON_KIND_DOUBLE,
        ),
    )

    /** Declares a `true` / `false` field. */
    fun bool(field: String): JsonBoolAccessor = JsonBoolAccessor(
        owner = schema,
        index = named(
            field = field,
            kind = JSON_KIND_BOOL,
        ),
    )

    /** Declares a string field. */
    fun string(field: String): JsonStringAccessor = JsonStringAccessor(
        owner = schema,
        index = named(
            field = field,
            kind = JSON_KIND_STRING,
        ),
    )

    /**
     * Declares a string field whose value must equal one of [literals]. The accessor returns the index of the
     * equal literal. Any other value fails the read.
     */
    fun enum(field: String, vararg literals: String): JsonIntAccessor {
        val index = named(field = field, kind = JSON_KIND_ENUM)
        schema.setEnum(index = index, literals = literals)
        return JsonIntAccessor(owner = schema, index = index)
    }

    /** Declares a flat array of integers read as `IntArray`. */
    fun ints(field: String): JsonIntsAccessor = JsonIntsAccessor(
        owner = schema,
        index = named(
            field = field,
            kind = JSON_KIND_NUMBERS,
        ),
    )

    /** Declares a flat array of integers read as `LongArray`. */
    fun longs(field: String): JsonLongsAccessor = JsonLongsAccessor(
        owner = schema,
        index = named(
            field = field,
            kind = JSON_KIND_NUMBERS,
        ),
    )

    /** Declares a flat array of numbers read as `FloatArray`. */
    fun floats(field: String): JsonFloatsAccessor = JsonFloatsAccessor(
        owner = schema,
        index = named(
            field = field,
            kind = JSON_KIND_NUMBERS,
        ),
    )

    /** Declares a flat array of numbers read as `DoubleArray`. */
    fun doubles(field: String): JsonDoublesAccessor = JsonDoublesAccessor(
        owner = schema,
        index = named(
            field = field,
            kind = JSON_KIND_NUMBERS,
        ),
    )

    /** Declares one nested value read by [reader]. The reader may be an object reader or a tuple reader. */
    fun <R> obj(field: String, reader: JsonReader<R>): JsonObjectAccessor<R> {
        val index = named(field = field, kind = JSON_KIND_READER)
        schema.setReader(index = index, reader = reader)
        return JsonObjectAccessor(owner = schema, index = index)
    }

    /** Declares one nested object whose reader is declared in place by [block]. */
    inline fun <R> obj(
        field: String,
        block: JsonReaderBuilder<R>.() -> JsonReader<R>,
    ): JsonObjectAccessor<R> = obj(field = field, reader = JsonReaderBuilder<R>(JsonSchema(false)).block())

    /** Declares one nested tuple whose reader is declared in place by [block]. */
    inline fun <R> tuple(
        field: String,
        block: JsonTupleBuilder<R>.() -> JsonReader<R>,
    ): JsonObjectAccessor<R> = obj(field = field, reader = JsonTupleBuilder<R>(JsonSchema(true)).block())

    /** Declares an array whose elements are read by [reader]. */
    fun <R> list(field: String, reader: JsonReader<R>): JsonListAccessor<R> {
        val index = named(field = field, kind = JSON_KIND_LIST)
        schema.setReader(index = index, reader = reader)
        return JsonListAccessor(owner = schema, index = index)
    }

    /** Declares an array of objects whose reader is declared in place by [block]. */
    inline fun <R> list(
        field: String,
        block: JsonReaderBuilder<R>.() -> JsonReader<R>,
    ): JsonListAccessor<R> = list(field = field, reader = JsonReaderBuilder<R>(JsonSchema(false)).block())

    /** Declares a field of any JSON type that is inspected through [JsonValue]. */
    fun value(field: String): JsonValueAccessor = JsonValueAccessor(
        owner = schema,
        index = named(
            field = field,
            kind = JSON_KIND_VALUE,
        ),
    )

    /** Declares an object field whose keys are not known in advance. */
    fun entries(field: String): JsonEntriesAccessor = JsonEntriesAccessor(
        owner = schema,
        index = named(
            field = field,
            kind = JSON_KIND_ENTRIES,
        ),
        filter = null,
    )

    /** Declares access to the members of the object being built whose keys are not declared in this reader. */
    fun entries(): JsonEntriesAccessor = JsonEntriesAccessor(owner = schema, index = schema.addRest(), filter = schema)

    /** Like [entries], every member value must be an integer that fits `Int`. */
    fun intEntries(field: String): JsonIntEntriesAccessor = JsonIntEntriesAccessor(
        owner = schema,
        index = named(
            field = field,
            kind = JSON_KIND_ENTRIES,
        ),
        filter = null,
    )

    /** Like [entries] without a name, every member value must be an integer that fits `Int`. */
    fun intEntries(): JsonIntEntriesAccessor = JsonIntEntriesAccessor(
        owner = schema,
        index = schema.addRest(),
        filter = schema,
    )

    /** Like [entries], every member value must be an integer that fits `Long`. */
    fun longEntries(field: String): JsonLongEntriesAccessor = JsonLongEntriesAccessor(
        owner = schema,
        index = named(
            field = field,
            kind = JSON_KIND_ENTRIES,
        ),
        filter = null,
    )

    /** Like [entries] without a name, every member value must be an integer that fits `Long`. */
    fun longEntries(): JsonLongEntriesAccessor = JsonLongEntriesAccessor(
        owner = schema,
        index = schema.addRest(),
        filter = schema,
    )

    /** Like [entries], every member value must be a number, read as `Float`. */
    fun floatEntries(field: String): JsonFloatEntriesAccessor = JsonFloatEntriesAccessor(
        owner = schema,
        index = named(
            field = field,
            kind = JSON_KIND_ENTRIES,
        ),
        filter = null,
    )

    /** Like [entries] without a name, every member value must be a number, read as `Float`. */
    fun floatEntries(): JsonFloatEntriesAccessor = JsonFloatEntriesAccessor(
        owner = schema,
        index = schema.addRest(),
        filter = schema,
    )

    /** Like [entries], every member value must be a number, read as `Double`. */
    fun doubleEntries(field: String): JsonDoubleEntriesAccessor = JsonDoubleEntriesAccessor(
        owner = schema,
        index = named(
            field = field,
            kind = JSON_KIND_ENTRIES,
        ),
        filter = null,
    )

    /** Like [entries] without a name, every member value must be a number, read as `Double`. */
    fun doubleEntries(): JsonDoubleEntriesAccessor = JsonDoubleEntriesAccessor(
        owner = schema,
        index = schema.addRest(),
        filter = schema,
    )

    /** Like [entries], every member value must be `true` or `false`. */
    fun boolEntries(field: String): JsonBoolEntriesAccessor = JsonBoolEntriesAccessor(
        owner = schema,
        index = named(
            field = field,
            kind = JSON_KIND_ENTRIES,
        ),
        filter = null,
    )

    /** Like [entries] without a name, every member value must be `true` or `false`. */
    fun boolEntries(): JsonBoolEntriesAccessor = JsonBoolEntriesAccessor(
        owner = schema,
        index = schema.addRest(),
        filter = schema,
    )

    /**
     * Finishes the declaration. [block] runs once per parsed object, after all its members were read, and returns
     * the result for that object. Accessors of this builder may be read only inside [block].
     */
    inline fun build(crossinline block: context(JsonReadContext) () -> T): JsonReader<T> {
        val finished = schema.finish()
        return object : JsonReader<T>() {
            @PublishedApi
            override fun readValue(
                context: JsonReadContext,
            ): T = jsonReadObject(context = context, schema = finished) { block(context) }

            @PublishedApi
            override fun readElements(context: JsonReadContext): List<T> = jsonReadList(context) { readValue(context) }
        }
    }

    private fun named(
        field: String,
        kind: Int,
    ): Int = schema.add(name = field, position = JSON_NO_POSITION, kind = kind)
}

/**
 * Declares the fields of a JSON array by position, starting at zero. Every function returns an accessor that is
 * read inside [build].
 */
class JsonTupleBuilder<T> @PublishedApi internal constructor(
    @PublishedApi
    @JvmField
    internal val schema: JsonSchema,
) {
    /** Declares an integer that fits `Int` at [position]. */
    fun int(position: Int): JsonIntAccessor = JsonIntAccessor(
        owner = schema,
        index = positional(
            position = position,
            kind = JSON_KIND_INT,
        ),
    )

    /** Declares an integer that fits `Long` at [position]. */
    fun long(position: Int): JsonLongAccessor = JsonLongAccessor(
        owner = schema,
        index = positional(
            position = position,
            kind = JSON_KIND_LONG,
        ),
    )

    /** Declares a number read as `Float` at [position]. */
    fun float(position: Int): JsonFloatAccessor = JsonFloatAccessor(
        owner = schema,
        index = positional(
            position = position,
            kind = JSON_KIND_FLOAT,
        ),
    )

    /** Declares a number read as `Double` at [position]. */
    fun double(position: Int): JsonDoubleAccessor = JsonDoubleAccessor(
        owner = schema,
        index = positional(
            position = position,
            kind = JSON_KIND_DOUBLE,
        ),
    )

    /** Declares a `true` / `false` value at [position]. */
    fun bool(position: Int): JsonBoolAccessor = JsonBoolAccessor(
        owner = schema,
        index = positional(
            position = position,
            kind = JSON_KIND_BOOL,
        ),
    )

    /** Declares a string at [position]. */
    fun string(position: Int): JsonStringAccessor = JsonStringAccessor(
        owner = schema,
        index = positional(
            position = position,
            kind = JSON_KIND_STRING,
        ),
    )

    /**
     * Declares a string at [position] whose value must equal one of [literals]. The accessor returns the index of
     * the equal literal. Any other value fails the read.
     */
    fun enum(position: Int, vararg literals: String): JsonIntAccessor {
        val index = positional(position = position, kind = JSON_KIND_ENUM)
        schema.setEnum(index = index, literals = literals)
        return JsonIntAccessor(owner = schema, index = index)
    }

    /** Declares a flat array of integers read as `IntArray` at [position]. */
    fun ints(position: Int): JsonIntsAccessor = JsonIntsAccessor(
        owner = schema,
        index = positional(
            position = position,
            kind = JSON_KIND_NUMBERS,
        ),
    )

    /** Declares a flat array of integers read as `LongArray` at [position]. */
    fun longs(position: Int): JsonLongsAccessor = JsonLongsAccessor(
        owner = schema,
        index = positional(
            position = position,
            kind = JSON_KIND_NUMBERS,
        ),
    )

    /** Declares a flat array of numbers read as `FloatArray` at [position]. */
    fun floats(position: Int): JsonFloatsAccessor = JsonFloatsAccessor(
        owner = schema,
        index = positional(
            position = position,
            kind = JSON_KIND_NUMBERS,
        ),
    )

    /** Declares a flat array of numbers read as `DoubleArray` at [position]. */
    fun doubles(position: Int): JsonDoublesAccessor = JsonDoublesAccessor(
        owner = schema,
        index = positional(
            position = position,
            kind = JSON_KIND_NUMBERS,
        ),
    )

    /** Declares one nested value at [position] read by [reader]. */
    fun <R> obj(position: Int, reader: JsonReader<R>): JsonObjectAccessor<R> {
        val index = positional(position = position, kind = JSON_KIND_READER)
        schema.setReader(index = index, reader = reader)
        return JsonObjectAccessor(owner = schema, index = index)
    }

    /** Declares one nested object at [position] whose reader is declared in place by [block]. */
    inline fun <R> obj(
        position: Int,
        block: JsonReaderBuilder<R>.() -> JsonReader<R>,
    ): JsonObjectAccessor<R> = obj(position = position, reader = JsonReaderBuilder<R>(JsonSchema(false)).block())

    /** Declares one nested tuple at [position] whose reader is declared in place by [block]. */
    inline fun <R> tuple(
        position: Int,
        block: JsonTupleBuilder<R>.() -> JsonReader<R>,
    ): JsonObjectAccessor<R> = obj(position = position, reader = JsonTupleBuilder<R>(JsonSchema(true)).block())

    /** Declares an array at [position] whose elements are read by [reader]. */
    fun <R> list(position: Int, reader: JsonReader<R>): JsonListAccessor<R> {
        val index = positional(position = position, kind = JSON_KIND_LIST)
        schema.setReader(index = index, reader = reader)
        return JsonListAccessor(owner = schema, index = index)
    }

    /** Declares an array of objects at [position] whose reader is declared in place by [block]. */
    inline fun <R> list(
        position: Int,
        block: JsonReaderBuilder<R>.() -> JsonReader<R>,
    ): JsonListAccessor<R> = list(position = position, reader = JsonReaderBuilder<R>(JsonSchema(false)).block())

    /** Declares a value of any JSON type at [position] that is inspected through [JsonValue]. */
    fun value(position: Int): JsonValueAccessor = JsonValueAccessor(
        owner = schema,
        index = positional(
            position = position,
            kind = JSON_KIND_VALUE,
        ),
    )

    /** Declares an object at [position] whose keys are not known in advance. */
    fun entries(position: Int): JsonEntriesAccessor = JsonEntriesAccessor(
        owner = schema,
        index = positional(
            position = position,
            kind = JSON_KIND_ENTRIES,
        ),
        filter = null,
    )

    /** Like [entries], every member value must be an integer that fits `Int`. */
    fun intEntries(position: Int): JsonIntEntriesAccessor = JsonIntEntriesAccessor(
        owner = schema,
        index = positional(
            position = position,
            kind = JSON_KIND_ENTRIES,
        ),
        filter = null,
    )

    /** Like [entries], every member value must be an integer that fits `Long`. */
    fun longEntries(position: Int): JsonLongEntriesAccessor = JsonLongEntriesAccessor(
        owner = schema,
        index = positional(
            position = position,
            kind = JSON_KIND_ENTRIES,
        ),
        filter = null,
    )

    /** Like [entries], every member value must be a number, read as `Float`. */
    fun floatEntries(position: Int): JsonFloatEntriesAccessor = JsonFloatEntriesAccessor(
        owner = schema,
        index = positional(
            position = position,
            kind = JSON_KIND_ENTRIES,
        ),
        filter = null,
    )

    /** Like [entries], every member value must be a number, read as `Double`. */
    fun doubleEntries(position: Int): JsonDoubleEntriesAccessor = JsonDoubleEntriesAccessor(
        owner = schema,
        index = positional(
            position = position,
            kind = JSON_KIND_ENTRIES,
        ),
        filter = null,
    )

    /** Like [entries], every member value must be `true` or `false`. */
    fun boolEntries(position: Int): JsonBoolEntriesAccessor = JsonBoolEntriesAccessor(
        owner = schema,
        index = positional(
            position = position,
            kind = JSON_KIND_ENTRIES,
        ),
        filter = null,
    )

    /**
     * Finishes the declaration. [block] runs once per parsed tuple, after all its elements were read, and returns
     * the result for that tuple. Accessors of this builder may be read only inside [block].
     */
    inline fun build(crossinline block: context(JsonReadContext) () -> T): JsonReader<T> {
        val finished = schema.finish()
        return object : JsonReader<T>() {
            @PublishedApi
            override fun readValue(
                context: JsonReadContext,
            ): T = jsonReadTuple(context = context, schema = finished) { block(context) }

            @PublishedApi
            override fun readElements(context: JsonReadContext): List<T> = jsonReadList(context) { readValue(context) }
        }
    }

    private fun positional(position: Int, kind: Int): Int = schema.add(name = null, position = position, kind = kind)
}

/**
 * Reads an `Int` field. Also returned by `enum`, where the value is the index of the matched literal.
 */
class JsonIntAccessor internal constructor(
    @JvmField
    internal val owner: JsonSchema,
    @JvmField
    internal val index: Int,
) {
    /** Returns the value. Fails the read when the field is absent. */
    context(readContext: JsonReadContext)
    operator fun invoke(): Int = jsonSlot(context = readContext, owner = owner, index = index).toInt()

    /** Returns the value, or [default] when the field is absent. */
    context(readContext: JsonReadContext)
    fun orDefault(default: Int): Int = if (jsonPresent(context = readContext, owner = owner, index = index)) {
        readContext.slots[readContext.base + index].toInt()
    } else {
        default
    }

    /** Returns the value, or `null` when the field is absent. Allocates a box for most values. */
    context(readContext: JsonReadContext)
    fun nullableBoxed(): Int? = if (jsonPresent(context = readContext, owner = owner, index = index)) {
        readContext.slots[readContext.base + index].toInt()
    } else {
        null
    }

    /** Tells whether the field occurs with a value other than `null`. */
    context(readContext: JsonReadContext)
    fun isPresent(): Boolean = jsonPresent(context = readContext, owner = owner, index = index)
}

/**
 * Reads a `Long` field.
 */
class JsonLongAccessor internal constructor(
    @JvmField
    internal val owner: JsonSchema,
    @JvmField
    internal val index: Int,
) {
    /** Returns the value. Fails the read when the field is absent. */
    context(readContext: JsonReadContext)
    operator fun invoke(): Long = jsonSlot(context = readContext, owner = owner, index = index)

    /** Returns the value, or [default] when the field is absent. */
    context(readContext: JsonReadContext)
    fun orDefault(default: Long): Long = if (jsonPresent(context = readContext, owner = owner, index = index)) {
        readContext.slots[readContext.base + index]
    } else {
        default
    }

    /** Returns the value, or `null` when the field is absent. Allocates a box for most values. */
    context(readContext: JsonReadContext)
    fun nullableBoxed(): Long? = if (jsonPresent(context = readContext, owner = owner, index = index)) {
        readContext.slots[readContext.base + index]
    } else {
        null
    }

    /** Tells whether the field occurs with a value other than `null`. */
    context(readContext: JsonReadContext)
    fun isPresent(): Boolean = jsonPresent(context = readContext, owner = owner, index = index)
}

/**
 * Reads a `Float` field.
 */
class JsonFloatAccessor internal constructor(
    @JvmField
    internal val owner: JsonSchema,
    @JvmField
    internal val index: Int,
) {
    /** Returns the value. Fails the read when the field is absent. */
    context(readContext: JsonReadContext)
    operator fun invoke(): Float = Float.fromBits(jsonSlot(context = readContext, owner = owner, index = index).toInt())

    /** Returns the value, or [default] when the field is absent. */
    context(readContext: JsonReadContext)
    fun orDefault(default: Float): Float = if (jsonPresent(context = readContext, owner = owner, index = index)) {
        Float.fromBits(readContext.slots[readContext.base + index].toInt())
    } else {
        default
    }

    /** Returns the value, or `null` when the field is absent. Allocates a box. */
    context(readContext: JsonReadContext)
    fun nullableBoxed(): Float? = if (jsonPresent(context = readContext, owner = owner, index = index)) {
        Float.fromBits(readContext.slots[readContext.base + index].toInt())
    } else {
        null
    }

    /** Tells whether the field occurs with a value other than `null`. */
    context(readContext: JsonReadContext)
    fun isPresent(): Boolean = jsonPresent(context = readContext, owner = owner, index = index)
}

/**
 * Reads a `Double` field.
 */
class JsonDoubleAccessor internal constructor(
    @JvmField
    internal val owner: JsonSchema,
    @JvmField
    internal val index: Int,
) {
    /** Returns the value. Fails the read when the field is absent. */
    context(readContext: JsonReadContext)
    operator fun invoke(): Double = Double.fromBits(jsonSlot(context = readContext, owner = owner, index = index))

    /** Returns the value, or [default] when the field is absent. */
    context(readContext: JsonReadContext)
    fun orDefault(default: Double): Double = if (jsonPresent(context = readContext, owner = owner, index = index)) {
        Double.fromBits(readContext.slots[readContext.base + index])
    } else {
        default
    }

    /** Returns the value, or `null` when the field is absent. Allocates a box. */
    context(readContext: JsonReadContext)
    fun nullableBoxed(): Double? = if (jsonPresent(context = readContext, owner = owner, index = index)) {
        Double.fromBits(readContext.slots[readContext.base + index])
    } else {
        null
    }

    /** Tells whether the field occurs with a value other than `null`. */
    context(readContext: JsonReadContext)
    fun isPresent(): Boolean = jsonPresent(context = readContext, owner = owner, index = index)
}

/**
 * Reads a `Boolean` field.
 */
class JsonBoolAccessor internal constructor(
    @JvmField
    internal val owner: JsonSchema,
    @JvmField
    internal val index: Int,
) {
    /** Returns the value. Fails the read when the field is absent. */
    context(readContext: JsonReadContext)
    operator fun invoke(): Boolean = jsonSlot(context = readContext, owner = owner, index = index) != 0L

    /** Returns the value, or [default] when the field is absent. */
    context(readContext: JsonReadContext)
    fun orDefault(default: Boolean): Boolean = if (jsonPresent(context = readContext, owner = owner, index = index)) {
        readContext.slots[readContext.base + index] != 0L
    } else {
        default
    }

    /** Returns the value, or `null` when the field is absent. */
    context(readContext: JsonReadContext)
    fun nullableBoxed(): Boolean? = if (jsonPresent(context = readContext, owner = owner, index = index)) {
        readContext.slots[readContext.base + index] != 0L
    } else {
        null
    }

    /** Tells whether the field occurs with a value other than `null`. */
    context(readContext: JsonReadContext)
    fun isPresent(): Boolean = jsonPresent(context = readContext, owner = owner, index = index)
}

/**
 * Reads a string field, either as a new `String` or as borrowed UTF-8 bytes.
 */
class JsonStringAccessor internal constructor(
    @PublishedApi
    @JvmField
    internal val owner: JsonSchema,
    @PublishedApi
    @JvmField
    internal val index: Int,
) {
    /** Returns the value as a new `String`. Fails the read when the field is absent. */
    context(readContext: JsonReadContext)
    operator fun invoke(): String = jsonSpanToString(
        context = readContext,
        span = jsonSlot(
            context = readContext,
            owner = owner,
            index = index,
        ),
    )

    /** Returns the value as a new `String`, or `null` when the field is absent. */
    context(readContext: JsonReadContext)
    fun orNull(): String? = if (jsonPresent(context = readContext, owner = owner, index = index)) {
        jsonSpanToString(context = readContext, span = readContext.slots[readContext.base + index])
    } else {
        null
    }

    /**
     * Lends the decoded UTF-8 bytes of the value to [block]. The array, offset and length are valid only inside
     * [block]. The array must not be changed and may be the input array. Fails the read when the field is absent.
     */
    context(readContext: JsonReadContext)
    inline fun <R> bytes(block: (bytes: ByteArray, offset: Int, length: Int) -> R): R = jsonWithStringBytes(
        context = readContext,
        span = jsonSlot(
            context = readContext,
            owner = owner,
            index = index,
        ),
        block = block,
    )

    /** Tells whether the field occurs with a value other than `null`. */
    context(readContext: JsonReadContext)
    fun isPresent(): Boolean = jsonPresent(context = readContext, owner = owner, index = index)
}

/**
 * Reads a nested value that was built by its own reader.
 */
class JsonObjectAccessor<T> internal constructor(
    @JvmField
    internal val owner: JsonSchema,
    @JvmField
    internal val index: Int,
) {
    /** Returns the nested result. Fails the read when the field is absent. */
    context(readContext: JsonReadContext)
    operator fun invoke(): T {
        @Suppress("UNCHECKED_CAST")
        return jsonRef(context = readContext, owner = owner, index = index) as T
    }

    /** Returns the nested result, or `null` when the field is absent. */
    context(readContext: JsonReadContext)
    fun orNull(): T? {
        if (!jsonPresent(context = readContext, owner = owner, index = index)) {
            return null
        }
        @Suppress("UNCHECKED_CAST")
        return readContext.refs[readContext.base + index] as T
    }

    /** Tells whether the field occurs with a value other than `null`. */
    context(readContext: JsonReadContext)
    fun isPresent(): Boolean = jsonPresent(context = readContext, owner = owner, index = index)
}

/**
 * Reads an array whose elements were built by their own reader.
 */
class JsonListAccessor<T> internal constructor(
    @JvmField
    internal val owner: JsonSchema,
    @JvmField
    internal val index: Int,
) {
    /** Returns the list. Fails the read when the field is absent. */
    context(readContext: JsonReadContext)
    operator fun invoke(): List<T> {
        @Suppress("UNCHECKED_CAST")
        return jsonRef(context = readContext, owner = owner, index = index) as List<T>
    }

    /** Returns the list, or `null` when the field is absent. */
    context(readContext: JsonReadContext)
    fun orNull(): List<T>? {
        if (!jsonPresent(context = readContext, owner = owner, index = index)) {
            return null
        }
        @Suppress("UNCHECKED_CAST")
        return readContext.refs[readContext.base + index] as List<T>
    }

    /** Tells whether the field occurs with a value other than `null`. */
    context(readContext: JsonReadContext)
    fun isPresent(): Boolean = jsonPresent(context = readContext, owner = owner, index = index)
}

/**
 * Reads a flat array of integers as `IntArray`.
 */
class JsonIntsAccessor internal constructor(
    @JvmField
    internal val owner: JsonSchema,
    @JvmField
    internal val index: Int,
) {
    /** Returns a new array of the exact size. Fails the read when the field is absent. */
    context(readContext: JsonReadContext)
    operator fun invoke(): IntArray = materialise(
        context = readContext,
        span = jsonSlot(
            context = readContext,
            owner = owner,
            index = index,
        ),
    )

    /** Returns a new array of the exact size, or `null` when the field is absent. */
    context(readContext: JsonReadContext)
    fun orNull(): IntArray? = if (jsonPresent(context = readContext, owner = owner, index = index)) {
        materialise(context = readContext, span = readContext.slots[readContext.base + index])
    } else {
        null
    }

    /** Returns the number of elements without reading them. Fails the read when the field is absent. */
    context(readContext: JsonReadContext)
    fun size(): Int = jsonSpanCount(jsonSlot(context = readContext, owner = owner, index = index))

    /** Writes all elements into [destination] starting at [destinationOffset]. Allocates nothing. */
    context(readContext: JsonReadContext)
    fun copyInto(destination: IntArray, destinationOffset: Int) {
        val span = jsonSlot(context = readContext, owner = owner, index = index)
        jsonCheckDestination(size = destination.size, destinationOffset = destinationOffset, span = span)
        jsonFillInts(
            context = readContext,
            span = span,
            destination = destination,
            destinationOffset = destinationOffset,
        )
        jsonMarkArrayRead(context = readContext, index = index)
    }

    /** Tells whether the field occurs with a value other than `null`. */
    context(readContext: JsonReadContext)
    fun isPresent(): Boolean = jsonPresent(context = readContext, owner = owner, index = index)

    private fun materialise(context: JsonReadContext, span: Long): IntArray {
        val count = jsonSpanCount(span)
        if (count == 0) {
            jsonMarkArrayRead(context = context, index = index)
            return jsonEmptyInts
        }
        val result = IntArray(count)
        jsonFillInts(context = context, span = span, destination = result, destinationOffset = 0)
        jsonMarkArrayRead(context = context, index = index)
        return result
    }
}

/**
 * Reads a flat array of integers as `LongArray`.
 */
class JsonLongsAccessor internal constructor(
    @JvmField
    internal val owner: JsonSchema,
    @JvmField
    internal val index: Int,
) {
    /** Returns a new array of the exact size. Fails the read when the field is absent. */
    context(readContext: JsonReadContext)
    operator fun invoke(): LongArray = materialise(
        context = readContext,
        span = jsonSlot(
            context = readContext,
            owner = owner,
            index = index,
        ),
    )

    /** Returns a new array of the exact size, or `null` when the field is absent. */
    context(readContext: JsonReadContext)
    fun orNull(): LongArray? = if (jsonPresent(context = readContext, owner = owner, index = index)) {
        materialise(context = readContext, span = readContext.slots[readContext.base + index])
    } else {
        null
    }

    /** Returns the number of elements without reading them. Fails the read when the field is absent. */
    context(readContext: JsonReadContext)
    fun size(): Int = jsonSpanCount(jsonSlot(context = readContext, owner = owner, index = index))

    /** Writes all elements into [destination] starting at [destinationOffset]. Allocates nothing. */
    context(readContext: JsonReadContext)
    fun copyInto(destination: LongArray, destinationOffset: Int) {
        val span = jsonSlot(context = readContext, owner = owner, index = index)
        jsonCheckDestination(size = destination.size, destinationOffset = destinationOffset, span = span)
        jsonFillLongs(
            context = readContext,
            span = span,
            destination = destination,
            destinationOffset = destinationOffset,
        )
        jsonMarkArrayRead(context = readContext, index = index)
    }

    /** Tells whether the field occurs with a value other than `null`. */
    context(readContext: JsonReadContext)
    fun isPresent(): Boolean = jsonPresent(context = readContext, owner = owner, index = index)

    private fun materialise(context: JsonReadContext, span: Long): LongArray {
        val count = jsonSpanCount(span)
        if (count == 0) {
            jsonMarkArrayRead(context = context, index = index)
            return jsonEmptyLongs
        }
        val result = LongArray(count)
        jsonFillLongs(context = context, span = span, destination = result, destinationOffset = 0)
        jsonMarkArrayRead(context = context, index = index)
        return result
    }
}

/**
 * Reads a flat array of numbers as `FloatArray`.
 */
class JsonFloatsAccessor internal constructor(
    @JvmField
    internal val owner: JsonSchema,
    @JvmField
    internal val index: Int,
) {
    /** Returns a new array of the exact size. Fails the read when the field is absent. */
    context(readContext: JsonReadContext)
    operator fun invoke(): FloatArray = materialise(
        context = readContext,
        span = jsonSlot(
            context = readContext,
            owner = owner,
            index = index,
        ),
    )

    /** Returns a new array of the exact size, or `null` when the field is absent. */
    context(readContext: JsonReadContext)
    fun orNull(): FloatArray? = if (jsonPresent(context = readContext, owner = owner, index = index)) {
        materialise(context = readContext, span = readContext.slots[readContext.base + index])
    } else {
        null
    }

    /** Returns the number of elements without reading them. Fails the read when the field is absent. */
    context(readContext: JsonReadContext)
    fun size(): Int = jsonSpanCount(jsonSlot(context = readContext, owner = owner, index = index))

    /** Writes all elements into [destination] starting at [destinationOffset]. Allocates nothing. */
    context(readContext: JsonReadContext)
    fun copyInto(destination: FloatArray, destinationOffset: Int) {
        val span = jsonSlot(context = readContext, owner = owner, index = index)
        jsonCheckDestination(size = destination.size, destinationOffset = destinationOffset, span = span)
        jsonFillFloats(
            context = readContext,
            span = span,
            destination = destination,
            destinationOffset = destinationOffset,
        )
        jsonMarkArrayRead(context = readContext, index = index)
    }

    /** Tells whether the field occurs with a value other than `null`. */
    context(readContext: JsonReadContext)
    fun isPresent(): Boolean = jsonPresent(context = readContext, owner = owner, index = index)

    private fun materialise(context: JsonReadContext, span: Long): FloatArray {
        val count = jsonSpanCount(span)
        if (count == 0) {
            jsonMarkArrayRead(context = context, index = index)
            return jsonEmptyFloats
        }
        val result = FloatArray(count)
        jsonFillFloats(context = context, span = span, destination = result, destinationOffset = 0)
        jsonMarkArrayRead(context = context, index = index)
        return result
    }
}

/**
 * Reads a flat array of numbers as `DoubleArray`.
 */
class JsonDoublesAccessor internal constructor(
    @JvmField
    internal val owner: JsonSchema,
    @JvmField
    internal val index: Int,
) {
    /** Returns a new array of the exact size. Fails the read when the field is absent. */
    context(readContext: JsonReadContext)
    operator fun invoke(): DoubleArray = materialise(
        context = readContext,
        span = jsonSlot(
            context = readContext,
            owner = owner,
            index = index,
        ),
    )

    /** Returns a new array of the exact size, or `null` when the field is absent. */
    context(readContext: JsonReadContext)
    fun orNull(): DoubleArray? = if (jsonPresent(context = readContext, owner = owner, index = index)) {
        materialise(context = readContext, span = readContext.slots[readContext.base + index])
    } else {
        null
    }

    /** Returns the number of elements without reading them. Fails the read when the field is absent. */
    context(readContext: JsonReadContext)
    fun size(): Int = jsonSpanCount(jsonSlot(context = readContext, owner = owner, index = index))

    /** Writes all elements into [destination] starting at [destinationOffset]. Allocates nothing. */
    context(readContext: JsonReadContext)
    fun copyInto(destination: DoubleArray, destinationOffset: Int) {
        val span = jsonSlot(context = readContext, owner = owner, index = index)
        jsonCheckDestination(size = destination.size, destinationOffset = destinationOffset, span = span)
        jsonFillDoubles(
            context = readContext,
            span = span,
            destination = destination,
            destinationOffset = destinationOffset,
        )
        jsonMarkArrayRead(context = readContext, index = index)
    }

    /** Tells whether the field occurs with a value other than `null`. */
    context(readContext: JsonReadContext)
    fun isPresent(): Boolean = jsonPresent(context = readContext, owner = owner, index = index)

    private fun materialise(context: JsonReadContext, span: Long): DoubleArray {
        val count = jsonSpanCount(span)
        if (count == 0) {
            jsonMarkArrayRead(context = context, index = index)
            return jsonEmptyDoubles
        }
        val result = DoubleArray(count)
        jsonFillDoubles(context = context, span = span, destination = result, destinationOffset = 0)
        jsonMarkArrayRead(context = context, index = index)
        return result
    }
}

/**
 * Reads a field of any JSON type.
 */
class JsonValueAccessor internal constructor(
    @JvmField
    internal val owner: JsonSchema,
    @JvmField
    internal val index: Int,
) {
    /** Returns the value for inspection. Fails the read when the field is absent. */
    context(readContext: JsonReadContext)
    operator fun invoke(): JsonValue = JsonValue(jsonSlot(context = readContext, owner = owner, index = index).toInt())

    /** Tells whether the field occurs with a value other than `null`. */
    context(readContext: JsonReadContext)
    fun isPresent(): Boolean = jsonPresent(context = readContext, owner = owner, index = index)
}

/**
 * Walks the members of an object whose keys are not known in advance.
 */
class JsonEntriesAccessor internal constructor(
    @PublishedApi
    @JvmField
    internal val owner: JsonSchema,
    @PublishedApi
    @JvmField
    internal val index: Int,
    @PublishedApi
    @JvmField
    internal val filter: JsonSchema?,
) {
    /**
     * Calls [block] once per member, in document order. Key and value are valid only inside [block].
     * Fails the read when the field is absent.
     */
    context(readContext: JsonReadContext)
    inline fun each(block: (key: JsonKey, value: JsonValue) -> Unit) {
        jsonEachMember(
            context = readContext,
            objectOffset = jsonEntriesOffset(
                context = readContext,
                owner = owner,
                index = index,
                filter = filter,
            ),
            filter = filter,
        ) { key, value ->
            block(
                JsonKey(key),
                JsonValue(value),
            )
        }
    }

    /** Tells whether the field occurs with a value other than `null`. Always true for the form without a name. */
    context(readContext: JsonReadContext)
    fun isPresent(): Boolean = filter != null || jsonPresent(context = readContext, owner = owner, index = index)
}

/**
 * Walks the members of an object whose values are all integers that fit `Int`.
 */
class JsonIntEntriesAccessor internal constructor(
    @PublishedApi
    @JvmField
    internal val owner: JsonSchema,
    @PublishedApi
    @JvmField
    internal val index: Int,
    @PublishedApi
    @JvmField
    internal val filter: JsonSchema?,
) {
    /**
     * Calls [block] once per member, in document order. The key is valid only inside [block].
     * Fails the read when the field is absent or a value is not an `Int`.
     */
    context(readContext: JsonReadContext)
    inline fun each(block: (key: JsonKey, value: Int) -> Unit) {
        jsonEachMember(
            context = readContext,
            objectOffset = jsonEntriesOffset(
                context = readContext,
                owner = owner,
                index = index,
                filter = filter,
            ),
            filter = filter,
        ) { key, value ->
            block(
                JsonKey(key),
                jsonValueInt(
                    context = readContext,
                    offset = value,
                ),
            )
        }
    }

    /** Tells whether the field occurs with a value other than `null`. Always true for the form without a name. */
    context(readContext: JsonReadContext)
    fun isPresent(): Boolean = filter != null || jsonPresent(context = readContext, owner = owner, index = index)
}

/**
 * Walks the members of an object whose values are all integers that fit `Long`.
 */
class JsonLongEntriesAccessor internal constructor(
    @PublishedApi
    @JvmField
    internal val owner: JsonSchema,
    @PublishedApi
    @JvmField
    internal val index: Int,
    @PublishedApi
    @JvmField
    internal val filter: JsonSchema?,
) {
    /**
     * Calls [block] once per member, in document order. The key is valid only inside [block].
     * Fails the read when the field is absent or a value is not a `Long`.
     */
    context(readContext: JsonReadContext)
    inline fun each(block: (key: JsonKey, value: Long) -> Unit) {
        jsonEachMember(
            context = readContext,
            objectOffset = jsonEntriesOffset(
                context = readContext,
                owner = owner,
                index = index,
                filter = filter,
            ),
            filter = filter,
        ) { key, value ->
            block(
                JsonKey(key),
                jsonValueLong(
                    context = readContext,
                    offset = value,
                ),
            )
        }
    }

    /** Tells whether the field occurs with a value other than `null`. Always true for the form without a name. */
    context(readContext: JsonReadContext)
    fun isPresent(): Boolean = filter != null || jsonPresent(context = readContext, owner = owner, index = index)
}

/**
 * Walks the members of an object whose values are all numbers, read as `Float`.
 */
class JsonFloatEntriesAccessor internal constructor(
    @PublishedApi
    @JvmField
    internal val owner: JsonSchema,
    @PublishedApi
    @JvmField
    internal val index: Int,
    @PublishedApi
    @JvmField
    internal val filter: JsonSchema?,
) {
    /**
     * Calls [block] once per member, in document order. The key is valid only inside [block].
     * Fails the read when the field is absent or a value is not a number.
     */
    context(readContext: JsonReadContext)
    inline fun each(block: (key: JsonKey, value: Float) -> Unit) {
        jsonEachMember(
            context = readContext,
            objectOffset = jsonEntriesOffset(
                context = readContext,
                owner = owner,
                index = index,
                filter = filter,
            ),
            filter = filter,
        ) { key, value ->
            block(
                JsonKey(key),
                jsonValueFloat(
                    context = readContext,
                    offset = value,
                ),
            )
        }
    }

    /** Tells whether the field occurs with a value other than `null`. Always true for the form without a name. */
    context(readContext: JsonReadContext)
    fun isPresent(): Boolean = filter != null || jsonPresent(context = readContext, owner = owner, index = index)
}

/**
 * Walks the members of an object whose values are all numbers, read as `Double`.
 */
class JsonDoubleEntriesAccessor internal constructor(
    @PublishedApi
    @JvmField
    internal val owner: JsonSchema,
    @PublishedApi
    @JvmField
    internal val index: Int,
    @PublishedApi
    @JvmField
    internal val filter: JsonSchema?,
) {
    /**
     * Calls [block] once per member, in document order. The key is valid only inside [block].
     * Fails the read when the field is absent or a value is not a number.
     */
    context(readContext: JsonReadContext)
    inline fun each(block: (key: JsonKey, value: Double) -> Unit) {
        jsonEachMember(
            context = readContext,
            objectOffset = jsonEntriesOffset(
                context = readContext,
                owner = owner,
                index = index,
                filter = filter,
            ),
            filter = filter,
        ) { key, value ->
            block(
                JsonKey(key),
                jsonValueDouble(
                    context = readContext,
                    offset = value,
                ),
            )
        }
    }

    /** Tells whether the field occurs with a value other than `null`. Always true for the form without a name. */
    context(readContext: JsonReadContext)
    fun isPresent(): Boolean = filter != null || jsonPresent(context = readContext, owner = owner, index = index)
}

/**
 * Walks the members of an object whose values are all `true` or `false`.
 */
class JsonBoolEntriesAccessor internal constructor(
    @PublishedApi
    @JvmField
    internal val owner: JsonSchema,
    @PublishedApi
    @JvmField
    internal val index: Int,
    @PublishedApi
    @JvmField
    internal val filter: JsonSchema?,
) {
    /**
     * Calls [block] once per member, in document order. The key is valid only inside [block].
     * Fails the read when the field is absent or a value is not `true` or `false`.
     */
    context(readContext: JsonReadContext)
    inline fun each(block: (key: JsonKey, value: Boolean) -> Unit) {
        jsonEachMember(
            context = readContext,
            objectOffset = jsonEntriesOffset(
                context = readContext,
                owner = owner,
                index = index,
                filter = filter,
            ),
            filter = filter,
        ) { key, value ->
            block(
                JsonKey(key),
                jsonValueBool(
                    context = readContext,
                    offset = value,
                ),
            )
        }
    }

    /** Tells whether the field occurs with a value other than `null`. Always true for the form without a name. */
    context(readContext: JsonReadContext)
    fun isPresent(): Boolean = filter != null || jsonPresent(context = readContext, owner = owner, index = index)
}

/**
 * The key of one member while walking entries. Valid only inside the block that received it.
 */
@JvmInline
value class JsonKey @PublishedApi internal constructor(
    @PublishedApi
    internal val span: Long,
) {
    /** Returns the key as a new `String`. */
    context(readContext: JsonReadContext)
    operator fun invoke(): String = jsonSpanToString(context = readContext, span = span)

    /** Tells whether the key equals [text]. Allocates nothing. */
    context(readContext: JsonReadContext)
    fun matches(text: String): Boolean = jsonSpanMatches(context = readContext, span = span, text = text)

    /** Tells whether the key starts with [prefix]. Allocates nothing. */
    context(readContext: JsonReadContext)
    fun startsWith(prefix: String): Boolean = jsonSpanStartsWith(context = readContext, span = span, prefix = prefix)

    /**
     * Lends the decoded UTF-8 bytes of the key to [block]. The array, offset and length are valid only inside
     * [block]. The array must not be changed and may be the input array.
     */
    context(readContext: JsonReadContext)
    inline fun <R> bytes(
        block: (bytes: ByteArray, offset: Int, length: Int) -> R,
    ): R = jsonWithStringBytes(context = readContext, span = span, block = block)
}

/**
 * One JSON value of any type, inspected on demand. Valid only inside the block that received it.
 * Every read fails with [JsonErrorType.TYPE_MISMATCH] when the value has another JSON type.
 */
@JvmInline
value class JsonValue @PublishedApi internal constructor(
    @PublishedApi
    internal val offset: Int,
) {
    /** Tells whether the value is the literal `null`. */
    context(readContext: JsonReadContext)
    fun isNull(): Boolean = jsonValueKind(context = readContext, offset = offset) == JSON_VALUE_NULL

    /** Tells whether the value is a string. */
    context(readContext: JsonReadContext)
    fun isString(): Boolean = jsonValueKind(context = readContext, offset = offset) == JSON_VALUE_STRING

    /** Tells whether the value is a number. */
    context(readContext: JsonReadContext)
    fun isNumber(): Boolean = jsonValueKind(context = readContext, offset = offset) == JSON_VALUE_NUMBER

    /** Tells whether the value is `true` or `false`. */
    context(readContext: JsonReadContext)
    fun isBool(): Boolean = jsonValueKind(context = readContext, offset = offset) == JSON_VALUE_BOOL

    /** Tells whether the value is an object. */
    context(readContext: JsonReadContext)
    fun isObject(): Boolean = jsonValueKind(context = readContext, offset = offset) == JSON_VALUE_OBJECT

    /** Tells whether the value is an array. */
    context(readContext: JsonReadContext)
    fun isArray(): Boolean = jsonValueKind(context = readContext, offset = offset) == JSON_VALUE_ARRAY

    /** Reads the value as an integer that fits `Int`. */
    context(readContext: JsonReadContext)
    fun int(): Int = jsonValueInt(context = readContext, offset = offset)

    /** Reads the value as an integer that fits `Long`. */
    context(readContext: JsonReadContext)
    fun long(): Long = jsonValueLong(context = readContext, offset = offset)

    /** Reads the value as a `Float`. */
    context(readContext: JsonReadContext)
    fun float(): Float = jsonValueFloat(context = readContext, offset = offset)

    /** Reads the value as a `Double`. */
    context(readContext: JsonReadContext)
    fun double(): Double = jsonValueDouble(context = readContext, offset = offset)

    /** Reads the value as a `Boolean`. */
    context(readContext: JsonReadContext)
    fun bool(): Boolean = jsonValueBool(context = readContext, offset = offset)

    /** Reads the value as a new `String`. */
    context(readContext: JsonReadContext)
    fun string(): String = jsonSpanToString(
        context = readContext,
        span = jsonValueStringSpan(
            context = readContext,
            offset = offset,
        ),
    )

    /** Tells whether the value is a string equal to [text]. Allocates nothing. */
    context(readContext: JsonReadContext)
    fun matches(text: String): Boolean = jsonSpanMatches(
        context = readContext,
        span = jsonValueStringSpan(
            context = readContext,
            offset = offset,
        ),
        text = text,
    )

    /**
     * Lends the decoded UTF-8 bytes of a string value to [block]. The array, offset and length are valid only
     * inside [block]. The array must not be changed and may be the input array.
     */
    context(readContext: JsonReadContext)
    inline fun <R> bytes(block: (bytes: ByteArray, offset: Int, length: Int) -> R): R = jsonWithStringBytes(
        context = readContext,
        span = jsonValueStringSpan(
            context = readContext,
            offset = offset,
        ),
        block = block,
    )

    /**
     * Lends the raw JSON text of the value, of any type, to [block]. The array is the input array. It must not be
     * changed and is valid only inside [block].
     */
    context(readContext: JsonReadContext)
    inline fun <R> raw(
        block: (bytes: ByteArray, offset: Int, length: Int) -> R,
    ): R = block(readContext.bytes, offset, jsonValueEnd(context = readContext, offset = offset) - offset)

    /** Reads the value with [reader]. */
    context(readContext: JsonReadContext)
    fun <T> read(reader: JsonReader<T>): T = jsonReadValueAt(context = readContext, reader = reader, offset = offset)

    /** Reads the value as an array whose elements are read by [reader]. */
    context(readContext: JsonReadContext)
    fun <T> readList(
        reader: JsonReader<T>,
    ): List<T> = jsonReadListAt(context = readContext, reader = reader, offset = offset)

    /** Calls [block] once per member of an object value, in document order. */
    context(readContext: JsonReadContext)
    inline fun each(block: (key: JsonKey, value: JsonValue) -> Unit) {
        jsonEachMember(context = readContext, objectOffset = offset, filter = null) { key, value ->
            block(
                JsonKey(key),
                JsonValue(value),
            )
        }
    }

    /** Calls [block] once per element of an array value, in document order. */
    context(readContext: JsonReadContext)
    inline fun elements(block: (value: JsonValue) -> Unit) {
        var element = jsonElementsFirst(context = readContext, arrayOffset = offset)
        while (element >= 0) {
            block(JsonValue(element))
            element = jsonElementsNext(context = readContext, valueOffset = element)
        }
    }
}

@PublishedApi
internal class JsonSchema(
    @JvmField
    val tuple: Boolean,
) {
    @JvmField
    var fieldCount: Int = 0

    @JvmField
    var presenceWords: Int = 0

    @JvmField
    var kinds: ByteArray = jsonEmptyBytes

    @JvmField
    var readers: Array<JsonReader<*>> = jsonNoReaders

    @JvmField
    var enums: Array<Array<ByteArray>> = jsonNoEnums

    @JvmField
    var keyBytes: Array<ByteArray> = jsonNoKeys

    @JvmField
    var keyLengths: IntArray = jsonEmptyInts

    @JvmField
    var keyWords: LongArray = jsonEmptyLongs

    @JvmField
    var keyLast: LongArray = jsonEmptyLongs

    @JvmField
    var arrayFields: IntArray = jsonEmptyInts

    @JvmField
    var hasArrayFields: Boolean = false

    @JvmField
    var keyTable: IntArray = jsonEmptyInts

    @JvmField
    var positionToField: IntArray = jsonEmptyInts

    @JvmField
    var restField: Int = JSON_NO_FIELD

    private val names = ArrayList<String?>()
    private val positions = ArrayList<Int>()
    private val kindList = ArrayList<Int>()
    private val readerList = ArrayList<JsonReader<*>>()
    private val enumList = ArrayList<Array<ByteArray>>()
    private var finished = false

    fun add(
        name: String?,
        position: Int,
        kind: Int,
    ): Int {
        if (finished) {
            jsonIllegalState("JSON fields cannot be declared after build")
        }
        if (tuple) {
            if (position < 0) {
                jsonIllegalState("JSON tuple position is negative")
            }
            if (positions.contains(position)) {
                jsonIllegalState("JSON tuple position is declared twice")
            }
        } else if (name != null && names.contains(name)) {
            jsonIllegalState("JSON field name is declared twice")
        }
        names.add(name)
        positions.add(position)
        kindList.add(kind)
        readerList.add(JsonNoReader)
        enumList.add(jsonNoLiterals)
        return names.size - 1
    }

    fun addRest(): Int {
        if (restField != JSON_NO_FIELD) {
            jsonIllegalState("JSON entries without a name are declared twice")
        }
        val index = add(name = null, position = JSON_NO_POSITION, kind = JSON_KIND_REST)
        restField = index
        return index
    }

    fun setReader(index: Int, reader: JsonReader<*>) {
        readerList[index] = reader
    }

    fun setEnum(index: Int, literals: Array<out String>) {
        if (literals.isEmpty()) {
            jsonIllegalState("JSON enum has no literals")
        }
        for (first in literals.indices) {
            for (second in first + 1 until literals.size) {
                if (literals[first] == literals[second]) {
                    jsonIllegalState("JSON enum literal is declared twice")
                }
            }
        }
        enumList[index] = Array(literals.size) { literals[it].encodeToByteArray() }
    }

    fun finish(): JsonSchema {
        if (finished) {
            jsonIllegalState("JSON build was already called on this builder")
        }
        finished = true
        val count = names.size
        fieldCount = count
        presenceWords = (count + JSON_PRESENCE_BIT_MASK) ushr JSON_PRESENCE_WORD_SHIFT
        kinds = ByteArray(count) { kindList[it].toByte() }
        readers = Array(count) { readerList[it] }
        enums = Array(count) { enumList[it] }
        arrayFields = (0 until count).filter { kindList[it] == JSON_KIND_NUMBERS }.toIntArray()
        hasArrayFields = arrayFields.isNotEmpty()
        if (tuple) {
            finishPositions(count)
        } else {
            finishKeys(count)
        }
        return this
    }

    private fun finishPositions(count: Int) {
        var size = 0
        for (field in 0 until count) {
            if (positions[field] >= size) {
                size = positions[field] + 1
            }
        }
        val table = IntArray(size)
        table.fill(JSON_NO_FIELD)
        for (field in 0 until count) {
            table[positions[field]] = field
        }
        positionToField = table
    }

    private fun finishKeys(count: Int) {
        val bytes = Array(count) { names[it]?.encodeToByteArray() ?: jsonEmptyBytes }
        val lengths = IntArray(count)
        val words = LongArray(count)
        for (field in 0 until count) {
            val key = bytes[field]
            lengths[field] = if (names[field] == null || jsonNeedsSlowKeyPath(key)) {
                JSON_SLOW_KEY
            } else {
                key.size
            }
            words[field] = jsonKeyWord(key)
        }
        keyBytes = bytes
        keyLengths = lengths
        keyWords = words
        keyLast = LongArray(count) { jsonKeyLastWord(bytes = bytes[it], end = bytes[it].size, length = bytes[it].size) }
        keyTable = jsonBuildKeyTable(schema = this, named = BooleanArray(count) { names[it] != null })
    }
}

private object JsonNoReader : JsonReader<Any?>() {
    override fun readValue(context: JsonReadContext): Any = jsonIllegalState("JSON field has no nested reader")

    override fun readElements(
        context: JsonReadContext,
    ): List<Any?> = jsonIllegalState("JSON field has no nested reader")
}

@PublishedApi
internal inline fun jsonFail(error: JsonErrorType, offset: Int): Nothing = throw JsonException(error = error, offset = offset)

@PublishedApi
internal inline fun jsonIllegalState(message: String): Nothing = throw JsonIllegalStateException(message)

private inline fun jsonInternalCheck(condition: () -> Boolean) {
    if (OPTION_INTERNAL_CHECKS && !condition()) {
        jsonIllegalState("JSON accessor was read outside of the build block of the reader that declared it")
    }
}

private fun jsonBeginRead(
    context: JsonReadContext,
    bytes: ByteArray,
    offset: Int,
    length: Int,
) {
    if (offset < 0 || length < 0 || offset > bytes.size - length) {
        jsonIllegalState("JSON offset and length are outside of the array")
    }
    if (context.busy) {
        jsonIllegalState("JSON read context is already used by a read in progress")
    }
    context.busy = true
    context.bytes = bytes
    context.cursor = offset
    context.limit = offset + length
    context.base = 0
    context.top = 0
    context.refsHighWater = 0
    context.elementsTop = 0
    context.elementsHighWater = 0
    context.scratchTop = 0
    context.depth = 0
    context.lastStringOffset = JSON_NO_OFFSET
}

private fun jsonReleaseRead(context: JsonReadContext) {
    Arrays.fill(context.refs, 0, context.refsHighWater, null)
    Arrays.fill(context.elements, 0, context.elementsHighWater, null)
    context.bytes = jsonEmptyBytes
    context.borrowBytes = jsonEmptyBytes
    context.busy = false
}

private fun jsonSkipLeading(context: JsonReadContext) {
    val bytes = context.bytes
    val cursor = context.cursor
    if (context.limit - cursor >= JSON_BOM_SIZE &&
        bytes[cursor] == JSON_BOM_FIRST &&
        bytes[cursor + 1] == JSON_BOM_SECOND &&
        bytes[cursor + 2] == JSON_BOM_THIRD
    ) {
        context.cursor = cursor + JSON_BOM_SIZE
    }
    jsonSkipWhitespace(context)
    if (context.cursor >= context.limit) {
        jsonFail(error = JsonErrorType.UNEXPECTED_END, offset = context.cursor)
    }
}

private fun jsonSkipTrailing(context: JsonReadContext) {
    jsonSkipWhitespace(context)
    if (context.cursor != context.limit) {
        jsonFail(error = JsonErrorType.TRAILING_DATA, offset = context.cursor)
    }
}

private fun jsonEnter(context: JsonReadContext) {
    val depth = context.depth + 1
    if (depth > MAX_NESTING_DEPTH) {
        jsonFail(error = JsonErrorType.NESTING_TOO_DEEP, offset = context.cursor)
    }
    context.depth = depth
}

private fun jsonLeave(context: JsonReadContext) {
    context.depth--
}

@PublishedApi
internal fun jsonPushFrame(context: JsonReadContext, schema: JsonSchema) {
    jsonEnter(context)
    val presenceStart = context.top
    val base = presenceStart + schema.presenceWords + 1
    val top = base + schema.fieldCount
    if (top > context.slots.size) {
        val capacity = maxOf(top, context.slots.size * JSON_GROWTH_FACTOR)
        context.slots = context.slots.copyOf(capacity)
        context.refs = context.refs.copyOf(capacity)
    }
    val slots = context.slots
    for (word in presenceStart until base - 1) {
        slots[word] = 0L
    }
    slots[base - 1] =
        (context.base.toLong() shl JSON_HIGH_HALF_SHIFT) or
                (context.cursor.toLong() and JSON_LOW_HALF_MASK)
    context.refs[base - 1] = schema
    context.base = base
    context.top = top
    if (top > context.refsHighWater) {
        context.refsHighWater = top
    }
}

@PublishedApi
internal fun jsonPopFrame(context: JsonReadContext, schema: JsonSchema) {
    val base = context.base
    context.top = base - 1 - schema.presenceWords
    context.base = (context.slots[base - 1] ushr JSON_HIGH_HALF_SHIFT).toInt()
    jsonLeave(context)
}

@PublishedApi
internal fun jsonSetPresence(
    context: JsonReadContext,
    field: Int,
    present: Boolean,
) {
    val word = context.base - 2 - (field ushr JSON_PRESENCE_WORD_SHIFT)
    val shift = (field and JSON_PRESENCE_BIT_MASK) shl JSON_PRESENCE_BITS_PER_FIELD_SHIFT
    val current = context.slots[word]
    if (OPTION_REJECT_DUPLICATE_KEYS && ((current ushr shift) and JSON_SEEN) != 0L) {
        jsonFail(error = JsonErrorType.DUPLICATE_KEY, offset = context.lastKeyOffset)
    }
    context.slots[word] = if (present) {
        current or (JSON_SEEN_AND_PRESENT shl shift)
    } else {
        (current and (JSON_PRESENT shl shift).inv()) or (JSON_SEEN shl shift)
    }
}

@PublishedApi
internal fun jsonPresent(
    context: JsonReadContext,
    owner: JsonSchema,
    index: Int,
): Boolean {
    jsonInternalCheck {
        context.busy && context.base > 0 && context.refs[context.base - 1] === owner
    }
    val word = context.slots[context.base - 2 - (index ushr JSON_PRESENCE_WORD_SHIFT)]
    return ((word ushr (((index and JSON_PRESENCE_BIT_MASK) shl JSON_PRESENCE_BITS_PER_FIELD_SHIFT) + 1)) and 1L) != 0L
}

@PublishedApi
internal fun jsonSlot(
    context: JsonReadContext,
    owner: JsonSchema,
    index: Int,
): Long {
    if (!jsonPresent(context = context, owner = owner, index = index)) {
        jsonFail(
            error = JsonErrorType.MISSING_FIELD,
            offset = jsonFrameOffset(context),
        )
    }
    return context.slots[context.base + index]
}

internal fun jsonRef(
    context: JsonReadContext,
    owner: JsonSchema,
    index: Int,
): Any? {
    if (!jsonPresent(context = context, owner = owner, index = index)) {
        jsonFail(
            error = JsonErrorType.MISSING_FIELD,
            offset = jsonFrameOffset(context),
        )
    }
    return context.refs[context.base + index]
}

private fun jsonFrameOffset(context: JsonReadContext): Int = context.slots[context.base - 1].toInt()

@PublishedApi
internal fun jsonEntriesOffset(
    context: JsonReadContext,
    owner: JsonSchema,
    index: Int,
    filter: JsonSchema?,
): Int {
    if (filter == null) {
        return jsonSlot(context = context, owner = owner, index = index).toInt()
    }
    jsonInternalCheck {
        context.busy && context.base > 0 && context.refs[context.base - 1] === owner
    }
    return jsonFrameOffset(context)
}

@PublishedApi
internal inline fun <T> jsonReadObject(
    context: JsonReadContext,
    schema: JsonSchema,
    build: () -> T,
): T {
    jsonPushFrame(context = context, schema = schema)
    var expected = 0
    var more = jsonOpenObject(context)
    while (more) {
        val field = jsonMatchKey(context = context, schema = schema, expected = expected)
        if (field >= 0) {
            jsonReadField(context = context, schema = schema, field = field)
            expected = field + 1
        } else {
            jsonSkipUnknownMember(context = context, schema = schema)
        }
        more = jsonNextMember(context)
    }
    val result = build()
    if (schema.hasArrayFields) {
        jsonFinishFrame(context = context, schema = schema)
    }
    jsonPopFrame(context = context, schema = schema)
    return result
}

@PublishedApi
internal inline fun <T> jsonReadTuple(
    context: JsonReadContext,
    schema: JsonSchema,
    build: () -> T,
): T {
    jsonPushFrame(context = context, schema = schema)
    var position = 0
    var more = jsonOpenArray(context)
    while (more) {
        val field = jsonFieldAt(schema = schema, position = position)
        if (field >= 0) {
            jsonReadField(context = context, schema = schema, field = field)
        } else {
            jsonSkipValue(context)
        }
        position++
        more = jsonNextElement(context)
    }
    val result = build()
    if (schema.hasArrayFields) {
        jsonFinishFrame(context = context, schema = schema)
    }
    jsonPopFrame(context = context, schema = schema)
    return result
}

@PublishedApi
internal inline fun jsonReadField(
    context: JsonReadContext,
    schema: JsonSchema,
    field: Int,
) {
    if (jsonConsumeNull(context)) {
        jsonSetPresence(context = context, field = field, present = false)
        return
    }
    val slot = context.base + field
    when (schema.kinds[field].toInt()) {
        JSON_KIND_INT -> {
            val value = jsonParseInt(context)
            context.slots[slot] = value.toLong()
        }

        JSON_KIND_LONG -> {
            val value = jsonParseLong(context)
            context.slots[slot] = value
        }

        JSON_KIND_FLOAT -> {
            val value = jsonParseFloat(context)
            context.slots[slot] = value.toRawBits().toLong()
        }

        JSON_KIND_DOUBLE -> {
            val value = jsonParseDouble(context)
            context.slots[slot] = value.toRawBits()
        }

        JSON_KIND_BOOL -> {
            val value = jsonParseBool(context)
            context.slots[slot] = if (value) {
                1L
            } else {
                0L
            }
        }

        JSON_KIND_STRING -> {
            val span = jsonScanString(context)
            context.slots[slot] = span
        }

        JSON_KIND_ENUM -> {
            val literal = jsonMatchEnum(context = context, literals = schema.enums[field])
            context.slots[slot] = literal.toLong()
        }

        JSON_KIND_NUMBERS -> {
            val span = jsonScanNumbers(context)
            context.slots[slot] = span
        }

        JSON_KIND_READER -> {
            val value = schema.readers[field].readValue(context)
            context.refs[slot] = value
        }

        JSON_KIND_LIST -> {
            val value = schema.readers[field].readElements(context)
            context.refs[slot] = value
        }

        JSON_KIND_VALUE -> {
            val offset = jsonScanValue(context)
            context.slots[slot] = offset.toLong()
        }

        JSON_KIND_ENTRIES -> {
            val offset = jsonScanEntries(context)
            context.slots[slot] = offset.toLong()
        }

        else -> {
            jsonIllegalState("JSON field kind cannot be read from input")
        }
    }
    jsonSetPresence(context = context, field = field, present = true)
}

@PublishedApi
internal inline fun <T> jsonReadList(context: JsonReadContext, element: () -> T): List<T> {
    val start = jsonBeginList(context)
    var more = jsonOpenArray(context)
    while (more) {
        jsonPushElement(context = context, element = element())
        more = jsonNextElement(context)
    }
    return jsonFinishList(context = context, start = start)
}

@PublishedApi
internal fun jsonBeginList(context: JsonReadContext): Int {
    jsonEnter(context)
    return context.elementsTop
}

@PublishedApi
internal fun jsonPushElement(context: JsonReadContext, element: Any?) {
    val top = context.elementsTop
    if (top == context.elements.size) {
        context.elements = context.elements.copyOf(top * JSON_GROWTH_FACTOR)
    }
    context.elements[top] = element
    context.elementsTop = top + 1
    if (top + 1 > context.elementsHighWater) {
        context.elementsHighWater = top + 1
    }
}

@PublishedApi
internal fun <T> jsonFinishList(context: JsonReadContext, start: Int): List<T> {
    jsonLeave(context)
    val end = context.elementsTop
    context.elementsTop = start
    if (end == start) {
        return emptyList()
    }
    @Suppress("UNCHECKED_CAST")
    return context.elements.copyOfRange(start, end).asList() as List<T>
}

@PublishedApi
internal fun jsonFieldAt(schema: JsonSchema, position: Int): Int {
    val table = schema.positionToField
    return if (position < table.size) {
        table[position]
    } else {
        JSON_NO_FIELD
    }
}

@PublishedApi
internal fun jsonSkipUnknownMember(context: JsonReadContext, schema: JsonSchema) {
    if (schema.restField == JSON_NO_FIELD) {
        jsonOnUnknownKey(context)
    }
    jsonSkipValue(context)
}

@PublishedApi
internal fun jsonSkipValue(context: JsonReadContext) {
    jsonSkipValueMode(context = context, structural = true)
}

private fun jsonSkipValueMode(context: JsonReadContext, structural: Boolean) {
    if (OPTION_VALIDATE_SKIPPED_VALUES) {
        jsonSkipValueValidating(context)
    } else if (structural) {
        jsonSkipValueStructural(context)
    }
}

@PublishedApi
internal fun jsonFinishFrame(context: JsonReadContext, schema: JsonSchema) {
    for (field in schema.arrayFields) {
        val word = context.slots[context.base - 2 - (field ushr JSON_PRESENCE_WORD_SHIFT)]
        val present =
            ((word ushr (((field and JSON_PRESENCE_BIT_MASK) shl JSON_PRESENCE_BITS_PER_FIELD_SHIFT) + 1)) and 1L) != 0L
        val span = context.slots[context.base + field]
        if (present && (span and JSON_ARRAY_READ_BIT) == 0L) {
            val saved = context.cursor
            context.cursor = jsonSpanOffset(span)
            jsonSkipValueMode(context = context, structural = false)
            context.cursor = saved
        }
    }
}

internal fun jsonMarkArrayRead(context: JsonReadContext, index: Int) {
    val slot = context.base + index
    context.slots[slot] = context.slots[slot] or JSON_ARRAY_READ_BIT
}

@PublishedApi
internal fun jsonScanValue(context: JsonReadContext): Int {
    val offset = context.cursor
    jsonSkipValue(context)
    return offset
}

@PublishedApi
internal fun jsonScanEntries(context: JsonReadContext): Int {
    val offset = context.cursor
    if (context.bytes[offset] != JSON_OBJECT_OPEN) {
        jsonFailTypedToken(bytes = context.bytes, start = offset, limit = context.limit)
    }
    jsonSkipValue(context)
    return offset
}

private fun jsonOnUnknownKey(context: JsonReadContext) {
    if (OPTION_REJECT_UNKNOWN_KEYS) {
        jsonFail(error = JsonErrorType.UNKNOWN_KEY, offset = context.lastKeyOffset)
    }
}

private fun jsonOnControlChar(offset: Int) {
    if (OPTION_REJECT_CONTROL_CHARS_IN_STRINGS) {
        jsonFail(error = JsonErrorType.CONTROL_CHAR_IN_STRING, offset = offset)
    }
}

private fun jsonOnQuotedNumber(offset: Int) {
    if (!OPTION_NUMBERS_ACCEPT_QUOTED) {
        jsonFail(error = JsonErrorType.TYPE_MISMATCH, offset = offset)
    }
}

private fun jsonOnDecimalFormInt(offset: Int) {
    if (!OPTION_INTS_ACCEPT_DECIMAL_FORM) {
        jsonFail(error = JsonErrorType.NOT_AN_INTEGER, offset = offset)
    }
}

private fun jsonOnNonFiniteLiteral(offset: Int) {
    if (!OPTION_ACCEPT_NAN_INFINITY) {
        jsonFail(error = JsonErrorType.INVALID_LITERAL, offset = offset)
    }
}

private inline fun jsonOnTrailingComma(offset: Int) {
    if (!OPTION_ACCEPT_TRAILING_COMMAS) {
        jsonFail(error = JsonErrorType.UNEXPECTED_BYTE, offset = offset)
    }
}

private inline fun jsonCheckUtf8(
    context: JsonReadContext,
    start: Int,
    end: Int,
) {
    if (OPTION_VALIDATE_UTF8) {
        jsonValidateUtf8(bytes = context.bytes, start = start, end = end)
    }
}

private inline fun jsonSpanOffset(span: Long): Int = (span ushr JSON_HIGH_HALF_SHIFT).toInt()

private inline fun jsonSpanLength(span: Long): Int = (span and JSON_SPAN_LENGTH_MASK).toInt()

private inline fun jsonSpanEscaped(span: Long): Boolean = (span and JSON_SPAN_ESCAPED_BIT) != 0L

private inline fun jsonSpanPlainAscii(span: Long): Boolean =
    (span and (JSON_SPAN_ESCAPED_BIT or JSON_SPAN_NON_ASCII_BIT)) == 0L

internal inline fun jsonSpanCount(span: Long): Int = span.toInt() and JSON_SPAN_COUNT_MASK

internal fun jsonCheckDestination(
    size: Int,
    destinationOffset: Int,
    span: Long,
) {
    if (destinationOffset < 0 || destinationOffset > size - jsonSpanCount(span)) {
        jsonIllegalState("JSON destination array is too small")
    }
}

@PublishedApi
internal fun jsonBorrowString(context: JsonReadContext, span: Long): Int {
    if (jsonSpanEscaped(span)) {
        return jsonDecodeEscapes(
            context = context,
            offset = jsonSpanOffset(span),
            rawLength = jsonSpanLength(span),
        )
    }
    context.borrowBytes = context.bytes
    context.borrowOffset = jsonSpanOffset(span)
    return jsonSpanLength(span)
}

@PublishedApi
internal inline fun <R> jsonWithStringBytes(
    context: JsonReadContext,
    span: Long,
    block: (bytes: ByteArray, offset: Int, length: Int) -> R,
): R {
    val mark = context.scratchTop
    val length = jsonBorrowString(context = context, span = span)
    try {
        return block(context.borrowBytes, context.borrowOffset, length)
    } finally {
        context.scratchTop = mark
    }
}

@PublishedApi
internal fun jsonSpanToString(context: JsonReadContext, span: Long): String {
    val length = jsonSpanLength(span)
    if (length == 0) {
        return ""
    }
    if (jsonSpanPlainAscii(span)) {
        return jsonAsciiString(bytes = context.bytes, offset = jsonSpanOffset(span), length = length)
    }
    if ((span and JSON_SPAN_NON_ASCII_BIT) == 0L) {
        return jsonEscapedAsciiString(context = context, offset = jsonSpanOffset(span), length = length)
    }
    return jsonDecodeToString(context = context, offset = jsonSpanOffset(span), length = length)
}

/**
 * The text of a span that has escapes and whose text is ASCII (span bit 30 is clear). The bytes are copied to the
 * byte scratch one scanner block at a time without a test per byte; a block with a backslash is cut there, the char
 * of the escape is written and the copy goes on behind it. The result is built from the scratch bytes with one more
 * copy. The scan has checked the escapes, so the returns through [jsonDecodeToString] are not reached; they only keep
 * this function total.
 */
private fun jsonEscapedAsciiString(
    context: JsonReadContext,
    offset: Int,
    length: Int,
): String {
    val bytes = context.bytes
    val end = offset + length
    val scan = JsonScans.active
    val width = scan.laneCount
    val outputStart = context.scratchTop
    var scratch = context.scratch
    if (scratch.size - outputStart < length + width) {
        scratch = scratch.copyOf(maxOf(outputStart + length + width, scratch.size * JSON_GROWTH_FACTOR))
        context.scratch = scratch
    }
    var output = outputStart
    var index = offset
    while (index < end) {
        if (end - index >= width) {
            val escapes = scan.copyBlock(source = bytes, sourceOffset = index, target = scratch, targetOffset = output)
            if (escapes == 0L) {
                index += width
                output += width
                continue
            }
            val run = java.lang.Long.numberOfTrailingZeros(escapes)
            index += run
            output += run
        } else {
            while (index < end && bytes[index] != JSON_BACKSLASH) {
                scratch[output++] = bytes[index]
                index++
            }
            if (index >= end) {
                break
            }
        }
        if (end - index < JSON_ESCAPE_PREFIX_LENGTH) {
            return jsonDecodeToString(context = context, offset = offset, length = length)
        }
        val letter = bytes[index + 1]
        if (letter != JSON_LETTER_U) {
            scratch[output++] = jsonSimpleEscapeByte(letter)
            index += JSON_ESCAPE_PREFIX_LENGTH
        } else {
            if (end - index < JSON_UNICODE_ESCAPE_LENGTH) {
                return jsonDecodeToString(context = context, offset = offset, length = length)
            }
            val codePoint = jsonHexDigits(bytes = bytes, index = index + JSON_ESCAPE_PREFIX_LENGTH)
            if (codePoint !in 0..JSON_ASCII_MAX) {
                return jsonDecodeToString(context = context, offset = offset, length = length)
            }
            scratch[output++] = codePoint.toByte()
            index += JSON_UNICODE_ESCAPE_LENGTH
        }
    }
    return jsonAsciiString(bytes = scratch, offset = outputStart, length = output - outputStart)
}

// The constructor with a high byte makes exactly one copy and no intermediate String (the Charset constructors
// build a second String object on this JDK), so the deprecation is accepted here.
@Suppress("DEPRECATION", "PLATFORM_CLASS_MAPPED_TO_KOTLIN")
private inline fun jsonAsciiString(
    bytes: ByteArray,
    offset: Int,
    length: Int,
): String = java.lang.String(bytes, 0, offset, length) as String

@PublishedApi
internal inline fun jsonEachMember(
    context: JsonReadContext,
    objectOffset: Int,
    filter: JsonSchema?,
    block: (key: Long, value: Int) -> Unit,
) {
    var member = jsonMembersFirst(context = context, objectOffset = objectOffset, filter = filter)
    while (member >= 0) {
        val key = jsonMemberKey(context = context, memberOffset = member)
        val value = jsonMemberValue(context = context, key = key)
        block(key, value)
        member = jsonMembersNext(context = context, valueOffset = value, filter = filter)
    }
}

private inline fun <R> jsonAt(
    context: JsonReadContext,
    offset: Int,
    block: () -> R,
): R {
    val saved = context.cursor
    context.cursor = offset
    val result = block()
    context.cursor = saved
    return result
}

@PublishedApi
internal fun jsonValueKind(context: JsonReadContext, offset: Int): Int = when (context.bytes[offset]) {
    JSON_QUOTE -> JSON_VALUE_STRING
    JSON_OBJECT_OPEN -> JSON_VALUE_OBJECT
    JSON_ARRAY_OPEN -> JSON_VALUE_ARRAY
    JSON_TRUE_START, JSON_FALSE_START -> JSON_VALUE_BOOL
    JSON_NULL_START -> JSON_VALUE_NULL
    else -> JSON_VALUE_NUMBER
}

@PublishedApi
internal fun jsonValueInt(
    context: JsonReadContext,
    offset: Int,
): Int = jsonAt(context = context, offset = offset) { jsonParseInt(context) }

@PublishedApi
internal fun jsonValueLong(
    context: JsonReadContext,
    offset: Int,
): Long = jsonAt(context = context, offset = offset) { jsonParseLong(context) }

@PublishedApi
internal fun jsonValueFloat(
    context: JsonReadContext,
    offset: Int,
): Float = jsonAt(context = context, offset = offset) { jsonParseFloat(context) }

@PublishedApi
internal fun jsonValueDouble(
    context: JsonReadContext,
    offset: Int,
): Double = jsonAt(context = context, offset = offset) { jsonParseDouble(context) }

@PublishedApi
internal fun jsonValueBool(
    context: JsonReadContext,
    offset: Int,
): Boolean = jsonAt(context = context, offset = offset) { jsonParseBool(context) }

@PublishedApi
internal fun jsonValueStringSpan(
    context: JsonReadContext,
    offset: Int,
): Long {
    val span = jsonAt(context = context, offset = offset) { jsonScanString(context) }
    context.lastStringOffset = offset
    context.lastStringEnd = jsonSpanEnd(span)
    return span
}

@PublishedApi
internal fun jsonValueEnd(context: JsonReadContext, offset: Int): Int = jsonAt(context = context, offset = offset) {
    jsonSkipValue(context)
    context.cursor
}

internal fun <T> jsonReadValueAt(
    context: JsonReadContext,
    reader: JsonReader<T>,
    offset: Int,
): T = jsonAt(context = context, offset = offset) { reader.readValue(context) }

internal fun <T> jsonReadListAt(
    context: JsonReadContext,
    reader: JsonReader<T>,
    offset: Int,
): List<T> = jsonAt(context = context, offset = offset) { reader.readElements(context) }

private fun jsonLoadWord(bytes: ByteArray, offset: Int): Long = jsonLongView.get(bytes, offset) as Long

private fun jsonKeyWord(key: ByteArray): Long {
    val padded = if (key.size >= Long.SIZE_BYTES) {
        key
    } else {
        key.copyOf(Long.SIZE_BYTES)
    }
    return jsonLoadWord(bytes = padded, offset = 0)
}

private fun jsonNeedsSlowKeyPath(key: ByteArray): Boolean {
    for (byte in key) {
        if (byte == JSON_QUOTE || byte == JSON_BACKSLASH || (byte in 0..<JSON_FIRST_PRINTABLE)) {
            return true
        }
    }
    return false
}

private fun jsonKeyFirstWord(
    bytes: ByteArray,
    start: Int,
    length: Int,
    readLimit: Int,
): Long {
    if (length >= Long.SIZE_BYTES) {
        return jsonLoadWord(bytes = bytes, offset = start)
    }
    if (readLimit - start >= Long.SIZE_BYTES) {
        return jsonLoadWord(bytes = bytes, offset = start) and ((1L shl (length shl JSON_SWAR_BYTE_SHIFT)) - 1L)
    }
    var word = 0L
    for (index in length - 1 downTo 0) {
        word = (word shl JSON_BITS_PER_BYTE) or (bytes[start + index].toLong() and JSON_BYTE_MASK.toLong())
    }
    return word
}

private fun jsonKeyLastWord(
    bytes: ByteArray,
    end: Int,
    length: Int,
): Long = if (length >= Long.SIZE_BYTES) {
    jsonLoadWord(bytes = bytes, offset = end - Long.SIZE_BYTES)
} else {
    0L
}

private fun jsonKeyHash(
    first: Long,
    last: Long,
    length: Int,
): Int {
    var hash = (first xor (length.toLong() shl JSON_HASH_LENGTH_SHIFT)) * JSON_HASH_MULTIPLIER
    hash = (hash xor (hash ushr JSON_HASH_SHIFT) xor last) * JSON_HASH_SECOND_MULTIPLIER
    return (hash xor (hash ushr JSON_HASH_SHIFT)).toInt()
}

private fun jsonBuildKeyTable(
    schema: JsonSchema,
    named: BooleanArray,
): IntArray {
    var count = 0
    for (field in named.indices) {
        if (named[field]) {
            count++
        }
    }
    if (count == 0) {
        return IntArray(1)
    }
    val size = Integer.highestOneBit(count * JSON_KEY_TABLE_LOAD - 1) shl 1
    val mask = size - 1
    val table = IntArray(size)
    for (field in named.indices) {
        if (named[field]) {
            val hash = jsonKeyHash(
                first = schema.keyWords[field],
                last = schema.keyLast[field],
                length = schema.keyBytes[field].size,
            )
            var slot = hash and mask
            while (table[slot] != 0) {
                slot = (slot + 1) and mask
            }
            table[slot] = field + 1
        }
    }
    return table
}

private fun jsonLookupKey(
    schema: JsonSchema,
    bytes: ByteArray,
    start: Int,
    end: Int,
    readLimit: Int,
): Int {
    val length = end - start
    val first = jsonKeyFirstWord(bytes = bytes, start = start, length = length, readLimit = readLimit)
    val last = jsonKeyLastWord(bytes = bytes, end = end, length = length)
    val table = schema.keyTable
    val mask = table.size - 1
    var slot = jsonKeyHash(first = first, last = last, length = length) and mask
    while (true) {
        val entry = table[slot]
        if (entry == 0) {
            return JSON_NO_FIELD
        }
        val field = entry - 1
        val key = schema.keyBytes[field]
        if (key.size == length &&
            schema.keyWords[field] == first &&
            (length <= Long.SIZE_BYTES || schema.keyLast[field] == last) &&
            (
                    length <= JSON_KEY_WORD_BYTES_TWICE ||
                            Arrays.equals(
                                bytes,
                                start + Long.SIZE_BYTES,
                                end - Long.SIZE_BYTES,
                                key,
                                Long.SIZE_BYTES,
                                length - Long.SIZE_BYTES,
                            )
                    )
        ) {
            return field
        }
        slot = (slot + 1) and mask
    }
}

private fun jsonLookupSpan(
    context: JsonReadContext,
    schema: JsonSchema,
    span: Long,
): Int {
    val offset = jsonSpanOffset(span)
    val length = jsonSpanLength(span)
    if (!jsonSpanEscaped(span)) {
        return jsonLookupKey(
            schema = schema,
            bytes = context.bytes,
            start = offset,
            end = offset + length,
            readLimit = context.limit,
        )
    }
    val mark = context.scratchTop
    val decoded = jsonDecodeEscapes(context = context, offset = offset, rawLength = length)
    val field = jsonLookupKey(
        schema = schema,
        bytes = context.borrowBytes,
        start = context.borrowOffset,
        end = context.borrowOffset + decoded,
        readLimit = context.borrowBytes.size,
    )
    context.scratchTop = mark
    return field
}

private fun jsonIsWhitespace(value: Byte): Boolean {
    val code = value.toInt() and JSON_BYTE_MASK
    return code <= JSON_SPACE_CODE && ((JSON_WHITESPACE_BITS ushr code) and 1L) != 0L
}

private fun jsonIsDelimiter(value: Byte): Boolean = value == JSON_COMMA ||
        value == JSON_ARRAY_CLOSE ||
        value == JSON_OBJECT_CLOSE ||
        jsonIsWhitespace(value)

private fun jsonIsDigit(offsetFromZero: Int): Boolean = offsetFromZero in 0..JSON_MAX_DIGIT

private fun jsonSkipWs(
    bytes: ByteArray,
    index: Int,
    limit: Int,
): Int {
    var current = index
    while (current < limit && jsonIsWhitespace(bytes[current])) {
        current++
    }
    return current
}

private fun jsonSkipWhitespace(context: JsonReadContext) {
    context.cursor = jsonSkipWs(bytes = context.bytes, index = context.cursor, limit = context.limit)
}

private fun jsonFailEnd(limit: Int): Nothing = jsonFail(error = JsonErrorType.UNEXPECTED_END, offset = limit)

@PublishedApi
internal fun jsonOpenObject(context: JsonReadContext): Boolean {
    val bytes = context.bytes
    val limit = context.limit
    val open = context.cursor
    if (bytes[open] != JSON_OBJECT_OPEN) {
        jsonFailTypedToken(bytes = bytes, start = open, limit = limit)
    }
    val first = jsonSkipWs(bytes = bytes, index = open + 1, limit = limit)
    if (first >= limit) {
        jsonFailEnd(limit)
    }
    val value = bytes[first]
    if (value == JSON_OBJECT_CLOSE) {
        context.cursor = first + 1
        return false
    }
    if (value != JSON_QUOTE) {
        jsonFail(error = JsonErrorType.UNEXPECTED_BYTE, offset = first)
    }
    context.cursor = first
    return true
}

@PublishedApi
internal fun jsonNextMember(context: JsonReadContext): Boolean {
    val bytes = context.bytes
    val limit = context.limit
    var index = context.cursor
    if (limit - index >= 2 && bytes[index] == JSON_COMMA && bytes[index + 1] == JSON_QUOTE) {
        context.cursor = index + 1
        return true
    }
    index = jsonSkipWs(bytes = bytes, index = index, limit = limit)
    if (index >= limit) {
        jsonFailEnd(limit)
    }
    val separator = bytes[index]
    if (separator == JSON_OBJECT_CLOSE) {
        context.cursor = index + 1
        return false
    }
    if (separator != JSON_COMMA) {
        jsonFail(error = JsonErrorType.UNEXPECTED_BYTE, offset = index)
    }
    index = jsonSkipWs(bytes = bytes, index = index + 1, limit = limit)
    if (index >= limit) {
        jsonFailEnd(limit)
    }
    val next = bytes[index]
    if (next == JSON_QUOTE) {
        context.cursor = index
        return true
    }
    if (next != JSON_OBJECT_CLOSE) {
        jsonFail(error = JsonErrorType.UNEXPECTED_BYTE, offset = index)
    }
    jsonOnTrailingComma(index)
    context.cursor = index + 1
    return false
}

@PublishedApi
internal fun jsonOpenArray(context: JsonReadContext): Boolean {
    val bytes = context.bytes
    val limit = context.limit
    val open = context.cursor
    if (bytes[open] != JSON_ARRAY_OPEN) {
        jsonFailTypedToken(bytes = bytes, start = open, limit = limit)
    }
    val first = jsonSkipWs(bytes = bytes, index = open + 1, limit = limit)
    if (first >= limit) {
        jsonFailEnd(limit)
    }
    if (bytes[first] == JSON_ARRAY_CLOSE) {
        context.cursor = first + 1
        return false
    }
    context.cursor = first
    return true
}

@PublishedApi
internal fun jsonNextElement(context: JsonReadContext): Boolean {
    val bytes = context.bytes
    val limit = context.limit
    var index = context.cursor
    if (limit - index >= 2 && bytes[index] == JSON_COMMA && !jsonIsWhitespace(bytes[index + 1])) {
        if (bytes[index + 1] != JSON_ARRAY_CLOSE) {
            context.cursor = index + 1
            return true
        }
    }
    index = jsonSkipWs(bytes = bytes, index = index, limit = limit)
    if (index >= limit) {
        jsonFailEnd(limit)
    }
    val separator = bytes[index]
    if (separator == JSON_ARRAY_CLOSE) {
        context.cursor = index + 1
        return false
    }
    if (separator != JSON_COMMA) {
        jsonFail(error = JsonErrorType.UNEXPECTED_BYTE, offset = index)
    }
    index = jsonSkipWs(bytes = bytes, index = index + 1, limit = limit)
    if (index >= limit) {
        jsonFailEnd(limit)
    }
    if (bytes[index] == JSON_ARRAY_CLOSE) {
        jsonOnTrailingComma(index)
        context.cursor = index + 1
        return false
    }
    context.cursor = index
    return true
}

private fun jsonLiteralTailEnd(
    bytes: ByteArray,
    tailStart: Int,
    limit: Int,
    tail: ByteArray,
): Int {
    for (offset in tail.indices) {
        val position = tailStart + offset
        if (position >= limit) {
            jsonFailEnd(limit)
        }
        if (bytes[position] != tail[offset]) {
            jsonFail(error = JsonErrorType.INVALID_LITERAL, offset = position)
        }
    }
    val end = tailStart + tail.size
    if (end < limit && !jsonIsDelimiter(bytes[end])) {
        jsonFail(error = JsonErrorType.INVALID_LITERAL, offset = end)
    }
    return end
}

private fun jsonLiteralEnd(
    bytes: ByteArray,
    start: Int,
    limit: Int,
): Int = when (bytes[start]) {
    JSON_TRUE_START -> jsonLiteralTailEnd(bytes = bytes, tailStart = start + 1, limit = limit, tail = jsonTrueTail)
    JSON_FALSE_START -> jsonLiteralTailEnd(bytes = bytes, tailStart = start + 1, limit = limit, tail = jsonFalseTail)
    JSON_NULL_START -> jsonLiteralTailEnd(bytes = bytes, tailStart = start + 1, limit = limit, tail = jsonNullTail)
    else -> jsonFail(error = JsonErrorType.UNEXPECTED_BYTE, offset = start)
}

@PublishedApi
internal fun jsonConsumeNull(context: JsonReadContext): Boolean {
    val bytes = context.bytes
    val start = context.cursor
    if (bytes[start] != JSON_NULL_START) {
        return false
    }
    context.cursor = jsonLiteralTailEnd(
        bytes = bytes,
        tailStart = start + 1,
        limit = context.limit,
        tail = jsonNullTail,
    )
    return true
}

@PublishedApi
internal fun jsonParseBool(context: JsonReadContext): Boolean {
    val bytes = context.bytes
    val start = context.cursor
    val first = bytes[start]
    if (first == JSON_TRUE_START) {
        context.cursor = jsonLiteralTailEnd(
            bytes = bytes,
            tailStart = start + 1,
            limit = context.limit,
            tail = jsonTrueTail,
        )
        return true
    }
    if (first == JSON_FALSE_START) {
        context.cursor = jsonLiteralTailEnd(
            bytes = bytes,
            tailStart = start + 1,
            limit = context.limit,
            tail = jsonFalseTail,
        )
        return false
    }
    jsonFailTypedToken(bytes = bytes, start = start, limit = context.limit)
}

private fun jsonKeyColon(
    bytes: ByteArray,
    limit: Int,
    afterQuote: Int,
): Int {
    var index = afterQuote
    if (limit - index >= 2 && bytes[index] == JSON_COLON && bytes[index + 1] > JSON_FIRST_PRINTABLE_BYTE) {
        return index + 1
    }
    index = jsonSkipWs(bytes = bytes, index = index, limit = limit)
    if (index >= limit) {
        jsonFailEnd(limit)
    }
    if (bytes[index] != JSON_COLON) {
        jsonFail(error = JsonErrorType.UNEXPECTED_BYTE, offset = index)
    }
    index = jsonSkipWs(bytes = bytes, index = index + 1, limit = limit)
    if (index >= limit) {
        jsonFailEnd(limit)
    }
    return index
}

private fun jsonFinishKey(context: JsonReadContext, afterQuote: Int) {
    context.cursor = jsonKeyColon(bytes = context.bytes, limit = context.limit, afterQuote = afterQuote)
}

@PublishedApi
internal fun jsonMatchKey(
    context: JsonReadContext,
    schema: JsonSchema,
    expected: Int,
): Int {
    val bytes = context.bytes
    val limit = context.limit
    val quote = context.cursor
    context.lastKeyOffset = quote
    val start = quote + 1
    if (expected < schema.fieldCount && limit - start >= Long.SIZE_BYTES) {
        val length = schema.keyLengths[expected]
        if (length >= 0 && limit - start > length) {
            val word = jsonLoadWord(bytes = bytes, offset = start)
            val mask = if (length >= Long.SIZE_BYTES) {
                -1L
            } else {
                (1L shl (length shl JSON_SWAR_BYTE_SHIFT)) - 1L
            }
            if ((word and mask) == schema.keyWords[expected] &&
                bytes[start + length] == JSON_QUOTE &&
                (
                        length <= Long.SIZE_BYTES ||
                                (
                                        jsonLoadWord(bytes = bytes, offset = start + length - Long.SIZE_BYTES) ==
                                                schema.keyLast[expected] &&
                                                (
                                                        length <= JSON_KEY_WORD_BYTES_TWICE ||
                                                                Arrays.equals(
                                                                    bytes,
                                                                    start + Long.SIZE_BYTES,
                                                                    start + length - Long.SIZE_BYTES,
                                                                    schema.keyBytes[expected],
                                                                    Long.SIZE_BYTES,
                                                                    length - Long.SIZE_BYTES,
                                                                )
                                                        )
                                        )
                        )
            ) {
                jsonFinishKey(context = context, afterQuote = start + length + 1)
                return expected
            }
        }
    }
    val span = jsonStringSpan(context = context, quote = quote)
    val field = jsonLookupSpan(context = context, schema = schema, span = span)
    jsonFinishKey(context = context, afterQuote = jsonSpanOffset(span) + jsonSpanLength(span) + 1)
    return field
}

private fun jsonStartsNonFinite(
    bytes: ByteArray,
    start: Int,
    limit: Int,
): Boolean {
    val first = bytes[start]
    return first == JSON_NAN_START ||
            first == JSON_INFINITY_START ||
            (first == JSON_MINUS && start + 1 < limit && bytes[start + 1] == JSON_INFINITY_START)
}

private fun jsonNonFiniteEnd(
    bytes: ByteArray,
    start: Int,
    limit: Int,
): Int {
    jsonOnNonFiniteLiteral(start)
    val letter = if (bytes[start] == JSON_MINUS) {
        start + 1
    } else {
        start
    }
    val tail = if (bytes[letter] == JSON_NAN_START) {
        jsonNanTail
    } else {
        jsonInfinityTail
    }
    return jsonLiteralTailEnd(bytes = bytes, tailStart = letter + 1, limit = limit, tail = tail)
}

/** The end of a number or literal token as the validating skip sees it; it depends on no skip option. */
private fun jsonScalarTokenEnd(
    bytes: ByteArray,
    start: Int,
    limit: Int,
): Int = when (bytes[start]) {
    JSON_TRUE_START, JSON_FALSE_START, JSON_NULL_START -> jsonLiteralEnd(bytes = bytes, start = start, limit = limit)
    else -> if (jsonStartsNonFinite(bytes = bytes, start = start, limit = limit)) {
        jsonNonFiniteEnd(bytes = bytes, start = start, limit = limit)
    } else if (bytes[start] == JSON_MINUS || jsonIsDigit(bytes[start] - JSON_ZERO)) {
        jsonScanNumberValue(bytes = bytes, start = start, limit = limit)
    } else {
        jsonFail(error = JsonErrorType.UNEXPECTED_BYTE, offset = start)
    }
}

/**
 * Grammar comes before type: a typed parse that cannot accept the token at [start] reports the grammar error of the
 * validating skip when the token is a malformed number or literal, and the type error otherwise. Failure path only.
 */
private fun jsonFailTypedToken(
    bytes: ByteArray,
    start: Int,
    limit: Int,
): Nothing {
    when (bytes[start]) {
        JSON_QUOTE, JSON_OBJECT_OPEN, JSON_ARRAY_OPEN -> jsonFail(error = JsonErrorType.TYPE_MISMATCH, offset = start)
        else -> jsonScalarTokenEnd(bytes = bytes, start = start, limit = limit)
    }
    jsonFail(error = JsonErrorType.TYPE_MISMATCH, offset = start)
}

private fun jsonFailRange(
    bytes: ByteArray,
    start: Int,
    limit: Int,
    quoted: Boolean,
): Nothing {
    val end = jsonScanNumberGrammar(bytes = bytes, start = start, limit = limit)
    if (!quoted && end < limit && !jsonIsDelimiter(bytes[end])) {
        jsonFail(error = JsonErrorType.INVALID_NUMBER, offset = end)
    }
    jsonFail(error = JsonErrorType.NUMBER_OUT_OF_RANGE, offset = start)
}

private fun jsonRequireDelimiterAt(
    bytes: ByteArray,
    limit: Int,
    index: Int,
) {
    if (index < limit && !jsonIsDelimiter(bytes[index])) {
        jsonFail(error = JsonErrorType.INVALID_NUMBER, offset = index)
    }
}

private fun jsonRequireNumberDelimiter(context: JsonReadContext, index: Int) {
    jsonRequireDelimiterAt(bytes = context.bytes, limit = context.limit, index = index)
}

private fun jsonQuotedNumberStart(context: JsonReadContext): Int {
    val bytes = context.bytes
    val limit = context.limit
    val quote = context.cursor
    jsonOnQuotedNumber(quote)
    val number = quote + 1
    if (number >= limit) {
        jsonFailEnd(limit)
    }
    val digit = if (bytes[number] == JSON_MINUS) {
        number + 1
    } else {
        number
    }
    if (digit >= limit) {
        jsonFailEnd(limit)
    }
    if (!jsonIsDigit(bytes[digit] - JSON_ZERO)) {
        jsonFail(error = JsonErrorType.INVALID_NUMBER, offset = digit)
    }
    return number
}

private fun jsonQuotedNumberEnd(context: JsonReadContext, end: Int) {
    if (end >= context.limit) {
        jsonFailEnd(context.limit)
    }
    if (context.bytes[end] != JSON_QUOTE) {
        jsonFail(error = JsonErrorType.INVALID_NUMBER, offset = end)
    }
    jsonRequireNumberDelimiter(context = context, index = end + 1)
    context.cursor = end + 1
}

private fun jsonReadIntegerDigits(
    context: JsonReadContext,
    start: Int,
    quoted: Boolean,
): Long {
    val bytes = context.bytes
    val limit = context.limit
    var index = start
    val negative = bytes[index] == JSON_MINUS
    if (negative) {
        index++
        if (index >= limit) {
            jsonFailEnd(limit)
        }
    }
    val digitsStart = index
    var magnitude: Long
    var more = true
    if (limit - index >= JSON_PACKED_DIGITS) {
        val word = jsonLoadWord(bytes = bytes, offset = index)
        val count = jsonDigitCount(word)
        if (count == 0) {
            jsonFailTypedToken(bytes = bytes, start = start, limit = limit)
        }
        if (count > 1 && bytes[index] == JSON_ZERO_BYTE) {
            jsonFail(error = JsonErrorType.INVALID_NUMBER, offset = index + 1)
        }
        magnitude = jsonDigitsValue(word = word, count = count)
        if (count < JSON_PACKED_DIGITS) {
            index += count
            more = false
        } else {
            index += JSON_PACKED_DIGITS
            if (limit - index >= JSON_PACKED_DIGITS) {
                val second = jsonLoadWord(bytes = bytes, offset = index)
                val secondCount = jsonDigitCount(second)
                if (secondCount > 0) {
                    magnitude = magnitude * jsonLongPowersOfTen[secondCount] +
                            jsonDigitsValue(word = second, count = secondCount)
                }
                if (secondCount < JSON_PACKED_DIGITS) {
                    index += secondCount
                    more = false
                } else {
                    index += JSON_PACKED_DIGITS
                    if (limit - index >= JSON_PACKED_DIGITS) {
                        val third = jsonLoadWord(bytes = bytes, offset = index)
                        val thirdCount = jsonDigitCount(third)
                        if (thirdCount > JSON_LONG_DIGITS - 2 * JSON_PACKED_DIGITS) {
                            jsonFailRange(bytes = bytes, start = start, limit = limit, quoted = quoted)
                        }
                        if (thirdCount > 0) {
                            magnitude = magnitude * jsonLongPowersOfTen[thirdCount] +
                                    jsonDigitsValue(word = third, count = thirdCount)
                        }
                        index += thirdCount
                        more = false
                    }
                }
            }
        }
    } else {
        val first = bytes[index] - JSON_ZERO
        if (!jsonIsDigit(first)) {
            jsonFailTypedToken(bytes = bytes, start = start, limit = limit)
        }
        magnitude = first.toLong()
        index++
        if (first == 0) {
            if (index < limit && jsonIsDigit(bytes[index] - JSON_ZERO)) {
                jsonFail(error = JsonErrorType.INVALID_NUMBER, offset = index)
            }
            more = false
        }
    }
    if (more) {
        while (index < limit) {
            val digit = bytes[index] - JSON_ZERO
            if (!jsonIsDigit(digit)) {
                break
            }
            if (index - digitsStart >= JSON_LONG_DIGITS) {
                jsonFailRange(bytes = bytes, start = start, limit = limit, quoted = quoted)
            }
            magnitude = magnitude * JSON_DECIMAL_BASE + digit
            index++
        }
    }
    context.cursor = index
    val maximum = if (negative) {
        Long.MIN_VALUE
    } else {
        Long.MAX_VALUE
    }
    if (java.lang.Long.compareUnsigned(magnitude, maximum) > 0) {
        jsonFailRange(bytes = bytes, start = start, limit = limit, quoted = quoted)
    }
    return if (negative) {
        -magnitude
    } else {
        magnitude
    }
}

private fun jsonPackedValue(digits: Long): Long {
    val pairs = (digits * JSON_PAIR_SCALE + (digits ushr JSON_PAIR_SHIFT)) and JSON_PAIR_MASK
    val quads = (pairs * JSON_QUAD_SCALE + (pairs ushr JSON_QUAD_SHIFT)) and JSON_QUAD_MASK
    return (quads * JSON_OCTET_SCALE + (quads ushr JSON_OCTET_SHIFT)) and JSON_LOW_HALF_MASK
}

private fun jsonDigitCount(word: Long): Int {
    val flags = ((word + JSON_DIGIT_CHECK_ADD) or (word - JSON_DIGIT_ASCII_ZEROS)) and JSON_SWAR_HIGH_BITS
    return java.lang.Long.numberOfTrailingZeros(flags) ushr JSON_SWAR_BYTE_SHIFT
}

private fun jsonDigitsValue(word: Long, count: Int): Long = jsonPackedValue(
    (word - JSON_DIGIT_ASCII_ZEROS) shl ((JSON_PACKED_DIGITS - count) shl JSON_SWAR_BYTE_SHIFT),
)

private fun jsonScanNumberGrammar(
    bytes: ByteArray,
    start: Int,
    limit: Int,
): Int {
    var index = start
    if (bytes[index] == JSON_MINUS) {
        index++
        if (index >= limit) {
            jsonFailEnd(limit)
        }
    }
    val first = bytes[index] - JSON_ZERO
    if (!jsonIsDigit(first)) {
        jsonFail(error = JsonErrorType.INVALID_NUMBER, offset = index)
    }
    index++
    if (first == 0) {
        if (index < limit && jsonIsDigit(bytes[index] - JSON_ZERO)) {
            jsonFail(error = JsonErrorType.INVALID_NUMBER, offset = index)
        }
    } else {
        index = jsonSkipDigits(bytes = bytes, start = index, limit = limit)
    }
    if (index < limit && bytes[index] == JSON_DOT) {
        index = jsonScanDigits(bytes = bytes, start = index + 1, limit = limit)
    }
    if (index < limit && (bytes[index] == JSON_EXPONENT_LOWER || bytes[index] == JSON_EXPONENT_UPPER)) {
        index++
        if (index < limit && (bytes[index] == JSON_MINUS || bytes[index] == JSON_PLUS)) {
            index++
        }
        index = jsonScanDigits(bytes = bytes, start = index, limit = limit)
    }
    return index
}

private fun jsonScanDigits(
    bytes: ByteArray,
    start: Int,
    limit: Int,
): Int {
    if (start >= limit) {
        jsonFailEnd(limit)
    }
    if (!jsonIsDigit(bytes[start] - JSON_ZERO)) {
        jsonFail(error = JsonErrorType.INVALID_NUMBER, offset = start)
    }
    return jsonSkipDigits(bytes = bytes, start = start + 1, limit = limit)
}

private fun jsonSkipDigits(
    bytes: ByteArray,
    start: Int,
    limit: Int,
): Int {
    var index = start
    while (limit - index >= JSON_PACKED_DIGITS) {
        val word = jsonLoadWord(bytes = bytes, offset = index)
        val flags = ((word + JSON_DIGIT_CHECK_ADD) or (word - JSON_DIGIT_ASCII_ZEROS)) and JSON_SWAR_HIGH_BITS
        if (flags != 0L) {
            return index + (java.lang.Long.numberOfTrailingZeros(flags) ushr JSON_SWAR_BYTE_SHIFT)
        }
        index += JSON_PACKED_DIGITS
    }
    while (index < limit && jsonIsDigit(bytes[index] - JSON_ZERO)) {
        index++
    }
    return index
}

private fun jsonScanNumberValue(
    bytes: ByteArray,
    start: Int,
    limit: Int,
): Int {
    val end = jsonScanNumberGrammar(bytes = bytes, start = start, limit = limit)
    if (end < limit && !jsonIsDelimiter(bytes[end])) {
        jsonFail(error = JsonErrorType.INVALID_NUMBER, offset = end)
    }
    return end
}

private fun jsonDecimalFormInteger(context: JsonReadContext, start: Int): Long {
    val end = jsonScanNumberGrammar(bytes = context.bytes, start = start, limit = context.limit)
    if (end - start > JSON_DECIMAL_FORM_MAX_LENGTH) {
        jsonFail(error = JsonErrorType.NUMBER_OUT_OF_RANGE, offset = start)
    }
    val decimal = try {
        java.math.BigDecimal(String(context.bytes, start, end - start, Charsets.ISO_8859_1))
    } catch (_: NumberFormatException) {
        jsonFail(error = JsonErrorType.NUMBER_OUT_OF_RANGE, offset = start)
    }
    context.cursor = end
    if (decimal.signum() == 0) {
        return 0L
    }
    if (decimal.precision() - decimal.scale() > JSON_DECIMAL_FORM_MAX_DIGITS) {
        jsonFail(error = JsonErrorType.NUMBER_OUT_OF_RANGE, offset = start)
    }
    if (decimal.stripTrailingZeros().scale() > 0) {
        jsonFail(error = JsonErrorType.NOT_AN_INTEGER, offset = start)
    }
    val integer = decimal.toBigIntegerExact()
    if (integer.bitLength() > JSON_LONG_MAGNITUDE_BITS) {
        jsonFail(error = JsonErrorType.NUMBER_OUT_OF_RANGE, offset = start)
    }
    return integer.toLong()
}

private fun jsonIntegerAfterDigits(context: JsonReadContext, start: Int): Long {
    val next = context.bytes[context.cursor]
    if (next != JSON_DOT && next != JSON_EXPONENT_LOWER && next != JSON_EXPONENT_UPPER) {
        jsonFail(error = JsonErrorType.INVALID_NUMBER, offset = context.cursor)
    }
    jsonScanNumberValue(bytes = context.bytes, start = start, limit = context.limit)
    jsonOnDecimalFormInt(start)
    val value = jsonDecimalFormInteger(context = context, start = start)
    jsonRequireNumberDelimiter(context = context, index = context.cursor)
    return value
}

private fun jsonIntegerValue(context: JsonReadContext, start: Int): Long {
    val value = jsonReadIntegerDigits(context = context, start = start, quoted = false)
    val index = context.cursor
    if (index < context.limit && !jsonIsDelimiter(context.bytes[index])) {
        return jsonIntegerAfterDigits(context = context, start = start)
    }
    return value
}

private fun jsonQuotedIntegerValue(context: JsonReadContext): Long {
    val number = jsonQuotedNumberStart(context)
    var value = jsonReadIntegerDigits(context = context, start = number, quoted = true)
    val index = context.cursor
    if (index < context.limit && context.bytes[index] != JSON_QUOTE) {
        val next = context.bytes[index]
        if (next != JSON_DOT && next != JSON_EXPONENT_LOWER && next != JSON_EXPONENT_UPPER) {
            jsonFail(error = JsonErrorType.INVALID_NUMBER, offset = index)
        }
        jsonOnDecimalFormInt(index)
        value = jsonDecimalFormInteger(context = context, start = number)
    }
    jsonQuotedNumberEnd(context = context, end = context.cursor)
    return value
}

private fun jsonSlowInteger(context: JsonReadContext, start: Int): Long {
    context.cursor = start
    return if (context.bytes[start] == JSON_QUOTE) {
        jsonQuotedIntegerValue(context)
    } else {
        jsonIntegerValue(context = context, start = start)
    }
}

private inline fun <R> jsonReadInteger(
    context: JsonReadContext,
    bytes: ByteArray,
    start: Int,
    limit: Int,
    result: (end: Int, value: Long) -> R,
): R {
    var end = 0
    var value = 0L
    val negative = bytes[start] == JSON_MINUS
    val digits = if (negative) {
        start + 1
    } else {
        start
    }
    if (limit - digits >= JSON_PACKED_DIGITS) {
        val word = jsonLoadWord(bytes = bytes, offset = digits)
        val flags = ((word + JSON_DIGIT_CHECK_ADD) or (word - JSON_DIGIT_ASCII_ZEROS)) and JSON_SWAR_HIGH_BITS
        val count = java.lang.Long.numberOfTrailingZeros(flags) ushr JSON_SWAR_BYTE_SHIFT
        if (flags != 0L) {
            if (count > 0 &&
                (count == 1 || bytes[digits] != JSON_ZERO_BYTE) &&
                jsonIsDelimiter(bytes[digits + count])
            ) {
                value = jsonPackedValue(
                    (word - JSON_DIGIT_ASCII_ZEROS) shl ((JSON_PACKED_DIGITS - count) shl JSON_SWAR_BYTE_SHIFT),
                )
                end = digits + count
            }
        } else if (limit - digits >= JSON_PACKED_DIGITS * 2 && bytes[digits] != JSON_ZERO_BYTE) {
            val second = jsonLoadWord(bytes = bytes, offset = digits + JSON_PACKED_DIGITS)
            val secondFlags = ((second + JSON_DIGIT_CHECK_ADD) or (second - JSON_DIGIT_ASCII_ZEROS)) and
                    JSON_SWAR_HIGH_BITS
            val secondCount = java.lang.Long.numberOfTrailingZeros(secondFlags) ushr JSON_SWAR_BYTE_SHIFT
            if (secondFlags != 0L && jsonIsDelimiter(bytes[digits + JSON_PACKED_DIGITS + secondCount])) {
                value = jsonPackedValue(word - JSON_DIGIT_ASCII_ZEROS)
                if (secondCount > 0) {
                    value = value * jsonLongPowersOfTen[secondCount] + jsonPackedValue(
                        (second - JSON_DIGIT_ASCII_ZEROS) shl
                                ((JSON_PACKED_DIGITS - secondCount) shl JSON_SWAR_BYTE_SHIFT),
                    )
                }
                end = digits + JSON_PACKED_DIGITS + secondCount
            }
        }
        if (end != 0 && negative) {
            value = -value
        }
    }
    if (end == 0) {
        value = jsonSlowInteger(context = context, start = start)
        end = context.cursor
    }
    return result(end, value)
}

private fun jsonIntegerToInt(value: Long, start: Int): Int {
    if (value < Int.MIN_VALUE || value > Int.MAX_VALUE) {
        jsonFail(error = JsonErrorType.NUMBER_OUT_OF_RANGE, offset = start)
    }
    return value.toInt()
}

@PublishedApi
internal fun jsonParseLong(context: JsonReadContext): Long = jsonReadInteger(
    context = context,
    bytes = context.bytes,
    start = context.cursor,
    limit = context.limit,
) { end, value ->
    context.cursor = end
    value
}

@PublishedApi
internal fun jsonParseInt(context: JsonReadContext): Int {
    val start = context.cursor
    return jsonReadInteger(
        context = context,
        bytes = context.bytes,
        start = start,
        limit = context.limit,
    ) { end, value ->
        context.cursor = end
        jsonIntegerToInt(value = value, start = start)
    }
}

private inline fun <R> jsonLexDecimal(
    bytes: ByteArray,
    start: Int,
    limit: Int,
    result: (end: Int, mantissa: Long, exponent: Int, truncated: Boolean) -> R,
): R {
    var index = start
    if (bytes[index] == JSON_MINUS) {
        index++
        if (index >= limit) {
            jsonFailEnd(limit)
        }
    }
    var digit = bytes[index] - JSON_ZERO
    if (!jsonIsDigit(digit)) {
        jsonFail(error = JsonErrorType.INVALID_NUMBER, offset = index)
    }
    var mantissa = 0L
    var exponent = 0L
    var truncated = false
    if (digit == 0) {
        index++
        if (index < limit && jsonIsDigit(bytes[index] - JSON_ZERO)) {
            jsonFail(error = JsonErrorType.INVALID_NUMBER, offset = index)
        }
    } else {
        mantissa = digit.toLong()
        index++
        while (index < limit) {
            if (limit - index >= JSON_PACKED_DIGITS) {
                val word = jsonLoadWord(bytes = bytes, offset = index)
                val count = jsonDigitCount(word)
                if (count == 0) {
                    break
                }
                if (java.lang.Long.compareUnsigned(mantissa, jsonMantissaRoom[count]) < 0) {
                    mantissa = mantissa * jsonLongPowersOfTen[count] + jsonDigitsValue(word = word, count = count)
                    index += count
                    if (count < JSON_PACKED_DIGITS) {
                        break
                    }
                    continue
                }
            }
            digit = bytes[index] - JSON_ZERO
            if (!jsonIsDigit(digit)) {
                break
            }
            if (java.lang.Long.compareUnsigned(mantissa, JSON_MANTISSA_LIMIT) < 0) {
                mantissa = mantissa * JSON_DECIMAL_BASE + digit
            } else {
                exponent++
                if (digit != 0) {
                    truncated = true
                }
            }
            index++
        }
    }
    if (index < limit && bytes[index] == JSON_DOT) {
        index++
        if (index >= limit) {
            jsonFailEnd(limit)
        }
        digit = bytes[index] - JSON_ZERO
        if (!jsonIsDigit(digit)) {
            jsonFail(error = JsonErrorType.INVALID_NUMBER, offset = index)
        }
        while (index < limit) {
            if (limit - index >= JSON_PACKED_DIGITS) {
                val word = jsonLoadWord(bytes = bytes, offset = index)
                val count = jsonDigitCount(word)
                if (count == 0) {
                    break
                }
                if (java.lang.Long.compareUnsigned(mantissa, jsonMantissaRoom[count]) < 0) {
                    mantissa = mantissa * jsonLongPowersOfTen[count] + jsonDigitsValue(word = word, count = count)
                    exponent -= count.toLong()
                    index += count
                    if (count < JSON_PACKED_DIGITS) {
                        break
                    }
                    continue
                }
            }
            digit = bytes[index] - JSON_ZERO
            if (!jsonIsDigit(digit)) {
                break
            }
            if (java.lang.Long.compareUnsigned(mantissa, JSON_MANTISSA_LIMIT) < 0) {
                mantissa = mantissa * JSON_DECIMAL_BASE + digit
                exponent--
            } else if (digit != 0) {
                truncated = true
            }
            index++
        }
    }
    if (index < limit && (bytes[index] == JSON_EXPONENT_LOWER || bytes[index] == JSON_EXPONENT_UPPER)) {
        index++
        if (index >= limit) {
            jsonFailEnd(limit)
        }
        var exponentNegative = false
        if (bytes[index] == JSON_MINUS) {
            exponentNegative = true
            index++
        } else if (bytes[index] == JSON_PLUS) {
            index++
        }
        if (index >= limit) {
            jsonFailEnd(limit)
        }
        digit = bytes[index] - JSON_ZERO
        if (!jsonIsDigit(digit)) {
            jsonFail(error = JsonErrorType.INVALID_NUMBER, offset = index)
        }
        var written = 0
        while (true) {
            if (written < JSON_EXPONENT_ACCUMULATE_LIMIT) {
                written = written * JSON_DECIMAL_BASE + digit
            }
            index++
            if (index >= limit) {
                break
            }
            digit = bytes[index] - JSON_ZERO
            if (!jsonIsDigit(digit)) {
                break
            }
        }
        exponent += if (exponentNegative) {
            -written.toLong()
        } else {
            written.toLong()
        }
    }
    return result(
        index,
        mantissa,
        exponent.coerceIn(-JSON_EXPONENT_CLAMP, JSON_EXPONENT_CLAMP).toInt(),
        truncated,
    )
}

private fun jsonFastDouble(mantissa: Long, exponent: Int): Double {
    if (mantissa < 0L) {
        return Double.NaN
    }
    if (exponent == 0) {
        return mantissa.toDouble()
    }
    if (mantissa > JSON_EXACT_MANTISSA_MAX) {
        return Double.NaN
    }
    if (exponent > 0) {
        if (exponent <= JSON_MAX_EXACT_POWER) {
            return mantissa.toDouble() * jsonPowersOfTen[exponent]
        }
        val shift = exponent - JSON_MAX_EXACT_POWER
        if (shift < JSON_LONG_POWER_COUNT) {
            val scale = jsonLongPowersOfTen[shift]
            if (mantissa <= JSON_EXACT_MANTISSA_MAX / scale) {
                return (mantissa * scale).toDouble() * jsonPowersOfTen[JSON_MAX_EXACT_POWER]
            }
        }
        return Double.NaN
    }
    if (exponent >= -JSON_MAX_EXACT_POWER) {
        return mantissa.toDouble() / jsonPowersOfTen[-exponent]
    }
    return Double.NaN
}

private fun jsonFastFloat(mantissa: Long, exponent: Int): Float {
    if (mantissa < 0L) {
        return Float.NaN
    }
    if (exponent == 0) {
        return mantissa.toFloat()
    }
    if (mantissa > JSON_FLOAT_EXACT_MANTISSA_MAX) {
        return Float.NaN
    }
    if (exponent > 0) {
        if (exponent <= JSON_FLOAT_MAX_EXACT_POWER) {
            return mantissa.toFloat() * jsonFloatPowersOfTen[exponent]
        }
        val shift = exponent - JSON_FLOAT_MAX_EXACT_POWER
        if (shift < JSON_FLOAT_MAX_EXACT_POWER) {
            val scale = jsonLongPowersOfTen[shift]
            if (mantissa <= JSON_FLOAT_EXACT_MANTISSA_MAX / scale) {
                return (mantissa * scale).toFloat() * jsonFloatPowersOfTen[JSON_FLOAT_MAX_EXACT_POWER]
            }
        }
        return Float.NaN
    }
    if (exponent >= -JSON_FLOAT_MAX_EXACT_POWER) {
        return mantissa.toFloat() / jsonFloatPowersOfTen[-exponent]
    }
    return Float.NaN
}

private fun jsonEiselLemire64(mantissa: Long, exponent: Int): Long {
    if (exponent !in JSON_POWER_MIN_EXPONENT..JSON_POWER_MAX_EXPONENT) {
        return JSON_NO_BITS
    }
    val clz = java.lang.Long.numberOfLeadingZeros(mantissa)
    val normalized = mantissa shl clz
    var exponent2 = ((JSON_LOG2_SCALE * exponent) shr JSON_LOG2_SHIFT) + JSON_WORD_BITS + JSON_DOUBLE_BIAS - clz
    val slot = exponent - JSON_POWER_MIN_EXPONENT
    var productHigh = Math.unsignedMultiplyHigh(normalized, JsonPowers.high[slot])
    var productLow = normalized * JsonPowers.high[slot]
    if ((productHigh and JSON_DOUBLE_ROUND_MASK) == JSON_DOUBLE_ROUND_MASK &&
        java.lang.Long.compareUnsigned(productLow + normalized, normalized) < 0
    ) {
        val wideHigh = Math.unsignedMultiplyHigh(normalized, JsonPowers.low[slot])
        val wideLow = normalized * JsonPowers.low[slot]
        var mergedHigh = productHigh
        val mergedLow = productLow + wideHigh
        if (java.lang.Long.compareUnsigned(mergedLow, productLow) < 0) {
            mergedHigh++
        }
        if ((mergedHigh and JSON_DOUBLE_ROUND_MASK) == JSON_DOUBLE_ROUND_MASK &&
            mergedLow == -1L &&
            java.lang.Long.compareUnsigned(wideLow + normalized, normalized) < 0
        ) {
            return JSON_NO_BITS
        }
        productHigh = mergedHigh
        productLow = mergedLow
    }
    val topBit = (productHigh ushr (Long.SIZE_BITS - 1)).toInt()
    var rounded = productHigh ushr (topBit + JSON_DOUBLE_SHIFT)
    exponent2 -= 1 xor topBit
    if (productLow == 0L && (productHigh and JSON_DOUBLE_ROUND_MASK) == 0L && (rounded and 3L) == 1L) {
        return JSON_NO_BITS
    }
    rounded += rounded and 1L
    rounded = rounded ushr 1
    if ((rounded ushr JSON_DOUBLE_MANTISSA_BITS) > 0L) {
        rounded = rounded ushr 1
        exponent2++
    }
    if (exponent2 !in 1..<JSON_DOUBLE_MAX_BIASED) {
        return JSON_NO_BITS
    }
    return (exponent2.toLong() shl (JSON_DOUBLE_MANTISSA_BITS - 1)) or (rounded and JSON_DOUBLE_FRACTION_MASK)
}

private fun jsonEiselLemire32(mantissa: Long, exponent: Int): Long {
    if (exponent !in JSON_POWER_MIN_EXPONENT..JSON_POWER_MAX_EXPONENT) {
        return JSON_NO_BITS
    }
    val clz = java.lang.Long.numberOfLeadingZeros(mantissa)
    val normalized = mantissa shl clz
    var exponent2 = ((JSON_LOG2_SCALE * exponent) shr JSON_LOG2_SHIFT) + JSON_WORD_BITS + JSON_FLOAT_BIAS - clz
    val slot = exponent - JSON_POWER_MIN_EXPONENT
    var productHigh = Math.unsignedMultiplyHigh(normalized, JsonPowers.high[slot])
    var productLow = normalized * JsonPowers.high[slot]
    if ((productHigh and JSON_FLOAT_ROUND_MASK) == JSON_FLOAT_ROUND_MASK &&
        java.lang.Long.compareUnsigned(productLow + normalized, normalized) < 0
    ) {
        val wideHigh = Math.unsignedMultiplyHigh(normalized, JsonPowers.low[slot])
        val wideLow = normalized * JsonPowers.low[slot]
        var mergedHigh = productHigh
        val mergedLow = productLow + wideHigh
        if (java.lang.Long.compareUnsigned(mergedLow, productLow) < 0) {
            mergedHigh++
        }
        if ((mergedHigh and JSON_FLOAT_ROUND_MASK) == JSON_FLOAT_ROUND_MASK &&
            mergedLow == -1L &&
            java.lang.Long.compareUnsigned(wideLow + normalized, normalized) < 0
        ) {
            return JSON_NO_BITS
        }
        productHigh = mergedHigh
        productLow = mergedLow
    }
    val topBit = (productHigh ushr (Long.SIZE_BITS - 1)).toInt()
    var rounded = productHigh ushr (topBit + JSON_FLOAT_SHIFT)
    exponent2 -= 1 xor topBit
    if (productLow == 0L && (productHigh and JSON_FLOAT_ROUND_MASK) == 0L && (rounded and 3L) == 1L) {
        return JSON_NO_BITS
    }
    rounded += rounded and 1L
    rounded = rounded ushr 1
    if ((rounded ushr JSON_FLOAT_MANTISSA_BITS) > 0L) {
        rounded = rounded ushr 1
        exponent2++
    }
    if (exponent2 !in 1..<JSON_FLOAT_MAX_BIASED) {
        return JSON_NO_BITS
    }
    return (exponent2.toLong() shl (JSON_FLOAT_MANTISSA_BITS - 1)) or (rounded and JSON_FLOAT_FRACTION_MASK)
}

private fun jsonDoubleMagnitude(
    mantissa: Long,
    exponent: Int,
    truncated: Boolean,
): Double {
    if (mantissa == 0L) {
        return 0.0
    }
    if (!truncated) {
        val fast = jsonFastDouble(mantissa = mantissa, exponent = exponent)
        if (!fast.isNaN()) {
            return fast
        }
    }
    if (exponent > JSON_POWER_MAX_EXPONENT) {
        return Double.POSITIVE_INFINITY
    }
    if (exponent < JSON_POWER_MIN_EXPONENT) {
        return 0.0
    }
    var bits = jsonEiselLemire64(mantissa = mantissa, exponent = exponent)
    if (bits != JSON_NO_BITS && truncated &&
        jsonEiselLemire64(mantissa = mantissa + 1, exponent = exponent) != bits
    ) {
        bits = JSON_NO_BITS
    }
    if (bits == JSON_NO_BITS) {
        return Double.NaN
    }
    return Double.fromBits(bits)
}

private fun jsonFloatMagnitude(
    mantissa: Long,
    exponent: Int,
    truncated: Boolean,
): Float {
    if (mantissa == 0L) {
        return 0f
    }
    if (!truncated) {
        val fast = jsonFastFloat(mantissa = mantissa, exponent = exponent)
        if (!fast.isNaN()) {
            return fast
        }
    }
    if (exponent > JSON_FLOAT_MAX_EXPONENT) {
        return Float.POSITIVE_INFINITY
    }
    if (exponent < JSON_FLOAT_MIN_EXPONENT) {
        return 0f
    }
    var bits = jsonEiselLemire32(mantissa = mantissa, exponent = exponent)
    if (bits != JSON_NO_BITS && truncated &&
        jsonEiselLemire32(mantissa = mantissa + 1, exponent = exponent) != bits
    ) {
        bits = JSON_NO_BITS
    }
    if (bits == JSON_NO_BITS) {
        return Float.NaN
    }
    return Float.fromBits(bits.toInt())
}

private fun jsonSlowDouble(
    bytes: ByteArray,
    start: Int,
    end: Int,
): Double {
    val text = String(bytes, start, end - start, Charsets.ISO_8859_1)
    val value = try {
        java.lang.Double.parseDouble(text)
    } catch (_: NumberFormatException) {
        jsonFail(error = JsonErrorType.INVALID_NUMBER, offset = start)
    }
    if (value.isInfinite()) {
        jsonFail(error = JsonErrorType.NUMBER_OUT_OF_RANGE, offset = start)
    }
    return value
}

private fun jsonSlowFloat(
    bytes: ByteArray,
    start: Int,
    end: Int,
): Float {
    val text = String(bytes, start, end - start, Charsets.ISO_8859_1)
    val value = try {
        java.lang.Float.parseFloat(text)
    } catch (_: NumberFormatException) {
        jsonFail(error = JsonErrorType.INVALID_NUMBER, offset = start)
    }
    if (value.isInfinite()) {
        jsonFail(error = JsonErrorType.NUMBER_OUT_OF_RANGE, offset = start)
    }
    return value
}

private fun jsonDoubleResult(
    bytes: ByteArray,
    start: Int,
    end: Int,
    mantissa: Long,
    exponent: Int,
    truncated: Boolean,
): Double {
    val magnitude = jsonDoubleMagnitude(mantissa = mantissa, exponent = exponent, truncated = truncated)
    if (magnitude.isNaN()) {
        return jsonSlowDouble(bytes = bytes, start = start, end = end)
    }
    if (magnitude.isInfinite()) {
        jsonFail(error = JsonErrorType.NUMBER_OUT_OF_RANGE, offset = start)
    }
    return if (bytes[start] == JSON_MINUS) {
        -magnitude
    } else {
        magnitude
    }
}

private fun jsonFloatResult(
    bytes: ByteArray,
    start: Int,
    end: Int,
    mantissa: Long,
    exponent: Int,
    truncated: Boolean,
): Float {
    val magnitude = jsonFloatMagnitude(mantissa = mantissa, exponent = exponent, truncated = truncated)
    if (magnitude.isNaN()) {
        return jsonSlowFloat(bytes = bytes, start = start, end = end)
    }
    if (magnitude.isInfinite()) {
        jsonFail(error = JsonErrorType.NUMBER_OUT_OF_RANGE, offset = start)
    }
    return if (bytes[start] == JSON_MINUS) {
        -magnitude
    } else {
        magnitude
    }
}

private fun jsonStartsRegularNumber(
    bytes: ByteArray,
    start: Int,
    limit: Int,
): Boolean {
    val digit = if (bytes[start] == JSON_MINUS) {
        start + 1
    } else {
        start
    }
    return digit < limit && jsonIsDigit(bytes[digit] - JSON_ZERO)
}

private fun jsonParseNonFinite(context: JsonReadContext): Double {
    val bytes = context.bytes
    val limit = context.limit
    val start = context.cursor
    if (!jsonStartsNonFinite(bytes = bytes, start = start, limit = limit)) {
        jsonFailTypedToken(bytes = bytes, start = start, limit = limit)
    }
    context.cursor = jsonNonFiniteEnd(bytes = bytes, start = start, limit = limit)
    return when (bytes[start]) {
        JSON_NAN_START -> Double.NaN
        JSON_MINUS -> Double.NEGATIVE_INFINITY
        else -> Double.POSITIVE_INFINITY
    }
}

private fun jsonParseQuotedDecimal(context: JsonReadContext, asFloat: Boolean): Long {
    val bytes = context.bytes
    val number = jsonQuotedNumberStart(context)
    return jsonLexDecimal(bytes = bytes, start = number, limit = context.limit) { end, mantissa, exponent, truncated ->
        jsonQuotedNumberEnd(context = context, end = end)
        if (asFloat) {
            jsonFloatResult(
                bytes = bytes,
                start = number,
                end = end,
                mantissa = mantissa,
                exponent = exponent,
                truncated = truncated,
            ).toRawBits().toLong()
        } else {
            jsonDoubleResult(
                bytes = bytes,
                start = number,
                end = end,
                mantissa = mantissa,
                exponent = exponent,
                truncated = truncated,
            ).toRawBits()
        }
    }
}

private fun jsonParseIrregularDouble(context: JsonReadContext): Double {
    if (context.bytes[context.cursor] != JSON_QUOTE) {
        return jsonParseNonFinite(context)
    }
    return Double.fromBits(jsonParseQuotedDecimal(context = context, asFloat = false))
}

private fun jsonParseIrregularFloat(context: JsonReadContext): Float {
    if (context.bytes[context.cursor] != JSON_QUOTE) {
        return jsonParseNonFinite(context).toFloat()
    }
    return Float.fromBits(jsonParseQuotedDecimal(context = context, asFloat = true).toInt())
}

@PublishedApi
internal fun jsonParseDouble(context: JsonReadContext): Double {
    val bytes = context.bytes
    val limit = context.limit
    val start = context.cursor
    if (!jsonStartsRegularNumber(bytes = bytes, start = start, limit = limit)) {
        return jsonParseIrregularDouble(context)
    }
    return jsonLexDecimal(bytes = bytes, start = start, limit = limit) { end, mantissa, exponent, truncated ->
        jsonRequireDelimiterAt(bytes = bytes, limit = limit, index = end)
        context.cursor = end
        jsonDoubleResult(
            bytes = bytes,
            start = start,
            end = end,
            mantissa = mantissa,
            exponent = exponent,
            truncated = truncated,
        )
    }
}

@PublishedApi
internal fun jsonParseFloat(context: JsonReadContext): Float {
    val bytes = context.bytes
    val limit = context.limit
    val start = context.cursor
    if (!jsonStartsRegularNumber(bytes = bytes, start = start, limit = limit)) {
        return jsonParseIrregularFloat(context)
    }
    return jsonLexDecimal(bytes = bytes, start = start, limit = limit) { end, mantissa, exponent, truncated ->
        jsonRequireDelimiterAt(bytes = bytes, limit = limit, index = end)
        context.cursor = end
        jsonFloatResult(
            bytes = bytes,
            start = start,
            end = end,
            mantissa = mantissa,
            exponent = exponent,
            truncated = truncated,
        )
    }
}

private object JsonPowers {
    @JvmField
    val high: LongArray = LongArray(JSON_POWER_COUNT)

    @JvmField
    val low: LongArray = LongArray(JSON_POWER_COUNT)

    init {
        val five = BigInteger.valueOf(JSON_POWER_BASE)
        val limit = BigInteger.ONE.shiftLeft(JSON_POWER_BITS)
        for (slot in 0 until JSON_POWER_COUNT) {
            val exponent = slot + JSON_POWER_MIN_EXPONENT
            var value: BigInteger
            if (exponent >= 0) {
                value = five.pow(exponent)
                val bits = value.bitLength()
                value = if (bits <= JSON_POWER_BITS) {
                    value.shiftLeft(JSON_POWER_BITS - bits)
                } else {
                    value.shiftRight(bits - JSON_POWER_BITS)
                }
            } else {
                val divisor = five.pow(-exponent)
                val ceilLog = divisor.subtract(BigInteger.ONE).bitLength()
                if (exponent >= JSON_POWER_SMALL_LIMIT) {
                    value = BigInteger.ONE.shiftLeft(ceilLog + JSON_POWER_BITS - 1).divide(divisor).add(BigInteger.ONE)
                } else {
                    value = BigInteger.ONE.shiftLeft(2 * ceilLog + JSON_POWER_BITS).divide(divisor).add(BigInteger.ONE)
                    while (value >= limit) {
                        value = value.shiftRight(1)
                    }
                }
            }
            high[slot] = value.shiftRight(Long.SIZE_BITS).toLong()
            low[slot] = value.toLong()
        }
    }
}

private fun jsonHexDigits(bytes: ByteArray, index: Int): Int {
    val first = jsonHexValues[bytes[index].toInt() and JSON_BYTE_MASK].toInt()
    val second = jsonHexValues[bytes[index + 1].toInt() and JSON_BYTE_MASK].toInt()
    val third = jsonHexValues[bytes[index + 2].toInt() and JSON_BYTE_MASK].toInt()
    val fourth = jsonHexValues[bytes[index + 3].toInt() and JSON_BYTE_MASK].toInt()
    if ((first or second or third or fourth) < 0) {
        return -1
    }
    return (first shl (JSON_HEX_BITS * (JSON_HEX_DIGITS - 1))) or
            (second shl (JSON_HEX_BITS * (JSON_HEX_DIGITS - 2))) or
            (third shl JSON_HEX_BITS) or
            fourth
}

/**
 * Checks the escape at [index] and returns the offset behind it, with [JSON_WIDE_ESCAPE] set when the escape stands
 * for a char above 0x7F.
 */
private fun jsonValidateEscape(
    bytes: ByteArray,
    index: Int,
    limit: Int,
): Int {
    if (index + 1 >= limit) {
        jsonFailEnd(limit)
    }
    when (bytes[index + 1]) {
        JSON_QUOTE,
        JSON_BACKSLASH,
        JSON_SLASH,
        JSON_LETTER_B,
        JSON_LETTER_F,
        JSON_LETTER_N,
        JSON_LETTER_R,
        JSON_LETTER_T,
            -> return index + JSON_ESCAPE_PREFIX_LENGTH

        JSON_LETTER_U -> {
            if (limit - index < JSON_UNICODE_ESCAPE_LENGTH) {
                jsonFailEnd(limit)
            }
            val code = jsonHexDigits(bytes = bytes, index = index + JSON_ESCAPE_PREFIX_LENGTH)
            if (code < 0) {
                jsonFail(error = JsonErrorType.INVALID_ESCAPE, offset = index)
            }
            if (code in JSON_HIGH_SURROGATE_MIN..JSON_HIGH_SURROGATE_MAX) {
                if (limit - index < JSON_SURROGATE_PAIR_LENGTH ||
                    bytes[index + JSON_UNICODE_ESCAPE_LENGTH] != JSON_BACKSLASH ||
                    bytes[index + JSON_UNICODE_ESCAPE_LENGTH + 1] != JSON_LETTER_U
                ) {
                    jsonFail(error = JsonErrorType.INVALID_ESCAPE, offset = index)
                }
                val low = jsonHexDigits(
                    bytes = bytes,
                    index = index + JSON_UNICODE_ESCAPE_LENGTH + JSON_ESCAPE_PREFIX_LENGTH,
                )
                if (low !in JSON_LOW_SURROGATE_MIN..JSON_LOW_SURROGATE_MAX) {
                    jsonFail(error = JsonErrorType.INVALID_ESCAPE, offset = index)
                }
                return (index + JSON_SURROGATE_PAIR_LENGTH) or JSON_WIDE_ESCAPE
            }
            if (code in JSON_LOW_SURROGATE_MIN..JSON_LOW_SURROGATE_MAX) {
                jsonFail(error = JsonErrorType.INVALID_ESCAPE, offset = index)
            }
            if (code > JSON_ASCII_MAX) {
                return (index + JSON_UNICODE_ESCAPE_LENGTH) or JSON_WIDE_ESCAPE
            }
            return index + JSON_UNICODE_ESCAPE_LENGTH
        }

        else -> jsonFail(error = JsonErrorType.INVALID_ESCAPE, offset = index)
    }
}

private fun jsonSkipAscii(
    bytes: ByteArray,
    start: Int,
    end: Int,
): Int {
    var index = start
    while (end - index >= Long.SIZE_BYTES) {
        val flags = jsonLoadWord(bytes = bytes, offset = index) and JSON_SWAR_HIGH_BITS
        if (flags != 0L) {
            return index + (java.lang.Long.numberOfTrailingZeros(flags) ushr JSON_SWAR_BYTE_SHIFT)
        }
        index += Long.SIZE_BYTES
    }
    while (index < end && bytes[index] >= 0) {
        index++
    }
    return index
}

private fun jsonIsContinuation(value: Int): Boolean = value in JSON_CONTINUATION_MIN..JSON_CONTINUATION_MAX

private fun jsonValidateUtf8(
    bytes: ByteArray,
    start: Int,
    end: Int,
) {
    var index = start
    while (index < end) {
        val first = bytes[index].toInt()
        if (first >= 0) {
            index = jsonSkipAscii(bytes = bytes, start = index + 1, end = end)
            continue
        }
        val lead = first and JSON_BYTE_MASK
        val remaining = end - index
        if (lead !in JSON_UTF8_MIN_LEAD..JSON_UTF8_MAX_LEAD) {
            jsonFail(error = JsonErrorType.INVALID_UTF8, offset = index)
        }
        if (lead < JSON_THREE_BYTE_TAG) {
            if (remaining < JSON_TWO_BYTES || !jsonIsContinuation(bytes[index + 1].toInt() and JSON_BYTE_MASK)) {
                jsonFail(error = JsonErrorType.INVALID_UTF8, offset = index)
            }
            index += JSON_TWO_BYTES
        } else if (lead < JSON_FOUR_BYTE_TAG) {
            if (remaining < JSON_THREE_BYTES) {
                jsonFail(error = JsonErrorType.INVALID_UTF8, offset = index)
            }
            val second = bytes[index + 1].toInt() and JSON_BYTE_MASK
            val third = bytes[index + 2].toInt() and JSON_BYTE_MASK
            val secondValid = when (lead) {
                JSON_UTF8_THREE_BYTE_LEAD -> second in JSON_UTF8_OVERLONG_THREE_MIN..JSON_CONTINUATION_MAX
                JSON_UTF8_SURROGATE_LEAD -> second in JSON_CONTINUATION_MIN..JSON_UTF8_SURROGATE_SECOND_MAX
                else -> jsonIsContinuation(second)
            }
            if (!secondValid || !jsonIsContinuation(third)) {
                jsonFail(error = JsonErrorType.INVALID_UTF8, offset = index)
            }
            index += JSON_THREE_BYTES
        } else {
            if (remaining < JSON_FOUR_BYTES) {
                jsonFail(error = JsonErrorType.INVALID_UTF8, offset = index)
            }
            val second = bytes[index + 1].toInt() and JSON_BYTE_MASK
            val third = bytes[index + 2].toInt() and JSON_BYTE_MASK
            val fourth = bytes[index + 3].toInt() and JSON_BYTE_MASK
            val secondValid = when (lead) {
                JSON_UTF8_FOUR_BYTE_LEAD -> second in JSON_UTF8_OVERLONG_FOUR_MIN..JSON_CONTINUATION_MAX
                JSON_UTF8_MAX_FOUR_LEAD -> second in JSON_CONTINUATION_MIN..JSON_UTF8_MAX_FOUR_SECOND
                else -> jsonIsContinuation(second)
            }
            if (!secondValid || !jsonIsContinuation(third) || !jsonIsContinuation(fourth)) {
                jsonFail(error = JsonErrorType.INVALID_UTF8, offset = index)
            }
            index += JSON_FOUR_BYTES
        }
    }
}

private fun jsonStringSpanSlow(
    context: JsonReadContext,
    start: Int,
    firstSpecial: Int,
): Long {
    val bytes = context.bytes
    val limit = context.limit
    val scan = JsonScans.active
    val width = scan.laneCount
    var index = firstSpecial
    var escaped = false
    var escapes = 0
    var firstNonAscii = -1
    var closed = false
    // Whole blocks: the quotes, backslashes and control chars of a block are taken from one mask, in order. A run of
    // escapes clears the bits of the bytes it covers, or moves the next block behind it when it crosses the end of
    // this one.
    blocks@ while (limit - index >= width) {
        val blockStart = index
        var events = scan.stringEvents(bytes = bytes, start = blockStart)
        while (events != 0L) {
            val position = blockStart + java.lang.Long.numberOfTrailingZeros(events)
            val value = bytes[position]
            if (value == JSON_QUOTE) {
                index = position
                closed = true
                break@blocks
            }
            if (value == JSON_BACKSLASH) {
                escaped = true
                var checked = jsonValidateEscape(bytes = bytes, index = position, limit = limit)
                var next = checked and Int.MAX_VALUE
                while (next < limit && bytes[next] == JSON_BACKSLASH) {
                    escapes = escapes or checked
                    checked = jsonValidateEscape(bytes = bytes, index = next, limit = limit)
                    next = checked and Int.MAX_VALUE
                }
                escapes = escapes or checked
                if (next - blockStart >= width) {
                    index = next
                    continue@blocks
                }
                events = events and (-1L shl (next - blockStart))
            } else {
                jsonOnControlChar(position)
                events = events and (events - 1L)
            }
        }
        index = blockStart + width
    }
    if (!closed) {
        index = scan.stringStop(bytes = bytes, start = index, end = limit)
    }
    while (true) {
        if (index >= limit) {
            jsonFailEnd(limit)
        }
        val value = bytes[index]
        if (value == JSON_QUOTE) {
            break
        }
        if (value == JSON_BACKSLASH) {
            escaped = true
            val checked = jsonValidateEscape(bytes = bytes, index = index, limit = limit)
            escapes = escapes or checked
            index = checked and Int.MAX_VALUE
        } else if (value >= 0) {
            jsonOnControlChar(index)
            index++
        } else {
            if (firstNonAscii < 0) {
                firstNonAscii = index
            }
            index++
        }
        if (index >= limit || bytes[index] != JSON_BACKSLASH) {
            index = scan.stringStop(bytes = bytes, start = index, end = limit)
        }
    }
    if (firstNonAscii < 0) {
        val found = jsonSkipAscii(bytes = bytes, start = firstSpecial, end = index)
        if (found < index) {
            firstNonAscii = found
        }
    }
    if (firstNonAscii >= 0) {
        jsonCheckUtf8(context = context, start = firstNonAscii, end = index)
    }
    if (index - start > JSON_SPAN_LENGTH_LIMIT) {
        jsonFail(error = JsonErrorType.UNEXPECTED_BYTE, offset = start - 1)
    }
    var flags = 0L
    if (escaped) {
        flags = JSON_SPAN_ESCAPED_BIT
    }
    if (firstNonAscii >= 0 || escapes < 0) {
        flags = flags or JSON_SPAN_NON_ASCII_BIT
    }
    return (start.toLong() shl JSON_HIGH_HALF_SHIFT) or flags or (index - start).toLong()
}

private fun jsonStringSpan(context: JsonReadContext, quote: Int): Long {
    val bytes = context.bytes
    val limit = context.limit
    if (bytes[quote] != JSON_QUOTE) {
        jsonFail(error = JsonErrorType.UNEXPECTED_BYTE, offset = quote)
    }
    val start = quote + 1
    val special = JsonScans.active.stringSpecial(bytes = bytes, start = start, end = limit)
    if (special < limit && bytes[special] == JSON_QUOTE) {
        if (special - start > JSON_SPAN_LENGTH_LIMIT) {
            jsonFail(error = JsonErrorType.UNEXPECTED_BYTE, offset = quote)
        }
        return (start.toLong() shl JSON_HIGH_HALF_SHIFT) or (special - start).toLong()
    }
    return jsonStringSpanSlow(context = context, start = start, firstSpecial = special)
}

private fun jsonSpanEnd(span: Long): Int = jsonSpanOffset(span) + jsonSpanLength(span) + 1

@PublishedApi
internal fun jsonScanString(context: JsonReadContext): Long {
    val quote = context.cursor
    if (context.bytes[quote] != JSON_QUOTE) {
        jsonFailTypedToken(bytes = context.bytes, start = quote, limit = context.limit)
    }
    val span = jsonStringSpan(context = context, quote = quote)
    context.cursor = jsonSpanEnd(span)
    return span
}

@PublishedApi
internal fun jsonMatchEnum(context: JsonReadContext, literals: Array<ByteArray>): Int {
    val quote = context.cursor
    val span = jsonScanString(context)
    val offset = jsonSpanOffset(span)
    val length = jsonSpanLength(span)
    val mark = context.scratchTop
    var source = context.bytes
    var start = offset
    var size = length
    if (jsonSpanEscaped(span)) {
        size = jsonDecodeEscapes(context = context, offset = offset, rawLength = length)
        source = context.borrowBytes
        start = context.borrowOffset
    }
    var found = JSON_NO_FIELD
    for (index in literals.indices) {
        val literal = literals[index]
        if (literal.size == size && Arrays.equals(source, start, start + size, literal, 0, size)) {
            found = index
            break
        }
    }
    context.scratchTop = mark
    if (found < 0) {
        jsonFail(error = JsonErrorType.UNKNOWN_ENUM_LITERAL, offset = quote)
    }
    return found
}

private fun jsonEncodeCodePoint(
    target: ByteArray,
    index: Int,
    codePoint: Int,
): Int {
    var output = index
    if (codePoint <= JSON_ASCII_MAX) {
        target[output++] = codePoint.toByte()
    } else if (codePoint < JSON_THREE_BYTE_MIN) {
        target[output++] = (JSON_TWO_BYTE_TAG or (codePoint ushr JSON_CONTINUATION_BITS)).toByte()
        target[output++] = (JSON_CONTINUATION_TAG or (codePoint and JSON_CONTINUATION_PAYLOAD)).toByte()
    } else if (codePoint < JSON_SUPPLEMENTARY_MIN) {
        target[output++] = (JSON_THREE_BYTE_TAG or (codePoint ushr (JSON_CONTINUATION_BITS * 2))).toByte()
        target[output++] =
            (JSON_CONTINUATION_TAG or ((codePoint ushr JSON_CONTINUATION_BITS) and JSON_CONTINUATION_PAYLOAD)).toByte()
        target[output++] = (JSON_CONTINUATION_TAG or (codePoint and JSON_CONTINUATION_PAYLOAD)).toByte()
    } else {
        target[output++] = (JSON_FOUR_BYTE_TAG or (codePoint ushr (JSON_CONTINUATION_BITS * 3))).toByte()
        target[output++] = (
                JSON_CONTINUATION_TAG or
                        ((codePoint ushr (JSON_CONTINUATION_BITS * 2)) and JSON_CONTINUATION_PAYLOAD)
                ).toByte()
        target[output++] =
            (JSON_CONTINUATION_TAG or ((codePoint ushr JSON_CONTINUATION_BITS) and JSON_CONTINUATION_PAYLOAD)).toByte()
        target[output++] = (JSON_CONTINUATION_TAG or (codePoint and JSON_CONTINUATION_PAYLOAD)).toByte()
    }
    return output
}

private fun jsonSimpleEscapeByte(letter: Byte): Byte = when (letter) {
    JSON_LETTER_B -> JSON_BACKSPACE
    JSON_LETTER_F -> JSON_FORM_FEED
    JSON_LETTER_N -> JSON_LINE_FEED
    JSON_LETTER_R -> JSON_CARRIAGE_RETURN
    JSON_LETTER_T -> JSON_TAB
    else -> letter
}

private fun jsonDecodeEscapes(
    context: JsonReadContext,
    offset: Int,
    rawLength: Int,
): Int {
    val bytes = context.bytes
    val end = offset + rawLength
    val outputStart = context.scratchTop
    var scratch = context.scratch
    if (scratch.size - outputStart < rawLength) {
        scratch = scratch.copyOf(maxOf(outputStart + rawLength, scratch.size * JSON_GROWTH_FACTOR))
        context.scratch = scratch
    }
    val scan = JsonScans.active
    var output = outputStart
    var index = offset
    while (index < end) {
        val special = scan.backslash(bytes = bytes, start = index, end = end)
        val run = special - index
        System.arraycopy(bytes, index, scratch, output, run)
        output += run
        if (special + 1 >= end) {
            break
        }
        val letter = bytes[special + 1]
        if (letter == JSON_LETTER_U) {
            if (end - special < JSON_UNICODE_ESCAPE_LENGTH) {
                break
            }
            var codePoint = jsonHexDigits(bytes = bytes, index = special + JSON_ESCAPE_PREFIX_LENGTH)
            index = special + JSON_UNICODE_ESCAPE_LENGTH
            if (codePoint in JSON_HIGH_SURROGATE_MIN..JSON_HIGH_SURROGATE_MAX) {
                val low = jsonLowSurrogateAfter(bytes = bytes, index = index, end = end)
                if (low >= 0) {
                    codePoint = JSON_SUPPLEMENTARY_MIN +
                            ((codePoint - JSON_HIGH_SURROGATE_MIN) shl JSON_SURROGATE_SHIFT) +
                            (low - JSON_LOW_SURROGATE_MIN)
                    index += JSON_UNICODE_ESCAPE_LENGTH
                }
            }
            output = jsonEncodeCodePoint(target = scratch, index = output, codePoint = codePoint)
        } else {
            scratch[output++] = jsonSimpleEscapeByte(letter)
            index = special + JSON_ESCAPE_PREFIX_LENGTH
        }
    }
    context.borrowBytes = scratch
    context.borrowOffset = outputStart
    context.scratchTop = output
    return output - outputStart
}

// The low surrogate of a pair whose escape starts at [index], or -1 when there is none inside the span.
private fun jsonLowSurrogateAfter(
    bytes: ByteArray,
    index: Int,
    end: Int,
): Int {
    if (end - index < JSON_UNICODE_ESCAPE_LENGTH ||
        bytes[index] != JSON_BACKSLASH ||
        bytes[index + 1] != JSON_LETTER_U
    ) {
        return -1
    }
    val low = jsonHexDigits(bytes = bytes, index = index + JSON_ESCAPE_PREFIX_LENGTH)
    return if (low in JSON_LOW_SURROGATE_MIN..JSON_LOW_SURROGATE_MAX) {
        low
    } else {
        -1
    }
}

// Decodes escapes and UTF-8 of one span into the char scratch in a single pass. Safe on malformed input: bytes that
// do not form a sequence become U+FFFD, and nothing outside [offset, offset + length] is read.
private fun jsonDecodeToString(
    context: JsonReadContext,
    offset: Int,
    length: Int,
): String {
    val bytes = context.bytes
    val end = offset + length
    var chars = context.stringChars
    if (chars.size < length) {
        chars = CharArray(maxOf(length, chars.size * JSON_GROWTH_FACTOR))
        context.stringChars = chars
    }
    var index = offset
    var output = 0
    while (index < end) {
        if (end - index >= Long.SIZE_BYTES) {
            val word = jsonLoadWord(bytes = bytes, offset = index)
            val flags = (word and JSON_SWAR_HIGH_BITS) or jsonSwarEquals(word, JSON_SWAR_BACKSLASHES)
            chars[output] = (word and JSON_BYTE_MASK_LONG).toInt().toChar()
            chars[output + 1] = ((word ushr JSON_BITS_PER_BYTE) and JSON_BYTE_MASK_LONG).toInt().toChar()
            chars[output + 2] = ((word ushr (JSON_BITS_PER_BYTE * 2)) and JSON_BYTE_MASK_LONG).toInt().toChar()
            chars[output + 3] = ((word ushr (JSON_BITS_PER_BYTE * 3)) and JSON_BYTE_MASK_LONG).toInt().toChar()
            chars[output + 4] = ((word ushr (JSON_BITS_PER_BYTE * 4)) and JSON_BYTE_MASK_LONG).toInt().toChar()
            chars[output + 5] = ((word ushr (JSON_BITS_PER_BYTE * 5)) and JSON_BYTE_MASK_LONG).toInt().toChar()
            chars[output + 6] = ((word ushr (JSON_BITS_PER_BYTE * 6)) and JSON_BYTE_MASK_LONG).toInt().toChar()
            chars[output + 7] = ((word ushr (JSON_BITS_PER_BYTE * 7)) and JSON_BYTE_MASK_LONG).toInt().toChar()
            if (flags == 0L) {
                index += Long.SIZE_BYTES
                output += Long.SIZE_BYTES
                continue
            }
            val run = java.lang.Long.numberOfTrailingZeros(flags) ushr JSON_SWAR_BYTE_SHIFT
            index += run
            output += run
        } else {
            while (index < end) {
                val plain = bytes[index].toInt()
                if (plain < 0 || plain == JSON_BACKSLASH_CODE) {
                    break
                }
                chars[output++] = plain.toChar()
                index++
            }
            if (index >= end) {
                break
            }
        }
        val first = bytes[index].toInt()
        if (first >= 0) {
            if (first.toByte() == JSON_BACKSLASH) {
                if (index + 1 >= end) {
                    break
                }
                val letter = bytes[index + 1]
                if (letter == JSON_LETTER_U) {
                    if (end - index < JSON_UNICODE_ESCAPE_LENGTH) {
                        break
                    }
                    val codePoint = jsonHexDigits(bytes = bytes, index = index + JSON_ESCAPE_PREFIX_LENGTH)
                    index += JSON_UNICODE_ESCAPE_LENGTH
                    if (codePoint < 0) {
                        chars[output++] = JSON_REPLACEMENT_CHAR
                    } else {
                        chars[output++] = codePoint.toChar()
                        if (codePoint in JSON_HIGH_SURROGATE_MIN..JSON_HIGH_SURROGATE_MAX) {
                            val low = jsonLowSurrogateAfter(bytes = bytes, index = index, end = end)
                            if (low >= 0) {
                                chars[output++] = low.toChar()
                                index += JSON_UNICODE_ESCAPE_LENGTH
                            }
                        }
                    }
                } else {
                    chars[output++] = jsonSimpleEscapeByte(letter).toInt().toChar()
                    index += JSON_ESCAPE_PREFIX_LENGTH
                }
            } else {
                chars[output++] = first.toChar()
                index++
            }
        } else {
            while (index < end && bytes[index] < 0) {
                val lead = bytes[index].toInt() and JSON_BYTE_MASK
                val remaining = end - index
                if (lead < JSON_THREE_BYTE_TAG) {
                    if (lead >= JSON_TWO_BYTE_TAG && remaining >= JSON_TWO_BYTES) {
                        chars[output++] = (
                                ((lead and JSON_TWO_BYTE_PAYLOAD) shl JSON_CONTINUATION_BITS) or
                                        (bytes[index + 1].toInt() and JSON_CONTINUATION_PAYLOAD)
                                ).toChar()
                        index += JSON_TWO_BYTES
                    } else {
                        chars[output++] = JSON_REPLACEMENT_CHAR
                        index++
                    }
                } else if (lead < JSON_FOUR_BYTE_TAG) {
                    if (remaining >= JSON_THREE_BYTES) {
                        chars[output++] = (
                                ((lead and JSON_THREE_BYTE_PAYLOAD) shl (JSON_CONTINUATION_BITS * 2)) or
                                        ((bytes[index + 1].toInt() and JSON_CONTINUATION_PAYLOAD) shl JSON_CONTINUATION_BITS) or
                                        (bytes[index + 2].toInt() and JSON_CONTINUATION_PAYLOAD)
                                ).toChar()
                        index += JSON_THREE_BYTES
                    } else {
                        chars[output++] = JSON_REPLACEMENT_CHAR
                        index++
                    }
                } else if (lead <= JSON_UTF8_MAX_LEAD && remaining >= JSON_FOUR_BYTES) {
                    val value = (((lead and JSON_FOUR_BYTE_PAYLOAD) shl (JSON_CONTINUATION_BITS * 3)) or
                            ((bytes[index + 1].toInt() and JSON_CONTINUATION_PAYLOAD) shl (JSON_CONTINUATION_BITS * 2)) or
                            ((bytes[index + 2].toInt() and JSON_CONTINUATION_PAYLOAD) shl JSON_CONTINUATION_BITS) or
                            (bytes[index + 3].toInt() and JSON_CONTINUATION_PAYLOAD)) - JSON_SUPPLEMENTARY_MIN
                    chars[output++] = (JSON_HIGH_SURROGATE_MIN + (value ushr JSON_SURROGATE_SHIFT)).toChar()
                    chars[output++] = (JSON_LOW_SURROGATE_MIN + (value and JSON_SURROGATE_LOW_MASK)).toChar()
                    index += JSON_FOUR_BYTES
                } else {
                    chars[output++] = JSON_REPLACEMENT_CHAR
                    index++
                }
            }
        }
    }
    return String(chars, 0, output)
}

private fun jsonCompareText(
    bytes: ByteArray,
    offset: Int,
    length: Int,
    text: String,
    exact: Boolean,
): Boolean {
    val end = offset + length
    var position = offset
    var index = 0
    while (index < text.length) {
        val unit = text[index].code
        index++
        if (unit <= JSON_ASCII_MAX) {
            if (position >= end || bytes[position].toInt() != unit) {
                return false
            }
            position++
        } else {
            var codePoint = unit
            if (unit in JSON_HIGH_SURROGATE_MIN..JSON_HIGH_SURROGATE_MAX) {
                if (index >= text.length) {
                    return false
                }
                val low = text[index].code
                if (low !in JSON_LOW_SURROGATE_MIN..JSON_LOW_SURROGATE_MAX) {
                    return false
                }
                index++
                codePoint = JSON_SUPPLEMENTARY_MIN +
                        ((unit - JSON_HIGH_SURROGATE_MIN) shl JSON_SURROGATE_SHIFT) +
                        (low - JSON_LOW_SURROGATE_MIN)
            } else if (unit in JSON_LOW_SURROGATE_MIN..JSON_LOW_SURROGATE_MAX) {
                return false
            }
            val size = if (codePoint < JSON_THREE_BYTE_MIN) {
                JSON_TWO_BYTES
            } else if (codePoint < JSON_SUPPLEMENTARY_MIN) {
                JSON_THREE_BYTES
            } else {
                JSON_FOUR_BYTES
            }
            if (end - position < size) {
                return false
            }
            if (!jsonMatchCodePoint(bytes = bytes, position = position, codePoint = codePoint, size = size)) {
                return false
            }
            position += size
        }
    }
    return !exact || position == end
}

private fun jsonMatchCodePoint(
    bytes: ByteArray,
    position: Int,
    codePoint: Int,
    size: Int,
): Boolean {
    val leadTag = when (size) {
        JSON_TWO_BYTES -> JSON_TWO_BYTE_TAG
        JSON_THREE_BYTES -> JSON_THREE_BYTE_TAG
        else -> JSON_FOUR_BYTE_TAG
    }
    val leadShift = JSON_CONTINUATION_BITS * (size - 1)
    if (bytes[position].toInt() != (leadTag or (codePoint ushr leadShift)).toByte().toInt()) {
        return false
    }
    for (offset in 1 until size) {
        val shift = JSON_CONTINUATION_BITS * (size - 1 - offset)
        val expected = JSON_CONTINUATION_TAG or ((codePoint ushr shift) and JSON_CONTINUATION_PAYLOAD)
        if (bytes[position + offset].toInt() != expected.toByte().toInt()) {
            return false
        }
    }
    return true
}

private fun jsonSpanCompare(
    context: JsonReadContext,
    span: Long,
    text: String,
    exact: Boolean,
): Boolean {
    val mark = context.scratchTop
    val length = jsonBorrowString(context = context, span = span)
    val result = jsonCompareText(
        bytes = context.borrowBytes,
        offset = context.borrowOffset,
        length = length,
        text = text,
        exact = exact,
    )
    context.scratchTop = mark
    return result
}

internal fun jsonSpanMatches(
    context: JsonReadContext,
    span: Long,
    text: String,
): Boolean = jsonSpanCompare(context = context, span = span, text = text, exact = true)

internal fun jsonSpanStartsWith(
    context: JsonReadContext,
    span: Long,
    prefix: String,
): Boolean = jsonSpanCompare(context = context, span = span, text = prefix, exact = false)

private fun jsonCheckDepth(context: JsonReadContext, offset: Int) {
    if (context.depth + 1 > MAX_NESTING_DEPTH) {
        jsonFail(error = JsonErrorType.NESTING_TOO_DEEP, offset = offset)
    }
}

private fun jsonFinishNumbers(
    context: JsonReadContext,
    open: Int,
    close: Int,
    commas: Int,
): Long {
    val bytes = context.bytes
    var last = close - 1
    while (last > open && jsonIsWhitespace(bytes[last])) {
        last--
    }
    val count = if (last == open) {
        0
    } else if (bytes[last] == JSON_COMMA) {
        jsonOnTrailingComma(close)
        commas
    } else {
        commas + 1
    }
    context.cursor = close + 1
    return (open.toLong() shl JSON_HIGH_HALF_SHIFT) or count.toLong()
}

private fun jsonScanNumbersQuoted(context: JsonReadContext, open: Int): Long {
    val bytes = context.bytes
    val limit = context.limit
    var index = open + 1
    var commas = 0
    while (index < limit) {
        when (bytes[index]) {
            JSON_ARRAY_CLOSE -> return jsonFinishNumbers(context = context, open = open, close = index, commas = commas)
            JSON_COMMA -> {
                commas++
                index++
            }

            JSON_QUOTE -> index = jsonSpanEnd(jsonStringSpan(context = context, quote = index))
            JSON_ARRAY_OPEN, JSON_OBJECT_OPEN -> jsonFail(error = JsonErrorType.TYPE_MISMATCH, offset = index)
            JSON_OBJECT_CLOSE -> jsonFail(error = JsonErrorType.UNEXPECTED_BYTE, offset = index)
            else -> index++
        }
    }
    jsonFailEnd(limit)
}

@PublishedApi
internal fun jsonScanNumbers(context: JsonReadContext): Long {
    val bytes = context.bytes
    val limit = context.limit
    val open = context.cursor
    if (bytes[open] != JSON_ARRAY_OPEN) {
        jsonFailTypedToken(bytes = bytes, start = open, limit = limit)
    }
    jsonCheckDepth(context = context, offset = open)
    val scanned = JsonScans.active.numbersEnd(bytes = bytes, start = open + 1, end = limit)
    if (scanned >= 0L) {
        return jsonFinishNumbers(
            context = context,
            open = open,
            close = (scanned ushr JSON_HIGH_HALF_SHIFT).toInt(),
            commas = scanned.toInt(),
        )
    }
    val stop = (-scanned - 1L).toInt()
    if (stop >= limit) {
        jsonFailEnd(limit)
    }
    when (bytes[stop]) {
        JSON_QUOTE -> {
            jsonOnQuotedNumber(stop)
            return jsonScanNumbersQuoted(context = context, open = open)
        }

        JSON_ARRAY_OPEN, JSON_OBJECT_OPEN -> jsonFail(error = JsonErrorType.TYPE_MISMATCH, offset = stop)
        else -> jsonFail(error = JsonErrorType.UNEXPECTED_BYTE, offset = stop)
    }
}

private fun jsonSetContainerKind(
    stack: LongArray,
    depth: Int,
    isObject: Boolean,
) {
    val bit = depth - 1
    val word = bit ushr JSON_STACK_WORD_SHIFT
    val mask = 1L shl (bit and JSON_STACK_BIT_MASK)
    stack[word] = if (isObject) {
        stack[word] or mask
    } else {
        stack[word] and mask.inv()
    }
}

private fun jsonContainerIsObject(stack: LongArray, depth: Int): Boolean {
    val bit = depth - 1
    return ((stack[bit ushr JSON_STACK_WORD_SHIFT] ushr (bit and JSON_STACK_BIT_MASK)) and 1L) != 0L
}

private fun jsonSkipKey(context: JsonReadContext, quote: Int): Int {
    val span = jsonStringSpan(context = context, quote = quote)
    return jsonKeyColon(bytes = context.bytes, limit = context.limit, afterQuote = jsonSpanEnd(span))
}

private fun jsonSkipNumberRun(
    bytes: ByteArray,
    start: Int,
    limit: Int,
): Int {
    var index = start
    while (true) {
        index = jsonScanNumberValue(bytes = bytes, start = index, limit = limit)
        val separator = jsonSkipWs(bytes = bytes, index = index, limit = limit)
        if (separator >= limit || bytes[separator] != JSON_COMMA) {
            return index
        }
        val next = jsonSkipWs(bytes = bytes, index = separator + 1, limit = limit)
        if (next >= limit || !jsonStartsPlainNumber(bytes = bytes, start = next, limit = limit)) {
            return index
        }
        index = next
    }
}

private fun jsonStartsPlainNumber(
    bytes: ByteArray,
    start: Int,
    limit: Int,
): Boolean = (bytes[start] == JSON_MINUS || jsonIsDigit(bytes[start] - JSON_ZERO)) &&
        !jsonStartsNonFinite(bytes = bytes, start = start, limit = limit)

private fun jsonSkipValueValidating(context: JsonReadContext) {
    val bytes = context.bytes
    val limit = context.limit
    val start = context.cursor
    when (bytes[start]) {
        JSON_OBJECT_OPEN, JSON_ARRAY_OPEN -> jsonSkipContainerValidating(context)
        JSON_QUOTE -> context.cursor = jsonSpanEnd(jsonStringSpan(context = context, quote = start))
        JSON_TRUE_START, JSON_FALSE_START, JSON_NULL_START -> context.cursor = jsonLiteralEnd(
            bytes = bytes,
            start = start,
            limit = limit,
        )

        else -> context.cursor = jsonScalarTokenEnd(bytes = bytes, start = start, limit = limit)
    }
}

private fun jsonSkipContainerValidating(context: JsonReadContext) {
    val bytes = context.bytes
    val limit = context.limit
    val stack = context.skipStack
    val baseDepth = context.depth
    var depth = baseDepth
    var index = context.cursor
    while (true) {
        if (index >= limit) {
            jsonFailEnd(limit)
        }
        var opened = false
        when (bytes[index]) {
            JSON_OBJECT_OPEN -> {
                depth++
                if (depth > MAX_NESTING_DEPTH) {
                    jsonFail(error = JsonErrorType.NESTING_TOO_DEEP, offset = index)
                }
                index = jsonSkipWs(bytes = bytes, index = index + 1, limit = limit)
                if (index >= limit) {
                    jsonFailEnd(limit)
                }
                if (bytes[index] == JSON_OBJECT_CLOSE) {
                    index++
                    depth--
                } else {
                    jsonSetContainerKind(stack = stack, depth = depth, isObject = true)
                    index = jsonSkipKey(context = context, quote = index)
                    opened = true
                }
            }

            JSON_ARRAY_OPEN -> {
                depth++
                if (depth > MAX_NESTING_DEPTH) {
                    jsonFail(error = JsonErrorType.NESTING_TOO_DEEP, offset = index)
                }
                index = jsonSkipWs(bytes = bytes, index = index + 1, limit = limit)
                if (index >= limit) {
                    jsonFailEnd(limit)
                }
                if (bytes[index] == JSON_ARRAY_CLOSE) {
                    index++
                    depth--
                } else {
                    jsonSetContainerKind(stack = stack, depth = depth, isObject = false)
                    opened = true
                }
            }

            JSON_QUOTE -> index = jsonSpanEnd(jsonStringSpan(context = context, quote = index))
            JSON_TRUE_START, JSON_FALSE_START, JSON_NULL_START -> index = jsonLiteralEnd(
                bytes = bytes,
                start = index,
                limit = limit,
            )

            else -> index = if (!jsonContainerIsObject(stack = stack, depth = depth) &&
                jsonStartsPlainNumber(bytes = bytes, start = index, limit = limit)
            ) {
                jsonSkipNumberRun(bytes = bytes, start = index, limit = limit)
            } else {
                jsonScalarTokenEnd(bytes = bytes, start = index, limit = limit)
            }
        }
        if (opened) {
            continue
        }
        while (depth > baseDepth) {
            index = jsonSkipWs(bytes = bytes, index = index, limit = limit)
            if (index >= limit) {
                jsonFailEnd(limit)
            }
            val separator = bytes[index]
            val isObject = jsonContainerIsObject(stack = stack, depth = depth)
            val close = if (isObject) {
                JSON_OBJECT_CLOSE
            } else {
                JSON_ARRAY_CLOSE
            }
            if (separator == close) {
                index++
                depth--
            } else if (separator == JSON_COMMA) {
                index = jsonSkipWs(bytes = bytes, index = index + 1, limit = limit)
                if (index >= limit) {
                    jsonFailEnd(limit)
                }
                if (bytes[index] == close) {
                    jsonOnTrailingComma(index)
                    index++
                    depth--
                } else {
                    if (isObject) {
                        index = jsonSkipKey(context = context, quote = index)
                    }
                    break
                }
            } else {
                jsonFail(error = JsonErrorType.UNEXPECTED_BYTE, offset = index)
            }
        }
        if (depth == baseDepth) {
            context.cursor = index
            return
        }
    }
}

private fun jsonSkipStructuralToken(context: JsonReadContext) {
    val bytes = context.bytes
    val limit = context.limit
    val start = context.cursor
    var index = start
    while (index < limit && !jsonIsDelimiter(bytes[index])) {
        index++
    }
    if (index == start) {
        jsonFail(error = JsonErrorType.UNEXPECTED_BYTE, offset = start)
    }
    context.cursor = index
}

private fun jsonSkipValueStructural(context: JsonReadContext) {
    val bytes = context.bytes
    val limit = context.limit
    val start = context.cursor
    when (bytes[start]) {
        JSON_OBJECT_OPEN, JSON_ARRAY_OPEN -> {
            val end = JsonScans.active.containerEnd(
                bytes = bytes,
                start = start,
                end = limit,
                maxDepth = MAX_NESTING_DEPTH - context.depth,
            )
            if (end < 0) {
                if (end == JSON_CONTAINER_UNTERMINATED) {
                    jsonFailEnd(limit)
                }
                jsonFail(error = JsonErrorType.NESTING_TOO_DEEP, offset = JSON_CONTAINER_TOO_DEEP_BASE - end)
            }
            context.cursor = end
        }

        JSON_QUOTE -> {
            val end = jsonSkipStringBody(scan = JsonScans.active, bytes = bytes, from = start + 1, end = limit)
            if (end < 0) {
                jsonFailEnd(limit)
            }
            context.cursor = end
        }

        else -> jsonSkipStructuralToken(context)
    }
}

private fun jsonArraySeparator(
    bytes: ByteArray,
    index: Int,
    limit: Int,
): Int {
    val separator = jsonSkipWs(bytes = bytes, index = index, limit = limit)
    if (separator >= limit) {
        jsonFailEnd(limit)
    }
    if (bytes[separator] != JSON_COMMA) {
        jsonFail(error = JsonErrorType.UNEXPECTED_BYTE, offset = separator)
    }
    return separator + 1
}

private fun jsonArrayEnd(
    bytes: ByteArray,
    index: Int,
    limit: Int,
) {
    var close = jsonSkipWs(bytes = bytes, index = index, limit = limit)
    if (close < limit && bytes[close] == JSON_COMMA) {
        jsonOnTrailingComma(close)
        close = jsonSkipWs(bytes = bytes, index = close + 1, limit = limit)
    }
    if (close >= limit) {
        jsonFailEnd(limit)
    }
    if (bytes[close] != JSON_ARRAY_CLOSE) {
        jsonFail(error = JsonErrorType.UNEXPECTED_BYTE, offset = close)
    }
}

private inline fun jsonFillElements(
    context: JsonReadContext,
    span: Long,
    parse: (bytes: ByteArray, start: Int, limit: Int, element: Int) -> Int,
) {
    val bytes = context.bytes
    val limit = context.limit
    val count = jsonSpanCount(span)
    val saved = context.cursor
    var index = jsonSpanOffset(span) + 1
    var element = 0
    while (element < count) {
        if (index >= limit) {
            jsonFailEnd(limit)
        }
        if (bytes[index] <= JSON_FIRST_PRINTABLE_BYTE) {
            index = jsonSkipWs(bytes = bytes, index = index, limit = limit)
            if (index >= limit) {
                jsonFailEnd(limit)
            }
        }
        index = parse(bytes, index, limit, element)
        element++
        if (element < count) {
            index = if (limit - index >= 2 && bytes[index] == JSON_COMMA &&
                bytes[index + 1] > JSON_FIRST_PRINTABLE_BYTE
            ) {
                index + 1
            } else {
                jsonArraySeparator(bytes = bytes, index = index, limit = limit)
            }
        }
    }
    jsonArrayEnd(bytes = bytes, index = index, limit = limit)
    context.cursor = saved
}

internal fun jsonFillInts(
    context: JsonReadContext,
    span: Long,
    destination: IntArray,
    destinationOffset: Int,
) {
    jsonFillElements(context = context, span = span) { bytes, start, limit, element ->
        jsonReadInteger(context = context, bytes = bytes, start = start, limit = limit) { end, value ->
            destination[destinationOffset + element] = jsonIntegerToInt(value = value, start = start)
            end
        }
    }
}

internal fun jsonFillLongs(
    context: JsonReadContext,
    span: Long,
    destination: LongArray,
    destinationOffset: Int,
) {
    jsonFillElements(context = context, span = span) { bytes, start, limit, element ->
        jsonReadInteger(context = context, bytes = bytes, start = start, limit = limit) { end, value ->
            destination[destinationOffset + element] = value
            end
        }
    }
}

internal fun jsonFillFloats(
    context: JsonReadContext,
    span: Long,
    destination: FloatArray,
    destinationOffset: Int,
) {
    jsonFillElements(context = context, span = span) { bytes, start, limit, element ->
        if (jsonStartsRegularNumber(bytes = bytes, start = start, limit = limit)) {
            jsonLexDecimal(bytes = bytes, start = start, limit = limit) { end, mantissa, exponent, truncated ->
                jsonRequireDelimiterAt(bytes = bytes, limit = limit, index = end)
                destination[destinationOffset + element] = jsonFloatResult(
                    bytes = bytes,
                    start = start,
                    end = end,
                    mantissa = mantissa,
                    exponent = exponent,
                    truncated = truncated,
                )
                end
            }
        } else {
            context.cursor = start
            destination[destinationOffset + element] = jsonParseIrregularFloat(context)
            context.cursor
        }
    }
}

internal fun jsonFillDoubles(
    context: JsonReadContext,
    span: Long,
    destination: DoubleArray,
    destinationOffset: Int,
) {
    jsonFillElements(context = context, span = span) { bytes, start, limit, element ->
        if (jsonStartsRegularNumber(bytes = bytes, start = start, limit = limit)) {
            jsonLexDecimal(bytes = bytes, start = start, limit = limit) { end, mantissa, exponent, truncated ->
                jsonRequireDelimiterAt(bytes = bytes, limit = limit, index = end)
                destination[destinationOffset + element] = jsonDoubleResult(
                    bytes = bytes,
                    start = start,
                    end = end,
                    mantissa = mantissa,
                    exponent = exponent,
                    truncated = truncated,
                )
                end
            }
        } else {
            context.cursor = start
            destination[destinationOffset + element] = jsonParseIrregularDouble(context)
            context.cursor
        }
    }
}

private fun jsonSkipValueAt(context: JsonReadContext, offset: Int): Int {
    if (offset == context.lastStringOffset) {
        return context.lastStringEnd
    }
    val saved = context.cursor
    context.cursor = offset
    jsonSkipValue(context)
    val end = context.cursor
    context.cursor = saved
    return end
}

private fun jsonMemberAfterValue(context: JsonReadContext, valueOffset: Int): Int {
    val bytes = context.bytes
    val limit = context.limit
    val valueEnd = jsonSkipValueAt(context = context, offset = valueOffset)
    var index = jsonSkipWs(bytes = bytes, index = valueEnd, limit = limit)
    if (index >= limit) {
        jsonFailEnd(limit)
    }
    val separator = bytes[index]
    if (separator == JSON_OBJECT_CLOSE) {
        return JSON_NO_FIELD
    }
    if (separator != JSON_COMMA) {
        jsonFail(error = JsonErrorType.UNEXPECTED_BYTE, offset = index)
    }
    index = jsonSkipWs(bytes = bytes, index = index + 1, limit = limit)
    if (index >= limit) {
        jsonFailEnd(limit)
    }
    if (bytes[index] == JSON_OBJECT_CLOSE) {
        jsonOnTrailingComma(index)
        return JSON_NO_FIELD
    }
    if (bytes[index] != JSON_QUOTE) {
        jsonFail(error = JsonErrorType.UNEXPECTED_BYTE, offset = index)
    }
    return index
}

private fun jsonPassOverDeclared(
    context: JsonReadContext,
    quote: Int,
    filter: JsonSchema?,
): Int {
    var member = quote
    if (filter == null) {
        return member
    }
    while (member >= 0) {
        val key = jsonMemberKey(context = context, memberOffset = member)
        if (jsonLookupSpan(context = context, schema = filter, span = key) < 0) {
            return member
        }
        member = jsonMemberAfterValue(context = context, valueOffset = jsonMemberValue(context = context, key = key))
    }
    return member
}

@PublishedApi
internal fun jsonMembersFirst(
    context: JsonReadContext,
    objectOffset: Int,
    filter: JsonSchema?,
): Int {
    val bytes = context.bytes
    val limit = context.limit
    if (bytes[objectOffset] != JSON_OBJECT_OPEN) {
        jsonFailTypedToken(bytes = bytes, start = objectOffset, limit = limit)
    }
    val first = jsonSkipWs(bytes = bytes, index = objectOffset + 1, limit = limit)
    if (first >= limit) {
        jsonFailEnd(limit)
    }
    val value = bytes[first]
    if (value == JSON_OBJECT_CLOSE) {
        return JSON_NO_FIELD
    }
    if (value != JSON_QUOTE) {
        jsonFail(error = JsonErrorType.UNEXPECTED_BYTE, offset = first)
    }
    return jsonPassOverDeclared(context = context, quote = first, filter = filter)
}

@PublishedApi
internal fun jsonMemberKey(
    context: JsonReadContext,
    memberOffset: Int,
): Long = jsonStringSpan(context = context, quote = memberOffset)

@PublishedApi
internal fun jsonMemberValue(
    context: JsonReadContext,
    key: Long,
): Int {
    val bytes = context.bytes
    val limit = context.limit
    var index = jsonSkipWs(bytes = bytes, index = jsonSpanEnd(key), limit = limit)
    if (index >= limit) {
        jsonFailEnd(limit)
    }
    if (bytes[index] != JSON_COLON) {
        jsonFail(error = JsonErrorType.UNEXPECTED_BYTE, offset = index)
    }
    index = jsonSkipWs(bytes = bytes, index = index + 1, limit = limit)
    if (index >= limit) {
        jsonFailEnd(limit)
    }
    return index
}

@PublishedApi
internal fun jsonMembersNext(
    context: JsonReadContext,
    valueOffset: Int,
    filter: JsonSchema?,
): Int {
    val next = jsonMemberAfterValue(context = context, valueOffset = valueOffset)
    if (next < 0) {
        return next
    }
    return jsonPassOverDeclared(context = context, quote = next, filter = filter)
}

@PublishedApi
internal fun jsonElementsFirst(
    context: JsonReadContext,
    arrayOffset: Int,
): Int {
    val bytes = context.bytes
    val limit = context.limit
    if (bytes[arrayOffset] != JSON_ARRAY_OPEN) {
        jsonFailTypedToken(bytes = bytes, start = arrayOffset, limit = limit)
    }
    val first = jsonSkipWs(bytes = bytes, index = arrayOffset + 1, limit = limit)
    if (first >= limit) {
        jsonFailEnd(limit)
    }
    if (bytes[first] == JSON_ARRAY_CLOSE) {
        return JSON_NO_FIELD
    }
    return first
}

@PublishedApi
internal fun jsonElementsNext(
    context: JsonReadContext,
    valueOffset: Int,
): Int {
    val bytes = context.bytes
    val limit = context.limit
    val valueEnd = jsonSkipValueAt(context = context, offset = valueOffset)
    var index = jsonSkipWs(bytes = bytes, index = valueEnd, limit = limit)
    if (index >= limit) {
        jsonFailEnd(limit)
    }
    val separator = bytes[index]
    if (separator == JSON_ARRAY_CLOSE) {
        return JSON_NO_FIELD
    }
    if (separator != JSON_COMMA) {
        jsonFail(error = JsonErrorType.UNEXPECTED_BYTE, offset = index)
    }
    index = jsonSkipWs(bytes = bytes, index = index + 1, limit = limit)
    if (index >= limit) {
        jsonFailEnd(limit)
    }
    if (bytes[index] == JSON_ARRAY_CLOSE) {
        jsonOnTrailingComma(index)
        return JSON_NO_FIELD
    }
    return index
}

/**
 * An abstraction that hides Vector API usage.
 * It is guaranteed that only one implementation will be loaded at runtime, giving JVM an option to
 * inline the calls and making it effectively zero-cost at runtime.
 * It is guaranteed that Vector API will be chosen only if enabled, if classes successfully load and
 * if Vector API passes simple smoke test at runtime.
 */
private sealed interface JsonScan {
    val laneCount: Int

    fun stringSpecial(
        bytes: ByteArray,
        start: Int,
        end: Int,
    ): Int

    fun stringStop(
        bytes: ByteArray,
        start: Int,
        end: Int,
    ): Int

    fun backslash(
        bytes: ByteArray,
        start: Int,
        end: Int,
    ): Int

    fun structuralMask(bytes: ByteArray, start: Int): Long

    fun stringEvents(bytes: ByteArray, start: Int): Long

    fun copyBlock(
        source: ByteArray,
        sourceOffset: Int,
        target: ByteArray,
        targetOffset: Int,
    ): Long

    fun containerEnd(
        bytes: ByteArray,
        start: Int,
        end: Int,
        maxDepth: Int,
    ): Int

    fun numbersEnd(
        bytes: ByteArray,
        start: Int,
        end: Int,
    ): Long

    fun selfTest(): Boolean
}

private fun jsonSwarZeroBytes(word: Long): Long = (
        ((word and JSON_SWAR_LOW_BITS) + JSON_SWAR_LOW_BITS) or word or JSON_SWAR_LOW_BITS
        ).inv() and JSON_SWAR_HIGH_BITS

private fun jsonSwarEquals(word: Long, pattern: Long): Long = jsonSwarZeroBytes(word xor pattern)

private fun jsonSwarStringSpecial(word: Long): Long {
    val outside = (word or ((word and JSON_SWAR_LOW_BITS) + JSON_SWAR_CONTROL_ADD).inv()) and JSON_SWAR_HIGH_BITS
    return outside or jsonSwarEquals(word, JSON_SWAR_QUOTES) or jsonSwarEquals(word, JSON_SWAR_BACKSLASHES)
}

private fun jsonSwarStringStop(word: Long): Long {
    val controls = (word or ((word and JSON_SWAR_LOW_BITS) + JSON_SWAR_CONTROL_ADD)).inv() and JSON_SWAR_HIGH_BITS
    return controls or jsonSwarEquals(word, JSON_SWAR_QUOTES) or jsonSwarEquals(word, JSON_SWAR_BACKSLASHES)
}

private fun jsonSwarStructural(word: Long): Long {
    val folded = word or JSON_SWAR_CASE_BITS
    return jsonSwarEquals(word, JSON_SWAR_QUOTES) or
            jsonSwarEquals(folded, JSON_SWAR_BRACKET_OPENS) or
            jsonSwarEquals(folded, JSON_SWAR_BRACKET_CLOSES)
}

private fun jsonSwarToMask(flags: Long): Long =
    ((flags ushr JSON_SWAR_HIGH_BIT_SHIFT) * JSON_SWAR_MASK_MULTIPLIER) ushr JSON_SWAR_MASK_SHIFT

private fun jsonIsStructural(value: Byte): Boolean = value == JSON_QUOTE ||
        value == JSON_OBJECT_OPEN ||
        value == JSON_OBJECT_CLOSE ||
        value == JSON_ARRAY_OPEN ||
        value == JSON_ARRAY_CLOSE

private fun jsonNumbersStop(
    bytes: ByteArray,
    index: Int,
    commas: Int,
): Long = if (bytes[index] == JSON_ARRAY_CLOSE) {
    (index.toLong() shl JSON_HIGH_HALF_SHIFT) or commas.toLong()
} else {
    -1L - index
}

private fun jsonSwarNumbersEnd(
    bytes: ByteArray,
    start: Int,
    end: Int,
    initialCommas: Int,
): Long {
    var index = start
    var commas = initialCommas
    while (end - index >= Long.SIZE_BYTES) {
        val word = jsonLoadWord(bytes = bytes, offset = index)
        val stops = jsonSwarStructural(word)
        val commaFlags = jsonSwarEquals(word, JSON_SWAR_COMMAS)
        if (stops != 0L) {
            val bit = java.lang.Long.numberOfTrailingZeros(stops)
            commas += java.lang.Long.bitCount(commaFlags and ((1L shl bit) - 1L))
            return jsonNumbersStop(bytes = bytes, index = index + (bit ushr JSON_SWAR_BYTE_SHIFT), commas = commas)
        }
        commas += java.lang.Long.bitCount(commaFlags)
        index += Long.SIZE_BYTES
    }
    while (index < end) {
        val value = bytes[index]
        if (value == JSON_COMMA) {
            commas++
        } else if (jsonIsStructural(value)) {
            return jsonNumbersStop(bytes = bytes, index = index, commas = commas)
        }
        index++
    }
    return -1L - end
}

private fun jsonTailStructuralMask(
    bytes: ByteArray,
    start: Int,
    end: Int,
): Long {
    var mask = 0L
    for (offset in 0 until end - start) {
        if (jsonIsStructural(bytes[start + offset])) {
            mask = mask or (1L shl offset)
        }
    }
    return mask
}

private fun jsonSkipStringBody(
    scan: JsonScan,
    bytes: ByteArray,
    from: Int,
    end: Int,
): Int {
    var index = from
    while (true) {
        index = scan.stringStop(bytes = bytes, start = index, end = end)
        if (index >= end) {
            return -1
        }
        val value = bytes[index]
        if (value == JSON_QUOTE) {
            return index + 1
        }
        index += if (value == JSON_BACKSLASH) {
            JSON_ESCAPE_PREFIX_LENGTH
        } else {
            1
        }
    }
}

private fun jsonContainerEnd(
    scan: JsonScan,
    bytes: ByteArray,
    start: Int,
    end: Int,
    maxDepth: Int,
): Int {
    val width = scan.laneCount
    var depth = 0
    var index = start
    outer@ while (index < end) {
        var events: Long
        val blockEnd: Int
        if (end - index >= width) {
            events = scan.structuralMask(bytes = bytes, start = index)
            blockEnd = index + width
        } else {
            events = jsonTailStructuralMask(bytes = bytes, start = index, end = end)
            blockEnd = end
        }
        while (events != 0L) {
            val position = index + java.lang.Long.numberOfTrailingZeros(events)
            events = events and (events - 1L)
            val value = bytes[position]
            if (value == JSON_QUOTE) {
                val after = jsonSkipStringBody(scan = scan, bytes = bytes, from = position + 1, end = end)
                if (after < 0) {
                    return JSON_CONTAINER_UNTERMINATED
                }
                if (after >= blockEnd) {
                    index = after
                    continue@outer
                }
                events = events and (-1L shl (after - index))
            } else if (value == JSON_OBJECT_OPEN || value == JSON_ARRAY_OPEN) {
                depth++
                if (depth > maxDepth) {
                    return JSON_CONTAINER_TOO_DEEP_BASE - position
                }
            } else {
                depth--
                if (depth == 0) {
                    return position + 1
                }
            }
        }
        index = blockEnd
    }
    return JSON_CONTAINER_UNTERMINATED
}

private object JsonSwarScan : JsonScan {
    override val laneCount: Int = Long.SIZE_BYTES

    override fun stringSpecial(
        bytes: ByteArray,
        start: Int,
        end: Int,
    ): Int {
        var index = start
        while (end - index >= Long.SIZE_BYTES) {
            val flags = jsonSwarStringSpecial(jsonLoadWord(bytes = bytes, offset = index))
            if (flags != 0L) {
                return index + (java.lang.Long.numberOfTrailingZeros(flags) ushr JSON_SWAR_BYTE_SHIFT)
            }
            index += Long.SIZE_BYTES
        }
        while (index < end) {
            val value = bytes[index]
            if (value == JSON_QUOTE || value == JSON_BACKSLASH || value < JSON_FIRST_PRINTABLE_BYTE) {
                return index
            }
            index++
        }
        return end
    }

    override fun stringStop(
        bytes: ByteArray,
        start: Int,
        end: Int,
    ): Int {
        var index = start
        while (end - index >= Long.SIZE_BYTES) {
            val flags = jsonSwarStringStop(jsonLoadWord(bytes = bytes, offset = index))
            if (flags != 0L) {
                return index + (java.lang.Long.numberOfTrailingZeros(flags) ushr JSON_SWAR_BYTE_SHIFT)
            }
            index += Long.SIZE_BYTES
        }
        while (index < end) {
            val value = bytes[index]
            if (value == JSON_QUOTE || value == JSON_BACKSLASH || (value in 0..<JSON_FIRST_PRINTABLE_BYTE)) {
                return index
            }
            index++
        }
        return end
    }

    override fun backslash(
        bytes: ByteArray,
        start: Int,
        end: Int,
    ): Int {
        var index = start
        while (end - index >= Long.SIZE_BYTES) {
            val flags = jsonSwarEquals(jsonLoadWord(bytes = bytes, offset = index), JSON_SWAR_BACKSLASHES)
            if (flags != 0L) {
                return index + (java.lang.Long.numberOfTrailingZeros(flags) ushr JSON_SWAR_BYTE_SHIFT)
            }
            index += Long.SIZE_BYTES
        }
        while (index < end) {
            if (bytes[index] == JSON_BACKSLASH) {
                return index
            }
            index++
        }
        return end
    }

    override fun structuralMask(bytes: ByteArray, start: Int): Long =
        jsonSwarToMask(jsonSwarStructural(jsonLoadWord(bytes = bytes, offset = start)))

    override fun stringEvents(bytes: ByteArray, start: Int): Long =
        jsonSwarToMask(jsonSwarStringStop(jsonLoadWord(bytes = bytes, offset = start)))

    override fun copyBlock(
        source: ByteArray,
        sourceOffset: Int,
        target: ByteArray,
        targetOffset: Int,
    ): Long {
        val word = jsonLoadWord(bytes = source, offset = sourceOffset)
        jsonLongView.set(target, targetOffset, word)
        return jsonSwarToMask(jsonSwarEquals(word, JSON_SWAR_BACKSLASHES))
    }

    override fun containerEnd(
        bytes: ByteArray,
        start: Int,
        end: Int,
        maxDepth: Int,
    ): Int = jsonContainerEnd(scan = this, bytes = bytes, start = start, end = end, maxDepth = maxDepth)

    override fun numbersEnd(
        bytes: ByteArray,
        start: Int,
        end: Int,
    ): Long = jsonSwarNumbersEnd(bytes = bytes, start = start, end = end, initialCommas = 0)

    override fun selfTest(): Boolean = true
}

private object JsonVectorScan : JsonScan {
    private val species: VectorSpecies<Byte> = ByteVector.SPECIES_PREFERRED

    override val laneCount: Int = species.length()

    private fun structural(vector: ByteVector): VectorMask<Byte> {
        val folded = vector.or(JSON_CASE_BIT)
        return vector.compare(VectorOperators.EQ, JSON_QUOTE)
            .or(folded.compare(VectorOperators.EQ, JSON_BRACKET_OPEN_FOLDED))
            .or(folded.compare(VectorOperators.EQ, JSON_BRACKET_CLOSE_FOLDED))
    }

    override fun stringSpecial(
        bytes: ByteArray,
        start: Int,
        end: Int,
    ): Int {
        var index = start
        val probeEnd = minOf(end, start + JSON_VECTOR_PROBE_BYTES)
        while (probeEnd - index >= Long.SIZE_BYTES) {
            val flags = jsonSwarStringSpecial(jsonLoadWord(bytes = bytes, offset = index))
            if (flags != 0L) {
                return index + (java.lang.Long.numberOfTrailingZeros(flags) ushr JSON_SWAR_BYTE_SHIFT)
            }
            index += Long.SIZE_BYTES
        }
        while (end - index >= laneCount) {
            val vector = ByteVector.fromArray(species, bytes, index)
            val special = vector.compare(VectorOperators.EQ, JSON_QUOTE)
                .or(vector.compare(VectorOperators.EQ, JSON_BACKSLASH))
                .or(vector.compare(VectorOperators.LT, JSON_FIRST_PRINTABLE_BYTE))
            val bits = special.toLong()
            if (bits != 0L) {
                return index + java.lang.Long.numberOfTrailingZeros(bits)
            }
            index += laneCount
        }
        return JsonSwarScan.stringSpecial(bytes = bytes, start = index, end = end)
    }

    override fun stringStop(
        bytes: ByteArray,
        start: Int,
        end: Int,
    ): Int {
        var index = start
        val probeEnd = minOf(end, start + JSON_VECTOR_PROBE_BYTES)
        while (probeEnd - index >= Long.SIZE_BYTES) {
            val flags = jsonSwarStringStop(jsonLoadWord(bytes = bytes, offset = index))
            if (flags != 0L) {
                return index + (java.lang.Long.numberOfTrailingZeros(flags) ushr JSON_SWAR_BYTE_SHIFT)
            }
            index += Long.SIZE_BYTES
        }
        while (end - index >= laneCount) {
            val vector = ByteVector.fromArray(species, bytes, index)
            val bits = vector.compare(VectorOperators.EQ, JSON_QUOTE)
                .or(vector.compare(VectorOperators.EQ, JSON_BACKSLASH))
                .or(vector.compare(VectorOperators.ULT, JSON_FIRST_PRINTABLE_BYTE))
                .toLong()
            if (bits != 0L) {
                return index + java.lang.Long.numberOfTrailingZeros(bits)
            }
            index += laneCount
        }
        return JsonSwarScan.stringStop(bytes = bytes, start = index, end = end)
    }

    override fun backslash(
        bytes: ByteArray,
        start: Int,
        end: Int,
    ): Int {
        var index = start
        val probeEnd = minOf(end, start + JSON_VECTOR_PROBE_BYTES)
        while (probeEnd - index >= Long.SIZE_BYTES) {
            val flags = jsonSwarEquals(jsonLoadWord(bytes = bytes, offset = index), JSON_SWAR_BACKSLASHES)
            if (flags != 0L) {
                return index + (java.lang.Long.numberOfTrailingZeros(flags) ushr JSON_SWAR_BYTE_SHIFT)
            }
            index += Long.SIZE_BYTES
        }
        while (end - index >= laneCount) {
            val bits = ByteVector.fromArray(species, bytes, index).compare(VectorOperators.EQ, JSON_BACKSLASH).toLong()
            if (bits != 0L) {
                return index + java.lang.Long.numberOfTrailingZeros(bits)
            }
            index += laneCount
        }
        return JsonSwarScan.backslash(bytes = bytes, start = index, end = end)
    }

    override fun structuralMask(bytes: ByteArray, start: Int): Long =
        structural(ByteVector.fromArray(species, bytes, start)).toLong()

    override fun stringEvents(bytes: ByteArray, start: Int): Long {
        val vector = ByteVector.fromArray(species, bytes, start)
        return vector.compare(VectorOperators.EQ, JSON_QUOTE)
            .or(vector.compare(VectorOperators.EQ, JSON_BACKSLASH))
            .or(vector.compare(VectorOperators.ULT, JSON_FIRST_PRINTABLE_BYTE))
            .toLong()
    }

    override fun copyBlock(
        source: ByteArray,
        sourceOffset: Int,
        target: ByteArray,
        targetOffset: Int,
    ): Long {
        val vector = ByteVector.fromArray(species, source, sourceOffset)
        vector.intoArray(target, targetOffset)
        return vector.compare(VectorOperators.EQ, JSON_BACKSLASH).toLong()
    }

    override fun containerEnd(
        bytes: ByteArray,
        start: Int,
        end: Int,
        maxDepth: Int,
    ): Int = jsonContainerEnd(scan = this, bytes = bytes, start = start, end = end, maxDepth = maxDepth)

    override fun numbersEnd(
        bytes: ByteArray,
        start: Int,
        end: Int,
    ): Long {
        var index = start
        var commas = 0
        while (end - index >= laneCount) {
            val vector = ByteVector.fromArray(species, bytes, index)
            val stops = structural(vector)
            val commaMask = vector.compare(VectorOperators.EQ, JSON_COMMA)
            val stopBits = stops.toLong()
            if (stopBits != 0L) {
                val first = java.lang.Long.numberOfTrailingZeros(stopBits)
                commas += java.lang.Long.bitCount(commaMask.toLong() and ((1L shl first) - 1L))
                return jsonNumbersStop(bytes = bytes, index = index + first, commas = commas)
            }
            commas += commaMask.trueCount()
            index += laneCount
        }
        return jsonSwarNumbersEnd(bytes = bytes, start = index, end = end, initialCommas = commas)
    }

    override fun selfTest(): Boolean {
        if (laneCount !in JSON_MIN_VECTOR_LANES..JSON_MAX_VECTOR_LANES) {
            return false
        }
        val alphabet = JSON_SELF_TEST_ALPHABET.toByteArray(Charsets.ISO_8859_1)
        val size = laneCount * JSON_SELF_TEST_SIZE_LANES + JSON_SELF_TEST_SIZE_EXTRA
        val bytes = ByteArray(size)
        var seed = size
        for (round in 0 until JSON_SELF_TEST_ROUNDS) {
            for (index in bytes.indices) {
                seed = seed * JSON_SELF_TEST_MULTIPLIER + JSON_SELF_TEST_INCREMENT
                val pick = (seed ushr JSON_SELF_TEST_SHIFT) and Int.MAX_VALUE
                bytes[index] = if (pick % alphabet.size == 0 || pick % (alphabet.size * 2) < alphabet.size) {
                    'x'.code.toByte()
                } else {
                    alphabet[pick % alphabet.size]
                }
            }
            val start = round % laneCount
            val end = size - (round % JSON_SELF_TEST_SIZE_EXTRA)
            val depth = JSON_SELF_TEST_SIZE_EXTRA + round % laneCount
            bytes[start] = if (round % 2 == 0) {
                JSON_OBJECT_OPEN
            } else {
                JSON_ARRAY_OPEN
            }
            if (stringSpecial(bytes = bytes, start = start, end = end) !=
                JsonSwarScan.stringSpecial(bytes = bytes, start = start, end = end) ||
                backslash(bytes = bytes, start = start, end = end) !=
                JsonSwarScan.backslash(bytes = bytes, start = start, end = end) ||
                stringStop(bytes = bytes, start = start, end = end) !=
                JsonSwarScan.stringStop(bytes = bytes, start = start, end = end) ||
                numbersEnd(bytes = bytes, start = start + 1, end = end) !=
                JsonSwarScan.numbersEnd(bytes = bytes, start = start + 1, end = end) ||
                containerEnd(bytes = bytes, start = start, end = end, maxDepth = depth) !=
                JsonSwarScan.containerEnd(bytes = bytes, start = start, end = end, maxDepth = depth) ||
                !blockMasksHold(bytes = bytes, start = start)
            ) {
                return false
            }
        }
        return true
    }

    private fun blockMasksHold(bytes: ByteArray, start: Int): Boolean {
        val copy = ByteArray(laneCount)
        val events = stringEvents(bytes = bytes, start = start)
        val escapes = copyBlock(source = bytes, sourceOffset = start, target = copy, targetOffset = 0)
        for (lane in 0 until laneCount) {
            val value = bytes[start + lane]
            val escape = value == JSON_BACKSLASH
            val event = escape || value == JSON_QUOTE || (value in 0..<JSON_FIRST_PRINTABLE_BYTE)
            if (((events ushr lane) and 1L) != 0L != event ||
                ((escapes ushr lane) and 1L) != 0L != escape ||
                copy[lane] != value
            ) {
                return false
            }
        }
        return true
    }
}

private object JsonScans {
    @JvmField
    val active: JsonScan = select()

    private fun select(): JsonScan {
        if (!OPTION_USE_VECTOR_API || !ModuleLayer.boot().findModule(JSON_VECTOR_MODULE).isPresent) {
            return JsonSwarScan
        }
        return try {
            val vector: JsonScan = JsonVectorScan
            if (vector.selfTest()) {
                vector
            } else {
                JsonSwarScan
            }
        } catch (_: LinkageError) {
            JsonSwarScan
        } catch (_: RuntimeException) {
            JsonSwarScan
        }
    }
}
