package io.github.kobi2187.readaloud.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow

@Composable
fun <T> StateFlow<T>.collectAsStateCompat(): State<T> = collectAsStateWithLifecycle()

/** Lays out post text by its own script, so Hebrew is right-aligned even in the English UI. */
fun TextStyle.byContent(): TextStyle = copy(textDirection = TextDirection.Content, textAlign = TextAlign.Start)
