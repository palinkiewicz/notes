package pl.dakil.notes.model

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * An axis-aligned rectangle in note units (points, 1/72 inch).
 *
 * Deliberately not `androidx.compose.ui.geometry.Rect` so the whole model stays on plain JVM
 * Kotlin and is unit-testable without an Android runtime.
 */
data class Rect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val isEmpty: Boolean get() = right <= left || bottom <= top

    fun contains(x: Float, y: Float): Boolean = x >= left && x <= right && y >= top && y <= bottom

    fun intersects(other: Rect): Boolean =
        left <= other.right && other.left <= right && top <= other.bottom && other.top <= bottom

    fun union(other: Rect): Rect = Rect(
        min(left, other.left),
        min(top, other.top),
        max(right, other.right),
        max(bottom, other.bottom),
    )

    /** Grows the rectangle by [amount] on every side. Used to pad hit-test and invalidation bounds. */
    fun inflate(amount: Float): Rect = Rect(left - amount, top - amount, right + amount, bottom + amount)

    companion object {
        val ZERO = Rect(0f, 0f, 0f, 0f)

        /** An intentionally inverted rectangle, so the first [union] adopts the operand exactly. */
        val EMPTY = Rect(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)

        fun of(x0: Float, y0: Float, x1: Float, y1: Float): Rect =
            Rect(min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1))
    }
}

/**
 * A 2D affine transform using the SVG/CSS `matrix(a, b, c, d, tx, ty)` convention:
 *
 * ```
 * x' = a*x + c*y + tx
 * y' = b*x + d*y + ty
 * ```
 *
 * This is the same six-number ordering serialised in `.daknote`, so no reordering happens at
 * the I/O boundary.
 */
data class Affine(
    val a: Float, val b: Float,
    val c: Float, val d: Float,
    val tx: Float, val ty: Float,
) {
    val isIdentity: Boolean
        get() = a == 1f && b == 0f && c == 0f && d == 1f && tx == 0f && ty == 0f

    fun mapX(x: Float, y: Float): Float = a * x + c * y + tx
    fun mapY(x: Float, y: Float): Float = b * x + d * y + ty

    /** Returns `this` applied after [other] — i.e. `this * other` in column-vector notation. */
    fun then(other: Affine): Affine = Affine(
        a = other.a * a + other.b * c,
        b = other.a * b + other.b * d,
        c = other.c * a + other.d * c,
        d = other.c * b + other.d * d,
        tx = other.tx * a + other.ty * c + tx,
        ty = other.tx * b + other.ty * d + ty,
    )

    fun invert(): Affine? {
        val det = a * d - b * c
        if (abs(det) < 1e-9f) return null
        val ia = d / det
        val ib = -b / det
        val ic = -c / det
        val id = a / det
        return Affine(ia, ib, ic, id, -(tx * ia + ty * ic), -(tx * ib + ty * id))
    }

    /** Transforms all four corners and returns their bounding box. */
    fun mapBounds(r: Rect): Rect {
        if (isIdentity) return r
        var out = Rect.EMPTY
        val xs = floatArrayOf(r.left, r.right, r.right, r.left)
        val ys = floatArrayOf(r.top, r.top, r.bottom, r.bottom)
        for (i in 0..3) {
            val x = mapX(xs[i], ys[i])
            val y = mapY(xs[i], ys[i])
            out = out.union(Rect(x, y, x, y))
        }
        return out
    }

    companion object {
        val IDENTITY = Affine(1f, 0f, 0f, 1f, 0f, 0f)

        fun translate(dx: Float, dy: Float): Affine = Affine(1f, 0f, 0f, 1f, dx, dy)

        fun scale(sx: Float, sy: Float, pivotX: Float = 0f, pivotY: Float = 0f): Affine =
            Affine(sx, 0f, 0f, sy, pivotX - sx * pivotX, pivotY - sy * pivotY)

        fun rotate(radians: Float, pivotX: Float = 0f, pivotY: Float = 0f): Affine {
            val cos = kotlin.math.cos(radians)
            val sin = kotlin.math.sin(radians)
            return Affine(
                a = cos, b = sin, c = -sin, d = cos,
                tx = pivotX - cos * pivotX + sin * pivotY,
                ty = pivotY - sin * pivotX - cos * pivotY,
            )
        }
    }
}
