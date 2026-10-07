package com.aliahad.ainewshot

import org.json.JSONArray
import org.json.JSONObject

/**
 * Decodes React Router's "turbo-stream" page data (what aihot.news pages like /all.data return) into plain JSON.
 * The payload is one flat array; objects map "_<index of key string>" to the index of their value, arrays hold
 * indices, negative numbers are null/undefined, and ["D", ms] style entries are typed values we pass through.
 */
object TurboStream {
    fun decode(text: String): Any? {
        val arr = JSONArray(text.lineSequence().firstOrNull() ?: return null)
        val memo = HashMap<Int, Any>()
        fun dec(ref: Any?): Any? {
            val i = (ref as? Int) ?: return ref
            if (i < 0) return null
            memo[i]?.let { return it }
            return when (val v = arr.get(i)) {
                is JSONObject -> JSONObject().also { o ->
                    memo[i] = o
                    for (k in v.keys()) {
                        val name = k.removePrefix("_").toIntOrNull()?.let { arr.optString(it) } ?: k
                        o.put(name, dec(v.get(k)) ?: JSONObject.NULL)
                    }
                }
                is JSONArray -> if (v.length() > 0 && v.opt(0) is String) v else JSONArray().also { a ->
                    memo[i] = a
                    for (j in 0 until v.length()) a.put(dec(v.get(j)) ?: JSONObject.NULL)
                }
                else -> v
            }
        }
        return dec(0)
    }
}
