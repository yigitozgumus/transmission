package com.trendyol.transmission.transformer

import com.trendyol.transmission.Transmission
import com.trendyol.transmission.effect.RouterPayloadEffect
import com.trendyol.transmission.transformer.handler.EffectHandler
import com.trendyol.transmission.transformer.handler.HandlerScope
import com.trendyol.transmission.transformer.handler.SignalHandler
import com.trendyol.transmission.transformer.query.DataQuery
import com.trendyol.transmission.transformer.query.QueryResponse
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.reflect.KClass

typealias DefaultTransformer = Transformer<Transmission.Data, Transmission.Effect>

open class Transformer<D : Transmission.Data, E : Transmission.Effect>(
	dispatcher: CoroutineDispatcher = Dispatchers.Default
) {

	private val transformerName = this::class.java.simpleName

	private val dataChannel: Channel<D> = Channel(capacity = Channel.UNLIMITED)
	private val effectChannel: Channel<E> = Channel(capacity = Channel.UNLIMITED)

	private val outGoingQueryChannel: Channel<DataQuery> = Channel()
	private val queryResponseChannel: Channel<D?> = Channel()

	private val holderDataReference: MutableStateFlow<D?> = MutableStateFlow(null)
	val holderData = holderDataReference.asStateFlow()

	private val jobList: MutableList<Job?> = mutableListOf()

	protected var transmissionDataHolderStateInternal: HolderState = HolderState.Undefined
	val transmissionDataHolderState: HolderState
		get() = transmissionDataHolderStateInternal

	open val signalHandler: SignalHandler<D, E>? = null

	open val effectHandler: EffectHandler<D, E>? = null

	private val transformerScope = CoroutineScope(SupervisorJob() + dispatcher)

	private val handlerScope: HandlerScope<D, E> = object : HandlerScope<D, E> {
		override fun publishData(data: D?) {
			data?.let { dataChannel.trySend(it) }
		}

		override fun publishEffect(effect: E) {
			effectChannel.trySend(effect)
		}
	}

	@Suppress("UNCHECKED_CAST")
	protected suspend fun <D : Transmission.Data> queryRouterForData(type: KClass<D>): D? {
		outGoingQueryChannel.trySend(
			DataQuery(
				sender = transformerName,
				type = type.simpleName.orEmpty()
			)
		)
		return queryResponseChannel.receive() as? D
	}


	fun initialize(
		incomingSignal: SharedFlow<Transmission.Signal>,
		incomingEffect: SharedFlow<Transmission.Effect>,
		incomingQueryResponse: SharedFlow<QueryResponse<D>>,
		outGoingData: SendChannel<D>,
		outGoingEffect: SendChannel<E>,
		outGoingQuery: SendChannel<DataQuery>,
	) {
		jobList += transformerScope.launch {
			launch {
				incomingSignal.collect {
					signalHandler?.apply { with(handlerScope) { onSignal(it) } }
				}
			}
			launch {
				incomingEffect.filterNot { it is RouterPayloadEffect }.collect {
					effectHandler?.apply { with(handlerScope) { onEffect(it) } }
				}
			}
			launch {
				incomingQueryResponse.filter { it.owner == transformerName }.collect {
					queryResponseChannel.trySend(it.data)
				}
			}
			launch { outGoingQueryChannel.receiveAsFlow().collect { outGoingQuery.trySend(it) } }
			launch { dataChannel.receiveAsFlow().collect { outGoingData.trySend(it) } }
			launch {
				effectChannel.receiveAsFlow().collect { outGoingEffect.trySend(it) }
			}
		}
	}

	fun clear() {
		jobList.clearJobs()
	}

	// region DataHolder

	inner class TransmissionDataHolder<T : D?>(initialValue: T) {

		private val holder = MutableStateFlow(initialValue)

		val value: T
			get() = holder.value

		init {
			jobList += transformerScope.launch {
				holder.collect {
					it?.let { holderData ->
						holderDataReference.update { holderData }
						dataChannel.trySend(it)
					}
				}
			}
		}

		fun update(updater: (T) -> @UnsafeVariance T) {
			holder.update(updater)
		}
	}

	protected inline fun <reified T : D> Transformer<D, E>.buildDataHolder(
		initialValue: T
	): TransmissionDataHolder<T> {
		transmissionDataHolderStateInternal = HolderState.Initialized(T::class.java.simpleName)
		return TransmissionDataHolder(initialValue)
	}

	// endregion

	// region handler extensions

	inline fun <reified S : Transmission.Signal> Transformer<D, E>.buildTypedSignalHandler(
		crossinline onSignal: suspend HandlerScope<D, E>.(signal: S) -> Unit
	): SignalHandler<D, E> {
		return SignalHandler { incomingSignal ->
			incomingSignal
				.takeIf { it is S }
				?.let { signal -> onSignal(signal as S) }
		}
	}

	inline fun <reified E : Transmission.Effect> Transformer<D, E>.buildTypedEffectHandler(
		crossinline onEffect: suspend HandlerScope<D, E>.(effect: E) -> Unit
	): EffectHandler<D, E> {
		return EffectHandler { incomingEffect ->
			incomingEffect
				.takeIf { it is E }
				?.let { effect -> onEffect(effect as E) }
		}
	}

	// endregion

}
