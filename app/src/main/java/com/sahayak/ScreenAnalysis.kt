package com.sahayak

enum class RiskLevel { NONE, LOW, HIGH }

data class ScreenAnalysisResult(
    val explanation: String,
    val riskLevel: RiskLevel,
    val riskReason: String?,
    val suggestedAction: String?
)

data class ScreenContext(
    val visibleText: String,
    val appPackage: String?,
    val hasPasswordField: Boolean,
    val hasPaymentContext: Boolean,
    val interactiveElements: List<String>
)
