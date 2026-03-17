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

        if (acc == null) {
            return "Служба управления отключена, сэр. Включите SINGULA Agent в Специальных возможностях."
        }

        for (step in steps) {
            onStatusUpdate?.invoke(step.description.ifEmpty { step.action })

            when (step.action) {

                "open_app" -> {
                    launchApp(step.target)
                    delay(2500)
                }

                "wait" -> {
                    val ms = step.text.toLongOrNull() ?: 2000L
                    delay(ms)
                }

                // ══ НАЙТИ КОНТАКТ в Telegram/WhatsApp ══
                "find_contact" -> {
                    delay(1000)

                    // Нажать кнопку поиска
                    val searchBtn = acc.findNodeByContentDesc("Search")
                        ?: acc.findNodeByContentDesc("Поиск")
                        ?: acc.findNodeByText("поиск")
                        ?: acc.findNodeByContentDesc("search")
                    if (searchBtn != null) {
                        acc.clickNode(searchBtn)
                        delay(800)
                    }

                    // Ввести имя
                    val searchField = waitForEditText(acc, 3000)
                    if (searchField == null) {
                        onStatusUpdate?.invoke("Не нашёл поле поиска")
                        continue
                    }
                    acc.clickNode(searchField)
                    delay(300)
                    acc.typeText(searchField, step.text)
                    delay(2000)

                    // Нажать на контакт в результатах (не на EditText!)
                    val firstName = step.text.split(" ").first()
                    val contactNode = acc.findContactResult(firstName)
                    if (contactNode != null) {
                        acc.clickNode(contactNode)
                        delay(1500)
                    } else {
                        onStatusUpdate?.invoke("Контакт не найден: ${step.text}")
                    }
                }

                // ══ ВВЕСТИ ТЕКСТ ══
                "type_text" -> {
                    delay(800)
                    val node = waitForEditText(acc, 5000)
                    if (node != null) {
                        acc.clickNode(node)
                        delay(400)
                        acc.typeText(node, step.text)
                        delay(400)
                    }
                }

                // ══ ПОИСК ══
                "search" -> {
                    delay(600)
                    var field = acc.findEditText()
                    if (field == null) {
                        val searchIcon = acc.findNodeByContentDesc("Search")
                            ?: acc.findNodeByContentDesc("Поиск")
                            ?: acc.findNodeByText("поиск")
                        if (searchIcon != null) {
                            acc.clickNode(searchIcon)
                            delay(800)
                        }
                        field = waitForEditText(acc, 3000)
                    }
                    if (field != null) {
                        acc.clickNode(field)
                        delay(400)
                        acc.typeText(field, step.text)
                        delay(600)
                        val send = acc.findSendButton()
                        if (send != null) acc.clickNode(send)
                        else acc.pressEnterKey()
                        delay(2000)
                    }
                }

                // ══ ОТПРАВИТЬ ══
                "send" -> {
                    delay(500)
                    val btn = acc.findSendButton()
                        ?: acc.findNodeByContentDesc("Send")
                        ?: acc.findNodeByContentDesc("Отправить")
                        ?: acc.findNodeByText("отправить")
                    if (btn != null) {
                        acc.clickNode(btn)
                        delay(600)
                    } else {
                        acc.pressEnterKey()
                        delay(600)
                    }
                }

                // ══ НАЖАТЬ ══
                "click" -> {
                    delay(400)
                    when (step.target) {
                        "first_result" -> {
                            delay(1500)
                            val node = acc.findFirstResult()
                            if (node != null) acc.clickNode(node)
                        }
                        "back" -> acc.pressBack()
                        "home" -> acc.pressHome()
                        else -> {
                            val node = acc.findNodeByText(step.target)
                                ?: acc.findNodeByContentDesc(step.target)
                            if (node != null) acc.clickNode(node)
                        }
                    }
                    delay(500)
                }

                "scroll_down" -> { acc.swipeDown(); delay(500) }
                "scroll_up"   -> { acc.swipeUp();   delay(500) }
                "back"        -> { acc.pressBack();  delay(500) }
                "home"        -> { acc.pressHome();  delay(300) }

                "done"  -> return step.description.ifEmpty { "Готово, сэр." }
                "error" -> return "Не смог выполнить, сэр."
            }
        }
        return "Выполнено, сэр."
    }

    private suspend fun waitForEditText(
        acc: SingulaAccessibilityService,
        timeoutMs: Long
    ): AccessibilityNodeInfo? {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val node = acc.findEditText()
            if (node != null) return node
            delay(300)
        }
        return null
    }

    private fun launchApp(packageName: String): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            } else {
                val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName"))
                market.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(market)
                false
            }
        } catch (e: Exception) { false }
    }
}

fun SingulaAccessibilityService.findContactResult(name: String): AccessibilityNodeInfo? {
    val root = rootInActiveWindow ?: return null
    return findContactNode(root, name.lowercase())
}

private fun findContactNode(node: AccessibilityNodeInfo, name: String): AccessibilityNodeInfo? {
    val text = node.text?.toString()?.lowercase() ?: ""
    val desc = node.contentDescription?.toString()?.lowercase() ?: ""
    val isEditText = node.className?.toString()?.contains("EditText") == true
    if (!isEditText && node.isClickable && (text.contains(name) || desc.contains(name))) {
        return node
    }
    for (i in 0 until node.childCount) {
        val child = node.getChild(i) ?: continue
        val found = findContactNode(child, name)
        if (found != null) return found
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
        val child = node.getChild(i) ?: continue
        val found = findFirstClickable(child, depth + 1)
        if (found != null) return found
    }
    return null
}
