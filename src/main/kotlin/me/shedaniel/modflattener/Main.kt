package me.shedaniel.modflattener

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonConfiguration
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import net.fabricmc.loader.api.SemanticVersion
import net.fabricmc.loader.util.version.VersionParsingException
import org.apache.commons.lang3.StringUtils
import java.io.File
import java.io.InputStream
import java.net.URI
import java.nio.charset.Charset
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.text.DecimalFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream
import kotlin.math.log10
import kotlin.math.pow


val json = Json(JsonConfiguration.Stable.copy(ignoreUnknownKeys = true))
val root = File(System.getProperty("user.dir"))
val tmp = File(root, ".removejij")
val flattenedMods = File(root, "flattenedMods")
val warnings = mutableListOf<String>()
val outerUUID = UUID.randomUUID().toString()

fun main() {
    tmp.deleteRecursively()
    flattenedMods.deleteRecursively()
    if (flattenedMods.exists())
        throw FileAlreadyExistsException(flattenedMods)
    flattenedMods.mkdirs()
    var ogSize = 0L
    root.listFiles()!!
        .forEach { file ->
            if (file.isFile && file.name.endsWith(".jar")) {
                if (isSelf(file.inputStream())) return@forEach
                ogSize += file.length()
                countJar(
                    file.lastModified(),
                    file.name,
                    file.inputStream(),
                    true
                )
                val modId = getModId(file.inputStream()) ?: "invalid"
                val toFile = File(File(tmp, modId), file.name.split("/").last())
                toFile.parentFile.mkdirs()
                file.copyTo(toFile)
            }
        }
    tmp.listFiles()!!.forEach { modIdFolder ->
        if (modIdFolder.isDirectory) {
            modIdFolder.listFiles()!!.forEach { jar ->
                clearJIJ(jar)
            }
        } else throw AssertionError()
    }
    tmp.listFiles()!!.forEach { modIdFolder ->
        if (modIdFolder.isDirectory) {
            if (modIdFolder.name == "invalid") {
                modIdFolder.listFiles()!!.forEach { file ->
                    file.copyTo(File(flattenedMods, file.name.stripInfoName()), false)
                }
            } else {
                val firstOrNull = modIdFolder.listFiles()!!
                    .firstOrNull {
                        it.isFile && it.name.stripInfoName().endsWith(".jar") && File(
                            root,
                            it.name.stripInfoName()
                        ).exists()
                    }
                if (firstOrNull != null) {
                    firstOrNull.copyTo(File(flattenedMods, firstOrNull.name.stripInfoName()), false)
                    println("[INFO] Selected ${firstOrNull.stripAndDepthName()} from ${modIdFolder.name} as depth 0 mod")
                } else {
                    val semverMap = mutableMapOf<File, Pair<String?, SemanticVersion?>>()
                    modIdFolder.listFiles()!!.forEach { file ->
                        val modVersion = getModVersion(file.inputStream())
                        semverMap[file] = Pair(modVersion, modVersion?.let {
                            try {
                                SemanticVersion.parse(it)
                            } catch (e: VersionParsingException) {
                                null
                            }
                        })
                    }
                    val groupBy = mutableMapOf<SemanticVersion?, MutableList<File>>()
                    semverMap.entries.distinctBy { it.value.first ?: UUID.randomUUID().toString() }
                        .forEach { (file, pair) ->
                            val version =
                                pair.second?.let { version -> groupBy.keys.firstOrNull { it?.compareTo(version) == 0 } }
                            groupBy.getOrPut(version ?: pair.second) { mutableListOf() }.add(file)
                        }
                    val sortedGroupBy = groupBy.toSortedMap(Comparator.nullsFirst { o1, o2 -> o1!!.compareTo(o2) })
                    var noForceSelect = false
                    if (semverMap.isEmpty()) {
                        println("[ERROR] ${modIdFolder.name} has no entries!")
                    } else if (semverMap.size > 1 && semverMap.any { it.value.first == null }) {
                        println("[WARN] ${modIdFolder.name} has an invalid version: ${semverMap.filterValues { it.first == null }.entries.joinToString { it.key.stripAndDepthName() }}".also {
                            warnings.add(it)
                        })
                    } else if (semverMap.size > 1 && semverMap.any { it.value.second == null }) {
                        println("[WARN] ${modIdFolder.name} has a non-semantic version: ${semverMap.filterValues { it.first != null && it.second == null }.entries.joinToString { "${it.key.stripAndDepthName()} [${it.value.first}]" }}".also {
                            warnings.add(it)
                        })
                    } else if (sortedGroupBy.values.last().size > 1) {
                        println("[WARN] ${modIdFolder.name} have duplicate entries: ${sortedGroupBy.entries.first { it.value.size > 1 }.value.joinToString { it.stripAndDepthName() }}".also {
                            warnings.add(it)
                        })
                    } else {
                        noForceSelect = true
                        val entry = semverMap.entries.maxBy { it.value.second!! }!!
                        val against = semverMap.entries.toMutableList()
                            .apply { removeIf { it.value.second == entry.value.second } }.distinctBy { it.value.second }
                        if (against.isEmpty())
                            println("[INFO] Selected ${entry.key.stripAndDepthName()} (${entry.value.second}) from ${modIdFolder.name} as the only version")
                        else println("[INFO] Selected ${entry.key.stripAndDepthName()} (${entry.value.second ?: entry.value.first}) from ${modIdFolder.name} as the latest version against ${against.joinToString { it.value.second?.friendlyString ?: (entry.value.first ?: "null") }}")
                        entry.key.copyTo(File(flattenedMods, entry.key.name.stripInfoName()), false)
                    }
                    if (semverMap.size > 1 && !noForceSelect) {
                        val order = ConcurrentHashMap<String?, File>()
                        semverMap.entries.groupBy { it.key.getDepth() }
                            .minBy { it.key }!!.value.forEach { order[it.value.first] = it.key }
//                        semverMap.entries.forEach { order[it.value.first] = it.key }
                        val entry = order.values.first()
                        println("[WARN] Forcefully selected ${entry.stripAndDepthName()} from ${modIdFolder.name}".also {
                            warnings.add(it)
                        })
                        entry.copyTo(File(flattenedMods, entry.name.stripInfoName()), false)
                    }
                }
            }
        } else throw AssertionError()
    }
    tmp.deleteRecursively()
    val newSize = flattenedMods.listFiles()?.filter { it.isFile && it.name.endsWith(".jar") }
        ?.sumByDouble { it.length().toDouble() }?.toLong() ?: 0L
    if (warnings.isNotEmpty()) {
        println()
        println("You have ${warnings.size} me.shedaniel.modflattener.getWarnings:")
        warnings.forEach { println(" - ${it.replaceFirst("[WARN] ", "")}") }
    }
    println()
    println("Flattened ${ogSize.readableFileSize()} to ${newSize.readableFileSize()}")
}

