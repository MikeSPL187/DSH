package com.dsh.core.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActionScope
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation

/** Secret values never enter the accessibility tree, including while visually revealed. */
@Composable
fun ProtectedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    secret: Boolean = true,
    revealed: Boolean = false,
    label: String = "",
    singleLine: Boolean = true,
    textStyle: TextStyle = TextStyle.Default,
    cursorBrush: SolidColor = SolidColor(Color.Black),
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    var field by remember { mutableStateOf(TextFieldValue(value)) }
    // Match BasicTextField(String)'s bridge in Compose 1.7.8: never discard IME composition
    // and compare callbacks with the last emitted text, rather than a captured parent value.
    val current = field.copy(text = value)
    SideEffect {
        if (current.selection != field.selection || current.composition != field.composition) field = current
    }
    var lastText by remember(value) { mutableStateOf(value) }
    fun editableValue(): TextFieldValue = if (field.text == lastText) field else current
    val focus = remember { FocusRequester() }
    var hasFocus by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val actionScope = object : KeyboardActionScope {
        override fun defaultKeyboardAction(imeAction: ImeAction) {
            when (imeAction) {
                ImeAction.Done -> keyboard?.hide()
                ImeAction.Next -> focusManager.moveFocus(FocusDirection.Next)
                ImeAction.Previous -> focusManager.moveFocus(FocusDirection.Previous)
            }
        }
    }
    val protected = if (!secret) Modifier else Modifier.clearAndSetSemantics {
        password()
        isEditable = true
        contentDescription = label
        editableText = AnnotatedString("•".repeat(value.length))
        textSelectionRange = current.selection
        focused = hasFocus
        setText { field = TextFieldValue(it.text, TextRange(it.length)); lastText = it.text; onValueChange(it.text); true }
        insertTextAtCursor {
            val base = editableValue()
            val next = base.text.replaceRange(base.selection.min, base.selection.max, it.text)
            field = TextFieldValue(next, TextRange(base.selection.min + it.length))
            lastText = next
            onValueChange(next)
            true
        }
        setSelection { start, end, _ ->
            val base = editableValue()
            field = base.copy(selection = TextRange(start.coerceIn(0, base.text.length), end.coerceIn(0, base.text.length)))
            lastText = base.text
            true
        }
        onImeAction(keyboardOptions.imeAction) {
            field = editableValue().copy(composition = null)
            val callback = when (keyboardOptions.imeAction) {
                ImeAction.Done -> keyboardActions.onDone
                ImeAction.Go -> keyboardActions.onGo
                ImeAction.Next -> keyboardActions.onNext
                ImeAction.Previous -> keyboardActions.onPrevious
                ImeAction.Search -> keyboardActions.onSearch
                ImeAction.Send -> keyboardActions.onSend
                else -> null
            }
            if (callback != null) callback.invoke(actionScope) else actionScope.defaultKeyboardAction(keyboardOptions.imeAction)
            true
        }
        requestFocus { focus.requestFocus(); true }
        onClick { focus.requestFocus(); keyboard?.show(); true }
    }
    BasicTextField(
        current, {
            field = it
            val changed = lastText != it.text
            lastText = it.text
            if (changed) onValueChange(it.text)
        },
        modifier.then(protected).focusRequester(focus).onFocusChanged { hasFocus = it.isFocused; if (!it.isFocused) field = field.copy(composition = null) },
        singleLine = singleLine,
        textStyle = if (secret && revealed) textStyle.copy(color = Color.Transparent) else textStyle,
        cursorBrush = cursorBrush,
        // Always retain password input behavior (no copy/cut), even when the visual layer is revealed.
        visualTransformation = if (secret) PasswordVisualTransformation('•') else VisualTransformation.None,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        decorationBox = { inner ->
            Box {
                if (secret && revealed) Text(value, style = textStyle, maxLines = 1, modifier = Modifier.clearAndSetSemantics {})
                inner()
            }
        },
    )
}
