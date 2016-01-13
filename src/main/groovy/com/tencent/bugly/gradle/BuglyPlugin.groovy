/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tencent.bugly.gradle

import com.android.builder.core.DefaultManifestParser
import com.tencent.bugly.symtabtool.android.SymtabToolAndroid
import org.apache.http.Consts
import org.apache.http.HttpEntity
import org.apache.http.HttpStatus
import org.apache.http.client.ClientProtocolException
import org.apache.http.client.methods.CloseableHttpResponse
import org.apache.http.client.methods.HttpPost
import org.apache.http.conn.ConnectTimeoutException
import org.apache.http.entity.ContentType
import org.apache.http.entity.FileEntity
import org.apache.http.impl.client.CloseableHttpClient
import org.apache.http.impl.client.HttpClients
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.file.ConfigurableFileTree

import java.security.MessageDigest
import java.security.NoSuchAlgorithmException

/**
 * {@code BuglyPlugin} is a gradle plugin for uploading symtab files to Bugly platform.
 *
 * <p>The term "symtab file" is an abbreviation of "symbol table file" that includes mapping
 * files (*.txt) created by Proguard and symbol files (*.symbol) created by "Symtab Tool for
 * Android" of Bugly which can be downloaded from http://bugly.qq.com/whitebook.
 *
 * <p>This plugin will create a task named "upload${variantName}SymtabFile".
 * ({@code "variantname"} means the name of variant. e.g., "Release") for doing following two
 * things:
 * <p>1. Create symbol files of SO files (*.so) created by Android NDK if the project has
 * native code.
 * <p>2. Upload symtab files to Bugly platform implemented through HttpClient.
 * <p>The "upload${variantName}SymtabFile"  task will only be activated if
 * the variant is "release". And if the project doesn't enable code obfuscation and doesn't
 * have any native code, the task will do nothing.
 *
 * <p>The plugin should be configured through the following required properties:
 * <p>{@code appId}: app ID of Bugly platform.
 * <p>{@code appKey}: app Key of Bugly platform.
 * <p>Other optional properties:
 * <p>{@code execute}: switch for controlling execution of "upload${variantName}SymtabFile".
 * <p>{@code upload}: switch for uploading symtab files.
 * <p>{@code outputDir}: Directory where symbol files would be output to.
 *
 * <p>More details can be read at http://bugly.qq.com/androidsymbol.
 *
 * @author JalenChen
 */
class BuglyPlugin implements Plugin<Project> {

    // Back up project
    private Project project = null

    /**
     * Note that there are two different URL for uploading mapping file and symbol file.
     */
    // URL for uploading mapping file
    private static final String MAPPING_UPLOAD_URL = 'http://bugly.qq.com/upload/map'
    // URL for uploading symbol file
    private static final String SYMBOL_UPLOAD_URL = 'http://bugly.qq.com/upload/symbol'

    /**
     * Defines for logging.
     *
     * One record of log file will be: <File> --> <SHA-1>[ -- > <Extra Information>]
     *
     */
    // The name of symbol log file.
    private static String SYMBOL_LOG_FILE_NAME = "BuglySymbolLog.txt"
    // The name of upload log file.
    private static String UPLOAD_LOG_FILE_NAME = "BuglyUploadLog.txt"
    // The maximum line of record in log file.
    private static int LOG_RECORD_MAXIMUM_NUMBER = 200
    // Separator of one record.
    private static String LOG_RECORD_SEPARATOR = " --> "

    /**
     * Get path string of output dir
     *
     * @return path string of output dir
     */
    private String getOutputDirName() {
        String dirName = project.bugly.outputDir
        if (dirName == null) {
            return null
        }
        if (!new File(dirName).exists()) {
            if (!new File(dirName).mkdirs()) {
                dirName = project.projectDir.getAbsolutePath() + File.separator + dirName
                if (!new File(dirName).mkdirs()) {
                    return null
                }
            }
        }
        return new File(dirName).getAbsolutePath()
    }

