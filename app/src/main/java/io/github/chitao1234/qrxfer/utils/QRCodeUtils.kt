package io.github.chitao1234.qrxfer.utils

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.WriterException
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.json.JSONObject
import java.util.EnumMap

/**
 * Utility class for QR code generation and processing
 */
class QRCodeUtils {
    companion object {
        private const val TAG = "QRCodeUtils"
        private const val QR_CODE_SIZE = 800
        
        /**
         * Generate a QR code bitmap from a JSON object
         * @param jsonObject The JSON object to encode
         * @param errorCorrectionLevel Error correction level (L, M, Q, H)
         * @return Bitmap containing the QR code
         */
        fun generateQRCode(jsonObject: JSONObject, errorCorrectionLevel: String = "H"): Bitmap? {
            return try {
                val jsonString = jsonObject.toString()
                Log.d(TAG, "Generating QR code from JSON object, length: ${jsonString.length}")
                generateQRCode(jsonString, errorCorrectionLevel)
            } catch (e: Exception) {
                Log.e(TAG, "Error generating QR code from JSON: ${e.message}")
                e.printStackTrace()
                null
            }
        }
        
        /**
         * Generate a QR code bitmap from a string
         * @param content The string to encode
         * @param errorCorrectionLevel Error correction level (L, M, Q, H)
         * @return Bitmap containing the QR code
         */
        fun generateQRCode(content: String, errorCorrectionLevel: String = "H"): Bitmap? {
            return try {
                Log.d(TAG, "Generating QR code with content length: ${content.length}, error correction: $errorCorrectionLevel")
                
                val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java)
                hints[EncodeHintType.MARGIN] = 1 // Make the QR code more compact
                hints[EncodeHintType.CHARACTER_SET] = "UTF-8"
                
                // Set error correction level
                val ecLevel = when (errorCorrectionLevel) {
                    "L" -> ErrorCorrectionLevel.L // ~7% correction
                    "M" -> ErrorCorrectionLevel.M // ~15% correction
                    "Q" -> ErrorCorrectionLevel.Q // ~25% correction
                    else -> ErrorCorrectionLevel.H // ~30% correction
                }
                hints[EncodeHintType.ERROR_CORRECTION] = ecLevel
                
                val writer = MultiFormatWriter()
                val bitMatrix = writer.encode(
                    content,
                    BarcodeFormat.QR_CODE,
                    QR_CODE_SIZE,
                    QR_CODE_SIZE,
                    hints
                )
                
                Log.d(TAG, "QR code matrix generated: ${bitMatrix.width}x${bitMatrix.height}")
                createBitmap(bitMatrix)
            } catch (e: WriterException) {
                Log.e(TAG, "Error writing QR code: ${e.message}")
                e.printStackTrace()
                null
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error generating QR code: ${e.message}")
                e.printStackTrace()
                null
            }
        }
        
        /**
         * Create a bitmap from a bit matrix
         * @param matrix The bit matrix to convert
         * @return Bitmap representation of the matrix
         */
        private fun createBitmap(matrix: BitMatrix): Bitmap {
            val width = matrix.width
            val height = matrix.height
            val pixels = IntArray(width * height)
            
            for (y in 0 until height) {
                val offset = y * width
                for (x in 0 until width) {
                    pixels[offset + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
                }
            }
            
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            Log.d(TAG, "QR code bitmap created: ${bitmap.width}x${bitmap.height}")
            return bitmap
        }
    }
}
