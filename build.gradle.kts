// I plugin sono caricati qui (stesso classloader per AGP e Kotlin). Con CORE_ONLY=1 si compila
// solo il motore e il plugin Android (repository Google) non serve.
buildscript {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.21")
        classpath("org.jetbrains.kotlin:kotlin-serialization:2.0.21")
        if (System.getenv("CORE_ONLY") == null) {
            classpath("com.android.tools.build:gradle:8.7.3")
            classpath("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.0.21")
        }
    }
}
