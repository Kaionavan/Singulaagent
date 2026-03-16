package com.singula.agent.ai

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import kotlinx.coroutines.delay
import com.singula.agent.service.SingulaAccessibilityService

class CommandExecutor(private val context: Context) {

    var onStatusUpdate: ((String) -> Unit)? = null

    suspend fun execute(steps: List<AgentStep>): String {
        val acc = SingulaAccessibilityService.instance

        for (step in steps) {
            onStatusUpdate?.invoke(step.description.ifEmpty { step.action })

            when (step.action) {

                "open_app" -> {
                    val launched = launchApp(step.target)
                    if (!launched) {
                        // Пробуем через браузер
                        openUrl("https://play.google.com/store/apps/details?id=${step.target}")
                    }
                    delay(2000) // Ждём загрузки
                }

                "type_text" -> {
                    delay(500)
                    val node = acc?.findEditText()
                    if (node != null) {
                        acc.typeText(node, step.text)
                        delay(300)
                    } else {
                        // Пробуем найти любое поле ввода по тексту
                        val searchNode = acc?.findNodeByText("поиск")
                            ?: acc?.findNodeByText("search")
                            ?: acc?.findNodeByText("введите")
                        if (searchNode != null) {
                            acc?.clickNode(searchNode)
                            delay(300)
                            val editNode = acc?.findEditText()
                            if (editNode != null) acc?.typeText(editNode, step.text)
                        }
                    }
                }

                "search" -> {
                    delay(500)
                    // Ищем поисковое поле
                    var searchField = acc?.findNodeByText("search")
                        ?: acc?.findNodeByText("поиск")
                        ?: acc?.findNodeByText("искать")
                        ?: acc?.findEditText()

                    if (searchField != null) {
                        acc?.clickNode(searchField)
                        delay(400)
                        searchField = acc?.findEditText() ?: searchField
                        acc?.typeText(searchField!!, step.text)
                        delay(300)
                        // Нажимаем Enter/Search
                        val sendBtn = acc?.findSendButton()
                        if (sendBtn != null) acc?.clickNode(sendBtn)
                        delay(1500)
                    }
                }

                "click" -> {
                    delay(300)
                    when (step.target) {
                        "first_result" -> {
                            // Нажать на первый результат поиска
                            delay(1000)
                            val node = acc?.findFirstVideoResult()
                            if (node != null) acc?.clickNode(node)
                        }
                        "back" -> acc?.pressBack()
                        else -> {
                            val node = acc?.findNodeByText(step.target)
                            if (node != null) {
                                acc.clickNode(node)
                            }
                        }
                    }
                    delay(500)
                }

                "send" -> {
                    delay(300)
                    val sendBtn = acc?.findSendButton()
                    if (sendBtn != null) {
                        acc.clickNode(sendBtn)
                    } else {
                        // Пробуем найти кнопку по иконке
                        val node = acc?.findNodeByText("send")
                            ?: acc?.findNodeByText("отправить")
                        if (node != null) acc?.clickNode(node)
                    }
                    delay(500)
                }

                "back" -> {
                    acc?.pressBack()
                    delay(500)
                }

                "wait" -> {
                    delay(2000)
                }

                "open_url" -> {
                    openUrl(step.target)
                    delay(2000)
                }

                "alarm" -> {
                    setAlarm(step.text)
                }

                "done" -> {
                    return step.description.ifEmpty { "Готово, сэр." }
                }

                "error" -> {
                    return "Не смог выполнить: ${step.description}"
                }
            }
        }
        return "Выполнено, сэр."
    }

    private fun launchApp(packageName: String): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            } else false
        } catch (e: Exception) { false }
    }

    private fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {}
    }

    private fun setAlarm(timeStr: String) {
        try {
            val parts = timeStr.split(":")
            val hour = parts[0].trim().toIntOrNull() ?: return
            val minute = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {}
    }
}

// Расширение для поиска первого результата видео
fun SingulaAccessibilityService.findFirstVideoResult(): android.view.accessibility.AccessibilityNodeInfo? {
    val root = rootInActiveWindow ?: return null
    // YouTube результаты обычно в RecyclerView
    return findFirstClickableItem(root, depth = 0)
}

private fun findFirstClickableItem(
    node: android.view.accessibility.AccessibilityNodeInfo,
    depth: Int
): android.view.accessibility.AccessibilityNodeInfo? {
    if (depth > 10) return null
    if (node.isClickable && node.childCount > 0) return node
    for (i in 0 until node.childCount) {
        val child = node.getChild(i) ?: continue
        val found = findFirstClickableItem(child, depth + 1)
        if (found != null) return found
    }
    return null
}
