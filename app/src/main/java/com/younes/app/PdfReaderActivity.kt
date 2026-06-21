package com.younes.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.younes.app.data.ExamRepository
import com.younes.app.timer.AlertLevel
import com.younes.app.timer.ExamDurations
import com.younes.app.timer.ExamTimerManager
import java.io.File

private const val TAG = "PdfReaderActivity"
private const val AUTO_HIDE_DELAY = 5000L
private const val ZOOM_INDICATOR_DELAY = 1500L
private const val TIMER_TICK_INTERVAL = 1000L

class PdfReaderActivity : AppCompatActivity() {

    // PDF Views
    private lateinit var pdfViewer: PdfViewerView
    private lateinit var pageIndicator: TextView
    private lateinit var errorText: TextView
    private lateinit var helpHintText: TextView
    private lateinit var zoomIndicator: TextView
    private lateinit var loadingOverlay: View
    private lateinit var topBar: View
    private lateinit var helpOverlay: View

    // Timer Views
    private lateinit var timerFloatingContainer: View
    private lateinit var timerFloatingIcon: TextView
    private lateinit var timerFloatingText: TextView
    private lateinit var timerPanelRoot: View
    private lateinit var timerSubjectName: TextView
    private lateinit var timerRemainingTime: TextView
    private lateinit var timerProgressBar: ProgressBar
    private lateinit var timerAlertText: TextView
    private lateinit var timerBtnPauseResume: TextView
    private lateinit var timerBtnAdd5: TextView
    private lateinit var timerBtnReset: TextView
    private lateinit var timerBtnModify: TextView
    private lateinit var timerBtnClose: TextView
    private lateinit var timerFinishedRoot: View
    private lateinit var timerFinishedAdd5: TextView
    private lateinit var timerFinishedFinish: TextView
    private lateinit var timerFinishedRestart: TextView
    private lateinit var timerDurationRoot: View
    private var durationButtons: List<TextView> = emptyList()
    private lateinit var durConfirm: TextView
    private lateinit var durCancel: TextView
    private lateinit var scrollButtons: View
    private lateinit var btnScrollUp: TextView
    private lateinit var btnScrollDown: TextView

    // State
    private var controlsVisible = true
    private var helpVisible = false
    private var timerPanelVisible = false
    private var durationPickerVisible = false
    private var finishedOverlayVisible = false
    private var examId: String? = null
    private var subjectId: String? = null
    private var defaultDurationMinutes: Int = 120
    private var examType: String? = null

    private val handler = Handler(Looper.getMainLooper())
    private var autoHideRunnable: Runnable? = null
    private var zoomIndicatorRunnable: Runnable? = null
    private var timerTickRunnable: Runnable? = null
    private var fitWidthMode = false

    // Timer
    private val timerManager = ExamTimerManager()
    private var selectedDurationMinutes: Int = 120

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        setContentView(R.layout.activity_main)

        // PDF views
        pdfViewer = findViewById(R.id.pdfViewer)
        pdfViewer.isFocusableInTouchMode = true
        pageIndicator = findViewById(R.id.pageIndicator)
        errorText = findViewById(R.id.errorText)
        helpHintText = findViewById(R.id.helpHintText)
        zoomIndicator = findViewById(R.id.zoomIndicator)
        loadingOverlay = findViewById(R.id.loadingOverlay)
        topBar = findViewById(R.id.topBar)
        helpOverlay = findViewById(R.id.helpOverlay)

        // Scroll buttons
        scrollButtons = findViewById(R.id.scrollButtons)
        btnScrollUp = findViewById(R.id.btnScrollUp)
        btnScrollDown = findViewById(R.id.btnScrollDown)
        btnScrollUp.setOnClickListener { pdfViewer.panUp(); showControls() }
        btnScrollDown.setOnClickListener { pdfViewer.panDown(); showControls() }

        // Back button (phone portrait layout)
        findViewById<View>(R.id.backButton)?.setOnClickListener { onBackPressed() }

