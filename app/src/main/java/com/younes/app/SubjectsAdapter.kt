package com.younes.app

import android.animation.ValueAnimator
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.younes.app.models.SubjectItem

class SubjectsAdapter(
    private val onClick: (SubjectItem) -> Unit
) : ListAdapter<SubjectItem, SubjectViewHolder>(SubjectDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SubjectViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_subject_card, parent, false)
        return SubjectViewHolder(view)
    }

    override fun onBindViewHolder(holder: SubjectViewHolder, position: Int) {
        holder.bind(getItem(position), onClick)
    }
}

class SubjectViewHolder(view: View) : RecyclerView.ViewHolder(view) {
    private val icon: ImageView = view.findViewById(R.id.subjectIcon)
    private val nameAr: TextView = view.findViewById(R.id.subjectNameAr)
    private val nameFr: TextView = view.findViewById(R.id.subjectNameFr)
    private val duration: TextView = view.findViewById(R.id.subjectDuration)
    private val accentLine: View = view.findViewById(R.id.subjectAccentLine)
    private val card: View = view.findViewById(R.id.subjectCard)

    private var currentAnimator: ValueAnimator? = null

    init {
        card.setOnFocusChangeListener { v, hasFocus ->
            currentAnimator?.cancel()
            val scale = if (hasFocus) 1.04f else 1.0f
            currentAnimator = ValueAnimator.ofFloat(v.scaleX, scale).apply {
                duration = 150
                addUpdateListener {
                    val s = it.animatedValue as Float
                    v.scaleX = s
                    v.scaleY = s
                }
                start()
            }

            // Accent line color on focus
            val ctx = v.context
            if (hasFocus) {
                accentLine.setBackgroundColor(ContextCompat.getColor(ctx, R.color.younes_cyan))
                v.elevation = 8f
            } else {
                accentLine.setBackgroundColor(ContextCompat.getColor(ctx, R.color.younes_border))
                v.elevation = 2f
            }
        }
    }

    fun bind(subject: SubjectItem, onClick: (SubjectItem) -> Unit) {
        icon.setImageResource(subjectIconRes(subject.id))
        nameAr.text = subject.nameAr
        nameFr.text = subject.nameFr

        val durationText = if (subject.defaultDurationMinutes >= 60) {
            "${subject.defaultDurationMinutes / 60}h"
        } else {
            "${subject.defaultDurationMinutes}min"
        }
        duration.text = durationText

        card.setOnClickListener { onClick(subject) }
        card.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                onClick(subject)
                true
            } else {
                false
            }
        }
    }

    private fun subjectIconRes(id: String): Int {
        return when {
            id.contains("french", ignoreCase = true) || id.contains("francais", ignoreCase = true) || id.contains("français", ignoreCase = true) -> R.drawable.ic_subject_french
            id.contains("math", ignoreCase = true) -> R.drawable.ic_subject_math
            id.contains("arabic", ignoreCase = true) || id.contains("arabe", ignoreCase = true) -> R.drawable.ic_subject_arabic
            id.contains("phys", ignoreCase = true) || id.contains("science", ignoreCase = true) -> R.drawable.ic_subject_physics
            id.contains("islamic", ignoreCase = true) -> R.drawable.ic_subject_islamic
            id.contains("ijtima3iyat", ignoreCase = true) -> R.drawable.ic_subject_social
            else -> R.drawable.ic_subject_social
        }
    }
}

class SubjectDiffCallback : DiffUtil.ItemCallback<SubjectItem>() {
    override fun areItemsTheSame(a: SubjectItem, b: SubjectItem) = a.id == b.id
    override fun areContentsTheSame(a: SubjectItem, b: SubjectItem) = a == b
}
