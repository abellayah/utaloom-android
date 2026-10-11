package com.metrolist.music.playback

import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.ContentMetadata

internal fun offlinePlaybackDataSpec(cache: Cache, dataSpec: DataSpec, storedLength: Long?): DataSpec? {
    val key = dataSpec.key ?: return null
    val length = ContentMetadata.getContentLength(cache.getContentMetadata(key)).takeIf { it > 0 }
        ?: storedLength?.takeIf { it > 0 }
        ?: return null
    if (!cache.isCached(key, 0, length) || dataSpec.position >= length) return null
    val remaining = length - dataSpec.position
    return dataSpec.buildUpon().setLength(if (dataSpec.length < 0) remaining else minOf(dataSpec.length, remaining)).build()
}
