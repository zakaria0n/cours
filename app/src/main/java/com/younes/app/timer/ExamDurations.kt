package com.younes.app.timer

object ExamDurations {
    const val FRENCH = 120
    const val MATH = 120
    const val ARABIC = 120
    const val PHYSICS = 60
    const val ISLAMIC = 60
    const val IJTIMA3IYAT = 60
    const val DEFAULT = 60

    fun getDuration(subjectId: String): Int {
        return when (subjectId) {
            "french" -> FRENCH
            "math" -> MATH
            "arabic" -> ARABIC
            "physics" -> PHYSICS
            "islamic" -> ISLAMIC
            "ijtima3iyat" -> IJTIMA3IYAT
            else -> DEFAULT
        }
    }
}
