// 极简 3D 数学库（列主序，与 OpenGL 一致）
package com.cf.transportship.engine

import kotlin.math.*

class Vec3(var x: Float = 0f, var y: Float = 0f, var z: Float = 0f) {
    fun set(x: Float, y: Float, z: Float): Vec3 { this.x = x; this.y = y; this.z = z; return this }
    fun set(v: Vec3): Vec3 { x = v.x; y = v.y; z = v.z; return this }
    fun copy(): Vec3 = Vec3(x, y, z)
    fun add(v: Vec3): Vec3 { x += v.x; y += v.y; z += v.z; return this }
    fun addScaled(v: Vec3, s: Float): Vec3 { x += v.x * s; y += v.y * s; z += v.z * s; return this }
    fun sub(v: Vec3): Vec3 { x -= v.x; y -= v.y; z -= v.z; return this }
    fun mul(s: Float): Vec3 { x *= s; y *= s; z *= s; return this }
    fun dot(v: Vec3) = x * v.x + y * v.y + z * v.z
    fun len() = sqrt(x * x + y * y + z * z)
    fun lenXZ() = sqrt(x * x + z * z)
    fun normalize(): Vec3 {
        val l = len()
        return if (l > 1e-9f) { x /= l; y /= l; z /= l; this } else set(0f, 0f, 0f)
    }
    fun dist(v: Vec3): Float { val dx = x - v.x; val dy = y - v.y; val dz = z - v.z; return sqrt(dx * dx + dy * dy + dz * dz) }
    fun distXZ(v: Vec3): Float { val dx = x - v.x; val dz = z - v.z; return sqrt(dx * dx + dz * dz) }
    fun cross(v: Vec3): Vec3 = Vec3(y * v.z - z * v.y, z * v.x - x * v.z, x * v.y - y * v.x)
    fun lerp(v: Vec3, t: Float): Vec3 { x += (v.x - x) * t; y += (v.y - y) * t; z += (v.z - z) * t; return this }
    override fun toString() = "($x,$y,$z)"
}

class Vec2(var x: Float = 0f, var y: Float = 0f) {
    fun set(x: Float, y: Float): Vec2 { this.x = x; this.y = y; return this }
    fun copy() = Vec2(x, y)
}

// 列主序 4x4
class Mat4 {
    val m = FloatArray(16)

    init { identity() }

    fun identity(): Mat4 {
        java.util.Arrays.fill(m, 0f)
        m[0] = 1f; m[5] = 1f; m[10] = 1f; m[15] = 1f
        return this
    }

    fun set(o: Mat4): Mat4 { System.arraycopy(o.m, 0, m, 0, 16); return this }

    fun multiply(b: Mat4): Mat4 { // this = this * b
        val a = m.copyOf()
        for (c in 0..3) for (r in 0..3) {
            var s = 0f
            for (k in 0..3) s += a[k * 4 + r] * b.m[c * 4 + k]
            m[c * 4 + r] = s
        }
        return this
    }

    fun copy(): Mat4 { val r = Mat4(); System.arraycopy(m, 0, r.m, 0, 16); return r }

    fun transformPoint(v: Vec3): Vec3 {
        val x = v.x; val y = v.y; val z = v.z
        return Vec3(m[0] * x + m[4] * y + m[8] * z + m[12], m[1] * x + m[5] * y + m[9] * z + m[13], m[2] * x + m[6] * y + m[10] * z + m[14])
    }

    fun transformPoint(v: Vec3, out: Vec3): Vec3 {
        val x = v.x; val y = v.y; val z = v.z
        out.set(m[0] * x + m[4] * y + m[8] * z + m[12], m[1] * x + m[5] * y + m[9] * z + m[13], m[2] * x + m[6] * y + m[10] * z + m[14])
        return out
    }

    fun transformDir(v: Vec3): Vec3 {
        val x = v.x; val y = v.y; val z = v.z
        return Vec3(m[0] * x + m[4] * y + m[8] * z, m[1] * x + m[5] * y + m[9] * z, m[2] * x + m[6] * y + m[10] * z)
    }

