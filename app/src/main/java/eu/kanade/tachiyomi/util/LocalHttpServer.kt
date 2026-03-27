package eu.kanade.tachiyomi.util

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.net.Uri
import fi.iki.elonen.NanoHTTPD
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.net.URLConnection

class LocalHttpServer(
    port: String,
    private val contentResolver: ContentResolver,
) : NanoHTTPD(port.toInt()) {

    @SuppressLint("Recycle")
    override fun serve(session: IHTTPSession): Response {
        val params = session.parameters
        logcat(LogPriority.DEBUG) { "LocalHttpServer request: ${session.uri} params: $params" }

        val uriParam = params["uri"]?.get(0) ?: return newFixedLengthResponse(
            Response.Status.BAD_REQUEST,
            "text/plain",
            "Missing uri parameter",
        ).also { addCorsHeaders(it) }

        logcat(LogPriority.DEBUG) { "LocalHttpServer uri param: $uriParam" }

        val uri = try {
            Uri.parse(uriParam)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Invalid URI: $uriParam" }
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid URI")
                .also { addCorsHeaders(it) }
        }

        val mimeType = getMimeType(uri.toString())
        logcat(LogPriority.DEBUG) { "LocalHttpServer mimeType: $mimeType for uri: $uri" }

        val assetFileDescriptor = try {
            contentResolver.openAssetFileDescriptor(uri, "r")
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "File not found: $uri" }
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found")
                .also { addCorsHeaders(it) }
        }

        val fileLength = assetFileDescriptor?.length ?: -1L

        val rangeHeader = session.headers["range"]
        if (rangeHeader != null && fileLength > 0) {
            try {
                val range = rangeHeader.replace("bytes=", "").split("-")
                val start = range.getOrNull(0)?.toLongOrNull() ?: 0L
                val end = range.getOrNull(1)?.takeIf { it.isNotEmpty() }?.toLongOrNull() ?: (fileLength - 1)
                val length = end - start + 1

                logcat(LogPriority.DEBUG) { "LocalHttpServer range request: $start-$end/$fileLength" }

                val inputStream = contentResolver.openInputStream(uri)
                inputStream?.skip(start)

                val response = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mimeType, inputStream, length)
                response.addHeader("Content-Range", "bytes $start-$end/$fileLength")
                response.addHeader("Accept-Ranges", "bytes")
                addCorsHeaders(response)
                return response
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Error processing Range header" }
            }
        }

        val inputStream = contentResolver.openInputStream(uri)
        return if (inputStream != null) {
            logcat(LogPriority.DEBUG) { "LocalHttpServer serving file: $uri" }
            val response = newChunkedResponse(Response.Status.OK, mimeType, inputStream)
            response.addHeader("Accept-Ranges", "bytes")
            addCorsHeaders(response)
            response
        } else {
            logcat(LogPriority.ERROR) { "LocalHttpServer could not open input stream for: $uri" }
            newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found")
                .also { addCorsHeaders(it) }
        }
    }

    private fun addCorsHeaders(response: Response) {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type, Range")
        response.addHeader("Access-Control-Expose-Headers", "Content-Range, Content-Length")
    }

    private fun getMimeType(filename: String): String {
        return when {
            filename.endsWith(".mkv", ignoreCase = true) -> "video/x-matroska"
            filename.endsWith(".mp4", ignoreCase = true) -> "video/mp4"
            filename.endsWith(".webm", ignoreCase = true) -> "video/webm"
            filename.endsWith(".m3u8", ignoreCase = true) -> "application/x-mpegURL"
            filename.endsWith(".mpd", ignoreCase = true) -> "application/dash+xml"
            else -> URLConnection.guessContentTypeFromName(filename) ?: "application/octet-stream"
        }
    }
}