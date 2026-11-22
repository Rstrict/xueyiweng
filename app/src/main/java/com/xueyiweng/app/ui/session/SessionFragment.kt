package com.xueyiweng.app.ui.session

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.xueyiweng.app.R
import com.xueyiweng.app.XueYiWenApp
import com.xueyiweng.app.databinding.FragmentSessionBinding
import com.xueyiweng.app.logic.BrainDump
import com.xueyiweng.app.logic.Dates
import com.xueyiweng.app.logic.Mastery
import com.xueyiweng.app.logic.SessionSnapshot
import com.xueyiweng.app.logic.Stage
import com.xueyiweng.app.logic.Timer
import com.xueyiweng.app.service.Chimes
import com.xueyiweng.app.service.Haptics
import com.xueyiweng.app.service.StageAlarms
import com.xueyiweng.app.ui.MainActivity
import com.xueyiweng.app.ui.MainViewModel
import kotlinx.coroutines.launch

class SessionFragment : Fragment() {

    private var _binding: FragmentSessionBinding? = null
    private val binding get() = _binding ?: error("[binding] accessed after onDestroyView: " + javaClass.simpleName)

    private val vm: MainViewModel by activityViewModels()

    private val handler = Handler(Looper.getMainLooper())
    private var ticking = false
    private var lastRenderedStage: String? = null
    private var chimes: Chimes? = null

    /** 当前注入的阶段视图内的控件 */
    private var stageRoot: View? = null
    private var etRecall: EditText? = null
    private var etStuck: EditText? = null
    private var etFinalNote: EditText? = null

    private val tick = object : Runnable {
        override fun run() {
            updateTimer()
            if (ticking) handler.postDelayed(this, 250)
        }
    }

    companion object {
        fun newInstance() = SessionFragment()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSessionBinding.inflate(inflater, container, false)
        return binding.root
    }

