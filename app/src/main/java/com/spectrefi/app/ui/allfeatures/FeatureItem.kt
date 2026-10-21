package com.spectrefi.app.ui.allfeatures

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.spectrefi.app.ui.drawer.DrawerItem.Requirement

data class FeatureItem(
    @StringRes val titleRes: Int,
    @StringRes val descriptionRes: Int,
    @DrawableRes val iconRes: Int,
    val navId: Int,
    val category: FeatureCategory,
    val requirement: Requirement = Requirement.NONE
)

enum class FeatureCategory {
    CORE_TOOLS,
    API_NETWORK,
    ROOT_FUNCTIONS,
    GENERATORS,
    UTILITIES,
    NETWORK_DIAGNOSTICS,
    NET_PROTECTION,
    OTHER
}
