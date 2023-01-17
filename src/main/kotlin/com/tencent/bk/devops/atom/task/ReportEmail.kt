package com.tencent.bk.devops.atom.task


//("自定义产出物报告-发送邮件")
data class ReportEmail(
    // 接收人
    val receivers: Set<String>,
    // 邮件主题
    val title: String,
    // 邮件内容
    val html: String
)