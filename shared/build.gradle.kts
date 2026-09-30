dependencies {
    intellijPlatform {
        bundledModule("intellij.platform.rpc")
        compileOnly(libs.kotlin.serialization.core.jvm)
        compileOnly(libs.kotlin.serialization.json.jvm)
    }
}
