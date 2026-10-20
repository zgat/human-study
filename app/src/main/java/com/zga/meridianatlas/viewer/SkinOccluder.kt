package com.zga.meridianatlas.viewer

import com.zga.meridianatlas.model.Vec3
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Immutable, two-sided skin mesh BVH. Distances and the self-surface tolerance are in metres. */
internal class SkinOccluder private constructor(private val triangles: FloatArray) {
    private val triangleCount = triangles.size / 9
    private val order = IntArray(triangleCount) { it }
    private val centroids = FloatArray(triangleCount * 3)
    private val capacity: Int = run {
        var leaves = 1
        while (leaves * LEAF_SIZE < triangleCount) leaves *= 2
        leaves * 2 - 1
    }
    private val bounds = FloatArray(capacity * 6)
    private val left = IntArray(capacity) { -1 }
    private val right = IntArray(capacity) { -1 }
    private val start = IntArray(capacity)
    private val count = IntArray(capacity)
    private var nodeCount = 0
    private val scratch = ThreadLocal.withInitial { RayScratch() }

    init {
        for (triangle in 0 until triangleCount) {
            val offset = triangle * 9
            for (axis in 0..2) centroids[triangle * 3 + axis] =
                (triangles[offset + axis] + triangles[offset + 3 + axis] +
                    triangles[offset + 6 + axis]) / 3f
        }
        build(0, triangleCount)
    }

    fun isOccluded(eye: Vec3, target: Vec3, tolerance: Float = 0.008f): Boolean {
        val dx = target.x - eye.x
        val dy = target.y - eye.y
        val dz = target.z - eye.z
        val distance = sqrt(dx * dx + dy * dy + dz * dz)
        if (!distance.isFinite() || distance <= tolerance) return false
        return nearest(eye, dx / distance, dy / distance, dz / distance,
            distance - tolerance) < Float.POSITIVE_INFINITY
    }

    /** Direction is normalized internally; returns the nearest positive distance, or null. */
    fun closestHit(eye: Vec3, direction: Vec3, maximumDistance: Float = Float.POSITIVE_INFINITY): Float? {
        val length = sqrt(direction.x * direction.x + direction.y * direction.y + direction.z * direction.z)
        if (!length.isFinite() || length < 0.000001f || maximumDistance <= 0f) return null
        val hit = nearest(eye, direction.x / length, direction.y / length,
            direction.z / length, maximumDistance)
        return hit.takeIf { it.isFinite() }
    }

    /** Closest actual triangle point within 5 cm, for display-route interpolation only. */
    fun closestSurface(point: Vec3, maximumDistance: Float = .05f): Vec3? {
        if (maximumDistance <= 0f || !maximumDistance.isFinite()) return null
        var squaredLimit = maximumDistance * maximumDistance
        val work = scratch.get()!!
        var size = 1
        work.nodes[0] = 0
        work.distances[0] = pointBoxDistanceSquared(0, point)
        var result: Vec3? = null
        while (size > 0) {
            --size
            val node = work.nodes[size]
            if (work.distances[size] > squaredLimit) continue
            if (count[node] > 0) {
                for (slot in start[node] until start[node] + count[node]) {
                    closestTrianglePoint(order[slot], point, work.point)
                    val x = work.point[0] - point.x
                    val y = work.point[1] - point.y
                    val z = work.point[2] - point.z
                    val squared = x*x + y*y + z*z
                    if (squared <= squaredLimit) {
                        squaredLimit = squared
                        result = Vec3(work.point[0], work.point[1], work.point[2])
                    }
                }
            } else {
                val a = left[node]
                val b = right[node]
                val da = pointBoxDistanceSquared(a, point)
                val db = pointBoxDistanceSquared(b, point)
                if (da < db) {
                    if (db <= squaredLimit) { work.nodes[size] = b; work.distances[size++] = db }
                    if (da <= squaredLimit) { work.nodes[size] = a; work.distances[size++] = da }
                } else {
                    if (da <= squaredLimit) { work.nodes[size] = a; work.distances[size++] = da }
                    if (db <= squaredLimit) { work.nodes[size] = b; work.distances[size++] = db }
                }
            }
        }
        return result
    }

