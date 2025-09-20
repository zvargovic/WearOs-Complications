package com.example.complicationprovider.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val DS_NAME = "ui_config"
val Context.uiDataStore by preferencesDataStore(DS_NAME)

object UiKeys {
    // title
    val TITLE_X   = floatPreferencesKey("title_x")
    val TITLE_Y   = floatPreferencesKey("title_y")
    val TITLE_W   = floatPreferencesKey("title_w")
    val TITLE_H   = floatPreferencesKey("title_h")
    val TITLE_FS  = floatPreferencesKey("title_fs")
    val TITLE_COL = intPreferencesKey("title_col")

    // subtitle
    val SUB_X   = floatPreferencesKey("sub_x")
    val SUB_Y   = floatPreferencesKey("sub_y")
    val SUB_W   = floatPreferencesKey("sub_w")
    val SUB_H   = floatPreferencesKey("sub_h")
    val SUB_FS  = floatPreferencesKey("sub_fs")
    val SUB_COL = intPreferencesKey("sub_col")

    // button
    val BTN_X   = floatPreferencesKey("btn_x")
    val BTN_Y   = floatPreferencesKey("btn_y")
    val BTN_W   = floatPreferencesKey("btn_w")
    val BTN_H   = floatPreferencesKey("btn_h")

    // toggle
    val TGL_X   = floatPreferencesKey("tgl_x")
    val TGL_Y   = floatPreferencesKey("tgl_y")
    val TGL_W   = floatPreferencesKey("tgl_w")
    val TGL_H   = floatPreferencesKey("tgl_h")
}

data class UiPrefs(
    // title
    val titleX: Float = 0f, val titleY: Float = -40f,
    val titleW: Float = 160f, val titleH: Float = 28f,
    val titleFs: Float = 16f, val titleColorArgb: Int? = null,
    // subtitle
    val subX: Float = 0f, val subY: Float = -16f,
    val subW: Float = 180f, val subH: Float = 22f,
    val subFs: Float = 12f, val subColorArgb: Int? = null,
    // button
    val btnX: Float = 0f, val btnY: Float = 20f,
    val btnW: Float = 120f, val btnH: Float = 44f,
    // toggle
    val tglX: Float = 0f, val tglY: Float = 72f,
    val tglW: Float = 140f, val tglH: Float = 44f,
)

class UiPrefsRepo(private val context: Context) {

    val flow: Flow<UiPrefs> = context.uiDataStore.data.map { p ->
        UiPrefs(
            // title
            titleX = p[UiKeys.TITLE_X] ?: 0f,
            titleY = p[UiKeys.TITLE_Y] ?: -40f,
            titleW = p[UiKeys.TITLE_W] ?: 160f,
            titleH = p[UiKeys.TITLE_H] ?: 28f,
            titleFs = p[UiKeys.TITLE_FS] ?: 16f,
            titleColorArgb = p[UiKeys.TITLE_COL],
            // subtitle
            subX = p[UiKeys.SUB_X] ?: 0f,
            subY = p[UiKeys.SUB_Y] ?: -16f,
            subW = p[UiKeys.SUB_W] ?: 180f,
            subH = p[UiKeys.SUB_H] ?: 22f,
            subFs = p[UiKeys.SUB_FS] ?: 12f,
            subColorArgb = p[UiKeys.SUB_COL],
            // button
            btnX = p[UiKeys.BTN_X] ?: 0f,
            btnY = p[UiKeys.BTN_Y] ?: 20f,
            btnW = p[UiKeys.BTN_W] ?: 120f,
            btnH = p[UiKeys.BTN_H] ?: 44f,
            // toggle
            tglX = p[UiKeys.TGL_X] ?: 0f,
            tglY = p[UiKeys.TGL_Y] ?: 72f,
            tglW = p[UiKeys.TGL_W] ?: 140f,
            tglH = p[UiKeys.TGL_H] ?: 44f,
        )
    }

    suspend fun save(block: UiPrefs.() -> UiPrefs) {
        context.uiDataStore.edit { p ->
            val n = block(UiPrefs(/* default fallback */))
            // title
            p[UiKeys.TITLE_X] = n.titleX; p[UiKeys.TITLE_Y] = n.titleY
            p[UiKeys.TITLE_W] = n.titleW; p[UiKeys.TITLE_H] = n.titleH
            p[UiKeys.TITLE_FS] = n.titleFs
            n.titleColorArgb?.let { p[UiKeys.TITLE_COL] = it } ?: p.remove(UiKeys.TITLE_COL)
            // subtitle
            p[UiKeys.SUB_X] = n.subX; p[UiKeys.SUB_Y] = n.subY
            p[UiKeys.SUB_W] = n.subW; p[UiKeys.SUB_H] = n.subH
            p[UiKeys.SUB_FS] = n.subFs
            n.subColorArgb?.let { p[UiKeys.SUB_COL] = it } ?: p.remove(UiKeys.SUB_COL)
            // button
            p[UiKeys.BTN_X] = n.btnX; p[UiKeys.BTN_Y] = n.btnY
            p[UiKeys.BTN_W] = n.btnW; p[UiKeys.BTN_H] = n.btnH
            // toggle
            p[UiKeys.TGL_X] = n.tglX; p[UiKeys.TGL_Y] = n.tglY
            p[UiKeys.TGL_W] = n.tglW; p[UiKeys.TGL_H] = n.tglH
        }
    }
}