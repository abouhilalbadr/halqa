package com.saiesh.tele.presentation.mac

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.saiesh.tele.R
import com.saiesh.tele.core.mac.MacSettings

/** Connects Halqa to a computer's shared folder: how to share on the left, the sign-in form on the right. */
class AddMacFragment : Fragment(R.layout.fragment_add_mac) {
    private val viewModel: MacLibraryViewModel by activityViewModels()
    private var form: MacConnectForm? = null

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        val connectForm = MacConnectForm(view, viewModel) { parentFragmentManager.popBackStack() }
        form = connectForm
        val forget = view.findViewById<View>(R.id.mac_forget)
        if (MacSettings.login.value != null) forget.visibility = View.VISIBLE
        forget.setOnClickListener {
            viewModel.forget()
            parentFragmentManager.popBackStack()
        }
        if (savedInstanceState == null) view.post { connectForm.firstFocus().requestFocus() }
    }

    override fun onStart() {
        super.onStart()
        form?.start()
    }

    override fun onStop() {
        form?.stop()
        super.onStop()
    }

    override fun onDestroyView() {
        form?.detach()
        form = null
        super.onDestroyView()
    }
}
