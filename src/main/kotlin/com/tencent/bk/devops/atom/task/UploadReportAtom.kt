package com.tencent.bk.devops.atom.task

import com.tencent.bk.devops.atom.AtomContext
import com.tencent.bk.devops.atom.exception.AtomException
import com.tencent.bk.devops.atom.pojo.StringData
import com.tencent.bk.devops.atom.spi.AtomService
import com.tencent.bk.devops.atom.spi.TaskAtom
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.charset.Charset
import java.nio.file.Paths
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@AtomService(paramClass = UploadReportParam::class)
class UploadReportAtom : TaskAtom<UploadReportParam> {
    override fun execute(atomContext: AtomContext<UploadReportParam>) {
        val atomParam = atomContext.param
        val atomResult = atomContext.result
        checkAndInitParam(atomParam)

        val workspace = File(atomParam.bkWorkspace)
        val elementId = atomParam.pipelineTaskId

        val isParallel = atomParam.isParallel == "true"
        val reportName: String = atomParam.reportName
        val fileDirParam = atomParam.fileDir
        val indexFileParam = atomParam.indexFile
        val fileDir = getFile(workspace, fileDirParam)
        if (!fileDir.isDirectory) {
            logger.error("report folder($fileDirParam) is not exist.")
            throw AtomException("report folder $fileDirParam not exist")
        }
        val indexFile = getFile(fileDir, indexFileParam)
        if (!indexFile.exists()) {
            logger.error("index file $indexFileParam not found in folder $fileDirParam")
            throw AtomException("index file($indexFileParam) not found")
        }
        val indexFileCharset = atomParam.indexFileCharset
        logger.info("index file encoding: $indexFileCharset")

        val reportRootUrl = archiveApi.getRootUrl(elementId).data!!
        val fileCharset = Charset.forName(indexFileCharset)
        var indexFileContent = indexFile.readText(fileCharset)
        indexFileContent = indexFileContent.replace("\${$REPORT_DYNAMIC_ROOT_URL}", reportRootUrl)
        val indexFileEmailContent = indexFileContent.replace("/api-html/user/", "/api/external/")
        indexFile.writeText(indexFileEmailContent, fileCharset)

        val fileDirPath = Paths.get(fileDir.absolutePath)
        val allFileList = recursiveGetFiles(fileDir).filter {
            if (!isFileLegal(it.name)) {
                logger.warn("illegal file $it(.md5|.sha1|.sha256|.ds_store|.DS_STORE|space), will skip upload")
                false
            } else {
                true
            }
        }
        val size = allFileList.size
        if (size == 0) {
            logger.error("no report file found in $fileDirPath")
            throw AtomException("no report file found")
        }
        if (size > MAX_FILE_COUNT) {
            logger.error("file count exceed $MAX_FILE_COUNT, please check your input")
            throw AtomException("too many files")
        }

        println("$size files was found, start upload...")

        if (isParallel) {
            val executor = Executors.newFixedThreadPool(UPLOAD_THREAD_COUNT)
            allFileList.forEach {
                val worker = {
                    val relativePath = fileDirPath.relativize(Paths.get(it.absolutePath)).toString()
                    archiveApi.uploadBkRepoReportFile(it, elementId, relativePath, atomParam)
                    logger.info("$relativePath uploaded parallel")
                }
                executor.submit(worker)
            }
            executor.shutdown()
            executor.awaitTermination(Long.MAX_VALUE, TimeUnit.MINUTES)
        } else {
            allFileList.forEach {
                val relativePath = fileDirPath.relativize(Paths.get(it.absolutePath)).toString()
                archiveApi.uploadBkRepoReportFile(it, elementId, relativePath, atomParam)
                logger.info("$relativePath uploaded")
            }
        }
        println("report upload done")

        archiveApi.create(elementId, indexFileParam, reportName)

        val reportRootUrlData = StringData(reportRootUrl)
        logger.info("$REPORT_DYNAMIC_ROOT_URL is :{}", com.tencent.bk.devops.atom.utils.json.JsonUtil.toJson(reportRootUrlData))
        atomResult.data[REPORT_DYNAMIC_ROOT_URL] = reportRootUrlData
    }

    private fun checkAndInitParam(atomParam: UploadReportParam) {
        if (atomParam.reportName.isNullOrBlank()) {
            throw AtomException("invalid reportName")
        }
        if (atomParam.indexFile.isNullOrBlank()) {
            throw AtomException("invalid indexFile")
        }
        if (atomParam.fileDir.isNullOrBlank()) {
            throw AtomException("invalid fileDir")
        }

        var indexFileCharset = atomParam.indexFileCharset
        if (indexFileCharset.isNullOrBlank()) {
            indexFileCharset = "UTF-8"
        }
        if (indexFileCharset == "default") {
            indexFileCharset = Charset.defaultCharset().name()
        }
        if (!Charset.availableCharsets().containsKey(indexFileCharset)) {
            throw RuntimeException("unsupported charset: $indexFileCharset")
        }
        atomParam.indexFileCharset = indexFileCharset
    }

    private fun recursiveGetFiles(file: File): List<File> {
        val fileList = mutableListOf<File>()
        file.listFiles()?.forEach {
            // 排除掉源文件已被删除的软链接
            if (it.isDirectory && it.exists()) {
                val subFileList = recursiveGetFiles(it)
                fileList.addAll(subFileList)
            } else {
                if (!it.isHidden && it.exists()) {
                    // 过滤掉隐藏文件
                    fileList.add(it)
                }
            }
        }
        return fileList
    }

    private fun getFile(workspace: File, filePath: String): File {
        val absPath = filePath.startsWith("/") || (filePath.length >= 2 && filePath[0].isLetter() && filePath[1] == ':')
        return if (absPath) {
            File(filePath)
        } else {
            File(workspace, filePath)
        }
    }

    private fun isFileLegal(name: String): Boolean {
        FIlTER_FILE.forEach {
            if (name.toLowerCase().endsWith(it)) return false
        }
        return true
    }

    companion object {
        private val logger = LoggerFactory.getLogger(UploadReportAtom::class.java)
        private const val REPORT_DYNAMIC_ROOT_URL = "report.dynamic.root.url"
        private val FIlTER_FILE = listOf(".md5", ".sha1", ".sha256", ".ds_store")
        private const val MAX_FILE_COUNT = 10000
        private const val UPLOAD_THREAD_COUNT = 10
        var archiveApi = ArchiveApi()
    }
}