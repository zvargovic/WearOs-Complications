package com.example.complicationprovider.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.example.complicationprovider.data.UiPrefs

/** Stil jednog UI elementa koji ćemo moći pomicati i stilizirati. */
class ElementStyle(
    xDp: Float = 0f,
    yDp: Float = 0f,
    widthDp: Float = 120f,
    heightDp: Float = 40f,
    fontSp: Float = 14f,
    color: Color = Color.Unspecified
) {
    var xDp by mutableStateOf(xDp)
    var yDp by mutableStateOf(yDp)
    var widthDp by mutableStateOf(widthDp)
    var heightDp by mutableStateOf(heightDp)
    var fontSp by mutableStateOf(fontSp)
    var color by mutableStateOf(color)
}

/** Globalna konfiguracija – ovdje držimo sve elemente na jednom mjestu. */
class UiConfig {
    val title = ElementStyle(xDp = 0f,  yDp = -40f, widthDp = 160f, heightDp = 28f, fontSp = 16f)
    val subtitle = ElementStyle(xDp = 0f, yDp = -16f, widthDp = 180f, heightDp = 22f, fontSp = 12f)
    val button = ElementStyle(xDp = 0f,  yDp = 20f,  widthDp = 120f, heightDp = 44f)
    val toggle = ElementStyle(xDp = 0f,  yDp = 72f,  widthDp = 140f, heightDp = 44f)
}

/** Pomoćna ekstenzija – primijeni vrijednosti iz UiPrefs na UiConfig */
fun UiConfig.applyFrom(p: UiPrefs) {
    // title
    title.xDp = p.titleX
    title.yDp = p.titleY
    title.widthDp = p.titleW
    title.heightDp = p.titleH
    title.fontSp = p.titleFs
    p.titleColorArgb?.let { title.color = Color(it) }

    // subtitle
    subtitle.xDp = p.subX
    subtitle.yDp = p.subY
    subtitle.widthDp = p.subW
    subtitle.heightDp = p.subH
    subtitle.fontSp = p.subFs
    p.subColorArgb?.let { subtitle.color = Color(it) }

    // button
    button.xDp = p.btnX
    button.yDp = p.btnY
    button.widthDp = p.btnW
    button.heightDp = p.btnH

    // toggle
    toggle.xDp = p.tglX
    toggle.yDp = p.tglY
    toggle.widthDp = p.tglW
    toggle.heightDp = p.tglH
}