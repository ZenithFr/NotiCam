package com.zenith.auxcam

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.Surface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** IO-thread confined recorder. A failed/too-short clip is never published. */
class VideoCapture(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var descriptor: ParcelFileDescriptor? = null
    private var uri: Uri? = null
    private var started = false
    val surface: Surface get() = checkNotNull(recorder).surface

    @Suppress("DEPRECATION")
    fun prepare(width: Int, height: Int, audio: Boolean, orientation: Int, onFailure: () -> Unit, onLimit: () -> Unit) {
        check(recorder == null)
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "NOTICAM_$stamp.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/NotiCam")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        try {
            uri = checkNotNull(context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values))
            descriptor = checkNotNull(context.contentResolver.openFileDescriptor(checkNotNull(uri), "rw"))
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
            recorder = r
            if (audio) r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setOutputFile(checkNotNull(descriptor).fileDescriptor)
            r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            r.setVideoSize(width, height)
            r.setVideoFrameRate(VideoPolicy.FPS)
            val codec = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
                info.isEncoder && info.isHardwareAccelerated && try {
                    val caps = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                    caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) &&
                        caps.videoCapabilities?.areSizeAndRateSupported(width, height, VideoPolicy.FPS.toDouble()) == true
                } catch (_: IllegalArgumentException) { false }
            } ?: error("No hardware AVC encoder for this native-aspect stream")
            val bitrateRange = codec.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities.bitrateRange
            r.setVideoEncodingBitRate(bitrateRange.clamp(VideoPolicy.bitrate(width, height)))
            if (audio) {
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioSamplingRate(48000)
                r.setAudioChannels(1)
                r.setAudioEncodingBitRate(192_000)
            }
            r.setOrientationHint(orientation)
            r.setMaxDuration(15 * 60 * 1000)
            r.setOnErrorListener { _, _, _ -> onFailure() }
            r.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED ||
                    what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) onLimit()
            }
            r.prepare()
        } catch (e: Exception) {
            finish(false)
            throw e
        }
    }

    fun start() { checkNotNull(recorder).start(); started = true }

    fun finish(save: Boolean): Uri? {
        val destination = uri
        uri = null
        var valid = false
        try {
            if (started) {
                checkNotNull(recorder).stop() // Throws on empty / too-short captures.
                valid = save
            }
        } catch (_: Exception) { valid = false }
        finally {
            started = false
            try { recorder?.reset() } catch (_: Exception) { }
            try { recorder?.release() } catch (_: Exception) { }
            recorder = null
            try { descriptor?.close() } catch (_: Exception) { }
            descriptor = null
        }
        if (destination == null) return null
        try {
            if (valid) {
                val metadata = MediaMetadataRetriever()
                try {
                    metadata.setDataSource(context, destination)
                    val duration = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                    check(duration > 0) { "Recorder returned an empty clip" }
                    val complete = ContentValues().apply {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                        put(MediaStore.Video.Media.DURATION, duration)
                        put(MediaStore.Video.Media.WIDTH, metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull())
                        put(MediaStore.Video.Media.HEIGHT, metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull())
                    }
                    check(context.contentResolver.update(destination, complete, null, null) > 0)
                    return destination
                } finally { metadata.release() }
            }
        } catch (_: Exception) { /* Roll back unpublished or malformed output below. */ }
        try { context.contentResolver.delete(destination, null, null) } catch (_: Exception) { }
        return null
    }
}
