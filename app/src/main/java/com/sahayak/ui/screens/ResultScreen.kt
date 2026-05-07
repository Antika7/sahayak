package com.sahayak.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.Markwon

@Composable
fun ResultScreen(
    resultText: String,
    onScanAnother: () -> Unit,
    onGoHome: () -> Unit
) {
    val context = LocalContext.current
    val markwon = remember { Markwon.create(context) }

    // Heuristic: if the AI result mentions danger/scam words treat as warning
    val isWarning = remember(resultText) {
        val lower = resultText.lowercase()
        listOf("scam", "fraud", "suspicious", "warning", "caution", "danger", "fake").any { lower.contains(it) }
    }

    val bannerColor = if (isWarning) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary
    val onSurfaceArgb = MaterialTheme.colorScheme.onSurface.toArgb()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 52.dp, start = 16.dp, end = 16.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onGoHome) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onSurface)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text("Result", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        }

        // Status banner
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .background(bannerColor.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isWarning) Icons.Default.Warning else Icons.Default.CheckCircle,
                contentDescription = null,
                tint = bannerColor,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = if (isWarning) "Check this carefully" else "Here's what I found",
                style = MaterialTheme.typography.titleMedium,
                color = bannerColor
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Scrollable result card
        Card(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
            ) {
                AndroidView(
                    factory = { ctx ->
                        android.widget.TextView(ctx).apply {
                            textSize = 18f
                            setTextColor(onSurfaceArgb)
                            setLineSpacing(0f, 1.5f)
                        }
                    },
                    update = { tv -> markwon.setMarkdown(tv, resultText) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Action buttons
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = onScanAnother,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("📷  Scan Another Form", style = MaterialTheme.typography.labelLarge)
            }

            OutlinedButton(
                onClick = onGoHome,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Go to Home", style = MaterialTheme.typography.labelLarge)
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}
