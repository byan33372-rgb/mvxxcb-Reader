# 简阅 release 混淆规则 —— WP-M 维护（01 §4.3：Jsoup/OkHttp/serialization keep 规则由 WP-M 维护）

# ================= kotlinx-serialization =================
# 规则 JSON（03 §6）与正文块 JSON（03 §8）序列化依赖编译期生成的 serializer，须保留。
-keepattributes *Annotation*, InnerClasses, Signature
-dontnote kotlinx.serialization.**
-dontwarn kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.mvxxcb.reader.**$$serializer { *; }
-keepclassmembers class com.mvxxcb.reader.** {
    *** Companion;
}
-keepclasseswithmembers class com.mvxxcb.reader.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ================= Jsoup =================
# HTML 解析使用反射场景较少，v0 保守整体保留（体积可接受；后续按 R8 输出收紧）。
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**

# ================= OkHttp / Okio =================
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
