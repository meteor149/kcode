// Feature-owned defaults are compiled into each private archive, without host resource lookup.
val uiTextOutput = layout.buildDirectory.dir("generated/uiTexts/kotlin")
val generateUiTexts by tasks.registering {
    val dictionary = layout.projectDirectory.file("src/main/ui-texts/strings_en.xml")
    inputs.file(dictionary)
    outputs.dir(uiTextOutput)
    doLast {
        fun quoted(value: String): String = "\"" + value
            .replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")
            .replace("\n", "\\n").replace("\r", "\\r") + "\""
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val strings = factory.newDocumentBuilder().parse(dictionary.asFile).getElementsByTagName("string")
        val labels = linkedMapOf<String, String>()
        repeat(strings.length) { index ->
            val item = strings.item(index) as org.w3c.dom.Element
            val key = item.getAttribute("name")
            require(key.isNotBlank() && labels.put(key, item.textContent) == null) { "Invalid or duplicate UI text: $key" }
        }
        val namespace = "ai.meteor.kcode.plugin.uitexts.${project.name.replace("-", "")}"
        val output = uiTextOutput.get().file("${namespace.replace('.', '/')}/BuiltinUiTexts.kt").asFile
        output.parentFile.mkdirs()
        output.writeText(buildString {
            appendLine("package $namespace")
            appendLine("internal val BuiltinUiTexts: Map<String, String> = mapOf(")
            labels.forEach { (key, value) -> appendLine("    ${quoted(key)} to ${quoted(value)},") }
            appendLine(")")
        }, Charsets.UTF_8)
    }
}
tasks.matching { it.name.startsWith("compile") && it.name.contains("Kotlin") }.configureEach {
    dependsOn(generateUiTexts)
}