    fun invert(): Mat4 {
        val a00 = m[0]; val a01 = m[1]; val a02 = m[2]; val a03 = m[3]
        val a10 = m[4]; val a11 = m[5]; val a12 = m[6]; val a13 = m[7]
        val a20 = m[8]; val a21 = m[9]; val a22 = m[10]; val a23 = m[11]
        val a30 = m[12]; val a31 = m[13]; val a32 = m[14]; val a33 = m[15]
        val b00 = a00 * a11 - a01 * a10
        val b01 = a00 * a12 - a02 * a10
        val b02 = a00 * a13 - a03 * a10
        val b03 = a01 * a12 - a02 * a11
        val b04 = a01 * a13 - a03 * a11
        val b05 = a02 * a13 - a03 * a12
        val b06 = a20 * a31 - a21 * a30
        val b07 = a20 * a32 - a22 * a30
        val b08 = a20 * a33 - a23 * a30
        val b09 = a21 * a32 - a22 * a31
        val b10 = a21 * a33 - a23 * a31
        val b11 = a22 * a33 - a23 * a32
        var det = b00 * b11 - b01 * b10 + b02 * b09 + b03 * b08 - b04 * b07 + b05 * b06
        if (abs(det) < 1e-12f) { identity(); return this }
        det = 1f / det
        m[0] = (a11 * b11 - a12 * b10 + a13 * b09) * det
        m[1] = (a02 * b10 - a01 * b11 - a03 * b09) * det
        m[2] = (a31 * b05 - a32 * b04 + a33 * b03) * det
        m[3] = (a22 * b04 - a21 * b05 - a23 * b03) * det
        m[4] = (a12 * b08 - a10 * b11 - a13 * b07) * det
        m[5] = (a00 * b11 - a02 * b08 + a03 * b07) * det
        m[6] = (a32 * b02 - a30 * b05 - a33 * b01) * det
        m[7] = (a20 * b05 - a22 * b02 + a23 * b01) * det
        m[8] = (a10 * b10 - a11 * b08 + a13 * b06) * det
        m[9] = (a01 * b08 - a00 * b10 - a03 * b06) * det
        m[10] = (a30 * b04 - a31 * b02 + a33 * b00) * det
        m[11] = (a21 * b02 - a20 * b04 - a23 * b00) * det
        m[12] = (a11 * b07 - a10 * b09 - a12 * b06) * det
        m[13] = (a00 * b09 - a01 * b07 + a02 * b06) * det
        m[14] = (a31 * b01 - a30 * b03 - a32 * b00) * det
        m[15] = (a20 * b03 - a21 * b01 + a22 * b00) * det
        return this
    }

    fun compose(px: Float, py: Float, pz: Float, q: Quat, sx: Float, sy: Float, sz: Float): Mat4 {
        val x = q.x; val y = q.y; val z = q.z; val w = q.w
        val x2 = x + x; val y2 = y + y; val z2 = z + z
        val xx = x * x2; val xy = x * y2; val xz = x * z2
        val yy = y * y2; val yz = y * z2; val zz = z * z2
        val wx = w * x2; val wy = w * y2; val wz = w * z2
        m[0] = (1 - (yy + zz)) * sx; m[1] = (xy + wz) * sx; m[2] = (xz - wy) * sx; m[3] = 0f
        m[4] = (xy - wz) * sy; m[5] = (1 - (xx + zz)) * sy; m[6] = (yz + wx) * sy; m[7] = 0f
        m[8] = (xz + wy) * sz; m[9] = (yz - wx) * sz; m[10] = (1 - (xx + yy)) * sz; m[11] = 0f
        m[12] = px; m[13] = py; m[14] = pz; m[15] = 1f
        return this
    }

    fun lookAt(eye: Vec3, target: Vec3, up: Vec3): Mat4 {
        var zx = eye.x - target.x; var zy = eye.y - target.y; var zz = eye.z - target.z
        var l = sqrt(zx * zx + zy * zy + zz * zz)
        if (l < 1e-9f) { zz = 1f; l = 1f }
        zx /= l; zy /= l; zz /= l
        var xx = up.y * zz - up.z * zy; var xy = up.z * zx - up.x * zz; var xz = up.x * zy - up.y * zx
        l = sqrt(xx * xx + xy * xy + xz * xz)
        if (l < 1e-9f) {
            if (abs(up.z) == 1f) zx += 1e-4f else zy += 1e-4f
            l = sqrt(zx * zx + zy * zy + zz * zz); zx /= l; zy /= l; zz /= l
            xx = up.y * zz - up.z * zy; xy = up.z * zx - up.x * zz; xz = up.x * zy - up.y * zx
            l = sqrt(xx * xx + xy * xy + xz * xz)
        }
        xx /= l; xy /= l; xz /= l
        val yx = zy * xz - zz * xy; val yy = zz * xx - zx * xz; val yz = zx * xy - zy * xx
        m[0] = xx; m[1] = yx; m[2] = zx; m[3] = 0f
        m[4] = xy; m[5] = yy; m[6] = zy; m[7] = 0f
        m[8] = xz; m[9] = yz; m[10] = zz; m[11] = 0f
        m[12] = -(xx * eye.x + xy * eye.y + xz * eye.z)
        m[13] = -(yx * eye.x + yy * eye.y + yz * eye.z)
        m[14] = -(zx * eye.x + zy * eye.y + zz * eye.z)
        m[15] = 1f
        return this
    }

    fun perspective(fovyRad: Float, aspect: Float, near: Float, far: Float): Mat4 {
        java.util.Arrays.fill(m, 0f)
        val f = 1f / tan(fovyRad / 2f)
        val nf = 1f / (near - far)
        m[0] = f / aspect; m[5] = f; m[10] = (far + near) * nf; m[11] = -1f; m[14] = 2f * far * near * nf
        return this
    }