    /**
     * Get message of HTTP response.
     *
     * @param response HTTP response
     * @return message of HTTP response
     */
    private String getResponseMessage(CloseableHttpResponse response) {
        HttpEntity responseEntity = response.getEntity()
        if (null == responseEntity) {
            return null
        }
        InputStream inputStream = null
        String ret = ""
        try {
            inputStream = responseEntity.getContent()
            int n
            byte[] tmp = new byte[1024]
            while ((n = inputStream.read(tmp)) != -1) {
                ret += new String(tmp, 0, n, "utf-8")
            }
            return ret
        } catch (IOException e) {
            project.logger.warn(e.getMessage())
            return null
        } finally {
            try {
                if (null != inputStream) {
                    inputStream.close()
                }
            } catch (IOException e) {
                project.logger.warn(e.getMessage())
            }
        }
    }

    /**
     * Execute HTTP post.
     *
     * @param url HTTP URL
     * @param httpEntity HTTP entity
     * @return true if success; false otherwise
     */
    private boolean post(String url, HttpEntity httpEntity) {
        CloseableHttpClient httpClient = HttpClients.createDefault()
        HttpPost httpPost = new HttpPost(url)
        httpPost.setEntity(httpEntity)
        CloseableHttpResponse response = null
        try {
            response = httpClient.execute(httpPost)
            int statusCode = response.getStatusLine().getStatusCode()
            if (HttpStatus.SC_OK == statusCode) {
                project.logger.info(getResponseMessage(response))
                return true
            } else {
                project.logger.warn("Failed to execute POST for \"%s\"", response.getStatusLine())
                return false
            }
        } catch (ConnectTimeoutException e) {
            project.logger.warn(e.getMessage() + "\nPlease Check your network.")
            return false
        } catch (SocketTimeoutException e) {
            project.logger.warn(e.getMessage() + "\nPlease Check your network.")
            return false
        } catch (ClientProtocolException e) {
            project.logger.warn(e.getMessage())
            return false
        } catch (IOException e) {
            project.logger.warn(e.getMessage())
            return false
        } finally {
            try {
                if (null != response) {
                    response.close()
                }
                httpClient.close()
            } catch (IOException e) {
                project.logger.warn(e.getMessage())
            }
        }
    }

    /**
     * Information need for upload.
     */
    private static class UploadInfo {
        // App ID of Bugly platform.
        public String appId = null
        // App Key of Bugly platform.
        public String appKey = null
        // Version name of the project.
        public String version = null
        // Package name of the project.
        public String packageName = null
        // Name of symtab file to upload.
        public String uploadFileName = null
    }

    /**
     * Information of uploading file.
     */
    private static class UploadFile {
        // File to upload.
        public File file = null
        // Whether the file is mapping file.
        public boolean isMapping = false
    }

    /**
     * Encode URL string.
     *
     * @param str URL string
     * @return encoded URL string
     */
    private String urlEncodeString(String str) {
        try {
            return URLEncoder.encode(str, "UTF-8")
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace()
            return null
        }
    }

    /**
     * Construct uploading URL.
     *
     * @param uploadInfo information need for upload
     * @param isMapping whether the file is mapping file
     * @return uploading URL
     */
    private String toUploadUrl(UploadInfo uploadInfo, boolean isMapping) {
        StringBuilder stringBuilder = new StringBuilder()
        if (isMapping) { // mapping file
            stringBuilder.append(MAPPING_UPLOAD_URL)
        } else { // symbol file
            stringBuilder.append(SYMBOL_UPLOAD_URL)
        }
        stringBuilder.append("?app=")
        stringBuilder.append(uploadInfo.appId)
        stringBuilder.append("&pid=1")
        stringBuilder.append("&ver=")
        stringBuilder.append(urlEncodeString(uploadInfo.version))
        stringBuilder.append("&n=")
        stringBuilder.append(urlEncodeString(uploadInfo.uploadFileName))
        stringBuilder.append("&key=")
        stringBuilder.append(uploadInfo.appKey)
        stringBuilder.append("&bid=")
        stringBuilder.append(uploadInfo.packageName)
        return stringBuilder.toString()
    }

    /**
     * Upload symtab file.
     *
     * @param uploadInfo information need for upload
     * @param uploadFile information of uploading file.
     * @return true if success; false otherwise
     */
    private boolean uploadSymtabFile(UploadInfo uploadInfo, UploadFile uploadFile) {
        String mimeType
        if (uploadFile.isMapping) {
            mimeType = "text/plain"
        } else {
            mimeType = "application/zip"
        }
        uploadInfo.uploadFileName = uploadFile.file.getName()
        String url = toUploadUrl(uploadInfo, uploadFile.isMapping)
        String contentType = ContentType.create(mimeType, Consts.UTF_8)
        FileEntity fileEntity = new FileEntity(uploadFile.file, contentType)
        println 'Uploading the file: ' + uploadFile.file.getAbsolutePath()
        if (!post(url, fileEntity)) {
            project.logger.error("Failed to upload!")
            return false
        } else {
            project.logger.info("Successfully uploaded!")
            return true
        }
    }