        // Timer views — accessed directly from included layouts
        timerFloatingContainer = findViewById(R.id.timerFloatingBtn)
        timerFloatingIcon = findViewById(R.id.timerFloatingIcon)
        timerFloatingText = findViewById(R.id.timerFloatingText)

        timerPanelRoot = findViewById(R.id.timerPanelRoot)
        timerSubjectName = findViewById(R.id.timerSubjectName)
        timerRemainingTime = findViewById(R.id.timerRemainingTime)
        timerProgressBar = findViewById(R.id.timerProgressBar)
        timerAlertText = findViewById(R.id.timerAlertText)
        timerBtnPauseResume = findViewById(R.id.timerBtnPauseResume)
        timerBtnAdd5 = findViewById(R.id.timerBtnAdd5)
        timerBtnReset = findViewById(R.id.timerBtnReset)
        timerBtnClose = findViewById(R.id.timerBtnClose)
        timerBtnModify = findViewById(R.id.timerBtnModify)

        timerFinishedRoot = findViewById(R.id.timerFinishedRoot)
        timerFinishedAdd5 = findViewById(R.id.timerFinishedAdd5)
        timerFinishedFinish = findViewById(R.id.timerFinishedFinish)
        timerFinishedRestart = findViewById(R.id.timerFinishedRestart)

        timerDurationRoot = findViewById(R.id.timerDurationRoot)
        durationButtons = listOf(
            findViewById<TextView>(R.id.dur30),
            findViewById<TextView>(R.id.dur45),
            findViewById<TextView>(R.id.dur60),
            findViewById<TextView>(R.id.dur90),
            findViewById<TextView>(R.id.dur120)
        )
        durConfirm = findViewById(R.id.durConfirm)
        durCancel = findViewById(R.id.durCancel)

