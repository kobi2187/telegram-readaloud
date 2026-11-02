package io.github.kobi2187.readaloud.tts

import java.io.IOException

/** Failures shared by the online voice services. Anything else is a plain (network) IOException. */
open class CloudTtsException(message: String) : IOException(message)

/** The key was rejected (wrong, revoked, or the service is not enabled for it). */
class AuthException(service: String, code: Int) : CloudTtsException("$service rejected the key (HTTP $code)")

/** Too many requests or the free quota is used up; try again after [retryAfterSeconds]. */
class ThrottledException(val retryAfterSeconds: Long?, val quota: Boolean) :
    CloudTtsException(if (quota) "Free quota used up" else "Too many requests")

/** The service refused this particular text; retrying will not help. */
class RejectedTextException(code: Int) : CloudTtsException("The text was rejected (HTTP $code)")
