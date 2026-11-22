package com.xueyiweng.app.ui.review

import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.xueyiweng.app.R
import com.xueyiweng.app.databinding.FragmentReviewBinding
import com.xueyiweng.app.logic.Dates
import com.xueyiweng.app.logic.Mastery
import com.xueyiweng.app.logic.QuestionRecord
import com.xueyiweng.app.logic.Queues
import com.xueyiweng.app.logic.Records
import com.xueyiweng.app.logic.Scheduler
import com.xueyiweng.app.service.Haptics
import com.xueyiweng.app.ui.MainActivity
import com.xueyiweng.app.ui.MainViewModel
import kotlinx.coroutines.launch
import java.time.LocalDate

class ReviewFragment : Fragment() {

    private var _binding: FragmentReviewBinding? = null
    private val binding get() = _binding ?: error("[binding] accessed after onDestroyView: " + javaClass.simpleName)

    private val vm: MainViewModel by activityViewModels()

    private var queue: List<QuestionRecord> = emptyList()
    private var index = 0
    private var revealed = false

    companion object {
        fun newInstance() = ReviewFragment()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentReviewBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {

        binding.btnExit.setOnClickListener {
            (activity as? MainActivity)?.closeSession()
        }
        binding.btnReveal.setOnClickListener { reveal() }
        binding.btnShowHint.setOnClickListener { showHint() }

        binding.btnIndependent.setOnClickListener { rate(Mastery.INDEPENDENT) }
        binding.btnNeedHint.setOnClickListener { rate(Mastery.NEED_HINT) }
        binding.btnRelearn.setOnClickListener { rate(Mastery.RELEARN) }

        viewLifecycleOwner.lifecycleScope.launch {
            val list = vm.repository.allRecords()
            queue = Queues.dueForReview(list, LocalDate.now())
            index = 0
            if (queue.isEmpty()) finishAll() else renderCurrent()
        }
    }

    private fun renderCurrent() {
        if (index >= queue.size) { finishAll(); return }
        revealed = false

        val r = queue[index]
        binding.counter.text = "${index + 1} / ${queue.size}"
        renderProgress()
        binding.questionText.text = r.questionText

        if (r.sourceLocation.isBlank()) {
            binding.sourceText.isVisible = false
        } else {
            binding.sourceText.isVisible = true
            binding.sourceText.text = "📍 ${r.sourceLocation}"
        }

        val days = r.nextReviewDate?.let { Dates.daysBetween(LocalDate.now(), it) } ?: 0
        binding.reviewHint.text = buildString {
            append(getString(R.string.review_hint))
            append("\n")
            append("第 ${r.reviewCount + 1} 次复习")
            if (days > 0) append(" · 逾期 $days 天")
        }

        binding.revealBlock.isVisible = false
        binding.rateBlock.isVisible = false
        binding.askActions.isVisible = true

        binding.btnIndependent.text =
            "🟢  独立答出   —   ${Scheduler.label(Mastery.INDEPENDENT, r.intervalDays, r.consecutiveIndependent)}"
        binding.btnNeedHint.text = "🟡  需要提示   —   明天"
        binding.btnRelearn.text = "🔴  仍没弄懂   —   移入待重学"
        binding.etNewStuck.setText("")
    }

    private fun renderProgress() {
        binding.progressLine.removeAllViews()
        val ctx = requireContext()
        for (i in queue.indices) {
            val v = View(ctx)
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            lp.marginEnd = (4 * resources.displayMetrics.density).toInt()
            v.layoutParams = lp
            v.setBackgroundColor(
                ContextCompat.getColor(
                    ctx,
                    when {
                        i < index -> R.color.accent
                        i == index -> R.color.ink3
                        else -> R.color.line
                    }
                )
            )
            binding.progressLine.addView(v)
        }
    }

    private fun reveal() {
        val r = queue[index]
        revealed = true
        binding.askActions.isVisible = false
        binding.rateBlock.isVisible = true

        binding.revealBlock.removeAllViews()
        binding.revealBlock.isVisible = true

        if (r.firstAttemptContent.isNotBlank()) {
            addSection("上次作答", r.firstAttemptContent, R.color.ink2, R.color.card2)
        }
        if (r.stuckPoint.isNotBlank()) {
            addSection("卡点", r.stuckPoint, R.color.ink, R.color.amberBg)
        }
        if (r.finalNote.isNotBlank()) {
            addSection("修正 / 结论", r.finalNote, R.color.ink, R.color.card)
        }
        if (r.firstAttemptContent.isBlank() && r.stuckPoint.isBlank() && r.finalNote.isBlank()) {
            addSection("记录", "这道题当时没有留下文字记录（可能是纸上 / 口头作答）。", R.color.ink2, R.color.card2)
        }
    }

    private fun addSection(title: String, body: String, textColorRes: Int, bgColorRes: Int) {
        val ctx = requireContext()
        val card = MaterialCardView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = (10 * resources.displayMetrics.density).toInt() }
            radius = resources.getDimension(R.dimen.radius_sm)
            setCardBackgroundColor(ContextCompat.getColor(ctx, bgColorRes))
            strokeWidth = resources.getDimensionPixelSize(R.dimen.stroke)
            setStrokeColor(ContextCompat.getColor(ctx, R.color.line))
        }
        val inner = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 34, 40, 34)
        }
        inner.addView(TextView(ctx).apply {
            text = title
            textSize = 11.5f
            typeface = Typeface.create(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(ctx, R.color.ink3))
            letterSpacing = 0.08f
        })
        inner.addView(TextView(ctx).apply {
            text = body
            textSize = 14.5f
            setTextColor(ContextCompat.getColor(ctx, textColorRes))
            setLineSpacing(0f, 1.65f)
            setPadding(0, (6 * resources.displayMetrics.density).toInt(), 0, 0)
        })
        card.addView(inner)
        binding.revealBlock.addView(card)
    }

    private fun showHint() {
        val r = queue[index]
        if (r.stuckPoint.isBlank()) {
            toast("这道题没有记录过卡点")
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("卡点提示")
            .setMessage("只看这一条，别展开全部记录。\n\n${r.stuckPoint}")
            .setPositiveButton("继续自评", null)
            .show()
    }

    private fun rate(result: Mastery) {
        val r = queue[index]
        val newStuck = binding.etNewStuck.text.toString().trim()

        viewLifecycleOwner.lifecycleScope.launch {
            val updated = Records.applyReview(
                r = r,
                result = result,
                newStickingPoints = newStuck,
            )
            vm.repository.upsert(updated)

            Haptics.light(requireContext())
            val label = Scheduler.label(result, r.intervalDays, r.consecutiveIndependent)
            toast("${result.label} · 下次 $label")

            index++
            if (index >= queue.size) finishAll() else renderCurrent()
        }
    }

    private fun finishAll() {
        if (!isAdded) return
        val title = if (queue.isEmpty()) "今天没有要复习的题" else "今日复习完成"
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setMessage("剩下的交给明天的你。")
            .setCancelable(false)
            .setPositiveButton("回到今天") { _, _ ->
                (activity as? MainActivity)?.closeSession()
            }
            .show()
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
