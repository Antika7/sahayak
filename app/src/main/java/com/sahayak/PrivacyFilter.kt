package com.sahayak

// Detects and strips sensitive numbers before screen text is sent to the LLM.
object PrivacyFilter {

    private val CREDIT_CARD = Regex("\\b\\d{4}[\\s-]?\\d{4}[\\s-]?\\d{4}[\\s-]?\\d{4}\\b")
    private val SSN = Regex("\\b\\d{3}-\\d{2}-\\d{4}\\b")
    private val AADHAAR = Regex("\\b\\d{4}\\s?\\d{4}\\s?\\d{4}\\b")
    private val SENSITIVE_COMBINED = Regex("(${CREDIT_CARD.pattern})|(${SSN.pattern})|(${AADHAAR.pattern})")

    // Replaces any credit card, SSN, or Aadhaar numbers with [REDACTED].
    fun redact(text: String): String = SENSITIVE_COMBINED.replace(text, "[REDACTED]")

    fun containsPaymentPattern(text: String): Boolean =
        CREDIT_CARD.containsMatchIn(text) ||
        text.contains("UPI", ignoreCase = true) ||
        text.contains("transfer", ignoreCase = true) ||
        text.contains("payment", ignoreCase = true)
}
