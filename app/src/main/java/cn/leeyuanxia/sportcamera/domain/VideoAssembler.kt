package cn.leeyuanxia.sportcamera.domain

import cn.leeyuanxia.sportcamera.hardware.audio.AudioRecorder
import cn.leeyuanxia.sportcamera.hardware.camera.RingBufferRecorder
import cn.leeyuanxia.sportcamera.hardware.storage.VideoStorageManager
import cn.leeyuanxia.sportcamera.domain.model.PreRecordDuration
import cn.leeyuanxia.sportcamera.domain.model.RecordOrientation
import cn.leeyuanxia.sportcamera.domain.model.ResolutionProfile

/**
 * 视频合成器 — 将 pre 段和 post 段合成完整 MP4（含音频轨）
 *
 * 处理逻辑：
 * 1. pre 段 = 环形缓冲 dump（前半时长）
 * 2. post 段 = 唤醒后继续录制的帧（后半时长）
 * 3. audio 段 = 后录阶段同步录制的 AAC 音频帧
 * 4. 总时长 = preRecordDuration
 * 5. 如果 pre 段不足，通过延长 post 段补齐
 * 6. 处理时间戳连续性和 CSD 配置帧
 */
class VideoAssembler(
    private val storageManager: VideoStorageManager,
) {

    /**
     * 合成完整视频（含音频）
     *
     * @param preFrames  前半段帧（环形缓冲）
     * @param postFrames 后半段帧（唤醒后录制）
     * @param preAudioFrames 前半段音频帧（音频环形缓冲）
     * @param preAudioStartUs 预录音频的 startTimeUs 基准
     * @param audioFrames 后录阶段的 AAC 音频帧
     * @param audioStartTimeUs 后录音频的 startTimeUs 基准
     * @param duration   预录时长设定
     * @param profile    用户选择的分辨率档位
     * @param orientation 录制方向（横屏/竖屏）
     * @param onProgress 进度回调
     * @return 合成后的文件 Uri 字符串
     */
    fun assemble(
        preFrames: List<RingBufferRecorder.EncodedFrame>,
        postFrames: List<RingBufferRecorder.EncodedFrame>,
        preAudioFrames: List<AudioRecorder.EncodedAudioFrame> = emptyList(),
        preAudioStartUs: Long = 0L,
        audioFrames: List<AudioRecorder.EncodedAudioFrame>,
        audioStartTimeUs: Long,
        audioCsdData: ByteArray? = null,
        duration: PreRecordDuration,
        profile: ResolutionProfile,
        orientation: RecordOrientation = RecordOrientation.LANDSCAPE,
        cameraWidth: Int = profile.width,
        cameraHeight: Int = profile.height,
        csd0Data: ByteArray? = null,
        csd1Data: ByteArray? = null,
        onProgress: (Float) -> Unit = {},
    ): String {
        val output = storageManager.createOutputFile(
            resolutionLabel = "${profile.height}p",
            fps = profile.fps,
        )

        val rotation = when (orientation) {
            RecordOrientation.PORTRAIT -> 90
            RecordOrientation.LANDSCAPE -> 0
        }
        storageManager.assembleToMp4(
            preFrames = preFrames,
            postFrames = postFrames,
            preAudioFrames = preAudioFrames,
            preAudioStartUs = preAudioStartUs,
            audioFrames = audioFrames,
            audioStartTimeUs = audioStartTimeUs,
            audioCsdData = audioCsdData,
            output = output,
            width = cameraWidth,
            height = cameraHeight,
            csd0Data = csd0Data,
            csd1Data = csd1Data,
            rotation = rotation,
            onProgress = onProgress,
        )

        return output.uri.toString()
    }

    /**
     * 计算需要的 post 段时长（考虑 pre 段不足的情况）
     */
    fun calculatePostDurationMs(
        actualPreDurationMs: Long,
        targetDuration: PreRecordDuration,
    ): Long {
        val shortfall = targetDuration.preHalfMs - actualPreDurationMs
        return targetDuration.postHalfMs + shortfall.coerceAtLeast(0)
    }
}
