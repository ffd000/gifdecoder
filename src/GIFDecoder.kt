import java.awt.image.BufferedImage
import java.io.*
import javax.imageio.ImageIO
import java.awt.Color
import java.io.File

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
            colorTableSize = 2.shl(fields.and(0x07))
            println(fields.toString(2))
            bgIndex = stream.read() and 0xFF
            aspectRatio = stream.read() and 0xFF

            println("colorTableSize: $colorTableSize")

            // read Global Color Table if present
            if (gctFlag) {
                println("using GCT")
                val size = colorTableSize * 3 // size of color table in bytes (RGB)
//                globalColorTable = IntArray(size)
                val test = ByteArray(size)
                stream.readFully(test)
                createDebugImage(test, 16, 16, File("test.png"))

//                for (i in 0..<size step 3) {
//                    val r = stream.read() and 0xFF
//                    val g = stream.read() and 0xFF
//                    val b = stream.read() and 0xFF
//                    globalColorTable!![i] = 0xff000000.toInt() or (r shl 16) or (g shl 8) or b
//                }
            }

            return

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
                    println("trailer")
                    return
                }
            }

            val colorIndexStream = uncompress()
            rgba = ByteArray(width * height * 4)
            var toIndex = 0
            var fromIndex = 0
            while (fromIndex < colorIndexStream.size) {
                val colorIndex = colorIndexStream[fromIndex]

                rgba[toIndex + 0] = (globalColorTable!![colorIndex].shr(16) and 0xff).toByte()
                rgba[toIndex + 1] = (globalColorTable!![colorIndex].shr(8) and 0xff).toByte()
                rgba[toIndex + 2] = (globalColorTable!![colorIndex] and 0xff).toByte()
                rgba[toIndex + 3] = 255.toByte()

                toIndex += 4
                fromIndex++
            }

//            saveImage(rgba, width, height, "output.png")

        } catch (e: EOFException) {
            println("Unexpected end of file while parsing GIF")
            e.printStackTrace()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            stream.close()
        }
    }

    fun createDebugImage(colorTable: ByteArray, width: Int, height: Int, outputFile: File) {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val numColors = colorTable.size / 3

        val g2d = image.createGraphics()
        var x = 0
        var y = 0

        for (i in 0 until 256) {
            val r = colorTable[i * 3].toInt() and 0xFF
            val g = colorTable[i * 3 + 1].toInt() and 0xFF
            val b = colorTable[i * 3 + 2].toInt() and 0xFF

            val color = Color(r, g, b)

            g2d.color = color
            g2d.fillRect(x, y, 1, 1) // Adjust size as needed

            x += 1
            if (x >= width) {
                x = 0
                y += 1
            }
        }

        g2d.dispose()
        ImageIO.write(image, "PNG", outputFile)
    }

    fun uncompress(): List<Int> {
        var lzwMinimumCodeSize = stream.read()

        if (lzwMinimumCodeSize < 2 || lzwMinimumCodeSize > 8) {
            println("Incorrect starting Key size: $lzwMinimumCodeSize")
            return listOf()
        }

        val clearCode = 1 shl lzwMinimumCodeSize
        val endCode = clearCode + 1
        val codeTable = mutableListOf<List<Int>>()
        (0 until 1.shl(lzwMinimumCodeSize) + 1).forEach { codeTable.add(listOf(it)) }

        var currentCodeSize = lzwMinimumCodeSize + 1
        var currentReadBits = 0
        var currentCodeValue = 0
        var currentSetBitIndexInCodeValue = 0
        var previousCodeValue = 0

        val indexStream = mutableListOf<Int>()
        var nextBlockSize = lzwMinimumCodeSize

        outer@do {
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
                            if (currentCodeValue == clearCode) {
                                // reset code table
                                codeTable.clear()
                                (0 until 1.shl(lzwMinimumCodeSize) + 1).forEach { codeTable.add(listOf(it)) }

                                currentCodeSize = lzwMinimumCodeSize
                                println("clear")
                            } else if (currentCodeValue == endCode) {
                                println("reach end of information code")

                                break@outer
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
            nextBlockSize = stream.readUnsignedByte()
            println("$nextBlockSize")
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
    var data = dec.read("a.gif")
}
fun saveImage(pixels: IntArray, width: Int, height: Int, outputFile: String) {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)

    for (y in 0 until height) {
        for (x in 0 until width) {
            val index = (y * width + x) * 4 // Each pixel has 4 bytes (RGBA)
            val red = pixels[index].toInt() and 0xFF
            val green = pixels[index + 1].toInt() and 0xFF
            val blue = pixels[index + 2].toInt() and 0xFF
            val alpha = 255
            val pixel = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
            image.setRGB(x, y, pixel)
        }
    }

    try {
        val file = File(outputFile)
        ImageIO.write(image, "png", file)
        println("Image saved successfully to: ${file.absolutePath}")
    } catch (e: Exception) {
        println("Error saving image: ${e.message}")
    }
}