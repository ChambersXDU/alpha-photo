package com.chambersxdu.alphaphoto

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

internal data class CameraModel(
    val associationName: String,
    val shortName: String,
    @param:StringRes val productName: Int,
    @param:DrawableRes val heroImage: Int,
)

internal object SupportedCameras {
    val sonyA7C2 = CameraModel(
        associationName = "ILCE-7CM2",
        shortName = "A7C2",
        productName = R.string.camera_sony_a7c2_product_name,
        heroImage = R.drawable.sony_a7c2_front,
    )

    private val models = listOf(sonyA7C2)

    fun fromAssociationName(name: CharSequence?): CameraModel? =
        models.firstOrNull { model -> model.associationName == name?.toString() }
}
