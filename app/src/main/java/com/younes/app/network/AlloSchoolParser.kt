package com.younes.app.network

import android.util.Log
import com.younes.app.models.ExamPdf
import com.younes.app.models.ExamType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

private const val TAG = "AlloSchoolParser"
private const val BASE_URL = "https://www.alloschool.com"

object AlloSchoolParser {

    /**
     * Charge et parse la page section d'une matière.
     * Retourne la liste des examens trouvés.
     */
    suspend fun parseSectionPage(sectionId: String, subjectId: String): List<ExamPdf> =
        withContext(Dispatchers.IO) {
            try {
                val url = "$BASE_URL/section/$sectionId"
                Log.d(TAG, "Chargement section: $url")
                val doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0")
                    .timeout(15000)
                    .get()

                parseSectionHtml(doc, subjectId)
            } catch (e: Exception) {
                Log.e(TAG, "Erreur section $sectionId: ${e.message}", e)
                emptyList()
            }
        }

    /**
     * Parse le HTML d'une page section pour extraire les examens.
     */
    private fun parseSectionHtml(doc: Document, subjectId: String): List<ExamPdf> {
        // Structure AlloSchool : <ul class="section-elements"> contient des <li>
        // Les examens sont dans <a class="er" href="https://.../element/XXXXX">
        // Les en-têtes d'année ont href="#!" → à ignorer
        val links = doc.select("ul.section-elements a.er[href*=/element/]")

        if (links.isEmpty()) {
            Log.w(TAG, "Aucun lien exam trouvé, essai sélecteur fallback")
            val fallback = doc.select("a[href*=/element/]")
            return parseExamLinks(fallback, subjectId)
        }

        return parseExamLinks(links, subjectId)
    }

    private fun parseExamLinks(elements: org.jsoup.select.Elements, subjectId: String): List<ExamPdf> {
        val exams = mutableListOf<ExamPdf>()

        for (el in elements) {
            val href = el.attr("href").trim()
            val title = el.text().trim()

            // Ignorer les en-têtes d'année (href="#!")
            if (href == "#!" || href.isBlank() || title.isBlank()) continue

            // L'URL est absolue : "https://www.alloschool.com/element/XXXXX"
            val elementId = href.substringAfterLast("/")
            val type = detectExamType(title)
            val year = detectYear(title)
            val region = detectRegion(title)

            if (year > 0 && elementId.isNotBlank()) {
                exams.add(
                    ExamPdf(
                        id = "exam_$elementId",
                        elementUrl = href,
                        title = title,
                        year = year,
                        region = region,
                        type = type,
                        subjectId = subjectId
                    )
                )
            }
        }

        Log.d(TAG, "${exams.size} examens trouvés dans la section")
        return exams.sortedByDescending { it.year }
    }

    /**
     * Charge la page d'un élément et extrait l'URL du PDF.
     */
    suspend fun extractPdfUrl(elementUrl: String): String =
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Chargement élément: $elementUrl")
                val doc = Jsoup.connect(elementUrl)
                    .userAgent("Mozilla/5.0")
                    .timeout(15000)
                    .get()

                // Chercher le lien de téléchargement du PDF
                val pdfLink = doc.select("a[href^=https://storage.googleapis.com]").first()
                    ?: doc.select("a:contains(Téléchargez)").first()
                    ?: doc.select("a[href]").firstOrNull { a ->
                        a.attr("href").contains(".pdf", ignoreCase = true)
                    }

                pdfLink?.attr("abs:href") ?: ""
            } catch (e: Exception) {
                Log.e(TAG, "Erreur extraction PDF: ${e.message}", e)
                ""
            }
        }

    private fun detectExamType(title: String): ExamType {
        return when {
            title.contains("الموضوع", ignoreCase = true) ||
            title.contains("Sujet", ignoreCase = true) -> ExamType.SUJET
            title.contains("التصحيح", ignoreCase = true) ||
            title.contains("Correction", ignoreCase = true) -> ExamType.CORRECTION
            else -> ExamType.SUJET
        }
    }

    private fun detectYear(title: String): Int {
        val yearRegex = Regex("(20\\d{2})")
        val match = yearRegex.findAll(title).lastOrNull() ?: return 0
        return match.groupValues[1].toIntOrNull() ?: 0
    }

    private val REGION_KEYWORDS = listOf(
        "الدار البيضاء" to "الدار البيضاء سطات",
        "الرباط" to "الرباط سلا القنيطرة",
        "فاس" to "فاس مكناس",
        "وجدة" to "الشرق",
        "مراكش" to "مراكش آسفي",
        "أكادير" to "سوس ماسة",
        "ورزازات" to "درعة تافيلالت",
        "بني ملال" to "بني ملال خنيفرة",
        "العيون" to "العيون الساقية الحمراء",
        "طنجة" to "طنجة تطوان الحسيمة",
        "الكتاتبي" to "الجهوية الموحدة"
    )

    private fun detectRegion(title: String): String {
        // Mots-clés arabes
        for ((keyword, region) in REGION_KEYWORDS) {
            if (title.contains(keyword)) return region
        }
        // Mots-clés français dans le titre
        if (title.contains("Examen national", ignoreCase = true) ||
            title.contains("الامتحان الوطني", ignoreCase = true)) {
            return "الامتحان الوطني"
        }
        // Extraction depuis les parenthèses (format français : "Examen régional (Région) - Sujet")
        val parenMatch = Regex("\\(([^)]+)\\)").find(title)
        if (parenMatch != null) {
            return parenMatch.groupValues[1].trim()
        }
        return "غير محدد"
    }
}
