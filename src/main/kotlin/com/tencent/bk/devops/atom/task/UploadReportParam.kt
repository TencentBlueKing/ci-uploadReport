package com.tencent.bk.devops.atom.task

import com.tencent.bk.devops.atom.pojo.AtomBaseParam
import lombok.Data
import lombok.EqualsAndHashCode

@Data
@EqualsAndHashCode(callSuper = true)
class UploadReportParam : AtomBaseParam() {
    var isParallel: String = ""
    var reportName: String = ""
    var indexFile: String = ""
    var fileDir: String = ""
    var indexFileCharset: String = ""
}