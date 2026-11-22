package ai.opencode.core.strings

import java.io.File
import java.lang.reflect.Modifier
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * [StringsClient]'s test value. JVM unit tests have no Android resources, so this reads the
 * English `src/main/res/values/strings*.xml` files from the source tree and finds a
 * resource's name from the `R` classes on the test classpath.
 */
internal object SourceStrings {
    fun string(id: Int, args: Array<out Any>): String {
        val name = names[id] ?: throw AssertionError("No string resource with id $id on the test classpath")
        val text = strings[name] ?: throw AssertionError("No <string name=\"$name\"> in values/strings*.xml")
        return format(text, args)
    }

    fun plural(id: Int, count: Int, args: Array<out Any>): String {
        val name = names[id] ?: throw AssertionError("No plurals resource with id $id on the test classpath")
        val forms = plurals[name] ?: throw AssertionError("No <plurals name=\"$name\"> in values/strings*.xml")
        // English has two forms.
        val quantity = if (count == 1) "one" else "other"
        val text = forms[quantity] ?: forms["other"] ?: throw AssertionError("<plurals name=\"$name\"> has no \"other\" item")
        return format(text, args)
    }

    /** As `Resources.getString`: the text is only formatted when there are arguments. */
    private fun format(text: String, args: Array<out Any>): String =
        if (args.isEmpty()) text else String.format(Locale.US, text, *args)

    private val strings = HashMap<String, String>()
    private val plurals = HashMap<String, Map<String, String>>()
    private val names = HashMap<Int, String>()

    init {
        for (module in moduleDirectories()) {
            readValues(File(module, "src/main/res/values"))
            readNames(module, "string")
            readNames(module, "plurals")
        }
    }

    /** Every module of the project: the root's folders and `feature/`'s. */
    private fun moduleDirectories(): List<File> {
        var root: File? = File("").absoluteFile
        while (root != null && !File(root, "settings.gradle.kts").exists()) {
            root = root.parentFile
        }
        if (root == null) {
            throw AssertionError("Couldn't find the project root (settings.gradle.kts) above ${File("").absolutePath}")
        }
        val modules = ArrayList<File>()
        for (directory in root.listFiles().orEmpty().sortedBy { it.name }) {
            if (File(directory, "src/main").isDirectory) {
                modules.add(directory)
            }
        }
        for (directory in File(root, "feature").listFiles().orEmpty().sortedBy { it.name }) {
            if (File(directory, "src/main").isDirectory) {
                modules.add(directory)
            }
        }
        return modules
    }

    private fun readValues(values: File) {
        val files = values.listFiles().orEmpty().filter { it.name.startsWith("strings") && it.name.endsWith(".xml") }
        for (file in files.sortedBy { it.name }) {
            val resources = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
            val children = resources.childNodes
            for (index in 0 until children.length) {
                val element = children.item(index) as? Element ?: continue
                val name = element.getAttribute("name")
                if (element.tagName == "string") {
                    strings[name] = unescape(element.textContent)
                }
                if (element.tagName == "plurals") {
                    val forms = HashMap<String, String>()
                    val items = element.getElementsByTagName("item")
                    for (itemIndex in 0 until items.length) {
                        val item = items.item(itemIndex) as Element
                        forms[item.getAttribute("quantity")] = unescape(item.textContent)
                    }
                    plurals[name] = forms
                }
            }
        }
    }

    /** What aapt does to a string's text: whitespace collapses, then the escapes are read. */
    private fun unescape(raw: String): String {
        val collapsed = raw.trim().replace(Regex("\\s+"), " ")
        val text = StringBuilder()
        var index = 0
        while (index < collapsed.length) {
            val character = collapsed[index]
            if (character == '\\' && index + 1 < collapsed.length) {
                val next = collapsed[index + 1]
                text.append(if (next == 'n') '\n' else if (next == 't') '\t' else next)
                index += 2
            } else {
                text.append(character)
                index += 1
            }
        }
        return text.toString()
    }

    /** Maps the ids of a module's `R.string` or `R.plurals` class to their names, if the module is on the classpath. */
    private fun readNames(module: File, type: String) {
        // A module's namespace follows its folder: feature/session is ai.opencode.feature.session.
        val folders = ArrayList<String>()
        var directory: File? = module
        while (directory != null && !File(directory, "settings.gradle.kts").exists()) {
            folders.add(0, directory.name)
            directory = directory.parentFile
        }
        val className = "ai.opencode." + folders.joinToString(".") + ".R$" + type
        val resourceClass = try {
            Class.forName(className)
        } catch (_: ClassNotFoundException) {
            return
        }
        for (field in resourceClass.fields) {
            if (!Modifier.isStatic(field.modifiers) || field.type != Int::class.javaPrimitiveType) {
                continue
            }
            val id = field.getInt(null)
            val known = names[id]
            if (known != null && known != field.name) {
                throw AssertionError("Resource id $id is both $known and ${field.name}")
            }
            names[id] = field.name
        }
    }
}
