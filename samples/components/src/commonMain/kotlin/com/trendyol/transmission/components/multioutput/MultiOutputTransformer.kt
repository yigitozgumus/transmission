package com.trendyol.transmission.components.multioutput

import com.trendyol.transmission.components.MultiOutputUiState
import com.trendyol.transmission.components.colorpicker.ColorPickerEffectId
import com.trendyol.transmission.components.input.InputEffect
import com.trendyol.transmission.components.output.OutputTransformer
import com.trendyol.transmission.transformer.Transformer
import com.trendyol.transmission.transformer.configure
import com.trendyol.transmission.transformer.dataholder.dataHolder
import com.trendyol.transmission.transformer.request.Contract
import kotlinx.coroutines.CoroutineDispatcher

val multiOutputTransformerIdentity = Contract.identity()

class MultiOutputTransformer(
    private val defaultDispatcher: CoroutineDispatcher
) : Transformer(multiOutputTransformerIdentity, defaultDispatcher) {

    private val holder = dataHolder(MultiOutputUiState())

    init {
        configure {
            onEffect<InputEffect.InputUpdate> { effect ->
                holder.update { it.copy(writtenUppercaseText = effect.value.uppercase()) }
                val result = compute(OutputTransformer.outputCalculationContract)
                holder.update {
                    it.copy(writtenUppercaseText = it.writtenUppercaseText + " ${result?.result}")
                }
            }
            onEffect(ColorPickerEffectId.BackgroundColorUpdate) { effect ->
                holder.update { it.copy(backgroundColor = effect.color) }
            }
            onEffect(ColorPickerEffectId.SelectedColorUpdate) { effect ->
                holder.update { it.copy(selectedColor = effect.color) }
            }
        }
    }

}
