/* SPDX-License-Identifier: Apache-2.0 */
package dev.cekxri.haaru

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Closed angular UV lattices; each object's toroidal or pebble topology stays fixed while morphing. */
internal class SculptureMesh(private val divisionsU: Int, private val divisionsV: Int) : AutoCloseable {
    private val vertexArray = IntArray(1)
    private val buffers = IntArray(2)
    val triangles: Int = divisionsU * divisionsV * 2
    private val indexCount = triangles * 3

    init {
        require((divisionsU + 1) * (divisionsV + 1) < 65535)
        val vertices = ByteBuffer.allocateDirect((divisionsU + 1) * (divisionsV + 1) * 8)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (u in 0..divisionsU) for (v in 0..divisionsV) {
            vertices.put(u.toFloat() / divisionsU).put(v.toFloat() / divisionsV)
        }
        vertices.position(0)
        val indices = ByteBuffer.allocateDirect(indexCount * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
        for (u in 0 until divisionsU) for (v in 0 until divisionsV) {
            val a = u * (divisionsV + 1) + v
            val b = a + divisionsV + 1
            // cross(dv, du) is the outward normal of our moving toroidal frame.
            indices.put(a.toShort()).put((a + 1).toShort()).put(b.toShort())
            indices.put((a + 1).toShort()).put((b + 1).toShort()).put(b.toShort())
        }
        indices.position(0)
        GLES30.glGenVertexArrays(1, vertexArray, 0)
        GLES30.glGenBuffers(2, buffers, 0)
        GLES30.glBindVertexArray(vertexArray[0])
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffers[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vertices.capacity() * 4, vertices, GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, buffers[1])
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, indices.capacity() * 2, indices, GLES30.GL_STATIC_DRAW)
        GLES30.glBindVertexArray(0)
    }

    fun draw() {
        GLES30.glBindVertexArray(vertexArray[0])
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_SHORT, 0)
        GLES30.glBindVertexArray(0)
    }

    override fun close() {
        GLES30.glDeleteBuffers(2, buffers, 0)
        GLES30.glDeleteVertexArrays(1, vertexArray, 0)
    }
}

internal class SculptureQuad : AutoCloseable {
    private val vertexArray = IntArray(1)
    private val buffer = IntArray(1)
    init {
        val vertices = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        vertices.put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)).position(0)
        GLES30.glGenVertexArrays(1, vertexArray, 0)
        GLES30.glGenBuffers(1, buffer, 0)
        GLES30.glBindVertexArray(vertexArray[0])
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffer[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, 32, vertices, GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
        GLES30.glBindVertexArray(0)
    }
    fun draw() {
        GLES30.glBindVertexArray(vertexArray[0])
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindVertexArray(0)
    }
    override fun close() {
        GLES30.glDeleteBuffers(1, buffer, 0)
        GLES30.glDeleteVertexArrays(1, vertexArray, 0)
    }
}
