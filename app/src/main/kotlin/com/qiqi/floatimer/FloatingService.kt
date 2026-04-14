package com.qiqi.floatimer

import android.app.*
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

class FloatingService : Service() {

	// ── 生命周期 ───────────────────────────────────────────────
	private val lifecycleOwner = FloatingLifecycleOwner()

	// ── WindowManager ──────────────────────────────────────────
	private lateinit var windowManager: WindowManager

	/**
	 * 每个悬浮窗用一个 Entry 存 view 和它的 params。
	 * 移动/穿透模式切换都需要拿到 params 去 updateViewLayout。
	 */
	private data class FloatingEntry(
		val view: ComposeView,
		val params: WindowManager.LayoutParams
	)

	private val timerEntries = mutableMapOf<Int, FloatingEntry>()
	private var netTimeEntry: FloatingEntry? = null

	// 穿透模式（全局开关）
	var noTouchMode: Boolean = false
		set(value) {
			field = value
			applyNoTouchMode()
		}

	// ── Companion ──────────────────────────────────────────────
	companion object {
		const val CHANNEL_ID	  = "float_timer_ch"
		const val NOTIFICATION_ID = 101

		// Intent Actions（主 App 通过这些 action 控制 Service）
		const val ACTION_ADD_TIMER	= "ACTION_ADD_TIMER"
		const val ACTION_REMOVE_TIMER = "ACTION_REMOVE_TIMER"
		const val ACTION_RECALL_ALL	= "ACTION_RECALL_ALL"
		const val ACTION_SET_NO_TOUCH = "ACTION_SET_NO_TOUCH"

		const val EXTRA_TIMER_ID  = "timer_id"
		const val EXTRA_NO_TOUCH  = "no_touch"
	}

	// ── 生命周期回调 ───────────────────────────────────────────

	override fun onCreate() {
		super.onCreate()
		lifecycleOwner.start()
		windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
		createNotificationChannel()
		startForeground(NOTIFICATION_ID, buildNotification())
	}

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		when (intent?.action) {
			ACTION_ADD_TIMER -> {
				// id = 当前数量（简单自增，removeTimerCapsule 会处理顺延）
				addTimerCapsule(TimerConfig(id = timerEntries.size))
			}
			ACTION_REMOVE_TIMER -> {
				val id = intent.getIntExtra(EXTRA_TIMER_ID, -1)
				if (id >= 0) removeTimerCapsule(id)
			}
			ACTION_RECALL_ALL -> recallAll()
			ACTION_SET_NO_TOUCH -> {
				noTouchMode = intent.getBooleanExtra(EXTRA_NO_TOUCH, false)
			}
		}
		return START_STICKY  // 被系统杀掉后自动重启
	}

	override fun onDestroy() {
		lifecycleOwner.stop()
		timerEntries.values.forEach { windowManager.removeView(it.view) }
		netTimeEntry?.let { windowManager.removeView(it.view) }
		super.onDestroy()
	}

	override fun onBind(intent: Intent?): IBinder? = null

	// ── 悬浮窗操作 ─────────────────────────────────────────────

	private fun addTimerCapsule(config: TimerConfig) {
		val screen = resources.displayMetrics
		val initX  = screen.widthPixels  / 2 - 120  // 默认屏幕中心（近似）
		val initY  = screen.heightPixels / 2 - 40

		val params = buildParams(initX, initY)
		val view	= buildComposeView {
			// TODO 下一步替换成 TimerCapsule(config, ...)
		}

		windowManager.addView(view, params)
		timerEntries[config.id] = FloatingEntry(view, params)
	}

	private fun removeTimerCapsule(id: Int) {
		timerEntries.remove(id)?.let { windowManager.removeView(it.view) }

		// 删除后顺延编号：0,1,2 删掉1 → 变成 0,1
		val remaining = timerEntries.values.toList()
		timerEntries.clear()
		remaining.forEachIndexed { index, entry -> timerEntries[index] = entry }
	}

	/** 把所有悬浮窗归位到屏幕中心 */
	fun recallAll() {
		val screen = resources.displayMetrics
		val cx = screen.widthPixels  / 2 - 120
		val cy = screen.heightPixels / 2 - 40

		timerEntries.values.forEach { entry ->
			entry.params.x = cx
			entry.params.y = cy
			windowManager.updateViewLayout(entry.view, entry.params)
		}
		netTimeEntry?.let { entry ->
			entry.params.x = cx
			entry.params.y = cy + 120  // 网络时间稍微错开，避免重叠
			windowManager.updateViewLayout(entry.view, entry.params)
		}
	}

	/** 移动某个计时器（手势回调用这个） */
	fun moveTimer(id: Int, dx: Int, dy: Int) {
		timerEntries[id]?.let { entry ->
			entry.params.x += dx
			entry.params.y += dy
			windowManager.updateViewLayout(entry.view, entry.params)
		}
	}

	/** 移动网络时间胶囊 */
	fun moveNetTime(dx: Int, dy: Int) {
		netTimeEntry?.let { entry ->
			entry.params.x += dx
			entry.params.y += dy
			windowManager.updateViewLayout(entry.view, entry.params)
		}
	}

	// ── 内部工具 ───────────────────────────────────────────────

	private fun applyNoTouchMode() {
		fun update(entry: FloatingEntry) {
			if (noTouchMode) {
				entry.params.flags =
					entry.params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
			} else {
				entry.params.flags =
					entry.params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
			}
			windowManager.updateViewLayout(entry.view, entry.params)
		}
		timerEntries.values.forEach(::update)
		netTimeEntry?.let(::update)
	}

	private fun buildParams(x: Int, y: Int): WindowManager.LayoutParams {
		val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
			WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
		else
			@Suppress("DEPRECATION")
			WindowManager.LayoutParams.TYPE_PHONE

		return WindowManager.LayoutParams(
			WindowManager.LayoutParams.WRAP_CONTENT,
			WindowManager.LayoutParams.WRAP_CONTENT,
			x, y,
			type,
			// FLAG_NOT_FOCUSABLE：不抢键盘焦点
			// FLAG_LAYOUT_NO_LIMITS：允许超出屏幕边缘 / 进入刘海区
			WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
			WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
			PixelFormat.TRANSLUCENT
		).apply {
			gravity = Gravity.TOP or Gravity.START
		}
	}

	private fun buildComposeView(content: @Composable () -> Unit): ComposeView {
		return ComposeView(this).apply {
			setViewTreeLifecycleOwner(lifecycleOwner)
			setViewTreeViewModelStoreOwner(lifecycleOwner)
			setViewTreeSavedStateRegistryOwner(lifecycleOwner)
			setContent { content() }
		}
	}

	// ── 通知 ───────────────────────────────────────────────────

	private fun createNotificationChannel() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			val channel = NotificationChannel(
				CHANNEL_ID,
				"悬浮计时器",
				NotificationManager.IMPORTANCE_LOW  // 低重要性，静默，不响铃
			).apply { description = "保持悬浮窗在后台运行，请保留" }
			getSystemService(NotificationManager::class.java)
				.createNotificationChannel(channel)
		}
	}

	private fun buildNotification(): Notification {
		return NotificationCompat.Builder(this, CHANNEL_ID)
			.setContentTitle("悬浮计时器运行中")
			.setContentText("点击打开设置")
			.setSmallIcon(android.R.drawable.ic_dialog_info)  // 后续换成自己的图标
			.setPriority(NotificationCompat.PRIORITY_LOW)
			.build()
	}
}