package com.younes.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.younes.app.data.ExamRepository
import com.younes.app.data.PdfDownloadManager
import com.younes.app.models.ExamPdf
import com.younes.app.models.ExamType
import com.younes.app.ui.NetworkHelper
import kotlinx.coroutines.launch

private const val TAG = "ExamListActivity"

class ExamListActivity : AppCompatActivity() {

    private lateinit var subjectId: String
    private lateinit var subjectNameAr: String

    private lateinit var subjectTitle: TextView
    private lateinit var examCountText: TextView
    private lateinit var examList: RecyclerView
    private lateinit var loadingOverlay: View
    private lateinit var emptyState: View
    private lateinit var emptyText: TextView
    private lateinit var retryButton: TextView
    private lateinit var helpHint: TextView

    private val examAdapter = ExamAdapter(
        onExamClick = { exam -> openExam(exam) },
        onFavoriteClick = { exam -> toggleFavorite(exam) }
    )

    private var allExams = listOf<ExamPdf>()
    private var currentFilter = FilterType.ALL
    private var filterButtons: Map<Int, FilterType> = emptyMap()

    private enum class FilterType { ALL, SUJET, CORRECTION, FAVORITES }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_exam_list)

        subjectId = intent.getStringExtra("subject_id") ?: return finish()
        subjectNameAr = intent.getStringExtra("subject_name_ar") ?: "Matière"

        subjectTitle = findViewById(R.id.subjectTitle)
        examCountText = findViewById(R.id.examCount)
        examList = findViewById(R.id.examList)
        loadingOverlay = findViewById(R.id.loadingOverlay)
        emptyState = findViewById(R.id.emptyState)
        emptyText = findViewById(R.id.emptyText)
        retryButton = findViewById(R.id.btnRetry)
        helpHint = findViewById(R.id.helpHint)

        subjectTitle.text = subjectNameAr

        examList.layoutManager = LinearLayoutManager(this)
        examList.adapter = examAdapter

        setupFilterButtons()
        retryButton.setOnClickListener { loadExams() }
        loadExams()
    }

    private fun setupFilterButtons() {
        filterButtons = mapOf(
            R.id.btnFilterAll to FilterType.ALL,
            R.id.btnFilterSujet to FilterType.SUJET,
            R.id.btnFilterCorrection to FilterType.CORRECTION,
            R.id.btnFilterFavorites to FilterType.FAVORITES
        )

        for ((id, filter) in filterButtons) {
            val btn = findViewById<TextView>(id)
            btn.setOnClickListener {
                currentFilter = filter
                updateFilterSelection()
                applyFilter()
                examList.post {
                    examList.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                }
            }
            btn.setOnKeyListener { v, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN &&
                    (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
                ) {
                    v.performClick()
                    true
                } else {
                    false
                }
            }
        }
        updateFilterSelection()
    }

    private fun updateFilterSelection() {
        filterButtons.forEach { (id, filter) ->
            findViewById<View>(id).isSelected = filter == currentFilter
        }
    }

    private fun loadExams() {
        loadingOverlay.visibility = View.VISIBLE
        emptyState.visibility = View.GONE
        retryButton.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val exams = ExamRepository.loadExams(subjectId)
                allExams = exams
                loadingOverlay.visibility = View.GONE

                if (exams.isEmpty()) {
                    emptyState.visibility = View.VISIBLE
                    emptyText.text = getString(R.string.no_exams)
                    retryButton.visibility = View.VISIBLE
                    retryButton.requestFocus()
                } else {
                    applyFilter()
                    // Focus sur le premier bouton filtre pour navigation télécommande
                    findViewById<View>(R.id.btnFilterAll)?.requestFocus()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erreur chargement: ${e.message}", e)
                loadingOverlay.visibility = View.GONE
                emptyState.visibility = View.VISIBLE
                emptyText.text = if (NetworkHelper.isOnline(this@ExamListActivity)) {
                    getString(R.string.download_failed)
                } else {
                    getString(R.string.no_connection)
                }
                retryButton.visibility = View.VISIBLE
                retryButton.requestFocus()
            }
        }
    }

    private fun applyFilter() {
        val filtered = when (currentFilter) {
            FilterType.ALL -> allExams
            FilterType.SUJET -> allExams.filter { it.type == ExamType.SUJET }
            FilterType.CORRECTION -> allExams.filter { it.type == ExamType.CORRECTION }
            FilterType.FAVORITES -> {
                val favs = ExamRepository.getFavorites()
                allExams.filter { favs.contains(it.id) }
            }
        }

        examAdapter.submitList(filtered)
        examCountText.text = "${filtered.size} examen(s)"
        emptyState.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        retryButton.visibility = View.GONE
        if (filtered.isEmpty() && currentFilter == FilterType.FAVORITES) {
            emptyText.text = "Aucun favori"
        } else if (filtered.isEmpty()) {
            emptyText.text = getString(R.string.no_exams)
        }
    }

    private fun toggleFavorite(exam: ExamPdf) {
        ExamRepository.toggleFavorite(exam.id)
        if (currentFilter == FilterType.FAVORITES) applyFilter() else examAdapter.refreshExam(exam.id)
    }

    private fun openExam(exam: ExamPdf) {
        val file = ExamRepository.getDownloadedExamFile(exam.id)

        if (file.exists()) {
            openPdfReader(exam)
        } else {
            downloadAndOpen(exam)
        }
    }

    private fun downloadAndOpen(exam: ExamPdf) {
        loadingOverlay.visibility = View.VISIBLE
        emptyState.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val pdfUrl = ExamRepository.getPdfUrl(exam)
                if (pdfUrl.isBlank()) {
                    loadingOverlay.visibility = View.GONE
                    emptyState.visibility = View.VISIBLE
                    emptyText.text = getString(R.string.error_no_pdf_url)
                    return@launch
                }

                PdfDownloadManager.downloadPdf(
                    examId = exam.id,
                    pdfUrl = pdfUrl,
                    callback = object : PdfDownloadManager.DownloadCallback {
                        override fun onProgress(examId: String, progress: Int) {
                            findViewById<TextView>(R.id.loadingText)?.text =
                                getString(R.string.downloading, progress)
                        }

                        override fun onSuccess(examId: String, file: java.io.File) {
                            loadingOverlay.visibility = View.GONE
                            openPdfReader(exam)
                        }

                        override fun onError(examId: String, message: String) {
                            loadingOverlay.visibility = View.GONE
                            emptyState.visibility = View.VISIBLE
                            emptyText.text = getString(R.string.download_failed)
                            Log.e(TAG, "Download error: $message")
                        }
                    },
                    scope = lifecycleScope
                )
            } catch (e: Exception) {
                Log.e(TAG, "Erreur: ${e.message}", e)
                loadingOverlay.visibility = View.GONE
                emptyState.visibility = View.VISIBLE
                emptyText.text = "Erreur: ${e.message}"
            }
        }
    }

    private fun openPdfReader(exam: ExamPdf) {
        val file = ExamRepository.getDownloadedExamFile(exam.id)
        val intent = Intent(this, PdfReaderActivity::class.java).apply {
            putExtra("exam_id", exam.id)
            putExtra("pdf_path", file.absolutePath)
            putExtra("subject_id", exam.subjectId)
            putExtra("exam_type", exam.type.name)
            putExtra("default_duration", com.younes.app.timer.ExamDurations.getDuration(exam.subjectId))
        }
        startActivity(intent)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                finish()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
