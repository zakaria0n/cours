package com.younes.app

import android.animation.ValueAnimator
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.younes.app.data.ExamRepository
import com.younes.app.models.ExamPdf
import com.younes.app.models.ExamType

class ExamAdapter(
    private val onExamClick: (ExamPdf) -> Unit,
    private val onFavoriteClick: (ExamPdf) -> Unit
) : ListAdapter<ExamPdf, ExamViewHolder>(ExamDiffCallback()) {

    fun refreshExam(examId: String) {
        val index = currentList.indexOfFirst { it.id == examId }
        if (index >= 0) notifyItemChanged(index)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ExamViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_exam_card, parent, false)
        return ExamViewHolder(view)
    }

    override fun onBindViewHolder(holder: ExamViewHolder, position: Int) {
        holder.bind(getItem(position), onExamClick, onFavoriteClick)
    }
}

class ExamViewHolder(view: View) : RecyclerView.ViewHolder(view) {
    private val card: View = view.findViewById(R.id.examCard)
    private val typeBadge: TextView = view.findViewById(R.id.examTypeBadge)
    private val title: TextView = view.findViewById(R.id.examTitle)
    private val year: TextView = view.findViewById(R.id.examYear)
    private val region: TextView = view.findViewById(R.id.examRegion)
    private val downloadedIcon: TextView = view.findViewById(R.id.downloadedIcon)
    private val favoriteIcon: TextView = view.findViewById(R.id.favoriteIcon)

    private var currentAnimator: ValueAnimator? = null

    init {
        card.setOnFocusChangeListener { v, hasFocus ->
            currentAnimator?.cancel()
            val scale = if (hasFocus) 1.05f else 1.0f
            currentAnimator = ValueAnimator.ofFloat(v.scaleX, scale).apply {
                duration = 150
                addUpdateListener {
                    val s = it.animatedValue as Float
                    v.scaleX = s
                    v.scaleY = s
                }
                start()
            }
        }
    }

    fun bind(exam: ExamPdf, onExamClick: (ExamPdf) -> Unit, onFavoriteClick: (ExamPdf) -> Unit) {
        // Type badge - different background and text color per type
        val context = card.context
        when (exam.type) {
            ExamType.SUJET -> {
                typeBadge.text = context.getString(R.string.type_sujet)
                typeBadge.setBackgroundResource(R.drawable.badge_sujet)
                typeBadge.setTextColor(ContextCompat.getColor(context, R.color.younes_white))
            }
            ExamType.CORRECTION -> {
                typeBadge.text = context.getString(R.string.type_correction)
                typeBadge.setBackgroundResource(R.drawable.badge_correction)
                typeBadge.setTextColor(ContextCompat.getColor(context, R.color.younes_navy_dark))
            }
        }

        title.text = exam.title
        year.text = exam.year.toString()
        region.text = exam.region

        // Downloaded state
        val isDownloaded = ExamRepository.isExamDownloaded(exam.id)
        downloadedIcon.visibility = if (isDownloaded) View.VISIBLE else View.GONE

        // Favorite state
        val isFav = ExamRepository.isFavorite(exam.id)
        favoriteIcon.visibility = if (isFav) View.VISIBLE else View.GONE

        // Click listeners
        card.setOnClickListener { onExamClick(exam) }
        card.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER -> {
                        onExamClick(exam)
                        true
                    }
                    KeyEvent.KEYCODE_ENTER -> {
                        onExamClick(exam)
                        true
                    }
                    KeyEvent.KEYCODE_PROG_RED, KeyEvent.KEYCODE_F, KeyEvent.KEYCODE_STAR,
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_BOOKMARK -> {
                        onFavoriteClick(exam)
                        true
                    }
                    else -> false
                }
            } else false
        }
    }
}

class ExamDiffCallback : DiffUtil.ItemCallback<ExamPdf>() {
    override fun areItemsTheSame(a: ExamPdf, b: ExamPdf) = a.id == b.id
    override fun areContentsTheSame(a: ExamPdf, b: ExamPdf) = a == b
}
