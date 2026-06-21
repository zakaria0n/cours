package com.younes.app

import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.younes.app.data.ExamRepository
import com.younes.app.data.AppUpdateManager
import com.younes.app.data.UpdateInfo
import com.younes.app.models.SubjectItem

private const val TAG = "HomeActivity"

class HomeActivity : AppCompatActivity() {

    private lateinit var subjectsGrid: RecyclerView
    private lateinit var loadingOverlay: View
    private lateinit var errorText: TextView
    private lateinit var continueBanner: View
    private lateinit var continueTitle: TextView
    private lateinit var helpHint: View
    private lateinit var updateCard: View
    private lateinit var updateText: TextView
    private lateinit var updateDownload: TextView
    private lateinit var updateLater: TextView
    private var availableUpdate: UpdateInfo? = null

    private val subjectsAdapter = SubjectsAdapter { subject ->
        openExamList(subject)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate")
        ExamRepository.init(applicationContext)
        setContentView(R.layout.activity_home)

        subjectsGrid = findViewById(R.id.subjectsGrid)
        loadingOverlay = findViewById(R.id.loadingOverlay)
        errorText = findViewById(R.id.errorText)
        continueBanner = findViewById(R.id.continueReadingBanner)
        continueTitle = findViewById(R.id.continueReadingTitle)
        helpHint = findViewById(R.id.helpHint)
        updateCard = findViewById(R.id.updateCard)
        updateText = findViewById(R.id.updateText)
        updateDownload = findViewById(R.id.btnUpdateDownload)
        updateLater = findViewById(R.id.btnUpdateLater)

        setupSubjectsGrid()
        loadContinueReading()
        setupUpdates()
    }

    private fun setupUpdates() {
        updateDownload.setOnClickListener {
            val update = availableUpdate ?: return@setOnClickListener
            if (!AppUpdateManager.canInstallPackages(this)) {
                updateText.text = getString(R.string.update_install_permission)
                return@setOnClickListener
            }

            updateDownload.isEnabled = false
            updateText.text = getString(R.string.update_downloading)
            AppUpdateManager.downloadAndInstall(this, update) { success ->
                updateDownload.isEnabled = true
                if (!success) {
                    updateText.text = getString(R.string.update_failed)
                    updateDownload.requestFocus()
                }
            }
        }
        updateLater.setOnClickListener {
            updateCard.visibility = View.GONE
            subjectsGrid.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
        }

        AppUpdateManager.checkForUpdate { result ->
            result.getOrNull()?.let { update ->
                availableUpdate = update
                updateText.text = getString(R.string.update_available, update.version)
                updateCard.visibility = View.VISIBLE
                updateDownload.requestFocus()
            }
        }
    }

    private fun setupSubjectsGrid() {
        val layoutManager = GridLayoutManager(this, 3)
        subjectsGrid.layoutManager = layoutManager
        subjectsGrid.adapter = subjectsAdapter

        // Add spacing between cards
        val gapPx = resources.getDimensionPixelSize(R.dimen.home_card_gap)
        val gapVerticalPx = resources.getDimensionPixelSize(R.dimen.home_card_gap_vertical)
        subjectsGrid.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(
                outRect: android.graphics.Rect,
                view: View,
                parent: RecyclerView,
                state: RecyclerView.State
            ) {
                val pos = parent.getChildAdapterPosition(view)
                val spanCount = 3
                val col = pos % spanCount

                outRect.left = if (col == 0) 0 else gapPx / 2
                outRect.right = if (col == spanCount - 1) 0 else gapPx / 2
                outRect.top = if (pos < spanCount) 0 else gapVerticalPx / 2
                outRect.bottom = gapVerticalPx / 2
            }
        })

        val subjects = ExamRepository.getSubjects()
        subjectsAdapter.submitList(subjects) {
            if (continueBanner.visibility != View.VISIBLE) {
                subjectsGrid.post {
                    subjectsGrid.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                }
            }
        }
    }

    private fun loadContinueReading() {
        val progress = ExamRepository.getReadingProgress() ?: return
        val examId = progress.examId
        val file = ExamRepository.getDownloadedExamFile(examId)

        if (file.exists()) {
            continueBanner.visibility = View.VISIBLE
            continueTitle.text = "Dernière lecture \u2014 page ${progress.pageIndex + 1}"

            // Focus animation for continue card
            var continueAnimator: ValueAnimator? = null
            continueBanner.setOnFocusChangeListener { v, hasFocus ->
                continueAnimator?.cancel()
                val scale = if (hasFocus) 1.02f else 1.0f
                continueAnimator = ValueAnimator.ofFloat(v.scaleX, scale).apply {
                    duration = 150
                    addUpdateListener {
                        val s = it.animatedValue as Float
                        v.scaleX = s
                        v.scaleY = s
                    }
                    start()
                }
                v.elevation = if (hasFocus) 8f else 3f
            }

            continueBanner.setOnClickListener {
                openPdfReader(
                    examId,
                    file.absolutePath,
                    progress.pageIndex,
                    progress.subjectId,
                    progress.examType
                )
            }
            continueBanner.setOnKeyListener { view, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN &&
                    (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
                ) {
                    view.performClick()
                    true
                } else false
            }

            continueBanner.requestFocus()
        }
    }

    private fun openExamList(subject: SubjectItem) {
        val intent = Intent(this, ExamListActivity::class.java).apply {
            putExtra("subject_id", subject.id)
            putExtra("subject_name_ar", subject.nameAr)
            putExtra("subject_name_fr", subject.nameFr)
        }
        startActivity(intent)
    }

    private fun openPdfReader(
        examId: String,
        filePath: String,
        page: Int,
        subjectId: String?,
        examType: String?
    ) {
        val intent = Intent(this, PdfReaderActivity::class.java).apply {
            putExtra("exam_id", examId)
            putExtra("pdf_path", filePath)
            putExtra("start_page", page)
            putExtra("subject_id", subjectId)
            putExtra("exam_type", examType)
            putExtra(
                "default_duration",
                com.younes.app.timer.ExamDurations.getDuration(subjectId.orEmpty())
            )
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
