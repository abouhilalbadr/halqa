package com.saiesh.tele.presentation.mac

import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import com.saiesh.tele.R
import com.saiesh.tele.presentation.text.ArabicText

/** Asks before a title's folder is removed from the Mac; Cancel has focus. The answer goes back as a fragment result. */
class MacDeleteSheetFragment : DialogFragment(R.layout.dialog_quality) {
    private lateinit var labels: android.content.res.Resources

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, R.style.Theme_Tele_ContextMenu)
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        val args = requireArguments()
        // An Arabic title gets the question in Arabic, right to left, like its page.
        val arabic = ArabicText.isArabic(args.getString(ARG_NAME))
        labels = if (arabic) ArabicText.arabicResources(requireContext()) else resources
        view.layoutDirection = if (arabic) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        view.findViewById<View>(R.id.quality_overlay).setOnClickListener { dismiss() }
        val title = view.findViewById<TextView>(R.id.quality_title)
        title.text = labels.getString(R.string.mac_delete_title, args.getString(ARG_NAME))
        val count = args.getInt(ARG_COUNT)
        val subtitle = view.findViewById<TextView>(R.id.quality_subtitle)
        subtitle.text = labels.getQuantityString(R.plurals.mac_delete_subtitle, count, count)
        listOf(title, subtitle).forEach { ArabicText.style(it, arabic) }

        val buttons = view.findViewById<LinearLayout>(R.id.quality_buttons)
        val cancel = addButton(buttons, R.string.sign_out_cancel, arabic) { dismiss() }
        addButton(buttons, R.string.mac_delete, arabic) {
            parentFragmentManager.setFragmentResult(RESULT_KEY, Bundle())
            dismiss()
        }
        cancel.post { cancel.requestFocus() }
    }

    private fun addButton(
        parent: LinearLayout,
        label: Int,
        arabic: Boolean,
        onClick: (View) -> Unit,
    ): TextView {
        val button =
            TextView(requireContext()).apply {
                text = labels.getString(label)
                gravity = Gravity.CENTER
                isFocusable = true
                isClickable = true
                minWidth = dp(96)
                setPadding(dp(22), dp(12), dp(22), dp(12))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(ContextCompat.getColorStateList(context, R.color.quality_button_text))
                background = ContextCompat.getDrawable(context, R.drawable.quality_button_background)
                setOnClickListener(onClick)
                ArabicText.style(this, arabic)
            }
        parent.addView(
            button,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (parent.childCount > 0) marginStart = dp(14)
            },
        )
        return button
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    companion object {
        const val RESULT_KEY = "mac_delete_confirmed"
        private const val TAG = "mac_delete_sheet"
        private const val ARG_NAME = "name"
        private const val ARG_COUNT = "count"

        fun show(
            host: Fragment,
            name: String,
            videoCount: Int,
        ) {
            if (host.childFragmentManager.findFragmentByTag(TAG) != null) return
            MacDeleteSheetFragment()
                .apply { arguments = bundleOf(ARG_NAME to name, ARG_COUNT to videoCount) }
                .show(host.childFragmentManager, TAG)
        }
    }
}
