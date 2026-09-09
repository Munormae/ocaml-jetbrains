package dev.munormae.settings

import com.intellij.ide.settings.RemoteSettingInfo
import com.intellij.ide.settings.RemoteSettingInfoProvider

class OCamlSettingsRemoteInfoProvider : RemoteSettingInfoProvider {
    override fun getRemoteSettingsInfo(): Map<String, RemoteSettingInfo> = mapOf(
        OCamlProjectSettings.COMPONENT_NAME to RemoteSettingInfo(
            RemoteSettingInfo.Direction.InitialFromBackend,
            false,
        ),
    )
}
