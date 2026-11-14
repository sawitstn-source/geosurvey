package com.example.spatial

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import com.example.data.local.LatLon
import com.example.data.local.MapBounds
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Represents a Ground Control Point (GCP) pairing a normalized coordinate on a local image
 * `(imageNormX, imageNormY)` in `[0f..1f]` with a WGS84 geographic coordinate `(mapLat, mapLon)`.
 */
data class ControlPoint(
    val id: Int,
    val imageNormX: Float? = null,
    val imageNormY: Float? = null,
    val mapLat: Double? = null,
    val mapLon: Double? = null
) {
    val hasImagePoint: Boolean
        get() = imageNormX != null && imageNormY != null

    val hasMapPoint: Boolean
        get() = mapLat != null && mapLon != null

    val isPaired: Boolean
        get() = hasImagePoint && hasMapPoint
}

/**
 * Forward 2D affine transform mapping normalized image coordinates `(u, v) in [0..1]`
 * into normalized Web Mercator coordinates `(mx, my) in [0..1]`:
 *
 * ```
 * mx = a * u + b * v + c
 * my = d * u + e * v + f
 * ```
 */
data class AffineGeoTransform(
    val a: Double,
    val b: Double,
    val c: Double,
    val d: Double,
    val e: Double,
    val f: Double
) {
    fun imageNormToMercator(u: Double, v: Double): Pair<Double, Double> {
        val mx = a * u + b * v + c
        val my = d * u + e * v + f
        return mx to my
    }

    fun imageNormToLatLon(u: Double, v: Double): LatLon {
        val (mx, my) = imageNormToMercator(u, v)
        return LatLon(
            latitude = GeoMath.normYToLat(my),
            longitude = GeoMath.normXToLon(mx)
        )
    }

    fun latLonToImageNorm(lat: Double, lon: Double): Pair<Double, Double>? {
        val det = a * e - b * d
        if (abs(det) < 1e-22) return null
        val mx = GeoMath.lonToNormX(lon) - c
        val my = GeoMath.latToNormY(lat) - f
        val u = (e * mx - b * my) / det
        val v = (-d * mx + a * my) / det
        return u to v
    }

    val determinant: Double
        get() = a * e - b * d
}

data class GeoreferenceSolution(
    val transform: AffineGeoTransform,
    val bounds: MapBounds,
    val cornersLatLon: List<LatLon>,
    val rmseMeters: Double,
    val residualsMeters: Map<Int, Double>,
    val rotationDeg: Double,
    val gcpCount: Int,
    val modeLabel: String
)

object ImageGeoreferencer {

    /**
     * Solves image-to-Mercator georeferencing from paired [ControlPoint]s.
     * - With 2 GCPs: computes an exact conformal similarity transform (scale + rotation + translation).
     * - With 3+ GCPs: computes a least-squares 2D affine transform and per-point meter residuals.
     *
     * Returns `null` if fewer than 2 points are paired or points are coincident/collinear.
     */
    fun solve(points: List<ControlPoint>): GeoreferenceSolution? {
        val paired = points.filter { it.isPaired }
        if (paired.size < 2) return null

        val samples = paired.map { cp ->
            Sample(
                id = cp.id,
                u = cp.imageNormX!!.toDouble(),
                v = cp.imageNormY!!.toDouble(),
                mx = GeoMath.lonToNormX(cp.mapLon!!),
                my = GeoMath.latToNormY(cp.mapLat!!),
                lat = cp.mapLat,
                lon = cp.mapLon
            )
        }

        val transform = if (samples.size == 2) {
            solveSimilarityTwoPoints(samples[0], samples[1])
        } else {
            solveLeastSquaresAffine(samples)
        } ?: return null

        // Verify non-degenerate linear part
        if (!transform.a.isFinite() || !transform.b.isFinite() ||
            !transform.c.isFinite() || !transform.d.isFinite() ||
            !transform.e.isFinite() || !transform.f.isFinite() ||
            abs(transform.determinant) < 1e-20
        ) {
            return null
        }

        // Transform all 4 corners of the image [0..1, 0..1]
        val cornerNorms = listOf(
            transform.imageNormToMercator(0.0, 0.0), // Top-Left
            transform.imageNormToMercator(1.0, 0.0), // Top-Right
            transform.imageNormToMercator(1.0, 1.0), // Bottom-Right
            transform.imageNormToMercator(0.0, 1.0)  // Bottom-Left
        )
        // Guard against out-of-world projections
        if (cornerNorms.any { (mx, my) ->
                !mx.isFinite() || !my.isFinite() || mx < -0.5 || mx > 1.5 || my <= 0.0001 || my >= 0.9999
            }
        ) {
            return null
        }

        val cornersLatLon = cornerNorms.map { (mx, my) ->
            LatLon(GeoMath.normYToLat(my), GeoMath.normXToLon(mx))
        }
        val bounds = GeoMath.computeBounds(cornersLatLon) ?: return null
        if (!bounds.isValid || bounds.maxLat <= bounds.minLat || bounds.maxLon <= bounds.minLon) {
            return null
        }
        // Prevent absurdly large (> 90 degrees) accidental transforms from collapsing zoom
        if ((bounds.maxLat - bounds.minLat) > 85.0 || (bounds.maxLon - bounds.minLon) > 170.0) {
            return null
        }

        val residuals = LinkedHashMap<Int, Double>(samples.size)
        var sumSq = 0.0
        for (s in samples) {
            val predicted = transform.imageNormToLatLon(s.u, s.v)
            val errM = GeoMath.distanceMeters(LatLon(s.lat, s.lon), predicted)
            residuals[s.id] = errM
            sumSq += errM * errM
        }
        val rmse = sqrt(sumSq / samples.size)
        val rotationDeg = Math.toDegrees(atan2(transform.d, transform.a))

        val modeLabel = if (samples.size == 2) {
            "Similarity 2 Titik"
        } else {
            "Affine (${samples.size} Titik)"
        }

        return GeoreferenceSolution(
            transform = transform,
            bounds = bounds,
            cornersLatLon = cornersLatLon,
            rmseMeters = rmse,
            residualsMeters = residuals,
            rotationDeg = rotationDeg,
            gcpCount = samples.size,
            modeLabel = modeLabel
        )
    }

