package top.kmar.sprite

import org.yaml.snakeyaml.Yaml
import java.awt.Color
import java.awt.Image
import java.awt.image.BufferedImage
import java.awt.image.BufferedImage.*
import java.io.File
import java.io.IOException
import java.lang.StringBuilder
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.nio.file.Files
import java.util.LinkedList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import javax.net.ssl.SSLException
import kotlin.Exception
import kotlin.IllegalStateException
import kotlin.math.abs

typealias YamlList = List<Map<String, Any>>

fun main(args: Array<String>) {
    var ymlPath = "./source/_data/link.yml"
    var outputPath = "./sprites/avatar"
    var localPath = "./.sprite_data/"
    var size = 120
    var retryTimes = 2
    var limit = 0
    var format = "jpg"
    var timeout = 10000
    val argsItor = args.iterator()
    while (argsItor.hasNext()) {
        when (val name = argsItor.next()) {
            "yml" -> ymlPath = argsItor.next()
            "output" -> outputPath = argsItor.next()
            "size" -> size = argsItor.next().toInt()
            "retry" -> retryTimes = argsItor.next().toInt()
            "limit" -> limit = argsItor.next().toInt()
            "format" -> {
                format = argsItor.next()
                if (format != "png" && format != "jpg" && format != "jpeg")
                    System.err.println("不支持的输出图像格式：$format，仅支持输出 jpg | png")
            }
            "local" -> localPath = argsItor.next()
            "timeout" -> timeout = argsItor.next().toInt()
            else -> throw IllegalStateException("未知的参数：${name}")
        }
    }
    println("正在读取图像数据……")
    val threadTool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() shl 1)
    val outputList = ArrayList<MutableCollection<Node>>()
    val ymlList = Yaml().load<YamlList>(Files.newBufferedReader(File(ymlPath).toPath()))
    val localMap = run {
        val list = File(localPath).listFiles()
        if (list == null) {
            emptyMap()
        } else {
            val map = HashMap<String, String>()
            list.forEach {
                map[it.nameWithoutExtension] = it.name
            }
            map
        }
    }
    for (map in ymlList) {
        val deprecated = map["deprecated"] as? Boolean
        if (deprecated != null && deprecated) continue
        @Suppress("UNCHECKED_CAST")
        val linkList = map["link_list"] as YamlList
        linkList.forEach {
            val domain = URL(it["link"] as String).host.run {
                val head = indexOf('.')
                val tail = lastIndexOf('.')
                if (head == tail)
                    substring(0, tail)
                else
                    substring(head + 1, tail)
            }
            val url = if (domain in localMap) {
                "file://${localPath}${localMap[domain]}"
            } else {
                it["avatar"] as String
            }
            val list = if (outputList.isEmpty()) {
                val list = LinkedList<Node>()
                outputList.add(list)
                list
            } else {
                outputList.last()
            }
            val node = Node(list.size)
            list += node
            if (list.size == limit)
                outputList.add(LinkedList())
            threadTool.submit {
                try {
                    val image = readImage(url, retryTimes, timeout)
                    if (abs(image.width - image.height) > 3)
                        println("WARN：图像【$url】长宽（width=${image.width}, height=${image.height}）不相同，可能会产生形变。")
                    node.image = image.getScaledInstance(size, size, SCALE_AREA_AVERAGING)
                } catch (e: Exception) {
                    val sb = StringBuilder("-----\nERR：读取文件【$url】时发生错误，使用空白图片代替！")
                    sb.append(when (e) {
                        is IllegalStateException -> "\n\t${e::class.simpleName} - ${e.message}"
                        is SSLException -> "\n\tSSL 证书异常：${e.message}"
                        is SocketTimeoutException -> "\n\t访问超时"
                        is UnknownHostException -> "\n\t${e::class.simpleName} - ${e.message}"
                        is IOException, is NullPointerException -> "\n\t拉取或解析文件失败：${e::class.simpleName} - ${e.message}"
                        else -> {
                            System.err.println(sb)
                            e.printStackTrace()
                            "\$EOF\$"
                        }
                    })
                    if (!sb.endsWith("\$EOF\$"))
                        System.err.println(sb)
                }
            }
        }
    }
    threadTool.shutdown()
    threadTool.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS)
    println("正在生成文件……")
    File(outputPath).parentFile.mkdirs()
    var serial = 0
    val isTransparent = format == "png"
    val type = if (isTransparent) TYPE_4BYTE_ABGR else TYPE_3BYTE_BGR
    outputList.forEach { list ->
        val bufferedImage = BufferedImage(size, size * list.size, type).apply {
            if (!isTransparent) {
                val graphics = createGraphics()
                graphics.color = Color.WHITE
                graphics.fillRect(0, 0, width, height)
                graphics.dispose()
            }
        }
        val graphics = bufferedImage.createGraphics()
        list.stream()
            .filter { it.init }
            .forEachOrdered {
                val y = it.index * size
                graphics.drawImage(it.image, 0, y, null)
            }
        graphics.dispose()
        val dist = "${outputPath}-${serial++}.${format}"
        if (!ImageIO.write(bufferedImage, format, File(dist))) {
            System.err.println("由于未知原因，向 $dist 写入数据时失败！")
        }
    }
}

/** 从网络拉取图片 */
fun readImage(url: String, retryTimes: Int, timeout: Int): BufferedImage {
    if (url.startsWith("file://"))
        return ImageIO.read(File(url.substring(7)))
    var count = 0
    var referer = "https://sprite-builder.kmar.top/"
    while (true) {
        try {
            val connection = URL(url).openConnection().apply {
                connectTimeout = timeout
                readTimeout = timeout
                setRequestProperty("accept", "image/webp, image/*")
                setRequestProperty("referer", referer)
                setRequestProperty("user-agent", "sprite-builder")
            }
            connection.connect()
            return ImageIO.read(connection.getInputStream())
        } catch (e: IOException) {
            if (++count == retryTimes) throw e
            if (e.message?.contains("code: 403 for URL") == true) {
                referer = ""
            }
        } catch (e: Exception) {
            if (++count == retryTimes) throw e
        }
    }
}

data class Node(
    val index: Int
) : Comparable<Node> {

    lateinit var image: Image
    val init: Boolean
        get() = ::image.isInitialized

    override fun compareTo(other: Node): Int {
        return index - other.index
    }

}