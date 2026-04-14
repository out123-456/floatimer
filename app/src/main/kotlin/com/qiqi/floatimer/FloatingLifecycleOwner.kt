package com.qiqi.floatimer  // ← 改成你项目实际的包名

import androidx.lifecycle.*
import androidx.savedstate.*

/**
 * 让 ComposeView 能在 Service（而不是 Activity）里正常运行。
 * Compose 需要 LifecycleOwner 才能响应 recomposition 和资源释放。
 */
class FloatingLifecycleOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

	private val lifecycleRegistry	= LifecycleRegistry(this)
	private val store				= ViewModelStore()
	private val savedStateController = SavedStateRegistryController.create(this)

	override val lifecycle: Lifecycle
		get() = lifecycleRegistry

	override val viewModelStore: ViewModelStore
		get() = store

	override val savedStateRegistry: SavedStateRegistry
		get() = savedStateController.savedStateRegistry

	/** Service.onCreate() 时调用 */
	fun start() {
		savedStateController.performRestore(null)
		lifecycleRegistry.currentState = Lifecycle.State.RESUMED
	}

	/** Service.onDestroy() 时调用 */
	fun stop() {
		lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
		store.clear()
	}
}