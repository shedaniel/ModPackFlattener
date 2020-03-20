package me.shedaniel.modflattener

import com.google.gson.Gson
import com.google.gson.JsonParser
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

fun main() {
    val root = File(System.getProperty("user.dir"))
    flatten(root, File(root, ".removejij"), File(root, "flattenedMods"))
}

fun flatten(mods: File, tmp: File, flattenedMods: File) {
    val warnings = mutableListOf<String>()
    val outerUUID = UUID.randomUUID().toString()
    tmp.deleteRecursively()
    flattenedMods.deleteRecursively()
    if (flattenedMods.exists())
        throw FileAlreadyExistsException(flattenedMods)
    flattenedMods.mkdirs()
    var ogSize = 0L
    mods.listFiles()!!
        .forEach { file ->
            if (file.isFile && file.name.endsWith(".jar")) {
                if (isExcluding(file.inputStream())) return@forEach
                ogSize += file.length()
                countJar(
                    file.lastModified(),
                    file.name,
                    file.inputStream(),
                    true,
                    tmp, outerUUID
                )
                val modId = getModId(file.name, file.inputStream()) ?: "invalid"
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
                    file.copyTo(File(flattenedMods, file.name.stripInfoName(outerUUID)), false)
                }
            } else {
                val firstOrNull = modIdFolder.listFiles()!!
                    .firstOrNull {
                        it.isFile && it.name.stripInfoName(outerUUID).endsWith(".jar") && File(
                            mods,
                            it.name.stripInfoName(outerUUID)
                        ).exists()
                    }
                if (firstOrNull != null) {
                    firstOrNull.copyTo(File(flattenedMods, firstOrNull.name.stripInfoName(outerUUID)), false)
                    println("[INFO] Selected ${firstOrNull.stripAndDepthName(outerUUID)} from ${modIdFolder.name} as depth 0 mod")
                } else {
                    val semverMap = mutableMapOf<File, Pair<String?, SemanticVersion?>>()
                    modIdFolder.listFiles()!!.forEach { file ->
                        val modVersion = getModVersion(file.name.stripInfoName(outerUUID), file.inputStream())
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
                        println("[WARN] ${modIdFolder.name} has an invalid version: ${semverMap.filterValues { it.first == null }.entries.joinToString {
                            it.key.stripAndDepthName(
                                outerUUID
                            )
                        }}".also {
                            warnings.add(it)
                        })
                    } else if (semverMap.size > 1 && semverMap.any { it.value.second == null }) {
                        println("[WARN] ${modIdFolder.name} has a non-semantic version: ${semverMap.filterValues { it.first != null && it.second == null }.entries.joinToString {
                            "${it.key.stripAndDepthName(
                                outerUUID
                            )} [${it.value.first}]"
                        }}".also {
                            warnings.add(it)
                        })
                    } else if (sortedGroupBy.values.last().size > 1) {
                        println("[WARN] ${modIdFolder.name} have duplicate entries: ${sortedGroupBy.entries.first { it.value.size > 1 }.value.joinToString {
                            it.stripAndDepthName(
                                outerUUID
                            )
                        }}".also {
                            warnings.add(it)
                        })
                    } else {
                        noForceSelect = true
                        val entry = semverMap.entries.maxBy { it.value.second!! }!!
                        val against = semverMap.entries.toMutableList()
                            .apply { removeIf { it.value.second == entry.value.second } }.distinctBy { it.value.second }
                        if (against.isEmpty())
                            println("[INFO] Selected ${entry.key.stripAndDepthName(outerUUID)} (${entry.value.second}) from ${modIdFolder.name} as the only version")
                        else println("[INFO] Selected ${entry.key.stripAndDepthName(outerUUID)} (${entry.value.second ?: entry.value.first}) from ${modIdFolder.name} as the latest version against ${against.joinToString { it.value.second?.friendlyString ?: (entry.value.first ?: "null") }}")
                        entry.key.copyTo(File(flattenedMods, entry.key.name.stripInfoName(outerUUID)), false)
                    }
                    if (semverMap.size > 1 && !noForceSelect) {
                        val order = ConcurrentHashMap<String?, File>()
                        semverMap.entries.groupBy { it.key.getDepth(outerUUID) }
                            .minBy { it.key }!!.value.forEach { order[it.value.first] = it.key }
                        val entry = order.values.first()
                        println("[WARN] Forcefully selected ${entry.stripAndDepthName(outerUUID)} from ${modIdFolder.name}".also {
                            warnings.add(it)
                        })
                        entry.copyTo(File(flattenedMods, entry.name.stripInfoName(outerUUID)), false)
                    }
                }
            }
        } else throw AssertionError()
    }
    println()
    println("Mod Duplication Stats (Only Show 20 Top Results)")
    val countMap = mutableMapOf<String, Int>()
    tmp.listFiles()!!.forEach { modIdFolder ->
        if (modIdFolder.isDirectory) {
            countMap[modIdFolder.name] = modIdFolder.listFiles()!!.size
        } else throw AssertionError()
    }
    countMap.entries.sortedByDescending { it.value }.take(20).forEach { println(" - ${it.key} x${it.value}") }
    tmp.deleteRecursively()
    val newSize = flattenedMods.listFiles()?.filter { it.isFile && it.name.endsWith(".jar") }
        ?.sumByDouble { it.length().toDouble() }?.toLong() ?: 0L
    if (warnings.isNotEmpty()) {
        println()
        println("You have ${warnings.size} warnings:")
        warnings.forEach { println(" - ${it.replaceFirst("[WARN] ", "")}") }
    }
    println()
    println("Flattened ${ogSize.readableFileSize()} to ${newSize.readableFileSize()}")
}

