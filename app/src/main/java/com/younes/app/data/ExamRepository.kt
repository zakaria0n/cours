package com.younes.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.younes.app.models.ExamPdf
import com.younes.app.models.ExamType
import com.younes.app.models.SubjectItem
import com.younes.app.network.AlloSchoolParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "ExamRepository"
private const val PREFS_NAME = "younes_prefs"
private const val KEY_FAVORITES = "favorites"
private const val KEY_CONTINUE_READING = "continue_reading"
private const val CACHE_DIR = "exam_cache"
private const val EXAMS_CACHE_FILE = "exams_cache.json"

object ExamRepository {

    private lateinit var appContext: Context
    private lateinit var prefs: SharedPreferences
    private val gson = Gson()

    // Cache en mémoire
    private val examsCache = ConcurrentHashMap<String, MutableList<ExamPdf>>()

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getTimerPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // =============================================
    //  MATIÈRES
    // =============================================

    fun getSubjects(): List<SubjectItem> = listOf(
        SubjectItem("french", "اللغة الفرنسية", "Français", "5891",
            0, "#00D4F2", 120),
        SubjectItem("math", "الرياضيات", "Mathématiques", "6357",
            0, "#00D4F2", 120),
        SubjectItem("arabic", "اللغة العربية", "Arabe", "5890",
            0, "#00D4F2", 120),
        SubjectItem("physics", "العلوم الفيزيائية", "Physique-Chimie", "6817",
            0, "#00D4F2", 60),
        SubjectItem("islamic", "التربية الإسلامية", "Éducation Islamique", "4486",
            0, "#00D4F2", 60),
        SubjectItem("ijtima3iyat", "الاجتماعيات", "Ijtima3iyat", "5892",
            0, "#00D4F2", 60)
    )

    // =============================================
    //  EXAMENS
    // =============================================

    suspend fun loadExams(subjectId: String): List<ExamPdf> =
        withContext(Dispatchers.IO) {
            // Vérifier cache mémoire
            examsCache[subjectId]?.let { return@withContext it }

            // Vérifier cache fichier
            val cached = loadExamsFromCache(subjectId)
            if (cached.isNotEmpty()) {
                examsCache[subjectId] = cached.toMutableList()
                return@withContext cached
            }

            // Charger depuis AlloSchool
            val subjects = getSubjects()
            val subject = subjects.find { it.id == subjectId } ?: return@withContext emptyList()
            val exams = AlloSchoolParser.parseSectionPage(subject.alloSchoolSectionId, subjectId)

            if (exams.isNotEmpty()) {
                saveExamsToCache(subjectId, exams)
                examsCache[subjectId] = exams.toMutableList()
            }

            exams
        }

    suspend fun getPdfUrl(exam: ExamPdf): String =
        withContext(Dispatchers.IO) {
            if (exam.pdfUrl.isNotBlank()) return@withContext exam.pdfUrl
            AlloSchoolParser.extractPdfUrl(exam.elementUrl)
        }

    // =============================================
    //  FAVORIS
    // =============================================

    fun getFavorites(): Set<String> {
        return prefs.getStringSet(KEY_FAVORITES, emptySet()) ?: emptySet()
    }

    fun isFavorite(examId: String): Boolean = getFavorites().contains(examId)

    fun toggleFavorite(examId: String) {
        val favs = getFavorites().toMutableSet()
        if (favs.contains(examId)) favs.remove(examId) else favs.add(examId)
        prefs.edit().putStringSet(KEY_FAVORITES, favs).apply()
    }

    // =============================================
    //  CONTINUER LA LECTURE
    // =============================================

    data class ReadingProgress(
        val examId: String,
        val pageIndex: Int,
        val subjectId: String? = null,
        val examType: String? = null,
        val timestamp: Long = System.currentTimeMillis()
    )

    fun saveReadingProgress(
        examId: String,
        pageIndex: Int,
        subjectId: String? = null,
        examType: String? = null
    ) {
        val progress = ReadingProgress(examId, pageIndex, subjectId, examType)
        prefs.edit().putString(KEY_CONTINUE_READING, gson.toJson(progress)).apply()
    }

    fun getReadingProgress(): ReadingProgress? {
        val json = prefs.getString(KEY_CONTINUE_READING, null) ?: return null
        return try { gson.fromJson(json, ReadingProgress::class.java) } catch (_: Exception) { null }
    }

    // =============================================
    //  CACHE FICHIER
    // =============================================

    private fun getCacheDir(): File {
        val dir = File(appContext.cacheDir, CACHE_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun saveExamsToCache(subjectId: String, exams: List<ExamPdf>) {
        try {
            val file = File(getCacheDir(), "${subjectId}_$EXAMS_CACHE_FILE")
            file.writeText(gson.toJson(exams))
        } catch (e: Exception) {
            Log.e(TAG, "Erreur cache: ${e.message}")
        }
    }

    private fun loadExamsFromCache(subjectId: String): List<ExamPdf> {
        return try {
            val file = File(getCacheDir(), "${subjectId}_$EXAMS_CACHE_FILE")
            if (!file.exists()) return emptyList()
            val type = object : TypeToken<List<ExamPdf>>() {}.type
            gson.fromJson<List<ExamPdf>>(file.readText(), type) ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Erreur lecture cache: ${e.message}")
            emptyList()
        }
    }

    fun getDownloadedExamFile(examId: String): File {
        return File(getCacheDir(), "${examId}.pdf")
    }

    fun isExamDownloaded(examId: String): Boolean {
        return getDownloadedExamFile(examId).exists()
    }
}