    private data class Sample(
        val id: Int,
        val u: Double,
        val v: Double,
        val mx: Double,
        val my: Double,
        val lat: Double,
        val lon: Double
    )

    private fun solveSimilarityTwoPoints(p1: Sample, p2: Sample): AffineGeoTransform? {
        val du = p2.u - p1.u
        val dv = p2.v - p1.v
        val denomImg = du * du + dv * dv
        if (denomImg < 1e-8) return null

        val dmx = p2.mx - p1.mx
        val dmy = p2.my - p1.my
        if (dmx * dmx + dmy * dmy < 1e-16) return null

        val a = (dmx * du + dmy * dv) / denomImg
        val bRot = (dmy * du - dmx * dv) / denomImg

        // mx = a * u - bRot * v + tx
        // my = bRot * u + a * v + ty
        val tx = p1.mx - (a * p1.u - bRot * p1.v)
        val ty = p1.my - (bRot * p1.u + a * p1.v)

        return AffineGeoTransform(
            a = a,
            b = -bRot,
            c = tx,
            d = bRot,
            e = a,
            f = ty
        )
    }

    private fun solveLeastSquaresAffine(samples: List<Sample>): AffineGeoTransform? {
        var sUU = 0.0
        var sUV = 0.0
        var sU = 0.0
        var sVV = 0.0
        var sV = 0.0
        val n = samples.size.toDouble()

        var sUMx = 0.0
        var sVMx = 0.0
        var sMx = 0.0

        var sUMy = 0.0
        var sVMy = 0.0
        var sMy = 0.0

        for (s in samples) {
            sUU += s.u * s.u
            sUV += s.u * s.v
            sU += s.u
            sVV += s.v * s.v
            sV += s.v

            sUMx += s.u * s.mx
            sVMx += s.v * s.mx
            sMx += s.mx

            sUMy += s.u * s.my
            sVMy += s.v * s.my
            sMy += s.my
        }

        val mat = arrayOf(
            doubleArrayOf(sUU, sUV, sU),
            doubleArrayOf(sUV, sVV, sV),
            doubleArrayOf(sU, sV, n)
        )

        val coeffX = solveLinear3x3(
            mat.map { it.clone() }.toTypedArray(),
            doubleArrayOf(sUMx, sVMx, sMx)
        ) ?: return null

        val coeffY = solveLinear3x3(
            mat.map { it.clone() }.toTypedArray(),
            doubleArrayOf(sUMy, sVMy, sMy)
        ) ?: return null

        return AffineGeoTransform(
            a = coeffX[0],
            b = coeffX[1],
            c = coeffX[2],
            d = coeffY[0],
            e = coeffY[1],
            f = coeffY[2]
        )
    }

