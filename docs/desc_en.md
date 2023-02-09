#### Plugin function
Archive the report files in the specified directory of the builder, and send email notifications at the same time.

#### Plugin parameters
- Output report path (fileDir): archive the report files under the relative path or absolute path directory of the current workspace, all files under this path will be archived.
- Entry file (indexFile): Specify the entry file name.
- Entry file encoding (indexFileCharset): Specifies the encoding type of the entry file.
- Label Alias (reportName): Specifies the report name.
- Parallel upload (isParallel): When enabled, upload report files in parallel.
- Enable Email Notification (isSendEmail): When enabled, the report will be sent by email.
- Recipients: Fill in recipients, support multiple recipients and mail groups.
- Email subject (body): The title of the email.