fun String.stripInfoName(outerUUID: String): String {
    val indexOf = indexOf(outerUUID)
    if (indexOf < 0) return this
    return substring(indexOf + outerUUID.length + 1)
}

fun String.onlyInfoName(outerUUID: String): String {
    val indexOf = indexOf(outerUUID)
    if (indexOf < 0) return this
    return substring(0, indexOf)
}

fun File.getDepth(outerUUID: String): Int = StringUtils.countMatches(name.onlyInfoName(outerUUID), " -> ")

fun File.stripAndDepthName(outerUUID: String): String =
    "${name.stripInfoName(outerUUID)} (Depth ${getDepth(outerUUID)})"

fun Long.readableFileSize(): String {
    if (this <= 0) return "0"
    val units = arrayOf("B", "kB", "MB", "GB", "TB")
    val digitGroups = (log10(this.toDouble()) / log10(1024.0)).toInt()
    return DecimalFormat("#,##0.#").format(this / 1024.0.pow(digitGroups.toDouble())) + " " + units[digitGroups]
}

fun clearJIJ(file: File) {
    try {
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
            } catch (t: Throwable) {
                throw RuntimeException("Failed to edit fabric.mod.json for $file", t)
            }
        }
    } catch (t: Throwable) {
        if (t is RuntimeException) throw t
        throw RuntimeException("Failed to remove jars from fabric.mod.json of $file", t)
    }
}

fun stripJars(text: String): String {
    val jsonObject = JsonParser.parseString(text).asJsonObject
    jsonObject.remove("jars")
    return Gson().toJson(jsonObject)
}

fun countJar(time: Long, modName: String, inputStream: InputStream, outer: Boolean, tmp: File, outerUUID: String) {
    try {
        val zip = ZipInputStream(inputStream)
        while (true) {
            val entry = zip.nextEntry ?: break
            if (!entry.isDirectory && entry.name.endsWith(".jar")) {
                val bytes = zip.readBytes()
                countJar(
                    entry.time,
                    modName + " -> " + entry.name.split("/").last(),
                    bytes.clone().inputStream(),
                    true, tmp, outerUUID
                )
                val modId = getModId(modName, bytes.clone().inputStream()) ?: "invalid"
                val file = File(
                    File(tmp, modId),
                    "$time ${entry.time} " + (if (outer) "$modName -> $outerUUID " else "") + entry.name.split("/")
                        .last()
                )
                file.parentFile.mkdirs()
                bytes.clone().inputStream().copyTo(file.outputStream())
            }
        }
    } catch (t: Throwable) {
        throw RuntimeException("Failed to load jar: $modName", t)
    }
}


fun getModVersion(modName: String, inputStream: InputStream): String? {
    val zip = ZipInputStream(inputStream)
    while (true) {
        val entry = zip.nextEntry ?: break
        if (entry.name == "fabric.mod.json")
            return getModVersionFromJson(modName, zip)
    }
    return null
}

fun getModVersionFromJson(modName: String, inputStream: InputStream): String? {
    try {
        return JsonParser.parseString(
            inputStream.reader(Charset.defaultCharset()).readText()
        ).asJsonObject["version"].asJsonPrimitive.asString
    } catch (t: Throwable) {
        RuntimeException("Failed to read fabric.mod.json from $modName", t).printStackTrace()
    }
    return null
}

fun getModId(modName: String, inputStream: InputStream): String? {
    val zip = ZipInputStream(inputStream)
    while (true) {
        val entry = zip.nextEntry ?: break
        if (entry.name == "fabric.mod.json")
            return getModIdFromJson(modName, zip)
    }
    return null
}

fun getModIdFromJson(modName: String, inputStream: InputStream): String? {
    try {
        return JsonParser.parseString(
            inputStream.reader(Charset.defaultCharset()).readText()
        ).asJsonObject["id"].asJsonPrimitive.asString
    } catch (t: Throwable) {
        RuntimeException("Failed to read fabric.mod.json from $modName", t).printStackTrace()
    }
    return null
}

fun isExcluding(inputStream: InputStream): Boolean {
    val zip = ZipInputStream(inputStream)
    while (true) {
        val entry = zip.nextEntry ?: break
        if (entry.name == ".modpacks-flatter-exclude")
            return true
    }
    return false
}
