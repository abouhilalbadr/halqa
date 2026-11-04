package com.saiesh.tele.presentation.library

import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.saiesh.tele.R
import com.saiesh.tele.presentation.auth.AuthViewModel

/** Shows who is signed in and asks before signing out, in the same bottom sheet as the quality choice; Cancel has focus. */
class SignOutSheetFragment : DialogFragment(R.layout.dialog_quality) {
    private val authViewModel: AuthViewModel by activityViewModels()

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
        view.findViewById<View>(R.id.quality_overlay).setOnClickListener { dismiss() }
        view.findViewById<TextView>(R.id.quality_title).text =
            requireArguments().getString(ARG_NAME) ?: getString(R.string.sign_out_title)
        view.findViewById<TextView>(R.id.quality_subtitle).setText(R.string.sign_out_subtitle)

        val buttons = view.findViewById<LinearLayout>(R.id.quality_buttons)
        val cancel = addButton(buttons, R.string.sign_out_cancel) { dismiss() }
        addButton(buttons, R.string.sign_out) {
            it.isEnabled = false
            authViewModel.logOut()
        }
        cancel.post { cancel.requestFocus() }
    }

    private fun addButton(
        parent: LinearLayout,
        label: Int,
        onClick: (View) -> Unit,
    ): TextView {
        val button =
            TextView(requireContext()).apply {
                setText(label)
                gravity = Gravity.CENTER
                isFocusable = true
                isClickable = true
                minWidth = dp(96)
                setPadding(dp(22), dp(12), dp(22), dp(12))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(ContextCompat.getColorStateList(context, R.color.quality_button_text))
                background = ContextCompat.getDrawable(context, R.drawable.quality_button_background)
                setOnClickListener(onClick)
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
        private const val TAG = "sign_out_sheet"
        private const val ARG_NAME = "name"

        fun show(
            host: Fragment,
            accountName: String?,
        ) {
            if (host.childFragmentManager.findFragmentByTag(TAG) != null) return
            SignOutSheetFragment()
                .apply { arguments = bundleOf(ARG_NAME to accountName) }
                .show(host.childFragmentManager, TAG)
        }
    }
}
