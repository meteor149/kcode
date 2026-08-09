package ai.meteor.kcode

import android.app.Application
import ai.meteor.kcode.chat.ChatGenerationRunner

class KcodeApplication : Application() {
    lateinit var generationRunner: ChatGenerationRunner
        private set

    override fun onCreate() {
        super.onCreate()
        generationRunner = ChatGenerationRunner(onActiveChanged = { active ->
            if (active) {
                LlmGenerationService.start(this)
            } else {
                LlmGenerationService.stop(this)
            }
        })
    }
}
