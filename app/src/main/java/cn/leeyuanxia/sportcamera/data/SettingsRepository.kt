package cn.leeyuanxia.sportcamera.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import cn.leeyuanxia.sportcamera.domain.model.CameraLens
import cn.leeyuanxia.sportcamera.domain.model.PreRecordDuration
import cn.leeyuanxia.sportcamera.domain.model.RecordOrientation
import cn.leeyuanxia.sportcamera.domain.model.ResolutionProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 设置持久化仓库 — DataStore 保存用户偏好
 *
 * 存储项：
 * - 预录时长
 * - 镜头选择
 * - 分辨率档位
 * - 录制方向（横屏/竖屏）
 * - 视频防抖（EIS）
 */
class SettingsRepository(private val context: Context) {

    companion object {
        private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("settings")

        private val KEY_PRE_RECORD = intPreferencesKey("pre_record_duration")
        private val KEY_CAMERA_LENS = stringPreferencesKey("camera_lens")
        private val KEY_RESOLUTION = stringPreferencesKey("resolution_profile")
        private val KEY_ORIENTATION = stringPreferencesKey("record_orientation")
        private val KEY_VIDEO_STABILIZATION = booleanPreferencesKey("video_stabilization")
    }

    /** 预录时长（自定义秒数，1~120s） */
    val preRecordDuration: Flow<PreRecordDuration> = context.dataStore.data.map { prefs ->
        val seconds = prefs[KEY_PRE_RECORD] ?: PreRecordDuration.DEFAULT.seconds
        PreRecordDuration(seconds.coerceIn(PreRecordDuration.MIN, PreRecordDuration.MAX))
    }

    /** 镜头选择 */
    val cameraLens: Flow<CameraLens> = context.dataStore.data.map { prefs ->
        val name = prefs[KEY_CAMERA_LENS] ?: CameraLens.DEFAULT.name
        CameraLens.entries.find { it.name == name } ?: CameraLens.DEFAULT
    }

    /** 分辨率档位 */
    val resolutionProfile: Flow<ResolutionProfile> = context.dataStore.data.map { prefs ->
        val name = prefs[KEY_RESOLUTION] ?: ResolutionProfile.DEFAULT.name
        ResolutionProfile.entries.find { it.name == name } ?: ResolutionProfile.DEFAULT
    }

    /** 录制方向 */
    val recordOrientation: Flow<RecordOrientation> = context.dataStore.data.map { prefs ->
        val name = prefs[KEY_ORIENTATION] ?: RecordOrientation.DEFAULT.name
        RecordOrientation.entries.find { it.name == name } ?: RecordOrientation.DEFAULT
    }

    /** 设置预录时长 */
    suspend fun setPreRecordDuration(duration: PreRecordDuration) {
        context.dataStore.edit { prefs ->
            prefs[KEY_PRE_RECORD] = duration.seconds
        }
    }

    /** 设置镜头选择 */
    suspend fun setCameraLens(lens: CameraLens) {
        context.dataStore.edit { prefs ->
            prefs[KEY_CAMERA_LENS] = lens.name
        }
    }

    /** 设置分辨率档位 */
    suspend fun setResolutionProfile(profile: ResolutionProfile) {
        context.dataStore.edit { prefs ->
            prefs[KEY_RESOLUTION] = profile.name
        }
    }

    /** 设置录制方向 */
    suspend fun setRecordOrientation(orientation: RecordOrientation) {
        context.dataStore.edit { prefs ->
            prefs[KEY_ORIENTATION] = orientation.name
        }
    }

    /** 视频防抖（EIS），默认关闭（EIS 会裁切传感器 ~10-15%，降低 FOV） */
    val videoStabilization: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_VIDEO_STABILIZATION] ?: false
    }

    /** 设置视频防抖 */
    suspend fun setVideoStabilization(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_VIDEO_STABILIZATION] = enabled
        }
    }
}