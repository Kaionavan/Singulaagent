package com.singula.agent.ai

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.delay
import com.singula.agent.service.SingulaAccessibilityService
import android.view.accessibility.AccessibilityNodeInfo

data class AgentStep(
    val action: String,
    val target: String = "",
    val text: String = "",
    val description: String = ""
)

class CommandExecutor(private val context: Context) {

    var onStatusUpdate: ((String) -> Unit)? = null

    suspend fun execute(steps: List<AgentStep>): String {
        val acc = SingulaAccessibilityService.instance
            ?: return "Служба управления отключена, сэр. Включите SINGULA Agent в Специальных возможностях."

        for (step in steps) {
            onStatusUpdate?.invoke(step.description.ifEmpty { step.action })

            when (step.action) {

                "open_app" -> {
                    launchApp(step.target)
                    delay(3000)
                }

                "wait" -> delay(step.text.toLongOrNull() ?: 2000L)

                // ══ НАЙТИ КОНТАКТ ══
                // Логика: открыть поиск → ввести имя → подождать → найти строку результата
                // ВАЖНО: результат находится НИЖЕ поля ввода в дереве View
                "find_contact" -> {
                    delay(1000)

                    // 1. Нажать иконку поиска
                    val searchBtn = acc.findNodeByContentDesc("Search")
                        ?: acc.findNodeByContentDesc("Поиск")
                        ?: acc.findNodeByContentDesc("search")
                        ?: acc.findNodeByText("поиск")
                    searchBtn?.let { acc.clickNode(it); delay(800) }

                    // 2. Ввести имя контакта
                    val field = waitForEditText(acc, 3000) ?: continue
                    acc.clickNode(field)
                    delay(300)
                    acc.typeText(field, step.text)
                    delay(2500) // ждём пока загрузятся результаты

                    // 3. Найти результат - берём все кликабельные узлы с именем,
                    //    пропускаем EditText и берём ВТОРОЙ найденный (первый = поле поиска)
                    val firstName = step.text.split(" ").first().lowercase()
                    val allMatches = acc.findAllContactResults(firstName)

                    val contactNode = when {
                        allMatches.size >= 2 -> allMatches[1] // второй = реальный контакт
                        allMatches.size == 1 -> allMatches[0] // если только один — берём его
                        else -> null
                    }

                    if (contactNode != null) {
                        acc.clickNode(contactNode)
                        delay(2000) // ждём открытия диалога
                    } else {
                        // Запасной вариант: тапнуть по координатам первого результата под полем
                        acc.tapBelowSearchField()
                        delay(2000)
                    }
                }

                "type_text" -> {
                    delay(800)
                    val node = waitForEditText(acc, 5000) ?: continue
                    acc.clickNode(node)
                    delay(400)
                    acc.typeText(node, step.text)
                    delay(400)
                }

                "search" -> {
                    delay(600)
                    var field = acc.findEditText()
                    if (field == null) {
                        val icon = acc.findNodeByContentDesc("Search")
                            ?: acc.findNodeByContentDesc("Поиск")
                            ?: acc.findNodeByText("поиск")
                        icon?.let { acc.clickNode(it); delay(800) }
                        field = waitForEditText(acc, 3000)
                    }
                    field?.let {
                        acc.clickNode(it); delay(400)
                        acc.typeText(it, step.text); delay(600)
                        val send = acc.findSendButton()
                        if (send != null) acc.clickNode(send) else acc.pressEnterKey()
                        delay(2000)
                    }
                }

                "send" -> {
                    delay(500)
                    val btn = acc.findSendButton()
                        ?: acc.findNodeByContentDesc("Send")
                        ?: acc.findNodeByContentDesc("Отправить")
                        ?: acc.findNodeByText("отправить")
                    if (btn != null) { acc.clickNode(btn); delay(600) }
                    else { acc.pressEnterKey(); delay(600) }
                }

                "click" -> {
                    delay(400)
                    when (step.target) {
                        "first_result" -> { delay(1500); acc.findFirstResult()?.let { acc.clickNode(it) } }
                        "back" -> acc.pressBack()
                        "home" -> acc.pressHome()
                        else -> {
                            val node = acc.findNodeByText(step.target)
                                ?: acc.findNodeByContentDesc(step.target)
                            node?.let { acc.clickNode(it) }
                        }
                    }
                    delay(500)
                }

                "scroll_down" -> { repeat(3) { acc.swipeDown(); delay(600) } }
                "scroll_up"   -> { repeat(3) { acc.swipeUp();  delay(600) } }
                "back"        -> { acc.pressBack();  delay(500) }
                "home"        -> { acc.pressHome();  delay(300) }

                "done"  -> return step.description.ifEmpty { "Готово, сэр." }
                "error" -> return "Не смог выполнить, сэр."
            }
        }
        return "Выполнено, сэр."
    }

    private suspend fun waitForEditText(acc: SingulaAccessibilityService, timeoutMs: Long): AccessibilityNodeInfo? {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            acc.findEditText()?.let { return it }
            delay(300)
        }
        return null
    }

    private fun launchApp(packageName: String): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (e: Exception) { false }
    }
}

// Найти ВСЕ узлы с именем (не только первый)
fun SingulaAccessibilityService.findAllContactResults(name: String): List<AccessibilityNodeInfo> {
    val root = rootInActiveWindow ?: return emptyList()
    val results = mutableListOf<AccessibilityNodeInfo>()
    collectContactNodes(root, name, results)
    return results
}

private fun collectContactNodes(
    node: AccessibilityNodeInfo,
    name: String,
    results: MutableList<AccessibilityNodeInfo>
) {
    val text = node.text?.toString()?.lowercase() ?: ""
    val desc = node.contentDescription?.toString()?.lowercase() ?: ""
    val isEdit = node.className?.toString()?.contains("EditText") == true
    if (!isEdit && node.isClickable && (text.contains(name) || desc.contains(name))) {
        results.add(node)
    }
    for (i in 0 until node.childCount) {
        collectContactNodes(node.getChild(i) ?: continue, name, results)
    }
}

// Запасной вариант: тапнуть под полем поиска (там обычно первый результат)
fun SingulaAccessibilityService.tapBelowSearchField() {
    val root = rootInActiveWindow ?: return
    val editText = findEditNode(root) ?: return
    val rect = android.graphics.Rect()
    editText.getBoundsInScreen(rect)
    // Тапаем на 200px ниже поля поиска — там первый результат
    tap(rect.exactCenterX(), (rect.bottom + 150).toFloat())
}

private fun findEditNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
    if (node.className?.toString()?.contains("EditText") == true) return node
    for (i in 0 until node.childCount) {
        findEditNode(node.getChild(i) ?: continue)?.let { return it }
    }
    return null
}

fun SingulaAccessibilityService.findFirstResult(): AccessibilityNodeInfo? {
    val root = rootInActiveWindow ?: return null
    return findFirstClickable(root, 0)
}

private fun findFirstClickable(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
    if (depth > 8) return null
    if (node.isClickable && node.childCount > 0) return node
    for (i in 0 until node.childCount) {
        findFirstClickable(node.getChild(i) ?: continue, depth + 1)?.let { return it }
    }
    return null
}