    fun ortho(l: Float, r: Float, b: Float, t: Float, n: Float, f: Float): Mat4 {
        java.util.Arrays.fill(m, 0f)
        m[0] = 2f / (r - l); m[5] = 2f / (t - b); m[10] = -2f / (f - n)
        m[12] = -(r + l) / (r - l); m[13] = -(t + b) / (t - b); m[14] = -(f + n) / (f - n); m[15] = 1f
        return this
    }

    fun rotationY(a: Float): Mat4 {
        identity(); val c = cos(a); val s = sin(a)
        m[0] = c; m[2] = -s; m[8] = s; m[10] = c
        return this
    }
}

class Quat(var x: Float = 0f, var y: Float = 0f, var z: Float = 0f, var w: Float = 1f) {
    fun set(x: Float, y: Float, z: Float, w: Float): Quat { this.x = x; this.y = y; this.z = z; this.w = w; return this }

    fun setFromEulerYXZ(pitch: Float, yaw: Float, roll: Float): Quat {
        val c1 = cos(pitch / 2f); val c2 = cos(yaw / 2f); val c3 = cos(roll / 2f)
        val s1 = sin(pitch / 2f); val s2 = sin(yaw / 2f); val s3 = sin(roll / 2f)
        x = s1 * c2 * c3 + c1 * s2 * s3
        y = c1 * s2 * c3 - s1 * c2 * s3
        z = c1 * c2 * s3 - s1 * s2 * c3
        w = c1 * c2 * c3 + s1 * s2 * s3
        return this
    }

    fun setQ(o: Quat): Quat { x = o.x; y = o.y; z = o.z; w = o.w; return this }

    fun conjugate(): Quat { x = -x; y = -y; z = -z; return this }

    fun setFromAxisAngle(ax: Float, ay: Float, az: Float, ang: Float): Quat {
        val h = ang / 2f; val s = sin(h)
        return set(ax * s, ay * s, az * s, cos(h))
    }

    fun copy() = Quat(x, y, z, w)

    fun multiply(b: Quat): Quat {
        val qx = x; val qy = y; val qz = z; val qw = w
        x = qw * b.x + qx * b.w + qy * b.z - qz * b.y
        y = qw * b.y - qx * b.z + qy * b.w + qz * b.x
        z = qw * b.z + qx * b.y - qy * b.x + qz * b.w
        w = qw * b.w - qx * b.x - qy * b.y - qz * b.z
        return this
    }

    fun setFromUnitVectors(from: Vec3, to: Vec3): Quat {
        val r = from.dot(to) + 1f
        if (r < 1e-6f) {
            if (abs(from.x) > abs(from.z)) set(0f, from.z, -from.y, 0f) else set(from.y, -from.x, 0f, 0f)
        } else {
            set(from.y * to.z - from.z * to.y, from.z * to.x - from.x * to.z, from.x * to.y - from.y * to.x, r)
        }
        return normalize()
    }

    fun normalize(): Quat {
        val l = sqrt(x * x + y * y + z * z + w * w)
        if (l > 1e-9f) { x /= l; y /= l; z /= l; w /= l }
        return this
    }

    companion object {
        fun slerp(a: Quat, b: Quat, t: Float, out: Quat): Quat {
            var bx = b.x; var by = b.y; var bz = b.z; var bw = b.w
            var cos = a.x * bx + a.y * by + a.z * bz + a.w * bw
            if (cos < 0f) { bx = -bx; by = -by; bz = -bz; bw = -bw; cos = -cos }
            val k0: Float; val k1: Float
            if (cos > 0.9995f) { k0 = 1 - t; k1 = t }
            else {
                val th = acos(cos); val sh = sin(th)
                k0 = sin((1 - t) * th) / sh; k1 = sin(t * th) / sh
            }
            return out.set(a.x * k0 + bx * k1, a.y * k0 + by * k1, a.z * k0 + bz * k1, a.w * k0 + bw * k1)
        }
    }
}

fun wrapPi(a: Float): Float {
    var r = a
    val pi = PI.toFloat()
    while (r > pi) r -= (2 * PI).toFloat()
    while (r < -pi) r += (2 * PI).toFloat()
    return r
}

fun clamp(v: Float, a: Float, b: Float) = if (v < a) a else if (v > b) b else v
fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t

// 确定性随机（mulberry32）
class Rng(seedIn: Int) {
    private var a = seedIn
    fun next(): Float {
        a += 0x6D2B79F5.toInt()
        var t = a xor (a ushr 15)
        t *= (1 or a)
        t = (t + ((t xor (t ushr 7)) * (61 or t))) xor t
        val r = (t xor (t ushr 14)).toLong() and 0xFFFFFFFFL
        return (r / 4294967296.0).toFloat()
    }
    fun range(a: Float, b: Float) = a + next() * (b - a)
}
