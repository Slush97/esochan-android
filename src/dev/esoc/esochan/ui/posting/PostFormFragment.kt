/*
 * esochan (Meta Imageboard Client)
 * Copyright (C) 2014-2016  miku-nyan <https://github.com/miku-nyan>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package dev.esoc.esochan.ui.posting

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import dev.esoc.esochan.R
import dev.esoc.esochan.api.interfaces.CancellableTask
import dev.esoc.esochan.api.models.BoardModel
import dev.esoc.esochan.api.models.CaptchaModel
import dev.esoc.esochan.api.models.SendPostModel
import dev.esoc.esochan.common.Async
import dev.esoc.esochan.common.Logger
import dev.esoc.esochan.common.MainApplication
import dev.esoc.esochan.http.interactive.InteractiveException
import dev.esoc.esochan.lib.AttachmentIconUtils
import dev.esoc.esochan.lib.UriFileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class PostFormFragment : BottomSheetDialogFragment() {

    companion object {
        private const val TAG = "PostFormFragment"
        private const val ARG_HASH = "hash"
        private const val ARG_BOARD_MODEL = "board_model"
        private const val ARG_SEND_POST_MODEL = "send_post_model"

        fun newInstance(hash: String, boardModel: BoardModel, sendPostModel: SendPostModel): PostFormFragment {
            return PostFormFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_HASH, hash)
                    putSerializable(ARG_BOARD_MODEL, boardModel)
                    putSerializable(ARG_SEND_POST_MODEL, sendPostModel)
                }
            }
        }
    }

    private lateinit var hash: String
    private lateinit var boardModel: BoardModel
    private lateinit var sendPostModel: SendPostModel
    private val chan by lazy { MainApplication.getInstance().getChanModule(sendPostModel.chanName) }
    private val settings by lazy { MainApplication.getInstance().settings }

    private var currentTask: CancellableTask? = null
    private val attachments = ArrayList<File>()
    private val attachmentPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) result.data?.data?.let(::importPickedFile)
    }
    private val galleryPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) result.data?.data?.let(::importPickedFile)
    }

    // Views
    private lateinit var commentField: EditText
    private lateinit var subjectField: EditText
    private lateinit var nameField: EditText
    private lateinit var emailField: EditText
    private lateinit var passwordField: EditText
    private lateinit var sageCheckbox: CheckBox
    private lateinit var custommarkCheckbox: CheckBox
    private lateinit var spinner: Spinner
    private lateinit var attachmentsLayout: LinearLayout
    private lateinit var markupLayout: LinearLayout
    private lateinit var captchaLayout: View
    private lateinit var captchaView: ImageView
    private lateinit var captchaLoading: View
    private lateinit var captchaField: EditText
    private lateinit var sendButton: View

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val args = requireArguments()
        hash = args.getString(ARG_HASH)!!
        boardModel = args.getSerializable(ARG_BOARD_MODEL) as BoardModel
        sendPostModel = args.getSerializable(ARG_SEND_POST_MODEL) as SendPostModel
    }

    override fun getTheme(): Int = R.style.ThemeOverlay_BottomSheetDialog

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return (super.onCreateDialog(savedInstanceState) as BottomSheetDialog).also { dialog ->
            dialog.setOnShowListener {
                val sheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                    ?: return@setOnShowListener
                val tv = TypedValue()
                requireActivity().theme.resolveAttribute(R.attr.activityRootBackground, tv, true)
                sheet.setBackgroundColor(tv.data)
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        // Resolve form theme attrs against the activity theme, not the dialog fallback.
        val themedInflater = inflater.cloneInContext(requireActivity())
        return themedInflater.inflate(R.layout.fragment_post_form, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        configureVisibility()
        readSendPostModel()
        setupListeners()
        setCaptcha()
    }

    override fun onStart() {
        super.onStart()
        val behavior = BottomSheetBehavior.from(requireView().parent as View)
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        behavior.skipCollapsed = true
        behavior.isFitToContents = true
    }

    private fun bindViews(view: View) {
        view.findViewById<TextView>(R.id.post_form_title).text =
            getString(if (sendPostModel.threadNumber == null) R.string.postform_title_thread else R.string.postform_title_post)

        commentField = view.findViewById(R.id.post_form_comment)
        subjectField = view.findViewById(R.id.post_form_subject)
        nameField = view.findViewById(R.id.post_form_name)
        emailField = view.findViewById(R.id.post_form_email)
        passwordField = view.findViewById(R.id.post_form_password)
        sageCheckbox = view.findViewById(R.id.post_form_sage)
        custommarkCheckbox = view.findViewById(R.id.post_form_custommark)
        spinner = view.findViewById(R.id.post_form_spinner)
        attachmentsLayout = view.findViewById(R.id.post_form_attachments_layout)
        markupLayout = view.findViewById(R.id.post_form_markup_layout)
        captchaLayout = view.findViewById(R.id.post_form_captcha_layout)
        captchaView = view.findViewById(R.id.post_form_captcha_view)
        captchaLoading = view.findViewById(R.id.post_form_captcha_loading)
        captchaField = view.findViewById(R.id.post_form_captcha_field)
        sendButton = view.findViewById(R.id.post_form_send_button)

        applyFormColors(view)
    }

    private fun applyFormColors(view: View) {
        val theme = requireActivity().theme
        val fg = resolveColor(theme, R.attr.postForeground)
        // itemInfo is too dim on several themes for empty-field hints
        val hint = (fg and 0x00FFFFFF) or (0x99000000.toInt())
        val accent = resolveColor(theme, R.attr.urlLinkForeground)
        val onAccent = resolveColor(theme, R.attr.activityRootBackground)
        view.findViewById<TextView>(R.id.post_form_title).setTextColor(fg)
        for (field in arrayOf(commentField, subjectField, nameField, emailField, passwordField, captchaField)) {
            field.setTextColor(fg)
            field.setHintTextColor(hint)
        }
        sageCheckbox.setTextColor(fg)
        custommarkCheckbox.setTextColor(fg)
        (sendButton as? com.google.android.material.button.MaterialButton)?.apply {
            backgroundTintList = android.content.res.ColorStateList.valueOf(accent)
            setTextColor(onAccent)
        }
    }

    private fun resolveColor(theme: android.content.res.Resources.Theme, attr: Int): Int {
        val tv = TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return if (tv.resourceId != 0) {
            requireActivity().getColor(tv.resourceId)
        } else {
            tv.data
        }
    }

    private fun configureVisibility() {
        val view = requireView()

        // Markup buttons
        val markupScroll = view.findViewById<View>(R.id.post_form_markup_scroll)
        val markupEnabled = booleanArrayOf(
            PostFormMarkup.hasMarkupFeature(boardModel.markType, PostFormMarkup.FEATURE_QUOTE),
            PostFormMarkup.hasMarkupFeature(boardModel.markType, PostFormMarkup.FEATURE_BOLD),
            PostFormMarkup.hasMarkupFeature(boardModel.markType, PostFormMarkup.FEATURE_ITALIC),
            PostFormMarkup.hasMarkupFeature(boardModel.markType, PostFormMarkup.FEATURE_UNDERLINE),
            PostFormMarkup.hasMarkupFeature(boardModel.markType, PostFormMarkup.FEATURE_STRIKE),
            PostFormMarkup.hasMarkupFeature(boardModel.markType, PostFormMarkup.FEATURE_SPOILER),
        )
        if (markupEnabled.any { it }) {
            markupScroll.visibility = View.VISIBLE
            if (!markupEnabled[0]) view.findViewById<View>(R.id.post_form_mark_quote).visibility = View.GONE
            if (!markupEnabled[1]) view.findViewById<View>(R.id.post_form_mark_bold).visibility = View.GONE
            if (!markupEnabled[2]) view.findViewById<View>(R.id.post_form_mark_italic).visibility = View.GONE
            if (!markupEnabled[3]) view.findViewById<View>(R.id.post_form_mark_underline).visibility = View.GONE
            if (!markupEnabled[4]) view.findViewById<View>(R.id.post_form_mark_strike).visibility = View.GONE
            if (!markupEnabled[5]) view.findViewById<View>(R.id.post_form_mark_spoiler).visibility = View.GONE
        } else {
            markupScroll.visibility = View.GONE
        }

        // Options section
        val optionsContainer = view.findViewById<View>(R.id.post_form_options_container)
        val optionsToggle = view.findViewById<View>(R.id.post_form_options_toggle)
        val optionsArrow = view.findViewById<ImageView>(R.id.post_form_options_arrow)

        val hidePersonal = settings.isHidePersonalData
        val nameEmailLayout = view.findViewById<View>(R.id.post_form_name_email_layout)
        val passwordLayout = view.findViewById<View>(R.id.post_form_password_layout)

        if (hidePersonal) {
            nameEmailLayout.visibility = View.GONE
            passwordLayout.visibility = View.GONE
        } else {
            nameEmailLayout.visibility = if (boardModel.allowNames || boardModel.allowEmails) View.VISIBLE else View.GONE
            nameField.visibility = if (boardModel.allowNames) View.VISIBLE else View.GONE
            emailField.visibility = if (boardModel.allowEmails) View.VISIBLE else View.GONE
            passwordLayout.visibility = if (boardModel.allowDeletePosts || boardModel.allowDeleteFiles) View.VISIBLE else View.GONE

            if (boardModel.allowNames && !boardModel.allowEmails) {
                nameField.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
            } else if (!boardModel.allowNames && boardModel.allowEmails) {
                emailField.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
        }

        subjectField.visibility = if (boardModel.allowSubjects) View.VISIBLE else View.GONE

        val checkboxLayout = view.findViewById<View>(R.id.post_form_checkbox_layout)
        checkboxLayout.visibility = if (boardModel.allowSage || boardModel.allowCustomMark) View.VISIBLE else View.GONE
        sageCheckbox.visibility = if (boardModel.allowSage) View.VISIBLE else View.GONE
        custommarkCheckbox.visibility = if (boardModel.allowCustomMark) View.VISIBLE else View.GONE
        if (boardModel.customMarkDescription != null) custommarkCheckbox.text = boardModel.customMarkDescription
        spinner.visibility = if (boardModel.allowIcons) View.VISIBLE else View.GONE

        if (boardModel.allowIcons) {
            spinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, boardModel.iconDescriptions)
        }

        // Determine if options section has anything visible
        val hasOptions = (!hidePersonal && (boardModel.allowNames || boardModel.allowEmails
                || boardModel.allowDeletePosts || boardModel.allowDeleteFiles))
                || boardModel.allowSubjects
                || boardModel.allowSage || boardModel.allowCustomMark
                || boardModel.allowIcons

        if (!hasOptions) {
            optionsToggle.visibility = View.GONE
            optionsContainer.visibility = View.GONE
        } else {
            optionsToggle.setOnClickListener {
                val isVisible = optionsContainer.visibility == View.VISIBLE
                optionsContainer.visibility = if (isVisible) View.GONE else View.VISIBLE
                optionsArrow.setImageResource(
                    if (isVisible) android.R.drawable.arrow_down_float else android.R.drawable.arrow_up_float
                )
            }
        }
    }

    private fun readSendPostModel() {
        if (boardModel.allowNames) nameField.setText(sendPostModel.name ?: "")
        if (boardModel.allowSubjects) subjectField.setText(sendPostModel.subject ?: "")
        if (boardModel.allowEmails) emailField.setText(sendPostModel.email ?: "")
        commentField.setText(sendPostModel.comment ?: "")
        commentField.text?.let { text ->
            var pos = sendPostModel.commentPosition
            if (pos > text.length) pos = -1
            if (pos < 0) pos = text.length
            commentField.setSelection(pos)
        }
        if (boardModel.allowDeletePosts || boardModel.allowDeleteFiles) {
            passwordField.setText(sendPostModel.password ?: "")
        }
        if (boardModel.allowIcons) spinner.setSelection(if (sendPostModel.icon != -1) sendPostModel.icon else 0)
        if (boardModel.allowSage) sageCheckbox.isChecked = sendPostModel.sage
        if (boardModel.ignoreEmailIfSage && boardModel.allowSage && sendPostModel.sage) emailField.isEnabled = false
        if (boardModel.allowCustomMark) custommarkCheckbox.isChecked = sendPostModel.custommark
        captchaField.setText(sendPostModel.captchaAnswer ?: "")
        sendPostModel.attachments?.forEach { handleFile(it) }
    }

    private fun setupListeners() {
        // Send button
        sendButton.setOnClickListener { send() }

        // Sage checkbox
        sageCheckbox.setOnClickListener {
            emailField.isEnabled = !(sageCheckbox.isChecked && boardModel.ignoreEmailIfSage)
        }

        // Markup buttons
        val markupIds = intArrayOf(
            R.id.post_form_mark_quote, R.id.post_form_mark_bold, R.id.post_form_mark_italic,
            R.id.post_form_mark_underline, R.id.post_form_mark_strike, R.id.post_form_mark_spoiler
        )
        val markupFeatures = intArrayOf(
            PostFormMarkup.FEATURE_QUOTE, PostFormMarkup.FEATURE_BOLD, PostFormMarkup.FEATURE_ITALIC,
            PostFormMarkup.FEATURE_UNDERLINE, PostFormMarkup.FEATURE_STRIKE, PostFormMarkup.FEATURE_SPOILER
        )
        for (i in markupIds.indices) {
            requireView().findViewById<View>(markupIds[i]).setOnClickListener {
                try {
                    PostFormMarkup.markup(boardModel.markType, commentField, markupFeatures[i])
                } catch (e: Exception) {
                    Logger.e(TAG, e)
                }
            }
        }

        // Attach buttons
        requireView().findViewById<View>(R.id.post_form_attach_button).setOnClickListener { attachFile() }
        requireView().findViewById<View>(R.id.post_form_gallery_button).setOnClickListener { attachGallery() }

        // Captcha tap to refresh
        captchaView.setOnClickListener { loadCaptchaOrDefer() }

        // Enter in captcha field sends
        captchaField.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_ENTER) {
                send()
                true
            } else false
        }
    }

    private fun send() {
        saveSendPostModel()
        when {
            boardModel.requiredFileForNewThread && sendPostModel.threadNumber == null && sendPostModel.attachments.isEmpty() -> {
                Toast.makeText(requireContext(), R.string.postform_required_file, Toast.LENGTH_LONG).show()
            }
            sendPostModel.comment.isEmpty() && sendPostModel.attachments.isEmpty() -> {
                Toast.makeText(requireContext(), R.string.postform_empty_comment, Toast.LENGTH_LONG).show()
            }
            else -> {
                MainApplication.getInstance().draftsCache.clearLastCaptcha()
                val intent = Intent(requireContext(), PostingService::class.java).apply {
                    putExtra(PostingService.EXTRA_PAGE_HASH, hash)
                    putExtra(PostingService.EXTRA_SEND_POST_MODEL, sendPostModel)
                    putExtra(PostingService.EXTRA_BOARD_MODEL, boardModel)
                }
                ContextCompat.startForegroundService(requireContext(), intent)
                dismiss()
            }
        }
    }

    private fun saveSendPostModel() {
        val hidePersonal = settings.isHidePersonalData
        sendPostModel.name = if (hidePersonal && boardModel.allowNames) settings.defaultName else nameField.text.toString()
        sendPostModel.subject = subjectField.text.toString()
        sendPostModel.email = if (hidePersonal && boardModel.allowEmails) settings.defaultEmail else emailField.text.toString()
        sendPostModel.comment = commentField.text.toString()
        sendPostModel.commentPosition = commentField.selectionStart
        sendPostModel.password = if (hidePersonal && (boardModel.allowDeletePosts || boardModel.allowDeleteFiles))
            chan.defaultPassword else passwordField.text.toString()
        sendPostModel.icon = if (boardModel.allowIcons) spinner.selectedItemPosition else -1
        sendPostModel.sage = sageCheckbox.isChecked
        sendPostModel.custommark = custommarkCheckbox.isChecked
        sendPostModel.captchaAnswer = captchaField.text.toString()
        sendPostModel.attachments = attachments.toTypedArray()
        sendPostModel.randomHash = boardModel.allowRandomHash && settings.isRandomHash
        MainApplication.getInstance().draftsCache.put(hash, sendPostModel)
    }

    // --- Captcha ---
    //
    // Strategy: For channels with interactive captcha (like 4chan), the captcha
    // is NOT fetched when the form opens. Instead, it's handled at send time
    // by PostingService (which triggers the InteractiveException flow).
    // This lets the user compose their post without being blocked by cooldowns.
    //
    // For channels with bitmap captchas, we load normally so the user can see
    // and type the captcha answer before sending.

    private var usesInteractiveCaptcha = false

    private fun setCaptcha() {
        val lastHash = MainApplication.getInstance().draftsCache.lastCaptchaHash
        if (lastHash != null && lastHash == hash) {
            switchToCaptcha(MainApplication.getInstance().draftsCache.lastCaptcha, clearField = false)
        } else {
            // Try to load captcha in background; if it's interactive, skip it
            loadCaptchaOrDefer()
        }
    }

    private fun loadCaptchaOrDefer() {
        switchToLoadingCaptcha()
        currentTask?.cancel()
        MainApplication.getInstance().draftsCache.clearLastCaptcha()
        Async.runAsync {
            try {
                val task = CancellableTask.BaseCancellableTask()
                currentTask = task
                val captcha = chan.getNewCaptcha(sendPostModel.boardName, sendPostModel.threadNumber, null, task)
                if (task.isCancelled) return@runAsync
                Async.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    switchToCaptcha(captcha)
                    MainApplication.getInstance().draftsCache.setLastCaptcha(hash, captcha)
                }
            } catch (e: Exception) {
                if (currentTask?.isCancelled == true) return@runAsync
                if (e is InteractiveException) {
                    // This channel uses interactive captcha (e.g. 4chan).
                    // Don't show the captcha dialog now - defer to send time.
                    usesInteractiveCaptcha = true
                    Async.runOnUiThread {
                        if (!isAdded) return@runOnUiThread
                        captchaLayout.visibility = View.GONE
                        captchaField.visibility = View.GONE
                        captchaLoading.visibility = View.GONE
                        sendButton.isEnabled = true
                    }
                } else {
                    Logger.e(TAG, e)
                    val msg = e.message ?: ""
                    if (currentTask?.isCancelled == true) return@runAsync
                    Async.runOnUiThread {
                        if (!isAdded) return@runOnUiThread
                        switchToErrorCaptcha(msg)
                    }
                }
            }
        }
    }

    private fun switchToLoadingCaptcha() {
        captchaLoading.visibility = View.VISIBLE
        captchaView.visibility = View.GONE
        captchaField.isEnabled = false
        sendButton.isEnabled = false
    }

    private fun switchToCaptcha(captchaModel: CaptchaModel?, clearField: Boolean = true) {
        if (clearField) captchaField.setText("")
        sendButton.isEnabled = true
        if (captchaModel != null) {
            captchaLoading.visibility = View.GONE
            captchaView.visibility = View.VISIBLE
            captchaView.setImageBitmap(captchaModel.bitmap)
            captchaField.isEnabled = true
            captchaField.inputType = if (captchaModel.type == CaptchaModel.TYPE_NORMAL)
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            else InputType.TYPE_CLASS_NUMBER
        } else {
            captchaLayout.visibility = View.GONE
            captchaField.visibility = View.GONE
        }
    }

    private fun switchToErrorCaptcha(message: String? = null) {
        captchaLoading.visibility = View.GONE
        captchaView.visibility = View.VISIBLE
        captchaView.setImageResource(android.R.drawable.ic_dialog_alert)
        captchaField.isEnabled = false
        sendButton.isEnabled = false
        if (message != null) {
            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
        }
    }

    // --- File attachment ---

    private fun attachFile() {
        if (!canAttachOneMore()) {
            Toast.makeText(requireContext(), R.string.postform_max_attachments, Toast.LENGTH_LONG).show()
            return
        }
        attachmentPicker.launch(UriFileUtils.createOpenDocumentIntent(boardModel.attachmentsFormatFilters))
    }

    private fun attachGallery() {
        if (!canAttachOneMore()) {
            Toast.makeText(requireContext(), R.string.postform_max_attachments, Toast.LENGTH_LONG).show()
            return
        }
        galleryPicker.launch(UriFileUtils.createImagePickerIntent())
    }

    private fun importPickedFile(uri: Uri) {
        val appContext = requireContext().applicationContext
        lifecycleScope.launch {
            val file = try {
                withContext(Dispatchers.IO) {
                    UriFileUtils.copyToCache(appContext, uri, UriFileUtils.MAX_ATTACHMENT_BYTES)
                }
            } catch (_: UriFileUtils.FileTooLargeException) {
                if (isAdded) {
                    val maxMb = UriFileUtils.MAX_ATTACHMENT_BYTES / (1024L * 1024L)
                    Toast.makeText(requireContext(), getString(R.string.postform_attachment_too_large, maxMb), Toast.LENGTH_LONG).show()
                }
                return@launch
            } catch (e: Exception) {
                Logger.e(TAG, e)
                if (isAdded) Toast.makeText(requireContext(), R.string.postform_cannot_attach, Toast.LENGTH_LONG).show()
                return@launch
            }

            if (!UriFileUtils.hasAllowedExtension(file, boardModel.attachmentsFormatFilters)) {
                if (uri.scheme == "content") file.delete()
                if (isAdded) Toast.makeText(requireContext(), R.string.postform_unsupported_attachment, Toast.LENGTH_LONG).show()
                return@launch
            }
            if (isAdded && view != null) {
                handleFile(file)
            } else if (uri.scheme == "content") {
                file.delete()
            }
        }
    }

    private fun canAttachOneMore(): Boolean = attachments.size < boardModel.attachmentsMaxCount

    private fun handleFile(file: File?) {
        if (!canAttachOneMore()) {
            Toast.makeText(requireContext(), R.string.postform_max_attachments, Toast.LENGTH_LONG).show()
            return
        }
        if (file == null || !file.exists()) {
            Toast.makeText(requireContext(), R.string.postform_cannot_attach, Toast.LENGTH_LONG).show()
            return
        }
        attachments.add(file)

        val itemView = LayoutInflater.from(requireContext()).inflate(R.layout.postform_attachment, attachmentsLayout, false)
        val thumbView = itemView.findViewById<ImageView>(R.id.postform_attachment_thumbnail)
        val tvFilename = itemView.findViewById<TextView>(R.id.postform_attachment_filename)
        val tvFileSize = itemView.findViewById<TextView>(R.id.postform_attachment_size)
        val removeBtn = itemView.findViewById<View>(R.id.postform_attachment_remove)

        val thumb = getBitmap(file.absolutePath)
        if (thumb == null) {
            thumbView.setImageResource(AttachmentIconUtils.getDefaultIconResId(file.name))
        } else {
            thumbView.setImageBitmap(thumb)
        }
        tvFilename.text = file.name
        tvFileSize.text = getImageSizeString(file)
        removeBtn.tag = itemView
        removeBtn.setOnClickListener { v ->
            val position = attachmentsLayout.indexOfChild(v.tag as View)
            attachments.removeAt(position)
            attachmentsLayout.removeViewAt(position)
        }
        attachmentsLayout.addView(itemView)
    }

    private fun getBitmap(filename: String): Bitmap? {
        val maxDimension = resources.getDimensionPixelSize(R.dimen.attachment_thumbnail_size)
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(filename, opts)

        var scale = 1
        if (opts.outWidth > maxDimension || opts.outHeight > maxDimension) {
            val realScale = maxOf(opts.outWidth, opts.outHeight).toDouble() / maxDimension
            val roundedScale = Math.pow(2.0, Math.ceil(Math.log(realScale) / Math.log(2.0)))
            scale = roundedScale.toInt()
        }
        return BitmapFactory.decodeFile(filename, BitmapFactory.Options().apply { inSampleSize = scale })
    }

    private fun getImageSizeString(file: File): String {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        val kb = Math.round(file.length() / 1024.0).toInt()
        return if (opts.outWidth == -1 || opts.outHeight == -1) {
            getString(R.string.postform_attachment_size_format_no_image, kb)
        } else {
            getString(R.string.postform_attachment_size_format, kb, opts.outWidth, opts.outHeight)
        }
    }

    // --- Lifecycle ---

    override fun onPause() {
        saveSendPostModel()
        super.onPause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        currentTask?.cancel()
    }
}
