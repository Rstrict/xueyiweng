package com.xueyiweng.app.ui

import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.xueyiweng.app.R
import com.xueyiweng.app.XueYiWenApp
import com.xueyiweng.app.databinding.ActivityMainBinding
import com.xueyiweng.app.ui.archive.ArchiveFragment
import com.xueyiweng.app.ui.home.HomeFragment
import com.xueyiweng.app.ui.review.ReviewFragment
import com.xueyiweng.app.ui.session.SessionFragment
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val vm: MainViewModel by viewModels()

    private var currentTheme = ""

    companion object {
        const val TAG_SESSION = "session"
        const val TAG_REVIEW = "review"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyNightMode(XueYiWenApp.from(this).theme.value)
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupTabs()
        observeTheme()
        setupImeAwareTabBar()

        if (savedInstanceState == null) showHome()

        handleIntentAction(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleIntentAction(intent)
    }

    private fun handleIntentAction(intent: android.content.Intent?) {
        when (intent?.getStringExtra("action_from_notif")) {
            "pause" -> vm.pauseSession()
            else -> Unit
        }
        // adb shell am start ... --es run_selfcheck 1
        if (intent?.getStringExtra("run_selfcheck") == "1") {
            lifecycleScope.launch(Dispatchers.IO) {
                val report = SelfCheck.run(this@MainActivity)
                android.util.Log.i("XYW_SELFCHECK", report)
            }
        }
        // 状态机驱动器（端到端验证用）——必须放后台线程，避免 runBlocking 造成 ANR
        intent?.getStringExtra("drive")?.let { cmd ->
            val extras = mapOf(
                "q" to intent.getStringExtra("q"),
                "src" to intent.getStringExtra("src"),
                "to" to intent.getStringExtra("to"),
                "a" to intent.getStringExtra("a"),
                "s" to intent.getStringExtra("s"),
                "t" to intent.getStringExtra("t"),
                "note" to intent.getStringExtra("note"),
                "m" to intent.getStringExtra("m"),
            )
            lifecycleScope.launch(Dispatchers.IO) {
                val report = SessionDriver.run(this@MainActivity, cmd, extras)
                android.util.Log.i("XYW_DRIVE", report)
                vm.repository.refresh()
            }
        }
    }

    private fun applyNightMode(theme: String) {
        AppCompatDelegate.setDefaultNightMode(
            when (theme) {
                "light" -> AppCompatDelegate.MODE_NIGHT_NO
                "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    private fun observeTheme() {
        lifecycleScope.launch {
            XueYiWenApp.from(this@MainActivity).theme.collectLatest { t ->
                if (currentTheme.isNotEmpty() && currentTheme != t) {
                    applyNightMode(t)
                    recreate()
                }
                currentTheme = t
            }
        }
    }

    /** 键盘弹起时收起底部导航，避免被顶到键盘上方 */
    private fun setupImeAwareTabBar() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            binding.tabBar.visibility = when {
                binding.fullscreen.visibility == android.view.View.VISIBLE -> android.view.View.GONE
                imeVisible -> android.view.View.GONE
                else -> android.view.View.VISIBLE
            }
            insets
        }
    }

    private fun setupTabs() {
        binding.tabToday.setOnClickListener { showHome() }
        binding.tabArchive.setOnClickListener { showArchive() }
    }

    fun showHome() {
        closeFullscreen()
        binding.tabToday.isSelected = true
        binding.tabArchive.isSelected = false
        binding.tabBar.visibility = android.view.View.VISIBLE
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, HomeFragment())
            .commit()
    }

    fun showArchive() {
        closeFullscreen()
        binding.tabToday.isSelected = false
        binding.tabArchive.isSelected = true
        binding.tabBar.visibility = android.view.View.VISIBLE
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, ArchiveFragment())
            .commit()
    }

    /* ---------------- 全屏沉浸层 ---------------- */

    /** 学习中：全屏层显示，底栏隐藏 */
    fun openSession() {
        showFullscreenLayer()
        try {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fullscreen, SessionFragment.newInstance(), TAG_SESSION)
                .commitNow()
            android.util.Log.i("XYW", "openSession: fragment committed")
        } catch (e: Exception) {
            android.util.Log.e("XYW", "openSession failed", e)
        }
    }

    /** 复习：全屏层显示，底栏隐藏 */
    fun openReview() {
        showFullscreenLayer()
        try {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fullscreen, ReviewFragment.newInstance(), TAG_REVIEW)
                .commitNow()
            android.util.Log.i("XYW", "openReview: fragment committed")
        } catch (e: Exception) {
            android.util.Log.e("XYW", "openReview failed", e)
        }
    }

    /**
     * 关键顺序：必须先把 fullscreen 变可见、让它的宽高确定下来，
     * 再提交 Fragment 事务。否则容器尺寸为 0 时 Fragment 会被判定为"无容器可用"，
     * 视图可能被挂到错误的位置，表现为按钮点不到。
     */
    private fun showFullscreenLayer() {
        binding.fullscreen.visibility = android.view.View.VISIBLE
        binding.tabBar.visibility = android.view.View.GONE
        binding.mainLayer.visibility = android.view.View.GONE
    }

    /** 关闭全屏层并回到今天 */
    fun closeSession() {
        closeFullscreen()
        binding.tabBar.visibility = android.view.View.VISIBLE
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, HomeFragment())
            .commit()
    }

    private fun closeFullscreen() {
        val fm = supportFragmentManager
        if (binding.fullscreen.visibility == android.view.View.VISIBLE) {
            val frag = fm.findFragmentById(R.id.fullscreen)
            if (frag != null) fm.beginTransaction().remove(frag).commitNowAllowingStateLoss()
            binding.fullscreen.visibility = android.view.View.GONE
        }
        // 主层必须恢复可见，否则关闭全屏后是空白
        binding.mainLayer.visibility = android.view.View.VISIBLE
    }

    fun isSessionShowing(): Boolean = binding.fullscreen.visibility == android.view.View.VISIBLE
}
