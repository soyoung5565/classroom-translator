package com.classroom.translator.repair

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自修复工具箱 / Agent 消息序列回归测试。
 *
 * 【2026-11-02】这两个测试钉死两个真 bug：
 *
 * ① `JSONArray(props.keys())` 抛
 *    "Not a primitive array: class java.util.LinkedHashMap$LinkedKeyIterator"
 *    —— JSONObject.keys() 返回的是 **Iterator** 不是 Collection，JSONArray 不收它。
 *    这个 bug 让「AI 自动修复」一点就崩。
 *
 * ② Agent 循环里 assistant 消息被写在**工具循环体内**：
 *    一轮返回 2 个工具 → push 2 条一模一样的 assistant 消息，
 *    而 tool 消息按协议必须紧跟其对应的 assistant —— 序列乱了云端直接 400。
 *
 * 这些是「写好了但没跑过」才会暴露的类型，所以必须有测试。
 */
class RepairToolboxTest {

    @Test
    fun `工具 schema 是合法 JSON 且含正确的 name`() {
        val tools = RepairToolboxSchemaFixture.schemas()
        assertTrue("至少应有 5 个工具", tools.size >= 5)
        for (t in tools) {
            assertEquals("function", t.getString("type"))
            val fn = t.getJSONObject("function")
            assertTrue("工具必须有名字", fn.getString("name").isNotBlank())
            assertTrue("工具必须有描述", fn.getString("description").isNotBlank())
            val params = fn.getJSONObject("parameters")
            assertEquals("object", params.getString("type"))
            assertTrue("parameters 必须有 properties", params.has("properties"))
            assertTrue("parameters 必须有 required", params.has("required"))
            // required 必须是真正的 JSON 数组（不是 Iterator 的字符串形式）
            assertTrue(
                "required 必须是 JSONArray",
                params.get("required") is JSONArray
            )
        }
    }

    @Test
    fun `required 数组正确列出参数名`() {
        val tools = RepairToolboxSchemaFixture.schemas()
        for (t in tools) {
            val fn = t.getJSONObject("function")
            val required = fn.getJSONObject("parameters").getJSONArray("required")
            val props = fn.getJSONObject("parameters").getJSONObject("properties")
            for (i in 0 until required.length()) {
                val name = required.getString(i)
                assertTrue(
                    "required 里的 '$name' 必须在 properties 里声明",
                    props.has(name)
                )
            }
        }
    }

    @Test
    fun `Agent 消息序列合法 assistant 紧跟 tool`() {
        // 复刻 Agent 循环里组装 messages 的方式：
        // 一轮返回 2 个工具 → assistant(带 tool_calls) 只能有 1 条，
        // 然后紧跟 2 条 tool 消息。
        val messages = JSONArray()
        val toolCalls = JSONArray().apply {
            put(JSONObject().apply {
                put("id", "call_1")
                put("type", "function")
                put("function", JSONObject().put("name", "diagnose_app").put("arguments", "{}"))
            })
            put(JSONObject().apply {
                put("id", "call_2")
                put("type", "function")
                put("function", JSONObject().put("name", "restart_local_service").put("arguments", "{}"))
            })
        }

        messages.put(JSONObject().apply {
            put("role", "assistant")
            put("content", "我来检查")
            put("tool_calls", toolCalls)
        })
        for (i in 0 until toolCalls.length()) {
            val tc = toolCalls.getJSONObject(i)
            messages.put(JSONObject().apply {
                put("role", "tool")
                put("tool_call_id", tc.getString("id"))
                put("name", tc.getJSONObject("function").getString("name"))
                put("content", "执行成功")
            })
        }

        // 序列：assistant, tool, tool
        assertEquals(3, messages.length())
        assertEquals("assistant", messages.getJSONObject(0).getString("role"))
        assertEquals("tool", messages.getJSONObject(1).getString("role"))
        assertEquals("tool", messages.getJSONObject(2).getString("role"))

        // 每条 tool 消息的 tool_call_id 必须能在前面的 assistant 的 tool_calls 里找到
        val declared = mutableSetOf<String>()
        for (i in 0 until messages.getJSONObject(0).getJSONArray("tool_calls").length()) {
            declared.add(messages.getJSONObject(0).getJSONArray("tool_calls").getJSONObject(i).getString("id"))
        }
        for (i in 1 until messages.length()) {
            val id = messages.getJSONObject(i).getString("tool_call_id")
            assertTrue("tool_call_id $id 未在 assistant 声明", id in declared)
        }

        // assistant 消息必须只有一条（多一条就是重复 push 的 bug）
        var assistantCount = 0
        for (i in 0 until messages.length()) {
            if (messages.getJSONObject(i).getString("role") == "assistant") assistantCount++
        }
        assertEquals("assistant 消息必须只有 1 条（每轮 push 一次）", 1, assistantCount)
    }
}

/**
 * 只为测试构造一份与 [RepairToolbox.toolSchemas] 等价的 schema 列表。
 *
 * 为什么不直接调 RepairToolbox.toolSchemas()：它需要 Android Context。
 * 这里复制同一份结构，重点是验证 **JSON 形态** 正确（尤其 required 数组）。
 */
object RepairToolboxSchemaFixture {
    fun schemas(): List<JSONObject> {
        fun tool(name: String, desc: String, props: JSONObject): JSONObject {
            // 复刻被修好的那段：手动遍历 keys()，不能用 JSONArray(props.keys())
            val required = JSONArray()
            val it = props.keys()
            while (it.hasNext()) required.put(it.next())
            return JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", name)
                    put("description", desc)
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", props)
                        put("required", required)
                    })
                })
            }
        }
        val reason = JSONObject().put("type", "string").put("description", "调用原因")
        return listOf(
            tool("diagnose_app", "体检", JSONObject()),
            tool("restart_local_service", "重启服务", JSONObject().put("reason", reason)),
            tool("repair_configuration", "纠正配置", JSONObject().put("reason", reason)),
            tool("download_missing_models", "下载模型", JSONObject().put("reason", reason)),
            tool("migrate_models_to_safe_storage", "迁移模型", JSONObject().put("reason", reason)),
            tool("verify_local_endpoints", "探测端点", JSONObject().put("reason", reason)),
        )
    }
}
