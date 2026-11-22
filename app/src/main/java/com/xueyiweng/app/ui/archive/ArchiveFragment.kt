package com.xueyiweng.app.ui.archive

import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.xueyiweng.app.R
import com.xueyiweng.app.databinding.FragmentArchiveBinding
import com.xueyiweng.app.logic.Dates
import com.xueyiweng.app.logic.Mastery
import com.xueyiweng.app.logic.QuestionRecord
import com.xueyiweng.app.logic.Queues
import com.xueyiweng.app.logic.Stats
import com.xueyiweng.app.ui.MainViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.LocalDate

class ArchiveFragment : Fragment() {

    private var _binding: FragmentArchiveBinding? = null
    private val binding get() = _binding ?: error("[binding] accessed after onDestroyView: " + javaClass.simpleName)

    private val vm: MainViewModel by activityViewModels()

    private data class Pool(val title: String, val emoji: String, val hint: String, val items: List<QuestionRecord>)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentArchiveBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.btnSettings.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, com.xueyiweng.app.ui.settings.SettingsFragment())
                .addToBackStack(null)
                .commit()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            vm.records.collectLatest { list -> render(list) }
        }
    }

    private fun render(list: List<QuestionRecord>) {
        val stats = Stats.weekly(list)
        val rate = Queues.retentionRate(list)

        binding.statLearned.text = stats.learned.toString()
        binding.statReviewed.text = stats.reviewed.toString()
        binding.statRate.text = rate?.let { "${(it * 100).toInt()}%" } ?: "—"

        renderPools(list)
        renderAllList(list)
    }

    private fun renderPools(list: List<QuestionRecord>) {
        binding.reviewCards.removeAllViews()
        val pools = listOf(
            Pool("稳固掌握", "🟢", "隔次仍能独立答出，已移出高频队列", Queues.stablePool(list)),
            Pool("高频卡点", "🟡", "最近两次都需要提示，值得单独拎出来", Queues.highFreqStuck(list)),
            Pool("下次优先", "🔴", "待重学区，建议下次坐下来最先攻克", Queues.relearnPool(list)),
        )
        for (pool in pools) {
            val card = makeCard()
            val inner = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(42, 36, 42, 36)
            }
            inner.addView(TextView(requireContext()).apply {
                text = "${pool.emoji}  ${pool.title} · ${pool.items.size} 题"
                textSize = 15f
                typeface = Typeface.create(typeface, Typeface.BOLD)
                setTextColor(ContextCompat.getColor(requireContext(), R.color.ink))
            })
            inner.addView(TextView(requireContext()).apply {
                text = pool.hint
                textSize = 12.5f
                setTextColor(ContextCompat.getColor(requireContext(), R.color.ink2))
                setPadding(0, (6 * resources.displayMetrics.density).toInt(), 0, 0)
            })
            card.addView(inner)
            card.setOnClickListener { showPool(pool) }
            binding.reviewCards.addView(card)
        }
    }

    private fun makeCard(): MaterialCardView = MaterialCardView(requireContext()).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = (10 * resources.displayMetrics.density).toInt() }
        radius = resources.getDimension(R.dimen.radius_sm)
        setCardBackgroundColor(ContextCompat.getColor(requireContext(), R.color.card))
        strokeWidth = resources.getDimensionPixelSize(R.dimen.stroke)
        setStrokeColor(ContextCompat.getColor(requireContext(), R.color.line))
        isClickable = true
        isFocusable = true
    }

    private fun renderAllList(list: List<QuestionRecord>) {
        binding.allCount.text = "${list.size} 题"
        binding.emptyHint.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        binding.allList.removeAllViews()
        val today = LocalDate.now()

        for (r in list.sortedByDescending { it.createdAt }) {
            val card = makeCard()
            val inner = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(42, 36, 42, 36)
            }
            inner.addView(TextView(requireContext()).apply {
                text = r.questionText
                textSize = 15.5f
                typeface = Typeface.create(typeface, Typeface.BOLD)
                setTextColor(ContextCompat.getColor(requireContext(), R.color.ink))
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            inner.addView(TextView(requireContext()).apply {
                val due = r.nextReviewDate != null &&
                    Dates.daysBetween(r.nextReviewDate!!, today) <= 0 && !r.isStable
                text = buildString {
                    append(r.mastery.label)
                    if (r.isStable) append(" · 稳固")
                    if (due) append(" · 待复习")
                    else if (r.nextReviewDate != null) append(" · ${Dates.human(r.nextReviewDate, today)}")
                    if (r.sourceLocation.isNotBlank()) append(" · ${r.sourceLocation}")
                    append(" · 复 ${r.reviewCount} 次")
                }
                textSize = 12.5f
                setTextColor(ContextCompat.getColor(requireContext(), R.color.ink2))
                setPadding(0, (8 * resources.displayMetrics.density).toInt(), 0, 0)
            })
            card.addView(inner)
            card.setOnClickListener { showDetail(r) }
            binding.allList.addView(card)
        }
    }

    private fun showPool(pool: Pool) {
        if (pool.items.isEmpty()) {
            toast("这里是空的")
            return
        }
        val names = pool.items.map { it.questionText.take(40) }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("${pool.title} · ${pool.items.size} 题")
            .setItems(names) { _, which -> showDetail(pool.items[which]) }
            .setNegativeButton(R.string.action_close, null)
            .show()
    }

    private fun showDetail(r: QuestionRecord) {
        val sb = StringBuilder()
        if (r.sourceLocation.isNotBlank()) sb.append("📍 ${r.sourceLocation}\n\n")
        sb.append("状态：${r.mastery.label}")
        if (r.isStable) sb.append(" · 稳固")
        r.nextReviewDate?.let { sb.append("\n下次复习：$it") }
        sb.append("\n复习次数：${r.reviewCount}")
        if (r.firstAttemptContent.isNotBlank()) sb.append("\n\n【首次作答】\n${r.firstAttemptContent}")
        if (r.stuckPoint.isNotBlank()) sb.append("\n\n【卡点】\n${r.stuckPoint}")
        if (r.finalNote.isNotBlank()) sb.append("\n\n【修正 / 结论】\n${r.finalNote}")
        if (r.reviewHistory.isNotEmpty()) {
            sb.append("\n\n【复习轨迹】")
            r.reviewHistory.takeLast(6).forEach { h ->
                sb.append("\n· ${Mastery.fromId(h.result).label}")
                if (h.note.isNotBlank()) sb.append("  ${h.note}")
            }
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(r.questionText.take(50))
            .setMessage(sb.toString())
            .setNeutralButton(R.string.action_delete) { _, _ -> confirmDelete(r) }
            .setPositiveButton(R.string.action_close, null)
            .show()
    }

    private fun confirmDelete(r: QuestionRecord) {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage("删除后无法恢复，确定吗？")
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    vm.deleteRecord(r.id)
                    toast("已删除")
                }
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
