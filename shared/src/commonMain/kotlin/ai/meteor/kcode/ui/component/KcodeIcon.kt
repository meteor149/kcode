package ai.meteor.kcode.ui.component

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import kcode.shared.generated.resources.Res
import kcode.shared.generated.resources.icon_add
import kcode.shared.generated.resources.icon_artifacts
import kcode.shared.generated.resources.icon_back
import kcode.shared.generated.resources.icon_bright_data
import kcode.shared.generated.resources.icon_chat
import kcode.shared.generated.resources.icon_check
import kcode.shared.generated.resources.icon_chevron_down
import kcode.shared.generated.resources.icon_chevron_right
import kcode.shared.generated.resources.icon_close
import kcode.shared.generated.resources.icon_delete
import kcode.shared.generated.resources.icon_device
import kcode.shared.generated.resources.icon_exa
import kcode.shared.generated.resources.icon_google
import kcode.shared.generated.resources.icon_info
import kcode.shared.generated.resources.icon_language
import kcode.shared.generated.resources.icon_menu
import kcode.shared.generated.resources.icon_model
import kcode.shared.generated.resources.icon_openai
import kcode.shared.generated.resources.icon_deepseek
import kcode.shared.generated.resources.icon_glm
import kcode.shared.generated.resources.icon_pin
import kcode.shared.generated.resources.icon_regenerate
import kcode.shared.generated.resources.icon_root
import kcode.shared.generated.resources.icon_scroll_down
import kcode.shared.generated.resources.icon_search
import kcode.shared.generated.resources.icon_send
import kcode.shared.generated.resources.icon_settings
import kcode.shared.generated.resources.icon_share
import kcode.shared.generated.resources.icon_stop
import kcode.shared.generated.resources.icon_terminal
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

enum class KcodeIconAsset(internal val resource: DrawableResource) {
    Add(Res.drawable.icon_add),
    Artifacts(Res.drawable.icon_artifacts),
    Back(Res.drawable.icon_back),
    BrightData(Res.drawable.icon_bright_data),
    Chat(Res.drawable.icon_chat),
    Check(Res.drawable.icon_check),
    ChevronDown(Res.drawable.icon_chevron_down),
    ChevronRight(Res.drawable.icon_chevron_right),
    Close(Res.drawable.icon_close),
    Delete(Res.drawable.icon_delete),
    Device(Res.drawable.icon_device),
    Exa(Res.drawable.icon_exa),
    Google(Res.drawable.icon_google),
    Info(Res.drawable.icon_info),
    Language(Res.drawable.icon_language),
    Menu(Res.drawable.icon_menu),
    Model(Res.drawable.icon_model),
    OpenAI(Res.drawable.icon_openai),
    DeepSeek(Res.drawable.icon_deepseek),
    Glm(Res.drawable.icon_glm),
    Pin(Res.drawable.icon_pin),
    Regenerate(Res.drawable.icon_regenerate),
    Root(Res.drawable.icon_root),
    ScrollDown(Res.drawable.icon_scroll_down),
    Search(Res.drawable.icon_search),
    Send(Res.drawable.icon_send),
    Settings(Res.drawable.icon_settings),
    Share(Res.drawable.icon_share),
    Stop(Res.drawable.icon_stop),
    Terminal(Res.drawable.icon_terminal),
}

@Composable
fun KcodeIcon(
    asset: KcodeIconAsset,
    tint: Color,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    Image(
        painter = painterResource(asset.resource),
        contentDescription = contentDescription,
        modifier = modifier,
        colorFilter = ColorFilter.tint(tint),
    )
}