fun String.stripInfoName(): String {
    val indexOf = indexOf(outerUUID)
    if (indexOf < 0) return this
    return substring(indexOf + outerUUID.length + 1)
}

fun String.onlyInfoName(): String {
    val indexOf = indexOf(outerUUID)
    if (indexOf < 0) return this
    return substring(0, indexOf)
}

fun File.getDepth(): Int = StringUtils.countMatches(name.onlyInfoName(), " -> ")

fun File.stripAndDepthName(): String = "${name.stripInfoName()} (Depth ${getDepth()})"

fun Long.readableFileSize(): String {
    if (this <= 0) return "0"
    val units = arrayOf("B", "kB", "MB", "GB", "TB")
    val digitGroups = (log10(this.toDouble()) / log10(1024.0)).toInt()
    return DecimalFormat("#,##0.#").format(this / 1024.0.pow(digitGroups.toDouble())) + " " + units[digitGroups]
}

fun clearJIJ(file: File) {
    val jars = mutableListOf<String>()
    val zip = ZipInputStream(file.inputStream())
    while (true) {
        val entry = zip.nextEntry ?: break
        if (!entry.isDirectory && entry.name.endsWith(".jar")) {
            jars.add(entry.name)
        }
    }

    val zipProperties: MutableMap<String, String?> = HashMap()
    zipProperties["create"] = "false"
    val zipURI = file.absoluteFile.toURI()

    FileSystems.newFileSystem(URI.create("jar:$zipURI"), zipProperties).use { zipfs ->
        jars.forEach { jarPath ->
            val pathInZipFile = zipfs.getPath(jarPath)
            Files.delete(pathInZipFile)
        }
        val fabricModJson = zipfs.getPath("fabric.mod.json")
        try {
            val newText = stripJars(
                Files.newBufferedReader(fabricModJson).readText()
            )
            Files.delete(fabricModJson)
            val writer = Files.newOutputStream(fabricModJson).bufferedWriter()
            writer.write(newText)
            writer.close()
        } catch (ignored: NoSuchFileException) {
        }
    }
}

