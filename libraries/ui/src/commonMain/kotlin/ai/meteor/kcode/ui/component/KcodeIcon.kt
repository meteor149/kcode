package ai.meteor.kcode.ui.component

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import ai.meteor.kcode.ui.resources.kcode_mark
import ai.meteor.kcode.ui.resources.Res
import ai.meteor.kcode.ui.resources.icon_selection_handle
import ai.meteor.kcode.ui.resources.icon_add
import ai.meteor.kcode.ui.resources.icon_back
import ai.meteor.kcode.ui.resources.icon_bright_data
import ai.meteor.kcode.ui.resources.icon_chat
import ai.meteor.kcode.ui.resources.icon_check
import ai.meteor.kcode.ui.resources.icon_chevron_down
import ai.meteor.kcode.ui.resources.icon_chevron_right
import ai.meteor.kcode.ui.resources.icon_close
import ai.meteor.kcode.ui.resources.icon_delete
import ai.meteor.kcode.ui.resources.icon_device
import ai.meteor.kcode.ui.resources.icon_exa
import ai.meteor.kcode.ui.resources.icon_google
import ai.meteor.kcode.ui.resources.icon_info
import ai.meteor.kcode.ui.resources.icon_language
import ai.meteor.kcode.ui.resources.icon_menu
import ai.meteor.kcode.ui.resources.icon_model
import ai.meteor.kcode.ui.resources.icon_more
import ai.meteor.kcode.ui.resources.icon_openai
import ai.meteor.kcode.ui.resources.icon_deepseek
import ai.meteor.kcode.ui.resources.icon_glm
import ai.meteor.kcode.ui.resources.icon_pin
import ai.meteor.kcode.ui.resources.icon_regenerate
import ai.meteor.kcode.ui.resources.icon_root
import ai.meteor.kcode.ui.resources.icon_scroll_down
import ai.meteor.kcode.ui.resources.icon_search
import ai.meteor.kcode.ui.resources.icon_send
import ai.meteor.kcode.ui.resources.icon_settings
import ai.meteor.kcode.ui.resources.icon_share
import ai.meteor.kcode.ui.resources.icon_stop
import ai.meteor.kcode.ui.resources.icon_terminal
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

enum class KcodeIconAsset(internal val resource: DrawableResource) {
    SelectionHandle(Res.drawable.icon_selection_handle),
    Add(Res.drawable.icon_add),
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
    More(Res.drawable.icon_more),
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

/** Shared brand asset access; feature plugins own its placement and behavior. */
@Composable
fun KcodeBrandMark(modifier: Modifier = Modifier) {
    Image(painterResource(Res.drawable.kcode_mark), contentDescription = null, modifier = modifier)
}
