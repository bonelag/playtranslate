package com.gamelens.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.gamelens.R

/**
 * Full-screen overlay that dims the screen and shows a small popup menu
 * next to the floating icon. Tapping outside the menu dismisses it.
 * When the hide button is tapped, shows a confirmation dialog.
 *
 * Also supports drag-to-select: dragging outside the menu draws a selection
 * rectangle and fires [onRegionSelected] with fractional coordinates.
 */
class FloatingIconMenu(context: Context) : FrameLayout(context) {

    private val dp = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    var onHideIcon: (() -> Unit)? = null
    var onHideTemporary: (() -> Unit)? = null
    var onDismiss: (() -> Unit)? = null
    var onRegionSelected: ((top: Float, bottom: Float, left: Float, right: Float) -> Unit)? = null
    var onToggleLive: (() -> Unit)? = null
    var isSingleScreen: Boolean = false
    var isLiveMode: Boolean = false
        set(value) {
            field = value
            updateLiveButton()
        }

    private val dimPaint = Paint().apply {
        color = Color.argb(170, 0, 0, 0)
    }
    private val clearPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val selectionStrokePaint = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 3f * dp
        isAntiAlias = true
    }

    private val menuCard: LinearLayout
    private val instructionText: TextView
    private var confirmDialog: LinearLayout? = null
    private val appName: String = context.getString(R.string.app_name)

    private lateinit var liveIcon: TextView
    private lateinit var liveLabel: TextView
    private lateinit var liveBtn: FrameLayout

    // ── Drag state ────────────────────────────────────────────────────────
    private var isDragging = false
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var selectionRect: RectF? = null
    private var potentialDrag = false

    init {
        setWillNotDraw(false)
        setLayerType(LAYER_TYPE_HARDWARE, null)

        val btnSize = (54 * dp).toInt()

        // Rounded rectangle container for both buttons
        menuCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#D9222222"))
                cornerRadius = 20 * dp
            }
            elevation = 12 * dp
            gravity = Gravity.CENTER_HORIZONTAL
            val hPad = (14 * dp).toInt()
            val vPad = (12 * dp).toInt()
            setPadding(hPad, vPad, hPad, vPad)
            visibility = View.INVISIBLE
        }

        // Live mode button + label
        val liveGroup = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (10 * dp).toInt() }
        }
        liveBtn = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#40A040"))
                cornerRadius = 14 * dp
            }
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
            setOnClickListener { onToggleLive?.invoke() }
        }
        liveIcon = TextView(context).apply {
            text = "\u25B6"
            setTextColor(Color.WHITE)
            textSize = 22f
            gravity = Gravity.CENTER
        }
        liveBtn.addView(liveIcon, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        liveLabel = TextView(context).apply {
            text = "Auto Translate"
            setTextColor(Color.parseColor("#CCFFFFFF"))
            textSize = 9f
            gravity = Gravity.CENTER_HORIZONTAL
            setTypeface(null, Typeface.BOLD)
            maxWidth = btnSize
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (4 * dp).toInt() }
        }
        liveGroup.addView(liveBtn)
        liveGroup.addView(liveLabel)
        menuCard.addView(liveGroup)

        // Hide button + label
        val hideGroup = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val hideBtn = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E04040"))
                cornerRadius = 14 * dp
            }
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
            setOnClickListener { showConfirmDialog() }
        }
        val hideIcon = TextView(context).apply {
            text = "\u2715"
            setTextColor(Color.WHITE)
            textSize = 24f
            gravity = Gravity.CENTER
        }
        hideBtn.addView(hideIcon, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        val hideLabel = TextView(context).apply {
            text = "Hide"
            setTextColor(Color.parseColor("#CCFFFFFF"))
            textSize = 9f
            gravity = Gravity.CENTER_HORIZONTAL
            setTypeface(null, Typeface.BOLD)
            maxWidth = btnSize
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (4 * dp).toInt() }
        }
        hideGroup.addView(hideBtn)
        hideGroup.addView(hideLabel)
        menuCard.addView(hideGroup)

        addView(menuCard, LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // Instruction text at top center
        instructionText = TextView(context).apply {
            text = "Drag finger to capture a specific area"
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(
                (16 * dp).toInt(), (16 * dp).toInt(),
                (16 * dp).toInt(), (8 * dp).toInt()
            )
        }
        addView(instructionText, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        })
    }

    private fun updateLiveButton() {
        if (isLiveMode) {
            liveIcon.text = "\u275A\u275A" // ❚❚ pause
            liveLabel.text = "Pause"
            (liveBtn.background as? GradientDrawable)?.setColor(Color.parseColor("#D4A020"))
        } else {
            liveIcon.text = "\u25B6" // ▶ play
            liveLabel.text = "Auto Translate"
            (liveBtn.background as? GradientDrawable)?.setColor(Color.parseColor("#40A040"))
        }
    }

    override fun onDraw(canvas: Canvas) {
        val sel = selectionRect
        if (sel != null && isDragging) {
            val sc = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
            canvas.drawRect(sel, clearPaint)
            canvas.restoreToCount(sc)
            canvas.drawRect(sel, selectionStrokePaint)
        } else {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Don't intercept while confirmation dialog is showing
        if (confirmDialog != null) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                val activeView = confirmDialog ?: return false
                val loc = IntArray(2)
                activeView.getLocationOnScreen(loc)
                val rect = RectF(
                    loc[0].toFloat(), loc[1].toFloat(),
                    loc[0].toFloat() + activeView.width, loc[1].toFloat() + activeView.height
                )
                if (!rect.contains(event.rawX, event.rawY)) {
                    onDismiss?.invoke()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val loc = IntArray(2)
                menuCard.getLocationOnScreen(loc)
                val menuRect = RectF(
                    loc[0].toFloat(), loc[1].toFloat(),
                    loc[0].toFloat() + menuCard.width, loc[1].toFloat() + menuCard.height
                )
                if (menuRect.contains(event.rawX, event.rawY)) {
                    return super.onTouchEvent(event)
                }
                potentialDrag = true
                dragStartX = event.x
                dragStartY = event.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!potentialDrag) return super.onTouchEvent(event)
                val dx = event.x - dragStartX
                val dy = event.y - dragStartY
                if (!isDragging && (dx * dx + dy * dy > touchSlop * touchSlop)) {
                    isDragging = true
                    menuCard.visibility = View.GONE
                    instructionText.visibility = View.GONE
                }
                if (isDragging) {
                    val left   = minOf(dragStartX, event.x)
                    val top    = minOf(dragStartY, event.y)
                    val right  = maxOf(dragStartX, event.x)
                    val bottom = maxOf(dragStartY, event.y)
                    selectionRect = RectF(left, top, right, bottom)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (isDragging) {
                    val sel = selectionRect
                    if (sel != null && sel.width() > touchSlop && sel.height() > touchSlop) {
                        val w = width.toFloat()
                        val h = height.toFloat()
                        if (w > 0 && h > 0) {
                            onRegionSelected?.invoke(
                                sel.top / h,
                                sel.bottom / h,
                                sel.left / w,
                                sel.right / w
                            )
                        }
                    }
                    isDragging = false
                    potentialDrag = false
                    selectionRect = null
                    return true
                }
                potentialDrag = false
                onDismiss?.invoke()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                potentialDrag = false
                selectionRect = null
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    // ── Confirmation dialog ──────────────────────────────────────────────

    private fun showConfirmDialog() {
        menuCard.visibility = View.GONE
        instructionText.visibility = View.GONE

        val dialog = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F0222222"))
                cornerRadius = 16 * dp
            }
            elevation = 12 * dp
            gravity = Gravity.CENTER_HORIZONTAL
            val pad = (24 * dp).toInt()
            setPadding(pad, pad, pad, (16 * dp).toInt())
        }

        val circleSize = (56 * dp).toInt()
        val imgSize = (circleSize * 1.5f).toInt()
        val offset = (circleSize - imgSize) / 2
        val iconFrame = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(circleSize, circleSize).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = (16 * dp).toInt()
            }
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
        }
        val icon = ImageView(context).apply {
            setImageResource(R.mipmap.ic_launcher_img)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = FrameLayout.LayoutParams(imgSize, imgSize).apply {
                leftMargin = offset
                topMargin = offset
            }
        }
        iconFrame.addView(icon)
        dialog.addView(iconFrame)

        dialog.addView(TextView(context).apply {
            text = "Hide $appName icon?"
            setTextColor(Color.WHITE)
            textSize = 17f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = (8 * dp).toInt()
            }
        })

        dialog.addView(TextView(context).apply {
            text = "\u201CHide for Now\u201D brings it back next time you open the app. \u201CTurn Off\u201D disables it until re-enabled in settings."
            setTextColor(Color.parseColor("#AAAAAA"))
            textSize = 13f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = (20 * dp).toInt()
            }
        })

        val hPad = (20 * dp).toInt()
        val vPad = (10 * dp).toInt()
        val btnLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val btnHideForNow = Button(context).apply {
            text = "Hide for Now"
            setTextColor(Color.WHITE)
            textSize = 14f
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#D4A020"))
                cornerRadius = 8 * dp
            }
            isAllCaps = false
            setPadding(hPad, vPad, hPad, vPad)
            layoutParams = LinearLayout.LayoutParams(btnLp).apply {
                bottomMargin = (8 * dp).toInt()
            }
            setOnClickListener { onHideTemporary?.invoke() }
        }

        val btnTurnOff = Button(context).apply {
            text = "Turn Off"
            setTextColor(Color.WHITE)
            textSize = 14f
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E04040"))
                cornerRadius = 8 * dp
            }
            isAllCaps = false
            setPadding(hPad, vPad, hPad, vPad)
            layoutParams = LinearLayout.LayoutParams(btnLp).apply {
                bottomMargin = (8 * dp).toInt()
            }
            setOnClickListener { onHideIcon?.invoke() }
        }

        val btnCancel = Button(context).apply {
            text = "Cancel"
            setTextColor(Color.parseColor("#AAAAAA"))
            textSize = 14f
            setBackgroundColor(Color.TRANSPARENT)
            isAllCaps = false
            setPadding(hPad, vPad, hPad, vPad)
            layoutParams = LinearLayout.LayoutParams(btnLp)
            setOnClickListener { onDismiss?.invoke() }
        }

        dialog.addView(btnHideForNow)
        dialog.addView(btnTurnOff)
        dialog.addView(btnCancel)

        val maxW = (280 * dp).toInt()
        val dlp = LayoutParams(maxW, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        }
        addView(dialog, dlp)
        confirmDialog = dialog

        dialog.alpha = 0f
        dialog.scaleX = 0.9f
        dialog.scaleY = 0.9f
        dialog.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(150)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    // ── Positioning ──────────────────────────────────────────────────────

    @Suppress("UNUSED_PARAMETER")
    fun positionNearIcon(iconCx: Int, iconCy: Int, iconEdge: FloatingOverlayIcon.Edge, screenW: Int, screenH: Int) {
        post {
            menuCard.measure(
                MeasureSpec.makeMeasureSpec(screenW, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(screenH, MeasureSpec.AT_MOST)
            )
            val mw = menuCard.measuredWidth
            val mh = menuCard.measuredHeight
            val margin = (16 * dp).toInt()

            val lp = menuCard.layoutParams as LayoutParams

            val menuX = if (iconEdge == FloatingOverlayIcon.Edge.LEFT) {
                margin
            } else {
                screenW - mw - margin
            }

            val menuY = (iconCy - mh / 2).coerceIn(margin, screenH - mh - margin)

            lp.gravity = Gravity.TOP or Gravity.START
            lp.leftMargin = menuX
            lp.topMargin = menuY
            menuCard.layoutParams = lp
            menuCard.visibility = View.VISIBLE

            menuCard.alpha = 0f
            menuCard.scaleX = 0.8f
            menuCard.scaleY = 0.8f
            menuCard.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(150)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }
}
