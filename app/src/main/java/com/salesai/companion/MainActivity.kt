package com.salesai.companion

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.CallLog
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class Contact(val id: String, val name: String, val phone: String)
data class AudioCandidate(val uri: Uri, val name: String, val addedSeconds: Long, val modifiedSeconds: Long)
data class CallSummary(
    val customerName: String,
    val phone: String,
    val fileName: String,
    val createdAt: String,
    val status: String,
    val lead: String,
    val score: String,
    val nextAction: String,
    val summary: String,
    val transcript: String,
    val error: String
)

class MainActivity : Activity() {
    private val client = OkHttpClient()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var contacts = mutableListOf<Contact>()
    private var calls = mutableListOf<CallSummary>()
    private var selectedContact: Contact? = null
    private var pendingCallContact: Contact? = null
    private var readyToUploadContact: Contact? = null
    private var readyToUploadStartedAt: Long = 0L
    private var callStartedAt: Long = 0L
    private var autoUploadTried = false
    private var autoUploadRunning = false
    private var showingDashboard = false
    private var activeRecorder: MediaRecorder? = null
    private var activeRecordingFile: File? = null
    private var previousAudioMode: Int? = null
    private var previousSpeakerphoneOn: Boolean? = null

    private lateinit var serverUrl: EditText
    private lateinit var customerNameInput: EditText
    private lateinit var customerPhoneInput: EditText
    private lateinit var customerNotesInput: EditText
    private lateinit var status: TextView
    private lateinit var folderInfo: TextView
    private lateinit var mainPage: LinearLayout
    private lateinit var dashboardPage: LinearLayout
    private lateinit var recentCallPanel: LinearLayout
    private lateinit var recentAudioList: LinearLayout
    private lateinit var contactList: LinearLayout
    private lateinit var dashboardList: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scrollView = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 28)
        }

        serverUrl = EditText(this).apply {
            hint = "Server URL"
            setText("http://10.161.118.14:8000")
        }
        customerNameInput = EditText(this).apply { hint = "Customer name" }
        customerPhoneInput = EditText(this).apply {
            hint = "Phone number"
            inputType = android.text.InputType.TYPE_CLASS_PHONE
        }
        customerNotesInput = EditText(this).apply { hint = "Notes" }
        val saveCustomerButton = primaryButton("Save Customer")
        val syncButton = secondaryButton("Sync Customers")
        val dashboardButton = secondaryButton("Refresh Dashboard")
        val customerPageButton = primaryButton("Upload Calls")
        val dashboardPageButton = secondaryButton("Dashboard")
        status = TextView(this).apply {
            text = "Ready"
            textSize = 14f
            setTextColor(Color.rgb(72, 80, 96))
            setPadding(0, 10, 0, 10)
        }
        folderInfo = TextView(this)
        mainPage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 10, 0, 0) }
        dashboardPage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 10, 0, 0) }
        recentCallPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        recentAudioList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        contactList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        dashboardList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(syncButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(dashboardButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        val navRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(customerPageButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(dashboardPageButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

        root.addView(TextView(this).apply {
            text = "AnyLaser"
            textSize = 26f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.rgb(18, 24, 38))
        })
        root.addView(TextView(this).apply {
            text = "Upload, analyze and manage sales call recordings"
            textSize = 13f
            setTextColor(Color.rgb(92, 100, 115))
            setPadding(0, 2, 0, 10)
        })
        root.addView(serverUrl)
        root.addView(navRow)
        root.addView(buttonRow)
        root.addView(status)

        mainPage.addView(sectionTitle("Upload Calls"))
        mainPage.addView(TextView(this).apply {
            text = "Save customer, tap Call, return after call. Answered calls upload automatically."
            textSize = 13f
            setTextColor(Color.rgb(92, 100, 115))
            setPadding(0, 0, 0, 10)
        })
        mainPage.addView(card().apply {
            addView(sectionTitle("Add Customer"))
            addView(customerNameInput)
            addView(customerPhoneInput)
            addView(customerNotesInput)
            addView(saveCustomerButton)
        })
        mainPage.addView(sectionTitle("Call Queue"))
        mainPage.addView(recentCallPanel)
        mainPage.addView(recentAudioList)
        mainPage.addView(sectionTitle("Customers"))
        mainPage.addView(contactList)

        dashboardPage.addView(sectionTitle("Dashboard"))
        dashboardPage.addView(dashboardList)
        dashboardPage.addView(sectionTitle("Recording Setup"))
        dashboardPage.addView(folderInfo)

        root.addView(mainPage)
        root.addView(dashboardPage)
        scrollView.addView(root)
        setContentView(scrollView)

        saveCustomerButton.setOnClickListener { saveCustomer() }
        syncButton.setOnClickListener { syncContacts() }
        dashboardButton.setOnClickListener { refreshDashboard() }
        customerPageButton.setOnClickListener { showMainPage() }
        dashboardPageButton.setOnClickListener {
            showDashboardPage()
            refreshDashboard()
        }
        requestStartupPermissions()
        ensureRecordingFolder()
        renderRecentCallPanel()
        showMainPage()
    }

    private fun baseUrl(): String {
        val enteredUrl = serverUrl.text.toString().trim().trimEnd('/')
        return listOf("/contacts", "/calls", "/crm-page")
            .firstOrNull { enteredUrl.endsWith(it) }
            ?.let { enteredUrl.removeSuffix(it).trimEnd('/') }
            ?: enteredUrl
    }

    private fun syncContacts() {
        status.text = "Syncing customers..."
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    client.newCall(Request.Builder().url("${baseUrl()}/contacts").build()).execute().use { response ->
                        val body = response.body?.string().orEmpty()
                        if (!response.isSuccessful) throw IOException(body)
                        body
                    }
                }
                contacts.clear()
                contacts.addAll(parseContacts(result))
                renderContacts()
                refreshDashboard(showLoading = false)
                status.text = "Loaded ${contacts.size} customer(s)."
            } catch (exc: Exception) {
                status.text = "Sync failed: ${errorMessage(exc)}"
            }
        }
    }

    private fun saveCustomer() {
        val name = customerNameInput.text.toString().trim()
        val phone = customerPhoneInput.text.toString().trim()
        val notes = customerNotesInput.text.toString().trim()

        if (name.isBlank() || phone.isBlank()) {
            status.text = "Enter customer name and phone number."
            return
        }

        status.text = "Saving customer..."
        scope.launch {
            try {
                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("customer_name", name)
                    .addFormDataPart("phone", phone)
                    .addFormDataPart("notes", notes)
                    .build()

                val result = withContext(Dispatchers.IO) {
                    client.newCall(
                        Request.Builder()
                            .url("${baseUrl()}/contacts")
                            .post(requestBody)
                            .build()
                    ).execute().use { response ->
                        val responseBody = response.body?.string().orEmpty()
                        if (!response.isSuccessful) throw IOException(responseBody)
                        responseBody
                    }
                }

                val row = JSONObject(result)
                val savedContact = Contact(
                    id = row.optString("id"),
                    name = row.optString("customer_name", name),
                    phone = row.optString("phone", phone)
                )
                contacts.removeAll { it.id == savedContact.id || it.phone == savedContact.phone }
                contacts.add(0, savedContact)
                customerNameInput.setText("")
                customerPhoneInput.setText("")
                customerNotesInput.setText("")
                renderContacts()
                status.text = "Saved ${savedContact.name}. Tap Call when ready."
            } catch (exc: Exception) {
                status.text = "Save failed: ${errorMessage(exc)}"
            }
        }
    }

    private fun parseContacts(body: String): List<Contact> {
        val rows = when {
            body.trim().startsWith("[") -> JSONArray(body)
            else -> JSONObject(body).optJSONArray("contacts")
                ?: JSONObject(body).optJSONArray("data")
                ?: throw IOException("Server did not return a contacts list")
        }

        val parsedContacts = mutableListOf<Contact>()
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val id = row.optString("id", "").ifBlank { row.optString("contact_id", "") }
            val name = row.optString("customer_name", "")
                .ifBlank { row.optString("name", "") }
                .ifBlank { "Customer ${i + 1}" }
            val phone = row.optString("phone", "")
                .ifBlank { row.optString("mobile", "") }
                .ifBlank { row.optString("phone_number", "") }

            if (id.isNotBlank()) {
                parsedContacts.add(Contact(id = id, name = name, phone = phone))
            }
        }
        return parsedContacts
    }

    private fun errorMessage(exc: Exception): String {
        return exc.message?.takeIf { it.isNotBlank() } ?: exc.javaClass.simpleName
    }

    private fun sectionTitle(title: String): TextView {
        return TextView(this).apply {
            text = title
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.rgb(18, 24, 38))
            setPadding(0, 28, 0, 10)
        }
    }

    private fun card(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 18, 22, 18)
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(1, Color.rgb(224, 229, 238))
                cornerRadius = 18f
            }
        }
    }

    private fun primaryButton(label: String): Button {
        return Button(this).apply {
            text = label
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.rgb(76, 91, 255))
                cornerRadius = 12f
            }
        }
    }

    private fun secondaryButton(label: String): Button {
        return Button(this).apply {
            text = label
            setTextColor(Color.rgb(38, 45, 65))
            background = GradientDrawable().apply {
                setColor(Color.rgb(246, 248, 252))
                setStroke(1, Color.rgb(224, 229, 238))
                cornerRadius = 12f
            }
        }
    }

    private fun metricCard(title: String, value: String, subtitle: String): LinearLayout {
        return card().apply {
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 12f
                setTextColor(Color.rgb(92, 100, 115))
            })
            addView(TextView(this@MainActivity).apply {
                text = value
                textSize = 24f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.rgb(18, 24, 38))
            })
            addView(TextView(this@MainActivity).apply {
                text = subtitle
                textSize = 12f
                setTextColor(Color.rgb(25, 150, 95))
            })
        }
    }

    private fun addSpace(parent: LinearLayout, height: Int = 12) {
        parent.addView(Space(this), LinearLayout.LayoutParams(1, height))
    }

    private fun recordingFolder(): File {
        return File(getExternalFilesDir(Environment.DIRECTORY_MUSIC), "SalesAIRecordings")
    }

    private fun ensureRecordingFolder() {
        val folder = recordingFolder()
        if (!folder.exists()) folder.mkdirs()
        folderInfo.text = "Folder created:\n${folder.absolutePath}\n\nThis build uses a foreground recorder so the audio file can keep saving while the Phone app is open. Android may still block the other side of a normal SIM call on some phones."
    }

    private fun showMainPage() {
        showingDashboard = false
        mainPage.visibility = android.view.View.VISIBLE
        dashboardPage.visibility = android.view.View.GONE
    }

    private fun showDashboardPage() {
        showingDashboard = true
        mainPage.visibility = android.view.View.GONE
        dashboardPage.visibility = android.view.View.VISIBLE
    }

    private fun renderContacts() {
        contactList.removeAllViews()
        if (contacts.isEmpty()) {
            contactList.addView(TextView(this).apply { text = "No customers found." })
            return
        }

        contacts.forEach { contact ->
            val box = card()
            box.addView(TextView(this).apply {
                text = contact.name
                textSize = 18f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.rgb(18, 24, 38))
            })
            box.addView(TextView(this).apply {
                text = contact.phone
                textSize = 14f
                setTextColor(Color.rgb(92, 100, 115))
                setPadding(0, 2, 0, 8)
            })
            box.addView(primaryButton("Call ${contact.name}").apply {
                setOnClickListener {
                    selectedContact = contact
                    callCustomer(contact)
                }
            })
            box.addView(secondaryButton("Upload Latest Recording").apply {
                setOnClickListener {
                    selectedContact = contact
                    uploadLatestRecording(contact, readyToUploadStartedAt.takeIf { it > 0L } ?: callStartedAt)
                }
            })
            box.addView(secondaryButton("Select Recording File").apply {
                setOnClickListener {
                    selectedContact = contact
                    pickRecording()
                }
            })
            contactList.addView(box)
            addSpace(contactList, 14)
        }
    }

    private fun callCustomer(contact: Contact) {
        pendingCallContact = contact
        readyToUploadContact = null
        readyToUploadStartedAt = 0L
        callStartedAt = System.currentTimeMillis()
        autoUploadTried = false
        autoUploadRunning = false
        renderRecentCallPanel()
        startExperimentalCallRecording(contact)
        status.text = "Calling ${contact.name}. Recording started. Upload will run automatically after the call ends."
        val uri = Uri.parse("tel:+91${contact.phone.filter { it.isDigit() }.takeLast(10)}")
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            startActivity(Intent(Intent.ACTION_CALL, uri))
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CALL_PHONE), 10)
            startActivity(Intent(Intent.ACTION_DIAL, uri))
        }
    }

    override fun onResume() {
        super.onResume()
        val contact = pendingCallContact ?: return
        if (callStartedAt == 0L || System.currentTimeMillis() - callStartedAt < 2_000L) return
        if (!autoUploadRunning) {
            finishCallAndUpload(contact, "App returned after call. Uploading recording automatically...")
        }
    }

    private fun finishCallAndUpload(contact: Contact, message: String) {
        if (autoUploadRunning) return
        autoUploadRunning = true
        val startedAt = callStartedAt
        stopExperimentalCallRecording()
        status.text = "Checking whether call was answered..."
        scope.launch {
            delay(3_000L)
            val callDuration = withContext(Dispatchers.IO) {
                latestOutgoingCallDurationSeconds(contact, startedAt)
            }
            if (callDuration == null) {
                skipCallUpload(contact, "Could not verify answered call. Allow Call Log permission; upload skipped.")
                return@launch
            }
            if (callDuration <= 0L) {
                skipCallUpload(contact, "Call was not answered. Recording deleted and upload skipped.")
                return@launch
            }
            readyToUploadContact = contact
            readyToUploadStartedAt = startedAt
            pendingCallContact = null
            selectedContact = contact
            renderRecentCallPanel()
            status.text = message
            autoUploadRunning = false
            uploadLatestRecording(contact, startedAt)
        }
    }

    private fun skipCallUpload(contact: Contact, message: String) {
        pendingCallContact = null
        selectedContact = contact
        readyToUploadContact = null
        readyToUploadStartedAt = 0L
        activeRecordingFile?.delete()
        activeRecordingFile = null
        autoUploadRunning = false
        renderRecentCallPanel()
        status.text = message
    }

    private fun requestStartupPermissions() {
        val permissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.CALL_PHONE)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.READ_CALL_LOG)
        }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val audioPermission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (ContextCompat.checkSelfPermission(this, audioPermission) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(audioPermission)
        }
        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 30)
        }
    }

    private fun startExperimentalCallRecording(contact: Contact) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 12)
            status.text = "Microphone permission required for test recording."
            return
        }

        try {
            stopExperimentalCallRecording()
            val folder = recordingFolder()
            if (!folder.exists()) folder.mkdirs()
            val file = File(folder, contactRecordingFileName(contact, "m4a"))
            val intent = Intent(this, CallRecordingService::class.java).apply {
                action = CallRecordingService.ACTION_START
                putExtra(CallRecordingService.EXTRA_FILE_PATH, file.absolutePath)
            }
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            activeRecorder = null
            activeRecordingFile = file
        } catch (exc: Exception) {
            activeRecorder = null
            activeRecordingFile = null
            status.text = "Experimental recording could not start: ${errorMessage(exc)}"
        }
    }

    private fun stopExperimentalCallRecording() {
        try {
            stopService(Intent(this, CallRecordingService::class.java).apply {
                action = CallRecordingService.ACTION_STOP
            })
        } catch (_: Exception) {
        }
        val recorder = activeRecorder
        if (recorder != null) {
            try {
                recorder.stop()
            } catch (_: Exception) {
            } finally {
                recorder.release()
                activeRecorder = null
            }
        }
    }

    private fun enableSpeakerRecordingAssist() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (previousAudioMode == null) previousAudioMode = audioManager.mode
            if (previousSpeakerphoneOn == null) previousSpeakerphoneOn = audioManager.isSpeakerphoneOn
            audioManager.mode = AudioManager.MODE_IN_CALL
            audioManager.isSpeakerphoneOn = true
            Toast.makeText(this, "Speaker enabled for recording test.", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            previousAudioMode = null
            previousSpeakerphoneOn = null
        }
    }

    private fun restoreSpeakerRecordingAssist() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            previousSpeakerphoneOn?.let { audioManager.isSpeakerphoneOn = it }
            previousAudioMode?.let { audioManager.mode = it }
        } catch (_: Exception) {
        } finally {
            previousAudioMode = null
            previousSpeakerphoneOn = null
        }
    }

    private fun renderRecentCallPanel() {
        recentCallPanel.removeAllViews()
        val contact = readyToUploadContact
        if (contact == null) {
            recentCallPanel.addView(TextView(this).apply {
                text = "No completed call waiting for upload."
                setPadding(0, 0, 0, 12)
            })
            return
        }

        recentCallPanel.addView(TextView(this).apply {
            text = "${contact.name} - ${contact.phone}\nCall completed. Upload this customer's recording."
            textSize = 16f
            setPadding(0, 0, 0, 10)
        })
        recentCallPanel.addView(Button(this).apply {
            text = "Find & Upload Call Recording"
            setOnClickListener {
                selectedContact = contact
                scanRecordingsForContact(contact, readyToUploadStartedAt)
            }
        })
        recentCallPanel.addView(Button(this).apply {
            text = "Select Recording File"
            setOnClickListener {
                selectedContact = contact
                pickRecording()
            }
        })
        recentAudioList.removeAllViews()
    }

    private fun startAutoUploadPolling(contact: Contact, startedAtMs: Long) {
        autoUploadTried = true
        autoUploadRunning = true
        scope.launch {
            try {
                val waitMs = (10_000L - (System.currentTimeMillis() - startedAtMs)).coerceAtLeast(0L)
                if (waitMs > 0L) delay(waitMs)

                repeat(12) { attempt ->
                    status.text = "Auto-checking call recording for ${contact.name} (${attempt + 1}/12)..."
                    val latest = withContext(Dispatchers.IO) { findLatestAudioRecording(startedAtMs) }
                    if (latest != null) {
                        status.text = "Recording found. Uploading automatically..."
                        uploadRecordingForContact(contact, latest)
                        return@launch
                    }
                    delay(5_000L)
                }

                status.text = "Auto upload did not find a new recording. Use Upload Recording manually."
            } catch (exc: Exception) {
                status.text = "Auto upload failed: ${errorMessage(exc)}"
            } finally {
                autoUploadRunning = false
            }
        }
    }

    private fun requestAudioPermission() {
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(permission), 11)
        }
    }

    private fun requestRecordPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 12)
        }
    }

    private fun requestCallLogPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_CALL_LOG), 14)
        }
    }

    private fun hasAudioPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun pickRecording() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
        }
        startActivityForResult(intent, 20)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 20 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            uploadRecording(uri)
        }
    }

    private fun uploadRecording(uri: Uri) {
        val contact = selectedContact ?: return
        uploadRecordingForContact(contact, uri)
    }

    private fun uploadLatestRecording(contact: Contact, startedAtMs: Long) {
        requestAudioPermission()
        scope.launch {
            try {
                val latest = withContext(Dispatchers.IO) {
                    activeRecordingFile?.takeIf { it.exists() }?.let { Uri.fromFile(it) }
                        ?: findLatestAppFolderRecording(startedAtMs)
                        ?: findLatestAudioRecording(startedAtMs)
                }
                if (latest == null) {
                    status.text = "No recording auto-found. Scanning visible phone audio..."
                    scanRecordingsForContact(contact, startedAtMs)
                    return@launch
                }
                uploadRecordingForContact(contact, latest)
            } catch (exc: Exception) {
                status.text = "Latest recording upload failed: ${errorMessage(exc)}"
            }
        }
    }

    private fun scanRecordingsForContact(contact: Contact, startedAtMs: Long) {
        requestAudioPermission()
        selectedContact = contact
        recentAudioList.removeAllViews()
        status.text = "Scanning phone audio for ${contact.name}..."

        scope.launch {
            try {
                if (!hasAudioPermission()) {
                    renderAudioScanHelp("Audio permission is not allowed. Allow Music/Audio permission for this app, then scan again.")
                    status.text = "Audio permission required."
                    return@launch
                }

                val candidates = withContext(Dispatchers.IO) {
                    appFolderCandidates(startedAtMs) + recentAudioCandidates(startedAtMs)
                }.distinctBy { it.uri.toString() }
                if (candidates.isEmpty()) {
                    renderAudioScanHelp("No recording was found in the SalesAI folder or visible phone audio.")
                    status.text = "No visible audio found. Use Select Recording File."
                    return@launch
                }

                renderAudioCandidates(contact, candidates)
                status.text = "Found ${candidates.size} audio file(s). Choose the call recording to upload."
            } catch (exc: Exception) {
                renderAudioScanHelp("Scan failed: ${errorMessage(exc)}")
                status.text = "Scan failed: ${errorMessage(exc)}"
            }
        }
    }

    private fun renderAudioCandidates(contact: Contact, candidates: List<AudioCandidate>) {
        recentAudioList.removeAllViews()
        recentAudioList.addView(TextView(this).apply {
            text = "Visible Audio Files"
            textSize = 18f
            setPadding(0, 18, 0, 8)
        })
        recentAudioList.addView(TextView(this).apply {
            text = "Tap the recording from your completed call. If it is not listed, use Select Recording File."
            setPadding(0, 0, 0, 8)
        })
        candidates.take(12).forEach { candidate ->
            recentAudioList.addView(Button(this).apply {
                text = "${candidate.name}\n${formatAudioDate(maxOf(candidate.modifiedSeconds, candidate.addedSeconds))}"
                setOnClickListener { uploadRecordingForContact(contact, candidate.uri) }
            })
        }
        recentAudioList.addView(Button(this).apply {
            text = "Select Recording File"
            setOnClickListener {
                selectedContact = contact
                pickRecording()
            }
        })
    }

    private fun renderAudioScanHelp(message: String) {
        recentAudioList.removeAllViews()
        recentAudioList.addView(TextView(this).apply {
            text = "$message\n\nUse Select Recording File and choose the recording from Recorder/File Manager.\n\nWhy: Android does not allow this app to control or read hidden Phone app recording storage. True automatic recording needs cloud calling/VoIP integration."
            setPadding(0, 18, 0, 8)
        })
        recentAudioList.addView(Button(this).apply {
            text = "Select Recording File"
            setOnClickListener { pickRecording() }
        })
    }

    private fun uploadRecordingForContact(contact: Contact, uri: Uri) {
        status.text = "Uploading recording for ${contact.name}..."

        scope.launch {
            try {
                val originalFileName = displayName(uri)
                val fileName = contactRecordingFileName(contact, recordingExtension(originalFileName))
                val bytes = withContext(Dispatchers.IO) {
                    if (uri.scheme == "file") {
                        File(uri.path.orEmpty()).readBytes()
                    } else {
                        contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    }
                } ?: throw IOException("Could not read recording")
                withContext(Dispatchers.IO) { copyRecordingToAppFolder(fileName, bytes) }

                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("contact_id", contact.id)
                    .addFormDataPart(
                        "file",
                        fileName,
                        bytes.toRequestBody("audio/*".toMediaTypeOrNull())
                    )
                    .build()

                val errorBody = withContext(Dispatchers.IO) {
                    client.newCall(
                        Request.Builder()
                            .url("${baseUrl()}/contact-call")
                            .post(requestBody)
                            .build()
                    ).execute().use { response ->
                        if (response.isSuccessful) {
                            null
                        } else {
                            response.body?.string().orEmpty()
                        }
                    }
                }

                if (errorBody != null) throw IOException(errorBody)
                status.text = "Uploaded and analyzed for ${contact.name}."
                if (pendingCallContact?.id == contact.id) {
                    pendingCallContact = null
                }
                if (readyToUploadContact?.id == contact.id) {
                    readyToUploadContact = null
                    readyToUploadStartedAt = 0L
                    activeRecordingFile = null
                    renderRecentCallPanel()
                }
                refreshDashboard(showLoading = false)
                showDashboardPage()
            } catch (exc: Exception) {
                status.text = "Upload failed: ${errorMessage(exc)}"
            }
        }
    }

    private fun refreshDashboard(showLoading: Boolean = true) {
        if (showLoading) status.text = "Loading dashboard..."
        scope.launch {
            try {
                val body = withContext(Dispatchers.IO) {
                    client.newCall(Request.Builder().url("${baseUrl()}/calls").build()).execute().use { response ->
                        val responseBody = response.body?.string().orEmpty()
                        if (!response.isSuccessful) throw IOException(responseBody)
                        responseBody
                    }
                }
                calls.clear()
                calls.addAll(parseCalls(body))
                renderDashboard()
                if (showLoading) status.text = "Dashboard loaded (${calls.size} call(s))."
            } catch (exc: Exception) {
                if (showLoading) status.text = "Dashboard failed: ${errorMessage(exc)}"
            }
        }
    }

    private fun parseCalls(body: String): List<CallSummary> {
        val rows = JSONArray(body)
        val parsedCalls = mutableListOf<CallSummary>()
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val analysis = row.optJSONObject("analysis") ?: JSONObject()
            parsedCalls.add(
                CallSummary(
                    customerName = row.optString("customer_name", "").ifBlank { "Unknown customer" },
                    phone = row.optString("phone", ""),
                    fileName = row.optString("file_name", ""),
                    createdAt = row.optString("created_at", ""),
                    status = row.optString("status", ""),
                    lead = analysis.optString("customer_intent", "Cold"),
                    score = analysis.optString("agent_score", analysis.optString("sales_score", "0")),
                    nextAction = analysis.optString("next_action", ""),
                    summary = analysis.optString("summary", "")
                        .ifBlank { analysis.optString("call_summary", "") }
                        .ifBlank { row.optString("summary", "") },
                    transcript = row.optString("transcript", "")
                        .ifBlank { analysis.optString("transcript", "") },
                    error = row.optString("error", "")
                )
            )
        }
        return parsedCalls
    }

    private fun renderDashboard() {
        dashboardList.removeAllViews()
        val doneCount = calls.count { it.status == "done" }
        val failedCount = calls.count { it.status == "failed" }
        val hotCount = calls.count { it.lead.equals("hot", ignoreCase = true) }
        val warmCount = calls.count { it.lead.equals("warm", ignoreCase = true) }
        val coldCount = calls.count { it.lead.equals("cold", ignoreCase = true) }

        dashboardList.addView(metricCard("Total Calls", calls.size.toString(), "Done $doneCount | Failed $failedCount"))
        addSpace(dashboardList, 12)
        dashboardList.addView(metricCard("Lead Mix", "Hot $hotCount", "Warm $warmCount | Cold $coldCount"))
        addSpace(dashboardList, 16)

        if (calls.isEmpty()) {
            dashboardList.addView(TextView(this).apply { text = "No call analysis yet." })
            return
        }

        calls.take(25).forEach { call ->
            dashboardList.addView(card().apply {
                addView(TextView(this@MainActivity).apply {
                    text = "${call.customerName} - ${call.phone}"
                    textSize = 17f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.rgb(18, 24, 38))
                })
                addView(TextView(this@MainActivity).apply {
                    text = "${formatDate(call.createdAt)} | ${call.status}"
                    textSize = 13f
                    setTextColor(Color.rgb(92, 100, 115))
                    setPadding(0, 2, 0, 8)
                })
                addView(TextView(this@MainActivity).apply {
                    text = "Lead: ${call.lead} | Score: ${call.score}"
                    textSize = 14f
                    setTextColor(Color.rgb(76, 91, 255))
                    setPadding(0, 0, 0, 6)
                })
                if (call.summary.isNotBlank()) addView(TextView(this@MainActivity).apply {
                    text = "Summary: ${call.summary}"
                    textSize = 14f
                    setTextColor(Color.rgb(38, 45, 65))
                })
                if (call.nextAction.isNotBlank()) addView(TextView(this@MainActivity).apply {
                    text = "Next: ${call.nextAction}"
                    textSize = 14f
                    setTextColor(Color.rgb(38, 45, 65))
                })
                if (call.transcript.isNotBlank()) addView(TextView(this@MainActivity).apply {
                    text = "Transcript: ${call.transcript.take(220)}"
                    textSize = 13f
                    setTextColor(Color.rgb(92, 100, 115))
                    setPadding(0, 6, 0, 0)
                })
                if (call.error.isNotBlank()) addView(TextView(this@MainActivity).apply {
                    text = "Error: ${call.error}"
                    textSize = 13f
                    setTextColor(Color.rgb(220, 60, 70))
                })
            })
            addSpace(dashboardList, 14)
        }
    }

    private fun formatDate(value: String): String {
        return try {
            val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val parsed = parser.parse(value.take(19)) ?: return value.take(19).replace("T", " ")
            SimpleDateFormat("yyyy-MM-dd hh:mm a", Locale.getDefault()).format(parsed)
        } catch (_: Exception) {
            value.take(19).replace("T", " ")
        }
    }

    private fun latestOutgoingCallDurationSeconds(contact: Contact, startedAtMs: Long): Long? {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        val expectedPhone = contact.phone.filter { it.isDigit() }.takeLast(10)
        val projection = arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.TYPE)
        return contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            projection,
            "${CallLog.Calls.TYPE} = ? AND ${CallLog.Calls.DATE} >= ?",
            arrayOf(CallLog.Calls.OUTGOING_TYPE.toString(), (startedAtMs - 60_000L).toString()),
            "${CallLog.Calls.DATE} DESC"
        )?.use { cursor ->
            val numberIndex = cursor.getColumnIndex(CallLog.Calls.NUMBER)
            val durationIndex = cursor.getColumnIndex(CallLog.Calls.DURATION)
            while (cursor.moveToNext()) {
                val number = cursor.getString(numberIndex).orEmpty().filter { it.isDigit() }.takeLast(10)
                if (expectedPhone.isBlank() || number == expectedPhone) {
                    return@use cursor.getLong(durationIndex)
                }
            }
            null
        }
    }

    private fun findLatestAudioRecording(startedAtMs: Long): Uri? {
        return recentAudioCandidates(startedAtMs).firstOrNull()?.uri
    }

    private fun findLatestAppFolderRecording(startedAtMs: Long): Uri? {
        return appFolderCandidates(startedAtMs).firstOrNull()?.uri
    }

    private fun appFolderCandidates(startedAtMs: Long): List<AudioCandidate> {
        val folder = recordingFolder()
        if (!folder.exists()) folder.mkdirs()
        val startMs = (startedAtMs.takeIf { it > 0L } ?: (System.currentTimeMillis() - 60 * 60 * 1000)) - 10 * 60 * 1000
        return folder.listFiles()
            ?.filter { it.isFile && isAudioFileName(it.name) && it.lastModified() >= startMs }
            ?.sortedByDescending { it.lastModified() }
            ?.map {
                AudioCandidate(
                    uri = Uri.fromFile(it),
                    name = it.name,
                    addedSeconds = it.lastModified() / 1000,
                    modifiedSeconds = it.lastModified() / 1000
                )
            }
            ?: emptyList()
    }

    private fun recentAudioCandidates(startedAtMs: Long): List<AudioCandidate> {
        val collection = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DATE_MODIFIED
        )
        val startSeconds = ((startedAtMs.takeIf { it > 0L } ?: (System.currentTimeMillis() - 60 * 60 * 1000)) / 1000) - 600
        val selection = "(${MediaStore.Audio.Media.DATE_ADDED} >= ? OR ${MediaStore.Audio.Media.DATE_MODIFIED} >= ?)"
        val selectionArgs = arrayOf(startSeconds.toString(), startSeconds.toString())
        val sortOrder = "${MediaStore.Audio.Media.DATE_MODIFIED} DESC, ${MediaStore.Audio.Media.DATE_ADDED} DESC"
        val candidates = mutableListOf<AudioCandidate>()

        contentResolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val addedIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val modifiedIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIndex)
                candidates.add(
                    AudioCandidate(
                        uri = Uri.withAppendedPath(collection, id.toString()),
                        name = cursor.getString(nameIndex) ?: "audio-file",
                        addedSeconds = cursor.getLong(addedIndex),
                        modifiedSeconds = cursor.getLong(modifiedIndex)
                    )
                )
            }
        }
        if (candidates.isNotEmpty()) return candidates

        contentResolver.query(collection, projection, null, null, sortOrder)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val addedIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val modifiedIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
            while (cursor.moveToNext() && candidates.size < 12) {
                val id = cursor.getLong(idIndex)
                candidates.add(
                    AudioCandidate(
                        uri = Uri.withAppendedPath(collection, id.toString()),
                        name = cursor.getString(nameIndex) ?: "audio-file",
                        addedSeconds = cursor.getLong(addedIndex),
                        modifiedSeconds = cursor.getLong(modifiedIndex)
                    )
                )
            }
        }
        return candidates
    }

    private fun isAudioFileName(name: String): Boolean {
        val lower = name.lowercase(Locale.getDefault())
        return listOf(".mp3", ".m4a", ".aac", ".amr", ".wav", ".ogg", ".3gp").any { lower.endsWith(it) }
    }

    private fun copyRecordingToAppFolder(fileName: String, bytes: ByteArray) {
        val folder = recordingFolder()
        if (!folder.exists()) folder.mkdirs()
        val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "call-recording.m4a" }
        File(folder, safeName).writeBytes(bytes)
    }

    private fun contactRecordingFileName(contact: Contact, extension: String): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val safeName = safeRecordingPart(contact.name).ifBlank { "customer" }
        val safePhone = contact.phone.filter { it.isDigit() }.takeLast(10).ifBlank { "no_phone" }
        val safeExtension = extension.lowercase(Locale.US).replace(Regex("[^a-z0-9]"), "").ifBlank { "m4a" }
        return "${timestamp}_${safeName}_${safePhone}_call.$safeExtension"
    }

    private fun safeRecordingPart(value: String): String {
        return value.trim().replace(Regex("[^A-Za-z0-9]+"), "_").trim('_')
    }

    private fun recordingExtension(fileName: String): String {
        return fileName.substringAfterLast('.', "m4a")
    }

    private fun formatAudioDate(seconds: Long): String {
        if (seconds <= 0L) return "Unknown time"
        return SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(seconds * 1000))
    }

    private fun displayName(uri: Uri): String {
        if (uri.scheme == "file") {
            return File(uri.path.orEmpty()).name.ifBlank { "call-recording.m4a" }
        }
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) {
                return cursor.getString(index)
            }
        }
        return "call-recording.m4a"
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
