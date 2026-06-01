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
        const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val READ_INTERVAL_MS = 100L // 100ms 读取间隔，省电
        private const val MODEL_DIR = "onnx-kws"

        /** 读取帧数 = 采样率 × 间隔秒数 */
        private val READ_SIZE = (SAMPLE_RATE * READ_INTERVAL_MS / 1000).toInt()
    }

    private lateinit var spotter: KeywordSpotter
    private var stream: OnlineStream? = null
    private var audioRecord: AudioRecord? = null

    @Volatile
    private var isListening = false

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
     */
    suspend fun startListening() = withContext(Dispatchers.IO) {
        if (isListening) return@withContext
        if (!::spotter.isInitialized) initialize()

        // 重建 stream（每次开始监听都重置，避免残留状态）
        stream?.release()
        stream = spotter.createStream()

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize * 2, // 双倍缓冲，减少丢帧
        )
        audioRecord?.startRecording()
        isListening = true

        val buffer = ShortArray(READ_SIZE)

        // 持续读取麦克风数据并喂入 KWS 解码器
        while (isListening) {
            val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
            if (read > 0) {
                // Short → Float 转换（PCM 16-bit 范围 -32768~32767）
                val samples = FloatArray(read) { i -> buffer[i] / 32768.0f }
                stream?.acceptWaveform(samples, SAMPLE_RATE)

                // 解码：只要 spotter 有足够数据就继续解码
                while (stream != null && spotter.isReady(stream!!)) {
                    spotter.decode(stream!!)
                }

                // 检测唤醒词
                val result = stream?.let { spotter.getResult(it) }
                val keyword = result?.keyword ?: ""
                if (keyword.isNotBlank()) {
                    _keywordFlow.emit(keyword)

                    // 检测到唤醒词后重置 stream，避免重复触发
                    stream?.release()
                    stream = spotter.createStream()
                }
            } else {
                delay(10) // 避免空转
            }
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
}