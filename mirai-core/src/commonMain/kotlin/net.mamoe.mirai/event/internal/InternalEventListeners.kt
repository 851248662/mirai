package net.mamoe.mirai.event.internal

import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import net.mamoe.mirai.event.Listener
import net.mamoe.mirai.event.ListeningStatus
import net.mamoe.mirai.event.Subscribable
import net.mamoe.mirai.utils.LockFreeLinkedList
import net.mamoe.mirai.utils.io.logStacktrace
import kotlin.coroutines.CoroutineContext
import kotlin.jvm.JvmField
import kotlin.reflect.KClass

/**
 * 设置为 `true` 以关闭事件.
 * 所有的 `subscribe` 都能正常添加到监听器列表, 但所有的广播都会直接返回.
 */
var EventDisabled = false

@PublishedApi
internal fun <L : Listener<E>, E : Subscribable> KClass<out E>.subscribeInternal(listener: L): L {
    this.listeners().addLast(listener)
    return listener
}

/**
 * 事件监听器.
 *
 * @author Him188moe
 */
internal sealed class ListenerImpl<in E : Subscribable> : Listener<E> {
    abstract override suspend fun onEvent(event: E): ListeningStatus
}

@PublishedApi
@Suppress("FunctionName")
internal fun <E : Subscribable> CoroutineScope.Handler(handler: suspend (E) -> ListeningStatus): Handler<E> {
    return Handler(coroutineContext[Job], coroutineContext, handler)
}

/**
 * 事件处理器.
 */
@PublishedApi
internal class Handler<in E : Subscribable>
@PublishedApi internal constructor(parentJob: Job?, private val subscriberContext: CoroutineContext, @JvmField val handler: suspend (E) -> ListeningStatus) :
    ListenerImpl<E>(), CompletableJob by Job(parentJob) {

    override suspend fun onEvent(event: E): ListeningStatus {
        if (isCompleted || isCancelled) return ListeningStatus.STOPPED
        if (!isActive) return ListeningStatus.LISTENING
        return try {
            // Inherit context.
            withContext(subscriberContext) { handler.invoke(event) }.also { if (it == ListeningStatus.STOPPED) this.complete() }
        } catch (e: Throwable) {
            e.logStacktrace()
            //this.completeExceptionally(e)
            ListeningStatus.STOPPED
        }
    }
}

/**
 * 这个事件类的监听器 list
 */
internal fun <E : Subscribable> KClass<out E>.listeners(): EventListeners<E> = EventListenerManger.get(this)

internal class EventListeners<E : Subscribable> : LockFreeLinkedList<Listener<E>>()

/**
 * 管理每个事件 class 的 [EventListeners].
 * [EventListeners] 是 lazy 的: 它们只会在被需要的时候才创建和存储.
 */
internal object EventListenerManger {
    private data class Registry<E : Subscribable>(val clazz: KClass<E>, val listeners: EventListeners<E>)

    private val registries = LockFreeLinkedList<Registry<*>>()

    @Suppress("UNCHECKED_CAST")
    internal fun <E : Subscribable> get(clazz: KClass<out E>): EventListeners<E> {
        return registries.filteringGetOrAdd({ it.clazz == clazz }) {
            Registry(
                clazz,
                EventListeners()
            )
        }.listeners as EventListeners<E>
    }
}

// inline: NO extra Continuation
internal suspend inline fun Subscribable.broadcastInternal() {
    if (EventDisabled) return

    callAndRemoveIfRequired(this::class.listeners())

    this::class.supertypes.forEach { superType ->
        val superListeners =
            @Suppress("UNCHECKED_CAST")
            (superType.classifier as? KClass<out Subscribable>)?.listeners() ?: return // return if super type is not Subscribable

        callAndRemoveIfRequired(superListeners)
    }
    return
}

private suspend inline fun <E : Subscribable> E.callAndRemoveIfRequired(listeners: EventListeners<E>) {
    // atomic foreach
    listeners.forEach {
        if (it.onEvent(this) == ListeningStatus.STOPPED) {
            listeners.remove(it) // atomic remove
        }
    }
}