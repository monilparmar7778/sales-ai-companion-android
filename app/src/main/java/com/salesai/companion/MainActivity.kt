package com.salesai.companion

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.View
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

data class Contact(val id: String, val name: String, val phone: String)
data class CallSummary(
    val customerName: String,
    val phone: String,
    val fileName: String,
    val createdAt: String,
    val status: String,
    val lead: String,
    val score: String,
    val nextAction: String,
    val error: String
)

class MainActivity : Activity() {
    private val client = OkHttpClient()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var contacts = mutableListOf<Contact>()
    private var calls = mutableListOf<CallSummary>()
    private var selectedContact: Contact? = null
    private var pendingCallContact: Contact? = null
    private var callStartedAt: Long = 0L
    private var autoUploadTried = false

    private lateinit var serverUrl: EditText
    private lateinit var status: TextView
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
        val syncButton = Button(this).apply { text = "Sync Customers" }
        val dashboardButton = Button(this).apply { text = "Refresh Dashboard" }
        status = TextView(this).apply { text = "Ready" }
        contactList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        dashboardList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(syncButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(dashboardButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

        root.addView(TextView(this).apply {
            text = "Sales AI Companion"
            textSize = 24f
        })
        root.addView(serverUrl)
        root.addView(buttonRow)
        root.addView(status)
        root.addView(sectionTitle("Customers"))
        root.addView(contactList)
        root.addView(sectionTitle("Call Analysis Dashboard"))
        root.addView(dashboardList)
        scrollView.addView(root)
        setContentView(scrollView)

        syncButton.setOnClickListener { syncContacts() }
        dashboardButton.setOnClickListener { refreshDashboard() }
        requestAudioPermission()
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
            setPadding(0, 28, 0, 10)
        }
    }

    private fun renderContacts() {
        contactList.removeAllViews()
        if (contacts.isEmpty()) {
            contactList.addView(TextView(this).apply { text = "No customers found." })
            return
        }

        contacts.forEach { contact ->
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 24, 0, 24)
            }
            box.addView(TextView(this).apply {
                text = "${contact.name} - ${contact.phone}"
                textSize = 18f
            })
            box.addView(Button(this).apply {
                text = "Call"
                setOnClickListener {
                    selectedContact = contact
                    callCustomer(contact)
                }
            })
            box.addView(Button(this).apply {
                text = "Upload Latest Recording"
                setOnClickListener {
                    selectedContact = contact
                    uploadLatestRecording(contact, callStartedAt)
                }
            })
            box.addView(Button(this).apply {
                text = "Upload Recording"
                setOnClickListener {
                    selectedContact = contact
                    pickRecording()
                }
            })
            contactList.addView(box)
        }
    }

    private fun callCustomer(contact: Contact) {
        pendingCallContact = contact
        callStartedAt = System.currentTimeMillis()
        autoUploadTried = false
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
        if (autoUploadTried || callStartedAt == 0L) return
        val secondsSinceCallStarted = (System.currentTimeMillis() - callStartedAt) / 1000
        if (secondsSinceCallStarted >= 20) {
            autoUploadTried = true
            status.text = "Checking latest recording for ${contact.name}..."
            uploadLatestRecording(contact, callStartedAt)
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
                val latest = withContext(Dispatchers.IO) { findLatestAudioRecording(startedAtMs) }
                if (latest == null) {
                    status.text = "No new recording found. Use Upload Recording and select the file manually."
                    return@launch
                }
                uploadRecordingForContact(contact, latest)
            } catch (exc: Exception) {
                status.text = "Latest recording upload failed: ${errorMessage(exc)}"
            }
        }
    }

    private fun uploadRecordingForContact(contact: Contact, uri: Uri) {
        status.text = "Uploading recording for ${contact.name}..."

        scope.launch {
            try {
                val fileName = displayName(uri)
                val bytes = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } ?: throw IOException("Could not read recording")

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
                refreshDashboard(showLoading = false)
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

        dashboardList.addView(TextView(this).apply {
            text = "Total: ${calls.size} | Done: $doneCount | Failed: $failedCount\nHot: $hotCount | Warm: $warmCount | Cold: $coldCount"
            textSize = 16f
            setPadding(0, 0, 0, 18)
        })

        if (calls.isEmpty()) {
            dashboardList.addView(TextView(this).apply { text = "No call analysis yet." })
            return
        }

        calls.take(25).forEach { call ->
            dashboardList.addView(TextView(this).apply {
                text = buildString {
                    append("${call.customerName} - ${call.phone}\n")
                    append("Date: ${formatDate(call.createdAt)}\n")
                    append("File: ${call.fileName}\n")
                    append("Lead: ${call.lead} | Score: ${call.score} | Status: ${call.status}\n")
                    if (call.nextAction.isNotBlank()) append("Next: ${call.nextAction}\n")
                    if (call.error.isNotBlank()) append("Error: ${call.error}\n")
                }
                textSize = 15f
                setPadding(0, 12, 0, 12)
            })
        }
    }

    private fun formatDate(value: String): String {
        return value.take(19).replace("T", " ")
    }

    private fun findLatestAudioRecording(startedAtMs: Long): Uri? {
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
        val startSeconds = (startedAtMs / 1000) - 60
        val selection = "${MediaStore.Audio.Media.DATE_ADDED} >= ? OR ${MediaStore.Audio.Media.DATE_MODIFIED} >= ?"
        val selectionArgs = arrayOf(startSeconds.toString(), startSeconds.toString())
        val sortOrder = "${MediaStore.Audio.Media.DATE_MODIFIED} DESC, ${MediaStore.Audio.Media.DATE_ADDED} DESC"

        contentResolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIndex)
                val name = cursor.getString(nameIndex).lowercase()
                val looksLikeRecording = listOf("call", "record", "rec", "phone").any { name.contains(it) }
                if (looksLikeRecording || cursor.count == 1) {
                    return Uri.withAppendedPath(collection, id.toString())
                }
            }
        }
        return null
    }

    private fun displayName(uri: Uri): String {
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
