@file:OptIn(com.trendyol.transmission.router.ExperimentalTransmissionIdGeneration::class)

package com.trendyol.transmission.components.colorpicker

import androidx.compose.ui.graphics.Color
import com.trendyol.transmission.Transmission
import com.trendyol.transmission.router.GenerateTransmissionId

@GenerateTransmissionId
sealed interface ColorPickerEffect : Transmission.Effect {
    data class BackgroundColorUpdate(val color: Color) : ColorPickerEffect
    data class SelectedColorUpdate(val color: Color) : ColorPickerEffect
}
