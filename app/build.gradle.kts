import de.undercouch.gradle.tasks.download.Download
import org.gradle.api.tasks.Copy
import java.io.StringReader
import java.io.StringWriter
import java.net.URI
import java.util.Properties
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory
import org.w3c.dom.NodeList
import org.xml.sax.InputSource

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.download)
}

val appVersionCode = 2339
val appRevision = "a"
val appReleaseName = "Elopteryx"

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use(localProperties::load)
}

val zohoAuthorizationServer = providers.gradleProperty("zohoAuthorizationServer").orNull
    ?: localProperties.getProperty("zohoAuthorizationServer", "")

// The provider catalog is the single source for native API and generic OAuth callbacks.
// fileContents also invalidates the configuration cache when providers.xml changes.
val oauthManifestPlaceholders = run {
    val providerXml = providers.fileContents(
        layout.projectDirectory.file("src/main/res/xml/providers.xml")
    ).asText.get()
    val factory = DocumentBuilderFactory.newInstance().apply {
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        isXIncludeAware = false
        isExpandEntityReferences = false
    }
    val document = factory.newDocumentBuilder().parse(InputSource(StringReader(providerXml)))
    val xpath = XPathFactory.newInstance().newXPath()
    fun redirectAttribute(expression: String): String {
        val attributes = xpath.evaluate(expression, document, XPathConstants.NODESET) as NodeList
        require(attributes.length == 1 && attributes.item(0).nodeValue.isNotBlank()) {
            "providers.xml must define exactly one non-empty $expression"
        }
        return attributes.item(0).nodeValue
    }
    fun callback(apiKey: String, providerId: String, debug: Boolean): Map<String, String> {
        val attribute = if (debug) "debugRedirectUri" else "redirectUri"
        val apiRedirect = redirectAttribute("/providers/apiCredentials/api[@key='$apiKey']/@$attribute")
        val imapRedirect = redirectAttribute("/providers/provider[@id='$providerId']/oauth/@$attribute")
        require(apiRedirect == imapRedirect) {
            "$apiKey API and $providerId IMAP $attribute values must match in providers.xml"
        }
        val redirect = URI(apiRedirect)
        require(!redirect.scheme.isNullOrBlank() && !redirect.path.isNullOrBlank() &&
            redirect.rawUserInfo == null && redirect.port == -1 &&
            redirect.rawQuery == null && redirect.rawFragment == null) {
            "$apiKey $attribute must contain only a scheme, optional host and non-empty path"
        }
        val prefix = if (apiKey == "graph") "microsoft" else "gmail"
        val values = mutableMapOf(
            "${prefix}RedirectScheme" to redirect.scheme,
            // Android matches decoded paths; leave the OAuth redirect encoded in the XML.
            "${prefix}RedirectPath" to redirect.path
        )
        if (apiKey == "graph") {
            require(!redirect.host.isNullOrBlank()) { "Graph $attribute must have a host" }
            values["${prefix}RedirectHost"] = redirect.host
        } else {
            require(redirect.rawAuthority == null) { "Gmail $attribute must not have a host" }
        }
        return values
    }
    fun zohoCallback(debug: Boolean): Map<String, String> {
        val api = "/providers/apiCredentials/api[@key='zoho']"
        // Match ApiCredentials: a missing/blank Debug override uses the Release URI.
        val debugAttribute = "$api/@debugRedirectUri[normalize-space(.) != '']"
        val attribute = if (debug && xpath.evaluate(debugAttribute, document).isNotBlank())
            "debugRedirectUri" else "redirectUri"
        val redirect = URI(redirectAttribute("$api/@$attribute"))
        require(!redirect.scheme.isNullOrBlank() && !redirect.host.isNullOrBlank() &&
            redirect.path.isNullOrEmpty() && redirect.rawUserInfo == null && redirect.port == -1 &&
            redirect.rawQuery == null && redirect.rawFragment == null) {
            "Zoho $attribute must contain only a scheme and host"
        }
        return mapOf(
            "zohoRedirectScheme" to redirect.scheme,
            "zohoRedirectHost" to redirect.host
        )
    }
    listOf("release", "debug").associateWith { variant ->
        callback("graph", "microsoft", variant == "debug") +
            callback("gmail", "gmail", variant == "debug") +
            zohoCallback(variant == "debug")
    }
}

