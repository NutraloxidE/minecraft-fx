import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    java
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group   = "com.gekiyabafx"
version = "1.0.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()

    // Paper MC 公式リポジトリ（旧）
    maven("https://repo.papermc.io/repository/maven-public/")

    // Paper MC Artifactory（新バージョンを2公開中）
    maven("https://artifactory.papermc.io/artifactory/universe")
}

dependencies {
    // ─── Paper API (サーバー側で提供されるため compileOnly) ───────────────────────
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")

    // ─── Javalin 6（内蔵Webサーバー）─────────────────────────────────────────────
    // Javalin本体（JettyおよびSLF4Jを推移的に含む）
    implementation("io.javalin:javalin:6.3.0")

    // Javalinが内部でSLF4Jを使うため、実装としてSimpleLoggerを同梱する
    implementation("org.slf4j:slf4j-simple:2.0.13")

    // ─── GSON（BigDecimalカスタムアダプター込みのJSONシリアライズ）─────────────────
    implementation("com.google.code.gson:gson:2.10.1")

    // ─── テスト（JUnit 5）────────────────────────────────────────────────────────
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.processResources {
    // plugin.yml 内の ${version} プレースホルダーをビルド時に解決する
    filesMatching("plugin.yml") {
        expand(mapOf("version" to project.version))
    }
}

tasks.named<ShadowJar>("shadowJar") {
    // 出力ファイル名を「GekiyabaFX-<version>.jar」に固定
    archiveFileName.set("GekiyabaFX-${project.version}.jar")

    // ── パッケージ再配置（リロケーション）────────────────────────────────────────
    // Paper本体がすでに同名パッケージを持つ場合の衝突を防ぐため、
    // 同梱するすべてのサードパーティライブラリを専用名前空間へ移動する。

    // Javalin 本体
    relocate("io.javalin",              "com.gekiyabafx.libs.javalin")

    // Jetty（Javalinの組み込みHTTPサーバー）
    relocate("org.eclipse.jetty",       "com.gekiyabafx.libs.jetty")

    // Jakarta Servlet API（Jettyが依存）
    relocate("jakarta.servlet",         "com.gekiyabafx.libs.jakarta.servlet")

    // SLF4J
    relocate("org.slf4j",               "com.gekiyabafx.libs.slf4j")

    // GSON
    relocate("com.google.gson",         "com.gekiyabafx.libs.gson")

    // ── 不要メタファイルの除去 ──────────────────────────────────────────────────
    exclude("META-INF/LICENSE*")
    exclude("META-INF/NOTICE*")
    exclude("META-INF/DEPENDENCIES")
    exclude("META-INF/versions/*/module-info.class")
    exclude("module-info.class")

    // Paperサーバーのクラスパスに shadowJar の出力を使わせる
    mergeServiceFiles()
}

// 通常の `jar` タスクの代わりに `shadowJar` を `build` にフックする
tasks.named("build") {
    dependsOn(tasks.named("shadowJar"))
}

// `./gradlew jar` 単体実行時は shadowJar に委譲する
tasks.named("jar") {
    finalizedBy(tasks.named("shadowJar"))
}
