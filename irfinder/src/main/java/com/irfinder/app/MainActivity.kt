package com.irfinder.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.ConsumerIrManager
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private val cInk = Color.parseColor("#1B2430")
    private val cMuted = Color.parseColor("#55606E")
    private val cAccent = Color.parseColor("#1565C0")
    private val cGreen = Color.parseColor("#2E7D32")
    private val cRed = Color.parseColor("#C62828")
    private val cOrange = Color.parseColor("#EF6C00")
    private val cGrey = Color.parseColor("#78909C")
    private val cBg = Color.parseColor("#EEF1F6")

    private var ir: ConsumerIrManager? = null
    private lateinit var store: RemoteStore
    private lateinit var prefs: SharedPreferences
    private val scanner = Scanner()

    private val protoChecks = LinkedHashMap<Protocol, CheckBox>()
    private lateinit var quickSwitch: Switch
    private lateinit var rateGroup: RadioGroup
    private lateinit var estimateView: TextView
    private lateinit var progressView: TextView
    private lateinit var startBtn: Button
    private lateinit var pauseBtn: Button
    private lateinit var reactedBtn: Button
    private lateinit var resetBtn: Button
    private lateinit var candidateCard: LinearLayout
    private lateinit var candidateText: TextView
    private lateinit var remoteBox: LinearLayout
    private lateinit var moreBtn: Button
    private lateinit var protoSpinner: Spinner
    private lateinit var addrEdit: EditText
    private lateinit var cmdEdit: EditText
    private lateinit var rawEdit: EditText
    private lateinit var rawHzEdit: EditText

    private var candidates: List<IrCode> = emptyList()
    private var candidateIndex = 0
    private var lastFound: IrCode? = null

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ir = getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager
        store = RemoteStore(this)
        prefs = getSharedPreferences("scan", Context.MODE_PRIVATE)
        buildUi()
        setScanning(false)
        refreshRemote()
        updateEstimate()
    }

    override fun onPause() {
        scanner.stop()
        persistPositions()
        setScanning(false)
        super.onPause()
    }

    // ------------------------------------------------------------------ UI construction

    private fun buildUi() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(20), dp(14), dp(32))
        }

        page.addView(TextView(this).apply {
            text = "IR Finder"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(cInk)
        })
        page.addView(body("Finds the infrared codes of a remote-controlled device (like your fan) by trying them one after another, then turns your phone into its remote."))

        val hasEmitter = ir?.hasIrEmitter() == true
        page.addView(TextView(this).apply {
            text = if (hasEmitter) "✓ This phone has an infrared emitter."
            else "✗ No infrared emitter found. This app cannot work on this phone."
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (hasEmitter) cGreen else cRed)
            setPadding(0, dp(2), 0, dp(12))
        })

        // ---- Step 0: LED test
        val c0 = card()
        c0.addView(heading("Step 0 - Check the phone's IR LED"))
        c0.addView(body("The LED is on the top edge. You cannot see infrared, but another phone's front camera can: point it at the top edge of this phone, press the button and look for a purple or white flicker."))
        c0.addView(button("Test IR LED (3 seconds)", cAccent) { testLed() }, btnLp())
        page.addView(c0)

        // ---- Step 1: search
        val c1 = card()
        c1.addView(heading("Step 1 - Search for the fan's codes"))
        c1.addView(body("Stand about 30 cm from the fan and point the top edge of the phone at its control panel (take any case off). Press Start. The moment the fan beeps, lights up, starts, stops or changes speed, press THE FAN REACTED."))
        for (p in Protocol.values()) {
            if (!p.scannable) continue
            val cb = CheckBox(this)
            cb.text = p.label
            cb.isChecked = p == Protocol.NEC || p == Protocol.SAMSUNG || p == Protocol.JVC
            cb.setOnCheckedChangeListener { _, _ -> updateEstimate() }
            protoChecks[p] = cb
            c1.addView(cb)
        }
        quickSwitch = Switch(this)
        quickSwitch.text = "Quick search (most common addresses only)"
        quickSwitch.isChecked = true
        quickSwitch.setOnCheckedChangeListener { _, _ -> updateEstimate() }
        c1.addView(quickSwitch, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        rateGroup = RadioGroup(this)
        rateGroup.orientation = RadioGroup.HORIZONTAL
        rateGroup.addView(rateButton("Slow 4/s", 4, false))
        rateGroup.addView(rateButton("Normal 8/s", 8, true))
        rateGroup.addView(rateButton("Fast 12/s", 12, false))
        rateGroup.setOnCheckedChangeListener { _, _ -> updateEstimate() }
        c1.addView(rateGroup)

        estimateView = body("")
        c1.addView(estimateView)

        startBtn = button("Start / resume search", cGreen) { startSearch() }
        c1.addView(startBtn, btnLp())
        reactedBtn = button("THE FAN REACTED!", cRed) { onReacted() }
        c1.addView(reactedBtn, btnLp(dp(68)))
        pauseBtn = button("Pause", cOrange) { pauseSearch() }
        c1.addView(pauseBtn, btnLp())
        resetBtn = button("Start over (forget progress)", cGrey) { resetPositions() }
        c1.addView(resetBtn, btnLp())
        progressView = body("Not started yet.")
        c1.addView(progressView)
        page.addView(c1)

        // ---- Candidate check (hidden until needed)
        candidateCard = card()
        candidateCard.visibility = View.GONE
        candidateCard.addView(heading("Which code did it?"))
        candidateText = body("")
        candidateCard.addView(candidateText)
        candidateCard.addView(button("Send this code again", cAccent) { sendCandidate() }, btnLp())
        candidateCard.addView(button("Yes - the fan reacted to this one", cGreen) { candidateYes() }, btnLp())
        candidateCard.addView(button("No - try the next one", cRed) { candidateNo() }, btnLp())
        page.addView(candidateCard)

        // ---- Step 2: my remote
        val c2 = card()
        c2.addView(heading("Step 2 - My remote"))
        c2.addView(body("Saved buttons appear here. Tap one to send it. Press and hold a button to delete it."))
        remoteBox = LinearLayout(this)
        remoteBox.orientation = LinearLayout.VERTICAL
        c2.addView(remoteBox)
        moreBtn = button("Find more buttons (same address)", cAccent) { searchMoreButtons() }
        c2.addView(moreBtn, btnLp())
        page.addView(c2)

        // ---- Manual send
        val c3 = card()
        c3.addView(heading("Manual send"))
        c3.addView(body("If you already know a code, for example from a Flipper Zero or LIRC file. Values are hex."))
        protoSpinner = Spinner(this)
        protoSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            Protocol.values().map { it.label }
        )
        c3.addView(protoSpinner)
        addrEdit = EditText(this)
        addrEdit.hint = "Address, e.g. 00"
        cmdEdit = EditText(this)
        cmdEdit.hint = "Command, e.g. 45"
        c3.addView(addrEdit)
        c3.addView(cmdEdit)
        c3.addView(button("Send this code", cAccent) { sendManual() }, btnLp())
        c3.addView(body("Raw pattern (microseconds, mark first, separated by commas or spaces):"))
        rawEdit = EditText(this)
        rawEdit.hint = "9000 4500 560 560 560 1690 ..."
        rawHzEdit = EditText(this)
        rawHzEdit.hint = "Carrier Hz (default 38000)"
        c3.addView(rawEdit)
        c3.addView(rawHzEdit)
        c3.addView(button("Send raw pattern", cAccent) { sendRawPattern() }, btnLp())
        page.addView(c3)

        page.addView(body("No internet permission. Saved buttons and progress stay on this phone."))

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(cBg)
        scroll.addView(page)
        setContentView(scroll)
    }

    // ------------------------------------------------------------------ search flow

    private fun selectedSources(): List<CodeSource> {
        val quick = quickSwitch.isChecked
        val out = ArrayList<CodeSource>()
        for (p in Protocol.values()) {
            if (p.scannable && protoChecks[p]?.isChecked == true) out.add(ProtocolSource(p, quick))
        }
        return out
    }

    private fun perSec(): Int {
        val rb = findViewById<RadioButton>(rateGroup.checkedRadioButtonId)
        return (rb?.tag as? Int) ?: 8
    }

    private fun startSearch() {
        val sources = selectedSources()
        if (sources.isEmpty()) {
            toast("Tick at least one protocol.")
            return
        }
        val starts = IntArray(sources.size) {
            prefs.getInt("pos_" + sources[it].key, 0).coerceIn(0, sources[it].size)
        }
        runScan(sources, starts)
    }

    private fun searchMoreButtons() {
        val base = lastFound ?: store.load().lastOrNull()?.code
        if (base == null) {
            toast("Save a first button before searching for more.")
            return
        }
        runScan(listOf(AddressSource(base.protocol, base.address)), intArrayOf(0))
    }

    private fun runScan(sources: List<CodeSource>, starts: IntArray) {
        val m = ir
        if (m == null || !m.hasIrEmitter()) {
            toast("This phone has no infrared emitter.")
            return
        }
        hideCandidates()
        var left = 0
        for (i in sources.indices) left += sources[i].size - starts[i]
        if (left <= 0) {
            progressView.text = "Everything in this selection was already tried. Press \"Start over\" to run it again."
            return
        }
        setScanning(true)
        progressView.text = "Starting..."
        scanner.start(m, sources, starts, perSec(), scanListener)
    }

    private val scanListener = object : Scanner.Listener {
        override fun onProgress(code: IrCode, done: Int, total: Int, title: String) {
            runOnUiThread {
                progressView.text = "$title\nTrying: ${code.describe()}\n$done of $total"
            }
        }

        override fun onFinished() {
            runOnUiThread {
                setScanning(false)
                persistPositions()
                progressView.text = "Finished: every code in this selection was tried and none was confirmed.\n" +
                    "Try other protocols, switch off Quick search, or the fan may use radio (RF) instead of infrared."
            }
        }

        override fun onError(message: String) {
            runOnUiThread {
                setScanning(false)
                progressView.text = "Stopped: $message"
            }
        }
    }

    private fun pauseSearch() {
        scanner.stop()
        persistPositions()
        setScanning(false)
        progressView.text = "Paused. Press Start / resume to continue where you left off."
        updateEstimate()
    }

    private fun onReacted() {
        scanner.stop()
        persistPositions()
        setScanning(false)
        candidates = scanner.candidates()
        candidateIndex = 0
        if (candidates.isEmpty()) {
            progressView.text = "No recent codes to check. Press Start / resume."
            return
        }
        progressView.text = "Stopped. Now let's find which code made the fan react."
        showCandidate()
    }

    private fun showCandidate() {
        candidateCard.visibility = View.VISIBLE
        val code = candidates[candidateIndex]
        candidateText.text = "Candidate ${candidateIndex + 1} of ${candidates.size}\n${code.describe()}\n\n" +
            "It was just sent. Did the fan react? Press \"Send this code again\" to double-check."
        sendCandidate()
    }

    private fun sendCandidate() {
        val m = ir ?: return
        val err = Sender.send(m, candidates[candidateIndex])
        if (err != null) toast("Send failed: $err")
    }

    private fun candidateYes() {
        val code = candidates[candidateIndex]
        hideCandidates()
        promptSave(code)
    }

    private fun candidateNo() {
        candidateIndex++
        if (candidateIndex >= candidates.size) {
            hideCandidates()
            progressView.text = "None of the recent codes matched. Press Start / resume to keep searching, " +
                "and next time press THE FAN REACTED a little sooner."
        } else {
            showCandidate()
        }
    }

    private fun hideCandidates() {
        candidateCard.visibility = View.GONE
        candidates = emptyList()
    }

    private fun promptSave(code: IrCode) {
        val input = EditText(this)
        input.setText(suggestName())
        input.setSelectAllOnFocus(true)
        val holder = FrameLayout(this)
        holder.setPadding(dp(20), dp(8), dp(20), 0)
        holder.addView(input)
        AlertDialog.Builder(this)
            .setTitle("Name this button")
            .setMessage(code.describe())
            .setView(holder)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { "Button" }
                val list = store.load()
                list.add(Saved(name, code))
                store.save(list)
                lastFound = code
                refreshRemote()
                progressView.text = "Saved \"$name\". Use \"Find more buttons\" to look for speed, oscillation and timer."
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun suggestName(): String {
        val used = store.load().map { it.name.lowercase() }
        for (n in listOf("Power", "Speed", "Oscillation", "Timer", "Mode", "Light")) {
            if (n.lowercase() !in used) return n
        }
        return "Button"
    }

    // ------------------------------------------------------------------ saved remote

    private fun refreshRemote() {
        remoteBox.removeAllViews()
        val list = store.load()
        if (list.isEmpty()) {
            remoteBox.addView(body("No saved buttons yet."))
        }
        for ((i, s) in list.withIndex()) {
            val b = button(s.name, cAccent) { sendSaved(s) }
            b.setOnLongClickListener {
                AlertDialog.Builder(this)
                    .setTitle("Delete \"${s.name}\"?")
                    .setPositiveButton("Delete") { _, _ ->
                        val cur = store.load()
                        if (i < cur.size) cur.removeAt(i)
                        store.save(cur)
                        refreshRemote()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                true
            }
            remoteBox.addView(b, btnLp(dp(56)))
        }
        moreBtn.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun sendSaved(s: Saved) {
        val m = ir
        if (m == null) {
            toast("No infrared emitter.")
            return
        }
        val err = Sender.send(m, s.code)
        if (err != null) toast("Send failed: $err")
    }

    // ------------------------------------------------------------------ extras

    private fun testLed() {
        val m = ir
        if (m == null || !m.hasIrEmitter()) {
            toast("This phone has no infrared emitter.")
            return
        }
        val err = Sender.sendRaw(m, 38000, IntArray(40) { 75000 })
        toast(if (err == null) "Sending for 3 seconds - watch it with a camera." else "Failed: $err")
    }

    private fun parseHex(s: String): Int? = s.trim().removePrefix("0x").removePrefix("0X").toIntOrNull(16)

    private fun sendManual() {
        val m = ir
        if (m == null) {
            toast("No infrared emitter.")
            return
        }
        val a = parseHex(addrEdit.text.toString())
        val c = parseHex(cmdEdit.text.toString())
        if (a == null || c == null) {
            toast("Enter the address and command in hex, e.g. 00 and 45.")
            return
        }
        var p = Protocol.values()[protoSpinner.selectedItemPosition]
        if (p == Protocol.NEC && a > 0xFF) p = Protocol.NEC_EXT
        val err = Sender.send(m, IrCode(p, a, c))
        toast(if (err == null) "Sent ${p.label}" else "Failed: $err")
    }

    private fun sendRawPattern() {
        val m = ir
        if (m == null) {
            toast("No infrared emitter.")
            return
        }
        val nums = rawEdit.text.toString().split(Regex("[,;\\s]+")).filter { it.isNotBlank() }.map { it.toIntOrNull() }
        if (nums.isEmpty() || nums.any { it == null || it <= 0 }) {
            toast("Enter positive numbers separated by commas or spaces.")
            return
        }
        val hz = rawHzEdit.text.toString().trim().toIntOrNull() ?: 38000
        val err = Sender.sendRaw(m, hz, nums.map { it!! }.toIntArray())
        toast(if (err == null) "Sent raw pattern" else "Failed: $err")
    }

    // ------------------------------------------------------------------ progress bookkeeping

    private fun persistPositions() {
        val e = prefs.edit()
        for ((k, v) in scanner.positionsSnapshot()) {
            if (k.startsWith("proto_")) e.putInt("pos_$k", v)
        }
        e.apply()
    }

    private fun resetPositions() {
        scanner.stop()
        val e = prefs.edit()
        for (s in selectedSources()) e.remove("pos_" + s.key)
        e.apply()
        setScanning(false)
        progressView.text = "Progress cleared for the selected protocols."
        updateEstimate()
    }

    private fun updateEstimate() {
        if (!::estimateView.isInitialized) return
        var left = 0
        var total = 0
        for (s in selectedSources()) {
            val pos = prefs.getInt("pos_" + s.key, 0).coerceIn(0, s.size)
            left += s.size - pos
            total += s.size
        }
        val minutes = left.toDouble() / perSec() / 60.0
        val time = when {
            left == 0 -> "nothing left"
            minutes < 1.5 -> "about 1 minute"
            minutes < 90 -> "about ${minutes.roundToInt()} minutes"
            else -> "about ${"%.1f".format(minutes / 60.0)} hours"
        }
        estimateView.text = "$left of $total codes left - $time at ${perSec()} codes per second."
    }

    private fun setScanning(scanning: Boolean) {
        enable(startBtn, !scanning)
        enable(reactedBtn, scanning)
        enable(pauseBtn, scanning)
        enable(resetBtn, !scanning)
    }

    private fun enable(b: Button, on: Boolean) {
        b.isEnabled = on
        b.alpha = if (on) 1f else 0.4f
    }

    // ------------------------------------------------------------------ small view helpers

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun card(): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(16), dp(14), dp(16), dp(16))
        val bg = GradientDrawable()
        bg.setColor(Color.WHITE)
        bg.cornerRadius = dp(16).toFloat()
        c.background = bg
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.setMargins(0, 0, 0, dp(12))
        c.layoutParams = lp
        return c
    }

    private fun heading(t: String): TextView {
        val v = TextView(this)
        v.text = t
        v.textSize = 17f
        v.setTypeface(v.typeface, Typeface.BOLD)
        v.setTextColor(cInk)
        return v
    }

    private fun body(t: String): TextView {
        val v = TextView(this)
        v.text = t
        v.textSize = 13.5f
        v.setTextColor(cMuted)
        v.setPadding(0, dp(4), 0, dp(6))
        return v
    }

    private fun button(label: String, color: Int, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.setTextColor(Color.WHITE)
        val bg = GradientDrawable()
        bg.setColor(color)
        bg.cornerRadius = dp(12).toFloat()
        b.background = bg
        b.setOnClickListener { onClick() }
        return b
    }

    private fun btnLp(height: Int = dp(50)): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
        lp.topMargin = dp(8)
        return lp
    }

    private fun rateButton(label: String, perSecond: Int, checked: Boolean): RadioButton {
        val r = RadioButton(this)
        r.id = View.generateViewId()
        r.text = label
        r.tag = perSecond
        r.isChecked = checked
        return r
    }
}
