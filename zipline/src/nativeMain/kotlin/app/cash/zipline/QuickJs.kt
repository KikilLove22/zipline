/*
 * Copyright (C) 2021 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package app.cash.zipline

import app.cash.zipline.internal.bridge.CallChannel
import app.cash.zipline.internal.bridge.inboundChannelName
import app.cash.zipline.internal.bridge.outboundChannelName
import app.cash.zipline.quickjs.JSClassDef
import app.cash.zipline.quickjs.JSClassIDVar
import app.cash.zipline.quickjs.JSContext
import app.cash.zipline.quickjs.JSMemoryUsage
import app.cash.zipline.quickjs.JSRuntime
import app.cash.zipline.quickjs.JSValue
import app.cash.zipline.quickjs.JS_ComputeMemoryUsage
import app.cash.zipline.quickjs.JS_EVAL_FLAG_COMPILE_ONLY
import app.cash.zipline.quickjs.JS_Eval
import app.cash.zipline.quickjs.JS_EvalFunction
import app.cash.zipline.quickjs.JS_FreeAtom
import app.cash.zipline.quickjs.JS_FreeContext
import app.cash.zipline.quickjs.JS_FreeRuntime
import app.cash.zipline.quickjs.JS_FreeValue
import app.cash.zipline.quickjs.JS_GetException
import app.cash.zipline.quickjs.JS_GetGlobalObject
import app.cash.zipline.quickjs.JS_GetPropertyStr
import app.cash.zipline.quickjs.JS_GetPropertyUint32
import app.cash.zipline.quickjs.JS_GetRuntime
import app.cash.zipline.quickjs.JS_GetRuntimeOpaque
import app.cash.zipline.quickjs.JS_HasProperty
import app.cash.zipline.quickjs.JS_IsArray
import app.cash.zipline.quickjs.JS_IsException
import app.cash.zipline.quickjs.JS_IsUndefined
import app.cash.zipline.quickjs.JS_NewArray
import app.cash.zipline.quickjs.JS_NewAtom
import app.cash.zipline.quickjs.JS_NewClass
import app.cash.zipline.quickjs.JS_NewClassID
import app.cash.zipline.quickjs.JS_NewContext
import app.cash.zipline.quickjs.JS_NewObjectClass
import app.cash.zipline.quickjs.JS_NewRuntime
import app.cash.zipline.quickjs.JS_NewString
import app.cash.zipline.quickjs.JS_READ_OBJ_BYTECODE
import app.cash.zipline.quickjs.JS_READ_OBJ_REFERENCE
import app.cash.zipline.quickjs.JS_ReadObject
import app.cash.zipline.quickjs.JS_ResolveModule
import app.cash.zipline.quickjs.JS_RunGC
import app.cash.zipline.quickjs.JS_SetGCThreshold
import app.cash.zipline.quickjs.JS_SetInterruptHandler
import app.cash.zipline.quickjs.JS_SetMaxStackSize
import app.cash.zipline.quickjs.JS_SetMemoryLimit
import app.cash.zipline.quickjs.JS_SetProperty
import app.cash.zipline.quickjs.JS_SetPropertyFunctionList
import app.cash.zipline.quickjs.JS_SetPropertyUint32
import app.cash.zipline.quickjs.JS_SetRuntimeOpaque
import app.cash.zipline.quickjs.JS_TAG_BOOL
import app.cash.zipline.quickjs.JS_TAG_EXCEPTION
import app.cash.zipline.quickjs.JS_TAG_FLOAT64
import app.cash.zipline.quickjs.JS_TAG_INT
import app.cash.zipline.quickjs.JS_TAG_NULL
import app.cash.zipline.quickjs.JS_TAG_OBJECT
import app.cash.zipline.quickjs.JS_TAG_STRING
import app.cash.zipline.quickjs.JS_TAG_UNDEFINED
import app.cash.zipline.quickjs.JS_ToCString
import app.cash.zipline.quickjs.JS_WRITE_OBJ_BYTECODE
import app.cash.zipline.quickjs.JS_WRITE_OBJ_REFERENCE
import app.cash.zipline.quickjs.JS_WriteObject
import app.cash.zipline.quickjs.JsDisconnectFunction
import app.cash.zipline.quickjs.JsFalse
import app.cash.zipline.quickjs.JsInvokeFunction
import app.cash.zipline.quickjs.JsInvokeSuspendingFunction
import app.cash.zipline.quickjs.JsServiceNamesFunction
import app.cash.zipline.quickjs.JsTrue
import app.cash.zipline.quickjs.JsUndefined
import app.cash.zipline.quickjs.JsValueArrayToInstanceRef
import app.cash.zipline.quickjs.JsValueGetBool
import app.cash.zipline.quickjs.JsValueGetFloat64
import app.cash.zipline.quickjs.JsValueGetInt
import app.cash.zipline.quickjs.JsValueGetNormTag
import app.cash.zipline.quickjs.installFinalizationRegistry
import app.cash.zipline.quickjs.js_free
import kotlinx.cinterop.CArrayPointer
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CValue
import kotlinx.cinterop.CValuesRef
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.convert
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.refTo
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKStringFromUtf8
import kotlinx.cinterop.value
import platform.posix.size_tVar

actual class QuickJs private constructor(
  private val runtime: CPointer<JSRuntime>,
  internal val context: CPointer<JSContext>
) {
  actual companion object {
    actual fun create(): QuickJs {
      val runtime = JS_NewRuntime() ?: throw OutOfMemoryError()
      val context = JS_NewContext(runtime)
      if (context == null) {
        JS_FreeRuntime(runtime)
        throw OutOfMemoryError()
      }
      return QuickJs(runtime, context)
        .apply {
          // Explicitly assign default values to these properties so the backing fields values
          // are consistent with their native fields. (QuickJS doesn't offer accessors for these.)
          // TODO extract this somewhere common to share with jniMain/
          memoryLimit = -1L
          gcThreshold = 256L * 1024L
          maxStackSize = 512L * 1024L // Override the QuickJS default which is 256 KiB
          installFinalizationRegistry(context)
        }
    }

    actual val version: String
      get() = quickJsVersion
  }

  private val jsInterruptHandlerCFunction = staticCFunction(::jsInterruptHandlerGlobal)
  private val thisPtr = StableRef.create(this)
  init {
    JS_SetRuntimeOpaque(runtime, thisPtr.asCPointer())
    JS_SetInterruptHandler(runtime, jsInterruptHandlerCFunction, thisPtr.asCPointer())
  }

  private var closed = false
  private var outboundChannel: CallChannel? = null

  internal fun jsInterruptHandler(runtime: CPointer<JSRuntime>?): Int {
    val interruptHandler = interruptHandler ?: return 0

    JS_SetInterruptHandler(runtime, null, null) // Suppress re-enter.

    val result = try {
      interruptHandler.poll()
    } catch (t: Throwable) {
      // TODO: propagate the interrupt handler's exceptions through JS.
      true // Halt JS.
    } finally {
      // Restore handler.
      JS_SetInterruptHandler(runtime, jsInterruptHandlerCFunction, thisPtr.asCPointer())
    }

    return if (result) 1 else 0
  }

  actual var interruptHandler: InterruptHandler? = null
    set(value) {
      checkNotClosed()

      field = value
    }

  /** Memory usage statistics for the JavaScript engine. */
  actual val memoryUsage: MemoryUsage
    get() {
      checkNotClosed()

      memScoped {
        val jsMemoryUsage = alloc<JSMemoryUsage>()
        JS_ComputeMemoryUsage(runtime, jsMemoryUsage.ptr)
        return MemoryUsage(
          jsMemoryUsage.malloc_count,
          jsMemoryUsage.malloc_size,
          jsMemoryUsage.malloc_limit,
          jsMemoryUsage.memory_used_count,
          jsMemoryUsage.memory_used_size,
          jsMemoryUsage.atom_count,
          jsMemoryUsage.atom_size,
          jsMemoryUsage.str_count,
          jsMemoryUsage.str_size,
          jsMemoryUsage.obj_count,
          jsMemoryUsage.obj_size,
          jsMemoryUsage.prop_count,
          jsMemoryUsage.prop_size,
          jsMemoryUsage.shape_count,
          jsMemoryUsage.shape_size,
          jsMemoryUsage.js_func_count,
          jsMemoryUsage.js_func_size,
          jsMemoryUsage.js_func_code_size,
          jsMemoryUsage.js_func_pc2line_count,
          jsMemoryUsage.js_func_pc2line_size,
          jsMemoryUsage.c_func_count,
          jsMemoryUsage.array_count,
          jsMemoryUsage.fast_array_count,
          jsMemoryUsage.fast_array_elements,
          jsMemoryUsage.binary_object_count,
          jsMemoryUsage.binary_object_size,
        )
      }
    }

  /** Default is -1. Use -1 for no limit. */
  actual var memoryLimit: Long = -1L
    set(value) {
      checkNotClosed()

      field = value
      JS_SetMemoryLimit(runtime, value.convert())
    }

  /** Default is 256 KiB. Use -1 to disable automatic GC. */
  actual var gcThreshold: Long = -1L
    set(value) {
      checkNotClosed()

      field = value
      JS_SetGCThreshold(runtime, value.convert())
    }

  /** Default is 512 KiB. Use 0 to disable the maximum stack size check. */
  actual var maxStackSize: Long = -1L
    set(value) {
      checkNotClosed()

      field = value
      JS_SetMaxStackSize(runtime, value.convert())
    }

  actual fun evaluate(script: String, fileName: String): Any? {
    checkNotClosed()

    val evalValue = JS_Eval(context, script, script.length.convert(), fileName, 0)
    val result = evalValue.toKotlinInstanceOrNull()
    JS_FreeValue(context, evalValue)
    return result
  }

  actual fun compile(sourceCode: String, fileName: String): ByteArray {
    checkNotClosed()

    val compiled =
      JS_Eval(context, sourceCode, sourceCode.length.convert(), fileName, JS_EVAL_FLAG_COMPILE_ONLY)
    if (JS_IsException(compiled) != 0) {
      throwJsException()
    }
    val result = memScoped {
      val bufferLengthVar = alloc<size_tVar>()
      val buffer = JS_WriteObject(context, bufferLengthVar.ptr, compiled,
        JS_WRITE_OBJ_BYTECODE or JS_WRITE_OBJ_REFERENCE
      )
      val bufferLength = bufferLengthVar.value.toInt()

      val result = if (buffer != null && bufferLength > 0) {
        buffer.readBytes(bufferLength)
      } else {
        null
      }

      JS_FreeValue(context, compiled)
      js_free(context, buffer)

      result
    }
    return result ?: throwJsException()
  }

  actual fun execute(bytecode: ByteArray): Any? {
    checkNotClosed()

    @Suppress("UNCHECKED_CAST") // ByteVar and UByteVar have the same bit layout.
    val bytecodeRef = bytecode.refTo(0) as CValuesRef<UByteVar>
    val obj = JS_ReadObject(context, bytecodeRef, bytecode.size.convert(),
      JS_READ_OBJ_BYTECODE or JS_READ_OBJ_REFERENCE
    )
    if (JS_IsException(obj) != 0) {
      throwJsException()
    }
    if (JS_ResolveModule(context, obj) != 0) {
      throw QuickJsException("Failed to resolve JS module")
    }
    val value = JS_EvalFunction(context, obj)
    if (JS_IsException(value) != 0) {
      JS_FreeValue(context, value)
      throwJsException()
    }
    val result = value.toKotlinInstanceOrNull()
    JS_FreeValue(context, value)
    return result
  }

  internal actual fun initOutboundChannel(outboundChannel: CallChannel) {
    checkNotClosed()

    val globalThis = JS_GetGlobalObject(context)
    val propertyName = JS_NewAtom(context, outboundChannelName)
    try {
      if (JS_HasProperty(context, globalThis, propertyName) != 0) {
        throw IllegalStateException("A global object called $outboundChannelName already exists")
      }

      val outboundCallChannelClassId = memScoped {
        val id = alloc<JSClassIDVar>()
        JS_NewClassID(id.ptr)

        val classDef = alloc<JSClassDef>()
        classDef.class_name = "OutboundCallChannel".cstr.ptr
        JS_NewClass(runtime, id.value, classDef.ptr)

        id.value.toInt() // Why doesn't JS_NewObjectClass accept a UInt / JSClassID?
      }

      val jsOutboundCallChannel = JS_NewObjectClass(context, outboundCallChannelClassId)
      if (JS_IsException(jsOutboundCallChannel) != 0 ||
          JS_SetProperty(context, globalThis, propertyName, jsOutboundCallChannel) <= 0) {
        throwJsException()
      }

      val functionList = nativeHeap.allocArrayOf(
        JsServiceNamesFunction(staticCFunction(::outboundServiceNames)),
        JsInvokeFunction(staticCFunction(::outboundInvoke)),
        JsInvokeSuspendingFunction(staticCFunction(::outboundInvokeSuspending)),
        JsDisconnectFunction(staticCFunction(::outboundDisconnect)),
      )
      JS_SetPropertyFunctionList(context, jsOutboundCallChannel, functionList, 4)
    } finally {
      JS_FreeAtom(context, propertyName)
      JS_FreeValue(context, globalThis)
    }

    this.outboundChannel = outboundChannel
  }

  internal fun jsOutboundServiceNames(argc: Int): CValue<JSValue> {
    assert(argc == 0)
    val result = outboundChannel!!.serviceNamesArray()
    return result.toJsValue()
  }

  internal fun jsOutboundInvoke(argc: Int, argv: CArrayPointer<JSValue>): CValue<JSValue> {
    assert(argc == 3)
    val arg0 = JsValueArrayToInstanceRef(argv, 0).toKotlinInstanceOrNull() as String
    val arg1 = JsValueArrayToInstanceRef(argv, 1).toKotlinInstanceOrNull() as String
    val arg2 = JsValueArrayToInstanceRef(argv, 2).toKotlinInstanceOrNull() as Array<String>
    val result = outboundChannel!!.invoke(arg0, arg1, arg2)
    return result.toJsValue()
  }

  internal fun jsOutboundInvokeSuspending(argc: Int, argv: CArrayPointer<JSValue>): CValue<JSValue> {
    assert(argc == 4)
    val arg0 = JsValueArrayToInstanceRef(argv, 0).toKotlinInstanceOrNull() as String
    val arg1 = JsValueArrayToInstanceRef(argv, 1).toKotlinInstanceOrNull() as String
    val arg2 = JsValueArrayToInstanceRef(argv, 2).toKotlinInstanceOrNull() as Array<String>
    val arg3 = JsValueArrayToInstanceRef(argv, 3).toKotlinInstanceOrNull() as String
    outboundChannel!!.invokeSuspending(arg0, arg1, arg2, arg3)
    return JsUndefined()
  }

  internal fun jsOutboundDisconnect(argc: Int, argv: CArrayPointer<JSValue>): CValue<JSValue> {
    assert(argc == 1)
    val arg0 = JsValueArrayToInstanceRef(argv, 0).toKotlinInstanceOrNull() as String
    val result = outboundChannel!!.disconnect(arg0)
    return result.toJsValue()
  }

  internal actual fun getInboundChannel(): CallChannel {
    checkNotClosed()

    val globalThis = JS_GetGlobalObject(context)
    val inboundChannelAtom = JS_NewAtom(context, inboundChannelName)
    val hasProperty = JS_HasProperty(context, globalThis, inboundChannelAtom) != 0
    JS_FreeValue(context, globalThis)
    JS_FreeAtom(context, inboundChannelAtom)
    check(hasProperty) { "A global JavaScript object called $inboundChannelName was not found" }

    return InboundCallChannel(this)
  }

  internal actual fun gc() {
    JS_RunGC(runtime)
  }

  actual fun close() {
    if (!closed) {
      JS_FreeContext(context)
      JS_FreeRuntime(runtime)
      thisPtr.dispose()
      closed = true
    }
  }

  internal fun checkNotClosed() {
    check(!closed) { "QuickJs instance was closed" }
  }

  private fun throwJsException(): Nothing {
    val exceptionValue = JS_GetException(context)

    val messageValue = JS_GetPropertyStr(context, exceptionValue, "message")
    val stackValue = JS_GetPropertyStr(context, exceptionValue, "stack")

    val message = JS_ToCString(context,
      messageValue.takeUnless { JS_IsUndefined(messageValue) != 0 } ?: exceptionValue
    )?.toKStringFromUtf8() ?: ""
    JS_FreeValue(context, messageValue)

    val stack = JS_ToCString(context, stackValue)!!.toKStringFromUtf8()
    JS_FreeValue(context, stackValue)
    JS_FreeValue(context, exceptionValue)

    // TODO extract cause

    throw QuickJsException(message) // TODO add stack
  }

  internal fun CValue<JSValue>.toKotlinInstanceOrNull(): Any? {
    return when (JsValueGetNormTag(this)) {
      JS_TAG_EXCEPTION -> throwJsException()
      JS_TAG_STRING -> JS_ToCString(context, this)!!.toKStringFromUtf8()
      JS_TAG_BOOL -> JsValueGetBool(this) != 0
      JS_TAG_INT -> JsValueGetInt(this)
      JS_TAG_FLOAT64 -> JsValueGetFloat64(this)
      JS_TAG_NULL, JS_TAG_UNDEFINED -> null
      JS_TAG_OBJECT -> {
        if (JS_IsArray(context, this) != 0) {
          val lengthProperty = JS_GetPropertyStr(context, this, "length")
          val length = JsValueGetInt(lengthProperty)
          JS_FreeValue(context, lengthProperty)

          Array(length) {
            val element = JS_GetPropertyUint32(context, this, it.convert())
            val value = element.toKotlinInstanceOrNull()
            JS_FreeValue(context, element)
            value
          }
        } else {
          null
        }
      }
      else -> null
    }
  }

  internal fun Array<String>.toJsValue(): CValue<JSValue> {
    val array = JS_NewArray(context)
    forEachIndexed { index, string ->
      JS_SetPropertyUint32(context, array, index.convert(), JS_NewString(context, string))
    }
    return array
  }

  private fun Boolean.toJsValue(): CValue<JSValue> {
    return if (this) JsTrue() else JsFalse()
  }
}

