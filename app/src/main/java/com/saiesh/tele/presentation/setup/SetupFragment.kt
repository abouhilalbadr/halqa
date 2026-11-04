package com.saiesh.tele.presentation.setup

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.saiesh.tele.R
import com.saiesh.tele.app.MainActivity
import com.saiesh.tele.core.mac.MacSettings
import com.saiesh.tele.core.setup.KeyEntryPage
import com.saiesh.tele.core.setup.KeyEntryServer
import com.saiesh.tele.core.setup.SetupPath
import com.saiesh.tele.core.setup.SetupResume
import com.saiesh.tele.domain.model.AuthStep
import com.saiesh.tele.domain.model.AuthUiState
import com.saiesh.tele.presentation.auth.AuthViewModel
import com.saiesh.tele.presentation.auth.QrCodeRenderer
import com.saiesh.tele.presentation.mac.MacConnectForm
import com.saiesh.tele.presentation.mac.MacLibraryViewModel
import com.saiesh.tele.presentation.text.ArabicText
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The first-launch guide. The welcome screen asks where the videos are (Telegram, a computer or both); the
 * guide then walks through that path's steps with a progress bar, and ends on a done screen that opens home.
 * It also opens straight on the Telegram steps when someone who already has a computer connected chooses
 * Sign in to Telegram from home.
 */
class SetupFragment : Fragment(R.layout.fragment_setup) {
    private enum class Step(
        val layout: Int,
    ) {
        GetKey(R.layout.setup_step_get_key),
        EnterKey(R.layout.setup_step_enter_key),
        SignIn(R.layout.setup_step_sign_in),
        Need(R.layout.setup_step_need),
        Share(R.layout.setup_step_share),
        Connect(R.layout.setup_step_connect),
    }

    private val auth: AuthViewModel by activityViewModels()
    private val macViewModel: MacLibraryViewModel by activityViewModels()

    private var path: SetupPath? = null
    private var steps: List<Step> = emptyList()
    private var index = 0
    private var showingDone = false

    /** Opened from home to add Telegram: Back on the first step returns home rather than to the welcome screen. */
    private var fromHome = false

    /** Whether the sign-in step has seen this TV signed out; signing in then moves on by itself. */
    private var awaitingSignIn = false
    private var shownQrLink: String? = null
    private var showKeyRejected = false

    private var keyServer: KeyEntryServer? = null
    private var connectForm: MacConnectForm? = null

    private lateinit var welcome: View
    private lateinit var stepsView: View
    private lateinit var doneView: View
    private lateinit var progress: LinearLayout
    private lateinit var stepOf: TextView
    private lateinit var content: FrameLayout
    private lateinit var next: TextView
    private lateinit var connectFrame: View
    private lateinit var back: TextView
    private lateinit var languageSwitch: TextView

    /** The guide's language: picked on the welcome screen, otherwise the TV's. */
    private val arabic: Boolean by lazy {
        (SetupResume.language ?: resources.configuration.locales[0].language) == "ar"
    }

    /** The guide's text and layouts in its own language, whatever the TV's language. */
    private val localized: Context by lazy {
        val config =
            Configuration(requireContext().resources.configuration).apply {
                setLocale(if (arabic) ARABIC else Locale.ENGLISH)
            }
        requireContext().createConfigurationContext(config)
    }

    private val res: Resources
        get() = localized.resources

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View =
        inflater.cloneInContext(localized).inflate(R.layout.fragment_setup, container, false).apply {
            layoutDirection = if (arabic) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        }

    private val currentStep: Step?
        get() = if (stepsView.visibility == View.VISIBLE && !showingDone) steps.getOrNull(index) else null

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        welcome = view.findViewById(R.id.setup_welcome)
        stepsView = view.findViewById(R.id.setup_steps)
        doneView = view.findViewById(R.id.setup_done)
        progress = view.findViewById(R.id.setup_progress)
        stepOf = view.findViewById(R.id.setup_step_of)
        content = view.findViewById(R.id.setup_step_content)
        next = view.findViewById(R.id.setup_next)
        connectFrame = view.findViewById(R.id.setup_connect_frame)
        back = view.findViewById(R.id.setup_back)
        if (arabic) useArabicFont(view)
        bindLanguageSwitch(view.findViewById(R.id.setup_language))

