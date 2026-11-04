package com.saiesh.tele.presentation.mac

import android.view.LayoutInflater
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.saiesh.tele.R
import com.saiesh.tele.core.mac.MacFinder
import com.saiesh.tele.core.mac.MacLogin
import com.saiesh.tele.core.mac.MacService
import com.saiesh.tele.core.mac.MacSettings

/**
 * The computer sign-in form shared by Add your computer and the setup guide. Computers with file sharing on
 * are listed as they are found; picking one fills in its address, and the user types their login on the TV.
 * [root] holds the form's views under the mac_* ids.
 */
class MacConnectForm(
    root: View,
    private val viewModel: MacLibraryViewModel,
    private val onConnected: () -> Unit,
) {
    private val context = root.context
    private val foundList: LinearLayout = root.findViewById(R.id.mac_found_list)
    private val searching: View = root.findViewById(R.id.mac_searching)
    private val host: EditText = root.findViewById(R.id.mac_host_input)
    private val user: EditText = root.findViewById(R.id.mac_user_input)
    private val password: EditText = root.findViewById(R.id.mac_password_input)
    private val share: EditText = root.findViewById(R.id.mac_share_input)
    private val error: TextView = root.findViewById(R.id.mac_error)
    private val connecting: View = root.findViewById(R.id.mac_connecting)

    /** The button that connects; focus lands here when nothing needs typing yet. */
    val connect: TextView = root.findViewById(R.id.mac_connect)

    private var finder: MacFinder? = null
    private val found = linkedMapOf<String, String>()
    private var chosenName = ""
    private var attached = true

    init {
        root.findViewById<View>(R.id.mac_searching_dot).startAnimation(
            AlphaAnimation(1f, 0.3f).apply {
                duration = 800
                repeatMode = Animation.REVERSE
                repeatCount = Animation.INFINITE
            },
        )
        // Changing the computer: the saved login is filled in, except the password.
        MacSettings.login.value?.let { saved ->
            chosenName = saved.name
            host.setText(saved.host)
            user.setText(saved.user)
            share.setText(saved.share)
        }
        connect.setOnClickListener { tryConnect() }
        share.setOnEditorActionListener { _, _, _ ->
            connect.requestFocus()
            false
        }
    }

    /** The field to focus first: the password when a login is saved, otherwise Connect. */
    fun firstFocus(): View = if (host.text.isEmpty()) connect else password

    fun start() {
        attached = true
        finder = MacFinder(context).also { it.start(::onMacFound) }
    }

    fun stop() {
        finder?.stop()
        finder = null
    }

    /** Stops callbacks from a connection attempt that finishes after the form is gone. */
    fun detach() {
        attached = false
        stop()
    }

    private fun onMacFound(mac: MacService) {
        val isNew = mac.name !in found
        if (!attached || found[mac.name] == mac.host || (isNew && found.size >= MAX_FOUND)) return
        found[mac.name] = mac.host
        searching.visibility = View.GONE
        if (!isNew) return
        val chip = LayoutInflater.from(context).inflate(R.layout.item_mac_found, foundList, false) as TextView
        chip.text = mac.name
        chip.setOnClickListener {
            chosenName = mac.name
            host.setText(found[mac.name])
            (if (user.text.isEmpty()) user else password).requestFocus()
        }
        foundList.addView(chip)
        // The first computer found is the likely one: fill it in so the user only types their login.
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
            showError(context.getString(R.string.mac_fill_in))
            missing.requestFocus()
            return
        }
        showError(null)
        connect.isEnabled = false
        connecting.visibility = View.VISIBLE
        viewModel.connect(login) { problem ->
            if (!attached) return@connect
            connect.isEnabled = true
            connecting.visibility = View.GONE
            if (problem == null) {
                onConnected()
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
