// Android VPN: owns the tun interface and the Engine. Runs as a foreground service so it survives while you play.
package com.minilan

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Base64
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream

class MiniLanVpnService : VpnService() {
    companion object {
        const val ACTION_CONNECT = "com.minilan.CONNECT"
        const val ACTION_DISCONNECT = "com.minilan.DISCONNECT"
        private const val CHANNEL = "minilan"

        @Volatile var engine: Engine? = null
            private set
        @Volatile var lastError: String? = null
        @Volatile var connecting = false

        fun loadIdentity(ctx: Context, server: String): Identity {
            val p = ctx.getSharedPreferences("id:$server", Context.MODE_PRIVATE)
            return Identity(
                p.getString("priv", null)?.let { Base64.decode(it, Base64.NO_WRAP) },
                p.getString("token", null), p.getInt("id", 0), p.getString("ip", null),
            )
        }

        fun saveIdentity(ctx: Context, server: String, id: Identity) {
            ctx.getSharedPreferences("id:$server", Context.MODE_PRIVATE).edit()
                .putString("priv", id.priv?.let { Base64.encodeToString(it, Base64.NO_WRAP) })
                .putString("token", id.token).putInt("id", id.id).putString("ip", id.ip).apply()
        }
    }

    private var tun: ParcelFileDescriptor? = null
    @Volatile private var running = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> { disconnect(); stopSelf(); return START_NOT_STICKY }
            ACTION_CONNECT -> {
                val server = intent.getStringExtra("server") ?: return START_NOT_STICKY
                val name = intent.getStringExtra("name") ?: Build.MODEL
                goForeground("Connecting…")
                connect(server, name)
            }
        }
        return START_STICKY
    }

    private fun goForeground(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "MiniLAN", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, MiniLanVpnService::class.java).setAction(ACTION_DISCONNECT), PendingIntent.FLAG_IMMUTABLE)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        val n = b.setContentTitle("MiniLAN").setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Disconnect", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
    }

    private fun connect(server: String, name: String) {
        if (engine != null || connecting) return
        connecting = true
        lastError = null
        Thread({
            try {
                val identity = loadIdentity(this, server)
                val e = Engine(server, name, identity, { saveIdentity(this, server, it) }, { protect(it) })
                val ip = e.register()
                val builder = Builder()
                    .setSession("MiniLAN")
                    .setMtu(1280)
                    .addAddress(ip, 16)
                    .addRoute("100.77.0.0", 16)
                // LAN game discovery: capture limited broadcast and multicast too.
                runCatching { builder.addRoute("255.255.255.255", 32) }
                runCatching { builder.addRoute("224.0.0.0", 4) }
                // Keep our own app's control traffic off the tunnel.
                runCatching { builder.addDisallowedApplication(packageName) }
                if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false)
                val pfd = builder.establish() ?: throw IllegalStateException("VPN permission was revoked")
                tun = pfd
                running = true
                e.start(FileOutputStream(pfd.fileDescriptor))
                engine = e
                goForeground("Connected as $ip")
                readTun(pfd, e)
            } catch (ex: Exception) {
                Log.e("MiniLAN", "connect failed", ex)
                lastError = ex.message ?: ex.toString()
                disconnect()
                stopSelf()
            } finally {
                connecting = false
            }
        }, "minilan-connect").start()
    }

    private fun readTun(pfd: ParcelFileDescriptor, e: Engine) {
        Thread({
            val input = FileInputStream(pfd.fileDescriptor)
            val buf = ByteArray(32767)
            while (running) {
                val n = try { input.read(buf) } catch (ex: Exception) { break }
                if (n > 0) e.fromTun(buf.copyOf(n))
            }
        }, "minilan-tun").start()
    }

    private fun disconnect() {
        running = false
        engine?.stop()
        engine = null
        runCatching { tun?.close() }
        tun = null
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
    }

    override fun onRevoke() { disconnect(); stopSelf() }
    override fun onDestroy() { disconnect(); super.onDestroy() }
}
