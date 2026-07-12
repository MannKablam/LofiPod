package com.lofipod.app.util

/**
 * Strip HTML-ish markup down to plain prose. Third copy of the regex
 * chain that lived privately in EpisodesScreen (stripHtml) and
 * PlayerScreen (stripHtmlForDetails) — extracted per those files' own
 * "on third use" note. New callers should use this; migrating the two
 * privates is optional cleanup.
 */
fun String.stripHtmlToPlainText(): String = this
    .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
    .replace(Regex("</p>", RegexOption.IGNORE_CASE), "\n\n")
    .replace(Regex("<[^>]+>"), " ")
    .replace(Regex("&nbsp;"), " ")
    .replace(Regex("&amp;"), "&")
    .replace(Regex("&lt;"), "<")
    .replace(Regex("&gt;"), ">")
    .replace(Regex("&quot;"), "\"")
    .replace(Regex("&#39;|&apos;"), "'")
    .replace(Regex("[ \\t]+"), " ")
    .trim()