    /**
     * Solves a 3x3 linear system `A * x = b` using partial-pivoting Gaussian elimination.
     */
    internal fun solveLinear3x3(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val m = Array(3) { r -> doubleArrayOf(a[r][0], a[r][1], a[r][2], b[r]) }
        for (col in 0 until 3) {
            var pivot = col
            var maxAbs = abs(m[col][col])
            for (row in col + 1 until 3) {
                val v = abs(m[row][col])
                if (v > maxAbs) {
                    maxAbs = v
                    pivot = row
                }
            }
            if (maxAbs < 1e-13) return null
            if (pivot != col) {
                val tmp = m[col]
                m[col] = m[pivot]
                m[pivot] = tmp
            }
            val div = m[col][col]
            for (j in col until 4) m[col][j] /= div
            for (row in 0 until 3) {
                if (row == col) continue
                val factor = m[row][col]
                if (factor != 0.0) {
                    for (j in col until 4) {
                        m[row][j] -= factor * m[col][j]
                    }
                }
            }
        }
        return doubleArrayOf(m[0][3], m[1][3], m[2][3])
    }

    fun readImageDimensions(filePath: String): Pair<Int, Int>? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(filePath, opts)
        return if (opts.outWidth > 0 && opts.outHeight > 0) {
            opts.outWidth to opts.outHeight
        } else {
            null
        }
    }

    fun decodeSampledBitmap(filePath: String, maxEdgePx: Int = 2048): Bitmap? {
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(filePath, boundsOpts)
        if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) return null

        var inSample = 1
        while (max(boundsOpts.outWidth / inSample, boundsOpts.outHeight / inSample) > maxEdgePx) {
            inSample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = inSample }
        return BitmapFactory.decodeFile(filePath, opts)
    }

    /**
     * Warps/rectifies [srcBitmap] according to [solution] into a north-up Web Mercator PNG
     * matching [GeoreferenceSolution.bounds] with transparent background outside the rotated quad.
     */
    fun rectifyBitmapToPng(
        srcBitmap: Bitmap,
        solution: GeoreferenceSolution,
        outPngFile: File,
        maxEdgePx: Int = 2048
    ): Boolean {
        return runCatching {
            val bounds = solution.bounds
            val minMx = GeoMath.lonToNormX(bounds.minLon)
            val maxMx = GeoMath.lonToNormX(bounds.maxLon)
            val minMy = GeoMath.latToNormY(bounds.maxLat) // top
            val maxMy = GeoMath.latToNormY(bounds.minLat) // bottom
            val spanMx = (maxMx - minMx).coerceAtLeast(1e-9)
            val spanMy = (maxMy - minMy).coerceAtLeast(1e-9)

            val aspect = spanMx / spanMy
            val targetBase = max(srcBitmap.width, srcBitmap.height).coerceIn(512, maxEdgePx)
            val outW: Int
            val outH: Int
            if (aspect >= 1.0) {
                outW = targetBase
                outH = (targetBase / aspect).roundToInt().coerceIn(256, maxEdgePx)
            } else {
                outH = targetBase
                outW = (targetBase * aspect).roundToInt().coerceIn(256, maxEdgePx)
            }

            val sw = srcBitmap.width.toFloat().coerceAtLeast(1f)
            val sh = srcBitmap.height.toFloat().coerceAtLeast(1f)
            val scaleX = outW.toDouble() / spanMx
            val scaleY = outH.toDouble() / spanMy
            val t = solution.transform

            // Direct 2D affine matrix: src pixel (px, py) -> u=px/sw, v=py/sh -> Mercator -> out pixel (ox, oy)
            val matrix = Matrix()
            matrix.setValues(
                floatArrayOf(
                    ((t.a / sw) * scaleX).toFloat(),
                    ((t.b / sh) * scaleX).toFloat(),
                    ((t.c - minMx) * scaleX).toFloat(),
                    ((t.d / sw) * scaleY).toFloat(),
                    ((t.e / sh) * scaleY).toFloat(),
                    ((t.f - minMy) * scaleY).toFloat(),
                    0f,
                    0f,
                    1f
                )
            )

            val outBitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            outBitmap.eraseColor(Color.TRANSPARENT)
            val canvas = Canvas(outBitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(srcBitmap, matrix, paint)

            outPngFile.parentFile?.mkdirs()
            outPngFile.outputStream().use { out ->
                outBitmap.compress(Bitmap.CompressFormat.PNG, 95, out)
            }
            outBitmap.recycle()
            true
        }.getOrDefault(false)
    }
}
