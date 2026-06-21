package com.younes.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.AttributeSet
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

private const val TAG = "YounesPDF"

/**
 * Vue personnalisée pour afficher un PDF page par page via PdfRenderer.
 * Supporte télécommande TV et touch téléphone.
 */
class PdfViewerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // --- État PDF ---
    private var pdfRenderer: PdfRenderer? = null
    private var parcelFd: ParcelFileDescriptor? = null
    private var currentBitmap: Bitmap? = null
    private var _pageIndex = 0
    private var _pageCount = 0

    // --- Zoom ---
    private var _zoom = 1.0f
    private var _fitScale = 1.0f
    private var _fitWidthScale = 1.0f
    private val ZOOM_MIN = 0.5f
    private val ZOOM_MAX = 4.0f
    private val ZOOM_STEP = 0.25f

    // --- Pan (déplacement) ---
    private var _panX = 0f
    private var _panY = 0f
    private val PAN_STEP = 0.25f

    // --- Touch smooth zoom ---
    private var _touchScale = 1.0f       // visual scale during pinch (1.0 = no pinch)
    private var _pinchFocusX = 0f
    private var _pinchFocusY = 0f
    private var _isPinching = false
    private var _pendingZoomRender = false

    // --- État interne ---
    private var _pendingAssetPath: String? = null
    private var _pendingFile: File? = null
    private var _pendingPageIndex: Int? = null
    private var _pdfReady = false
    private var _errorMessage: String? = null
    private var _pageWidthPdf = 0
    private var _pageHeightPdf = 0

    // Cache des dimensions de la vue (capturées sur main thread)
    private var _viewWidth = 0
    private var _viewHeight = 0

    // --- Cache bitmaps ---
    private val bitmapCache = mutableMapOf<Int, Bitmap>()
    private val maxCacheSize = 5

    // --- Callbacks ---
    var onPageChanged: ((pageIndex: Int, pageCount: Int) -> Unit)? = null
    var onError: ((message: String) -> Unit)? = null
    var onLoadingChanged: ((loading: Boolean) -> Unit)? = null
    var onZoomChanged: ((percent: Int) -> Unit)? = null

    val pageCount: Int get() = _pageCount
    val pageIndex: Int get() = _pageIndex
    val zoom: Float get() = _zoom
    val isZoomed: Boolean get() = _zoom > 1.01f

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var renderJob: Job? = null
    private var pendingRenderJob: Job? = null

    // --- Paints ---
    private val backgroundPaint = Paint().apply { color = Color.parseColor("#F0F2F6") }
    private val pageBorderPaint = Paint().apply { color = Color.parseColor("#D0D5E0") }
    private val errorBgPaint = Paint().apply { color = Color.parseColor("#E8FFFFFF") }
    private val errorTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E74C3C")
        textSize = 40f
        textAlign = Paint.Align.CENTER
    }

    // --- Touch / Gestures ---
    private val scaleDetector: ScaleGestureDetector
    private val gestureDetector: GestureDetector
    private val drawMatrix = Matrix()

    init {
        scaleDetector = ScaleGestureDetector(context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    if (!_pdfReady) return false
                    _isPinching = true
                    _touchScale = 1.0f
                    _pinchFocusX = detector.focusX
                    _pinchFocusY = detector.focusY
                    return true
                }

                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    if (!_isPinching) return false
                    _touchScale = detector.scaleFactor
                    _pinchFocusX = detector.focusX
                    _pinchFocusY = detector.focusY
                    invalidate()  // instant visual feedback, no re-render
                    return true
                }

                override fun onScaleEnd(detector: ScaleGestureDetector) {
                    if (!_isPinching) return
                    _isPinching = false
                    val newZoom = (_zoom * _touchScale).coerceIn(ZOOM_MIN, ZOOM_MAX)
                    _touchScale = 1.0f
                    if (newZoom != _zoom) {
                        _zoom = newZoom
                        bitmapCache.clear()
                        renderPage(_pageIndex)
                    } else {
                        invalidate()
                    }
                }
            })

        gestureDetector = GestureDetector(context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onScroll(
                    e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float
                ): Boolean {
                    if (!_isPinching && isZoomed) {
                        _panX -= dx
                        _panY -= dy
                        invalidate()
                        return true
                    }
                    return false
                }

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    if (!_pdfReady || _isPinching) return false
                    val x = e.x
                    val w = this@PdfViewerView.width.toFloat()
                    if (x < w / 3) previousPage()
                    else if (x > w * 2 / 3) nextPage()
                    return true
                }
            })
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    // ================================================================
    //  OUVERTURE PDF
    // ================================================================

    fun openPdf(assetPath: String) {
        Log.d(TAG, "openPdf($assetPath), w=$_viewWidth, h=$_viewHeight")
        if (_viewWidth == 0 || _viewHeight == 0) {
            _pendingAssetPath = assetPath
            return
        }
        doOpenPdf(assetPath)
    }

    fun openPdfFile(file: File) {
        Log.d(TAG, "openPdfFile(${file.absolutePath}), w=$_viewWidth, h=$_viewHeight")
        if (_viewWidth == 0 || _viewHeight == 0) {
            _pendingFile = file
            return
        }
        doOpenPdfFile(file)
    }

    fun goToPage(page: Int) {
        if (!_pdfReady) {
            _pendingPageIndex = page
            return
        }
        _panX = 0f
        _panY = 0f
        renderPage(page.coerceIn(0, _pageCount - 1))
    }

    private fun doOpenPdf(assetPath: String) {
        closePdfResources()
        _errorMessage = null
        _pdfReady = false
        setLoading(true)

        val viewW = _viewWidth
        val viewH = _viewHeight

        scope.launch {
            try {
                Log.d(TAG, "Copie depuis assets: $assetPath")

                val inputStream = context.assets.open(assetPath)
                val tempFile = File(context.cacheDir, "exam.pdf")
                if (tempFile.exists()) tempFile.delete()
                FileOutputStream(tempFile).use { out ->
                    inputStream.use { it.copyTo(out) }
                }
                inputStream.close()
                Log.d(TAG, "PDF copié, taille=${tempFile.length()}")

                openFromFd(ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY), viewW, viewH)
            } catch (e: java.io.FileNotFoundException) {
                Log.e(TAG, "PDF introuvable: $assetPath", e)
                showErrorOnMain("PDF introuvable : $assetPath")
            } catch (e: IOException) {
                Log.e(TAG, "Erreur IO: ${e.message}", e)
                showErrorOnMain("Impossible d'ouvrir le PDF :\n${e.message}")
            } catch (e: Exception) {
                val msg = "${e.javaClass.simpleName}: ${e.message}"
                Log.e(TAG, "Erreur: $msg", e)
                showErrorOnMain("Erreur de chargement :\n$msg")
            }
        }
    }

    private fun doOpenPdfFile(file: File) {
        closePdfResources()
        _errorMessage = null
        _pdfReady = false
        setLoading(true)

        val viewW = _viewWidth
        val viewH = _viewHeight

        scope.launch {
            try {
                Log.d(TAG, "Ouverture fichier: ${file.absolutePath}, taille=${file.length()}, exists=${file.exists()}")
                if (!file.exists()) {
                    showErrorOnMain("Fichier introuvable : ${file.absolutePath}")
                    return@launch
                }
                if (file.length() == 0L) {
                    showErrorOnMain("Fichier PDF vide : ${file.absolutePath}")
                    return@launch
                }
                val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                openFromFd(fd, viewW, viewH)
            } catch (e: java.io.FileNotFoundException) {
                Log.e(TAG, "Fichier introuvable: ${file.absolutePath}", e)
                showErrorOnMain("Fichier introuvable :\n${file.absolutePath}")
            } catch (e: IOException) {
                Log.e(TAG, "Erreur IO: ${e.message}", e)
                showErrorOnMain("Impossible d'ouvrir le PDF :\n${e.message}")
            } catch (e: Exception) {
                val msg = "${e.javaClass.simpleName}: ${e.message}"
                Log.e(TAG, "Erreur: $msg", e)
                showErrorOnMain("Erreur de chargement :\n$msg")
            }
        }
    }

    private suspend fun openFromFd(fd: ParcelFileDescriptor, viewW: Int, viewH: Int) {
        parcelFd = fd
        Log.d(TAG, "ParcelFileDescriptor ouvert")

        pdfRenderer = PdfRenderer(parcelFd!!)
        _pageCount = pdfRenderer!!.pageCount
        Log.d(TAG, "PdfRenderer OK, $_pageCount pages")

        if (_pageCount == 0) {
            withContext(Dispatchers.Main) {
                setLoading(false)
                _errorMessage = "Le PDF ne contient aucune page."
                onError?.invoke(_errorMessage!!)
                invalidate()
            }
            return
        }

        val page0 = pdfRenderer!!.openPage(0)
        _pageWidthPdf = page0.width
        _pageHeightPdf = page0.height
        computeScales(page0.width, page0.height, viewW, viewH)
        Log.d(TAG, "Page 0: ${page0.width}x${page0.height}, fitScale=$_fitScale, fitWidthScale=$_fitWidthScale")
        page0.close()

        _pageIndex = 0
        _zoom = 1.0f
        _panX = 0f
        _panY = 0f
        bitmapCache.clear()
        _pdfReady = true

        withContext(Dispatchers.Main) {
            setLoading(false)
            val pendingPage = _pendingPageIndex
            _pendingPageIndex = null
            if (pendingPage != null) {
                goToPage(pendingPage)
            } else {
                renderCurrentPage()
            }
        }
    }

    private suspend fun showErrorOnMain(msg: String) {
        withContext(Dispatchers.Main) {
            setLoading(false)
            _errorMessage = msg
            onError?.invoke(msg)
            invalidate()
        }
    }

    private fun setLoading(loading: Boolean) {
        onLoadingChanged?.invoke(loading)
    }

    // ================================================================
    //  RENDU
    // ================================================================

    private fun renderCurrentPage() {
        if (!_pdfReady || _viewWidth == 0 || _viewHeight == 0) return
        renderPage(_pageIndex)
    }

    private fun renderPage(index: Int) {
        val renderer = pdfRenderer ?: return
        if (index < 0 || index >= _pageCount) return

        val viewW = _viewWidth
        val viewH = _viewHeight
        Log.d(TAG, "renderPage($index) vue=${viewW}x${viewH} zoom=$_zoom")

        renderJob?.cancel()
        renderJob = scope.launch {
            try {
                // Vérifier le cache
                val cached = bitmapCache[index]
                if (cached != null && !cached.isRecycled) {
                    Log.d(TAG, "Cache hit page $index")
                    withContext(Dispatchers.Main) {
                        setBitmap(cached, index)
                    }
                    return@launch
                }

                val page = renderer.openPage(index)
                _pageWidthPdf = page.width
                _pageHeightPdf = page.height
                computeScales(page.width, page.height, viewW, viewH)

                val scale = _fitScale * _zoom
                var rw = (page.width * scale).toInt()
                var rh = (page.height * scale).toInt()

                // Limiter pour éviter OOM
                val maxDim = 4096
                if (rw > maxDim || rh > maxDim) {
                    val ds = maxDim.toFloat() / maxOf(rw, rh)
                    rw = (rw * ds).toInt()
                    rh = (rh * ds).toInt()
                }
                rw = rw.coerceAtLeast(1)
                rh = rh.coerceAtLeast(1)

                Log.d(TAG, "Bitmap: ${rw}x${rh} scale=$scale")

                val bitmap = Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()

                if (bitmapCache.size >= maxCacheSize) {
                    bitmapCache.remove(bitmapCache.keys.first())?.recycle()
                }
                bitmapCache[index] = bitmap

                withContext(Dispatchers.Main) {
                    setBitmap(bitmap, index)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erreur rendu page $index: ${e.javaClass.simpleName}: ${e.message}", e)
            }
        }
    }

    private fun setBitmap(bitmap: Bitmap, index: Int) {
        currentBitmap?.recycle()
        currentBitmap = bitmap
        _pageIndex = index
        onPageChanged?.invoke(index + 1, _pageCount)
        onZoomChanged?.invoke((_zoom * 100).toInt())
        invalidate()
    }

    private fun computeScales(pageW: Int, pageH: Int, viewW: Int, viewH: Int) {
        val vw = viewW.toFloat().coerceAtLeast(1f)
        val vh = viewH.toFloat().coerceAtLeast(1f)
        val usableW = vw * 0.96f
        val usableH = vh * 0.94f
        _fitScale = minOf(usableW / pageW, usableH / pageH)
        _fitWidthScale = usableW / pageW
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        _viewWidth = w
        _viewHeight = h
        Log.d(TAG, "onSizeChanged: ${w}x${h}")

        if (_pendingAssetPath != null) {
            val p = _pendingAssetPath
            _pendingAssetPath = null
            doOpenPdf(p!!)
            return
        }
        if (_pendingFile != null) {
            val f = _pendingFile
            _pendingFile = null
            doOpenPdfFile(f!!)
            return
        }
        if (_pdfReady) {
            bitmapCache.clear()
            _panX = 0f
            _panY = 0f
            renderCurrentPage()
        }
    }

    // ================================================================
    //  DESSIN
    // ================================================================

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val vw = width.toFloat()
        val vh = height.toFloat()
        canvas.drawRect(0f, 0f, vw, vh, backgroundPaint)

        // Afficher erreur
        if (_errorMessage != null) {
            drawErrorMessage(canvas, vw, vh)
            return
        }

        val bmp = currentBitmap ?: return
        if (bmp.isRecycled) return

        val bw = bmp.width.toFloat()
        val bh = bmp.height.toFloat()

        // Centrer + pan
        var left = (vw - bw) / 2f + _panX
        var top = (vh - bh) / 2f + _panY

        // Limiter le pan
        left = clampPan(left, vw, bw)
        top = clampPan(top, vh, bh)

        // During pinch: apply smooth matrix transform around pinch focus point
        if (_isPinching && _touchScale != 1.0f) {
            drawMatrix.reset()
            drawMatrix.postTranslate(-_pinchFocusX, -_pinchFocusY)
            drawMatrix.postScale(_touchScale, _touchScale)
            drawMatrix.postTranslate(_pinchFocusX, _pinchFocusY)
            drawMatrix.postTranslate(left, top)
            canvas.drawRect(RectF(left - 1f, top - 1f, left + bw + 1f, top + bh + 1f), pageBorderPaint)
            canvas.drawBitmap(bmp, drawMatrix, null)
        } else {
            canvas.drawRect(RectF(left - 1f, top - 1f, left + bw + 1f, top + bh + 1f), pageBorderPaint)
            canvas.drawBitmap(bmp, left, top, null)
        }
    }

    private fun drawErrorMessage(canvas: Canvas, vw: Float, vh: Float) {
        val text = _errorMessage!!
        val cx = vw / 2f
        val cy = vh / 2f

        // Fond
        val padding = 40f
        val lines = text.split("\n")
        val lineH = errorTextPaint.textSize * 1.4f
        val boxH = lines.size * lineH + padding * 2
        val maxLineW = lines.maxOf { errorTextPaint.measureText(it) } + padding * 2
        val boxW = maxOf(maxLineW, 400f)

        canvas.drawRect(RectF(cx - boxW / 2, cy - boxH / 2, cx + boxW / 2, cy + boxH / 2), errorBgPaint)

        // Texte ligne par ligne
        for ((i, line) in lines.withIndex()) {
            val y = cy - boxH / 2 + padding + lineH * (i + 1)
            canvas.drawText(line, cx, y, errorTextPaint)
        }
    }

    private fun clampPan(pos: Float, viewLen: Float, bmpLen: Float): Float {
        if (bmpLen <= viewLen) return (viewLen - bmpLen) / 2f
        return pos.coerceIn(viewLen - bmpLen, 0f)
    }

    // ================================================================
    //  ZOOM
    // ================================================================

    fun zoomIn() {
        if (!_pdfReady) return
        _zoom = (_zoom + ZOOM_STEP).coerceAtMost(ZOOM_MAX)
        _panX = 0f; _panY = 0f
        bitmapCache.clear()
        Log.d(TAG, "zoomIn -> ${_zoom}x")
        renderPage(_pageIndex)
    }

    fun zoomOut() {
        if (!_pdfReady) return
        _zoom = (_zoom - ZOOM_STEP).coerceAtLeast(ZOOM_MIN)
        _panX = 0f; _panY = 0f
        bitmapCache.clear()
        Log.d(TAG, "zoomOut -> ${_zoom}x")
        renderPage(_pageIndex)
    }

    fun resetZoom() {
        _zoom = 1.0f
        _panX = 0f; _panY = 0f
        bitmapCache.clear()
        renderPage(_pageIndex)
    }

    fun fitToPage() {
        _zoom = 1.0f
        _panX = 0f; _panY = 0f
        bitmapCache.clear()
        Log.d(TAG, "fitToPage")
        renderPage(_pageIndex)
    }

    fun fitToWidth() {
        if (_fitScale > 0f) {
            _zoom = _fitWidthScale / _fitScale
            _panX = 0f; _panY = 0f
            bitmapCache.clear()
            Log.d(TAG, "fitToWidth zoom=${_zoom}x")
            renderPage(_pageIndex)
        }
    }

    // ================================================================
    //  PAN
    // ================================================================

    fun panLeft() { if (isZoomed) { _panX += width * PAN_STEP; invalidate() } }
    fun panRight() { if (isZoomed) { _panX -= width * PAN_STEP; invalidate() } }
    fun panUp() { if (isZoomed) { _panY += height * PAN_STEP; invalidate() } }
    fun panDown() { if (isZoomed) { _panY -= height * PAN_STEP; invalidate() } }

    // ================================================================
    //  PAGES
    // ================================================================

    fun nextPage() {
        if (!_pdfReady || _pageIndex >= _pageCount - 1) return
        _panX = 0f; _panY = 0f
        renderPage(_pageIndex + 1)
    }

    fun previousPage() {
        if (!_pdfReady || _pageIndex <= 0) return
        _panX = 0f; _panY = 0f
        renderPage(_pageIndex - 1)
    }

    // ================================================================
    //  NETTOYAGE
    // ================================================================

    private fun closePdfResources() {
        renderJob?.cancel()
        bitmapCache.values.forEach { it.recycle() }
        bitmapCache.clear()
        currentBitmap?.recycle()
        currentBitmap = null
        pdfRenderer?.close(); pdfRenderer = null
        parcelFd?.close(); parcelFd = null
    }

    fun closePdf() {
        closePdfResources()
        scope.cancel()
        _pdfReady = false
        _pendingAssetPath = null
        _pendingFile = null
        _pendingPageIndex = null
    }
}
