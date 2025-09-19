package com.example.complicationprovider

import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.*
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService

class ComplicationProviderService : SuspendingComplicationDataSourceService() {

    override suspend fun onComplicationRequest(
        request: ComplicationRequest
    ): ComplicationData? {
        return when (request.complicationType) {
            ComplicationType.SHORT_TEXT          -> shortText()
            ComplicationType.LONG_TEXT           -> longText()
            ComplicationType.RANGED_VALUE        -> rangedValue()
            ComplicationType.SMALL_IMAGE         -> smallImageIcon()
            ComplicationType.MONOCHROMATIC_IMAGE -> monochromeIcon()
            ComplicationType.PHOTO_IMAGE         -> photoImage()
            else -> NotConfiguredComplicationData()
        }
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        when (type) {
            ComplicationType.SHORT_TEXT          -> shortText()
            ComplicationType.LONG_TEXT           -> longText()
            ComplicationType.RANGED_VALUE        -> rangedValue()
            ComplicationType.SMALL_IMAGE         -> smallImageIcon()
            ComplicationType.MONOCHROMATIC_IMAGE -> monochromeIcon()
            ComplicationType.PHOTO_IMAGE         -> photoImage()
            else -> null
        }

    // --- TEXT ---
    private fun shortText(): ComplicationData =
        ShortTextComplicationData.Builder(
            text = PlainComplicationText.Builder("Zlato 1850€").build(),
            contentDescription = PlainComplicationText.Builder("Cijena zlata u eurima").build()
        ).setTitle(PlainComplicationText.Builder("Zlato").build())
            .build()

    private fun longText(): ComplicationData =
        LongTextComplicationData.Builder(
            text = PlainComplicationText.Builder("Cijena zlata: 1850€ po unci").build(),
            contentDescription = PlainComplicationText.Builder("Cijena zlata u eurima").build()
        ).setTitle(PlainComplicationText.Builder("Zlato").build())
            .build()

    // --- RANGED/PROGRESS ---
    private fun rangedValue(): ComplicationData =
        RangedValueComplicationData.Builder(
            value = 75f, min = 0f, max = 100f,
            contentDescription = PlainComplicationText.Builder("Napredak").build()
        ).setText(PlainComplicationText.Builder("75%").build())
            .build()

    // --- IMAGES ---
    private fun smallImageIcon(): ComplicationData {
        val img = SmallImage.Builder(
            image = Icon.createWithResource(this, R.drawable.ic_gold),
            type = SmallImageType.ICON
        ).build()
        return SmallImageComplicationData.Builder(
            smallImage = img,
            contentDescription = PlainComplicationText.Builder("Ikona zlata").build()
        ).build()
    }

    private fun monochromeIcon(): ComplicationData {
        val mono = MonochromaticImage.Builder(
            Icon.createWithResource(this, R.drawable.ic_gold)
        ).build()
        return MonochromaticImageComplicationData.Builder(
            monochromaticImage = mono,
            contentDescription = PlainComplicationText.Builder("Monokromatska ikona zlata").build()
        ).build()
    }

    private fun photoImage(): ComplicationData {
        return PhotoImageComplicationData.Builder(
            photoImage = Icon.createWithResource(this, R.drawable.ic_gold_photo),
            contentDescription = PlainComplicationText.Builder("Fotografija zlata").build()
        ).build()
    }
}