internal fun jsInterruptHandlerGlobal(runtime: CPointer<JSRuntime>?, opaque: COpaquePointer?): Int {
  val quickJs = opaque!!.asStableRef<QuickJs>().get()
  return quickJs.jsInterruptHandler(runtime)
}

@Suppress("UNUSED_PARAMETER") // API shape mandated by QuickJs.
internal fun outboundServiceNames(
  context: CPointer<JSContext>,
  thisVal: CValue<JSValue>,
  argc: Int,
  argv: CArrayPointer<JSValue>,
): CValue<JSValue> {
  val quickJs = JS_GetRuntimeOpaque(JS_GetRuntime(context))!!.asStableRef<QuickJs>().get()
  return try {
    quickJs.jsOutboundServiceNames(argc)
  } catch (t: Throwable) {
    t.printStackTrace() // TODO throw to JS return null
    throw t
  }
}

@Suppress("UNUSED_PARAMETER") // API shape mandated by QuickJs.
internal fun outboundInvoke(
  context: CPointer<JSContext>,
  thisVal: CValue<JSValue>,
  argc: Int,
  argv: CArrayPointer<JSValue>,
): CValue<JSValue> {
  val quickJs = JS_GetRuntimeOpaque(JS_GetRuntime(context))!!.asStableRef<QuickJs>().get()
  return try {
    quickJs.jsOutboundInvoke(argc, argv)
  } catch (t: Throwable) {
    t.printStackTrace() // TODO throw to JS return null
    throw t
  }
}

@Suppress("UNUSED_PARAMETER") // API shape mandated by QuickJs.
internal fun outboundInvokeSuspending(
  context: CPointer<JSContext>,
  thisVal: CValue<JSValue>,
  argc: Int,
  argv: CArrayPointer<JSValue>,
): CValue<JSValue> {
  val quickJs = JS_GetRuntimeOpaque(JS_GetRuntime(context))!!.asStableRef<QuickJs>().get()
  return try {
    quickJs.jsOutboundInvokeSuspending(argc, argv)
  } catch (t: Throwable) {
    t.printStackTrace() // TODO throw to JS return null
    throw t
  }
}

@Suppress("UNUSED_PARAMETER") // API shape mandated by QuickJs.
internal fun outboundDisconnect(
  context: CPointer<JSContext>,
  thisVal: CValue<JSValue>,
  argc: Int,
  argv: CArrayPointer<JSValue>,
): CValue<JSValue> {
  val quickJs = JS_GetRuntimeOpaque(JS_GetRuntime(context))!!.asStableRef<QuickJs>().get()
  return try {
    quickJs.jsOutboundDisconnect(argc, argv)
  } catch (t: Throwable) {
    t.printStackTrace() // TODO throw to JS return null
    throw t
  }
}
