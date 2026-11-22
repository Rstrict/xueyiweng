package com.xueyiweng.app.ui.home

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.xueyiweng.app.R
import com.xueyiweng.app.databinding.FragmentHomeBinding
import com.xueyiweng.app.logic.Dates
import com.xueyiweng.app.logic.Queues
import com.xueyiweng.app.logic.SessionSnapshot
import com.xueyiweng.app.logic.Stats
import com.xueyiweng.app.logic.Timer
import com.xueyiweng.app.service.StageTimerService
import com.xueyiweng.app.ui.MainActivity
import com.xueyiweng.app.ui.MainViewModel
import com.xueyiweng.app.ui.session.SessionFragment
import com.xueyiweng.app.ui.review.ReviewFragment
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.LocalDate

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding ?: error("[binding] accessed after onDestroyView: " + javaClass.simpleName)

    private val vm: MainViewModel by activityViewModels()

    private val phrases = listOf(
        "为什么" to "为什么…",
        "怎样判断" to "怎样判断…",
        "我能独立做出" to "我能独立做出…",
        "简述" to "简述…核心特征",
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val today = LocalDate.now()
        binding.dateLabel.text = buildString {
            append("${today.monthValue}月${today.dayOfMonth}日")
            append(" · 周")
            append("日一二三四五六"[today.dayOfWeek.value % 7])
        }

        buildPhraseChips()
        wireInputs()
        wireSettings()
        wireResume()

        observeSession()
        observeRecords()

        // 自动聚焦
        viewLifecycleOwner.lifecycleScope.launch {
            val settings = com.xueyiweng.app.XueYiWenApp.from(requireContext()).settings.current()
            if (settings.autoFocusInput && vm.session.value == null) {
                binding.etQuestion.requestFocus()
            }
        }
    }

    private fun buildPhraseChips() {
        for ((fill, label) in phrases) {
            val chip = Chip(requireContext()).apply {
                text = label
                isCheckable = false
                isClickable = true
                setOnClickListener { insertPhrase(fill) }
            }
            binding.chipPhrases.addView(chip)
        }
        val more = Chip(requireContext()).apply {
            text = "章节标题改问题"
            isCheckable = false
            setOnClickListener { showPhraseHelp() }
        }
        binding.chipPhrases.addView(more)
    }

    private fun insertPhrase(fill: String) {
        val et = binding.etQuestion
        et.setText(fill)
        et.requestFocus()
        et.setSelection(fill.length)
        binding.btnStart.isEnabled = et.text.isNotBlank()
    }

    private fun showPhraseHelp() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("把章节标题改写成问题")
            .setMessage(
                "小标题只是\"范围\"，问题才是\"检索线索\"。改写时带上数量或动作。\n\n" +
                    "数学定理：\n「特征值与特征向量」→ 为什么 Ax=λx 里的 λ 可以是非实数？几何上它表示什么？\n\n" +
                    "专业课概念：\n「科举制度」→ 简述科举制度的 4 个核心特征，并说明它解决了什么问题。\n\n" +
                    "英语长难句：\n「定语从句」→ 我能独立拆出一个含两个定语从句的句子并说明各自修饰谁吗？"
            )
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun wireInputs() {
        binding.etQuestion.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                binding.btnStart.isEnabled = !s.isNullOrBlank()
            }
        })

        binding.etQuestion.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE ||
                actionId == EditorInfo.IME_ACTION_GO ||
                actionId == EditorInfo.IME_ACTION_NEXT
            ) {
                false
            } else false
        }

        binding.btnStart.setOnClickListener { startSession() }
        binding.btnStartReview.setOnClickListener { startReview() }
        binding.cardDue.setOnClickListener { startReview() }
    }

    private fun wireSettings() {
        binding.btnSettings.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, com.xueyiweng.app.ui.settings.SettingsFragment())
                .addToBackStack(null)
                .commit()
        }
    }

    private fun wireResume() {
        binding.btnResume.setOnClickListener {
            val s = vm.session.value ?: return@setOnClickListener
            openSession(s)
        }
        binding.btnDiscardSession.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setMessage("放弃这一轮？问题和草稿会一起删除。")
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton("放弃") { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        vm.saveSession(null)
                        StageTimerService.stop(requireContext())
                    }
                }
                .show()
        }
    }

    private fun observeSession() {
        viewLifecycleOwner.lifecycleScope.launch {
            vm.session.collectLatest { s -> renderResume(s) }
        }
    }

    private fun observeRecords() {
        viewLifecycleOwner.lifecycleScope.launch {
            vm.records.collectLatest { list ->
                val today = LocalDate.now()
                val due = Queues.dueForReview(list, today)
                val relearn = Queues.relearnPool(list)
                val stats = Stats.weekly(list)

                binding.dueCount.text = "${due.size} 题"
                binding.btnStartReview.isVisible = due.isNotEmpty()
                binding.cardDue.isVisible = due.isNotEmpty()
                binding.cardNoDue.isVisible = due.isEmpty()

                if (due.isNotEmpty()) {
                    val first = due.first()
                    binding.dueQuestion.text = first.questionText
                    binding.dueMeta.text = buildString {
                        append(Dates.human(first.nextReviewDate, today))
                        if (first.sourceLocation.isNotBlank()) append(" · ${first.sourceLocation}")
                        if (due.size > 1) append("   （共 ${due.size} 题）")
                    }
                }

                // 待重学区
                binding.relearnTitle.isVisible = relearn.isNotEmpty()
                binding.relearnList.isVisible = relearn.isNotEmpty()
                if (relearn.isNotEmpty()) {
                    binding.relearnTitle.text = "待重学区  ${relearn.size} 题 · 建议下次最先攻克"
                    renderRelearnList(relearn.take(3))
                }

                binding.footStats.text = buildString {
                    append("${stats.total} 题存档")
                    if (stats.stable > 0) append(" · ${stats.stable} 题稳固")
                    if (stats.stuck > 0) append(" · ${stats.stuck} 个高频卡点")
                }
            }
        }
    }

    private fun renderRelearnList(list: List<com.xueyiweng.app.logic.QuestionRecord>) {
        binding.relearnList.removeAllViews()
        val ctx = requireContext()
        for (r in list) {
            val card = MaterialCardView(ctx).apply {
                layoutParams = ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = resources.getDimensionPixelSize(R.dimen.gap_sm) }
                radius = resources.getDimension(R.dimen.radius_sm)
                setCardBackgroundColor(com.google.android.material.color.MaterialColors.getColor(
                    this, com.google.android.material.R.attr.colorSurface))
                strokeWidth = resources.getDimensionPixelSize(R.dimen.stroke)
                setStrokeColor(com.google.android.material.color.MaterialColors.getColor(
                    this, com.google.android.material.R.attr.colorOutline))
                isClickable = true
                isFocusable = true
            }
            val tv = android.widget.TextView(ctx).apply {
                text = r.questionText
                textSize = 15f
                setTextColor(com.google.android.material.color.MaterialColors.getColor(
                    this, com.google.android.material.R.attr.colorOnSurface))
                setPadding(42, 38, 42, 38)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            card.addView(tv)
            card.setOnClickListener { prefillQuestion(r.questionText, r.sourceLocation) }
            binding.relearnList.addView(card)
        }
    }

    private fun renderResume(s: SessionSnapshot?) {
        val hasSession = s != null && s.questionText.isNotBlank()
        binding.resumeCard.isVisible = hasSession
        binding.newBlock.isVisible = !hasSession
        if (!hasSession || s == null) return

        binding.resumeQuestion.text = s.questionText
        val remain = Timer.remainingSec(s, System.currentTimeMillis())
        binding.resumeMeta.text = buildString {
            append("停在：")
            append(s.stage.label)
            if (s.isPaused) append(" · 已暂停")
            if (s.pauseContextNote.isNotBlank()) append("\n备注：${s.pauseContextNote}")
            if (!s.isPaused) append("\n剩余 ${Dates.clock(remain)}")
        }
        binding.btnResume.text = if (s.isPaused) "继续这一轮" else "继续这一轮（剩 ${Dates.clock(remain)}）"
    }

    private fun prefillQuestion(text: String, source: String) {
        binding.newBlock.isVisible = true
        binding.resumeCard.isVisible = false
        binding.etQuestion.setText(text)
        binding.etQuestion.setSelection(text.length)
        binding.etSource.setText(source)
        binding.btnStart.isEnabled = true
        binding.etQuestion.requestFocus()
    }

    private fun startSession() {
        val q = binding.etQuestion.text.toString().trim()
        if (q.isEmpty()) return
        val src = binding.etSource.text.toString().trim()

        viewLifecycleOwner.lifecycleScope.launch {
            val settings = com.xueyiweng.app.XueYiWenApp.from(requireContext()).settings.current()
            val s = Timer.create(q, src, settings.stageSeconds)
            vm.saveSession(s)

            binding.etQuestion.setText("")
            binding.etSource.setText("")

            openSession(s)
        }
    }

    private fun openSession(s: SessionSnapshot) {
        (activity as? MainActivity)?.openSession()
        // 启动原生前台服务，息屏也照走
        StageTimerService.start(requireContext(), s)
    }

    private fun startReview() {
        (activity as? MainActivity)?.openReview()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
