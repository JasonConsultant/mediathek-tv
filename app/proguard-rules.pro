# Verschleierung: Klassennamen/Methoden unlesbar machen
-repackageclasses ''
-allowaccessmodification
# Media3 / OkHttp brauchen das nicht speziell; BuildConfig-Strings bleiben Konstanten
-keepclassmembers class **.BuildConfig { *; }
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
