package com.aradsb.ar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * On-device airplane detector: SSD MobileNet v1 (quantized, COCO) via TensorFlow Lite.
 *
 * Model contract (verified by running the bundled model with the LiteRT interpreter):
 *   input : uint8 [1, 300, 300, 3] RGB
 *   output: [1,10,4] boxes (ymin,xmin,ymax,xmax, normalized to the input),
 *           [1,10] class indices (0-based into assets/labelmap.txt),
 *           [1,10] scores, [1] count
 *
 * Boxes are returned normalized to the bitmap that was passed in (the input is a plain stretch
 * resize, so normalized coordinates are valid on the original bitmap too). Only detections whose
 * label is "airplane" are returned. The model is COCO-trained, so it recognizes aircraft that are
 * visually resolvable; a distant cruise-altitude dot is below any detector's resolution.
 *
 * Model © TensorFlow authors, Apache License 2.0.
 */
class AirplaneDetector(context: Context) {

    data class Detection(val box: RectF, val score: Float)

    private val interpreter: Interpreter
    private val labels: List<String>
    private val airplaneIndex: Int

    private val inputBuf: ByteBuffer =
        ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * 3).order(ByteOrder.nativeOrder())
    private val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
    private val outBoxes = Array(1) { Array(NUM_DET) { FloatArray(4) } }
    private val outClasses = Array(1) { FloatArray(NUM_DET) }
    private val outScores = Array(1) { FloatArray(NUM_DET) }
    private val outCount = FloatArray(1)

    init {
        interpreter = Interpreter(loadModel(context), Interpreter.Options().setNumThreads(2))
        labels = context.assets.open("labelmap.txt").bufferedReader().readLines()
            .map { it.trim() }.filter { it.isNotEmpty() }
        airplaneIndex = labels.indexOf("airplane")
        require(airplaneIndex >= 0) { "labelmap.txt has no 'airplane' class" }
    }

    private fun loadModel(context: Context): ByteBuffer {
        context.assets.openFd("detect.tflite").use { afd ->
            FileInputStream(afd.fileDescriptor).channel.use { ch ->
                return ch.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
            }
        }
    }

    /**
     * Run detection on [bmp] (any size; it is stretch-resized to 300x300 internally) and return
     * airplane detections with score >= [minScore], boxes normalized to [bmp].
     * Not thread-safe; call from a single background thread.
     */
    fun detect(bmp: Bitmap, minScore: Float): List<Detection> {
        val scaled = if (bmp.width == INPUT_SIZE && bmp.height == INPUT_SIZE) bmp
        else Bitmap.createScaledBitmap(bmp, INPUT_SIZE, INPUT_SIZE, true)

        scaled.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        inputBuf.rewind()
        for (p in pixels) {
            inputBuf.put(((p shr 16) and 0xFF).toByte())   // R
            inputBuf.put(((p shr 8) and 0xFF).toByte())    // G
            inputBuf.put((p and 0xFF).toByte())            // B
        }
        if (scaled !== bmp) scaled.recycle()

        inputBuf.rewind()
        val outputs = mapOf(
            0 to outBoxes, 1 to outClasses, 2 to outScores, 3 to outCount
        )
        interpreter.runForMultipleInputsOutputs(arrayOf(inputBuf), outputs)

        val n = outCount[0].toInt().coerceIn(0, NUM_DET)
        val result = ArrayList<Detection>(2)
        for (i in 0 until n) {
            if (outScores[0][i] < minScore) continue
            if (outClasses[0][i].toInt() != airplaneIndex) continue
            val b = outBoxes[0][i]   // ymin, xmin, ymax, xmax (normalized)
            val rect = RectF(
                b[1].coerceIn(0f, 1f), b[0].coerceIn(0f, 1f),
                b[3].coerceIn(0f, 1f), b[2].coerceIn(0f, 1f)
            )
            if (rect.width() <= 0f || rect.height() <= 0f) continue
            result.add(Detection(rect, outScores[0][i]))
        }
        return result
    }

    fun close() {
        interpreter.close()
    }

    companion object {
        const val INPUT_SIZE = 300
        private const val NUM_DET = 10
    }
}
