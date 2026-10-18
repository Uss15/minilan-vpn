// Simple one-screen UI: connect, private networks, public game rooms, peer list.
package com.minilan

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray

class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var server: EditText
    private lateinit var name: EditText
    private lateinit var connectBtn: Button
    private lateinit var statusText: TextView
    private lateinit var peersBox: LinearLayout
    private lateinit var roomsBox: LinearLayout
    private lateinit var gameSpinner: Spinner
    private var gameIds: List<String> = emptyList()
    private val prefs by lazy { getSharedPreferences("ui", Context.MODE_PRIVATE) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun label(t: String, bold: Boolean = false, size: Float = 14f) = TextView(this).apply {
        text = t; textSize = size; if (bold) setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(10), 0, dp(4))
    }
    private fun input(hint: String, password: Boolean = false) = EditText(this).apply {
        this.hint = hint; isSingleLine = true
        if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }
    private fun button(t: String, onClick: () -> Unit) = Button(this).apply { text = t; setOnClickListener { onClick() } }
    private fun row(vararg views: android.view.View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(32)) }

        col.addView(label("MiniLAN", bold = true, size = 22f))
        statusText = label("Not connected")
        col.addView(statusText)

        server = input("Server, e.g. http://203.0.113.10:8080").apply { setText(prefs.getString("server", getString(R.string.default_server))) }
        name = input("Your name").apply { setText(prefs.getString("name", Build.MODEL)) }
        connectBtn = button("Connect") { toggleConnect() }
        col.addView(server); col.addView(name); col.addView(connectBtn)

        col.addView(label("Private network", bold = true, size = 16f))
        val netName = input("Network name")
        val netPw = input("Password (6+ chars)", password = true)
        col.addView(netName); col.addView(netPw)
        col.addView(row(
            button("Create") { act { it.createNetwork(netName.text.toString(), netPw.text.toString()) } },
            button("Join") { act { it.joinNetwork(netName.text.toString(), netPw.text.toString()) } },
        ))

        col.addView(label("Game rooms", bold = true, size = 16f))
        gameSpinner = Spinner(this)
        col.addView(gameSpinner)
        col.addView(row(
            button("Find rooms") { loadRooms() },
            button("Host room") { hostRoom() },
        ))
        roomsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(roomsBox)

        col.addView(button("❤ Donate to MiniLAN") {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/sponsors/Uss15")))
        })

        col.addView(label("Players", bold = true, size = 16f))
        peersBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(peersBox)

        setContentView(ScrollView(this).apply { addView(col, MATCH_PARENT, WRAP_CONTENT) })
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        refreshLoop()
    }

    private fun toggleConnect() {
        if (MiniLanVpnService.engine != null || MiniLanVpnService.connecting) {
            startService(Intent(this, MiniLanVpnService::class.java).setAction(MiniLanVpnService.ACTION_DISCONNECT))
            return
        }
        prefs.edit().putString("server", server.text.toString().trim()).putString("name", name.text.toString().trim()).apply()
        val consent = VpnService.prepare(this)
        if (consent != null) startActivityForResult(consent, 1) else onActivityResult(1, RESULT_OK, null)
    }

    @Deprecated("Activity result API kept simple on purpose")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != 1 || resultCode != RESULT_OK) return
        val i = Intent(this, MiniLanVpnService::class.java).setAction(MiniLanVpnService.ACTION_CONNECT)
            .putExtra("server", server.text.toString().trim()).putExtra("name", name.text.toString().trim())
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
    }

    /** Runs a network action off the UI thread and reports the result. */
    private fun act(f: (Engine) -> Unit) {
        val e = MiniLanVpnService.engine ?: return toast("Connect first")
        Thread {
            val err = runCatching { f(e) }.exceptionOrNull()
            ui.post { toast(err?.message ?: "Done") }
        }.start()
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    private fun ensureGames(e: Engine) {
        if (gameIds.isNotEmpty() || e.games.length() == 0) return
        gameIds = e.games.keys().asSequence().filter { !it.startsWith("_") }.toList()
        val titles = gameIds.map { e.games.getJSONObject(it).optString("title", it) }
        gameSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, titles)
        gameIds.indexOf("mc-bedrock").takeIf { it >= 0 }?.let { gameSpinner.setSelection(it) }
    }

    private fun selectedGame() = gameIds.getOrNull(gameSpinner.selectedItemPosition)

    private fun loadRooms() {
        val e = MiniLanVpnService.engine ?: return toast("Connect first")
        val game = selectedGame() ?: return
        Thread {
            val rooms = runCatching { e.publicRooms(game) }
            ui.post {
                roomsBox.removeAllViews()
                rooms.onFailure { toast(it.message ?: "error") }
                rooms.onSuccess { arr -> showRooms(e, arr, game) }
            }
        }.start()
    }

    private fun showRooms(e: Engine, arr: JSONArray, game: String) {
        val tip = e.games.optJSONObject(game)?.optString("tip").orEmpty()
        if (tip.isNotEmpty()) roomsBox.addView(label("Tip: $tip", size = 12f))
        if (arr.length() == 0) roomsBox.addView(label("No rooms yet. Host one!"))
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            val roomName = r.getString("name")
            roomsBox.addView(row(
                label("$roomName  (${r.getInt("online")}/${r.getInt("members")} online)${if (r.getBoolean("locked")) " 🔒" else ""}"),
                button("Join") {
                    if (r.getBoolean("locked")) askPassword { pw -> act { it.joinNetwork(roomName, pw) } }
                    else act { it.joinNetwork(roomName, "") }
                },
            ))
        }
    }

    private fun askPassword(then: (String) -> Unit) {
        val pw = input("Password", password = true)
        AlertDialog.Builder(this).setTitle("Room password").setView(pw)
            .setPositiveButton("Join") { _, _ -> then(pw.text.toString()) }.setNegativeButton("Cancel", null).show()
    }

    private fun hostRoom() {
        val game = selectedGame() ?: return toast("Connect first")
        val roomName = input("Room name")
        AlertDialog.Builder(this).setTitle("Host a public room").setView(roomName)
            .setPositiveButton("Create") { _, _ -> act { it.createNetwork(roomName.text.toString(), "", public = true, game = game) } }
            .setNegativeButton("Cancel", null).show()
    }

    private fun refreshLoop() {
        val e = MiniLanVpnService.engine
        connectBtn.text = if (e != null || MiniLanVpnService.connecting) "Disconnect" else "Connect"
        if (e == null) {
            statusText.text = MiniLanVpnService.lastError?.let { "Error: $it" } ?: if (MiniLanVpnService.connecting) "Connecting…" else "Not connected"
            peersBox.removeAllViews()
        } else {
            ensureGames(e)
            val s = e.status()
            statusText.text = "You: ${s.ip}  ·  relay ${s.relay}\nPublic ${s.publicEndpoint ?: "?"}"
            peersBox.removeAllViews()
            for (i in 0 until s.networks.length()) {
                val n = s.networks.getJSONObject(i)
                val game = n.optString("game").takeIf { n.optBoolean("public") }
                peersBox.addView(row(
                    label("■ ${n.getString("name")}${game?.let { " · " + (e.games.optJSONObject(it)?.optString("title") ?: it) } ?: ""}", bold = true),
                    button("Leave") { act { it.leaveNetwork(n.getString("name")) } },
                ))
                val members = n.getJSONArray("members")
                for (j in 0 until members.length()) {
                    val p = s.peers.firstOrNull { it.id == members.getInt(j) } ?: continue
                    val state = when { !p.online -> "offline"; p.path == "relay" -> "Relay"; p.path.startsWith("direct") -> "Direct"; else -> "connecting…" }
                    peersBox.addView(label("   ${p.name}  ${p.ip}  ·  $state${p.rtt?.let { "  ${it} ms" } ?: ""}"))
                }
            }
        }
        ui.postDelayed(::refreshLoop, 1500)
    }
}
