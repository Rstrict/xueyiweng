## 默认不混淆。若开启 minify，保留 Kotlin 元数据与序列化模型。
-keep class com.xueyiweng.app.data.** { *; }
-keepattributes *Annotation*, InnerClasses, Signature
-dontwarn org.jetbrains.annotations.**
