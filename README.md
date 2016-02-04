##Bugly Android符号表配置

----

[TOC]

###介绍
####什么是符号表？
符号表是内存地址与函数名、文件名、行号的映射表。符号表元素如下所示：

 `<起始地址> <结束地址> <函数> [<文件名: 行号>]`

####为什么要配置符号表？
为了能快速并准确地定位用户APP发生**Crash的代码位置**，Bugly使用**符号表**对APP发生Crash的程序**堆栈**进行**解析**和**还原**。

 **举一个实例：**

![Alt text](http://bugly.qq.com/img/white-book/android5.png?v=20160203165136)

**Bugly Android提供了自动和手动两种方法配置符号表。**

###自动配置：使用Android Studio插件

**符号表文件上传插件最新版本为：1.3.6，注意插件位于[JCenter仓库](http://jcenter.bintray.com/com/tencent/bugly/symtabfileuploader/)。**

在项目的buid.gradle文件的dependencies（buildscript部分）中添加：

``` gradle
classpath 'com.tencent.bugly:symtabfileuploader:latest.release'
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
        classpath 'com.tencent.bugly:symtabfileuploader:latest.release'
    }
}
```

`其中"1.2.3"为SDK版本号，更新插件只需要修改这个版本号并Rebuild一下工程即可。`

**`<Project>/<Module>/build.gradle`**文件如下：

``` gradle
apply plugin: 'bugly'

bugly {	
	appId = "your AppId" // 注册时分配的App ID
	appKey = "your AppKey" // 注册时分配的App Key
}

```

项目应用了Bugly插件之后，Gradle会新建出一个“uploadReleaseSymtabFile”的任务，该任务被“assembleRelease”任务依赖，即在**Release**编译时执行。

uploadReleaseSymtabFile任务将执行以下动作：
1. 当项目使用了Proguard，将自动上传Proguard生成的mapping文件；
2. 当项目使用了NDK，将自动扫描Debug SO文件，生成符号表文件（zip）并自动上传。
其中查找Debug SO文件时，首先会扫描默认的NDK路径，如果未找到，将继续扫描项目路径。

### 手动配置

**`注意，手动配置主要针对使用NDK的项目，如果只使用了Proguard混淆代码，只需要直接上传Proguard生成的Mapping文件即可。`**

1. 下载最新版**[Bugly Android符号表工具](http://bugly.qq.com/sdkdown?id=99eec2a5-8aac-421d-ba7e-b487a3d9ee9f)**，其中工具包括：
	- buglySymbolAndroid`<Version>`.zip
		- 符号表工具Android版-使用指南
		- buglySymbolAndroid.bat（Bat脚本）
		- buglySymbolAndroid.sh（Shell脚本）
		- buglySymbolAndroid.jar（Jar包）


2. 定位Debug SO文件并使用Bugly Android符号表工具生成**符号表文件（xxx.zip）**。

	`定位Debug SO文件方法和工具的使用方法请参考：符号表工具Android版-使用指南`
	`注意，不同架构的SO文件需要分别生成对应的符号表文件并上传！`

3. 在Bugly的**版本管理页面（产品页面 -> 设置 -> 版本管理）**上传相应版本的符号表文件，如下图所示：

![Alt text](http://bugly.qq.com/img/white-book/android6.jpg?v=20160203165136)

---
### 符号表HTTP上传接口

Bugly提供了上传符号表的HTTP（请使用**HTTP POST**方式上传）接口。
- java 符号表接口（Mapping文件）：http://bugly.qq.com/upload/map
- native 符号表接口（Symbol文件）：http://bugly.qq.com/upload/symbol

参数说明
- pid：1
- app：App ID
- key：App Key
- bid：包名（Package Name）
- ver：版本号（需要URL Encode）
- n：符号表文件名（需要URL Encode）

Content‐Type
- 上传.txt 文件：text/plain
- 上传.zip 文件：application/zip

可以使用Curl工具来上传。

- 上传Mapping文件：

```makefile
curl --header "Content-Type: text/plain" --data-binary @mapping.txt 'http://bugly.qq.com/upload/map?pid=1&app=123456789&key=XXXXXX&bid=com.demo.test&ver=1.0.1&n=mapping.txt' --verbose
```

- 上传Symbol文件：

```makefile
curl --header "Content-Type: application/zip" --data-binary @symbol.zip 'http://bugly.qq.com/upload/symbol?pid=1&app=123456789&key=XXXXXX&bid=com.demo.test&ver=1.0.1&n=symbol.zip' --verbose
```
