package com.desk.companion2.ui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.desk.companion2.DeskConfig
import com.google.firebase.database.*
import org.json.JSONObject

class EventsActivity : AppCompatActivity() {

    private lateinit var dbRef: DatabaseReference
    private lateinit var rootLayout: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F172A"))
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        setContentView(rootLayout)

        dbRef = FirebaseDatabase.getInstance()
            .getReference(DeskConfig.FIREBASE_ROOT_NODE)
            .child(DeskConfig.TARGET_DEVICE_ID)

        fetchAvailableDates()
    }

    private fun fetchAvailableDates() {
        showLoading()
        dbRef.child("available_dates").addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val datesList = ArrayList<String>()
                for (child in snapshot.children) {
                    child.getValue(String::class.java)?.let { datesList.add(it) }
                }
                showDatesListView(datesList.sortedDescending())
            }

            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun showLoading() {
        rootLayout.removeAllViews()
        val tv = TextView(this).apply {
            text = "Loading Cloud Records..."
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 100, 0, 0)
        }
        rootLayout.addView(tv)
    }

    private fun showDatesListView(dates: List<String>) {
        rootLayout.removeAllViews()

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#1E293B"))
            setPadding(24, 18, 24, 18)
            gravity = Gravity.CENTER_VERTICAL
        }
        val tvTitle = TextView(this).apply {
            text = "📊 Desk Study Logs"
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnClose = Button(this).apply {
            text = "✕ Close"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#334155"))
            setOnClickListener { finish() }
        }
        topBar.addView(tvTitle)
        topBar.addView(btnClose)
        rootLayout.addView(topBar)

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            isFillViewport = true
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 16, 20, 24)
        }

        if (dates.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "No study logs found on cloud."
                setTextColor(Color.GRAY)
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, 100, 0, 0)
            }
            container.addView(emptyTv)
        } else {
            for (dateKey in dates) {
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setBackgroundColor(Color.parseColor("#1E293B"))
                    setPadding(20, 16, 20, 16)
                    val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    params.setMargins(0, 0, 0, 12)
                    layoutParams = params
                }

                val tvDate = TextView(this).apply {
                    text = "📅 $dateKey"
                    setTextColor(Color.parseColor("#38BDF8"))
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

                val btnOpen = Button(this).apply {
                    text = "View Report →"
                    textSize = 11f
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.parseColor("#0284C7"))
                    setOnClickListener { fetchAndShowDayReport(dateKey) }
                }

                card.addView(tvDate)
                card.addView(btnOpen)
                container.addView(card)
            }
        }

        scrollView.addView(container)
        rootLayout.addView(scrollView)
    }

    private fun fetchAndShowDayReport(dateKey: String) {
        showLoading()
        dbRef.child("events").child(dateKey).addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val rawJson = snapshot.getValue(String::class.java)
                if (!rawJson.isNullOrEmpty()) {
                    showReportScreen(dateKey, JSONObject(rawJson))
                } else {
                    fetchAvailableDates()
                }
            }

            override fun onCancelled(error: DatabaseError) {
                fetchAvailableDates()
            }
        })
    }

    private fun showReportScreen(dateKey: String, json: JSONObject) {
        rootLayout.removeAllViews()

        val dayName = json.optString("dayName", dateKey)
        val topAppBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#1E293B"))
            setPadding(16, 12, 20, 12)
            gravity = Gravity.CENTER_VERTICAL
        }

        val btnBack = Button(this).apply {
            text = "←"
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setBackgroundColor(Color.parseColor("#334155"))
            setOnClickListener { fetchAvailableDates() }
        }

        val tvReportHeading = TextView(this).apply {
            text = "Full Day Report"
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(14, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tvStickyDateBadge = TextView(this).apply {
            text = "📅 $dayName"
            setTextColor(Color.parseColor("#FBBF24"))
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#312E81"))
                cornerRadius = 8f
                setStroke(1, Color.parseColor("#6366F1"))
            }
            setPadding(14, 6, 14, 6)
        }

        topAppBar.addView(btnBack)
        topAppBar.addView(tvReportHeading)
        topAppBar.addView(tvStickyDateBadge)
        rootLayout.addView(topAppBar)

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            isFillViewport = true
        }
        val contentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 12, 16, 20)
        }

        val slots = json.optJSONObject("slots") ?: JSONObject()
        var totalPresentSec = 0L
        var totalAbsentSec = 0L
        var totalBreakSec = 0L

        for (slotNum in 1..5) {
            val slotObj = slots.optJSONObject(slotNum.toString()) ?: continue
            totalPresentSec += slotObj.optLong("presentSec", 0L)
            totalAbsentSec += slotObj.optLong("absentSec", 0L)
            totalBreakSec += slotObj.optLong("officialBreakSec", 0L)
        }

        val summaryCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 12, 16, 12)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0F766E"))
                cornerRadius = 12f
            }
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            params.setMargins(0, 0, 0, 12)
            layoutParams = params
        }

        val tvStudy = TextView(this).apply {
            text = "🏆 Total Study Time: ${formatSec(totalPresentSec)}"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
        }
        val tvBreaks = TextView(this).apply {
            text = "☕ Total Breaks: ${formatSec(totalBreakSec)}"
            setTextColor(Color.parseColor("#E0F2FE"))
            textSize = 11f
            setPadding(0, 3, 0, 2)
        }
        val tvAway = TextView(this).apply {
            text = "⚠️ Unexcused Away: ${formatSec(totalAbsentSec)}"
            setTextColor(Color.parseColor("#FECACA"))
            textSize = 11f
        }

        summaryCard.addView(tvStudy)
        summaryCard.addView(tvBreaks)
        summaryCard.addView(tvAway)
        contentLayout.addView(summaryCard)

        for (slotNum in 1..5) {
            val slotObj = slots.optJSONObject(slotNum.toString())
            val presentSec = slotObj?.optLong("presentSec", 0L) ?: 0L
            val absentSec = slotObj?.optLong("absentSec", 0L) ?: 0L
            val breakSec = slotObj?.optLong("officialBreakSec", 0L) ?: 0L

            val slotCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1E293B"))
                    cornerRadius = 10f
                    setStroke(1, Color.parseColor("#334155"))
                }
                setPadding(16, 10, 16, 10)
                val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                params.setMargins(0, 0, 0, 8)
                layoutParams = params
            }

            val tvSlotTitle = TextView(this).apply {
                text = "📘 Slot $slotNum Summary"
                setTextColor(Color.parseColor("#38BDF8"))
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
            }
            val tvSlotStats = TextView(this).apply {
                text = "Study: ${formatSec(presentSec)} | Breaks: ${formatSec(breakSec)} | Away: ${formatSec(absentSec)}"
                setTextColor(Color.parseColor("#E2E8F0"))
                textSize = 10f
                setPadding(0, 3, 0, 3)
            }

            slotCard.addView(tvSlotTitle)
            slotCard.addView(tvSlotStats)

            val absences = slotObj?.optJSONArray("absences")
            if (absences != null && absences.length() > 0) {
                for (j in 0 until absences.length()) {
                    val item = absences.getJSONObject(j)
                    val reason = item.optString("reason", "Absent")
                    val tvInterval = TextView(this).apply {
                        text = "  ⚠️ ${item.optString("start")} – ${item.optString("end")} (${formatSec(item.optLong("durationSec"))}) [$reason]"
                        setTextColor(Color.parseColor("#F87171"))
                        textSize = 10f
                    }
                    slotCard.addView(tvInterval)
                }
            }

            contentLayout.addView(slotCard)
        }

        scrollView.addView(contentLayout)
        rootLayout.addView(scrollView)
    }

    private fun formatSec(sec: Long): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) String.format("%dh %02dm %02ds", h, m, s) else String.format("%02dm %02ds", m, s)
    }
}