    private fun pointBoxDistanceSquared(node: Int, point: Vec3): Float {
        val b = node * 6
        var squared = 0f
        for (axis in 0..2) {
            val p = when (axis) { 0 -> point.x; 1 -> point.y; else -> point.z }
            val delta = maxOf(bounds[b + axis] - p, 0f, p - bounds[b + 3 + axis])
            squared += delta * delta
        }
        return squared
    }

    /** Voronoi-region closest point (vertices, edges, then face), with degenerate-edge fallback. */
    private fun closestTrianglePoint(triangle: Int, p: Vec3, out: FloatArray) {
        val o = triangle * 9
        val ax = triangles[o]; val ay = triangles[o+1]; val az = triangles[o+2]
        val bx = triangles[o+3]; val by = triangles[o+4]; val bz = triangles[o+5]
        val cx = triangles[o+6]; val cy = triangles[o+7]; val cz = triangles[o+8]
        val abx = bx-ax; val aby = by-ay; val abz = bz-az
        val acx = cx-ax; val acy = cy-ay; val acz = cz-az
        fun put(x: Float, y: Float, z: Float) { out[0]=x; out[1]=y; out[2]=z }
        val nx = aby*acz-abz*acy; val ny = abz*acx-abx*acz; val nz = abx*acy-aby*acx
        if (nx*nx+ny*ny+nz*nz < 1e-20f) {
            var best = Float.POSITIVE_INFINITY
            fun edge(x: Float, y: Float, z: Float, ex: Float, ey: Float, ez: Float) {
                val length = ex*ex+ey*ey+ez*ez
                val t = if (length > 0f) (((p.x-x)*ex+(p.y-y)*ey+(p.z-z)*ez)/length).coerceIn(0f,1f) else 0f
                val qx=x+t*ex; val qy=y+t*ey; val qz=z+t*ez
                val distance=(p.x-qx)*(p.x-qx)+(p.y-qy)*(p.y-qy)+(p.z-qz)*(p.z-qz)
                if (distance < best) { best=distance; put(qx,qy,qz) }
            }
            edge(ax,ay,az,abx,aby,abz)
            edge(ax,ay,az,acx,acy,acz)
            edge(bx,by,bz,cx-bx,cy-by,cz-bz)
            return
        }
        val apx=p.x-ax; val apy=p.y-ay; val apz=p.z-az
        val d1=abx*apx+aby*apy+abz*apz; val d2=acx*apx+acy*apy+acz*apz
        if (d1 <= 0f && d2 <= 0f) { put(ax,ay,az); return }
        val bpx=p.x-bx; val bpy=p.y-by; val bpz=p.z-bz
        val d3=abx*bpx+aby*bpy+abz*bpz; val d4=acx*bpx+acy*bpy+acz*bpz
        if (d3 >= 0f && d4 <= d3) { put(bx,by,bz); return }
        val vc=d1*d4-d3*d2
        if (vc <= 0f && d1 >= 0f && d3 <= 0f) {
            val v=d1/(d1-d3); put(ax+v*abx,ay+v*aby,az+v*abz); return
        }
        val cpx=p.x-cx; val cpy=p.y-cy; val cpz=p.z-cz
        val d5=abx*cpx+aby*cpy+abz*cpz; val d6=acx*cpx+acy*cpy+acz*cpz
        if (d6 >= 0f && d5 <= d6) { put(cx,cy,cz); return }
        val vb=d5*d2-d1*d6
        if (vb <= 0f && d2 >= 0f && d6 <= 0f) {
            val w=d2/(d2-d6); put(ax+w*acx,ay+w*acy,az+w*acz); return
        }
        val va=d3*d6-d5*d4
        if (va <= 0f && d4-d3 >= 0f && d5-d6 >= 0f) {
            val w=(d4-d3)/((d4-d3)+(d5-d6)); put(bx+w*(cx-bx),by+w*(cy-by),bz+w*(cz-bz)); return
        }
        val inverse=1f/(va+vb+vc)
        val v=vb*inverse; val w=vc*inverse
        put(ax+abx*v+acx*w,ay+aby*v+acy*w,az+abz*v+acz*w)
    }

