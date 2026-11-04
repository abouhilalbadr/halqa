package com.saiesh.tele.presentation.mac

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.saiesh.tele.R
import com.saiesh.tele.core.mac.MacFinder
import com.saiesh.tele.core.mac.MacLogin
import com.saiesh.tele.core.mac.MacService
import com.saiesh.tele.core.mac.MacSettings

/**
 * Connects Halqa to a Mac's shared folder. Macs with File Sharing on are listed as they are found;
 * picking one fills in its address. The user types their Mac user name and password on the TV.
 */
class AddMacFragment : Fragment(R.layout.fragment_add_mac) {
    private val viewModel: MacLibraryViewModel by activityViewModels()
    private var finder: MacFinder? = null
    private val found = linkedMapOf<String, String>()
    private var chosenName = ""

    private lateinit var foundList: LinearLayout
    private lateinit var searching: View
    private lateinit var host: EditText
    private lateinit var user: EditText
    private lateinit var password: EditText
    private lateinit var share: EditText
    private lateinit var error: TextView
    private lateinit var connect: TextView
    private lateinit var connecting: View

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        foundList = view.findViewById(R.id.mac_found_list)
        searching = view.findViewById(R.id.mac_searching)
        host = view.findViewById(R.id.mac_host_input)
        user = view.findViewById(R.id.mac_user_input)
        password = view.findViewById(R.id.mac_password_input)
        share = view.findViewById(R.id.mac_share_input)
        error = view.findViewById(R.id.mac_error)
        connect = view.findViewById(R.id.mac_connect)
        connecting = view.findViewById(R.id.mac_connecting)
        val forget = view.findViewById<TextView>(R.id.mac_forget)
        view.findViewById<View>(R.id.mac_searching_dot).startAnimation(
            AlphaAnimation(1f, 0.3f).apply {
                duration = 800
                repeatMode = Animation.REVERSE
                repeatCount = Animation.INFINITE
            },
        )

        // Changing the Mac: the saved login is filled in, except the password.
        MacSettings.login.value?.let { saved ->
            chosenName = saved.name
            host.setText(saved.host)
            user.setText(saved.user)
            share.setText(saved.share)
            forget.visibility = View.VISIBLE
        }
        forget.setOnClickListener {
            viewModel.forget()
            parentFragmentManager.popBackStack()
        }
        connect.setOnClickListener { tryConnect() }
        share.setOnEditorActionListener { _, _, _ ->
            connect.requestFocus()
            false
        }
        if (savedInstanceState == null) view.post { (if (host.text.isEmpty()) connect else password).requestFocus() }
    }

    override fun onStart() {
        super.onStart()
        finder = MacFinder(requireContext()).also { it.start(::onMacFound) }
    }

    override fun onStop() {
        finder?.stop()
        finder = null
        super.onStop()
    }

    private fun onMacFound(mac: MacService) {
        val isNew = mac.name !in found
        if (view == null || found[mac.name] == mac.host || (isNew && found.size >= MAX_FOUND)) return
        found[mac.name] = mac.host
        searching.visibility = View.GONE
        if (!isNew) return
        val chip = LayoutInflater.from(requireContext()).inflate(R.layout.item_mac_found, foundList, false) as TextView
        chip.text = mac.name
        chip.setOnClickListener {
            chosenName = mac.name
            host.setText(found[mac.name])
            (if (user.text.isEmpty()) user else password).requestFocus()
        }
        foundList.addView(chip)
        // The first Mac found is the likely one: fill it in so the user only types their login.
        if (host.text.isEmpty()) {
            chosenName = mac.name
            host.setText(mac.host)
            if (connect.isFocused) (if (user.text.isEmpty()) user else password).requestFocus()
        } else if (chosenName == mac.name) {
            host.setText(mac.host)
        }
    }

    private fun tryConnect() {
        val login =
            MacLogin(
                name = chosenName.ifEmpty { found.entries.firstOrNull { it.value == host.text.toString().trim() }?.key.orEmpty() },
                host = host.text.toString().trim(),
                user = user.text.toString().trim(),
                password = password.text.toString(),
                share = share.text.toString().trim(),
            )
        val missing =
            when {
                login.host.isEmpty() -> host
                login.user.isEmpty() -> user
                login.password.isEmpty() -> password
                login.share.isEmpty() -> share
                else -> null
            }
        if (missing != null) {
            showError(getString(R.string.mac_fill_in))
            missing.requestFocus()
            return
        }
        showError(null)
        connect.isEnabled = false
        connecting.visibility = View.VISIBLE
        viewModel.connect(login) { problem ->
            if (view == null) return@connect
            connect.isEnabled = true
            connecting.visibility = View.GONE
            if (problem == null) {
                parentFragmentManager.popBackStack()
            } else {
                showError(problem)
                connect.requestFocus()
            }
        }
    }

    private fun showError(text: String?) {
        error.text = text
        error.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    private companion object {
        const val MAX_FOUND = 3
    }
}
