package com.ultimatevideo.uveditor.domain

/**
 * One tone curve: control points (x, y) in 0..1 with strictly increasing x, at least the two end points
 * and at most [MAX_POINTS]. The curve is the monotone cubic (Fritsch-Carlson) through them, flat before
 * the first and after the last point, so it never overshoots between points.
 */
data class GradeCurve(val points: List<CurvePoint> = IDENTITY_POINTS) {
    val isIdentity: Boolean get() = points == IDENTITY_POINTS

    fun problem(): String? {
        if (points.size < 2) return "a curve needs at least two points"
        if (points.size > MAX_POINTS) return "a curve has at most $MAX_POINTS points"
        for ((i, p) in points.withIndex()) {
            if (!(p.x.isFinite() && p.y.isFinite() && p.x in 0.0..1.0 && p.y in 0.0..1.0)) {
                return "curve point ${i + 1} must be inside 0..1"
            }
            if (i > 0 && p.x <= points[i - 1].x) return "curve points must have increasing x"
        }
        return null
    }

    /** The curve's value at [x] (clamped to 0..1). */
    fun valueAt(x: Double): Double {
        val v = x.coerceIn(0.0, 1.0)
        val n = points.size
        if (n == 0) return v
        if (v <= points.first().x) return points.first().y
        if (v >= points.last().x) return points.last().y
        val tangents = tangents()
        var i = 0
        while (i < n - 2 && v > points[i + 1].x) i++
        val p0 = points[i]
        val p1 = points[i + 1]
        val h = p1.x - p0.x
        val t = (v - p0.x) / h
        val t2 = t * t
        val t3 = t2 * t
        val y = (2 * t3 - 3 * t2 + 1) * p0.y + (t3 - 2 * t2 + t) * h * tangents[i] +
            (-2 * t3 + 3 * t2) * p1.y + (t3 - t2) * h * tangents[i + 1]
        return y.coerceIn(0.0, 1.0)
    }

    /** [GradeCurves.SAMPLES] samples at x = i / (SAMPLES - 1). */
    fun bake(): DoubleArray = DoubleArray(GradeCurves.SAMPLES) { valueAt(it.toDouble() / (GradeCurves.SAMPLES - 1)) }

    private fun tangents(): DoubleArray {
        val n = points.size
        val delta = DoubleArray(n - 1) { (points[it + 1].y - points[it].y) / (points[it + 1].x - points[it].x) }
        val m = DoubleArray(n)
        m[0] = delta[0]
        m[n - 1] = delta[n - 2]
        for (i in 1 until n - 1) m[i] = if (delta[i - 1] * delta[i] <= 0.0) 0.0 else (delta[i - 1] + delta[i]) / 2.0
        // Fritsch-Carlson: keep each segment monotone.
        for (i in 0 until n - 1) {
            if (delta[i] == 0.0) {
                m[i] = 0.0
                m[i + 1] = 0.0
            } else {
                val a = m[i] / delta[i]
                val b = m[i + 1] / delta[i]
                val s = a * a + b * b
                if (s > 9.0) {
                    val tau = 3.0 / kotlin.math.sqrt(s)
                    m[i] = tau * a * delta[i]
                    m[i + 1] = tau * b * delta[i]
                }
            }
        }
        return m
    }

    companion object {
        const val MAX_POINTS = 8
        val IDENTITY_POINTS = listOf(CurvePoint(0.0, 0.0), CurvePoint(1.0, 1.0))
    }
}

data class CurvePoint(val x: Double, val y: Double)

/** The four curves of a colour grade: the master (applied first) and one per channel. */
data class GradeCurves(
    val master: GradeCurve = GradeCurve(),
    val red: GradeCurve = GradeCurve(),
    val green: GradeCurve = GradeCurve(),
    val blue: GradeCurve = GradeCurve(),
) {
    val isIdentity: Boolean get() = master.isIdentity && red.isIdentity && green.isIdentity && blue.isIdentity

    fun problem(): String? =
        master.problem()?.let { "master: $it" } ?: red.problem()?.let { "red: $it" } ?: green.problem()?.let { "green: $it" }
            ?: blue.problem()?.let { "blue: $it" }

    /** The curves as [SAMPLES] x (master, red, green, blue), the wire form that `render/grade_math.h` reads. */
    fun bake(): DoubleArray {
        val m = master.bake()
        val r = red.bake()
        val g = green.bake()
        val b = blue.bake()
        return DoubleArray(SAMPLES * 4) { i ->
            when (i % 4) {
                0 -> m[i / 4]
                1 -> r[i / 4]
                2 -> g[i / 4]
                else -> b[i / 4]
            }
        }
    }

    companion object {
        /** Samples per curve on the wire; the shader interpolates linearly between them. */
        const val SAMPLES = 33
        val IDENTITY = GradeCurves()
    }
}

/**
 * A saved grade ("look"): the colour grade's values and curves under a name, so it can be applied to other
 * clips. [values] follows `EffectType.COLOR_GRADE.params`.
 */
data class GradeLook(
    val id: String,
    val name: String,
    val values: List<Double>,
    val curves: GradeCurves = GradeCurves.IDENTITY,
) {
    fun problem(): String? {
        if (id.isBlank()) return "look id must not be blank"
        if (name.isBlank()) return "look name must not be blank"
        return Effect("look", EffectType.COLOR_GRADE, values, curves.takeUnless { it.isIdentity }).problem()
    }

    /** A fresh colour grade effect carrying this look. */
    fun toEffect(effectId: String): Effect =
        Effect(effectId, EffectType.COLOR_GRADE, values, curves.takeUnless { it.isIdentity })

    companion object {
        fun of(id: String, name: String, effect: Effect): GradeLook =
            GradeLook(id, name, effect.values, effect.curves ?: GradeCurves.IDENTITY)
    }
}