    /**
     * Convert byte array to hex string.
     *
     * @param array byte array to convert
     * @return hex string
     */
    private String byteArrayToHexString(byte[] array) {
        StringBuilder stringBuilder = new StringBuilder()
        for (byte b : array) {
            int n = b & 0xFF
            if (n < 16) {
                stringBuilder.append('0')
            }
            stringBuilder.append(Integer.toHexString(n))
        }
        return stringBuilder.toString()
    }

    /**
     * Get SHA-1 of file.
     *
     * @param fileName path name of file
     * @return SHA-1 of file
     */
    private String getFileSha1(String fileName) {
        if (null == fileName) {
            return null
        }
        FileInputStream fileInputStream = null
        MessageDigest messageDigest
        try {
            fileInputStream = new FileInputStream(new File(fileName))
            messageDigest = MessageDigest.getInstance("SHA1")
            byte[] buffer = new byte[4096]
            int length
            while ((length = fileInputStream.read(buffer)) > 0) {
                messageDigest.update(buffer, 0, length)
            }
            return byteArrayToHexString(messageDigest.digest())
        } catch (FileNotFoundException e) {
            project.logger.warn(e.getMessage())
            return null
        } catch (IOException e) {
            project.logger.warn(e.getMessage())
            return null
        } catch (NoSuchAlgorithmException e) {
            project.logger.warn(e.getMessage())
            return null
        } finally {
            try {
                fileInputStream.close()
            } catch (IOException e) {
                project.logger.warn(e.getMessage())
            }
        }
    }

    /**
     * Get log file.
     *
     * @param logFileName name of log file
     * @return log file
     */
    private File getLogFile(String logFileName) {
        String logDirName = getOutputDirName()
        if (null == logDirName) {
            logDirName = project.projectDir.getAbsolutePath()
        }
        return new File(logDirName, logFileName)
    }

    /**
     * Construct one record.
     *
     * @param file file to log
     * @return record constructed
     */
    private String constructRecord(File file, String extraInfo) {
        String filePath = file.getAbsolutePath()
        String sha1 = getFileSha1(filePath)
        if (null == sha1) {
            return null
        }
        StringBuilder stringBuilder = new StringBuilder()
        stringBuilder.append(filePath)
        stringBuilder.append(LOG_RECORD_SEPARATOR)
        stringBuilder.append(sha1)
        if (extraInfo != null) {
            stringBuilder.append(LOG_RECORD_SEPARATOR)
            stringBuilder.append(extraInfo)
        }
        stringBuilder.append("\n")
        return stringBuilder.toString()
    }

