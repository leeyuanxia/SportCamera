package cn.leeyuanxia.sportcamera.hardware.audio

import android.content.res.AssetManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.getFeatureConfig
import cn.leeyuanxia.sportcamera.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext

/**
 * KWS（Keyword Spotting）语音唤醒管理器
 *
 * 基于 sherpa-onnx 的 Zipformer2 Transducer 模型，离线唤醒词检测。
 * 用 SharedFlow 替代 listener 回调，更 Kotlin-idiomatic。
 *
 * 省电设计：
 * - numThreads=1（单线程推理）
 * - 100ms 读取间隔（降低 CPU 占用）
 * - 唤醒词检测到后自动停止监听，避免持续占用麦克风
 */
class KwsManager(private val assetManager: AssetManager) {

    companion object {
        private const val TAG = "KwsManager"
        const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val DEFAULT_READ_INTERVAL_MS = 100L // 默认 100ms 读取间隔
        private const val MODEL_DIR = "onnx-kws"
    }

    /**
     * 动态读取间隔（由 ThermalThrottler 控制）
     * Normal=100ms, Severe=300ms, Emergency=800ms
     */
    @Volatile
    var readIntervalMs: Long = DEFAULT_READ_INTERVAL_MS
        private set

    fun updateReadInterval(ms: Long) {
        readIntervalMs = ms
    }

    private lateinit var spotter: KeywordSpotter
    private var stream: OnlineStream? = null
    private var audioRecord: AudioRecord? = null

    @Volatile
    private var isListening = false

    /**
     * 外部 PCM 模式 — 由外部组件（如 RingBufferAudioRecorder）提供 PCM 数据，
     * KwsManager 不再自行创建 AudioRecord。
     *
     * 用于解决 Android 只允许一个 AudioRecord 同时访问 MIC 的限制：
     * 待机时 pre-audio 录制器和 KWS 需要同时使用麦克风，
     * 通过共享一个 AudioRecord 的 PCM 数据来避免冲突。
     */
    @Volatile
    private var externalPcmMode = false

    fun setExternalPcmMode(enabled: Boolean) {
        externalPcmMode = enabled
        DebugLog.d(TAG, "外部 PCM 模式: $enabled")
    }

    // 外部 PCM 模式的缓冲 — 积累数据到 100ms 后再送入 KWS
    private val pcmBuffer = mutableListOf<Float>()
    private val MIN_KWS_SAMPLES = (SAMPLE_RATE * DEFAULT_READ_INTERVAL_MS / 1000).toInt() // 1600 samples = 100ms

    /** 唤醒词事件流 — UI 和编排器通过此 Flow 接收唤醒词 */
    private val _keywordFlow = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val keywordFlow: SharedFlow<String> = _keywordFlow.asSharedFlow()

    /**
     * 初始化 KWS 模型（首次调用或 release 后需要重新初始化）
     */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        if (::spotter.isInitialized) return@withContext

