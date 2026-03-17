package com.singula.agent.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*

class SingulaAccessibilityService : AccessibilityService() {

    companion object {
        var instance: SingulaAccessibilityService? = null
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onServiceConnected() {
        instance = this
        SingulaForegroundService.sendStatus("ACCESSIBILITY_READY")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        scope.cancel()
        super.onDestroy()
    }

    // ══ НАЙТИ УЗЕЛ ПО ТЕКСТУ ══
    fun findNodeByText(text: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return findNode(root, text.lowercase())
    }

    private fun findNode(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val nodeText = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val hint = node.hintText?.toString()?.lowercase() ?: ""
        if (nodeText.contains(text) || desc.contains(text) || hint.contains(text)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNode(child, text)
            if (found != null) return found
        }
        return null
    }

    // ══ НАЙТИ УЗЕЛ ПО CONTENT DESCRIPTION ══
    fun findNodeByContentDesc(desc: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return findNodeDesc(root, desc.lowercase())
    }

    private fun findNodeDesc(node: AccessibilityNodeInfo, desc: String): AccessibilityNodeInfo? {
        val d = node.contentDescription?.toString()?.lowercase() ?: ""
        if (d.contains(desc)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeDesc(child, desc)
            if (found != null) return found
        }
        return null
    }

    // ══ НАЙТИ ПОЛЕ ВВОДА ══
    fun findEditText(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return findEditNode(root)
    }

    private fun findEditNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.className?.toString()?.contains("EditText") == true) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditNode(child)
            if (found != null) return found
        }
        return null
    }

    // ══ НАЙТИ КНОПКУ ОТПРАВКИ ══
    fun findSendButton(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        // Ключевые слова для Telegram, WhatsApp, и других мессенджеров
        val keywords = listOf(
            "send", "отправить", "отправ",
            "submit", "search", "поиск", "go",
            "done", "готово"
        )
        for (kw in keywords) {
            val node = findNode(root, kw)
            if (node != null && (node.isClickable || node.className?.toString()?.contains("Button") == true ||
                node.className?.toString()?.contains("ImageView") == true)) {
                return node
            }
        }
        // Ищем по contentDescription
        val descKeywords = listOf("Send", "Отправить", "Search", "Поиск", "Done")
        for (kw in descKeywords) {
            val node = findNodeByContentDesc(kw)
            if (node != null && node.isClickable) return node
        }
        return findClickableButton(root)
    }

    private fun findClickableButton(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isClickable && (
            node.className?.toString()?.contains("Button") == true ||
            node.className?.toString()?.contains("ImageView") == true ||
            node.className?.toString()?.contains("ImageButton") == true
        )) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findClickableButton(child)
            if (found != null) return found
        }
        return null
    }

    // ══ НАЖАТЬ НА УЗЕЛ ══
    fun clickNode(node: AccessibilityNodeInfo): Boolean {
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    // ══ ВВЕСТИ ТЕКСТ В УЗЕЛ ══
    fun typeText(node: AccessibilityNodeInfo, text: String): Boolean {
        node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    // ══ НАЖАТЬ ENTER (отправка сообщения) ══
    fun pressEnterKey() {
        performGlobalAction(GLOBAL_ACTION_KEYCODE_HEADSETHOOK)
        // Альтернативный метод через жест
        val root = rootInActiveWindow ?: return
        val editText = findEditNode(root)
        if (editText != null) {
            val args = Bundle()
            args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT,
                AccessibilityNodeInfo.MOVEMENT_GRANULARITY_PARAGRAPH)
            editText.performAction(AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY, args)
        }
    }

    // ══ НАЖАТЬ ENTER (старый метод) ══
    fun pressEnter(node: AccessibilityNodeInfo): Boolean {
        val args = Bundle()
        args.putInt(
            AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT,
            AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE
        )
        return node.performAction(AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY, args)
    }

    // ══ ТАПНУТЬ ПО КООРДИНАТАМ ══
    fun tap(x: Float, y: Float) {
        val path = Path()
        path.moveTo(x, y)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        dispatchGesture(gesture, null, null)
    }

    // ══ ПОЛУЧИТЬ ЦЕНТР УЗЛА ══
    fun getNodeCenter(node: AccessibilityNodeInfo): Pair<Float, Float> {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        return Pair(rect.exactCenterX(), rect.exactCenterY())
    }

    // ══ СВАЙП ВНИЗ ══
    fun swipeDown() {
        val path = Path()
        path.moveTo(540f, 800f)
        path.lineTo(540f, 200f)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 400))
            .build()
        dispatchGesture(gesture, null, null)
    }

    // ══ СВАЙП ВВЕРХ ══
    fun swipeUp() {
        val path = Path()
        path.moveTo(540f, 200f)
        path.lineTo(540f, 800f)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 400))
            .build()
        dispatchGesture(gesture, null, null)
    }

    // ══ КНОПКА НАЗАД ══
    fun pressBack() {
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    // ══ ДОМОЙ ══
    fun pressHome() {
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    // ══ СКРИНШОТ ══
    fun takeScreenshot(callback: TakeScreenshotCallback) {
        takeScreenshot(0, mainExecutor, callback)
    }
}
