// Compile the adapter's XML dictionary into its private bytecode. External JAR/APK loading
// therefore needs no host resource lookup and carries the labels from the selected package.
val modelLabelOutput = layout.buildDirectory.dir("generated/modelLabels/kotlin")
val generateModelLabels by tasks.registering {
    val dictionaries = fileTree("src/main/localization") { include("*.xml") }
    inputs.files(dictionaries)
    outputs.dir(modelLabelOutput)
    doLast {
        fun quoted(value: String): String = "\"" + value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("$", "\\$")
            .replace("\n", "\\n")
            .replace("\r", "\\r") + "\""
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val labels = linkedMapOf<String, String>()
        dictionaries.files.sortedBy { it.name }.forEach { dictionary ->
            val strings = factory.newDocumentBuilder().parse(dictionary).getElementsByTagName("string")
            repeat(strings.length) { index ->
                val string = strings.item(index) as org.w3c.dom.Element
                val key = string.getAttribute("name")
                check(labels.put(key, string.textContent) == null) { "Duplicate model label: $key" }
            }
        }
        val output = modelLabelOutput.get().file("${project.extra["modelLabelsPackage"].toString().replace('.', '/')}/BuiltinModelLabels.kt").asFile
        output.parentFile.mkdirs()
        output.writeText(buildString {
            appendLine("package ${project.extra["modelLabelsPackage"]}")
            appendLine()
            appendLine("internal val BuiltinModelLabels: Map<String, String> = mapOf(")
            labels.forEach { (key, value) -> appendLine("    ${quoted(key)} to ${quoted(value)},") }
            appendLine(")")
        }, Charsets.UTF_8)
    }
}

tasks.matching { it.name.startsWith("compile") && it.name.contains("Kotlin") }.configureEach { dependsOn(generateModelLabels) }
