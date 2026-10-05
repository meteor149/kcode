package ai.meteor.kcode.localization

/** Neutral numbered string/integer interpolation shared by dictionary implementations. */
fun formatLocalizedText(template: String, arguments: List<Any>): String =
    Regex("%(\\d+)\\$([sd])|%%").replace(template) { match ->
        if (match.value == "%%") "%" else {
            val position = match.groupValues[1].toInt() - 1
            require(position in arguments.indices) { "Missing translation argument ${position + 1}" }
            val value = arguments[position]
            if (match.groupValues[2] == "d") {
                require(value is Number && value.toDouble().isFinite() && value.toLong().toDouble() == value.toDouble()) {
                    "Translation argument ${position + 1} must be an integer"
                }
                value.toLong().toString()
            } else value.toString()
        }
    }
