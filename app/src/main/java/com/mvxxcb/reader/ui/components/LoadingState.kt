package com.mvxxcb.reader.ui.components

// WP-U（04 §4）：加载态组件。

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.mvxxcb.reader.ui.theme.spacing

/** 居中圆形加载态 */
@Composable
fun LoadingState(
    modifier: Modifier = Modifier,
    text: String? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(MaterialTheme.spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md),
    ) {
        CircularProgressIndicator()
        if (text != null) {
            Text(text = text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** 行内/顶部线性进度（导入进度等） */
@Composable
fun InlineLoadingBar(
    modifier: Modifier = Modifier,
    text: String? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = MaterialTheme.spacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
    ) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        if (text != null) {
            Text(text = text, style = MaterialTheme.typography.bodySmall)
        }
    }
}
