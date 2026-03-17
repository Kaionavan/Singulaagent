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
                    delay(2500) // Ждём загрузки приложения
                }

                "wait" -> {
                    val ms = step.text.toLongOrNull() ?: 2000L
                    delay(ms)
                }

                // Найти контакт/диалог в Telegram по имени
                "find_contact" -> {
                    delay(800)
                    // Нажимаем кнопку поиска (иконка лупы)
                    var searchBtn = acc.findNodeByText("поиск")
                        ?: acc.findNodeByText("search")
                        ?: acc.findNodeByContentDesc("Search")
                        ?: acc.findNodeByContentDesc("Поиск")
                    if (searchBtn != null) {
                        acc.clickNode(searchBtn)
                        delay(600)
                    }
                    // Вводим имя в поле поиска
                    val field = waitForEditText(acc, 3000)
                    if (field != null) {
                        acc.typeText(field, step.text)
                        delay(1500) // Ждём результатов поиска
                        // Кликаем на первый результат
                        val result = acc.findNodeByText(step.text.split(" ").first())
                        if (result != null) {
                            acc.clickNode(result)
                            delay(1000)
                        }
                    }
                }

                // Ввести текст в поле ввода (сообщение)
                "type_text" -> {
                    delay(600)
                    val node = waitForEditText(acc, 4000)
                    if (node != null) {
                        acc.clickNode(node)
                        delay(300)
                        acc.typeText(node, step.text)
                        delay(400)
                    }
                }

                // Поиск (YouTube, Google и т.д.)
                "search" -> {
                    delay(600)
                    // Сначала ищем кнопку поиска если поле не видно
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
                        // Нажимаем кнопку поиска/Enter
                        val send = acc.findSendButton()
                        if (send != null) acc.clickNode(send)
                        else acc.pressEnterKey()
                        delay(2000)
                    }
                }

                // Отправить сообщение
                "send" -> {
                    delay(400)
                    // Ищем кнопку отправки по всем вариантам
                    val btn = acc.findSendButton()
                        ?: acc.findNodeByContentDesc("Send")
                        ?: acc.findNodeByContentDesc("Отправить")
                        ?: acc.findNodeByText("отправить")
                    if (btn != null) {
                        acc.clickNode(btn)
                        delay(600)
                    } else {
                        // Пробуем Enter
                        acc.pressEnterKey()
                        delay(600)
                    }
                }

                // Нажать на элемент по тексту
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

                // Прокрутка
                "scroll_down" -> {
                    acc.swipeDown()
                    delay(500)
                }

                "scroll_up" -> {
                    acc.swipeUp()
                    delay(500)
                }

                "back" -> {
                    acc.pressBack()
                    delay(500)
                }

                "home" -> {
                    acc.pressHome()
                    delay(300)
                }

                "done" -> return step.description.ifEmpty { "Готово, сэр." }
                "error" -> return "Не смог выполнить, сэр."
            }
        }
        return "Выполнено, сэр."
    }

    // Ждём появления поля ввода (с таймаутом)
    private suspend fun waitForEditText(acc: SingulaAccessibilityService, timeoutMs: Long): AccessibilityNodeInfo? {
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
                val market = Intent(Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=$packageName"))
                market.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(market)
                false
            }
        } catch (e: Exception) { false }
    }
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
