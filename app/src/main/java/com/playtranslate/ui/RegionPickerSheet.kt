package com.playtranslate.ui

import android.annotation.SuppressLint
import android.content.DialogInterface
import android.os.Bundle
import android.view.Display
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.playtranslate.PlayTranslateAccessibilityService
import com.playtranslate.Prefs
import com.playtranslate.RegionEntry
import com.playtranslate.R
import com.playtranslate.fullScreenDialogTheme
import com.playtranslate.themeColor

class RegionPickerSheet : DialogFragment() {

    var onSaved: ((Int) -> Unit)? = null
    var onTranslateOnce: ((RegionEntry) -> Unit)? = null
    var onClose: (() -> Unit)? = null
    var gameDisplay: Display? = null

    private lateinit var prefs: Prefs
    private var workingList: MutableList<RegionEntry> = mutableListOf()
    private var selectedIndex = 0
    private var isEditMode = false

    private lateinit var recyclerView: RecyclerView
    private lateinit var btnEdit: Button
    private lateinit var adapter: RegionAdapter
    private var itemTouchHelper: ItemTouchHelper? = null

    override fun getTheme(): Int = fullScreenDialogTheme(requireContext())

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.sheet_region_picker, container, false)

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setWindowAnimations(R.style.AnimSlideBottom)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        prefs = Prefs(requireContext())
        workingList = prefs.getRegionList().toMutableList()
        selectedIndex = prefs.captureRegionIndex.coerceIn(0, (workingList.size - 1).coerceAtLeast(0))

        recyclerView = view.findViewById(R.id.regionRecyclerView)
        btnEdit      = view.findViewById(R.id.btnEditRegion)

        val noPreviewNotice = view.findViewById<View>(R.id.noPreviewNotice)
        if (PlayTranslateAccessibilityService.isEnabled) {
            noPreviewNotice.visibility = View.GONE
        } else {
            noPreviewNotice.visibility = View.VISIBLE
            noPreviewNotice.setOnClickListener {
                startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        view.findViewById<View>(R.id.btnAddRegion).setOnClickListener {
            if (PlayTranslateAccessibilityService.isEnabled) {
                openAddCustomSheet()
            } else {
                showCustomRegionA11yDialog()
            }
        }

        btnEdit.setOnClickListener {
            if (isEditMode) exitEditMode() else enterEditMode()
        }

        adapter = RegionAdapter()
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter
        setupDragHelper()
        adapter.submitList()

        showOverlayForIndex(selectedIndex)
    }

    /** App went to background — kill the overlay immediately so it doesn't get stuck. */
    override fun onStop() {
        PlayTranslateAccessibilityService.instance?.hideRegionOverlay()
        super.onStop()
        if (showsDialog) dismissAllowingStateLoss()
    }

    override fun onDismiss(dialog: DialogInterface) {
        PlayTranslateAccessibilityService.instance?.hideRegionOverlay()
        super.onDismiss(dialog)
    }

    // ── Edit mode ─────────────────────────────────────────────────────────

    private fun enterEditMode() {
        isEditMode = true
        btnEdit.text = getString(R.string.label_done)
        adapter.submitList()
        itemTouchHelper?.attachToRecyclerView(recyclerView)
    }

    private fun exitEditMode() {
        prefs.setRegionList(workingList)
        selectedIndex = selectedIndex.coerceIn(0, (workingList.size - 1).coerceAtLeast(0))
        isEditMode = false
        btnEdit.text = getString(R.string.label_edit)
        itemTouchHelper?.attachToRecyclerView(null)
        adapter.submitList()
        showOverlayForIndex(selectedIndex)
    }

    // ── Drag-to-reorder ──────────────────────────────────────────────────

    private fun setupDragHelper() {
        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                val item = workingList.removeAt(from)
                workingList.add(to, item)
                // Track selected index through the move
                selectedIndex = when (selectedIndex) {
                    from -> to
                    in minOf(from, to)..maxOf(from, to) -> {
                        if (from < to) selectedIndex - 1 else selectedIndex + 1
                    }
                    else -> selectedIndex
                }
                adapter.notifyItemMoved(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun isLongPressDragEnabled() = false
        }
        itemTouchHelper = ItemTouchHelper(callback)
    }

    // ── Actions ────────────────────────────────────────────────────────────

    private fun deleteItem(index: Int) {
        workingList.removeAt(index)
        if (workingList.isEmpty()) {
            workingList.addAll(Prefs.DEFAULT_REGION_LIST)
        }
        selectedIndex = selectedIndex.coerceIn(0, workingList.lastIndex)
        adapter.submitList()
    }

    // ── Sheets ──────────────────────────────────────────────────────────

    private fun showCustomRegionA11yDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.custom_region_a11y_required_title)
            .setMessage(R.string.custom_region_a11y_required_message)
            .setPositiveButton(R.string.btn_open_a11y_settings) { _, _ ->
                startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openEditSheet(index: Int) {
        val entry = workingList.getOrNull(index) ?: return
        PlayTranslateAccessibilityService.instance?.hideRegionOverlay()
        AddCustomRegionSheet().also { sheet ->
            sheet.gameDisplay = gameDisplay
            sheet.initRegion(entry, index)
            sheet.onRegionEdited = { editedIndex ->
                workingList = prefs.getRegionList().toMutableList()
                selectedIndex = editedIndex
                adapter.submitList()
                showOverlayForIndex(selectedIndex)
            }
            sheet.onTranslateOnce = { region ->
                PlayTranslateAccessibilityService.instance?.hideRegionOverlay()
                if (showsDialog) dismissAllowingStateLoss()
                onTranslateOnce?.invoke(region)
            }
            sheet.onDismissed = {
                if (isAdded && !isDetached) {
                    adapter.submitList()
                    showOverlayForIndex(selectedIndex)
                }
            }
        }.show(childFragmentManager, AddCustomRegionSheet.TAG)
    }

    private fun openAddCustomSheet() {
        PlayTranslateAccessibilityService.instance?.hideRegionOverlay()
        AddCustomRegionSheet().also { sheet ->
            sheet.gameDisplay = gameDisplay
            sheet.onRegionAdded = { newIndex ->
                prefs.captureRegionIndex = newIndex
                PlayTranslateAccessibilityService.instance?.hideRegionOverlay()
                onSaved?.invoke(newIndex)
                if (showsDialog) dismissAllowingStateLoss()
            }
            sheet.onDismissed = {
                if (isAdded && !isDetached) {
                    adapter.submitList()
                    showOverlayForIndex(selectedIndex)
                }
            }
            sheet.onTranslateOnce = { region ->
                PlayTranslateAccessibilityService.instance?.hideRegionOverlay()
                if (showsDialog) dismissAllowingStateLoss()
                onTranslateOnce?.invoke(region)
            }
        }.show(childFragmentManager, AddCustomRegionSheet.TAG)
    }

    // ── Overlay helpers ────────────────────────────────────────────────────

    private fun showOverlayForIndex(index: Int) {
        val display = gameDisplay ?: return
        val e = workingList.getOrElse(index) { Prefs.DEFAULT_REGION_LIST[0] }
        PlayTranslateAccessibilityService.instance?.showRegionOverlay(display, e)
    }

    // ── RecyclerView Adapter ──────────────────────────────────────────────

    private inner class RegionAdapter : RecyclerView.Adapter<RegionAdapter.VH>() {

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val radio: RadioButton = itemView.findViewById(R.id.radioRegion)
            val label: TextView = itemView.findViewById(R.id.tvRegionLabel)
            val dragHandle: ImageView = itemView.findViewById(R.id.dragHandle)
            val btnDelete: ImageView = itemView.findViewById(R.id.btnDeleteRegion)
        }

        @SuppressLint("NotifyDataSetChanged")
        fun submitList() {
            notifyDataSetChanged()
        }

        override fun getItemCount() = workingList.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_region_row, parent, false)
            return VH(view)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onBindViewHolder(holder: VH, position: Int) {
            val entry = workingList[position]

            holder.label.text = entry.label
            holder.radio.isChecked = position == selectedIndex

            if (isEditMode) {
                holder.radio.visibility = View.GONE
                holder.dragHandle.visibility = View.VISIBLE
                holder.btnDelete.visibility = View.VISIBLE

                holder.label.paintFlags = holder.label.paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
                holder.label.setOnClickListener { openEditSheet(holder.bindingAdapterPosition) }

                holder.dragHandle.setOnTouchListener { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        itemTouchHelper?.startDrag(holder)
                    }
                    false
                }

                holder.btnDelete.setOnClickListener {
                    val pos = holder.bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) deleteItem(pos)
                }
            } else {
                holder.radio.visibility = View.VISIBLE
                holder.dragHandle.visibility = View.GONE
                holder.btnDelete.visibility = View.GONE

                holder.label.paintFlags = holder.label.paintFlags and android.graphics.Paint.UNDERLINE_TEXT_FLAG.inv()
                holder.label.setOnClickListener(null)

                holder.itemView.setOnClickListener {
                    val pos = holder.bindingAdapterPosition
                    if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                    selectedIndex = pos
                    prefs.captureRegionIndex = selectedIndex
                    val e = workingList.getOrElse(pos) { Prefs.DEFAULT_REGION_LIST[0] }
                    PlayTranslateAccessibilityService.instance?.updateRegionOverlay(e)
                    onSaved?.invoke(selectedIndex)
                    submitList()
                }
            }
        }
    }

    companion object {
        const val TAG = "RegionPickerSheet"
    }
}
