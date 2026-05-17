package com.sahayak

// Severity of threat detected on screen; drives overlay colour in Sentinel UI.
enum class RiskLevel { NONE, LOW, HIGH }

// Parsed output from LocalGemmaEngine.analyzeScreenContext().
data class ScreenAnalysisResult(
    val explanation: String,
    val riskLevel: RiskLevel,
    val riskReason: String?,
    val suggestedAction: String?
)

// Input assembled by the Accessibility Service from the current window's view hierarchy.
data class ScreenContext(
    val visibleText: String,
    val appPackage: String?,
    val hasPasswordField: Boolean,
    val hasPaymentContext: Boolean,
    val interactiveElements: List<String>
)
