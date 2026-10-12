// GL 工具：着色器编译、纹理上传、FBO
package com.cf.transportship.engine

import android.graphics.Bitmap
import android.opengl.GLES30.*
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer

object GLU {
    fun compileShader(type: Int, src: String): Int {
        val s = glCreateShader(type)
        glShaderSource(s, src)
        glCompileShader(s)
        val st = IntArray(1)
        glGetShaderiv(s, GL_COMPILE_STATUS, st, 0)
        if (st[0] == 0) {
            val log = glGetShaderInfoLog(s)
            throw RuntimeException("Shader compile failed: $log\n---\n$src")
        }
        return s
    }

    fun link(vs: Int, fs: Int): Int {
        val p = glCreateProgram()
        glAttachShader(p, vs)
        glAttachShader(p, fs)
        glLinkProgram(p)
        val st = IntArray(1)
        glGetProgramiv(p, GL_LINK_STATUS, st, 0)
        if (st[0] == 0) {
            val log = glGetProgramInfoLog(p)
            throw RuntimeException("Program link failed: $log")
        }
        glDeleteShader(vs); glDeleteShader(fs)
        return p
    }

    fun buildProgram(vsrc: String, fsrc: String): Int = link(compileShader(GL_VERTEX_SHADER, vsrc), compileShader(GL_FRAGMENT_SHADER, fsrc))

    fun floatBuffer(arr: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(arr.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(arr); position(0) }

    fun intBuffer(arr: IntArray): IntBuffer =
        ByteBuffer.allocateDirect(arr.size * 4).order(ByteOrder.nativeOrder()).asIntBuffer().apply { put(arr); position(0) }

    /** 上传 Bitmap 为纹理。srgb=true 时使用 SRGB8_ALPHA8；mipmap/aniso 控制 */
    fun uploadBitmap(bmp: Bitmap, srgb: Boolean, repeat: Boolean, mipmap: Boolean = true, aniso: Int = 4): Int {
        val tex = IntArray(1)
        glGenTextures(1, tex, 0)
        val t = tex[0]
        glBindTexture(GL_TEXTURE_2D, t)
        val internal = GL_RGBA
        GLUtils.texImage2D(GL_TEXTURE_2D, 0, internal, bmp, GL_UNSIGNED_BYTE, 0)
        if (mipmap) {
            glGenerateMipmap(GL_TEXTURE_2D)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR)
        } else {
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        }
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        val wrap = if (repeat) GL_REPEAT else GL_CLAMP_TO_EDGE
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, wrap)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, wrap)
        if (aniso > 0) {
            val max = FloatArray(1)
            glGetFloatv(0x84FF /*GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT*/, max, 0)
            glTexParameteri(GL_TEXTURE_2D, 0x84FE /*GL_TEXTURE_MAX_ANISOTROPY_EXT*/, minOf(aniso, max[0].toInt()))
        }
        return t
    }

    fun deleteTex(t: Int) {
        if (t != 0) glDeleteTextures(1, intArrayOf(t), 0)
    }

    fun createDepthTex(w: Int, h: Int): Int {
        val tex = IntArray(1)
        glGenTextures(1, tex, 0)
        glBindTexture(GL_TEXTURE_2D, tex[0])
        glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT24, w, h, 0, GL_DEPTH_COMPONENT, GL_UNSIGNED_INT, null)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_MODE, GL_COMPARE_REF_TO_TEXTURE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_FUNC, GL_LEQUAL)
        return tex[0]
    }
}

/** 离屏渲染目标：颜色纹理 + 深度 RBO */
class Fbo(val w: Int, val h: Int, val colorTex: Int, val fbo: Int, private val depthRbo: Int = 0) {
    fun bind() {
        glBindFramebuffer(GL_FRAMEBUFFER, fbo)
        glViewport(0, 0, w, h)
    }
    fun dispose() {
        glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        GLU.deleteTex(colorTex)
        if (depthRbo != 0) glDeleteRenderbuffers(1, intArrayOf(depthRbo), 0)
    }

    companion object {
        fun create(w: Int, h: Int, halfFloat: Boolean, withDepth: Boolean): Fbo {
            val tex = IntArray(1)
            glGenTextures(1, tex, 0)
            glBindTexture(GL_TEXTURE_2D, tex[0])
            if (halfFloat) {
                // 尝试 RGBA16F；ES3.0 核心支持
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, w, h, 0, GL_RGBA, GL_HALF_FLOAT, null)
            } else {
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, null)
            }
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
            val fboArr = IntArray(1)
            glGenFramebuffers(1, fboArr, 0)
            glBindFramebuffer(GL_FRAMEBUFFER, fboArr[0])
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex[0], 0)
            var rbo = 0
            if (withDepth) {
                val rboArr = IntArray(1)
                glGenRenderbuffers(1, rboArr, 0)
                rbo = rboArr[0]
                glBindRenderbuffer(GL_RENDERBUFFER, rbo)
                glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, w, h)
                glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, rbo)
                // 注意：ES 规范下删除“挂在当前绑定 FBO 上”的 RBO 会先被分离，
                // 必须保留 id由 dispose() 统一释放
            }
            val st = glCheckFramebufferStatus(GL_FRAMEBUFFER)
            glBindFramebuffer(GL_FRAMEBUFFER, 0)
            if (st != GL_FRAMEBUFFER_COMPLETE) {
                glDeleteFramebuffers(1, fboArr, 0)
                GLU.deleteTex(tex[0])
                if (rbo != 0) glDeleteRenderbuffers(1, intArrayOf(rbo), 0)
                throw RuntimeException("FBO incomplete: 0x${Integer.toHexString(st)} (${w}x$h halfFloat=$halfFloat)")
            }
            return Fbo(w, h, tex[0], fboArr[0], rbo)
        }
    }
}

/** 简单全屏三角形网格 */
class FullscreenQuad {
    private val vao = IntArray(1)
    private val vbo = IntArray(1)
    // 三个顶点覆盖全屏：pos(2) + uv(2)
    private val verts = floatArrayOf(-1f, -1f, 0f, 0f, 3f, -1f, 2f, 0f, -1f, 3f, 0f, 2f)

    init {
        glGenVertexArrays(1, vao, 0)
        glGenBuffers(1, vbo, 0)
        glBindVertexArray(vao[0])
        glBindBuffer(GL_ARRAY_BUFFER, vbo[0])
        val fb = GLU.floatBuffer(verts)
        glBufferData(GL_ARRAY_BUFFER, verts.size * 4, fb, GL_STATIC_DRAW)
        glEnableVertexAttribArray(0)
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 16, 0)
        glEnableVertexAttribArray(1)
        glVertexAttribPointer(1, 2, GL_FLOAT, false, 16, 8)
        glBindVertexArray(0)
    }

    fun draw() {
        glBindVertexArray(vao[0])
        glDrawArrays(GL_TRIANGLES, 0, 3)
        glBindVertexArray(0)
    }

    fun vaoId() = vao[0]
}
