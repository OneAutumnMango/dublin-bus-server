package dev.dublinbustracker.server

import com.google.transit.realtime.GtfsRealtime.TranslatedString

/** GTFS-RT wraps user-facing text in translations; the feed only ships one, so take the first. */
fun TranslatedString.firstText(): String =
    translationList.firstOrNull()?.text.orEmpty()

/** Protobuf returns "" rather than null for unset strings, which is rarely what a caller wants. */
fun String.orNullIfBlank(): String? = takeIf { it.isNotBlank() }
