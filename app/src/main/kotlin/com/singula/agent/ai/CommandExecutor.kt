package com.singula.agent.ai

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
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

        for (step in steps) {
            onStatusUpdate?.invoke(step.description.ifEmpty { step.action })

            when (step.action) {
                "open_app" -> {
                    launchApp(step.target)
                    delay(2000)
                }
                "type_text" -> {
                    delay(600)
                    val node = acc?.findEditText()
                    if (node != null) {
                        acc.typeText(node, step.text)
                        delay(400)
                    }
                }
                "search" -> {
                    delay(600)
                    val field = acc?.findEditText()
                    if (field != null) {
                        acc.clickNode(field)
                        delay(400)
                        acc.typeText(field, step.text)
                        delay(500)
                        val send = acc.findSendButton()
                        if (send != null) acc.clickNode(send)
                        delay(1500)
                    }
                }
                "click" -> {
                    delay(400)
                    when (step.target) {
                        "first_result" -> {
                            delay(1500)
                            val node = acc?.findFirstResult()
                            if (node != null) acc?.clickNode(node)
                        }
                        "back" -> acc?.pressBack()
                        else -> {
                            val node = acc?.findNodeByText(step.target)
                            if (node != null) acc.clickNode(node)
                        }
                    }
                    delay(500)
                }
                "send" -> {
                    delay(300)
                    val btn = acc?.findSendButton()
                    if (btn != null) acc.clickNode(btn)
                    delay(500)
                }
                "back" -> {
                    acc?.pressBack()
                    delay(500)
                }
                "wait" -> delay(2000)
                "done" -> return step.description.ifEmpty { "Готово, сэр." }
                "error" -> return "Не смог выполнить, сэр."
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