    private fun build(first: Int, end: Int): Int {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Skin BVH build cancelled")
        val node = nodeCount++
        var minX = Float.POSITIVE_INFINITY
        var minY = minX
        var minZ = minX
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = maxX
        var maxZ = maxX
        var centerMinX = Float.POSITIVE_INFINITY
        var centerMinY = centerMinX
        var centerMinZ = centerMinX
        var centerMaxX = Float.NEGATIVE_INFINITY
        var centerMaxY = centerMaxX
        var centerMaxZ = centerMaxX
        for (slot in first until end) {
            val triangle = order[slot]
            val offset = triangle * 9
            for (vertex in 0..2) {
                val at = offset + vertex * 3
                minX = min(minX, triangles[at]); maxX = max(maxX, triangles[at])
                minY = min(minY, triangles[at + 1]); maxY = max(maxY, triangles[at + 1])
                minZ = min(minZ, triangles[at + 2]); maxZ = max(maxZ, triangles[at + 2])
            }
            val c = triangle * 3
            centerMinX = min(centerMinX, centroids[c]); centerMaxX = max(centerMaxX, centroids[c])
            centerMinY = min(centerMinY, centroids[c + 1]); centerMaxY = max(centerMaxY, centroids[c + 1])
            centerMinZ = min(centerMinZ, centroids[c + 2]); centerMaxZ = max(centerMaxZ, centroids[c + 2])
        }
        val b = node * 6
        bounds[b] = minX; bounds[b + 1] = minY; bounds[b + 2] = minZ
        bounds[b + 3] = maxX; bounds[b + 4] = maxY; bounds[b + 5] = maxZ
        start[node] = first
        count[node] = end - first
        if (end - first > LEAF_SIZE) {
            val x = centerMaxX - centerMinX
            val y = centerMaxY - centerMinY
            val z = centerMaxZ - centerMinZ
            val axis = if (x >= y && x >= z) 0 else if (y >= z) 1 else 2
            val middle = (first + end) ushr 1
            partition(first, end - 1, middle, axis)
            count[node] = 0
            left[node] = build(first, middle)
            right[node] = build(middle, end)
        }
        return node
    }

    /** In-place quickselect avoids an allocation-heavy comparator sort at every BVH level. */
    private fun partition(first: Int, last: Int, median: Int, axis: Int) {
        var low = first
        var high = last
        while (low < high) {
            val pivot = centroids[order[(low + high) ushr 1] * 3 + axis]
            var i = low
            var j = high
            while (i <= j) {
                while (i <= high && centroids[order[i] * 3 + axis] < pivot) ++i
                while (j >= low && centroids[order[j] * 3 + axis] > pivot) --j
                if (i <= j) {
                    val value = order[i]; order[i] = order[j]; order[j] = value
                    ++i; --j
                }
            }
            when {
                median <= j -> high = j
                median >= i -> low = i
                else -> return
            }
        }
    }

    private fun nearest(eye: Vec3, dx: Float, dy: Float, dz: Float, limit: Float): Float {
        var closest = limit
        var found = false
        val work = scratch.get()!!
        val rootDistance = boxDistance(0, eye, dx, dy, dz, closest)
        if (!rootDistance.isFinite()) return Float.POSITIVE_INFINITY
        var size = 1
        work.nodes[0] = 0
        work.distances[0] = rootDistance
        while (size > 0) {
            --size
            val node = work.nodes[size]
            if (work.distances[size] >= closest) continue
            if (count[node] > 0) {
                for (slot in start[node] until start[node] + count[node]) {
                    val hit = triangleDistance(order[slot], eye, dx, dy, dz)
                    if (hit < closest) {
                        closest = hit
                        found = true
                    }
                }
            } else {
                val a = left[node]
                val b = right[node]
                val da = boxDistance(a, eye, dx, dy, dz, closest)
                val db = boxDistance(b, eye, dx, dy, dz, closest)
                // Stack is LIFO: visit the nearer child first to prune the farther one sooner.
                if (da < db) {
                    if (db.isFinite()) { work.nodes[size] = b; work.distances[size++] = db }
                    if (da.isFinite()) { work.nodes[size] = a; work.distances[size++] = da }
                } else {
                    if (da.isFinite()) { work.nodes[size] = a; work.distances[size++] = da }
                    if (db.isFinite()) { work.nodes[size] = b; work.distances[size++] = db }
                }
            }
        }
        return if (found) closest else Float.POSITIVE_INFINITY
    }

