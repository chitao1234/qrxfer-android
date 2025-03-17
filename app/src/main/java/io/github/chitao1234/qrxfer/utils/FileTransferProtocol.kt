package io.github.chitao1234.qrxfer.utils

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.codec.digest.DigestUtils
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Date
import kotlin.math.ceil
import kotlin.math.min

/**
 * Android implementation of the FileTransferProtocol
 * Based on the JavaScript implementation from qrxfer-js
 */
class FileTransferProtocol {
    private var transferId: String? = null
    private var fileUri: Uri? = null
    private var chunks = mutableListOf<JSONObject>()
    private var currentChunkIndex = 0
    private var receivedChunks = mutableMapOf<String, JSONObject>()
    private var totalReceivedChunks = 0
    private var filename: String? = null
    private var mimetype: String? = null
    private var totalChunks = 0
    
    companion object {
        private const val TAG = "FileTransferProtocol"
        private const val DEFAULT_CHUNK_SIZE = 1024 // 1KB chunks by default
    }
    
    /**
     * Generate a unique ID for the transfer session
     */
    private fun generateTransferId(): String {
        return "transfer-${Date().time}-${(Math.random() * 1000000).toInt()}"
    }
    
    /**
     * Process a file and split it into chunks
     * @param context Android context
     * @param fileUri URI of the file to process
     * @param chunkSize Size of each chunk in bytes
     * @return Number of chunks created
     */
    suspend fun processFile(context: Context, fileUri: Uri, chunkSize: Int = DEFAULT_CHUNK_SIZE): Int {
        return withContext(Dispatchers.IO) {
            this@FileTransferProtocol.fileUri = fileUri
            this@FileTransferProtocol.transferId = generateTransferId()
            this@FileTransferProtocol.chunks.clear()
            
            val contentResolver = context.contentResolver
            val fileDescriptor = contentResolver.openFileDescriptor(fileUri, "r")
            
            if (fileDescriptor == null) {
                Log.e(TAG, "Failed to open file descriptor")
                return@withContext 0
            }
            
            val fileSize = fileDescriptor.statSize
            val totalChunks = ceil(fileSize.toDouble() / chunkSize).toInt()
            
            // Get file metadata
            val cursor = contentResolver.query(fileUri, null, null, null, null)
            var displayName = "unknown_file"
            var mimeType = "application/octet-stream"
            
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex("_display_name")
                    val mimeIndex = it.getColumnIndex("mime_type")
                    
                    if (nameIndex >= 0) {
                        displayName = it.getString(nameIndex)
                    }
                    
                    if (mimeIndex >= 0) {
                        mimeType = it.getString(mimeIndex)
                    }
                }
            }
            
