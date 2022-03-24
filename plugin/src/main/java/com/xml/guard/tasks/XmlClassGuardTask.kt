package com.xml.guard.tasks

import com.xml.guard.entensions.GuardExtension
import com.xml.guard.model.Mapping
import com.xml.guard.utils.findClassByLayoutXml
import com.xml.guard.utils.findClassByManifest
import com.xml.guard.utils.findClassByNavigationXml
import com.xml.guard.utils.findDependencyAndroidProject
import com.xml.guard.utils.findLocationProject
import com.xml.guard.utils.getDirPath
import com.xml.guard.utils.javaDir
import com.xml.guard.utils.manifestFile
import com.xml.guard.utils.removeSuffix
import com.xml.guard.utils.replaceWords
import com.xml.guard.utils.resDir
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.tasks.TaskAction
import java.io.File
import javax.inject.Inject

/**
 * User: ljx
 * Date: 2022/2/25
 * Time: 19:06
 */
open class XmlClassGuardTask @Inject constructor(
    guardExtension: GuardExtension
) : DefaultTask() {

    init {
        group = "guard"
    }

    private val mappingFile = guardExtension.mappingFile ?: project.file("xml-class-mapping.txt")
    private val mapping = Mapping(mappingFile)

    @TaskAction
    fun execute() {
        val dependencyProjects = mutableListOf<Project>()
        project.findDependencyAndroidProject(dependencyProjects)
        val androidProjects = mutableListOf<Project>()
        androidProjects.add(project)
        androidProjects.addAll(dependencyProjects)
        //1、遍历res下的xml文件，找到自定义的类(View/Fragment/四大组件等)，并将混淆结果同步到xml文件内
        androidProjects.forEach { handleResDir(it) }
        //2、混淆文件名及文件路径，返回本次混淆的类
        val classMapping = mapping.obfuscateAllClass(project)
        //3、替换Java/kotlin文件里引用到的类
        if (classMapping.isNotEmpty()) {
            androidProjects.forEach { replaceJavaText(it, classMapping) }
        }
        //4、混淆映射写出到文件
        mapping.writeMappingToFile()
    }

    //处理res目录
    private fun handleResDir(project: Project) {
        val listFiles = project.resDir().listFiles { _, name ->
            //过滤res目录下的layout、navigation目录
            name.startsWith("layout") || name.startsWith("navigation")
        }?.toMutableList() ?: return
        listFiles.add(project.manifestFile())
        project.files(listFiles).asFileTree.forEach { xmlFile ->
            guardXml(project, xmlFile)
        }
    }

    private fun guardXml(project: Project, xmlFile: File) {
        var xmlText = xmlFile.readText()
        val classPaths = mutableListOf<String>()
        val parentName = xmlFile.parentFile.name
        var packageName: String? = null
        when {
            parentName.startsWith("navigation") -> {
                findClassByNavigationXml(xmlText, classPaths)
            }
            parentName.startsWith("layout") -> {
                findClassByLayoutXml(xmlText, classPaths)
            }
            xmlFile.name == "AndroidManifest.xml" -> {
                packageName = findClassByManifest(xmlText, classPaths)
            }
        }
        for (classPath in classPaths) {
            val dirPath = classPath.getDirPath()
            //本地不存在这个文件
            if (project.findLocationProject(dirPath) == null) continue
            //已经混淆了这个类
            if (mapping.isObfuscated(classPath)) continue
            val obfuscatePath = mapping.obfuscatePath(classPath)
            xmlText = xmlText.replaceWords(classPath, obfuscatePath)
            if (packageName != null) {
                xmlText = xmlText.replaceWords(classPath.substring(packageName.length), obfuscatePath)
            }
        }
        xmlFile.writeText(xmlText)
    }


    private fun replaceJavaText(project: Project, mapping: Map<String, String>) {
        val javaDir = project.javaDir()
        //遍历所有Java\Kt文件，替换混淆后的类的引用，import及new对象的地方
        project.files(javaDir).asFileTree.forEach { javaFile ->
            var replaceText = javaFile.readText()
            mapping.forEach {
                replaceText = replaceText(javaFile, replaceText, it.key, it.value)
            }
            javaFile.writeText(replaceText)
        }
    }

    private fun replaceText(
        rawFile: File,
        rawText: String,
        rawPath: String,
        obfuscatePath: String,
    ): String {
        val rawIndex = rawPath.lastIndexOf(".")
        val rawPackage = rawPath.substring(0, rawIndex)
        val rawName = rawPath.substring(rawIndex + 1)

        val obfuscateIndex = obfuscatePath.lastIndexOf(".")
        val obfuscatePackage = obfuscatePath.substring(0, obfuscateIndex)
        val obfuscateName = obfuscatePath.substring(obfuscateIndex + 1)

        var replaceText = rawText
        replaceText = if (rawFile.absolutePath.removeSuffix().endsWith(obfuscatePath.replace(".", "/"))) {
            //更改混淆文件的package路径
            replaceText.replaceWords("package $rawPackage", "package $obfuscatePackage")
        } else {
            replaceText.replaceWords(rawPath, obfuscatePath)  //替换{包名+类名}
                .replaceWords("$rawPackage.*", "$obfuscatePackage.*")
        }
        replaceText = replaceText.replaceWords(rawName, obfuscateName)
        return replaceText
    }
}