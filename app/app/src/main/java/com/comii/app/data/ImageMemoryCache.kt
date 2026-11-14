package com.comii.app.data

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache

/**
 * 阅读器自己的内存图片缓存：退出 ReaderScreen 后仍然保留，
 * 再次打开相邻图片时直接复用，不再重新下载/解码。
 */
object ImageMemoryCache {
    private val maxKb = (Runtime.getRuntime().maxMemory() / 1024 / 6).toInt().coerceAtLeast(16 * 1024)
    private val cache = object : LruCache<String, Bitmap>(maxKb) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    fun get(key: String?): Bitmap? {
        if (key.isNullOrBlank()) return null
        return runCatching { cache.get(key) }.getOrNull()
    }

    fun put(key: String?, bitmap: Bitmap?) {
        if (key.isNullOrBlank() || bitmap == null || bitmap.isRecycled) return
        runCatching { cache.put(key, bitmap) }
    }

    fun putDrawable(key: String?, drawable: Drawable?) {
        if (drawable is BitmapDrawable) put(key, drawable.bitmap)
    }
}
