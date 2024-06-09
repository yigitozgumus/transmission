package com.trendyol.transmission

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trendyol.transmission.effect.RouterEffect
import com.trendyol.transmission.ui.ColorPickerUiState
import com.trendyol.transmission.ui.InputUiState
import com.trendyol.transmission.ui.MultiOutputUiState
import com.trendyol.transmission.ui.OutputUiState
import com.trendyol.transmission.ui.SampleScreenUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

@HiltViewModel
class SampleViewModel @Inject constructor(
    private val transmissionRouter: TransmissionRouter
) : ViewModel() {

    private val _uiState = MutableStateFlow(SampleScreenUiState())
    val uiState = _uiState.asStateFlow()

    private val _transmissionList = MutableStateFlow<List<String>>(emptyList())
    val transmissionList = _transmissionList.asStateFlow()

    init {
        viewModelScope.launch {
            launch {
                transmissionRouter.dataStream.collect(::onData)
            }
            launch {
                transmissionRouter.effectStream.collect(::onEffect)
            }
        }
    }

    fun processSignal(signal: Transmission.Signal) {
        transmissionRouter.processSignal(signal)
        _transmissionList.update { it.plus("Signal: $signal") }
    }

    private fun onEffect(effect: Transmission.Effect) = viewModelScope.launch {
        _transmissionList.update { it.plus("Effect: $effect") }
        if (effect is RouterEffect) {
            when (effect.payload) {
                is OutputUiState -> {
                    _transmissionList.update { it.plus("Generic Effect: $effect") }
                }
            }
        }
        val inputData = transmissionRouter.queryHelper.queryData<InputUiState>("InputUiState")
        delay(1.seconds)
        val colorPicker =
            transmissionRouter.queryHelper.queryData<ColorPickerUiState>("ColorPickerUiState")
        _transmissionList.update { it.plus("Current InputData: $inputData") }
        _transmissionList.update { it.plus("Current ColorPickerData: $colorPicker") }
    }


    private fun onData(data: Transmission.Data) {
        when (data) {
            is InputUiState -> _uiState.update { it.copy(inputState = data) }
            is OutputUiState -> _uiState.update { it.copy(outputState = data) }
            is MultiOutputUiState -> _uiState.update { it.copy(multiOutputState = data) }
            is ColorPickerUiState -> _uiState.update { it.copy(colorPickerState = data) }
        }
        _transmissionList.update { it.plus("Data: $data") }
    }


    override fun onCleared() {
        transmissionRouter.clear()
    }

}
