# BuglyPlugin
**插件位于[JCenter仓库](http://jcenter.bintray.com/com/tencent/bugly/symtabfileuploader/)。**

在项目的buid.gradle文件的dependencies（buildscript部分）中添加：
``` gradle
classpath 'com.tencent.bugly:symtabfileuploader:<version>'
```

`其中version为符号表文件上传插件版本号`

在module的buid.gradle文件的顶部添加：
``` gradle
apply plugin: 'bugly'

bugly {
	appId = '<App ID>'
	appKey = '<App Key>'
}
```
`其中appId和appKey是必填的，App ID和App Key可以从“设置->产品信息里面获取。”`

除了appId和appKey之外，还可以设置其他属性，属性列表如下：

| 属 性 | 值 | 说 明 |
| :-- | :-- | :-- |
| appId | String | App ID |
| appKey | String | App Key |
| execute | boolean | 插件开关 |
| upload | boolean | 上传开关 |
| outputDir | String | 符号表文件输出路径 |

**`<Project>/build.gradle`**文件如下：
``` gradle
buildscript {
    repositories {
        jcenter()
    }
    dependencies {
        classpath 'com.tencent.bugly:symtabfileuploader:1.2.3'
    }
}
```
`其中"1.2.3"为SDK版本号，更新插件只需要修改这个版本号并Rebuild一下工程即可。`

**`<Project>/<Module>/build.gradle`**文件如下：
``` gradle
apply plugin: 'bugly'

bugly {	
    appId = '900000000'
    appKey = 'abcdefghijklmn'
}

```

项目应用了Bugly插件之后，Gradle会新建出一个“uploadReleaseSymtabFile”的任务，该任务被“assembleRelease”任务依赖，即在**Release**编译时执行。

uploadReleaseSymtabFile任务将执行以下动作：
1. 当项目使用了Proguard，将自动上传Proguard生成的mapping文件；
2. 当项目使用了NDK，将自动扫描Debug SO文件，生成符号表文件（zip）并自动上传。
其中查找Debug SO文件时，首先会扫描默认的NDK路径，如果未找到，将继续扫描项目路径。