abstract class GenerateApiCredentialResources : DefaultTask() {
    @get:Input
    abstract val authorizationServer: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val providersFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val output = outputDirectory.file("api-credentials-overrides.properties").get().asFile
        output.parentFile.mkdirs()
        val xml = outputDirectory.file("xml/providers.xml").get().asFile
        xml.parentFile.mkdirs()
        providersFile.get().asFile.copyTo(xml, overwrite = true)
        // Properties.store preserves escaping without exposing the value in task output.
        val overrides = Properties()
        if (authorizationServer.get().isNotEmpty())
            overrides.setProperty("zohoAuthorizationServer", authorizationServer.get())
        val encoded = StringWriter().also { overrides.store(it, null) }.toString()
        // Omit Properties.store's timestamp so clean builds produce identical resources.
        output.writeText(encoded.lineSequence().filterNot { it.startsWith("#") }
            .joinToString("\n"), Charsets.UTF_8)
    }
}

val generateApiCredentialResources = tasks.register<GenerateApiCredentialResources>("generateApiCredentialResources") {
    authorizationServer.set(zohoAuthorizationServer)
    providersFile.set(layout.projectDirectory.file("src/main/res/xml/providers.xml"))
    outputDirectory.set(layout.buildDirectory.dir("generated/apiCredentials"))
}

