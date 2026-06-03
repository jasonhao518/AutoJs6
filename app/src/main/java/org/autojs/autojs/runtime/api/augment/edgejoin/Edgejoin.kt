package org.autojs.autojs.runtime.api.augment.edgejoin

import org.autojs.autojs.annotation.RhinoRuntimeFunctionInterface
import org.autojs.autojs.rhino.ArgumentGuards
import org.autojs.autojs.rhino.extension.AnyExtensions.isJsNullish
import org.autojs.autojs.runtime.ScriptRuntime
import org.autojs.autojs.runtime.api.EdgeJoinBridge
import org.autojs.autojs.runtime.api.augment.Augmentable
import org.autojs.autojs.util.RhinoUtils.UNDEFINED
import org.autojs.autojs.util.RhinoUtils.callFunction
import org.autojs.autojs.util.RhinoUtils.coerceString
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Function
import org.mozilla.javascript.Undefined
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class Edgejoin(scriptRuntime: ScriptRuntime) : Augmentable(scriptRuntime) {

    override val key = "edgejoin"

    override val selfAssignmentFunctions = listOf(
        ::sendMessage.name,
        ::onMessage.name,
        ::offMessage.name,
        ::clearMessageCallbacks.name,
    )

    companion object : ArgumentGuards() {

        private data class RuntimeCallbacks(
            val runtimeRef: WeakReference<ScriptRuntime>,
            val callbacks: CopyOnWriteArrayList<BaseFunction> = CopyOnWriteArrayList(),
        )

        private val callbacksByRuntime = ConcurrentHashMap<String, RuntimeCallbacks>()
        private val listenerRegistered = AtomicBoolean(false)

        private val bridgeListener = EdgeJoinBridge.ScriptMessageListener { peerId, protocol, payload ->
            dispatchIncomingMessage(peerId ?: "", protocol ?: "", payload ?: "")
        }

        private fun ensureBridgeListenerRegistered() {
            if (listenerRegistered.compareAndSet(false, true)) {
                EdgeJoinBridge.addScriptMessageListener(bridgeListener)
            }
        }

        private fun dispatchIncomingMessage(peerId: String, protocol: String, payload: String) {
            callbacksByRuntime.forEach { (ownerId, holder) ->
                val runtime = holder.runtimeRef.get()
                if (runtime == null || runtime.isStopped) {
                    callbacksByRuntime.remove(ownerId)
                    return@forEach
                }
                if (holder.callbacks.isEmpty()) {
                    return@forEach
                }
                holder.callbacks.forEach { callback ->
                    runCatching {
                        callFunction(runtime, callback, runtime.topLevelScope, runtime.topLevelScope, arrayOf(peerId, protocol, payload))
                    }
                }
            }
        }

        @JvmStatic
        @RhinoRuntimeFunctionInterface
        fun sendMessage(scriptRuntime: ScriptRuntime, args: Array<out Any?>): String = ensureArgumentsLengthInRange(args, 2..3) {
            val peerId = coerceString(it[0])
            val payload = coerceString(it[1])
            val protocol = it.getOrNull(2)

            if (protocol.isJsNullish()) {
                EdgeJoinBridge.sendScriptMessage(peerId, payload)
            } else {
                EdgeJoinBridge.sendScriptMessage(peerId, coerceString(protocol), payload)
            }
        }

        @JvmStatic
        @RhinoRuntimeFunctionInterface
        fun onMessage(scriptRuntime: ScriptRuntime, args: Array<out Any?>): Undefined = ensureArgumentsOnlyOne(args) {
            require(it is BaseFunction) { "edgejoin.onMessage expects one callback function" }
            ensureBridgeListenerRegistered()
            val holder = callbacksByRuntime.computeIfAbsent(scriptRuntime.ownerId) {
                RuntimeCallbacks(WeakReference(scriptRuntime))
            }
            if (!holder.callbacks.contains(it)) {
                holder.callbacks.add(it)
            }
            UNDEFINED
        }

        @JvmStatic
        @RhinoRuntimeFunctionInterface
        fun offMessage(scriptRuntime: ScriptRuntime, args: Array<out Any?>): Undefined = ensureArgumentsOnlyOne(args) {
            require(it is BaseFunction) { "edgejoin.offMessage expects one callback function" }
            val holder = callbacksByRuntime[scriptRuntime.ownerId]
            holder?.callbacks?.remove(it)
            if (holder != null && holder.callbacks.isEmpty()) {
                callbacksByRuntime.remove(scriptRuntime.ownerId)
            }
            UNDEFINED
        }

        @JvmStatic
        @RhinoRuntimeFunctionInterface
        fun clearMessageCallbacks(scriptRuntime: ScriptRuntime, args: Array<out Any?>): Undefined = ensureArgumentsIsEmpty(args) {
            callbacksByRuntime.remove(scriptRuntime.ownerId)
            UNDEFINED
        }
    }
}
