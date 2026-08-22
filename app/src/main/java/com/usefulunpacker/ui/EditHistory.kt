package com.usefulunpacker

class EditHistory(private val maxSteps: Int = 50) {
    private val history = mutableListOf<String>()
    private var currentIndex = -1
    private var onChange: (() -> Unit)? = null

    fun setOnChangeListener(listener: () -> Unit) {
        onChange = listener
    }

    fun pushState(state: String) {
        // 移除当前位置之后的历史（分支）
        if (currentIndex < history.size - 1) {
            history.subList(currentIndex + 1, history.size).clear()
        }
        // 限制历史大小
        if (history.size >= maxSteps) {
            history.removeAt(0)
            currentIndex--
        }
        history.add(state)
        currentIndex = history.size - 1
        onChange?.invoke()
    }

    fun undo(): String? {
        if (currentIndex > 0) {
            currentIndex--
            onChange?.invoke()
            return history[currentIndex]
        }
        return null
    }

    fun redo(): String? {
        if (currentIndex < history.size - 1) {
            currentIndex++
            onChange?.invoke()
            return history[currentIndex]
        }
        return null
    }

    fun canUndo() = currentIndex > 0
    fun canRedo() = currentIndex < history.size - 1
    fun getCurrentState(): String? = if (currentIndex in history.indices) history[currentIndex] else null
}
