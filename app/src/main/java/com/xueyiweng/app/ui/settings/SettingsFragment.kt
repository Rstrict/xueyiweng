package com.xueyiweng.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.xueyiweng.app.R
import com.xueyiweng.app.XueYiWenApp
import com.xueyiweng.app.databinding.FragmentSettingsBinding
import com.xueyiweng.app.logic.JsonLite
import com.xueyiweng.app.logic.QuestionRecord
import com.xueyiweng.app.logic.Stage
import com.xueyiweng.app.logic.Transfer
import com.xueyiweng.app.service.Haptics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var settingsRepo: com.xueyiweng.app.data.SettingsRepository

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { doImport(it) }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        settingsRepo = XueYiWenApp.from(requireContext()).settings

        binding.btnBack.setOnClickListener { parentFragmentManager.popBackStack() }

        buildStageSliders()
        buildToggles()
        buildDataSection()

        binding.btnExportJson.setOnClickListener { exportJson() }
        binding.btnExportMd.setOnClickListener { exportMarkdown() }
        binding.btnImport.setOnClickListener {
            importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
        }
        binding.btnWipe.setOnClickListener { confirmWipe() }
    }

    /* ---------------- 阶段时长 ---------------- */

    private fun buildStageSliders() {
        binding.stageSliders.removeAllViews()
        viewLifecycleOwner.lifecycleScope.launch {
            val s = settingsRepo.current()
            for (stage in Stage.entries) {
                val sec = s.stageSeconds[stage.id] ?: stage.defaultSec
                binding.stageSliders.addView(makeSliderRow(stage, sec))
            }
        }
    }

    private fun makeSliderRow(stage: Stage, currentSec: Int): View {
        val ctx = requireContext()
        val d = resources.displayMetrics.density
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (10 * d).toInt(), 0, (10 * d).toInt())
        }

        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val title = TextView(ctx).apply {
            text = stage.label
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(ctx, R.color.ink))
        }
        val value = TextView(ctx).apply {
            text = "${currentSec / 60} 分"
            textSize = 14f
            setTextColor(ContextCompat.getColor(ctx, R.color.ink2))
            gravity = android.view.Gravity.END
        }
        head.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(value)
        row.addView(head)

        val seek = SeekBar(ctx).apply {
            max = 29
            progress = (currentSec / 60).coerceIn(1, 30) - 1
            setPadding(0, (8 * d).toInt(), 0, 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    value.text = "${p + 1} 分"
                }

                override fun onStartTrackingTouch(sb: SeekBar?) = Unit

                override fun onStopTrackingTouch(sb: SeekBar?) {
                    val minutes = (sb?.progress ?: 0) + 1
                    viewLifecycleOwner.lifecycleScope.launch { settingsRepo.setStageSeconds(stage.id, minutes * 60) }
                }
            })
        }
        row.addView(seek)

        row.addView(TextView(ctx).apply {
            text = stage.hint
            textSize = 12f
            setTextColor(ContextCompat.getColor(ctx, R.color.ink3))
        })
        return row
    }

    /* ---------------- 开关 ---------------- */

    private fun buildToggles() {
        binding.toggleRows.removeAllViews()
        viewLifecycleOwner.lifecycleScope.launch {
            val s = settingsRepo.current()

            binding.toggleRows.addView(makeThemeRow(s.theme))
            binding.toggleRows.addView(makeSwitchRow("默认隐藏秒数", "只留圆环，不显示倒计时数字", s.hideTimerDigits) {
                viewLifecycleOwner.lifecycleScope.launch { settingsRepo.setHideTimerDigits(it) }
            })
            binding.toggleRows.addView(makeSwitchRow("首页自动聚焦", "打开即写，进一步缩短启动路径", s.autoFocusInput) {
                viewLifecycleOwner.lifecycleScope.launch { settingsRepo.setAutoFocus(it) }
            })
            binding.toggleRows.addView(makeSwitchRow("震动", "阶段到点双轻震，保存时单震", s.haptic) {
                viewLifecycleOwner.lifecycleScope.launch { settingsRepo.setHaptic(it) }
                if (it) Haptics.light(requireContext())
            })
            binding.toggleRows.addView(makeSwitchRow("提示音", "单次短促木鱼声（＜0.8s）", s.sound) {
                viewLifecycleOwner.lifecycleScope.launch { settingsRepo.setSound(it) }
                if (it) com.xueyiweng.app.service.Chimes(requireContext()).playChime()
            })
        }
    }

    private fun makeThemeRow(current: String): View {
        val ctx = requireContext()
        val d = resources.displayMetrics.density
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, (14 * d).toInt(), 0, (14 * d).toInt())
        }
        val left = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        left.addView(TextView(ctx).apply {
            text = "主题"
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(ctx, R.color.ink))
        })
        left.addView(TextView(ctx).apply {
            text = "跟随系统 / 常亮纸感 / 常亮夜间"
            textSize = 12.5f
            setTextColor(ContextCompat.getColor(ctx, R.color.ink2))
        })
        row.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val spinner = android.widget.Spinner(ctx, null, android.R.attr.spinnerStyle).apply {
            adapter = android.widget.ArrayAdapter(
                ctx,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("跟随系统", "浅色", "深色"),
            )
            setSelection(when (current) { "light" -> 1; "dark" -> 2; else -> 0 })
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    val v2 = when (pos) { 1 -> "light"; 2 -> "dark"; else -> "auto" }
                    viewLifecycleOwner.lifecycleScope.launch { settingsRepo.setTheme(v2) }
                }
                override fun onNothingSelected(p: android.widget.AdapterView<*>?) = Unit
            }
        }
        row.addView(spinner, LinearLayout.LayoutParams(
            (110 * d).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        return row
    }

    private fun makeSwitchRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val ctx = requireContext()
        val d = resources.displayMetrics.density
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, (14 * d).toInt(), 0, (14 * d).toInt())
        }
        val left = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        left.addView(TextView(ctx).apply {
            text = title
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(ctx, R.color.ink))
        })
        left.addView(TextView(ctx).apply {
            text = desc
            textSize = 12.5f
            setTextColor(ContextCompat.getColor(ctx, R.color.ink2))
        })
        row.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val sw = SwitchCompat(ctx).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, c -> onChange(c) }
        }
        row.addView(sw)
        return row
    }

    /* ---------------- 数据 ---------------- */

    private fun buildDataSection() {
        viewLifecycleOwner.lifecycleScope.launch {
            val count = XueYiWenApp.from(requireContext()).repo.recordCount()
            binding.storageInfo.text = "SQLite（应用私有目录）· $count 题存档"
        }
    }

    private fun exportFile(content: String, ext: String, mime: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val dir = File(requireContext().cacheDir, "exports").apply { mkdirs() }
                val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.CHINA).format(Date())
                val file = File(dir, "学一问-备份-$stamp.$ext")
                withContext(Dispatchers.IO) { file.writeText(content) }

                val uri = FileProvider.getUriForFile(
                    requireContext(),
                    "${requireContext().packageName}.fileprovider",
                    file,
                )
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, "导出到"))
            } catch (e: Exception) {
                toast("导出失败：${e.message}")
            }
        }
    }

    private fun exportJson() {
        viewLifecycleOwner.lifecycleScope.launch {
            val repo = XueYiWenApp.from(requireContext()).repo
            val records = repo.allRecords()
            val payload = mapOf(
                "app" to Transfer.APP_ID,
                "version" to Transfer.VERSION,
                "exportedAt" to System.currentTimeMillis(),
                "records" to records.map { r -> recordToMap(r) },
            )
            exportFile(JsonLite.stringify(payload), "json", "application/json")
        }
    }

    private fun recordToMap(r: QuestionRecord): Map<String, Any?> = mapOf(
        "id" to r.id,
        "questionText" to r.questionText,
        "sourceLocation" to r.sourceLocation,
        "subjectTag" to r.subjectTag,
        "createdAt" to r.createdAt,
        "firstAttempt" to mapOf("type" to r.firstAttemptType, "content" to r.firstAttemptContent),
        "stuckPoint" to r.stuckPoint,
        "finalNote" to r.finalNote,
        "masteryStatus" to r.masteryStatus,
        "intervalDays" to r.intervalDays,
        "consecutiveIndependent" to r.consecutiveIndependent,
        "isStable" to r.isStable,
        "nextReviewDate" to r.nextReviewDate?.toString(),
        "lastReviewedAt" to r.lastReviewedAt,
        "reviewCount" to r.reviewCount,
        "reviewHistory" to r.reviewHistory.map {
            mapOf("reviewedAt" to it.reviewedAt, "result" to it.result, "note" to it.note)
        },
    )

    private fun exportMarkdown() {
        viewLifecycleOwner.lifecycleScope.launch {
            val records = XueYiWenApp.from(requireContext()).repo.allRecords()
            exportFile(Transfer.toMarkdown(records), "md", "text/markdown")
        }
    }

    private fun doImport(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    requireContext().contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: ""
                }
                val result = Transfer.parseImport(text)
                if (!result.ok) {
                    toast(result.error ?: "导入失败")
                    return@launch
                }
                if (result.records.isEmpty()) {
                    toast("文件里没有可导入的记录")
                    return@launch
                }
                val repo = XueYiWenApp.from(requireContext()).repo
                val existing = repo.allRecords()
                val merged = Transfer.merge(existing, result.records)
                repo.upsertAll(merged.records)

                val msg = buildString {
                    append("新增 ${merged.added} 题")
                    if (merged.updated > 0) append(" · 更新 ${merged.updated} 题")
                    if (result.skipped > 0) append(" · 跳过 ${result.skipped} 条坏数据")
                }
                toast("导入完成 · $msg")
                buildDataSection()
            } catch (e: Exception) {
                toast("读取文件失败：${e.message}")
            }
        }
    }

    private fun confirmWipe() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("清空全部数据？")
            .setMessage("将删除全部学习记录、会话快照与设置，无法恢复。")
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton("清空") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val app = XueYiWenApp.from(requireContext())
                    app.repo.clearRecords()
                    app.repo.saveSession(null)
                    settingsRepo.clearAll()
                    com.xueyiweng.app.service.StageTimerService.stop(requireContext())
                    toast("已清空")
                    buildDataSection()
                }
            }
            .show()
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_LONG).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
