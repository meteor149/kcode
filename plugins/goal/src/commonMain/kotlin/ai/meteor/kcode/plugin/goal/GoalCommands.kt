package ai.meteor.kcode.plugin.goal

sealed interface GoalCommand {
    data object Show : GoalCommand
    data class Set(val objective: String) : GoalCommand
    data class Edit(val objective: String) : GoalCommand
    data object Pause : GoalCommand
    data object Resume : GoalCommand
    data object Clear : GoalCommand
}

fun parseGoalCommand(input: String): GoalCommand? {
    val value = input.trim()
    if (value == "/goal") return GoalCommand.Show
    if (!value.startsWith("/goal ")) return null
    val argument = value.removePrefix("/goal ").trim()
    return when {
        argument == "pause" -> GoalCommand.Pause
        argument == "resume" -> GoalCommand.Resume
        argument == "clear" -> GoalCommand.Clear
        argument == "edit" -> GoalCommand.Edit("")
        argument.startsWith("edit ") -> GoalCommand.Edit(argument.removePrefix("edit ").trim())
        else -> GoalCommand.Set(argument)
    }
}
