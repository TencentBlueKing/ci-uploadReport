package com.tencent.bk.devops.atom.task

import com.fasterxml.jackson.module.kotlin.readValue
import com.google.common.collect.Maps
import com.tencent.bk.devops.atom.api.BaseApi
import com.tencent.bk.devops.atom.exception.AtomException
import com.tencent.bk.devops.atom.pojo.AtomBaseParam
import com.tencent.bk.devops.atom.pojo.Result
import com.tencent.bk.devops.atom.task.JsonUtils.objectMapper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import org.slf4j.LoggerFactory
import java.io.File

class ArchiveApi : BaseApi() {
    private val atomHttpClient = AtomHttpClient()

    fun uploadBkRepoReportFile(file: File, elementId: String, relativePath: String, atomBaseParam: AtomBaseParam) {
        val request = atomHttpClient.buildAtomPut(
            "/bkrepo/api/build/generic/${atomBaseParam.projectName}/report/${atomBaseParam.pipelineId}/${atomBaseParam.pipelineBuildId}/$elementId/${relativePath.removePrefix("/")}",
            RequestBody.create("application/octet-stream".toMediaType(), file),
            getBkRepoUploadHeader(file, atomBaseParam)
        )
        uploadFile(request)
    }

    fun create(elementId: String, indexFile: String, name: String, reportType: String? = ReportTypeEnum.INTERNAL.name): Result<Boolean> {
        val path = "/process/api/build/reports/$elementId?indexFile=${encode(indexFile)}&name=${encode(name)}&reportType=$reportType"
        val responseContent = request(buildPost(path), "创建报告失败")
        return objectMapper.readValue(responseContent)
    }

    private fun uploadFile(request: Request, retry: Boolean = false) {
        try {
            val response = atomHttpClient.doRequest(request)
            if (!response.isSuccessful) {
                logger.error("upload file failed, code: ${response.code}, responseContent: ${response.body!!.string()}")
                throw AtomException("upload file failed")
            }
        } catch (e: Exception) {
            logger.error("upload file error, cause: ${e.message}")
            if (!retry) {
                logger.info("retry after 3 seconds")
                Thread.sleep(3 * 1000L)
                uploadFile(request, true)
                return
            }
            throw e
        }
    }

    fun getRootUrl(elementId: String): Result<String> {
        val path = "/process/api/build/reports/$elementId/rootUrl"
        val request = buildGet(path)
        val responseContent = request(request, "获取报告跟路径失败")
        return objectMapper.readValue(responseContent)
    }

    private fun getBkRepoUploadHeader(file: File, atomParam: AtomBaseParam): HashMap<String, String> {
        val header = Maps.newHashMap<String, String>()
        header[BKREPO_METADATA_PREFIX + ARCHIVE_PROPS_PROJECT_ID] = atomParam.projectName
        header[BKREPO_METADATA_PREFIX + ARCHIVE_PROPS_PIPELINE_ID] = atomParam.pipelineId
        header[BKREPO_METADATA_PREFIX + ARCHIVE_PROPS_BUILD_ID] = atomParam.pipelineBuildId
        header[BKREPO_METADATA_PREFIX + ARCHIVE_PROPS_USER_ID] = atomParam.pipelineStartUserName
        header[BKREPO_METADATA_PREFIX + ARCHIVE_PROPS_BUILD_NO] = atomParam.pipelineBuildNum
        header[BKREPO_METADATA_PREFIX + ARCHIVE_PROPS_SOURCE] = "pipeline"
        header[BKREPO_UID] = atomParam.pipelineStartUserName
        header[BKREPO_OVERRIDE] = "true"
        return header
    }

    companion object {
        private val logger = LoggerFactory.getLogger(ArchiveApi::class.java)

        private const val ARCHIVE_PROPS_PROJECT_ID = "projectId"
        private const val ARCHIVE_PROPS_PIPELINE_ID = "pipelineId"
        private const val ARCHIVE_PROPS_BUILD_ID = "buildId"
        private const val ARCHIVE_PROPS_BUILD_NO = "buildNo"
        private const val ARCHIVE_PROPS_USER_ID = "userId"
        private const val ARCHIVE_PROPS_SOURCE = "source"

        private const val BKREPO_METADATA_PREFIX = "X-BKREPO-META-"
        private const val BKREPO_UID = "X-BKREPO-UID"
        private const val BKREPO_OVERRIDE = "X-BKREPO-OVERWRITE"
    }
}