        view.findViewById<View>(R.id.setup_choice_telegram).setOnClickListener { choose(SetupPath.Telegram) }
        view.findViewById<View>(R.id.setup_choice_computer).setOnClickListener { choose(SetupPath.Computer) }
        view.findViewById<View>(R.id.setup_choice_both).setOnClickListener { choose(SetupPath.Both) }
        next.setOnClickListener { onNext() }
        back.setOnClickListener { goBack() }
        view.findViewById<View>(R.id.setup_done_button).setOnClickListener { finish() }

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    when {
                        showingDone -> showStep(steps.lastIndex)
                        stepsView.visibility == View.VISIBLE -> goBack()
                        else -> {
                            // On the welcome screen Back leaves the app, as it did on the old sign-in screen.
                            isEnabled = false
                            requireActivity().onBackPressedDispatcher.onBackPressed()
                        }
                    }
                }
            },
        )

        val rejectedPath = SetupResume.takeRejectedKeyPath()
        when {
            rejectedPath != null -> {
                fromHome = rejectedPath == SetupPath.Telegram && MacSettings.login.value != null
                showKeyRejected = true
                choose(rejectedPath, Step.EnterKey)
            }
            MacSettings.login.value != null -> {
                fromHome = true
                choose(SetupPath.Telegram)
            }
            else -> showWelcome()
        }
        if (arguments?.getBoolean(ARG_FOCUS_LANGUAGE) == true) focus(languageSwitch)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                auth.uiState.collect { state -> onAuthState(state) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Coming back to the app on a step that listens for something picks it up again.
        when (currentStep) {
            Step.EnterKey -> startKeyServer()
            Step.Connect -> connectForm?.start()
            else -> Unit
        }
    }

    override fun onStop() {
        stopKeyServer()
        connectForm?.stop()
        super.onStop()
    }

    override fun onDestroyView() {
        leaveStep()
        super.onDestroyView()
    }

    private fun showWelcome() {
        leaveStep()
        path = null
        welcome.visibility = View.VISIBLE
        languageSwitch.visibility = View.VISIBLE
        stepsView.visibility = View.GONE
        doneView.visibility = View.GONE
        requireView().findViewById<View>(R.id.setup_choice_telegram).post {
            view?.findViewById<View>(R.id.setup_choice_telegram)?.requestFocus()
        }
    }

    private fun choose(
        chosen: SetupPath,
        startAt: Step? = null,
    ) {
        path = chosen
        val telegram = if (auth.hasKeys()) listOf(Step.SignIn) else listOf(Step.GetKey, Step.EnterKey, Step.SignIn)
        val computer = listOf(Step.Need, Step.Share, Step.Connect)
        steps =
            when (chosen) {
                SetupPath.Telegram -> telegram
                SetupPath.Computer -> computer
                SetupPath.Both -> telegram + computer
            }
        showStep(startAt?.let { steps.indexOf(it) }?.takeIf { it >= 0 } ?: 0)
    }

    private fun showStep(position: Int) {
        leaveStep()
        index = position
        showingDone = false
        welcome.visibility = View.GONE
        languageSwitch.visibility = View.GONE
        doneView.visibility = View.GONE
        stepsView.visibility = View.VISIBLE

        progress.removeAllViews()
        val gap = resources.getDimensionPixelSize(R.dimen.setup_segment_gap)
        steps.indices.forEach { i ->
            progress.addView(
                View(requireContext()).apply {
                    setBackgroundResource(if (i <= position) R.drawable.setup_segment_on else R.drawable.setup_segment_off)
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                    if (i > 0) marginStart = gap
                },
            )
        }
        stepOf.text = res.getString(R.string.setup_step_of, position + 1, steps.size)

        content.removeAllViews()
        val step = steps[position]
        val stepView = LayoutInflater.from(localized).inflate(step.layout, content, true)
        if (arabic) useArabicFont(stepView)
        keyboardOnOk(stepView)
        next.visibility = View.VISIBLE
        connectFrame.visibility = View.GONE
        when (step) {
            Step.GetKey -> {
                stepView.findViewById<ImageView>(R.id.setup_site_qr).setImageBitmap(QrCodeRenderer.render(TELEGRAM_APPS_URL, QR_SIZE_PX))
                focus(next)
            }
            Step.EnterKey -> {
                val error = stepView.findViewById<TextView>(R.id.setup_key_error)
                if (showKeyRejected) {
                    showKeyRejected = false
                    error.setText(R.string.setup_key_rejected)
                    error.visibility = View.VISIBLE
                }
                stepView.findViewById<EditText>(R.id.setup_api_hash).setOnEditorActionListener { _, _, _ ->
                    next.requestFocus()
                    false
                }
                startKeyServer()
                focus(stepView.findViewById(R.id.setup_api_id))
            }
            Step.SignIn -> {
                stepView.findViewById<View>(R.id.setup_waiting_dot).startAnimation(
                    AlphaAnimation(1f, 0.3f).apply {
                        duration = 800
                        repeatMode = Animation.REVERSE
                        repeatCount = Animation.INFINITE
                    },
                )
                shownQrLink = null
                awaitingSignIn = auth.uiState.value.step != AuthStep.Authorized
                renderSignIn(auth.uiState.value)
            }
            Step.Need, Step.Share -> focus(next)
            Step.Connect -> {
                next.visibility = View.GONE
                connectFrame.visibility = View.VISIBLE
                val form = MacConnectForm(requireView(), macViewModel) { advance() }
                connectForm = form
                form.start()
                focus(form.firstFocus())
            }
        }
    }

    /** Stops whatever the step on screen was listening for. */
    private fun leaveStep() {
        stopKeyServer()
        connectForm?.detach()
        connectForm = null
    }

    private fun onNext() {
        when (currentStep) {
            Step.EnterKey -> submitTypedKeys()
            Step.SignIn -> submitSignIn()
            else -> advance()
        }
    }

    private fun advance() {
        if (index < steps.lastIndex) showStep(index + 1) else showDone()
    }

    private fun goBack() {
        when {
            index > 0 -> showStep(index - 1)
            fromHome -> {
                // Back to home without Telegram, as before choosing Sign in to Telegram.
                leaveStep()
                MacSettings.setTelegramSkipped(true)
                (activity as? MainActivity)?.showLibrary()
            }
            else -> showWelcome()
        }
    }

    private fun showDone() {
        leaveStep()
        showingDone = true
        stepsView.visibility = View.GONE
        welcome.visibility = View.GONE
        languageSwitch.visibility = View.GONE
        doneView.visibility = View.VISIBLE
        val signedIn = auth.uiState.value.step == AuthStep.Authorized
        val body =
            when {
                path == SetupPath.Both && signedIn -> R.string.setup_done_both
                path == SetupPath.Computer || !signedIn -> R.string.setup_done_computer
                else -> R.string.setup_done_telegram
            }
        doneView.findViewById<TextView>(R.id.setup_done_body).setText(body)
        focus(doneView.findViewById(R.id.setup_done_button))
    }

    private fun finish() {
        // Without a Telegram account home shows only the computer's videos, with Sign in to Telegram on top.
        if (auth.uiState.value.step != AuthStep.Authorized) MacSettings.setTelegramSkipped(true)
        (activity as? MainActivity)?.showLibrary()
    }

    // Telegram key

    private fun submitTypedKeys() {
        val stepView = content.getChildAt(0) ?: return
        val apiId = stepView.findViewById<EditText>(R.id.setup_api_id).text.toString().trim()
        val apiHash =
            stepView
                .findViewById<EditText>(R.id.setup_api_hash)
                .text
                .toString()
                .trim()
                .lowercase()
        submitKeys(apiId, apiHash)
    }

    private fun submitKeys(
        apiId: String,
        apiHash: String,
    ) {
        if (!KeyEntryServer.isValidApiId(apiId) || !KeyEntryServer.isValidApiHash(apiHash)) {
            content.getChildAt(0)?.findViewById<TextView>(R.id.setup_key_error)?.let {
                it.setText(R.string.setup_key_invalid)
                it.visibility = View.VISIBLE
            }
            return
        }
        auth.submitApiKeys(apiId, apiHash)
        advance()
    }

    private fun startKeyServer() {
        if (keyServer != null) return
        val stepView = content.getChildAt(0) ?: return
        val phoneCard = stepView.findViewById<View>(R.id.setup_phone_card) ?: return
        val server =
            KeyEntryServer(
                KeyEntryPage(
                    language = if (arabic) "ar" else "en",
                    rtl = arabic,
                    title = res.getString(R.string.setup_phone_title),
                    intro = res.getString(R.string.setup_phone_intro),
                    apiIdLabel = res.getString(R.string.setup_api_id_hint),
                    apiHashLabel = res.getString(R.string.setup_api_hash_hint),
                    send = res.getString(R.string.setup_phone_send),
                    invalid = res.getString(R.string.setup_key_invalid),
                    sent = res.getString(R.string.setup_phone_sent),
                ),
            ) { apiId, apiHash ->
                if (currentStep != Step.EnterKey) return@KeyEntryServer
                content.getChildAt(0)?.let {
                    it.findViewById<EditText>(R.id.setup_api_id).setText(apiId)
                    it.findViewById<EditText>(R.id.setup_api_hash).setText(apiHash)
                }
                submitKeys(apiId, apiHash)
            }
        val url = server.start()
        if (url == null) {
            // No home-network address: typing with the remote is the only way.
            phoneCard.visibility = View.GONE
            return
        }
        keyServer = server
        phoneCard.visibility = View.VISIBLE
        stepView.findViewById<ImageView>(R.id.setup_phone_qr).setImageBitmap(QrCodeRenderer.render(url, QR_SIZE_PX))
    }

    private fun stopKeyServer() {
        keyServer?.stop()
        keyServer = null
    }

    // Telegram sign-in

    private fun onAuthState(state: AuthUiState) {
        if (state.keysRejected && path != null && path != SetupPath.Computer) {
            // TDLib takes its keys once per run, so a new key needs a fresh start; the guide reopens on the key
            // step. Keys built into the app can't be replaced, so then the error just stays on screen.
            if (!auth.hasKeys()) {
                SetupResume.rememberRejectedKey(path!!)
                (activity as? MainActivity)?.restart()
                return
            }
        }
        if (currentStep == Step.SignIn) renderSignIn(state)
    }

    private fun renderSignIn(state: AuthUiState) {
        val stepView = content.getChildAt(0) ?: return
        val qrSteps = stepView.findViewById<View>(R.id.setup_qr_steps)
        val qr = stepView.findViewById<ImageView>(R.id.setup_sign_in_qr)
        val signedIn = stepView.findViewById<TextView>(R.id.setup_signed_in)
        val input = stepView.findViewById<EditText>(R.id.setup_sign_in_input)
        val message = stepView.findViewById<TextView>(R.id.setup_sign_in_message)
        val loading = stepView.findViewById<View>(R.id.setup_sign_in_loading)

        if (state.step == AuthStep.Authorized) {
            qrSteps.visibility = View.GONE
            qr.visibility = View.INVISIBLE
            input.visibility = View.GONE
            message.visibility = View.GONE
            loading.visibility = View.GONE
            signedIn.text = state.accountName?.let { res.getString(R.string.setup_signed_in, it) } ?: res.getString(R.string.setup_done_title)
            if (arabic) ArabicText.style(signedIn, true)
            signedIn.visibility = View.VISIBLE
            next.visibility = View.VISIBLE
            if (awaitingSignIn) {
                awaitingSignIn = false
                advance()
            } else if (!next.isFocused && !back.isFocused) {
                focus(next)
            }
            return
        }
        awaitingSignIn = true
        signedIn.visibility = View.GONE

        val showQr = state.step == AuthStep.ScanQr && state.qrLink != null
        if (showQr && state.qrLink != shownQrLink) {
            shownQrLink = state.qrLink
            qr.setImageBitmap(QrCodeRenderer.render(state.qrLink!!, QR_SIZE_PX))
        }
        qr.visibility = if (showQr) View.VISIBLE else View.INVISIBLE
        qrSteps.visibility = if (state.step == AuthStep.ScanQr || state.step == AuthStep.Loading || state.step == AuthStep.EnterApiKeys) View.VISIBLE else View.GONE

        val field =
            when (state.step) {
                AuthStep.EnterPhone -> R.string.auth_phone_hint to InputType.TYPE_CLASS_PHONE
                AuthStep.EnterCode -> R.string.auth_code_hint to InputType.TYPE_CLASS_NUMBER
                AuthStep.EnterPassword -> R.string.auth_password_hint to (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
                else -> null
            }
        if (field != null) {
            val wasHidden = input.visibility != View.VISIBLE || input.tag != state.step
            input.visibility = View.VISIBLE
            next.visibility = View.VISIBLE
            if (wasHidden) {
                input.tag = state.step
                input.setText("")
                input.setHint(field.first)
                input.inputType = field.second
                focus(input)
            }
        } else {
            input.visibility = View.GONE
            next.visibility = View.GONE
            if (!back.isFocused) focus(back)
        }

        val text = state.message?.takeIf { field != null && it.isNotBlank() }
        message.text = text
        message.visibility = if (text == null) View.GONE else View.VISIBLE
        loading.visibility = if (state.isLoading || (state.step != AuthStep.ScanQr && field == null)) View.VISIBLE else View.GONE
    }

    private fun submitSignIn() {
        val state = auth.uiState.value
        if (state.step == AuthStep.Authorized) {
            advance()
            return
        }
        val value =
            content
                .getChildAt(0)
                ?.findViewById<EditText>(R.id.setup_sign_in_input)
                ?.text
                ?.toString()
                .orEmpty()
        when (state.step) {
            AuthStep.EnterPhone -> {
                auth.onPhoneChange(value.trim())
                auth.submitPhone()
            }
            AuthStep.EnterCode -> {
                auth.onCodeChange(value.trim())
                auth.submitCode()
            }
            AuthStep.EnterPassword -> {
                auth.onPasswordChange(value)
                auth.submitPassword()
            }
            else -> Unit
        }
    }

    /**
     * English · العربية, the current language brighter. Picking the other one reopens the guide in it; the choice
     * is remembered for the guide and its phone page only.
     */
    private fun bindLanguageSwitch(view: TextView) {
        languageSwitch = view
        val english = "English"
        val arabicName = "العربية"
        val text = SpannableStringBuilder().append(english).append("  ·  ").append(arabicName)
        val current = if (arabic) text.length - arabicName.length to text.length else 0 to english.length
        val other = if (arabic) 0 to english.length else text.length - arabicName.length to text.length
        text.setSpan(ForegroundColorSpan(Color.WHITE), current.first, current.second, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(StyleSpan(Typeface.BOLD), current.first, current.second, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(ForegroundColorSpan(MUTED), other.first, other.second, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(ForegroundColorSpan(MUTED), english.length, english.length + 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        view.text = text
        view.textDirection = View.TEXT_DIRECTION_LTR
        ArabicText.style(view, true)
        view.setOnClickListener {
            SetupResume.language = if (arabic) "en" else "ar"
            val again = SetupFragment().apply { arguments = Bundle().apply { putBoolean(ARG_FOCUS_LANGUAGE, true) } }
            parentFragmentManager.beginTransaction().replace(id, again).commit()
        }
    }

    private fun focus(target: View) {
        target.post { target.requestFocus() }
    }

    /**
     * The TV keyboard covers half the screen, so it opens when OK is pressed on a field rather than whenever a
     * field gets focus; the instructions and codes stay readable while moving around.
     */
    private fun keyboardOnOk(root: View) {
        when (root) {
            is EditText -> {
                root.showSoftInputOnFocus = false
                root.setOnClickListener {
                    val keyboard = requireContext().getSystemService(InputMethodManager::class.java)
                    keyboard?.showSoftInput(it, InputMethodManager.SHOW_IMPLICIT)
                }
            }
            is ViewGroup -> (0 until root.childCount).forEach { keyboardOnOk(root.getChildAt(it)) }
        }
    }

    /** The system font has no Arabic that matches the app, so every label switches to Tajawal. */
    private fun useArabicFont(root: View) {
        when (root) {
            is TextView -> ArabicText.style(root, true)
            is ViewGroup -> (0 until root.childCount).forEach { useArabicFont(root.getChildAt(it)) }
        }
    }

    private companion object {
        const val ARG_FOCUS_LANGUAGE = "focus_language"
        val MUTED = Color.parseColor("#8B919C")

        /** Arabic with Western digits, as elsewhere in the app. */
        val ARABIC: Locale = Locale.forLanguageTag("ar-u-nu-latn")
        const val TELEGRAM_APPS_URL = "https://my.telegram.org/apps"
        const val QR_SIZE_PX = 480
    }
}
