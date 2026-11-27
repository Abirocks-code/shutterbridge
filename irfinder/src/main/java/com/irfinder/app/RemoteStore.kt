package com.irfinder.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Saved(val name: String, val code: IrCode)

/** Keeps the buttons you saved on the phone itself (nothing leaves the device). */
class RemoteStore(ctx: Context) {

    private val sp = ctx.getSharedPreferences("remote", Context.MODE_PRIVATE)

    fun load(): MutableList<Saved> {
        val out = ArrayList<Saved>()
        try {
            val arr = JSONArray(sp.getString("buttons", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    Saved(
                        o.getString("n"),
                        IrCode(
                            Protocol.valueOf(o.getString("p")),
                            o.getInt("a"),
                            o.getInt("c"),
                            o.optInt("t", 0)
                        )
                    )
                )
            }
        } catch (e: Exception) {
            // a damaged list simply starts empty
        }
        return out
    }

    fun save(list: List<Saved>) {
        val arr = JSONArray()
        for (s in list) {
            arr.put(
                JSONObject()
                    .put("n", s.name)
                    .put("p", s.code.protocol.name)
                    .put("a", s.code.address)
                    .put("c", s.code.command)
                    .put("t", s.code.toggle)
            )
        }
        sp.edit().putString("buttons", arr.toString()).apply()
    }
}
