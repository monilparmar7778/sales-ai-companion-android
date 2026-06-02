package com.salesai.companion

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.telecom.Call
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class SalesInCallActivity : Activity() {
    private lateinit var numberText: TextView
    private lateinit var stateText: TextView
    private lateinit var answerButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentActivity = this
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(36, 44, 36, 44)
            setBackgroundColor(Color.rgb(18, 24, 38))
        }

        root.addView(TextView(this).apply {
            text = "Sales AI Call"
            textSize = 28f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        })

        numberText = TextView(this).apply {
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 28, 0, 8)
        }
        root.addView(numberText)

        stateText = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.rgb(210, 220, 240))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 28)
        }
        root.addView(stateText)

        answerButton = callButton("ANSWER", Color.rgb(25, 150, 95)).apply {
            setOnClickListener { SalesInCallService.answerCurrentCall() }
        }
        root.addView(answerButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        root.addView(callButton("END CALL", Color.rgb(220, 60, 70)).apply {
            setOnClickListener {
                SalesInCallService.disconnectCurrentCall()
                finish()
            }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 16 })

        setContentView(root)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        currentActivity = this
        refresh()
    }

    override fun onDestroy() {
        if (currentActivity == this) currentActivity = null
        super.onDestroy()
    }

    fun refresh() {
        if (!::numberText.isInitialized) return
        numberText.text = SalesInCallService.currentCallNumber()
        val state = SalesInCallService.currentCallState()
        stateText.text = when (state) {
            Call.STATE_RINGING -> "Incoming call. Recording starts after answer."
            Call.STATE_DIALING -> "Dialing. Recording starts when connected."
            Call.STATE_ACTIVE -> "Call active. Sales AI recorder is running."
            Call.STATE_HOLDING -> "Call on hold."
            Call.STATE_DISCONNECTED -> "Call ended."
            else -> "Preparing call controls."
        }
        answerButton.visibility = if (state == Call.STATE_RINGING) {
            android.view.View.VISIBLE
        } else {
            android.view.View.GONE
        }
        if (state == Call.STATE_DISCONNECTED || state == null) {
            finish()
        }
    }

    private fun callButton(label: String, color: Int): Button {
        return Button(this).apply {
            text = label
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(color)
                cornerRadius = 16f
            }
        }
    }

    companion object {
        private var currentActivity: SalesInCallActivity? = null

        fun refreshFromService() {
            currentActivity?.runOnUiThread { currentActivity?.refresh() }
        }
    }
}
