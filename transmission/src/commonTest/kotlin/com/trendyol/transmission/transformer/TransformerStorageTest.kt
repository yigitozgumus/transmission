package com.trendyol.transmission.transformer

import com.trendyol.transmission.transformer.data.TestData
import com.trendyol.transmission.transformer.dataholder.dataHolder
import com.trendyol.transmission.transformer.request.Contract
import kotlin.test.Test
import kotlin.test.assertEquals

class TransformerStorageTest {

    @Test
    fun `GIVEN storage with holders computations and executions WHEN clear is called THEN all state is removed`() {
        // Given
        val transformer = Transformer()
        val dataContract = Contract.dataHolder<TestData>()
        val computationContract = Contract.computation<String>()
        val executionContract = Contract.execution()
        transformer.dataHolder(TestData("initial"), dataContract)
        transformer.registerComputation(computationContract) { "computed" }
        transformer.registerExecution(executionContract) {}

        // When
        transformer.storage.clear()

        // Then
        assertEquals(false, transformer.storage.isHolderStateInitialized())
        assertEquals(false, transformer.storage.isHolderDataDefined(dataContract.key))
        assertEquals(false, transformer.storage.hasComputation(computationContract.key))
        assertEquals(false, transformer.storage.hasExecution(executionContract.key))
        assertEquals(null, transformer.storage.getHolderDataByKey(dataContract.key))
    }
}