fun stripJars(text: String): String {
    val newObject = mutableMapOf<String, JsonElement>()
    val jsonObject = json.parseJson(text).jsonObject
    jsonObject.forEach { key, element ->
        if (key != "jars")
            newObject[key] = element
    }
    return JsonObject(newObject).toString()
}

fun countJar(time: Long, modName: String, inputStream: InputStream, outer: Boolean) {
    val zip = ZipInputStream(inputStream)
    while (true) {
        val entry = zip.nextEntry ?: break
        if (!entry.isDirectory && entry.name.endsWith(".jar")) {
            val bytes = zip.readBytes()
            countJar(
                entry.time,
                modName + " -> " + entry.name.split("/").last(),
                bytes.clone().inputStream(),
                true
            )
            val modId = getModId(bytes.clone().inputStream()) ?: "invalid"
            val file = File(
                File(tmp, modId),
                "$time ${entry.time} " + (if (outer) "$modName -> $outerUUID " else "") + entry.name.split("/").last()
            )
            file.parentFile.mkdirs()
            bytes.clone().inputStream().copyTo(file.outputStream())
        }
    }
}

fun getModVersion(inputStream: InputStream): String? {
    val zip = ZipInputStream(inputStream)
    while (true) {
        val entry = zip.nextEntry ?: break
        if (entry.name == "fabric.mod.json")
            return getModVersionFromJson(zip)
    }
    return null
}

fun getModVersionFromJson(inputStream: InputStream): String? {
    try {
        val map = json.parse(
            FabricModInfo.serializer(),
            inputStream.reader(Charset.defaultCharset()).readText()
        )
        return map.version
    } catch (t: Throwable) {
        t.printStackTrace()
    }
    return null
}

fun getModId(inputStream: InputStream): String? {
    val zip = ZipInputStream(inputStream)
    while (true) {
        val entry = zip.nextEntry ?: break
        if (entry.name == "fabric.mod.json")
            return getModIdFromJson(zip)
    }
    return null
}

fun isSelf(inputStream: InputStream): Boolean {
    val zip = ZipInputStream(inputStream)
    while (true) {
        val entry = zip.nextEntry ?: break
        if (entry.name == ".modpacks-flatter-exclude")
            return true
    }
    return false
}


fun getModIdFromJson(inputStream: InputStream): String? {
    try {
        val map = json.parse(
            FabricModInfo.serializer(),
            inputStream.reader(Charset.defaultCharset()).readText()
        )
        return map.id
    } catch (t: Throwable) {
        t.printStackTrace()
    }
    return null
}

@Serializable
data class FabricModInfo(val id: String? = null, val version: String? = null)