        val config = KeywordSpotterConfig(
            featConfig = getFeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "$MODEL_DIR/encoder-epoch-12-avg-2-chunk-16-left-64.onnx",
                    decoder = "$MODEL_DIR/decoder-epoch-12-avg-2-chunk-16-left-64.onnx",
                    joiner = "$MODEL_DIR/joiner-epoch-12-avg-2-chunk-16-left-64.onnx",
                ),
                tokens = "$MODEL_DIR/tokens.txt",
                numThreads = 1,        // 省电: 单线程推理
                provider = "cpu",
                modelType = "zipformer2",
            ),
            keywordsFile = "$MODEL_DIR/keywords.txt",
            keywordsScore = 1.5f,
            keywordsThreshold = 0.25f,
            numTrailingBlanks = 2,
        )
        spotter = KeywordSpotter(assetManager = assetManager, config = config)
    }

    /**
     * 开始监听麦克风并检测唤醒词
     *
     * 在 IO 线程上持续运行 AudioRecord 读取循环，
     * 每次检测到唤醒词都会通过 [keywordFlow] 发射事件。
     *
     * 外部 PCM 模式下不创建 AudioRecord，由外部组件通过 [feedPcm] 提供数据。
     */
    suspend fun startListening() = withContext(Dispatchers.IO) {
        if (isListening) return@withContext
        if (!::spotter.isInitialized) initialize()

        // 重建 stream（每次开始监听都重置，避免残留状态）
        stream?.release()
        stream = spotter.createStream()
        synchronized(pcmBuffer) { pcmBuffer.clear() }

        // 外部 PCM 模式：由外部组件提供 PCM 数据，KWS 不创建 AudioRecord
        if (externalPcmMode) {
            isListening = true
            DebugLog.d(TAG, "外部 PCM 模式已启用，等待 feedPcm 数据 (stream=${stream != null})")
            return@withContext
        }

        isListening = true

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize * 2, // 双倍缓冲，减少丢帧
        )
        audioRecord?.startRecording()

        val maxReadSize = SAMPLE_RATE // 最大 1 秒的采样数
        val buffer = ShortArray(maxReadSize)
        var samplesBuffer = FloatArray(maxReadSize)

        // 持续读取麦克风数据并喂入 KWS 解码器
        while (isListening) {
            val currentInterval = readIntervalMs
            val readSize = (SAMPLE_RATE * currentInterval / 1000).toInt().coerceIn(1, maxReadSize)
            val read = audioRecord?.read(buffer, 0, readSize) ?: -1
            if (read > 0) {
                processShortSamples(buffer, read, samplesBuffer)
            } else {
                delay(10) // 避免空转
            }
            // 读取间隔由热管理动态控制
            if (currentInterval > DEFAULT_READ_INTERVAL_MS) {
                delay(currentInterval - DEFAULT_READ_INTERVAL_MS)
            }
        }
    }

    /**
     * 接收外部 PCM 数据（外部 PCM 模式下由 RingBufferAudioRecorder 调用）
     *
     * 非 suspend 函数，不会阻塞调用方。
     * 数据会缓冲到 ~100ms 后批量送入 KWS，与原始模式的输入节奏一致。
     *
     * @param samples 16kHz 单声道 PCM_16BIT 采样数据
     */
    fun feedPcm(samples: ShortArray) {
        if (!isListening || !externalPcmMode) return

        synchronized(pcmBuffer) {
            // Short → Float 并追加到缓冲
            for (i in samples.indices) {
                pcmBuffer.add(samples[i] / 32768.0f)
            }

            // 积累到 100ms 的数据量后送入 KWS
            while (pcmBuffer.size >= MIN_KWS_SAMPLES) {
                val chunk = FloatArray(MIN_KWS_SAMPLES)
                for (i in 0 until MIN_KWS_SAMPLES) {
                    chunk[i] = pcmBuffer[i]
                }
                // 移除已处理的数据
                repeat(MIN_KWS_SAMPLES) { pcmBuffer.removeAt(0) }
                processFloatSamples(chunk)
            }
        }
    }

    /**
     * 处理 Short PCM 采样（原始模式使用）
     */
    private fun processShortSamples(buffer: ShortArray, read: Int, samplesBuffer: FloatArray) {
        val samples = if (read == samplesBuffer.size) {
            samplesBuffer
        } else {
            FloatArray(read)
        }
        for (i in 0 until read) {
            samples[i] = buffer[i] / 32768.0f
        }
        processFloatSamples(samples)
    }

    /**
     * 处理 Float PCM 采样：送入 KWS 解码 → 检测唤醒词
     *
     * 非 suspend，使用 tryEmit 发送唤醒词事件。
     */
    private fun processFloatSamples(samples: FloatArray) {
        val s = stream ?: return
        if (!::spotter.isInitialized) return

        try {
            s.acceptWaveform(samples, SAMPLE_RATE)

            // 解码：只要 spotter 有足够数据就继续解码
            while (spotter.isReady(s)) {
                spotter.decode(s)
            }

            // 检测唤醒词
            val result = spotter.getResult(s)
            val keyword = result?.keyword ?: ""
            if (keyword.isNotBlank()) {
                DebugLog.d(TAG, "检测到唤醒词: $keyword")
                _keywordFlow.tryEmit(keyword)

                // 检测到唤醒词后重置 stream，避免重复触发
                s.release()
                stream = spotter.createStream()
            }
        } catch (e: Exception) {
            DebugLog.e(TAG, "KWS 处理异常: ${e.message}")
        }
    }

    /**
     * 停止监听麦克风
     */
    fun stopListening() {
        isListening = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    /**
     * 完全释放 KWS 资源（包括模型和 native 内存）
     */
    fun release() {
        stopListening()
        stream?.release()
        stream = null
        if (::spotter.isInitialized) {
            spotter.release()
        }
    }

    /**
     * 匹配唤醒词 — 判断是否为"开始录像"相关指令
     */
    fun matchStartRecording(keyword: String): Boolean {
        return keyword.contains("录") || keyword.contains("录像") || keyword == "开始录像"
    }

    /**
     * 匹配唤醒词 — 判断是否为"拍摄动态照片"相关指令
     */
    fun matchMotionPhoto(keyword: String): Boolean {
        return keyword.contains("动态") || keyword.contains("照片") || keyword == "拍摄动态照片"
    }
}