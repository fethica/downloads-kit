package com.fethica.downloadskit.compose

import androidx.compose.runtime.Composable

/**
 * The host's way out of a downloaded group: "open this group where it lives".
 *
 * The library cannot write this itself for the same two reasons it cannot draw a
 * group's artwork — it has no navigation and no vocabulary. A group is an opaque
 * id here; in one app it names an album and in another a course or a series,
 * and only the host knows which noun its user reads. So the host supplies the
 * whole thing: the label, whether it leads anywhere, and what happens on the tap.
 *
 * @param label what the button says, already in the host's language and nouns.
 * @param enabled false when the destination would have nothing to show — a group
 *   page whose source is gone and with no items stored. A disabled button with
 *   [supportingText] under it is honest; navigating into an empty screen is not.
 * @param supportingText one short line under the button saying why it is off.
 *   Null when it is on.
 */
public class GroupLink(
    public val label: String,
    public val enabled: Boolean = true,
    public val supportingText: String? = null,
    public val onOpen: () -> Unit,
)

/**
 * How the offline screen asks for one group's link: the group id as the host filed
 * it, back a link or null.
 *
 * Null means "offer nothing", which covers the loose bucket (not a group) and a
 * download whose group the host can no longer name. Composable, like
 * [DownloadArtwork], because everything it answers with comes out of the host's
 * own resources.
 */
public typealias GroupLinkFor = @Composable (group: String) -> GroupLink?
