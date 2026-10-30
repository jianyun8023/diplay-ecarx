package com.shilapi.xcertplay

import android.content.Context
import android.graphics.SurfaceTexture
import android.graphics.Matrix
import android.view.Surface
import android.view.TextureView

/** Owns only the Surface wrapper; returning true lets TextureView release its texture. */
internal class ClusterVideoTexture(context: Context, private val onPresented: () -> Unit = {},
    private val onSurface: (Surface?) -> Unit) :
    TextureView(context), java.io.Closeable {
    private var output: Surface? = null
    private var videoWidth = 1920
    private var videoHeight = 720
    private val pictureBinding = CarPlayPicture.Binding(this)
    init {
        isOpaque = false
        surfaceTextureListener = object : SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                releaseOutput()
                setVideoSize(videoWidth, videoHeight)
                output = Surface(texture).also(onSurface)
            }
            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                setVideoSize(videoWidth, videoHeight)
            }
            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) { onPresented() }
            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                releaseOutput()
                return true
            }
        }
    }
    fun setVideoSize(width: Int, height: Int) {
        require(width > 0 && height > 0)
        videoWidth = width
        videoHeight = height
        surfaceTexture?.setDefaultBufferSize(width, height)
        if (this.width == 0 || this.height == 0) return
        val scale = minOf(this.width.toFloat() / width, this.height.toFloat() / height)
        setTransform(Matrix().apply {
            setScale(width * scale / this@ClusterVideoTexture.width,
                height * scale / this@ClusterVideoTexture.height,
                this@ClusterVideoTexture.width / 2f, this@ClusterVideoTexture.height / 2f)
        })
    }
    private fun releaseOutput() {
        if (output == null) return
        onSurface(null)
        output?.release()
        output = null
    }
    override fun close() {
        pictureBinding.close()
        surfaceTextureListener = null
        releaseOutput()
    }
}
