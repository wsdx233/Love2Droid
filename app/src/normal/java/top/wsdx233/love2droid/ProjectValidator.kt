package top.wsdx233.love2droid

import java.io.File

object ProjectValidator {
    fun validate(project: Project): String? {
        if (!project.root.isDirectory) return "项目目录不存在"
        val main = File(project.root, "main.lua")
        if (!StorageUtils.isWithin(project.root, main)) return "项目路径无效"
        if (!main.isFile) return "项目缺少 main.lua"
        if (main.length() > EditorFileLoader.MAX_EDITOR_BYTES) return "main.lua 过大"
        return null
    }
}
