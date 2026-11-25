/*

 This is the source code of exteraGram for Android.

 We do not and cannot prevent the use of our code,
 but be respectful and credit the original author.

 Copyright @exteraGram, 2022-2026

 Licensed under the GNU GPL v. 3 or later, with the additional terms
 in ADDITIONAL_TERMS.md at the root of the repository.

*/

package com.exteragram.messenger.xray

import androidx.annotation.Keep

/**
 * A single Xray connection profile.
 *
 * [xrayJson] is the outbound-only Xray configuration produced by
 * libXray's convertShareLinksToXrayJson, i.e. {"outbounds":[...]}.
 * The full runnable config is assembled by [XrayConfigBuilder].
 */
@Keep
data class XrayProfile(
    var id: String = "",
    var name: String = "",
    var xrayJson: String = "",
    var remark: String = "",
    var subscriptionId: String = "",
    var lastDelayMs: Long = 0L,
    var lastCheckTime: Long = 0L,
) {
    val isValid: Boolean
        get() = xrayJson.isNotBlank()
}

/**
 * A subscription source holding one or more profiles.
 */
@Keep
data class XraySubscription(
    var id: String = "",
    var url: String = "",
    var name: String = "",
    var updatedAt: Long = 0L,
    var profileIds: List<String> = emptyList(),
)
