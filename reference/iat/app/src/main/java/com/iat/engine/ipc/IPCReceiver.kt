package com.iat.engine.ipc

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class IPCReceiver(private val port: Int = 8888, private val onPayloadReceived: (JSONObject) -> Unit) {
    private var isRunning = false
    private var serverSocket: ServerSocket? = null

    fun start() {
        isRunning = true
        thread(name = "IAT-IPC-Listener") {
            try {
                serverSocket = ServerSocket(port)
                Log.i("IAT-IPC", "IPC Receiver listening on port $port")
                while (isRunning) {
                    val client = serverSocket?.accept() ?: break
                    handleClient(client)
                }
            } catch (e: Exception) {
                Log.e("IAT-IPC", "IPC Server Error", e)
            }
        }
    }

    private fun handleClient(socket: Socket) {
        thread {
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val writer = PrintWriter(socket.getOutputStream(), true)
                val rawData = reader.readLine()
                if (!rawData.isNullOrEmpty()) {
                    val payload = JSONObject(rawData)
                    Log.i("IAT-IPC", "Ingested Context Session: ${payload.optString("session_id", "Unknown")}")
                    onPayloadReceived(payload)
                    writer.println("""{"status":"SUCCESS","message":"Context Ingested"}""")
                }
            } catch (e: Exception) {
                Log.e("IAT-IPC", "Client read error", e)
            } finally {
                socket.close()
            }
        }
    }

    fun stop() {
        isRunning = false
        serverSocket?.close()
    }
}