android {
    namespace = "in.anoopbhatia.mail"

    defaultConfig {
        applicationId = "in.anoopbhatia.mail"
        compileSdk = 37
        // Network-specific roaming classification requires NET_CAPABILITY_NOT_ROAMING.
        minSdk = 28
        targetSdk = 37
        versionCode = appVersionCode
        versionName = "1.$appVersionCode"

        manifestPlaceholders["namespace"] = "in.anoopbhatia.mail"
        manifestPlaceholders["largeHeap"] = false
        manifestPlaceholders["build_uuid"] = UUID.nameUUIDFromBytes(("release$appVersionCode$appRevision").toByteArray()).toString()

        externalNativeBuild {
            cmake {
                arguments += "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,--build-id=none"
            }
        }
        buildConfigField("String", "OPENAI_ENDPOINT", "\"https://api.openai.com/v1/\"")
        buildConfigField("String", "OPENAI_PRIVACY", "\"https://openai.com/policies/privacy-policy\"")
        buildConfigField("String", "GEMINI_ENDPOINT", "\"https://generativelanguage.googleapis.com/v1beta/\"")
        buildConfigField("String", "GEMINI_PRIVACY", "\"https://support.google.com/gemini/answer/13594961\"")
        buildConfigField("String", "PWNED_ENDPOINT", "\"https://api.pwnedpasswords.com/\"")
        buildConfigField("String", "PWNED_URI", "\"https://haveibeenpwned.com/\"")

        buildConfigField("String", "REVISION", "\"$appRevision\"")
        buildConfigField("String", "RELEASE_NAME", "\"$appReleaseName\"")

        javaCompileOptions {
            annotationProcessorOptions {
                arguments["room.schemaLocation"] = "$projectDir/schemas"
            }
        }

        vectorDrawables.useSupportLibrary = true

        externalNativeBuild {
            cmake {
                cFlags += "-ffile-prefix-map=${rootDir}=."
                cppFlags += "-ffile-prefix-map=${rootDir}=."
            }
        }

        ndkVersion = "30.0.16248370"
        ndk {
            abiFilters += listOf("armeabi-v7a", "x86", "arm64-v8a", "x86_64")
        }
    }

    base {
        archivesName.set("Mail-v1.$appVersionCode$appRevision")
    }

    sourceSets {
        // Build-type fallbacks select library variants, not application source files.
        maybeCreate("large").java.directories.add("src/release/java")
        getByName("main") {
            res.directories.add("src/main/resExtra")
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
        disable += "MissingTranslation"
        fatal += setOf("StringFormatInvalid", "StringFormatMatches", "StringFormatCount")
        // Keep the existing format gate and also report deprecated Android APIs.
        checkOnly += setOf("StringFormatInvalid", "StringFormatMatches", "StringFormatCount", "Deprecated", "DeprecatedSinceApi")
        enable += setOf("Deprecated", "DeprecatedSinceApi")
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }

    java {
        toolchain {
            languageVersion = JavaLanguageVersion.of(25)
        }
    }

    kotlin {
        jvmToolchain(25)
    }

    externalNativeBuild {
        cmake {
            version = "4.4.4"
            path = file("CMakeLists.txt")
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        dex {
            // Keep APK downloads small; minSdk >= 28 otherwise defaults to uncompressed DEX.
            useLegacyPackaging = true
        }
        jniLibs {
            useLegacyPackaging = true
            excludes += "org/apache/**"
        }
        resources {
            pickFirsts += listOf(
                "META-INF/mimetypes.default",
                "META-INF/mailcap.default"
            )
            excludes += listOf(
                "LICENSES",
                "META-INF/LICENSE.md",
                "META-INF/NOTICE.md",
                "META-INF/LICENSE.txt",
                "META-INF/README.md",
                "META-INF/CHANGES",
                "META-INF/jersey-module-version",
                "META-INF/COPYRIGHT.html",
                "META-INF/LICENSE.gpl.txt",
                "META-INF/LICENSE.commercial.txt",
                "LICENSE-2.0.txt",
                "RELEASE.txt",
                "DebugProbesKt.bin",
                "font_metrics.properties"
            )
        }
    }

    val releaseSigningConfig = try {
        signingConfigs.create("release") {
            val configuredStoreFile = file(project.property("storeFilePath") as String)
            require(configuredStoreFile.isFile) { "Release keystore file is unavailable" }
            storeFile = configuredStoreFile
            keyAlias = project.property("keyAlias") as String
            storePassword = project.property("storePassword") as String
            keyPassword = project.property("storePassword") as String
            enableV1Signing = true
            enableV2Signing = true
        }
    } catch (_: Exception) {
        null
    }

    buildFeatures {
        aidl = true
        buildConfig = true
        compose = false
        prefab = false
        resValues = false
        shaders = false
        viewBinding = false
        dataBinding = false
    }

    buildTypes {
        getByName("release") {
            manifestPlaceholders.putAll(oauthManifestPlaceholders.getValue("release"))
            isDebuggable = false
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = releaseSigningConfig
            buildConfigField("String", "TX_URI", "\"\"")
            buildConfigField("String", "GPA_URI", "\"\"")
            buildConfigField("String", "INFO_URI", "\"\"")
            buildConfigField("String", "DEV_DOMAIN", "\"\"")
        }
        create("large") {
            initWith(getByName("release"))
            matchingFallbacks += "release"
            manifestPlaceholders["largeHeap"] = true
            manifestPlaceholders["build_uuid"] = UUID.nameUUIDFromBytes(("large$appVersionCode$appRevision").toByteArray()).toString()
        }
        getByName("debug") {
            applicationIdSuffix = ".debug"
            isDebuggable = true
            isMinifyEnabled = false
            manifestPlaceholders.putAll(oauthManifestPlaceholders.getValue("debug"))
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Use repository signing credentials, or null rather than the default debug key.
            signingConfig = releaseSigningConfig
            buildConfigField("String", "TX_URI", localProperties.getProperty("paypal.uri", "\"\""))
            buildConfigField("String", "GPA_URI", localProperties.getProperty("gpa.uri", "\"\""))
            buildConfigField("String", "INFO_URI", localProperties.getProperty("info.uri", "\"\""))
            buildConfigField("String", "DEV_DOMAIN", localProperties.getProperty("dev.domain", "\"\""))
        }
    }


}

tasks.register<Copy>("copyMarkdown") {
    from(rootDir)
    into("src/main/assets")
    include("SETUP.md", "ATTRIBUTION.md")
}

tasks.register<Download>("downloadPSL") {
    src("https://raw.githubusercontent.com/publicsuffix/list/master/public_suffix_list.dat")
    dest("src/main/assets")
    overwrite(true)
}

tasks.register<Download>("downloadBraveDebounce") {
    src("https://raw.githubusercontent.com/brave/adblock-lists/master/brave-lists/debounce.json")
    dest("src/main/assets")
    overwrite(true)
}

tasks.register("downloadBrave") {
    dependsOn("downloadBraveDebounce")
}

tasks.register<Download>("downloadSmime") {
    src("https://ccadb.my.salesforce-sites.com/mozilla/IncludedRootsPEMTxt?TrustBitsInclude=Email")
    dest(rootProject.file("app/src/main/assets/smime/MozillaCA.pem"))
    overwrite(true)
}

configurations.configureEach {
    exclude(group = "androidx.lifecycle", module = "lifecycle-livedata")
    exclude(group = "androidx.lifecycle", module = "lifecycle-livedata-core")

    exclude(group = "androidx.emoji2", module = "emoji2")

    exclude(group = "com.atlassian.commonmark", module = "commonmark")

}

configurations.configureEach {
    resolutionStrategy.eachDependency {
        when {
            requested.group == "androidx.room" -> useVersion(libs.versions.room.get())
            requested.group == "androidx.lifecycle" -> useVersion(libs.versions.lifecycle.get())
            requested.group == "org.apache.poi" -> useVersion(libs.versions.apachePoi.get())
            requested.group == "org.simplejavamail" -> useVersion(libs.versions.outlookParser.get())
            requested.group == "com.google.flatbuffers" -> useVersion(libs.versions.flatbuffers.get())
        }
    }
}

dependencies {

    coreLibraryDesugaring(libs.android.tools.desugar.jdk.libs.nio)

    implementation(libs.androidx.startup.runtime)

    implementation(libs.androidx.annotation.experimental)

    implementation(libs.androidx.core)

    implementation(libs.androidx.core.remoteviews)

    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.window.java)

    implementation(libs.androidx.emoji2)
    implementation(libs.com.google.flatbuffers.java)

    implementation(libs.androidx.webkit)

    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.viewpager2)

    implementation(libs.androidx.coordinatorlayout)

    implementation(libs.androidx.constraintlayout)

    implementation(libs.com.google.android.material)

    implementation(libs.androidx.browser)

    implementation(libs.androidx.swiperefreshlayout)

    implementation(libs.androidx.documentfile)

    // Locally maintained LiveData uses ArchTaskExecutor directly.
    implementation(libs.androidx.arch.core.runtime)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.livedata)
    implementation(libs.androidx.lifecycle.livedata.core)
    implementation(libs.androidx.lifecycle.viewmodel.savedstate)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.service)

    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.room.paging)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.sqlite)
    implementation(libs.androidx.sqlite.framework)
    annotationProcessor(libs.androidx.room.compiler)
    annotationProcessor(libs.org.jetbrains.kotlin.kotlin.metadata.jvm)

    implementation(libs.androidx.preference) {
        exclude(group = "androidx.fragment", module = "fragment-ktx")
    }

    implementation(libs.androidx.work.runtime)
    implementation(libs.com.google.guava)

    implementation(libs.androidx.exifinterface)

    implementation(libs.androidx.biometric)
    implementation(libs.com.google.android.gms.play.services.basement)
    implementation(libs.com.google.android.gms.play.services.auth)

    implementation(libs.com.appmattus.certificatetransparency.android)

    implementation(libs.com.squareup.okhttp3)

    implementation(libs.org.jsoup)

    implementation(libs.com.jayway.jsonpath)

    implementation(libs.net.sourceforge.cssparser)

    // Still required for org.w3c.dom.css/stylesheets, which Android does not supply.
    implementation(libs.org.w3c.dom)

    implementation(libs.org.minidns.hla)

    implementation(project(":openpgp-api"))

    implementation(libs.me.leolin.shortcutbadger)

    implementation(libs.net.sf.biweekly) {
        exclude(group = "com.fasterxml.jackson.core", module = "jackson-core")
        exclude(group = "com.fasterxml.jackson.core", module = "jackson-databind")
    }

    implementation(libs.com.googlecode.ez.vcard) {
        exclude(group = "com.fasterxml.jackson.core", module = "jackson-core")
        exclude(group = "com.fasterxml.jackson.core", module = "jackson-databind")
    }

    implementation(libs.io.noties.markwon.core)
    implementation(libs.io.noties.markwon.html)
    implementation(libs.io.noties.markwon.editor)

    implementation(libs.org.commonmark.commonmark)
    implementation(libs.org.commonmark.ext.ins)
    implementation(libs.org.commonmark.ext.task.list.items)
    implementation(libs.org.commonmark.ext.gfm.tables)
    implementation(libs.org.commonmark.ext.gfm.strikethrough)

    implementation(libs.com.vladsch.flexmark.html2md.converter)

    implementation(project(":colorpicker"))

    implementation(libs.io.github.everythingme.overscroll.decor.android)

    implementation(libs.org.bouncycastle.bcpkix.jdk15to18)
    implementation(libs.org.bouncycastle.bctls.jdk15to18)

    implementation(libs.org.bouncycastle.bcprov.jdk15to18)
    implementation(libs.org.bouncycastle.bcutil.jdk15to18)

    implementation(libs.net.openid.appauth)

    implementation(libs.net.freeutils.jcharset)

    implementation(libs.org.apache.poi.scratchpad)

    implementation(libs.org.simplejavamail.outlook.message.parser) {
        exclude(group = "com.sun.activation", module = "jakarta.activation")
    }

    implementation(libs.org.reactivestreams)
    implementation(libs.io.reactivex.rxjava)

    implementation(libs.com.caverock.androidsvg)

    implementation(libs.org.apache.commons.compress)

    implementation(libs.com.github.seancfoley.ipaddress)

    debugImplementation(libs.com.squareup.leakcanary.android)

    implementation(libs.com.neovisionaries.nv.websocket.client)

    implementation(libs.org.tinylog.api)
    implementation(libs.org.tinylog.impl)

    implementation(libs.com.google.zxing.core)

    implementation(libs.com.ezylang.evalex)

    implementation(libs.com.vanniktech.android.image.cropper)

    implementation(libs.androidx.heifwriter)

    // Android provides XmlPullParser at runtime; use its host implementation in JVM tests.
    testImplementation(libs.net.sf.kxml)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.org.xerial.sqlite.jdbc)
}

// Package the same source as plain XML for the typed Java loader. Android's
// res/xml copy is compiled binary XML, not a plain XML input stream.
androidComponents.onVariants { variant ->
    variant.sources.resources?.addGeneratedSourceDirectory(
        generateApiCredentialResources, GenerateApiCredentialResources::outputDirectory
    )
}

// Javac covers Java/library deprecations that Android lint does not diagnose.
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:deprecation")
}
