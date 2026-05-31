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

class MainActivity : Activity() {
    private val client = OkHttpClient()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var contacts = mutableListOf<Contact>()
    private var selectedContact: Contact? = null
    private var pendingCallContact: Contact? = null
    private var callStartedAt: Long = 0L
    private var autoUploadTried = false

    private lateinit var serverUrl: EditText
    private lateinit var status: TextView
    private lateinit var contactList: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 28)
        }

        serverUrl = EditText(this).apply {
            hint = "Server URL"
            setText("http://10.161.118.14:8000")
        }
        val syncButton = Button(this).apply { text = "Sync Customers" }
        status = TextView(this).apply { text = "Ready" }
        contactList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        root.addView(TextView(this).apply {
            text = "Sales AI Companion"
            textSize = 24f
        })
        root.addView(serverUrl)
        root.addView(syncButton)
        root.addView(status)
        root.addView(contactList)
        setContentView(root)

        syncButton.setOnClickListener { syncContacts() }
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
                val response = withContext(Dispatchers.IO) {
                    client.newCall(Request.Builder().url("${baseUrl()}/contacts").build()).execute()
                }
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw IOException(body)
                contacts.clear()
                contacts.addAll(parseContacts(body))
                renderContacts()
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

                val response = withContext(Dispatchers.IO) {
                    client.newCall(
                        Request.Builder()
                            .url("${baseUrl()}/contact-call")
                            .post(requestBody)
                            .build()
                    ).execute()
                }

                if (!response.isSuccessful) throw IOException(response.body?.string().orEmpty())
                status.text = "Uploaded and analyzed for ${contact.name}."
                if (pendingCallContact?.id == contact.id) {
                    pendingCallContact = null
                }
            } catch (exc: Exception) {
                status.text = "Upload failed: ${errorMessage(exc)}"
            }
        }
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
