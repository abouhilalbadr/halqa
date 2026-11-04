package com.saiesh.tele.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.saiesh.tele.R
import com.saiesh.tele.core.mac.MacSettings
import com.saiesh.tele.domain.model.AuthStep
import com.saiesh.tele.presentation.auth.AuthViewModel
import com.saiesh.tele.presentation.library.LibraryFragment
import com.saiesh.tele.presentation.setup.SetupFragment
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setupExitHandler()
        // Telegram rows load only once TDLib reports a signed-in account; loading them earlier makes
        // every chat request fail with "Unauthorized".
        val authViewModel = ViewModelProvider(this)[AuthViewModel::class.java]
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(authViewModel.uiState, MacSettings.telegramSkipped) { state, skipped -> state.step to skipped }
                    .collect { (step, skipped) ->
                        val current = supportFragmentManager.findFragmentById(R.id.main_browse_fragment)
                        if (step == AuthStep.SignedOut) {
                            restartSignedOut()
                        } else if (current is SetupFragment) {
                            // The guide may go on to the computer after Telegram signs in, so it opens home itself.
                        } else if (step == AuthStep.Authorized || skipped) {
                            // Without Telegram the library shows only the computer's videos. A page opened from
                            // the library sits on top of it; only fill an empty screen.
                            if (current == null) showLibrary()
                        } else if (step != AuthStep.Loading) {
                            supportFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
                            supportFragmentManager
                                .beginTransaction()
                                .replace(R.id.main_browse_fragment, SetupFragment())
                                .commit()
                        }
                    }
            }
        }
    }

    fun showLibrary() {
        supportFragmentManager
            .beginTransaction()
            .replace(R.id.main_browse_fragment, LibraryFragment())
            .commit()
    }

    /**
     * Signing out closes the TDLib client that every data source holds, so the app starts a fresh
     * process for the next account. Cached videos and thumbnails belong to the old account and go too.
     */
    private fun restartSignedOut() {
        cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        restart()
    }

    /** Starts the app again in a new process, which also gives TDLib a fresh start. */
    fun restart() {
        startActivity(Intent.makeRestartActivityTask(componentName))
        Runtime.getRuntime().exit(0)
    }

    private var exitCallback: OnBackPressedCallback? = null

    private fun setupExitHandler() {
        if (exitCallback != null) return
        var waitingForExit = false
        val handler = Handler(Looper.getMainLooper())
        exitCallback =
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    val fragment = supportFragmentManager.findFragmentById(R.id.main_browse_fragment)
                    if (fragment is LibraryFragment && supportFragmentManager.backStackEntryCount == 0) {
                        if (waitingForExit) {
                            finish()
                            return
                        }
                        waitingForExit = true
                        Toast
                            .makeText(this@MainActivity, R.string.library_press_back_to_exit, Toast.LENGTH_SHORT)
                            .show()
                        handler.removeCallbacksAndMessages(null)
                        handler.postDelayed({ waitingForExit = false }, 2000)
                        return
                    }
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        onBackPressedDispatcher.addCallback(this, exitCallback!!)
    }
}
