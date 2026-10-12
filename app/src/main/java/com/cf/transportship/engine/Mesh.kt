// 网格：静态 VAO 构建（可变顶点布局）与动态 VBO
package com.cf.transportship.engine

import android.opengl.GLES30.*

/** 顶点布局描述 */
class Layout(val attribs: Array<Triple<Int, Int, Int>> /* loc, size, offset */, val stride: Int)

object Layouts {
    // pos3 normal3 uv2 uv2(ao) = 40 字节
    val MAP = Layout(arrayOf(Triple(0, 3, 0), Triple(1, 3, 12), Triple(2, 2, 24), Triple(3, 2, 32)), 40)
    // pos3 normal3 uv2 color3 skin1 = 48 字节
    val SKIN = Layout(arrayOf(Triple(0, 3, 0), Triple(1, 3, 12), Triple(2, 2, 24), Triple(3, 3, 32), Triple(4, 1, 44)), 48)
    // pos3 normal3（深度通道）
    val POS = Layout(arrayOf(Triple(0, 3, 0)), 12)
    // pos3 normal3 uv2（深度通道 + alphaTest，共 32 字节）
    val POS_UV = Layout(arrayOf(Triple(0, 3, 0), Triple(2, 2, 24)), 32)
    // pos3 uv2（无光照板/贴花）
    val UNLIT = Layout(arrayOf(Triple(0, 3, 0), Triple(1, 2, 12)), 20)
}

class StaticMesh(vertices: FloatArray, indices: IntArray, val layout: Layout) {
    private val vao = IntArray(1)
    private val vbo = IntArray(1)
    private val ibo = IntArray(1)
    val indexCount = indices.size

    init {
        glGenVertexArrays(1, vao, 0)
        glGenBuffers(1, vbo, 0)
        glGenBuffers(1, ibo, 0)
        glBindVertexArray(vao[0])
        glBindBuffer(GL_ARRAY_BUFFER, vbo[0])
        glBufferData(GL_ARRAY_BUFFER, vertices.size * 4, GLU.floatBuffer(vertices), GL_STATIC_DRAW)
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ibo[0])
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices.size * 4, GLU.intBuffer(indices), GL_STATIC_DRAW)
        for ((loc, size, off) in layout.attribs) {
            glEnableVertexAttribArray(loc)
            glVertexAttribPointer(loc, size, GL_FLOAT, false, layout.stride, off)
        }
        glBindVertexArray(0)
    }

    fun draw() {
        glBindVertexArray(vao[0])
        glDrawElements(GL_TRIANGLES, indexCount, GL_UNSIGNED_INT, 0)
        glBindVertexArray(0)
    }

    fun dispose() {
        glDeleteVertexArrays(1, vao, 0)
        glDeleteBuffers(1, vbo, 0)
        glDeleteBuffers(1, ibo, 0)
    }
}

/** 动态网格：CPU 端填充后每帧上传（贴花/曳光/火焰板等） */
class DynamicMesh(val layout: Layout, maxVerts: Int) {
    private val vao = IntArray(1)
    private val vbo = IntArray(1)
    private val data = FloatArray(maxVerts * layout.stride / 4)
    var vertCount = 0
        private set
    private var indices: IntArray = IntArray(0)
    private var ibo = IntArray(1)

    init {
        glGenVertexArrays(1, vao, 0)
        glGenBuffers(1, vbo, 0)
        glGenBuffers(1, ibo, 0)
        val maxQuads = maxVerts / 4
        indices = IntArray(maxQuads * 6)
        for (q in 0 until maxQuads) {
            val b = q * 4
            indices[q * 6 + 0] = b; indices[q * 6 + 1] = b + 1; indices[q * 6 + 2] = b + 2
            indices[q * 6 + 3] = b; indices[q * 6 + 4] = b + 2; indices[q * 6 + 5] = b + 3
        }
        glBindVertexArray(vao[0])
        glBindBuffer(GL_ARRAY_BUFFER, vbo[0])
        glBufferData(GL_ARRAY_BUFFER, data.size * 4, null, GL_DYNAMIC_DRAW)
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ibo[0])
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices.size * 4, GLU.intBuffer(indices), GL_STATIC_DRAW)
        for ((loc, size, off) in layout.attribs) {
            glEnableVertexAttribArray(loc)
            glVertexAttribPointer(loc, size, GL_FLOAT, false, layout.stride, off)
        }
        glBindVertexArray(0)
    }

    fun reset() { vertCount = 0 }

    /** 写入一个四边形（4 顶点，按布局交错） */
    fun quad(
        ax: Float, ay: Float, az: Float, au: Float, av: Float,
        bx: Float, by: Float, bz: Float, bu: Float, bv: Float,
        cx: Float, cy: Float, cz: Float, cu: Float, cv: Float,
        dx: Float, dy: Float, dz: Float, du: Float, dv: Float,
    ) {
        if (vertCount + 4 > data.size / (layout.stride / 4)) return
        val n = layout.stride / 4
        var o = vertCount * n
        data[o] = ax; data[o + 1] = ay; data[o + 2] = az; data[o + 3] = au; data[o + 4] = av; o += n
        data[o] = bx; data[o + 1] = by; data[o + 2] = bz; data[o + 3] = bu; data[o + 4] = bv; o += n
        data[o] = cx; data[o + 1] = cy; data[o + 2] = cz; data[o + 3] = cu; data[o + 4] = cv; o += n
        data[o] = dx; data[o + 1] = dy; data[o + 2] = dz; data[o + 3] = du; data[o + 4] = dv
        vertCount += 4
    }

    private var uploadBuf: java.nio.FloatBuffer? = null

    fun uploadAndDraw() {
        if (vertCount == 0) return
        val count = vertCount * layout.stride / 4
        var fb = uploadBuf
        if (fb == null) {
            fb = java.nio.ByteBuffer.allocateDirect(data.size * 4).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
            uploadBuf = fb
        }
        fb.clear()
        fb.put(data, 0, count)
        fb.position(0)
        glBindVertexArray(vao[0])
        glBindBuffer(GL_ARRAY_BUFFER, vbo[0])
        glBufferSubData(GL_ARRAY_BUFFER, 0, count * 4, fb)
        glDrawElements(GL_TRIANGLES, vertCount / 4 * 6, GL_UNSIGNED_INT, 0)
        glBindVertexArray(0)
    }

    fun dispose() {
        glDeleteVertexArrays(1, vao, 0)
        glDeleteBuffers(1, vbo, 0)
        glDeleteBuffers(1, ibo, 0)
    }
}
