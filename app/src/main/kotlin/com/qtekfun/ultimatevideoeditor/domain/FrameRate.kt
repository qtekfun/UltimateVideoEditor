package com.qtekfun.ultimatevideoeditor.domain

import java.math.BigInteger

/**
 * Rational frame rate (e.g. 60000/1001). All conversions use integer math.
 *
 * Rounding: frames -> time and frames -> samples round half up; time -> frames and
 * samples -> frames floor (the frame that contains the instant). A result that does not
 * fit in Long throws [ArithmeticException].
 */
data class FrameRate(val num: Int, val den: Int) {
    init {
        require(num > 0 && den > 0) { "Frame rate must be positive: $num/$den" }
    }

    fun framesToMicros(frames: Long): Long = mulDiv(frames, MICROS_PER_SECOND * den, num.toLong(), roundHalfUp = true)

    fun microsToFrames(micros: Long): Long = mulDiv(micros, num.toLong(), MICROS_PER_SECOND * den, roundHalfUp = false)

    fun framesToSamples(frames: Long, sampleRate: Int): Long {
        require(sampleRate > 0) { "Sample rate must be positive: $sampleRate" }
        return mulDiv(frames, sampleRate.toLong() * den, num.toLong(), roundHalfUp = true)
    }

    fun samplesToFrames(samples: Long, sampleRate: Int): Long {
        require(sampleRate > 0) { "Sample rate must be positive: $sampleRate" }
        return mulDiv(samples, num.toLong(), sampleRate.toLong() * den, roundHalfUp = false)
    }

    private companion object {
        const val MICROS_PER_SECOND = 1_000_000L
    }
}

private const val SMALL_OPERAND = 1L shl 31

/** Computes a*b/c exactly (floor, or round half up), widening to BigInteger only for large operands. */
internal fun mulDiv(a: Long, b: Long, c: Long, roundHalfUp: Boolean): Long {
    require(c > 0) { "Divisor must be positive" }
    if (a in -SMALL_OPERAND..SMALL_OPERAND && b in -SMALL_OPERAND..SMALL_OPERAND) {
        val product = a * b
        return if (roundHalfUp) Math.floorDiv(2 * product + c, 2 * c) else Math.floorDiv(product, c)
    }
    val product = BigInteger.valueOf(a).multiply(BigInteger.valueOf(b))
    val bigC = BigInteger.valueOf(c)
    val numerator = if (roundHalfUp) product.shiftLeft(1).add(bigC) else product
    val divisor = if (roundHalfUp) bigC.shiftLeft(1) else bigC
    val (quotient, remainder) = numerator.divideAndRemainder(divisor).let { it[0] to it[1] }
    val floor = if (remainder.signum() < 0) quotient.subtract(BigInteger.ONE) else quotient
    return floor.longValueExact()
}
