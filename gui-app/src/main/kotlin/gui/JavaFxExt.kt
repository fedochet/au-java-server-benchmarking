package gui

import javafx.beans.value.ObservableValue
import javafx.scene.control.TextField

internal val TextField.textAsInt get() = text.toInt()

internal fun <T> ObservableValue<T>.onChange(action: () -> Unit) {
    addListener { _, _, _ -> action() }
}

internal fun <T> ObservableValue<T>.onChange(action: (T) -> Unit) {
    addListener { _, _, value -> action(value) }
}
