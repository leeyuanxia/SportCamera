package cn.leeyuanxia.sportcamera.domain

import android.util.Log
import cn.leeyuanxia.sportcamera.domain.model.PreRecordDuration
import cn.leeyuanxia.sportcamera.domain.model.ResolutionProfile
import cn.leeyuanxia.sportcamera.hardware.camera.FrameConsumer
import cn.leeyuanxia.sportcamera.hardware.camera.RingBufferRecorder

class PreRecordManager : FrameConsumer {

    companion object {
        private const val TAG = "PreRecordManager"
    }

    @Volatile var currentDuration: PreRecordDuration = PreRecordDuration.DEFAULT; private set
    @Volatile var currentProfile: ResolutionProfile = ResolutionProfile.DEFAULT; private set
    @Volatile var cameraWidth: Int = 0; private set
    @Volatile var cameraHeight: Int = 0; private set

    private var ringBuffer: RingBufferRecorder? = null
    private var readyToCreate: Boolean = false
    private var drainStarted: Boolean = false

    fun setDuration(duration: PreRecordDuration) { currentDuration = duration }
    fun setProfile(profile: ResolutionProfile) { currentProfile = profile }
    fun setOrientation(o: cn.leeyuanxia.sportcamera.domain.model.RecordOrientation) {}

    /**
     * 标记可以创建编码器。首帧到达 + 此标记都满足时，自动创建。
     */
    fun markReadyToCreate() {
        readyToCreate = true
    }

    /**
     * 用相机实际分辨率创建编码器
     */
    private fun createBuffer(w: Int, h: Int) {
        ringBuffer?.release()
        ringBuffer = RingBufferRecorder(
            maxDurationSec = currentDuration.seconds,
            width = w, height = h,
            fps = RingBufferRecorder.STANDBY_FPS,
            bitrateBps = RingBufferRecorder.STANDBY_BITRATE,
        )
        ringBuffer?.prepare()
        ringBuffer?.start()
        drainStarted = true
        Log.d(TAG, "环形缓冲已创建: ${w}x${h}, ${currentDuration.seconds}s")
    }

    suspend fun drainLoop() {
        // 等待 ringBuffer 创建（由 feedFrame 中首帧触发）
        var waits = 0
        while (ringBuffer == null && waits < 300) { // 最多等 3 秒
            kotlinx.coroutines.delay(10)
            waits++
        }
        ringBuffer?.drainEncoder()
    }

    private var firstFrame = true

    override fun feedFrame(yuvData: ByteArray, timestampUs: Long, width: Int, height: Int) {
        // 首帧：记录相机分辨率
        if (cameraWidth == 0 && width > 0 && height > 0) {
            cameraWidth = width
            cameraHeight = height
            Log.d(TAG, "首帧: ${width}x${height}, readyToCreate=$readyToCreate")
        }

        // 满足条件时自动创建编码器
        if (readyToCreate && !drainStarted && cameraWidth > 0) {
            createBuffer(cameraWidth, cameraHeight)
        }

        ringBuffer?.feedFrame(yuvData, timestampUs, width, height)
    }

    fun dumpPreFrames(): List<RingBufferRecorder.EncodedFrame> {
        val rb = ringBuffer ?: return emptyList()
        val recent = rb.dumpRecentFrames(currentDuration.preHalfMs)
        return if (recent.isEmpty()) rb.dumpAllFrames() else recent
    }

    fun actualPreDurationMs(frames: List<RingBufferRecorder.EncodedFrame>): Long {
        if (frames.size < 2) return 0L
        return (frames.last().presentationTimeUs - frames.first().presentationTimeUs) / 1000
    }

    fun stop() { ringBuffer?.stop() }
    fun clearBuffer() { ringBuffer?.clear() }
    fun release() { ringBuffer?.release(); ringBuffer = null }
}
