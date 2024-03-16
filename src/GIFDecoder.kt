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

    private lateinit var rgba: ByteArray

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
//            colorResolution
//            sortFlag
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
            val colorIndexStream = uncompress()
            rgba = ByteArray(width * height * 4)
            var toIndex = 0
            var fromIndex = 0
            while (fromIndex < colorIndexStream.size) {
                val colorIndex = colorIndexStream[fromIndex]

                rgba[toIndex + 0] = (globalColorTable!![colorIndex] and 0xff0000).toByte()
                rgba[toIndex + 1] = (globalColorTable!![colorIndex] and 0x00ff00).toByte()
                rgba[toIndex + 2] = (globalColorTable!![colorIndex] and 0x0000ff).toByte()
                rgba[toIndex + 3] = 255.toByte()

                toIndex += 4
                fromIndex++
            }

            // output first row of pixels
            for (p in 0 until width-2) {
                val r = rgba[p]
                val g = rgba[p+1]
                val b = rgba[p+2]
                println("($r, $g, $b)")
            }

        } catch (e: EOFException) {
            println("Unexpected end of file while parsing GIF")
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            stream.close()
        }
    }
    fun uncompress(): List<Int> {
        var lzwMinimumCodeSize = stream.read()
        val clearCode = 1 shl lzwMinimumCodeSize
        val endCode = clearCode + 1
        val codeTable = mutableListOf<List<Int>>()
        (0 until 1.shl(lzwMinimumCodeSize) + 2).forEach { codeTable.add(listOf(it)) }

        var currentCodeSize = lzwMinimumCodeSize + 1
        var currentReadBits = 0
        var currentCodeValue = 0
        var currentSetBitIndexInCodeValue = 0
        var previousCodeValue = 0

        val indexStream = mutableListOf<Int>()
        var nextBlockSize = lzwMinimumCodeSize

        do {
            if (nextBlockSize > 0) {
                val subblock = ByteArray(nextBlockSize)
                stream.readFully(subblock)

                for (i in 0 until subblock.size) {
                    val value = subblock[i].toInt() and 0xff

                    for (shift in 0..7) {
                        if (value.shr(shift) and 0x1 == 0x1) {
                            currentCodeValue = currentCodeValue.or(1.shl(currentSetBitIndexInCodeValue))
                        }
                        currentReadBits++
                        currentSetBitIndexInCodeValue++

                        if (currentReadBits == currentCodeSize) {
                            // we got one code from code stream
//                                println(currentCodeValue)
                            if (currentCodeValue == clearCode) {
                                // reset code table
                                codeTable.clear()
                                (0 until 1.shl(lzwMinimumCodeSize) + 2).forEach { codeTable.add(listOf(it)) }

                                currentCodeSize = lzwMinimumCodeSize + 1
                            } else if (currentCodeValue == endCode) {
                                println("reach end of information code")
                            } else {
                                if (currentCodeValue < codeTable.size && previousCodeValue < codeTable.size) {
                                    // in code table
                                    indexStream.addAll(codeTable[currentCodeValue])

                                    if (previousCodeValue != clearCode) {
                                        val K = codeTable[currentCodeValue][0]
                                        val toAdded = mutableListOf<Int>()
                                        toAdded.addAll(codeTable[previousCodeValue])
                                        toAdded.add(K)

                                        codeTable.add(toAdded)
                                    }

                                } else {
                                    // not in code table
                                    if (previousCodeValue != clearCode && previousCodeValue < codeTable.size) {

                                        val K = codeTable[previousCodeValue][0]
                                        val toAdded = mutableListOf<Int>()
                                        toAdded.addAll(codeTable[previousCodeValue])
                                        toAdded.add(K)

                                        indexStream.addAll(toAdded)
                                        codeTable.add(toAdded)
                                    }
                                }
                            }

                            previousCodeValue = currentCodeValue
                            currentCodeValue = 0
                            currentReadBits = 0
                            currentSetBitIndexInCodeValue = 0
                        }

                        if (codeTable.size == 1.shl(currentCodeSize) && currentCodeSize < 12) {
                            currentCodeSize++
                        }
                    }
                }
            }
            nextBlockSize = stream.read()
        } while (nextBlockSize > 0)

        return indexStream
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
