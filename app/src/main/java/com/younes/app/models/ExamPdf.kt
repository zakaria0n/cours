package com.younes.app.models

data class ExamPdf(
    val id: String,
    val elementUrl: String,          // ex: https://allocours.com/element/82748
    val title: String,               // titre complet arabe
    val year: Int,
    val region: String,              // nom de région en arabe
    val type: ExamType,              // SUJET ou CORRECTION
    val subjectId: String,
    val pdfUrl: String = "",         // rempli après parsing de la page élément
    val localPath: String = "",      // chemin local si téléchargé
    val downloaded: Boolean = false,
    val downloadProgress: Int = 0,   // 0-100
    val fileSize: Long = 0           // en bytes
)

enum class ExamType {
    SUJET, CORRECTION
}
