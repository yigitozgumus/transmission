package com.trendyol.transmission.features.output

import com.trendyol.transmission.Transmission
import com.trendyol.transmission.features.colorpicker.ColorPickerEffect
import com.trendyol.transmission.features.input.InputEffect
import com.trendyol.transmission.transformer.Transformer
import com.trendyol.transmission.transformer.handler.buildGenericEffectHandler
import com.trendyol.transmission.ui.OutputUiState
import javax.inject.Inject

class OutputTransformer @Inject constructor() : Transformer<Transmission.Data>() {

	private val holder = TransmissionDataHolder(OutputUiState())

	override val effectHandler = buildGenericEffectHandler { effect ->
		when (effect) {
			is InputEffect.InputUpdate -> {
				holder.update { it.copy(outputText = effect.value) }
			}

			is ColorPickerEffect.BackgroundColorUpdate -> {
				holder.update { it.copy(backgroundColor = effect.color) }
			}
		}
	}
}
