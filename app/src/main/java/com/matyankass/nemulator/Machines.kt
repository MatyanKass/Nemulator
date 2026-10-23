package com.matyankass.nemulator

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class CpuModel(val title: String, val qemu: String, val hint: String) {
    MAX("Максимальный", "max", "Все возможности процессора — для Windows 10 обычно лучший вариант"),
    COMPAT("Совместимый", "Nehalem", "Если Windows не загружается или падает с синим экраном"),
}

data class Resolution(val w: Int, val h: Int) {
    override fun toString() = "$w × $h"

    companion object {
        val presets = listOf(
            Resolution(1280, 720),
            Resolution(1366, 768),
            Resolution(1600, 900),
            Resolution(1920, 1080),
            Resolution(1024, 768),
        )
    }
}

/** Один «компьютер»: настройки и его жёсткий диск в files/machines/<id>/. */
data class Machine(
    val id: String = UUID.randomUUID().toString().take(8),
    val name: String = "Мой ПК",
    val ramMb: Int = 3072,
    val cpus: Int = 4,
    val diskGb: Int = 48,
    val isoPath: String = "",
    val cpu: CpuModel = CpuModel.MAX,
    val resolution: Resolution = Resolution.presets[0],
) {
    fun dir(ctx: Context) = File(ctx.filesDir, "machines/$id")
    fun disk(ctx: Context) = File(dir(ctx), "disk.qcow2")

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("ramMb", ramMb)
        .put("cpus", cpus)
        .put("diskGb", diskGb)
        .put("isoPath", isoPath)
        .put("cpu", cpu.name)
        .put("resW", resolution.w)
        .put("resH", resolution.h)

    companion object {
        fun fromJson(j: JSONObject) = Machine(
            id = j.getString("id"),
            name = j.optString("name", "ПК"),
            ramMb = j.optInt("ramMb", 3072),
            cpus = j.optInt("cpus", 4),
            diskGb = j.optInt("diskGb", 48),
            isoPath = j.optString("isoPath", ""),
            cpu = runCatching { CpuModel.valueOf(j.optString("cpu")) }.getOrDefault(CpuModel.MAX),
            resolution = Resolution(j.optInt("resW", 1280), j.optInt("resH", 720)),
        )
    }
}

object Machines {
    private fun root(ctx: Context) = File(ctx.filesDir, "machines")

    fun list(ctx: Context): List<Machine> =
        root(ctx).listFiles().orEmpty()
            .mapNotNull { d ->
                runCatching { Machine.fromJson(JSONObject(File(d, "machine.json").readText())) }.getOrNull()
            }
            .sortedBy { it.name.lowercase() }

    fun save(ctx: Context, m: Machine) {
        val d = m.dir(ctx)
        d.mkdirs()
        val tmp = File(d, "machine.json.tmp")
        tmp.writeText(m.toJson().toString(2))
        tmp.renameTo(File(d, "machine.json"))
    }

    fun delete(ctx: Context, m: Machine) {
        m.dir(ctx).deleteRecursively()
    }

    /** Сколько места реально занимает диск ПК (qcow2 растёт по мере записи). */
    fun diskUsedBytes(ctx: Context, m: Machine): Long = m.disk(ctx).takeIf { it.exists() }?.length() ?: 0
}