            try {
                val inputStream = contentResolver.openInputStream(fileUri)
                inputStream?.use { stream ->
                    val buffer = ByteArray(chunkSize)
                    var bytesRead: Int
                    var chunkIndex = 0
                    
                    while (stream.read(buffer).also { bytesRead = it } != -1) {
                        val chunkData = if (bytesRead < chunkSize) {
                            buffer.copyOf(bytesRead)
                        } else {
                            buffer
                        }
                        
                        // Convert chunk to base64
                        val base64Chunk = Base64.encodeToString(chunkData, Base64.NO_WRAP)
                        
                        // Calculate checksum
                        // To match JavaScript implementation:
                        // CryptoJS.MD5(CryptoJS.enc.Base64.parse(base64Chunk)).toString()
                        val checksum = DigestUtils.md5Hex(chunkData)
                        
                        // Create chunk data object
                        val chunkDataObj = JSONObject().apply {
                            put("id", transferId)
                            put("filename", displayName)
                            put("mimetype", mimeType)
                            put("totalChunks", totalChunks)
                            put("chunkIndex", chunkIndex)
                            put("data", base64Chunk)
                            put("checksum", checksum)
                        }
                        
                        chunks.add(chunkDataObj)
                        chunkIndex++
                    }
                }
                
                fileDescriptor.close()
                return@withContext chunks.size
            } catch (e: Exception) {
                Log.e(TAG, "Error processing file: ${e.message}")
                fileDescriptor.close()
                return@withContext 0
            }
        }
    }
    
    /**
     * Get current chunk for QR code generation
     */
    fun getCurrentChunk(): JSONObject? {
        return if (currentChunkIndex < chunks.size) {
            chunks[currentChunkIndex]
        } else {
            null
        }
    }
    
    /**
     * Get the chunk at the specified index
     */
    fun getChunkAt(index: Int): JSONObject? {
        return if (index >= 0 && index < chunks.size) {
            currentChunkIndex = index
            chunks[index]
        } else {
            Log.e(TAG, "Invalid chunk index: $index, chunks size: ${chunks.size}")
            null
        }
    }
    
    /**
     * Move to the next chunk
     */
    fun nextChunk(): JSONObject? {
        currentChunkIndex = (currentChunkIndex + 1) % chunks.size
        return getCurrentChunk()
    }
    
    /**
     * Process a received chunk from QR code
     */
    fun processReceivedChunk(chunkData: String): Map<String, Any>? {
        try {
            // Parse the JSON data
            val chunk = JSONObject(chunkData)
            
            // Basic validation
            if (!chunk.has("id") || !chunk.has("chunkIndex") || !chunk.has("data")) {
                Log.e(TAG, "Invalid chunk format: $chunk")
                return null
            }
            
            // Check if we've already received this chunk
            val chunkKey = "${chunk.getString("id")}-${chunk.getInt("chunkIndex")}"
            if (receivedChunks.containsKey(chunkKey)) {
                Log.d(TAG, "Ignoring duplicate chunk: ${chunk.getInt("chunkIndex")}")
                return null // Duplicate chunk, ignore
            }
            
            // Verify checksum
            val data = chunk.getString("data")
            // Calculate MD5 on the decoded byte array, not on the base64 string directly
            // This is to match the JavaScript implementation:
            // CryptoJS.MD5(CryptoJS.enc.Base64.parse(chunk.data)).toString()
            val decodedData = Base64.decode(data, Base64.NO_WRAP)
            val calculatedChecksum = DigestUtils.md5Hex(decodedData)
            
            if (calculatedChecksum != chunk.getString("checksum")) {
                Log.e(TAG, "Checksum mismatch for chunk: ${chunk.getInt("chunkIndex")}")
                Log.e(TAG, "Calculated: $calculatedChecksum, Expected: ${chunk.getString("checksum")}")
                return null
            }
            
            // Store the chunk
            receivedChunks[chunkKey] = chunk
            totalReceivedChunks++
            
            // Initialize the transfer info if this is the first chunk
            if (transferId == null) {
                transferId = chunk.getString("id")
                filename = chunk.getString("filename")
                mimetype = chunk.getString("mimetype")
                totalChunks = chunk.getInt("totalChunks")
                Log.d(TAG, "Initialized transfer info: id=$transferId, totalChunks=$totalChunks")
            }
            
            // Flag to indicate if this is the first chunk
            val isFirstChunk = receivedChunks.size == 1
            
            // Check if we have all chunks
            val isComplete = totalReceivedChunks == totalChunks
            
            return mapOf(
                "isNewChunk" to true,
                "isFirstChunk" to isFirstChunk,
                "progress" to Math.min(100.0, (totalReceivedChunks.toDouble() / this.totalChunks * 100)),
                "isComplete" to isComplete,
                "totalChunks" to totalChunks
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error processing chunk: ${e.message}")
            return null
        }
    }
    
    /**
     * Reconstruct the file from received chunks
     * @param context Android context
     * @param outputDir Directory to save the reconstructed file
     * @return Path to the reconstructed file or null if reconstruction failed
     */
    suspend fun reconstructFile(context: Context, outputDir: File): String? {
        return withContext(Dispatchers.IO) {
            if (transferId == null) {
                Log.e(TAG, "No transfer ID set")
                return@withContext null
            }
            
            if (totalReceivedChunks < totalChunks) {
                Log.w(TAG, "Missing chunks: $totalReceivedChunks/$totalChunks received")
            }
            
            // Log received chunks for debugging
            Log.d(TAG, "Reconstructing file from $totalReceivedChunks chunks: " +
                    "transferId=$transferId, totalChunks=$totalChunks, " +
                    "filename=$filename, mimetype=$mimetype")
            
            // Sort chunks by index
            val sortedChunks = mutableListOf<JSONObject>()
            val missingChunks = mutableListOf<Int>()
            
            for (i in 0 until totalChunks) {
                val chunkKey = "$transferId-$i"
                val chunk = receivedChunks[chunkKey]
                if (chunk == null) {
                    Log.e(TAG, "Missing chunk at index $i")
                    missingChunks.add(i)
                    // Continue and attempt reconstruction anyway in case some chunks were miscounted
                } else {
                    sortedChunks.add(chunk)
                }
            }
            
            if (missingChunks.isNotEmpty()) {
                Log.w(TAG, "Missing ${missingChunks.size} chunks: ${missingChunks.joinToString(", ")}")
                // If we're missing too many chunks, don't attempt reconstruction
                if (missingChunks.size > min(3, (totalChunks * 0.1).toInt())) {
                    return@withContext null
                }
            }
            
            try {
                // Make sure the output directory exists
                if (!outputDir.exists()) {
                    outputDir.mkdirs()
                }
                
                // Create the output file
                val outputFile = File(outputDir, filename ?: "downloaded_file")
                val outputStream = FileOutputStream(outputFile)
                
                // Process each chunk separately and write to the file
                for (chunk in sortedChunks) {
                    try {
                        // Decode each chunk's base64 data separately
                        val base64Data = chunk.getString("data")
                        val binaryData = Base64.decode(base64Data, Base64.NO_WRAP)
                        outputStream.write(binaryData)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error decoding chunk ${chunk.getInt("chunkIndex")}: ${e.message}")
                        outputStream.close()
                        return@withContext null
                    }
                }
                
                outputStream.close()
                return@withContext outputFile.absolutePath
            } catch (e: Exception) {
                Log.e(TAG, "Error reconstructing file: ${e.message}")
                return@withContext null
            }
        }
    }
    
    /**
     * Reset the receiver
     */
    fun resetReceiver() {
        transferId = null
        receivedChunks.clear()
        totalReceivedChunks = 0
        filename = null
        mimetype = null
        totalChunks = 0
    }
    
    /**
     * Get the progress of the current transfer
     */
    fun getProgress(): Double {
        return if (totalChunks > 0) {
            // Make sure progress never exceeds 100%
            Math.min(100.0, (totalReceivedChunks.toDouble() / totalChunks) * 100)
        } else {
            0.0
        }
    }
    
    /**
     * Get the total number of chunks
     */
    fun getTotalChunks(): Int {
        // For sender, use chunks.size
        // For receiver, use totalChunks field
        return if (chunks.isNotEmpty()) {
            chunks.size
        } else {
            totalChunks
        }
    }
    
    /**
     * Get the current chunk index
     */
    fun getCurrentChunkIndex(): Int {
        return currentChunkIndex
    }
    
    /**
     * Set the current chunk index
     */
    fun setCurrentChunkIndex(index: Int) {
        if (index >= 0 && index < chunks.size) {
            currentChunkIndex = index
        } else {
            Log.e(TAG, "Attempted to set invalid chunk index: $index, chunks size: ${chunks.size}")
        }
    }
    
    /**
     * Get the received chunks
     */
    fun getReceivedChunks(): Map<String, JSONObject> {
        return receivedChunks
    }
    
    /**
     * Get the total number of received chunks
     */
    fun getTotalReceivedChunks(): Int {
        return totalReceivedChunks
    }
    
    /**
     * Get the filename of the transfer
     */
    fun getFilename(): String? {
        return filename
    }
    
    /**
     * Get the MIME type of the transfer
     */
    fun getMimeType(): String? {
        return mimetype
    }
    
    /**
     * Get a list of missing chunk indices
     */
    fun getMissingChunks(): List<Int> {
        if (totalChunks == 0) return emptyList()
        
        val missingChunks = mutableListOf<Int>()
        for (i in 0 until totalChunks) {
            val chunkKey = "$transferId-$i"
            if (!receivedChunks.containsKey(chunkKey)) {
                missingChunks.add(i)
            }
        }
        return missingChunks
    }
}
