package com.messenger.crisix.crypto

sealed class DecryptFailure {
    data class BadAuthTag(val skipViolation: Boolean) : DecryptFailure()
    object MalformedPayload : DecryptFailure()
    object NoSession : DecryptFailure()
}

object DecryptErrorClassifier {
    fun classify(e: Throwable?, skipViolation: Boolean): DecryptFailure {
        return when (e) {
            is javax.crypto.AEADBadTagException -> DecryptFailure.BadAuthTag(skipViolation)
            is javax.crypto.BadPaddingException -> DecryptFailure.BadAuthTag(skipViolation)
            is IllegalArgumentException -> DecryptFailure.MalformedPayload
            is java.io.IOException -> DecryptFailure.MalformedPayload
            is org.json.JSONException -> DecryptFailure.MalformedPayload
            else -> DecryptFailure.MalformedPayload
        }
    }
}
