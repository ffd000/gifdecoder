import java.io.DataInputStream
import java.io.EOFException
import java.io.FileInputStream
import java.io.InputStream

class GIFDecoder() {
    private lateinit var stream: DataInputStream

    companion object {
        const val EXTENSION_INTRODUCER: Int = 0x21
        const val IMAGE_SEPARATOR: Int = 0x2C
        const val TRAILER: Int = 0x3B
    }

    var width: Int = 0
        private set
    var height: Int = 0
        private set
    var gctFlag: Boolean = false
        private set
    var colorTableSize: Int = 0
        private set
    var bgIndex: Int = 0
        private set
    var aspectRatio: Int = 0
        private set
    var globalColorTable: IntArray? = null
        private set

    data class ImageDescriptor(
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
        val fields: Int
    )

    data class GCE(
        val disposalMethod: Int,
        val userInputFlag: Boolean,
        val transparentColorFlag: Boolean,
        val delayTime: Int,
        val transparentColorIndex: Int
    )

    private var imageDescriptor: ImageDescriptor? = null
    private var gce: GCE? = null

    fun read(filepath: String) {
        try {
            stream = DataInputStream(FileInputStream(filepath))

            // read main header
            val header = ByteArray(6)
            stream.readFully(header)

            if (!header.contentEquals(byteArrayOf(0x47, 0x49, 0x46, 0x38, 0x39, 0x61))) { // GIF89a
                throw IllegalArgumentException("Not a valid GIF file")
            }

            // read Local Screen Descriptor
            width = readShort(stream)
            height = readShort(stream)
            val fields = stream.read() and 0xFF
            gctFlag = (fields and 0x80) != 0
//            colorRes = (packedFields and 0x70) shr 4
//            sortFlag = (packedFields and 0x08) != 0
            colorTableSize = 2.shl(fields and 0x07)

            bgIndex = stream.read() and 0xFF
            aspectRatio = stream.read() and 0xFF

            // read Global Color Table if present
            if (gctFlag) {
                println("GCT present")
                val size = colorTableSize * 3 // size of color table in bytes (RGB)

                globalColorTable = IntArray(size)
                for (i in 0..<size step 3) {
                    val r = stream.readUnsignedByte()
                    val g = stream.readUnsignedByte()
                    val b = stream.readUnsignedByte()
                    globalColorTable!![i] = (r shl 16) or (g shl 8) or b
                }
            }

            // read Image Descriptor header
            while (true) {
                val blockType = stream.read()
                if (blockType == IMAGE_SEPARATOR) {
                    imageDescriptor = readImageDescriptor()
                    println(imageDescriptor)
                    // only one image descriptor allowed
                    break
                } else if (blockType == EXTENSION_INTRODUCER) {
                    val extensionLabel = stream.read()
                    if (extensionLabel == 0xf9) { // control extension
                        gce = readGraphicsControlExtension()
                    } else { // other extension, don't care
                        val blockSize = stream.read()
                        stream.skipBytes(blockSize)
                    }
                } else if (blockType == TRAILER) {
                    break
                }
            }

            // read image data sub-blocks
            // uncompress

        } catch (e: EOFException) {
            println("Unexpected end of file while parsing GIF")
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            stream.close()
        }
    }

    private fun readGraphicsControlExtension(): GCE? {
        val blockLength = stream.readUnsignedByte()
        if (blockLength != 4) {
            println("Invalid GCE block length: $blockLength")
            return null
        }

        val fields = stream.readUnsignedByte()
        val disposalMethod = (fields and 0x1C) shr 2
        val userInputFlag = (fields and 0x02) != 0
        val transparentColorFlag = (fields and 0x01) != 0
        val delayTime = stream.readUnsignedShort()
        val transparentColorIndex = stream.readUnsignedByte()

        return GCE(disposalMethod, userInputFlag, transparentColorFlag, delayTime, transparentColorIndex)
    }

    private fun readImageDescriptor(): ImageDescriptor {
        val imageLeft = readShort(stream)
        val imageTop = readShort(stream)
        val imageWidth = readShort(stream)
        val imageHeight = readShort(stream)
        val fields = stream.read() and 0xFF

        return ImageDescriptor(imageLeft, imageTop, imageWidth, imageHeight, fields)
    }
}

fun readShort(inputStream: InputStream): Int {
    val b1 = inputStream.read()
    val b2 = inputStream.read()
    return (b2 shl 8) or b1
}

fun hex(byteArray: ByteArray) {
    println(byteArray.joinToString(separator = " ") { byte -> "%02X".format(byte) })
}

fun main() {
    val dec = GIFDecoder()
    var data = dec.read("cat.gif")
}