    private fun boxDistance(node: Int, eye: Vec3, dx: Float, dy: Float, dz: Float, limit: Float): Float {
        val b = node * 6
        var near = 0f
        var far = limit
        for (axis in 0..2) {
            val origin = when (axis) { 0 -> eye.x; 1 -> eye.y; else -> eye.z }
            val direction = when (axis) { 0 -> dx; 1 -> dy; else -> dz }
            val minimum = bounds[b + axis]
            val maximum = bounds[b + 3 + axis]
            if (abs(direction) < 0.00000001f) {
                if (origin < minimum || origin > maximum) return Float.POSITIVE_INFINITY
            } else {
                val first = (minimum - origin) / direction
                val second = (maximum - origin) / direction
                near = max(near, min(first, second))
                far = min(far, max(first, second))
                if (far < near) return Float.POSITIVE_INFINITY
            }
        }
        return if (near <= limit && far >= 0f) near else Float.POSITIVE_INFINITY
    }

    private fun triangleDistance(triangle: Int, eye: Vec3, dx: Float, dy: Float, dz: Float): Float {
        val o = triangle * 9
        val e1x = triangles[o + 3] - triangles[o]
        val e1y = triangles[o + 4] - triangles[o + 1]
        val e1z = triangles[o + 5] - triangles[o + 2]
        val e2x = triangles[o + 6] - triangles[o]
        val e2y = triangles[o + 7] - triangles[o + 1]
        val e2z = triangles[o + 8] - triangles[o + 2]
        val px = dy * e2z - dz * e2y
        val py = dz * e2x - dx * e2z
        val pz = dx * e2y - dy * e2x
        val determinant = e1x * px + e1y * py + e1z * pz
        if (abs(determinant) < 0.0000000001f) return Float.POSITIVE_INFINITY
        val inverse = 1f / determinant
        val sx = eye.x - triangles[o]
        val sy = eye.y - triangles[o + 1]
        val sz = eye.z - triangles[o + 2]
        val u = (sx * px + sy * py + sz * pz) * inverse
        if (u < -0.00001f || u > 1.00001f) return Float.POSITIVE_INFINITY
        val qx = sy * e1z - sz * e1y
        val qy = sz * e1x - sx * e1z
        val qz = sx * e1y - sy * e1x
        val v = (dx * qx + dy * qy + dz * qz) * inverse
        if (v < -0.00001f || u + v > 1.00001f) return Float.POSITIVE_INFINITY
        val distance = (e2x * qx + e2y * qy + e2z * qz) * inverse
        return if (distance > 0.00001f) distance else Float.POSITIVE_INFINITY
    }

    private class RayScratch {
        // Balanced median splitting of at most 1M triangles needs fewer than 32 stack slots.
        val nodes = IntArray(64)
        val distances = FloatArray(64)
        val point = FloatArray(3)
    }

    companion object {
        private const val LEAF_SIZE = 8
        private const val MAX_TRIANGLES = 1_000_000

        fun readFrom(input: InputStream): SkinOccluder {
            val source = DataInputStream(input)
            val header = ByteArray(8)
            source.readFully(header)
            if (!header.copyOfRange(0, 4).contentEquals(byteArrayOf(77, 83, 79, 49)))
                throw IOException("Invalid skin occlusion magic; expected MSO1")
            val count = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt(4)
            if (count !in 1..MAX_TRIANGLES) throw IOException("Invalid skin triangle count: $count")
            val bytes = ByteArray(count * 36)
            source.readFully(bytes)
            if (source.read() != -1) throw IOException("Trailing bytes in skin occlusion mesh")
            val triangles = FloatArray(count * 9)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(triangles)
            if (triangles.any { !it.isFinite() }) throw IOException("Non-finite skin vertex")
            return SkinOccluder(triangles)
        }

        internal fun fromTriangles(triangles: FloatArray): SkinOccluder {
            require(triangles.isNotEmpty() && triangles.size % 9 == 0)
            require(triangles.size / 9 <= MAX_TRIANGLES && triangles.all { it.isFinite() })
            return SkinOccluder(triangles.copyOf())
        }
    }
}
