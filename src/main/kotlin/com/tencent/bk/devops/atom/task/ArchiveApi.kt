package com.tencent.bk.devops.atom.task

import com.fasterxml.jackson.module.kotlin.readValue
import com.google.common.collect.Maps
import com.tencent.bk.devops.atom.api.BaseApi
import com.tencent.bk.devops.atom.api.SdkEnv
import com.tencent.bk.devops.atom.exception.AtomException
import com.tencent.bk.devops.atom.pojo.AtomBaseParam
import com.tencent.bk.devops.atom.pojo.Result
import com.tencent.bk.devops.atom.task.JsonUtils.objectMapper
import com.tencent.bkrepo.common.api.constant.HttpHeaders
import com.tencent.bkrepo.common.api.constant.MediaTypes
import com.tencent.bkrepo.common.api.constant.StringPool
import com.tencent.bkrepo.common.api.pojo.Response
import com.tencent.bkrepo.common.api.util.readJsonString
import com.tencent.bkrepo.common.api.util.toJsonString
import com.tencent.bkrepo.common.artifact.pojo.RepositoryCategory
import com.tencent.bkrepo.common.artifact.pojo.RepositoryType
import com.tencent.bkrepo.generic.pojo.TemporaryAccessToken
import com.tencent.bkrepo.repository.pojo.project.UserProjectCreateRequest
import com.tencent.bkrepo.repository.pojo.repo.UserRepoCreateRequest
import com.tencent.bkrepo.repository.pojo.token.TemporaryTokenCreateRequest
import com.tencent.bkrepo.repository.pojo.token.TokenType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class ArchiveApi : BaseApi() {
    private val atomHttpClient = AtomHttpClient()
    private val fileGateway: String by lazy { SdkEnv.getFileGateway() }
    private val tokenRequest: Boolean by lazy { fileGateway.isNotBlank() }
    private var createFlag: Boolean = false
    private var token: String = ""

    fun uploadBkRepoReportFile(file: File, elementId: String, relativePath: String, atomBaseParam: AtomBaseParam) {
        val fullPath = "/${atomBaseParam.pipelineId}/${atomBaseParam.pipelineBuildId}/$elementId/$relativePath"
        uploadFile(file, fullPath, atomBaseParam)
    }

    fun setIndexFileMetadata(reportName: String, indexFileName: String, atomBaseParam: AtomBaseParam) {
        with(atomBaseParam) {
            val url = if (tokenRequest) {
                createToken(pipelineStartUserId, projectName)
                "$fileGateway/repository/api/metadata/$projectName/report/$pipelineId/$pipelineBuildId/$pipelineTaskId/$indexFileName"
            } else {
                "/bkrepo/api/build/repository/api/metadata/$projectName/report/$pipelineId/$pipelineBuildId/$pipelineTaskId/$indexFileName"
            }
            val metadata = mapOf(
                ARCHIVE_PROPS_PROJECT_ID to projectName,
                ARCHIVE_PROPS_PIPELINE_ID to pipelineId,
                ARCHIVE_PROPS_BUILD_ID to pipelineBuildId,
                ARCHIVE_PROPS_USER_ID to pipelineStartUserName,
                ARCHIVE_PROPS_BUILD_NO to pipelineBuildNum,
                ARCHIVE_PROPS_SOURCE to "pipeline",
                ARCHIVE_PROPS_REPORT_NAME to reportName,
                ARCHIVE_PROPS_REPORT_TYPE to ReportTypeEnum.INTERNAL.name
            )
            val metadataSaveRequest = UserMetadataSaveRequest(metadata.map { MetadataModel(it.key, it.value) })
            val request = buildPost(
                url,
                metadataSaveRequest.toJsonString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()),
                buildBaseHeaders(pipelineStartUserId)
            )
            doRequest(request)
        }

    }

    fun create(
        elementId: String,
        indexFile: String,
        name: String,
        reportType: String? = ReportTypeEnum.INTERNAL.name,
        reportEmail: ReportEmail? = null
    ): Result<Boolean> {
        val path =
            "/process/api/build/reports/$elementId?indexFile=${encode(indexFile)}&name=${encode(name)}&reportType=$reportType"
        val request = if (reportEmail == null) {
            buildPost(path)
        } else {
            val requestBody = objectMapper.writeValueAsString(reportEmail)
                .toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
            buildPost(path, requestBody, Maps.newHashMap())
        }
        val responseContent = request(request, "创建报告失败")
        return objectMapper.readValue(responseContent)
    }

    private fun uploadFile(file: File, fullPath: String, atomParam: AtomBaseParam) {
        if (tokenRequest) {
            token = createToken(atomParam.pipelineStartUserId, atomParam.projectName)
            createProjectOrRepoIfNotExist(atomParam.pipelineStartUserId, atomParam.projectName)
            uploadFileByToken(file, fullPath, atomParam)
        } else {
            val request = buildPut(
                "/bkrepo/api/build/generic/${atomParam.projectName}/report$fullPath",
                file.asRequestBody("application/octet-stream".toMediaType()),
                buildBaseHeaders(atomParam.pipelineStartUserId)
            )
            doRequest(request)
        }
    }

    private fun uploadFileByToken(file: File, fullPath: String, atomParam: AtomBaseParam) {
        val request = buildPut(
            "$fileGateway/generic/${atomParam.projectName}/report$fullPath?token=$token",
            file.asRequestBody("application/octet-stream".toMediaType()),
            buildBaseHeaders(atomParam.pipelineStartUserId)
        )
        doRequest(request)
    }

    private fun createToken(userId: String, projectId: String): String {
        if (token.isNotBlank()) {
            return token
        }
        val tokenCreateRequest = TemporaryTokenCreateRequest(
            projectId,
            "report",
            setOf(StringPool.ROOT),
            setOf(userId),
            emptySet(),
            TimeUnit.DAYS.toSeconds(1),
            null,
            TokenType.ALL
        )
        val request = buildPost(
            "/bkrepo/api/build/generic/temporary/token/create",
            tokenCreateRequest.toJsonString().toRequestBody(MediaTypes.APPLICATION_JSON.toMediaType()),
            buildBaseHeaders(userId)
        )
        val (status, response) = doRequest(request)
        if (status == 200) {
            return response.readJsonString<Response<List<TemporaryAccessToken>>>().data!!.first().token
        } else {
            throw AtomException(response)
        }
    }

    private fun createProjectOrRepoIfNotExist(userId: String, projectId: String) {
        if (createFlag) {
            return
        }
        createProject(userId, projectId)
        createRepo(userId, projectId)
        createFlag = true
    }

    private fun createProject(userId: String, projectId: String) {
        val projectCreateRequest = UserProjectCreateRequest(projectId, projectId, "")
        val request = buildPost(
            "$fileGateway/repository/api/project/create",
            projectCreateRequest.toJsonString().toRequestBody(MediaTypes.APPLICATION_JSON.toMediaType()),
            buildBaseHeaders(userId)
        )
        val (status, response) = doRequest(request)
        if (status == 200) {
            return
        }
        val code = response.readJsonString<Response<Void>>().code
        if (code == ERROR_PROJECT_EXISTED) {
            return
        } else {
            throw AtomException(response)
        }
    }

    private fun createRepo(userId: String, projectId: String) {
        val repoCreateRequest = UserRepoCreateRequest(
            projectId = projectId,
            name = "report",
            type = RepositoryType.GENERIC,
            category = RepositoryCategory.LOCAL,
            public = false
        )
        val request = buildPost(
            "$fileGateway/repository/api/repo/create",
            repoCreateRequest.toJsonString().toRequestBody(MediaTypes.APPLICATION_JSON.toMediaType()),
            buildBaseHeaders(userId)
        )
        val (status, response) = doRequest(request)
        if (status == 200) {
            return
        }
        val code = response.readJsonString<Response<Void>>().code
        if (code == ERROR_REPO_EXISTED) {
            return
        } else {
            throw AtomException(response)
        }
    }

    private fun doRequest(request: Request, retry: Int = 3): Pair<Int,String> {
        try {
            val response = atomHttpClient.doRequest(request)
            val responseContent = response.body!!.string()
            if (response.isSuccessful) {
                return Pair(response.code, responseContent)
            }
            logger.debug("request url: ${request.url}, code: ${response.code}, response: $responseContent")
            if (response.code > 499 && retry > 0) {
                return doRequest(request, retry - 1)
            }
            return Pair(response.code, responseContent)
        } catch (e: IOException) {
            logger.error("request[${request.url}] error, ", e)
            if (retry > 0) {
                logger.info("retry after 3 seconds")
                Thread.sleep(3 * 1000L)
                return doRequest(request, retry - 1)
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

    private fun buildBaseHeaders(userId: String): MutableMap<String, String> {
        val header = mutableMapOf<String, String>()
        header[BKREPO_UID] = userId
        if (token.isNotBlank()) {
            header[HttpHeaders.AUTHORIZATION] = "Temporary $token"
        }

        return header
    }

    private fun getBkRepoUploadHeader(atomParam: AtomBaseParam): MutableMap<String, String> {
        val header = buildBaseHeaders(atomParam.pipelineStartUserId)
        header[BKREPO_METADATA_PREFIX + ARCHIVE_PROPS_PROJECT_ID] = atomParam.projectName
        header[BKREPO_METADATA_PREFIX + ARCHIVE_PROPS_PIPELINE_ID] = atomParam.pipelineId
        header[BKREPO_METADATA_PREFIX + ARCHIVE_PROPS_BUILD_ID] = atomParam.pipelineBuildId
        header[BKREPO_METADATA_PREFIX + ARCHIVE_PROPS_USER_ID] = atomParam.pipelineStartUserName
        header[BKREPO_METADATA_PREFIX + ARCHIVE_PROPS_BUILD_NO] = atomParam.pipelineBuildNum
        header[BKREPO_METADATA_PREFIX + ARCHIVE_PROPS_SOURCE] = "pipeline"
        header[BKREPO_OVERRIDE] = "true"
        return header
    }

    companion object {
        private val logger = LoggerFactory.getLogger(ArchiveApi::class.java)

        private const val ERROR_PROJECT_EXISTED = 251005
        private const val ERROR_REPO_EXISTED = 251007

        private const val ARCHIVE_PROPS_PROJECT_ID = "projectId"
        private const val ARCHIVE_PROPS_PIPELINE_ID = "pipelineId"
        private const val ARCHIVE_PROPS_BUILD_ID = "buildId"
        private const val ARCHIVE_PROPS_BUILD_NO = "buildNo"
        private const val ARCHIVE_PROPS_USER_ID = "userId"
        private const val ARCHIVE_PROPS_SOURCE = "source"
        private const val ARCHIVE_PROPS_REPORT_NAME = "reportName"
        private const val ARCHIVE_PROPS_REPORT_TYPE = "reportType"

        private const val BKREPO_METADATA_PREFIX = "X-BKREPO-META-"
        private const val BKREPO_UID = "X-BKREPO-UID"
        private const val BKREPO_OVERRIDE = "X-BKREPO-OVERWRITE"
    }
}