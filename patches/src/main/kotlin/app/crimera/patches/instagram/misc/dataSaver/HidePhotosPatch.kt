/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.misc.dataSaver

import app.crimera.patches.instagram.misc.settings.settingsPatch
import app.crimera.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.crimera.patches.instagram.utils.Constants.PHOTO_HIDER_DESCRIPTOR
import app.crimera.patches.instagram.utils.enableSettings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel

/**
 * Hooks the single image-load entry used by feed, explore, profile and DM
 * photo views: IgProgressImageView.setUrl(X/00CC;), verified present with code
 * on the pinned version. Callers include the feed bindView holders.
 */
internal object FeedPhotoSetUrlFingerprint : Fingerprint(
    definingClass = "Lcom/instagram/feed/widget/IgProgressImageView;",
    name = "setUrl",
    parameters = listOf("LX/00CC;"),
)

@Suppress("unused")
val hidePhotosUntilTapPatch =
    bytecodePatch(
        name = "Hide photos until tap",
        description = "Replaces photos with a blank placeholder until tapped. Photos load inline on tap. Works with the Ultra data saver master switch and auto mode.",
        default = false,
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(settingsPatch)
        execute {
            FeedPhotoSetUrlFingerprint.method.apply {
                addInstructionsWithLabels(
                    0,
                    """
                    invoke-static {p0, p1}, $PHOTO_HIDER_DESCRIPTOR->hideUntilTap(Landroid/view/View;Ljava/lang/Object;)Z
                    move-result v0
                    if-eqz v0, :show
                    return-void
                    """.trimIndent(),
                    ExternalLabel("show", getInstruction(0)),
                )
                enableSettings("ultraHidePhotos")
            }
        }
    }