    /** 拦截返回键：进行中的一轮不允许无声退出 */
    override fun onResume() {
        super.onResume()
        // 只注册一次：onResume 会多次触发，重复注册会叠加回调导致一次返回弹多个框
        if (backCallback == null) {
            val cb = object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = confirmLeave()
            }
            requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, cb)
            backCallback = cb
        }
        val s = vm.session.value
        if (s != null) {
            updateTimer()
            if (lastRenderedStage != null && lastRenderedStage != s.currentStage) {
                renderStage(s)
            }
            startTicking()
        }
    }

    private var backCallback: androidx.activity.OnBackPressedCallback? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        chimes = Chimes(requireContext())

        // 会话页是全屏沉浸层，需显式声明可访问，否则无障碍/自动化工具遍历不到子控件
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        binding.root.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES

        // 键盘由 adjustResize 自动处理（窗口整体收缩，actionBar 随之上移），
        // 这里不要再手动加 padding，否则会把底部动作区推出可点区域。

        binding.timerBox.setOnClickListener { toggleTimerDigits() }
        binding.btnPause.setOnClickListener { showPauseSheet() }
        binding.btnDump.setOnClickListener { showBrainDumpSheet() }
        binding.btnPrimary.setOnClickListener { onPrimaryAction() }

        val s = vm.session.value
        if (s == null) {
            goHome()
            return
        }
        renderStage(s)
        startTicking()

        // 按全局设置初始化计时显示模式（默认显示数字）
        viewLifecycleOwner.lifecycleScope.launch {
            val cfg = XueYiWenApp.from(requireContext()).settings.current()
            digitsVisible = !cfg.hideTimerDigits
            updateTimer()
        }
    }

    /**
     * 返回键不直接丢弃进行中的一轮 —— 弹确认，
     * 避免误触把刚写的盲答弄丢（断点恢复才是默认出路）。
     */
    private fun confirmLeave() {
        val s = vm.session.value
        if (s == null) { goHome(); return }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("先暂停一下？")
            .setMessage("这一轮会保存成断点，首页可以随时继续。也可以直接放弃。")
            .setNeutralButton("继续学习", null)
            .setNegativeButton("放弃本轮") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    vm.saveSession(null)
                    com.xueyiweng.app.service.StageTimerService.stop(requireContext())
                    goHome()
                }
            }
            .setPositiveButton("暂停并返回") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val paused = Timer.pause(s, System.currentTimeMillis())
                    vm.saveSession(paused)
                    goHome()
                }
            }
            .show()
    }

    /* ---------------- 阶段渲染 ---------------- */

    private fun renderStage(s: SessionSnapshot) {
        val stage = s.stage
        binding.stageBadge.text = "阶段 ${stage.number}/4 · ${stage.label}"
        binding.stageBadge.background = ContextCompat.getDrawable(
            requireContext(),
            if (s.isPaused) R.drawable.bg_badge_paused else R.drawable.bg_badge,
        )
        binding.stageBadge.setTextColor(
            ContextCompat.getColor(requireContext(), if (s.isPaused) R.color.amber else R.color.accent)
        )

        renderProgressDots(stage)

        // 清空并注入对应阶段的视图
        binding.stageContent.removeAllViews()
        stageRoot = null
        etRecall = null
        etStuck = null
        etFinalNote = null

        when (stage) {
            Stage.STUDY -> inflateStudy(s)
            Stage.RECALL_1 -> inflateRecall1(s)
            Stage.VERIFY -> inflateVerify(s)
            Stage.RECALL_2 -> inflateRecall2(s)
        }

        updatePrimaryButton(s)
        updatePauseUi(s)
        // 重建视图后立刻刷新一次计时，避免数字停留在隐藏态
        updateTimer()
        lastRenderedStage = stage.id
    }

    private fun renderProgressDots(stage: Stage) {
        binding.progressLine.removeAllViews()
        val ctx = requireContext()
        for (i in 0 until 4) {
            val dot = View(ctx)
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            lp.marginEnd = (4 * resources.displayMetrics.density).toInt()
            dot.layoutParams = lp
            val color = when {
                i < stage.index -> R.color.accent
                i == stage.index -> R.color.ink3
                else -> R.color.line
            }
            dot.setBackgroundColor(ContextCompat.getColor(ctx, color))
            binding.progressLine.addView(dot)
        }
    }

    private fun inflateStudy(s: SessionSnapshot) {
        val v = layoutInflater.inflate(R.layout.stage_study, binding.stageContent, false)
        v.findViewById<TextView>(R.id.questionText).text = s.questionText
        val src = v.findViewById<TextView>(R.id.sourceText)
        if (s.sourceLocation.isBlank()) {
            src.isVisible = false
        } else {
            src.text = "📍 ${s.sourceLocation}"
        }
        // 暂停备注：恢复时高亮，10 秒接上思路
        val note = v.findViewById<TextView>(R.id.pauseNoteCard)
        if (s.pauseContextNote.isNotBlank()) {
            note.isVisible = true
            note.text = "上次停在这里：${s.pauseContextNote}"
        }
        binding.stageContent.addView(v)
        stageRoot = v
    }

    private fun inflateRecall1(s: SessionSnapshot) {
        val v = layoutInflater.inflate(R.layout.stage_recall1, binding.stageContent, false)
        v.findViewById<TextView>(R.id.questionText).text = s.questionText

        val stuckMode = s.firstAttemptType == "stuck"
        v.findViewById<View>(R.id.textBlock).isVisible = !stuckMode
        v.findViewById<View>(R.id.stuckBlock).isVisible = stuckMode

        val recall = v.findViewById<EditText>(R.id.etRecall)
        recall.setText(s.firstAttemptDraft)
        recall.setSelection(recall.text.length)
        recall.addTextChangedListener(simpleWatcher { text ->
            vm.updateSession {
                it.copy(
                    firstAttemptDraft = text,
                    firstAttemptType = if (text.isNotBlank()) "text" else it.firstAttemptType,
                )
            }
            updatePrimaryButton(vm.session.value ?: return@simpleWatcher)
        })
        etRecall = recall

        val stuck = v.findViewById<EditText>(R.id.etStuck)
        stuck.setText(s.stuckPointDraft)
        stuck.addTextChangedListener(simpleWatcher { text ->
            vm.updateSession { it.copy(stuckPointDraft = text) }
        })
        etStuck = stuck

        binding.stageContent.addView(v)
        stageRoot = v

        // 自动聚焦
        handler.postDelayed({
            if (isAdded) {
                (if (stuckMode) stuck else recall).requestFocus()
                showKeyboard(if (stuckMode) stuck else recall)
            }
        }, 180)
    }

    private fun inflateVerify(s: SessionSnapshot) {
        // 核对阶段：问题 + 折叠卡片展示盲答，方便边看边对照
        val root = layoutInflater.inflate(R.layout.stage_study, binding.stageContent, false)
        root.findViewById<TextView>(R.id.questionText).text = s.questionText
        val src = root.findViewById<TextView>(R.id.sourceText)
        if (s.sourceLocation.isBlank()) src.isVisible = false
        else src.text = "📍 ${s.sourceLocation}"

        val note = root.findViewById<TextView>(R.id.pauseNoteCard)
        note.isVisible = false

        // 在问题下方插入折叠卡片
        val container = root as LinearLayout
        val foldCard = createAttemptFoldCard(s)
        val insertAt = container.indexOfChild(root.findViewById(R.id.sourceText)) + 1
        container.addView(foldCard, insertAt)

        binding.stageContent.addView(root)
        stageRoot = root
    }

    private fun createAttemptFoldCard(s: SessionSnapshot): View {
        val ctx = requireContext()
        val card = MaterialCardView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = (14 * resources.displayMetrics.density).toInt()
            }
            radius = resources.getDimension(R.dimen.radius_sm)
            setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.card2))
            strokeWidth = resources.getDimensionPixelSize(R.dimen.stroke)
            setStrokeColor(ContextCompat.getColor(ctx, R.color.line))
        }
        val inner = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 34, 40, 34)
        }

        val header = TextView(ctx).apply {
            text = "▾  刚才的盲答 / 卡点"
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(ctx, R.color.ink2))
        }
        inner.addView(header)

        val label1 = TextView(ctx).apply {
            text = "首次作答"
            textSize = 11.5f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(ctx, R.color.ink3))
            letterSpacing = 0.08f
            setPadding(0, (12 * resources.displayMetrics.density).toInt(), 0, 0)
        }
        inner.addView(label1)

        val body1 = TextView(ctx).apply {
            text = s.firstAttemptDraft.ifBlank {
                if (s.firstAttemptType == "paper_oral") "（已在纸上 / 口头作答）" else "（未填写）"
            }
            textSize = 14.5f
            setTextColor(ContextCompat.getColor(ctx, R.color.ink))
            setLineSpacing(0f, 1.6f)
            setPadding(0, (5 * resources.displayMetrics.density).toInt(), 0, 0)
        }
        inner.addView(body1)

        if (s.stuckPointDraft.isNotBlank()) {
            val label2 = TextView(ctx).apply {
                text = "卡在哪里"
                textSize = 11.5f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(ContextCompat.getColor(ctx, R.color.amber))
                letterSpacing = 0.08f
                setPadding(0, (12 * resources.displayMetrics.density).toInt(), 0, 0)
            }
            inner.addView(label2)
            val body2 = TextView(ctx).apply {
                text = s.stuckPointDraft
                textSize = 14.5f
                setTextColor(ContextCompat.getColor(ctx, R.color.ink))
                setLineSpacing(0f, 1.6f)
                setPadding(0, (5 * resources.displayMetrics.density).toInt(), 0, 0)
            }
            inner.addView(body2)
        }

        card.addView(inner)
        return card
    }

    private fun inflateRecall2(s: SessionSnapshot) {
        val v = layoutInflater.inflate(R.layout.stage_recall2, binding.stageContent, false)
        v.findViewById<TextView>(R.id.questionText).text = s.questionText

        v.findViewById<TextView>(R.id.firstAttemptText).text =
            s.firstAttemptDraft.ifBlank {
                if (s.firstAttemptType == "paper_oral") "（已在纸上 / 口头作答）" else "（未填写）"
            }

        if (s.stuckPointDraft.isNotBlank()) {
            v.findViewById<TextView>(R.id.stuckLabel).isVisible = true
            v.findViewById<TextView>(R.id.stuckText).isVisible = true
            v.findViewById<TextView>(R.id.stuckText).text = s.stuckPointDraft
        }

        // 折叠开关
        val header = v.findViewById<TextView>(R.id.foldHeader)
        val body = v.findViewById<View>(R.id.foldBody)
        header.setOnClickListener {
            body.isVisible = !body.isVisible
            header.text = if (body.isVisible) "▾  原问题与我刚才的作答" else "▸  原问题与我刚才的作答"
        }

        val note = v.findViewById<EditText>(R.id.etFinalNote)
        note.setText(s.finalNoteDraft)
        note.addTextChangedListener(simpleWatcher { text ->
            vm.updateSession { it.copy(finalNoteDraft = text) }
        })
        etFinalNote = note

        binding.stageContent.addView(v)
        stageRoot = v
    }

    /* ---------------- 计时与到点 ---------------- */

    private fun startTicking() {
        if (ticking) return
        ticking = true
        handler.post(tick)
    }

    private fun stopTicking() {
        ticking = false
        handler.removeCallbacks(tick)
    }

    private fun updateTimer() {
        val s = vm.session.value ?: return
        val now = System.currentTimeMillis()
        val remain = Timer.remainingSec(s, now)
        val progress = Timer.progress(s, now)
        val overtime = remain <= 0

        binding.ring.progress = (progress * 1000).toInt()

        val digitsShow = digitsVisible
        if (digitsShow) {
            binding.timerText.isVisible = true
            binding.timerText.text = when {
                s.isPaused -> "已暂停"
                overtime -> "+${Dates.clock(-remain)}"
                else -> Dates.clock(remain)
            }
            binding.timerText.setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    if (overtime && !s.isPaused) R.color.amber else R.color.ink2
                )
            )
        } else {
            binding.timerText.isVisible = false
        }

        // 到点只触发一次
        if (overtime && !s.isPaused && !s.overtimeNotified) {
            onOvertime(s)
        }
    }

    /**
     * 计时显示模式：
     * - 进入会话时按设置初始化（settings.hideTimerDigits）
     * - 点计时区只在本次会话内临时切换
     * - renderStage 重建视图时保持当前选择，不会莫名消失
     */
    private var digitsVisible: Boolean = true

    private fun settingsHideDigits(): Boolean = false

    private fun toggleTimerDigits() {
        digitsVisible = !digitsVisible
        updateTimer()
        toast(if (digitsVisible) "显示剩余时间" else "只留圆环，减少时间压迫感")
    }

    private fun onOvertime(s: SessionSnapshot) {
        vm.updateSession { it.copy(overtimeNotified = true) }
        chimes?.playChime()
        Haptics.double(requireContext())

        val next = Stage.next(s.currentStage)
        val msg = when (s.stage) {
            Stage.STUDY -> "准备好了，点一下进入「第一次盲答」"
            Stage.RECALL_1 -> "去核对资料，找出盲区"
            Stage.VERIFY -> "收起资料，再完整答一遍"
            Stage.RECALL_2 -> "可以收尾选掌握度了"
        }
        toast("${s.stage.label}时间到 · $msg")

        // 同时挂一个精确闹钟兜底（即使 App 被杀）
        if (next != null) {
            StageAlarms.schedule(
                requireContext(),
                System.currentTimeMillis() + 1000,
                s.stage,
                msg,
            )
        }
    }

    /* ---------------- 动作 ---------------- */

    private fun updatePrimaryButton(s: SessionSnapshot) {
        val btn = binding.btnPrimary
        when (s.stage) {
            Stage.STUDY -> {
                btn.text = getString(R.string.btn_ready_to_test)
                btn.isEnabled = true
            }
            Stage.RECALL_1 -> {
                val has = s.firstAttemptDraft.isNotBlank() || !s.firstAttemptType.isNullOrBlank()
                btn.text = getString(R.string.btn_wrote_it)
                btn.isEnabled = has
            }
            Stage.VERIFY -> {
                btn.text = getString(R.string.btn_collapse_retry)
                btn.isEnabled = true
            }
            Stage.RECALL_2 -> {
                btn.text = getString(R.string.btn_finish_round)
                btn.isEnabled = true
            }
        }
    }

    private fun updatePauseUi(s: SessionSnapshot) {
        binding.btnPause.text = if (s.isPaused) getString(R.string.btn_continue_timer)
        else getString(R.string.btn_pause_leave)
    }

    private fun onPrimaryAction() {
        val s = vm.session.value ?: run {
            android.util.Log.w("XYW", "onPrimaryAction: session is null")
            return
        }
        android.util.Log.i("XYW", "onPrimaryAction: stage=${s.currentStage} -> advancing")
        hideKeyboard()

        when (s.stage) {
            Stage.STUDY -> advanceTo(Stage.RECALL_1)
            Stage.RECALL_1 -> advanceTo(Stage.VERIFY)
            Stage.VERIFY -> advanceTo(Stage.RECALL_2)
            Stage.RECALL_2 -> showMasterySheet()
        }
    }

    private fun advanceTo(stage: Stage) {
        val s = vm.session.value ?: return
        // 推进阶段前先收键盘，避免下一步的按钮被输入法窗口压住
        hideKeyboard()
        viewLifecycleOwner.lifecycleScope.launch {
            val settings = XueYiWenApp.from(requireContext()).settings.current()
            val next = Timer.gotoStage(s, stage, settings.stageSeconds, System.currentTimeMillis())
            vm.saveSession(next)
            renderStage(next)
            // 新阶段重新挂闹钟：即使锁屏也会准点提醒
            StageAlarms.schedule(
                requireContext(),
                System.currentTimeMillis() + next.stageTargetDurationSec * 1000L,
                stage,
                "准备好了就进入下一步",
            )
        }
    }

    private fun goHome() {
        (activity as? MainActivity)?.closeSession()
    }

    /* ---------------- 弹窗 ---------------- */

    private fun showPauseSheet() {
        val s = vm.session.value ?: return

        if (s.isPaused) {
            // 恢复
            viewLifecycleOwner.lifecycleScope.launch {
                val resumed = Timer.resume(s, System.currentTimeMillis())
                vm.saveSession(resumed)
                renderStage(resumed)
                val tip = if (resumed.pauseContextNote.isNotBlank())
                    "已继续 · ${resumed.pauseContextNote}" else "已继续"
                toast(tip)
            }
            return
        }

        val ctx = requireContext()
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }
        val input = EditText(ctx).apply {
            hint = "如：看到独立性适用条件"
            setText(s.pauseContextNote)
            setSingleLine()
            setTextSize(16f)
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_input)
            setPadding(34, 30, 34, 30)
        }
        layout.addView(input)

        // 快捷标签
        val chips = com.google.android.material.chip.ChipGroup(ctx).apply {
            setPadding(0, 24, 0, 0)
        }
        for (t in listOf("刚看完例题", "推导到一半", "正在记关键词")) {
            chips.addView(com.google.android.material.chip.Chip(ctx).apply {
                text = t
                isCheckable = false
                setOnClickListener { input.setText(t); input.setSelection(t.length) }
            })
        }
        layout.addView(chips)

        MaterialAlertDialogBuilder(ctx)
            .setTitle("暂停离开")
            .setMessage("计时会冻结。需要记一句停在哪吗？（选填）")
            .setView(layout)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton("暂停，记下") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val paused = Timer.pause(s, System.currentTimeMillis())
                        .copy(pauseContextNote = input.text.toString().trim())
                    vm.saveSession(paused)
                    renderStage(paused)
                    Haptics.light(requireContext())
                    toast("已暂停 · 计时已冻结")
                }
            }
            .show()
    }

    private fun showBrainDumpSheet() {
        val ctx = requireContext()
        val input = EditText(ctx).apply {
            hint = "如：回微信 / 取快递"
            setSingleLine()
            setTextSize(16f)
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_input)
            setPadding(34, 30, 34, 30)
        }
        val wrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 20, 48, 8)
        }
        wrap.addView(input)

        MaterialAlertDialogBuilder(ctx)
            .setTitle("记个杂念")
            .setMessage("想到什么就先收进来，本轮结束后再统一处理。")
            .setView(wrap)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton("暂存到本轮结束") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isEmpty()) return@setPositiveButton
                vm.updateSession { cur ->
                    cur.copy(brainDumps = cur.brainDumps + BrainDump(text, System.currentTimeMillis()))
                }
                Haptics.light(requireContext())
                toast("已收好，继续看题")
            }
            .show()
    }

    private fun showMasterySheet() {
        val s = vm.session.value ?: return
        val dumps = s.brainDumps.filter { !it.resolved }

        val items = arrayOf(
            "🟢  独立答出   —   3 天后",
            "🟡  需要提示   —   明天",
            "🔴  仍没弄懂   —   移入待重学",
        )

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("这轮掌握得怎么样？")
            .setMessage("首次作答、修正和卡点会一起归档，并自动排进复习队列。")
            .setItems(items) { _, which ->
                val result = when (which) {
                    0 -> Mastery.INDEPENDENT
                    1 -> Mastery.NEED_HINT
                    else -> Mastery.RELEARN
                }
                finishRound(result, dumps)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun finishRound(result: Mastery, dumps: List<BrainDump>) {
        viewLifecycleOwner.lifecycleScope.launch {
            chimes?.playTick()
            Haptics.light(requireContext())
            vm.finishRound(result)
            StageAlarms.cancel(requireContext())

            goHome()

            if (dumps.isNotEmpty()) {
                handler.postDelayed({ showDumpsSummary(dumps) }, 320)
            } else {
                toast("已保存 · ${com.xueyiweng.app.logic.Scheduler.label(result)}复习")
            }
        }
    }

    private fun showDumpsSummary(dumps: List<BrainDump>) {
        if (!isAdded) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("本轮暂存了 ${dumps.size} 条杂念")
            .setMessage(dumps.joinToString("\n\n") { "·  ${it.text}" })
            .setNeutralButton("复制全部") { _, _ ->
                val cm = requireContext().getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText("杂念", dumps.joinToString("\n") { "· ${it.text}" }))
                toast("已复制到剪贴板")
            }
            .setPositiveButton("处理完了") { _, _ -> toast("杂念已归档") }
            .show()
    }

    /* ---------------- 工具 ---------------- */

    private fun simpleWatcher(onChange: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        override fun afterTextChanged(s: Editable?) = onChange(s?.toString() ?: "")
    }

    private fun showKeyboard(v: View) {
        val imm = requireContext().getSystemService(InputMethodManager::class.java)
        imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(InputMethodManager::class.java)
        view?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    override fun onPause() {
        super.onPause()
        stopTicking()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopTicking()
        _binding = null
    }
}
