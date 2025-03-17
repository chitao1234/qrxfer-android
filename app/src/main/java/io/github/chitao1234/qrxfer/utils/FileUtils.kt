package io.github.chitao1234.qrxfer.utils

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Utility class for file operations
 */
object FileUtils {
    
    /**
     * Get the file name from a URI
     */
    fun getFileName(context: Context, uri: Uri): String {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor: Cursor? = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        result = it.getString(nameIndex)
                    }
                }
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/')
            if (cut != null && cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result ?: "unknown_file"
    }
    
    /**
     * Get the file size from a URI
     */
    fun getFileSize(context: Context, uri: Uri): Long {
        var fileSize: Long = 0
        if (uri.scheme == "content") {
            val cursor: Cursor? = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !it.isNull(sizeIndex)) {
                        fileSize = it.getLong(sizeIndex)
                    }
                }
            }
        }
        
        // If we couldn't get size from cursor, try to get it from file descriptor
        if (fileSize == 0L) {
            try {
                val fileDescriptor = context.contentResolver.openFileDescriptor(uri, "r")
                fileDescriptor?.use {
                    fileSize = it.statSize
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        // If we still couldn't get size, try to read the stream
        if (fileSize == 0L) {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                inputStream?.use {
                    fileSize = it.available().toLong()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        return fileSize
    }
    
    /**
     * Get the MIME type from a URI
     */
    fun getMimeType(context: Context, uri: Uri): String {
        var mimeType: String? = null
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            mimeType = context.contentResolver.getType(uri)
        }
        
        if (mimeType == null) {
            val fileExtension = MimeTypeMap.getFileExtensionFromUrl(uri.toString())
            mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileExtension.lowercase())
        }
        
        return mimeType ?: "application/octet-stream"
    }
    
    /**
     * Copy a file from a URI to a destination file
     */
    fun copyFile(context: Context, sourceUri: Uri, destFile: File): Boolean {
        try {
            val inputStream: InputStream? = context.contentResolver.openInputStream(sourceUri)
            if (inputStream == null) {
                return false
            }
            
            val outputStream = FileOutputStream(destFile)
            val buffer = ByteArray(4 * 1024) // 4KB buffer
            var read: Int
            
            while (inputStream.read(buffer).also { read = it } != -1) {
                outputStream.write(buffer, 0, read)
            }
            
            outputStream.flush()
            outputStream.close()
            inputStream.close()
            
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }
    
    /**
     * Get the app's cache directory
     */
    fun getCacheDir(context: Context): File {
        return context.cacheDir
    }
    
    /**
     * Get the app's files directory
     */
    fun getFilesDir(context: Context): File {
        return context.filesDir
    }
    
    /**
     * Get a temporary file in the cache directory
     */
    fun getTempFile(context: Context, prefix: String, suffix: String): File {
        return File.createTempFile(prefix, suffix, getCacheDir(context))
    }
    
    /**
     * Save a file to the Downloads directory
     * @return The URI of the saved file or null if saving failed
     */
    fun saveToDownloads(context: Context, sourceFile: File, filename: String, mimeType: String): Uri? {
        try {
            // For Android 10 (API 29) and above, use the MediaStore API
            val contentValues = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, filename)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
            }
            
            // Insert the ContentValues into the MediaStore
            val resolver = context.contentResolver
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Downloads.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Files.getContentUri("external")
            }
            
            val uri = resolver.insert(collection, contentValues)
            if (uri != null) {
                // Copy the file content to the new location
                resolver.openOutputStream(uri)?.use { os ->
                    sourceFile.inputStream().use { it.copyTo(os) }
                }
                
                // Update the IS_PENDING flag to 0 to publish it
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, contentValues, null, null)
                }
                
                return uri
            }
            
            return null
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }
}
