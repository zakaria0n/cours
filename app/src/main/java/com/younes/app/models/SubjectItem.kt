package com.younes.app.models

import androidx.annotation.DrawableRes

data class SubjectItem(
    val id: String,
    val nameAr: String,
    val nameFr: String,
    val alloSchoolSectionId: String,
    @DrawableRes val iconRes: Int,
    val colorHex: String,
    val defaultDurationMinutes: Int = 120
)