        setupTimerButtons()
        setupCallbacks()
        openPdf()
    }

    private fun setupTimerButtons() {
        // Floating button: starts timer if not running, or toggles panel if running
        timerFloatingContainer.setOnClickListener {
            Log.d(TAG, "TimerFloatingBtn clicked, isRunning=${timerManager.isRunning}")
            if (timerManager.isRunning) {
                toggleTimerPanel()
            } else {
                startNewTimer()
            }
        }
        timerFloatingContainer.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
                Log.d(TAG, "TimerFloatingBtn DPAD_CENTER, isRunning=${timerManager.isRunning}")
                if (timerManager.isRunning) {
                    toggleTimerPanel()
                } else {
                    startNewTimer()
                }
                true
            } else false
        }

        // Helper: add DPAD_CENTER listener to a button (for TV remote OK key)
        fun addTvKeyListener(btn: TextView, action: () -> Unit) {
            btn.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN &&
                    (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
                ) {
                    action()
                    true
                } else false
            }
        }

        // Panel buttons — Pause/Resume
        timerBtnPauseResume.setOnClickListener {
            Log.d(TAG, "timerBtnPauseResume clicked, isPaused=${timerManager.isPaused}")
            if (timerManager.isPaused) timerManager.resume() else timerManager.pause()
            saveTimerState()
            updateTimerUI()
        }
        addTvKeyListener(timerBtnPauseResume) {
            Log.d(TAG, "timerBtnPauseResume DPAD_CENTER, isPaused=${timerManager.isPaused}")
            if (timerManager.isPaused) timerManager.resume() else timerManager.pause()
            saveTimerState()
            updateTimerUI()
        }

        // +5 min
        timerBtnAdd5.setOnClickListener {
            Log.d(TAG, "timerBtnAdd5 clicked")
            timerManager.addMinutes(5)
            saveTimerState()
            updateTimerUI()
        }
        addTvKeyListener(timerBtnAdd5) {
            Log.d(TAG, "timerBtnAdd5 DPAD_CENTER")
            timerManager.addMinutes(5)
            saveTimerState()
            updateTimerUI()
        }

        // Modify (open duration picker)
        timerBtnModify.setOnClickListener {
            Log.d(TAG, "timerBtnModify clicked")
            openDurationPicker()
        }
        addTvKeyListener(timerBtnModify) {
            Log.d(TAG, "timerBtnModify DPAD_CENTER")
            openDurationPicker()
        }

        // Reset
        timerBtnReset.setOnClickListener {
            Log.d(TAG, "timerBtnReset clicked")
            timerManager.reset()
            clearTimerState()
            closeTimerPanel()
            updateTimerUI()
        }
        addTvKeyListener(timerBtnReset) {
            Log.d(TAG, "timerBtnReset DPAD_CENTER")
            timerManager.reset()
            clearTimerState()
            closeTimerPanel()
            updateTimerUI()
        }

        // Close
        timerBtnClose.setOnClickListener {
            Log.d(TAG, "timerBtnClose clicked")
            closeTimerPanel()
        }
        addTvKeyListener(timerBtnClose) {
            Log.d(TAG, "timerBtnClose DPAD_CENTER")
            closeTimerPanel()
        }

        // Finished overlay buttons
        timerFinishedAdd5.setOnClickListener {
            Log.d(TAG, "timerFinishedAdd5 clicked")
            timerManager.addMinutes(5)
            saveTimerState()
            hideFinishedOverlay()
            startTimerTick()
            updateTimerUI()
        }
        addTvKeyListener(timerFinishedAdd5) {
            Log.d(TAG, "timerFinishedAdd5 DPAD_CENTER")
            timerManager.addMinutes(5)
            saveTimerState()
            hideFinishedOverlay()
            startTimerTick()
            updateTimerUI()
        }

        timerFinishedFinish.setOnClickListener {
            Log.d(TAG, "timerFinishedFinish clicked")
            timerManager.stop()
            clearTimerState()
            hideFinishedOverlay()
            updateTimerUI()
        }
        addTvKeyListener(timerFinishedFinish) {
            Log.d(TAG, "timerFinishedFinish DPAD_CENTER")
            timerManager.stop()
            clearTimerState()
            hideFinishedOverlay()
            updateTimerUI()
        }

        timerFinishedRestart.setOnClickListener {
            Log.d(TAG, "timerFinishedRestart clicked")
            timerManager.start(selectedDurationMinutes)
            saveTimerState()
            hideFinishedOverlay()
            startTimerTick()
            updateTimerUI()
        }
        addTvKeyListener(timerFinishedRestart) {
            Log.d(TAG, "timerFinishedRestart DPAD_CENTER")
            timerManager.start(selectedDurationMinutes)
            saveTimerState()
            hideFinishedOverlay()
            startTimerTick()
            updateTimerUI()
        }

        // Duration picker buttons
        val durations = intArrayOf(30, 45, 60, 90, 120)
        for ((i, btn) in durationButtons.withIndex()) {
            btn.setOnClickListener {
                Log.d(TAG, "durationButtons[$i] clicked: ${durations[i]}min")
                selectedDurationMinutes = durations[i]
                closeDurationPicker()
                timerManager.start(selectedDurationMinutes)
                saveTimerState()
                startTimerTick()
                closeTimerPanel()
                updateTimerUI()
            }
            addTvKeyListener(btn) {
                Log.d(TAG, "durationButtons[$i] DPAD_CENTER: ${durations[i]}min")
                selectedDurationMinutes = durations[i]
                closeDurationPicker()
                timerManager.start(selectedDurationMinutes)
                saveTimerState()
                startTimerTick()
                closeTimerPanel()
                updateTimerUI()
            }
        }
        durConfirm.setOnClickListener {
            Log.d(TAG, "durConfirm clicked: ${selectedDurationMinutes}min")
            closeDurationPicker()
            timerManager.start(selectedDurationMinutes)
            saveTimerState()
            startTimerTick()
            closeTimerPanel()
            updateTimerUI()
        }
        addTvKeyListener(durConfirm) {
            Log.d(TAG, "durConfirm DPAD_CENTER: ${selectedDurationMinutes}min")
            closeDurationPicker()
            timerManager.start(selectedDurationMinutes)
            saveTimerState()
            startTimerTick()
            closeTimerPanel()
            updateTimerUI()
        }
        durCancel.setOnClickListener {
            Log.d(TAG, "durCancel clicked")
            closeDurationPicker()
        }
        addTvKeyListener(durCancel) {
            Log.d(TAG, "durCancel DPAD_CENTER")
            closeDurationPicker()
        }
    }

    private fun setupCallbacks() {
        pdfViewer.onPageChanged = { page, total ->
            pageIndicator.text = getString(R.string.page_indicator, page, total)
            if (controlsVisible) pageIndicator.visibility = View.VISIBLE
            rescheduleAutoHide()
            examId?.let {
                ExamRepository.saveReadingProgress(it, page - 1, subjectId, examType)
            }
        }

        pdfViewer.onError = { message ->
            Log.e(TAG, "Erreur: $message")
            errorText.text = message
            errorText.visibility = View.VISIBLE
            pageIndicator.visibility = View.GONE
            helpHintText.visibility = View.GONE
            timerFloatingContainer.visibility = View.GONE
        }

        pdfViewer.onLoadingChanged = { loading ->
            loadingOverlay.visibility = if (loading) View.VISIBLE else View.GONE
        }

        pdfViewer.onZoomChanged = { percent -> showZoomIndicator(percent) }
    }

    private fun openPdf() {
        try {
            val pdfPath = intent.getStringExtra("pdf_path")
            examId = intent.getStringExtra("exam_id")
            subjectId = intent.getStringExtra("subject_id")
            examType = intent.getStringExtra("exam_type")
            defaultDurationMinutes = intent.getIntExtra("default_duration", 120)
            selectedDurationMinutes = defaultDurationMinutes

            val startPage = intent.getIntExtra("start_page", 0)

            if (pdfPath == null) {
                errorText.text = getString(R.string.error_pdf_not_found)
                errorText.visibility = View.VISIBLE
                return
            }

            val file = File(pdfPath)
            if (!file.exists()) {
                errorText.text = getString(R.string.error_pdf_not_found)
                errorText.visibility = View.VISIBLE
                return
            }

            Log.d(TAG, "Ouverture PDF: ${file.absolutePath}, size=${file.length()}")
            pdfViewer.openPdfFile(file)

            if (startPage > 0) {
                pdfViewer.goToPage(startPage)
            }

            // Setup timer: only for SUJET exams
            try {
                val isSujet = examType == "SUJET" || examType == "sujet"
                if (isSujet) {
                    setupTimer()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erreur setup timer: ${e.message}", e)
            }

            pdfViewer.requestFocus()
            scheduleAutoHide()
        } catch (e: Exception) {
            Log.e(TAG, "Erreur openPdf: ${e.message}", e)
            errorText.text = "Erreur: ${e.message}"
            errorText.visibility = View.VISIBLE
        }
    }

    // ================================================================
    //  TIMER
    // ================================================================

    private fun setupTimer() {
        // Try to restore saved state
        val examIdVal = examId ?: return
        val prefs = ExamRepository.getTimerPrefs(this)
        val restored = timerManager.restoreState(prefs, examIdVal)

        if (restored && timerManager.isRunning) {
            startTimerTick()
        }

        updateTimerUI()
        timerFloatingContainer.visibility = View.VISIBLE
    }

    private fun startNewTimer() {
        Log.d(TAG, "startNewTimer() duration=${selectedDurationMinutes}min")
        timerManager.start(selectedDurationMinutes)
        saveTimerState()
        startTimerTick()
        updateTimerUI()
    }

    private fun toggleTimerPanel() {
        if (timerPanelVisible) {
            closeTimerPanel()
        } else {
            openTimerPanel()
        }
    }

    private fun openTimerPanel() {
        Log.d(TAG, "openTimerPanel()")
        timerPanelVisible = true
        timerPanelRoot.visibility = View.VISIBLE
        timerFloatingContainer.visibility = View.GONE
        autoHideRunnable?.let { handler.removeCallbacks(it) }
        updateTimerUI()
        // Focus first button, not the root FrameLayout
        timerBtnPauseResume.requestFocus()
        Log.d(TAG, "openTimerPanel() focus requested on timerBtnPauseResume")
    }

    private fun closeTimerPanel() {
        Log.d(TAG, "closeTimerPanel()")
        timerPanelVisible = false
        timerPanelRoot.visibility = View.GONE
        // Show floating button again (only if timer has been started)
        if (timerManager.isRunning) {
            timerFloatingContainer.visibility = View.VISIBLE
        }
        pdfViewer.requestFocus()
        scheduleAutoHide()
    }

    private fun openDurationPicker() {
        Log.d(TAG, "openDurationPicker()")
        durationPickerVisible = true
        timerDurationRoot.visibility = View.VISIBLE
        // Focus first duration button, not root
        if (durationButtons.isNotEmpty()) {
            durationButtons[0].requestFocus()
        }
    }

    private fun closeDurationPicker() {
        Log.d(TAG, "closeDurationPicker()")
        durationPickerVisible = false
        timerDurationRoot.visibility = View.GONE
        // Return focus to the Modify button
        if (timerPanelVisible) {
            timerBtnModify.requestFocus()
        } else {
            pdfViewer.requestFocus()
        }
    }

    private fun showFinishedOverlay() {
        Log.d(TAG, "showFinishedOverlay()")
        finishedOverlayVisible = true
        timerFinishedRoot.visibility = View.VISIBLE
        timerFloatingContainer.visibility = View.GONE
        // Focus first button, not root
        timerFinishedAdd5.requestFocus()
    }

    private fun hideFinishedOverlay() {
        Log.d(TAG, "hideFinishedOverlay()")
        finishedOverlayVisible = false
        timerFinishedRoot.visibility = View.GONE
        // Show floating button again if timer is running
        if (timerManager.isRunning) {
            timerFloatingContainer.visibility = View.VISIBLE
        }
        pdfViewer.requestFocus()
    }

    private fun startTimerTick() {
        timerTickRunnable?.let { handler.removeCallbacks(it) }
        timerTickRunnable = object : Runnable {
            override fun run() {
                updateTimerUI()

                if (timerManager.isFinished() && timerManager.isRunning) {
                    timerManager.pause()
                    showFinishedOverlay()
                    saveTimerState()
                    return
                }

                updateTimerAlerts()

                if (timerManager.isRunning) {
                    handler.postDelayed(this, TIMER_TICK_INTERVAL)
                }
            }
        }
        handler.post(timerTickRunnable!!)
    }

    private fun updateTimerAlerts() {
        when (timerManager.getAlertLevel()) {
            AlertLevel.FINISHED -> { }
            AlertLevel.WARNING_1 -> {
                timerAlertText.text = getString(R.string.timer_1min)
                timerAlertText.setTextColor(ContextCompat.getColor(this, R.color.younes_gold))
                timerAlertText.visibility = View.VISIBLE
            }
            AlertLevel.WARNING_5 -> {
                timerAlertText.text = getString(R.string.timer_5min)
                timerAlertText.setTextColor(ContextCompat.getColor(this, R.color.younes_gold))
                timerAlertText.visibility = View.VISIBLE
            }
            AlertLevel.WARNING_15 -> {
                timerAlertText.text = getString(R.string.timer_15min)
                timerAlertText.setTextColor(ContextCompat.getColor(this, R.color.younes_text_secondary))
                timerAlertText.visibility = View.VISIBLE
            }
            AlertLevel.NONE -> {
                timerAlertText.visibility = View.GONE
            }
        }
    }

    private fun updateTimerUI() {
        if (!timerManager.isRunning) {
            // Not started yet — show floating button with "Démarrer"
            timerFloatingText.text = getString(R.string.timer_start)
            timerRemainingTime.text = formatDuration(selectedDurationMinutes)
            timerProgressBar.progress = 100
            timerBtnPauseResume.text = getString(R.string.timer_pause)
            if (!timerPanelVisible) {
                timerFloatingContainer.visibility = View.VISIBLE
            }
            return
        }

        val remaining = timerManager.getFormattedRemaining()
        timerFloatingText.text = remaining

        if (timerPanelVisible) {
            timerRemainingTime.text = remaining
            timerProgressBar.progress = (timerManager.getProgress() * 100).toInt()

            if (timerManager.isPaused) {
                timerBtnPauseResume.text = getString(R.string.timer_resume)
            } else {
                timerBtnPauseResume.text = getString(R.string.timer_pause)
            }
        }

        // Update floating button border color based on alert
        when (timerManager.getAlertLevel()) {
            AlertLevel.FINISHED, AlertLevel.WARNING_1 -> {
                timerFloatingContainer.setBackgroundResource(R.drawable.timer_danger_background)
            }
            AlertLevel.WARNING_5 -> {
                timerFloatingContainer.setBackgroundResource(R.drawable.timer_warning_background)
            }
            else -> {
                timerFloatingContainer.setBackgroundResource(R.drawable.button_selector)
            }
        }
    }

    private fun formatDuration(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return String.format("%02d:%02d:00", h, m)
    }

    private fun saveTimerState() {
        val examIdVal = examId ?: return
        val prefs = ExamRepository.getTimerPrefs(this)
        timerManager.saveState(prefs, examIdVal)
    }

    private fun clearTimerState() {
        val examIdVal = examId ?: return
        val prefs = ExamRepository.getTimerPrefs(this)
        timerManager.clearState(prefs, examIdVal)
    }

    // ================================================================
    //  INTERFACE MASQUABLE
    // ================================================================

    private fun showControls() {
        controlsVisible = true
        topBar.visibility = View.VISIBLE
        pageIndicator.visibility = View.VISIBLE
        scrollButtons.visibility = if (pdfViewer.isZoomed) View.VISIBLE else View.GONE
        helpHintText.visibility = View.GONE
        rescheduleAutoHide()
    }

    private fun hideControls() {
        if (timerPanelVisible || durationPickerVisible || finishedOverlayVisible) return
        controlsVisible = false
        topBar.visibility = View.GONE
        pageIndicator.visibility = View.GONE
        scrollButtons.visibility = if (pdfViewer.isZoomed) View.VISIBLE else View.GONE
        helpHintText.visibility = View.VISIBLE
        autoHideRunnable?.let { handler.removeCallbacks(it) }
    }

    private fun scheduleAutoHide() {
        autoHideRunnable?.let { handler.removeCallbacks(it) }
        if (controlsVisible) {
            autoHideRunnable = Runnable {
                hideControls()
                pdfViewer.requestFocus()
            }
            handler.postDelayed(autoHideRunnable!!, AUTO_HIDE_DELAY)
        }
    }

    private fun rescheduleAutoHide() {
        autoHideRunnable?.let { handler.removeCallbacks(it) }
        if (controlsVisible) scheduleAutoHide()
    }

    private fun showZoomIndicator(percent: Int) {
        zoomIndicator.text = getString(R.string.zoom_format, percent)
        zoomIndicator.visibility = View.VISIBLE
        zoomIndicatorRunnable?.let { handler.removeCallbacks(it) }
        zoomIndicatorRunnable = Runnable { zoomIndicator.visibility = View.GONE }
        handler.postDelayed(zoomIndicatorRunnable!!, ZOOM_INDICATOR_DELAY)
    }

    private fun toggleHelp() {
        helpVisible = !helpVisible
        helpOverlay.visibility = if (helpVisible) View.VISIBLE else View.GONE
        if (helpVisible) {
            helpOverlay.requestFocus()
            autoHideRunnable?.let { handler.removeCallbacks(it) }
        } else {
            pdfViewer.requestFocus()
            scheduleAutoHide()
        }
    }

    // ================================================================
    //  TOUCHES TELECOMMANDE
    // ================================================================

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Finished overlay: any key except focus nav
        if (finishedOverlayVisible) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    return super.onKeyDown(keyCode, event)
                }
                KeyEvent.KEYCODE_BACK -> {
                    hideFinishedOverlay()
                    return true
                }
            }
            return super.onKeyDown(keyCode, event)
        }

        // Duration picker: handle navigation, back closes
        if (durationPickerVisible) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    return super.onKeyDown(keyCode, event)
                }
                KeyEvent.KEYCODE_BACK -> {
                    closeDurationPicker()
                    return true
                }
            }
            return super.onKeyDown(keyCode, event)
        }

        // Help overlay
        if (helpVisible) {
            toggleHelp()
            return true
        }

        if (keyCode == KeyEvent.KEYCODE_MENU ||
            keyCode == KeyEvent.KEYCODE_INFO ||
            keyCode == KeyEvent.KEYCODE_GUIDE ||
            keyCode == KeyEvent.KEYCODE_H
        ) {
            toggleHelp()
            return true
        }

        // Timer panel: handle navigation, back closes panel
        if (timerPanelVisible) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    return super.onKeyDown(keyCode, event)
                }
                KeyEvent.KEYCODE_BACK -> {
                    closeTimerPanel()
                    return true
                }
            }
            return super.onKeyDown(keyCode, event)
        }

        // Media play/pause: toggle timer pause
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE && timerManager.isRunning) {
            if (timerManager.isPaused) timerManager.resume() else timerManager.pause()
            saveTimerState()
            updateTimerUI()
            return true
        }

        // D-pad up/down = zoom (always), left/right = pan when zoomed, pages when not
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> { pdfViewer.zoomIn(); showControls(); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { pdfViewer.zoomOut(); showControls(); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (pdfViewer.isZoomed) pdfViewer.panLeft() else pdfViewer.previousPage()
                showControls(); return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (pdfViewer.isZoomed) pdfViewer.panRight() else pdfViewer.nextPage()
                showControls(); return true
            }
        }

        when (keyCode) {
            KeyEvent.KEYCODE_PAGE_UP -> { pdfViewer.previousPage(); showControls(); return true }
            KeyEvent.KEYCODE_PAGE_DOWN -> { pdfViewer.nextPage(); showControls(); return true }
            KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_NUMPAD_ADD -> { pdfViewer.zoomIn(); showControls(); return true }
            KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> { pdfViewer.zoomOut(); showControls(); return true }
            KeyEvent.KEYCODE_CHANNEL_UP -> {
                if (pdfViewer.isZoomed) pdfViewer.panUp() else pdfViewer.previousPage()
                showControls(); return true
            }
            KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                if (pdfViewer.isZoomed) pdfViewer.panDown() else pdfViewer.nextPage()
                showControls(); return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                val isSujet = examType.equals("SUJET", ignoreCase = true)
                if (isSujet) {
                    if (timerManager.isRunning) openTimerPanel() else openDurationPicker()
                } else {
                    fitWidthMode = !fitWidthMode
                    if (fitWidthMode) pdfViewer.fitToWidth() else pdfViewer.fitToPage()
                    showControls()
                }
                return true
            }
        }

        return super.onKeyDown(keyCode, event)
    }

    override fun onBackPressed() {
        when {
            finishedOverlayVisible -> hideFinishedOverlay()
            durationPickerVisible -> closeDurationPicker()
            timerPanelVisible -> closeTimerPanel()
            helpVisible -> toggleHelp()
            else -> {
                saveTimerState()
                pdfViewer.closePdf()
                super.onBackPressed()
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        timerTickRunnable = null
        pdfViewer.closePdf()
        super.onDestroy()
    }
}