    /**
     * Append a record to log file.
     *
     * @param file file to log
     * @param logFile log file
     * @param extraInfo extra information to record
     * @return true if success; false otherwise
     */
    private boolean logFileWithExtraInfo(File logFile, File file, String extraInfo) {
        BufferedWriter logWriter = null
        try {
            logWriter = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(logFile, true), "utf-8"))
            if (!logWriter.write(constructRecord(file, extraInfo))) {
                return false
            }
            return true
        } catch (UnsupportedEncodingException e) {
            project.logger.warn(e.getMessage())
            return false
        } catch (FileNotFoundException e) {
            project.logger.warn(e.getMessage())
            return false
        } catch (IOException e) {
            project.logger.warn(e.getMessage())
            return false
        } finally {
            if (logWriter != null) {
                try {
                    logWriter.close()
                } catch (IOException e) {
                    project.logger.warn(e.getMessage())
                }
            }
        }
    }

    /**
     * Append a record to log file.
     *
     * @param file file to log
     * @param logFile log file
     * @return true if success; false otherwise
     */
    private boolean logFile(File logFile, File file) {
        logFileWithExtraInfo(logFile, file, null)
    }

    /**
     * Check whether the file has been recorded to log file and return extra info.
     *
     * @param file file to be checked
     * @param logFile log file.
     * @return extra information if the file has been recorded; null otherwise
     */
    private String parseLogFile(File logFile, File file) {
        if (!logFile.exists() || !file.exists()) {
            return null
        }
        String filePath = file.getAbsolutePath()
        String fileSha1 = getFileSha1(filePath)
        if (null == fileSha1) {
            return null
        }
        BufferedReader logReader = null
        try {
            logReader = new BufferedReader(new InputStreamReader(new FileInputStream(logFile), "utf-8"))
            String extraInfo = null
            String record
            int count = 0
            while (null != (record = logReader.readLine())) {
                if (count++ >= LOG_RECORD_MAXIMUM_NUMBER) {
                    project.logger.info("The number of records in log file exceed the MAX_COUNT(500). It will clear the log.")
                    logFile.delete()
                    break;
                }
                String[] array = record.split(LOG_RECORD_SEPARATOR)
                if (array.length < 2) {
                    continue
                }
                String recordFilePath = array[0]
                String recordSha1 = array[1]
                if (recordFilePath.equals(filePath) && recordSha1.equals(fileSha1)) {
                    if (array.length > 2) {
                        extraInfo = array[2]
                    } else {
                        extraInfo = ""
                    }
                }
            }
            return extraInfo
        } catch (UnsupportedEncodingException e) {
            project.logger.warn(e.getMessage())
            return null
        } catch (FileNotFoundException e) {
            project.logger.warn(e.getMessage())
            return null
        } finally {
            if (logReader != null) {
                try {
                    logReader.close()
                } catch (IOException e) {
                    project.logger.warn(e.getMessage())
                }
            }
        }
    }

    /**
     * Check whether the file has been recorded to log file.
     *
     * @param file file to be checked
     * @param logFile log file.
     * @return true if the file has been recorded; false otherwise
     */
    private boolean checkLogFile(File logFile, File file) {
        if (parseLogFile(logFile, file) == null) {
            return false
        }
        return true
    }

    /**
     * Create symbol file of a debug SO file.
     *
     * @param soFilePath path of SO file.
     * @param outputDirName directory name for output
     * @return path of created symbol file
     */
    private File createSymbolFile(String soFilePath, String outputDirName) {
        String[] args
        if (null == outputDirName) {
            args = ["-i", soFilePath]
        } else {
            args = ["-i", soFilePath, "-o", outputDirName]
        }
        SymtabToolAndroid.main(args)
        String symtabFileName = SymtabToolAndroid.getSymtabFileName()
        return new File(symtabFileName)
    }

    /**
     * Get debug SO files of project.
     *
     * @param flavorName name of flavor
     * @param project project need to find
     * @return list of found debug SO files.
     */
    private Vector<File> getDebugSoFiles(String flavorName, Project project) {
        Vector<File> soFiles = new Vector<File>()
        if (project == null) {
            return soFiles
        }
        if (flavorName != null && !flavorName.isEmpty()) {
            flavorName += "/"
        }
        String variantFilter = "**/" + flavorName + "release/obj/**/*.so"
        String genericFilter = "**/obj/**/*.so"
        ConfigurableFileTree collection = project.fileTree(project.buildDir) {
            include variantFilter
        }
        if (!collection.isEmpty()) {
            soFiles.addAll(collection.files)
            return soFiles
        }
        collection = project.fileTree(project.projectDir) {
            include variantFilter
        }
        if (!collection.isEmpty()) {
            soFiles.addAll(collection.files)
            return soFiles
        }
        collection = project.fileTree(project.projectDir) {
            include genericFilter
        }
        soFiles.addAll(collection.files)
        return soFiles
    }

    /**
     * Get all debug SO files of project.
     *
     * @param flavorName name of flavor
     * @return list of found debug SO files.
     */
    private Vector<File> getAllDebugSoFiles(String flavorName) {
        Vector<File> soFiles = new Vector<File>()
        // Get debug SO files of dependent project.
        project.configurations.compile.getDependencies().withType(ProjectDependency).each {
            Project dProject = it.getDependencyProject()
            soFiles.addAll(getDebugSoFiles(flavorName, dProject))
        }
        // Get debug SO files of this project.
        soFiles.addAll(getDebugSoFiles(flavorName, project))
        return soFiles
    }

    /**
     * Get mapping file of project.
     *
     * @param project project
     * @param flavorName name of flavor
     * @return list of mapping files.
     */
    private Vector<File> getMappingFile(Project project, String flavorName) {
        if (null == project) {
            return null
        }        
        if (!project.hasProperty("android")) {
            return null
        }
        Vector<File> mappingFiles = new Vector<File>()
        if (project.android.hasProperty("applicationVariants")) {
            project.android.applicationVariants.all { variant ->
                if (flavorName != null && !variant.getFlavorName().equals(flavorName)) {
                    return
                }
                if (variant.name.capitalize().contains("Release")) {
                    File mappingFile = variant.getMappingFile()
                    if (null != mappingFile) {
                        String mappingFileSuffix = project.name
                        if (flavorName != null && !flavorName.isEmpty()) {
                            mappingFileSuffix += "-" + flavorName
                        }      
                        mappingFileSuffix += "-mapping.txt"
                        String mappingFileName = mappingFile.getParent() + File.separator + mappingFileSuffix
                        mappingFile.renameTo(new File(mappingFileName))
                        mappingFiles.add(new File(mappingFileName))
                    }
                }
            }
        } else if (project.android.hasProperty("libraryVariants")) {
            project.android.libraryVariants.all { variant ->
                if (flavorName != null && !variant.getFlavorName().equals(flavorName)) {
                    return
                }
                if (variant.name.capitalize().contains("Release")) {
                    File mappingFile = variant.getMappingFile()                    
                    if (null != mappingFile) {
                        String mappingFileSuffix = project.name
                        if (flavorName != null && !flavorName.isEmpty()) {
                            mappingFileSuffix += "-" + flavorName
                        }
                        mappingFileSuffix += "-mapping.txt"
                        String mappingFileName = mappingFile.getParent() + File.separator + mappingFileSuffix
                        mappingFile.renameTo(new File(mappingFileName))
                        mappingFiles.add(new File(mappingFileName))
                    }
                }
            }
        }        
        return mappingFiles
    }

    /**
     * Get all mapping files of project.
     *
     * @param project project
     * @param flavorName name of flavor
     * @return list of mapping files.
     */
    private Vector<File> getAllMappingFiles(Project project, String flavorName) {
        Vector<File> mappingFiles = new Vector<File>()
        Vector<File> projectMappingFiles = null
        // Get debug SO files of dependent project.
        project.configurations.compile.getDependencies().withType(ProjectDependency).each {
            Project dProject = it.getDependencyProject()
            projectMappingFiles = getMappingFile(dProject, null)
            if (projectMappingFiles != null) {
                mappingFiles.addAll(projectMappingFiles)
            }
        }
        // Get mapping file of this project.
        projectMappingFiles = getMappingFile(project, flavorName)        
        if (projectMappingFiles != null) {
            mappingFiles.addAll(projectMappingFiles)
        }
        return mappingFiles
    }

    /**
     * Get version name of project.
     *
     * @param variant variant of project
     * @return version name of project
     */
    private String getVersionName(Object variant) {
        String versionName = null
        if (project.android.hasProperty("applicationVariants")) {
            // Get version name by api of variant
            versionName = variant.getVersionName()
        }
        // Get version name of "defaultConfig".
        if (null == versionName || versionName.isEmpty()) {
            versionName = project.android.defaultConfig.versionName
        }
        // Get version name of "AndroidManifest.xml".
        if (null == versionName || versionName.isEmpty()) {
            versionName = new DefaultManifestParser().getVersionName(project.android.sourceSets.main.manifest.srcFile)
        }
        return versionName
    }

    /**
     * Create a task named "upload$&{variantName}SymtabFile"
     * ({@code "variantname"} means the name of variant. e.g., "Release")
     *
     * @param variant
     * @return task of gradle
     */
    private Task createUploadTask(Object variant) {

        String variantName = variant.name.capitalize()

        // Create task for uploading symtab file
        def buglyTask = project.task("upload${variantName}SymtabFile") << {

            // Check for execution
            if (false == project.bugly.execute) {
                return
            }
            UploadInfo uploadInfo = new UploadInfo()
            uploadInfo.appId = project.bugly.appId
            uploadInfo.appKey = project.bugly.appKey
            uploadInfo.packageName = variant.applicationId
            uploadInfo.version = getVersionName(variant)
            if (uploadInfo.version == null) {
                project.logger.error("Failed to get version name of project" + project.getName())
                return
            }

            // Get flavor name.
            String flovorName = variant.getFlavorName()

            // Files to upload.
            Vector<UploadFile> uploadFiles = new Vector<UploadFile>()

            // Get all mapping files.
            Vector<File> mappingFiles = getAllMappingFiles(project, flovorName)
            mappingFiles.each { mappingFile ->
                if (null != mappingFile && mappingFile.exists()) {
                    UploadFile uploadFile = new UploadFile()
                    uploadFile.file = mappingFile
                    uploadFile.isMapping = true
                    uploadFiles.add(uploadFile)
                }
            }
            // Get symbol log file.
            File symbolLogFile = getLogFile(SYMBOL_LOG_FILE_NAME)
            // Get all debug SO files.
            Vector<File> soFiles = getAllDebugSoFiles(flovorName)
            soFiles.each { file ->
                File symtabFile = null
                // Check record in log file.
                String symbolFilePath = parseLogFile(symbolLogFile, file)
                if (symbolFilePath != null) {
                    project.logger.info("The record of symtab file is found in symbol log file.")
                    if (new File(symbolFilePath).exists()) {
                        symtabFile = new File(symbolFilePath)
                    } else {
                        project.logger.warn("But the symbol file recorded does not exist.")
                    }
                }
                if (symtabFile == null) {
                    symtabFile = createSymbolFile(file.getAbsolutePath(), getOutputDirName())
                    if (symtabFile == null) {
                        project.logger.error("Failed to create symtab file.")
                        return
                    }
                    logFileWithExtraInfo(symbolLogFile, file, symtabFile.getAbsolutePath())
                }
                UploadFile uploadFile = new UploadFile()
                uploadFile.file = symtabFile
                uploadFile.isMapping = false
                uploadFiles.add(uploadFile)
            }
            // If no need to upload, just return
            if (!project.bugly.upload || uploadFiles.isEmpty()) {
                return
            }
            // Validate required configuration.
            if (project.bugly.appId == null) {
                project.logger.error("Please set your app id")
                return
            }
            if (project.bugly.appKey == null) {
                project.logger.error("Please set your app key")
                return
            }
            // Get upload log file.
            File uploadLogFile = getLogFile(UPLOAD_LOG_FILE_NAME)
            // Upload symtab files.
            uploadFiles.each { uploadFile ->
                // Check record in log file.
                if (checkLogFile(uploadLogFile, uploadFile.file)) {
                    project.logger.info("The record of symtab file is found in upload log file.")
                    return
                }
                if (uploadSymtabFile(uploadInfo, uploadFile)) {
                    logFile(uploadLogFile, uploadFile.file)
                }
            }

        }
        return buglyTask
    }

    /**
     * Entry of plugin
     *
     * @param project context of project
     */
    void apply(Project project) {
        // Set colors of log
        System.setProperty('org.gradle.color.error', 'RED')
        System.setProperty('org.gradle.color.warn', 'YELLOW')
        // Back up project
        this.project = project
        // Create extended properties of Bugly plugin.
        project.extensions.create("bugly", BuglyPluginExtension)
        if (!project.hasProperty("android")) {
            return
        }
        /**
         *  Create task and set proper stage to execute.
         *
         *  The way to set proper stage to execute is to configure the specific dependency
         *  relationship of before and after tasks. But note that the task name is somewhat
         *  different between android application and library.
         *
         */
        if (project.android.hasProperty("applicationVariants")) { // For android application.
            project.android.applicationVariants.all { variant ->
                String variantName = variant.name.capitalize()
                if (!variantName.contains("Release")) {
                    return
                }
                // Create task.
                Task buglyTask = createUploadTask(variant)
                // Set proper stage to execute.
                buglyTask.dependsOn project.tasks["package${variantName}"]
                project.tasks["assemble${variantName}"].dependsOn buglyTask
            }
        } else if (project.android.hasProperty("libraryVariants")) { // For android library.
            project.android.libraryVariants.all { variant ->
                String variantName = variant.name.capitalize()
                if (!variantName.contains("Release")) {
                    return
                }
                // Create task.
                Task buglyTask = createUploadTask(variant)
                // Set proper stage to execute.
                buglyTask.dependsOn project.tasks["package${variantName}JniLibs"]
                project.tasks["bundle${variantName}"].dependsOn buglyTask
            }
        }
    }
}
