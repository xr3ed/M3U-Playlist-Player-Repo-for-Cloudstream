package com.xr3ed.idlix

import kotlinx.coroutines.runBlocking
import com.lagradost.cloudstream3.app
import java.net.ServerSocket
import java.net.Socket
import java.io.OutputStream
import kotlin.concurrent.thread
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.URI

object IdlixProxyServer {
    private var serverSocket: ServerSocket? = null
    private var port: Int = 0

    fun startAndGetProxyUrl(targetM3u8Url: String, referer: String): String {
        if (serverSocket == null || serverSocket?.isClosed == true) {
            serverSocket = ServerSocket(0)
            port = serverSocket!!.localPort
            thread {
                try {
                    while (true) {
                        val client = serverSocket!!.accept()
                        thread { handleClient(client) }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        val encUrl = URLEncoder.encode(targetM3u8Url, "UTF-8")
        val encRef = URLEncoder.encode(referer, "UTF-8")
        return "http://127.0.0.1:$port/playlist.m3u8?url=$encUrl&ref=$encRef"
    }

    private fun handleClient(client: Socket) {
        client.use { s ->
            val input = s.getInputStream()
            val output = s.getOutputStream()
            val reader = input.bufferedReader()
            val requestLine = reader.readLine() ?: return
            
            if (requestLine.contains("/playlist.m3u8")) {
                handlePlaylist(requestLine, output)
            } else if (requestLine.contains("/first_segment")) {
                handleFirstSegment(requestLine, output)
            } else {
                output.write("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray())
            }
        }
    }

    private fun extractParam(requestLine: String, paramName: String): String? {
        val query = requestLine.substringAfter("?", "").substringBefore(" HTTP")
        query.split("&").forEach {
            val parts = it.split("=")
            if (parts.size == 2 && parts[0] == paramName) {
                return URLDecoder.decode(parts[1], "UTF-8")
            }
        }
        return null
    }

    private fun handlePlaylist(requestLine: String, output: OutputStream) {
        val url = extractParam(requestLine, "url") ?: return
        val ref = extractParam(requestLine, "ref") ?: ""
        
        try {
            val m3u8Content = runBlocking { app.get(url, referer = ref).text }
            
            var initUrl: String? = null
            val mapRegex = Regex("""#EXT-X-MAP:URI="(.*?)"""")
            val match = mapRegex.find(m3u8Content)
            if (match != null) {
                val relativeInit = match.groupValues[1]
                initUrl = URI(url).resolve(relativeInit).toString()
            }
            
            val lines = m3u8Content.lines().toMutableList()
            var modifiedM3u8 = ""
            var firstSegmentReplaced = false
            
            for (line in lines) {
                if (line.startsWith("#EXT-X-MAP")) {
                    continue 
                }
                if (!line.startsWith("#") && line.isNotBlank()) {
                    val absoluteSegUrl = URI(url).resolve(line).toString()
                    if (!firstSegmentReplaced && initUrl != null) {
                        val encInit = URLEncoder.encode(initUrl, "UTF-8")
                        val encSeg = URLEncoder.encode(absoluteSegUrl, "UTF-8")
                        val encRef = URLEncoder.encode(ref, "UTF-8")
                        modifiedM3u8 += "http://127.0.0.1:$port/first_segment?init=$encInit&seg=$encSeg&ref=$encRef\n"
                        firstSegmentReplaced = true
                    } else {
                        modifiedM3u8 += "$absoluteSegUrl\n"
                    }
                } else {
                    modifiedM3u8 += "$line\n"
                }
            }
            
            val responseBytes = modifiedM3u8.toByteArray()
            output.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nContent-Length: ${responseBytes.size}\r\n\r\n".toByteArray())
            output.write(responseBytes)
        } catch (e: Exception) {
            output.write("HTTP/1.1 500 Internal Server Error\r\n\r\n".toByteArray())
        }
    }

    private fun handleFirstSegment(requestLine: String, output: OutputStream) {
        val init = extractParam(requestLine, "init") ?: return
        val seg = extractParam(requestLine, "seg") ?: return
        val ref = extractParam(requestLine, "ref") ?: ""
        
        try {
            val initRes = runBlocking { app.get(init, referer = ref).okhttpResponse }
            val segRes = runBlocking { app.get(seg, referer = ref).okhttpResponse }
            
            val initBytes = initRes.body?.bytes() ?: ByteArray(0)
            val segBytes = segRes.body?.bytes() ?: ByteArray(0)
            
            val totalSize = initBytes.size + segBytes.size
            output.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp4\r\nContent-Length: $totalSize\r\n\r\n".toByteArray())
            output.write(initBytes)
            output.write(segBytes)
        } catch (e: Exception) {
            output.write("HTTP/1.1 500 Internal Server Error\r\n\r\n".toByteArray())
        }
    }
}
