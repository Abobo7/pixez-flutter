import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationParameters
import com.android.build.api.instrumentation.InstrumentationScope
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("dev.flutter.flutter-gradle-plugin")
}

var dartEnvironmentVariables = mutableMapOf(
    "IS_GOOGLEPLAY" to false
)

if (project.hasProperty("dart-defines")) {
    dartEnvironmentVariables.putAll(
        (project.property("dart-defines") as String)
            .split(',')
            .associate { entry ->
                val pair = String(Base64.getDecoder().decode(entry)).split('=')
                pair.first() to (pair.last() == "true")
            }
    )
}

val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("key.properties")
if (keystorePropertiesFile.exists()) {
    keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
}

val isGooglePlay = dartEnvironmentVariables["IS_GOOGLEPLAY"] as Boolean
val packageName = if (isGooglePlay) "com.perol.play.pixez" else "com.perol.pixez"
val kitkatMinSdk = 19

android {
    namespace = "com.perol.pixez"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    val isBuildingBundle = gradle.startParameter.taskNames.any { it.lowercase().contains("bundle") }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = packageName
        minSdk = kitkatMinSdk
        targetSdk = 33
        multiDexEnabled = true
        versionCode = 10010150
        versionName = "0.9.114-kitkat"
        buildConfigField("boolean", "IS_GOOGLEPLAY", isGooglePlay.toString())
        vectorDrawables {
            useSupportLibrary = true
        }
        ndk {
            abiFilters.addAll(arrayOf("armeabi-v7a"))
        }
    }

    splits {
        abi {
            isEnable = !isBuildingBundle
            reset()
            include("armeabi-v7a")
            isUniversalApk = true
        }
    }

    signingConfigs {
        getByName("debug") {
            enableV1Signing = true
            enableV2Signing = true
        }
        if (keystorePropertiesFile.exists()) {
            create("release") {
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
                storeFile = keystoreProperties["storeFile"]?.let { file(it) }
                storePassword = keystoreProperties["storePassword"] as String
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("debug")
        }
        getByName("release") {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = if (keystorePropertiesFile.exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    if (isGooglePlay) {
        sourceSets {
            getByName("release") {
                manifest.srcFile("src/googlePlayRelease/AndroidManifest.xml")
            }
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    packaging {
        if (!isBuildingBundle) {
            jniLibs {
                useLegacyPackaging = true
            }
        }
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt"
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

flutter {
    source = "../.."
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
    implementation("androidx.multidex:multidex:2.0.1")
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    implementation("com.squareup.okhttp3:okhttp:3.12.13")

    implementation(project(":androidndkgif"))
    implementation("androidx.core:core:1.12.0")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.core:core-remoteviews:1.1.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.annotation:annotation:1.8.2")
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("com.google.android.material:material:1.6.1")
    implementation("androidx.browser:browser:1.5.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
}

/* ============================================================================
 * 1. ASM Bytecode Instrumentation (Patches Flutter 3.47 Embedding & Plugins for API 19)
 * ============================================================================ */
abstract class KitKatBytecodePatcher : AsmClassVisitorFactory<InstrumentationParameters.None> {
    override fun createClassVisitor(
        classContext: ClassContext,
        nextClassVisitor: ClassVisitor
    ): ClassVisitor {
        val className = classContext.currentClassData.className.replace('.', '/')
        if (className.startsWith("com/perol/pixez/kitkat/")) {
            return nextClassVisitor
        }
        return KitKatClassVisitor(className, nextClassVisitor)
    }

    override fun isInstrumentable(classData: ClassData): Boolean {
        return !classData.className.startsWith("com.perol.pixez.kitkat.")
    }
}

class KitKatClassVisitor(
    private val currentClass: String,
    next: ClassVisitor
) : ClassVisitor(Opcodes.ASM9, next) {

    companion object {
        private const val HOOKS = "com/perol/pixez/kitkat/KitKatRuntimeHooks"
        private val CONTEXT_OWNERS = setOf(
            "android/content/Context",
            "android/content/ContextWrapper",
            "android/app/Activity",
            "android/app/Application",
            "android/app/Service",
            "androidx/fragment/app/FragmentActivity",
            "androidx/appcompat/app/AppCompatActivity",
            "androidx/activity/ComponentActivity",
            "io/flutter/embedding/android/FlutterActivity",
            "io/flutter/embedding/android/FlutterFragmentActivity",
            "com/perol/pixez/MainActivity"
        )
    }

    override fun visitMethod(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        exceptions: Array<out String>?
    ): MethodVisitor {
        val mv = super.visitMethod(access, name, descriptor, signature, exceptions)

        // Replace FlutterJNI.computePlatformResolvedLocale directly with API-19-safe helper
        if (currentClass == "io/flutter/embedding/engine/FlutterJNI" &&
            name == "computePlatformResolvedLocale" &&
            descriptor == "([Ljava/lang/String;)[Ljava/lang/String;"
        ) {
            return object : MethodVisitor(Opcodes.ASM9, null) {
                override fun visitCode() {
                    mv.visitCode()
                    val argSlot = if ((access and Opcodes.ACC_STATIC) != 0) 0 else 1
                    mv.visitVarInsn(Opcodes.ALOAD, argSlot)
                    mv.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        HOOKS,
                        "computePlatformResolvedLocale",
                        "([Ljava/lang/String;)[Ljava/lang/String;",
                        false
                    )
                    mv.visitInsn(Opcodes.ARETURN)
                    mv.visitMaxs(2, argSlot + 1)
                    mv.visitEnd()
                }
            }
        }

        // Replace LocalizationPlugin.localeFromString directly with API-19-safe helper
        if (currentClass == "io/flutter/plugin/localization/LocalizationPlugin" &&
            name == "localeFromString" &&
            descriptor == "(Ljava/lang/String;)Ljava/util/Locale;"
        ) {
            return object : MethodVisitor(Opcodes.ASM9, null) {
                override fun visitCode() {
                    mv.visitCode()
                    mv.visitVarInsn(Opcodes.ALOAD, 0)
                    mv.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        HOOKS,
                        "localeFromString",
                        "(Ljava/lang/String;)Ljava/util/Locale;",
                        false
                    )
                    mv.visitInsn(Opcodes.ARETURN)
                    mv.visitMaxs(1, 1)
                    mv.visitEnd()
                }
            }
        }

        // Replace LocalizationPlugin.resolveNativeLocale with straight-line API-19-safe helper
        if (currentClass == "io/flutter/plugin/localization/LocalizationPlugin" &&
            name == "resolveNativeLocale" &&
            descriptor == "(Ljava/util/List;)Ljava/util/Locale;"
        ) {
            return object : MethodVisitor(Opcodes.ASM9, null) {
                override fun visitCode() {
                    mv.visitCode()
                    mv.visitVarInsn(Opcodes.ALOAD, 0)
                    mv.visitFieldInsn(
                        Opcodes.GETFIELD,
                        "io/flutter/plugin/localization/LocalizationPlugin",
                        "context",
                        "Landroid/content/Context;"
                    )
                    mv.visitVarInsn(Opcodes.ALOAD, 1)
                    mv.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        HOOKS,
                        "resolveNativeLocale",
                        "(Landroid/content/Context;Ljava/util/List;)Ljava/util/Locale;",
                        false
                    )
                    mv.visitInsn(Opcodes.ARETURN)
                    mv.visitMaxs(2, 2)
                    mv.visitEnd()
                }
            }
        }

        // Replace LocalizationPlugin.sendLocalesToFlutter with straight-line API-19-safe helper
        if (currentClass == "io/flutter/plugin/localization/LocalizationPlugin" &&
            name == "sendLocalesToFlutter" &&
            descriptor == "(Landroid/content/res/Configuration;)V"
        ) {
            return object : MethodVisitor(Opcodes.ASM9, null) {
                override fun visitCode() {
                    mv.visitCode()
                    mv.visitVarInsn(Opcodes.ALOAD, 0)
                    mv.visitFieldInsn(
                        Opcodes.GETFIELD,
                        "io/flutter/plugin/localization/LocalizationPlugin",
                        "localizationChannel",
                        "Lio/flutter/embedding/engine/systemchannels/LocalizationChannel;"
                    )
                    mv.visitVarInsn(Opcodes.ALOAD, 1)
                    mv.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        HOOKS,
                        "getConfigurationLocales",
                        "(Landroid/content/res/Configuration;)Ljava/util/List;",
                        false
                    )
                    mv.visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL,
                        "io/flutter/embedding/engine/systemchannels/LocalizationChannel",
                        "sendLocales",
                        "(Ljava/util/List;)V",
                        false
                    )
                    mv.visitInsn(Opcodes.RETURN)
                    mv.visitMaxs(2, 2)
                    mv.visitEnd()
                }
            }
        }

        // Replace PlatformPlugin.setSystemChromeApplicationSwitcherDescription (API 21 TaskDescription)
        if (currentClass == "io/flutter/plugin/platform/PlatformPlugin" &&
            name == "setSystemChromeApplicationSwitcherDescription"
        ) {
            return object : MethodVisitor(Opcodes.ASM9, null) {
                override fun visitCode() {
                    mv.visitCode()
                    mv.visitInsn(Opcodes.RETURN)
                    mv.visitMaxs(0, 2)
                    mv.visitEnd()
                }
            }
        }

        // Replace InputConnectionAdaptor.getCursorAnchorInfo (API 21 CursorAnchorInfo)
        if (currentClass == "io/flutter/plugin/editing/InputConnectionAdaptor" &&
            name == "getCursorAnchorInfo"
        ) {
            return object : MethodVisitor(Opcodes.ASM9, null) {
                override fun visitCode() {
                    mv.visitCode()
                    mv.visitInsn(Opcodes.ACONST_NULL)
                    mv.visitInsn(Opcodes.ARETURN)
                    mv.visitMaxs(1, 1)
                    mv.visitEnd()
                }
            }
        }

        return object : MethodVisitor(Opcodes.ASM9, mv) {
            override fun visitTypeInsn(opcode: Int, type: String) {
                if (opcode == Opcodes.NEW &&
                    type == "android/view/accessibility/AccessibilityNodeInfo\$AccessibilityAction"
                ) {
                    super.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        HOOKS,
                        "newActionHolder",
                        "()[Ljava/lang/Object;",
                        false
                    )
                    return
                }
                super.visitTypeInsn(opcode, type)
            }

            override fun visitFieldInsn(opcode: Int, owner: String, fName: String, fDesc: String) {
                if (opcode == Opcodes.GETSTATIC && owner == "android/os/Build" &&
                    fName == "SUPPORTED_ABIS" && fDesc == "[Ljava/lang/String;"
                ) {
                    super.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        HOOKS,
                        "getSupportedAbis",
                        "()[Ljava/lang/String;",
                        false
                    )
                    return
                }
                if (opcode == Opcodes.GETFIELD && owner == "android/content/pm/ApplicationInfo" &&
                    fName == "splitSourceDirs" && fDesc == "[Ljava/lang/String;"
                ) {
                    super.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        HOOKS,
                        "getSplitSourceDirs",
                        "(Landroid/content/pm/ApplicationInfo;)[Ljava/lang/String;",
                        false
                    )
                    return
                }
                if (opcode == Opcodes.GETFIELD && owner == "android/view/WindowManager\$LayoutParams" &&
                    fName == "layoutInDisplayCutoutMode" && fDesc == "I"
                ) {
                    super.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        HOOKS,
                        "getLayoutInDisplayCutoutMode",
                        "(Landroid/view/WindowManager\$LayoutParams;)I",
                        false
                    )
                    return
                }
                super.visitFieldInsn(opcode, owner, fName, fDesc)
            }

            override fun visitMethodInsn(
                opcode: Int,
                owner: String,
                mName: String,
                mDesc: String,
                isInterface: Boolean
            ) {
                if (opcode == Opcodes.INVOKEVIRTUAL &&
                    mName == "requestUnbufferedDispatch" &&
                    mDesc == "(Landroid/view/MotionEvent;)V"
                ) {
                    super.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        HOOKS,
                        "requestUnbufferedDispatch",
                        "(Landroid/view/View;Landroid/view/MotionEvent;)V",
                        false
                    )
                    return
                }
                if (opcode == Opcodes.INVOKEVIRTUAL &&
                    mName == "setAccessibilityPaneTitle" &&
                    mDesc == "(Ljava/lang/CharSequence;)V" &&
                    (owner == "android/view/View" || owner.startsWith("io/flutter/"))
                ) {
                    super.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        HOOKS,
                        "setAccessibilityPaneTitle",
                        "(Landroid/view/View;Ljava/lang/CharSequence;)V",
                        false
                    )
                    return
                }
                if (opcode == Opcodes.INVOKESPECIAL &&
                    owner == "android/view/accessibility/AccessibilityNodeInfo\$AccessibilityAction" &&
                    mName == "<init>" &&
                    mDesc == "(ILjava/lang/CharSequence;)V"
                ) {
                    super.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        HOOKS,
                        "initActionHolder",
                        "([Ljava/lang/Object;ILjava/lang/CharSequence;)V",
                        false
                    )
                    return
                }
                if (opcode == Opcodes.INVOKEVIRTUAL &&
                    owner == "android/view/accessibility/AccessibilityNodeInfo" &&
                    mName == "addAction" &&
                    mDesc == "(Landroid/view/accessibility/AccessibilityNodeInfo\$AccessibilityAction;)V"
                ) {
                    super.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        HOOKS,
                        "addAccessibilityActionSafe",
                        "(Landroid/view/accessibility/AccessibilityNodeInfo;Ljava/lang/Object;)V",
                        false
                    )
                    return
                }
                if (opcode == Opcodes.INVOKEVIRTUAL &&
                    owner == "android/content/res/Resources" &&
                    mName == "getColor" &&
                    mDesc == "(ILandroid/content/res/Resources\$Theme;)I"
                ) {
                    super.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        HOOKS,
                        "getColor",
                        "(Landroid/content/res/Resources;ILandroid/content/res/Resources\$Theme;)I",
                        false
                    )
                    return
                }
                if (opcode == Opcodes.INVOKEVIRTUAL && owner in CONTEXT_OWNERS) {
                    when {
                        mName == "getDataDir" && mDesc == "()Ljava/io/File;" -> {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "getDataDir", "(Landroid/content/Context;)Ljava/io/File;", false)
                            return
                        }
                        mName == "getCodeCacheDir" && mDesc == "()Ljava/io/File;" -> {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "getCodeCacheDir", "(Landroid/content/Context;)Ljava/io/File;", false)
                            return
                        }
                        mName == "getNoBackupFilesDir" && mDesc == "()Ljava/io/File;" -> {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "getNoBackupFilesDir", "(Landroid/content/Context;)Ljava/io/File;", false)
                            return
                        }
                        mName == "getExternalMediaDirs" && mDesc == "()[Ljava/io/File;" -> {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "getExternalMediaDirs", "(Landroid/content/Context;)[Ljava/io/File;", false)
                            return
                        }
                        mName == "getSystemService" && mDesc == "(Ljava/lang/Class;)Ljava/lang/Object;" -> {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "getSystemService", "(Landroid/content/Context;Ljava/lang/Class;)Ljava/lang/Object;", false)
                            return
                        }
                        mName == "checkSelfPermission" && mDesc == "(Ljava/lang/String;)I" -> {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "checkSelfPermission", "(Landroid/content/Context;Ljava/lang/String;)I", false)
                            return
                        }
                        mName == "getMainExecutor" && mDesc == "()Ljava/util/concurrent/Executor;" -> {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "getMainExecutor", "(Landroid/content/Context;)Ljava/util/concurrent/Executor;", false)
                            return
                        }
                    }
                }
                if (owner == "java/util/Locale") {
                    when {
                        opcode == Opcodes.INVOKEVIRTUAL && mName == "getScript" && mDesc == "()Ljava/lang/String;" -> {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "getScript", "(Ljava/util/Locale;)Ljava/lang/String;", false)
                            return
                        }
                        opcode == Opcodes.INVOKEVIRTUAL && mName == "toLanguageTag" && mDesc == "()Ljava/lang/String;" -> {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "toLanguageTag", "(Ljava/util/Locale;)Ljava/lang/String;", false)
                            return
                        }
                        opcode == Opcodes.INVOKESTATIC && mName == "forLanguageTag" && mDesc == "(Ljava/lang/String;)Ljava/util/Locale;" -> {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "forLanguageTag", "(Ljava/lang/String;)Ljava/util/Locale;", false)
                            return
                        }
                    }
                }
                if (opcode == Opcodes.INVOKEVIRTUAL && owner == "android/view/Window") {
                    when {
                        mName == "setStatusBarColor" && mDesc == "(I)V" -> {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "setStatusBarColor", "(Landroid/view/Window;I)V", false)
                            return
                        }
                        mName == "setNavigationBarColor" && mDesc == "(I)V" -> {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "setNavigationBarColor", "(Landroid/view/Window;I)V", false)
                            return
                        }
                        mName == "getStatusBarColor" && mDesc == "()I" -> {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "getStatusBarColor", "(Landroid/view/Window;)I", false)
                            return
                        }
                        mName == "getNavigationBarColor" && mDesc == "()I" -> {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "getNavigationBarColor", "(Landroid/view/Window;)I", false)
                            return
                        }
                    }
                }
                if (opcode == Opcodes.INVOKEVIRTUAL && owner == "android/graphics/SurfaceTexture" &&
                    mName == "setOnFrameAvailableListener" &&
                    mDesc == "(Landroid/graphics/SurfaceTexture\$OnFrameAvailableListener;Landroid/os/Handler;)V"
                ) {
                    super.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        HOOKS,
                        "setOnFrameAvailableListener",
                        "(Landroid/graphics/SurfaceTexture;Landroid/graphics/SurfaceTexture\$OnFrameAvailableListener;Landroid/os/Handler;)V",
                        false
                    )
                    return
                }
                if (opcode == Opcodes.INVOKEVIRTUAL && owner == "android/view/accessibility/AccessibilityNodeInfo") {
                    when {
                        mName == "setImportantForAccessibility" && mDesc == "(Z)V" -> {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "setImportantForAccessibility", "(Landroid/view/accessibility/AccessibilityNodeInfo;Z)V", false)
                            return
                        }
                        mName == "setTraversalAfter" && mDesc == "(Landroid/view/View;I)V" -> {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "setTraversalAfter", "(Landroid/view/accessibility/AccessibilityNodeInfo;Landroid/view/View;I)V", false)
                            return
                        }
                        mName == "setMaxTextLength" && mDesc == "(I)V" -> {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "setMaxTextLength", "(Landroid/view/accessibility/AccessibilityNodeInfo;I)V", false)
                            return
                        }
                    }
                }
                if (opcode == Opcodes.INVOKEVIRTUAL && owner == "android/view/inputmethod/InputMethodManager" &&
                    mName == "updateCursorAnchorInfo" &&
                    mDesc == "(Landroid/view/View;Landroid/view/inputmethod/CursorAnchorInfo;)V"
                ) {
                    super.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        HOOKS,
                        "updateCursorAnchorInfoSafe",
                        "(Landroid/view/inputmethod/InputMethodManager;Landroid/view/View;Ljava/lang/Object;)V",
                        false
                    )
                    return
                }
                if (opcode == Opcodes.INVOKESPECIAL &&
                    owner == "android/view/inputmethod/BaseInputConnection" &&
                    mName == "closeConnection" &&
                    mDesc == "()V"
                ) {
                    super.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        HOOKS,
                        "closeInputConnectionSafe",
                        "(Landroid/view/inputmethod/BaseInputConnection;)V",
                        false
                    )
                    return
                }
                super.visitMethodInsn(opcode, owner, mName, mDesc, isInterface)
            }
        }
    }
}

androidComponents {
    onVariants { variant ->
        variant.instrumentation.transformClassesWith(
            KitKatBytecodePatcher::class.java,
            InstrumentationScope.ALL
        ) {}
        variant.instrumentation.setAsmFramesComputationMode(
            FramesComputationMode.COPY_FRAMES
        )
    }
}

/* ============================================================================
 * 2. Disable Flutter Gradle MinSdk/CompileSdk Gatekeepers & Pass NDK-Safe MinSdk to NativeAssets
 * ============================================================================ */
tasks.configureEach {
    if (name.contains("MinSdkCheck", ignoreCase = true) ||
        name.contains("ValidateCompileSdkVersion", ignoreCase = true)
    ) {
        enabled = false
    }
    if (name.startsWith("compileFlutterBuild")) {
        // NDK r27c used by Dart native_toolchain_c starts clang wrappers at API 21;
        // pass 21 to flutter assemble while APK / D8 / Bionic shim target API 19.
        try {
            javaClass.getMethod("setMinSdkVersion", Integer::class.java).invoke(this, 21)
        } catch (_: Throwable) {
            try {
                javaClass.getMethod("setMinSdkVersion", Int::class.javaPrimitiveType).invoke(this, 21)
            } catch (_: Throwable) {
            }
        }
    }
}

/* ============================================================================
 * 3. Post-mergeNativeLibs ELF32 Patcher (Injects & Prioritizes libjnicompat19.so)
 * ============================================================================ */
fun patchElf32ForKitKat(soFile: File, replaceJnigraphics: Boolean) {
    val bytes = soFile.readBytes()
    if (bytes.size < 64 || bytes[0] != 0x7f.toByte() || bytes[4] != 1.toByte()) return
    val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val eShoff = buf.getInt(32)
    val eShentsize = buf.getShort(46).toInt() and 0xFFFF
    val eShnum = buf.getShort(48).toInt() and 0xFFFF
    val eShstrndx = buf.getShort(50).toInt() and 0xFFFF
    if (eShoff <= 0 || eShnum <= 0 || eShstrndx >= eShnum) return

    val shstrHdr = eShoff + eShstrndx * eShentsize
    val shstrOff = buf.getInt(shstrHdr + 16)
    val shstrSize = buf.getInt(shstrHdr + 20)

    fun cstr(base: Int, idx: Int, maxLen: Int): String {
        var end = base + idx
        val limit = (base + maxLen).coerceAtMost(bytes.size)
        while (end < limit && bytes[end] != 0.toByte()) end++
        return String(bytes, base + idx, end - (base + idx), Charsets.US_ASCII)
    }

    var dynstrOff = -1
    var dynstrSize = 0
    var dynamicOff = -1
    var dynamicSize = 0

    for (i in 0 until eShnum) {
        val hdr = eShoff + i * eShentsize
        val nameIdx = buf.getInt(hdr)
        val secName = cstr(shstrOff, nameIdx, shstrSize)
        val secOff = buf.getInt(hdr + 16)
        val secSize = buf.getInt(hdr + 20)
        if (secName == ".dynstr") {
            dynstrOff = secOff
            dynstrSize = secSize
        } else if (secName == ".dynamic") {
            dynamicOff = secOff
            dynamicSize = secSize
        }
    }
    if (dynstrOff < 0 || dynamicOff < 0) return

    var modified = false
    if (replaceJnigraphics) {
        val target = "libjnigraphics.so\u0000".toByteArray(Charsets.US_ASCII)
        val replacement = "libjnicompat19.so\u0000".toByteArray(Charsets.US_ASCII)
        require(target.size == replacement.size)
        var pos = dynstrOff
        val end = dynstrOff + dynstrSize - target.size
        while (pos <= end) {
            var match = true
            for (k in target.indices) {
                if (bytes[pos + k] != target[k]) {
                    match = false
                    break
                }
            }
            if (match) {
                System.arraycopy(replacement, 0, bytes, pos, replacement.size)
                modified = true
                break
            }
            pos++
        }
    }

    // Ensure DT_NEEDED for libjnicompat19.so precedes libc.so so pthread_cond_timedwait
    // resolves to our CLOCK_MONOTONIC-aware bridge before Bionic's libc.so!
    var libcSlot = -1
    var compatSlot = -1
    var p = dynamicOff
    val dynEnd = dynamicOff + dynamicSize - 8
    while (p <= dynEnd) {
        val tag = buf.getInt(p)
        val value = buf.getInt(p + 4)
        if (tag == 0) break
        if (tag == 1) { // DT_NEEDED
            val depName = cstr(dynstrOff, value, dynstrSize)
            if (depName == "libc.so" && libcSlot < 0) libcSlot = p
            if (depName == "libjnicompat19.so" && compatSlot < 0) compatSlot = p
        }
        p += 8
    }
    if (libcSlot >= 0 && compatSlot > libcSlot) {
        val libcVal = buf.getInt(libcSlot + 4)
        val compatVal = buf.getInt(compatSlot + 4)
        buf.putInt(libcSlot + 4, compatVal)
        buf.putInt(compatSlot + 4, libcVal)
        modified = true
    }

    if (modified) {
        soFile.writeBytes(bytes)
        println("[KitKatELF] Patched ${soFile.name} (prioritized libjnicompat19.so in DT_NEEDED)")
    }
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("NativeLibs") }.configureEach {
    val mergeTask = this
    doLast {
        val prebuiltCompat = rootProject.file("kitkat-prebuilt/armeabi-v7a/libjnicompat19.so")
        mergeTask.outputs.files.files.forEach { outRoot ->
            val armDirs = outRoot.walkTopDown().filter { it.isDirectory && it.name == "armeabi-v7a" }.toList()
            armDirs.forEach { armDir ->
                val compatDst = File(armDir, "libjnicompat19.so")
                if (!compatDst.exists() && prebuiltCompat.exists()) {
                    Files.copy(prebuiltCompat.toPath(), compatDst.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                val flutterSo = File(armDir, "libflutter.so")
                if (flutterSo.exists()) patchElf32ForKitKat(flutterSo, replaceJnigraphics = true)
                val rhttpSo = File(armDir, "librhttp.so")
                if (rhttpSo.exists()) patchElf32ForKitKat(rhttpSo, replaceJnigraphics = false)
            }
        }
    }
}

/* ============================================================================
 * 4. Post-mergeDex Dalvik Main-DEX Repack (d8 --min-api 19 --main-dex-rules)
 * ============================================================================ */
tasks.matching { it.name.startsWith("mergeDex") }.configureEach {
    val dexTask = this
    doLast {
        val sdkDir = android.sdkDirectory
        val isWin = System.getProperty("os.name").lowercase().contains("win")
        val d8Bin = File(sdkDir, "build-tools/35.0.0/" + if (isWin) "d8.bat" else "d8")
        val androidJar = File(sdkDir, "platforms/android-${android.compileSdk}/android.jar")
        val rulesFile = file("multidex-config.pro")
        if (!d8Bin.exists() || !androidJar.exists() || !rulesFile.exists()) {
            throw GradleException("Missing d8/android.jar/multidex-config.pro for KitKat main-dex repack")
        }
        dexTask.outputs.files.files.filter { it.isDirectory }.forEach { outDir ->
            val dexFiles = outDir.listFiles()?.filter { it.name.endsWith(".dex") }?.sortedBy { it.name } ?: emptyList()
            if (dexFiles.isNotEmpty()) {
                val tmpOut = File(layout.buildDirectory.asFile.get(), "tmp/${dexTask.name}_kitkat_maindex")
                if (tmpOut.exists()) tmpOut.deleteRecursively()
                tmpOut.mkdirs()
                val cmd = mutableListOf(
                    d8Bin.absolutePath,
                    if (dexTask.name.lowercase().contains("debug")) "--debug" else "--release",
                    "--min-api", "19",
                    "--lib", androidJar.absolutePath,
                    "--main-dex-rules", rulesFile.absolutePath,
                    "--output", tmpOut.absolutePath
                ).apply { addAll(dexFiles.map { it.absolutePath }) }
                val pb = ProcessBuilder(cmd)
                pb.environment()["JAVA_HOME"] = System.getProperty("java.home")
                pb.redirectErrorStream(true)
                val proc = pb.start()
                val logText = proc.inputStream.bufferedReader().readText()
                val code = proc.waitFor()
                if (code != 0) {
                    throw GradleException("d8 main-dex repack failed ($code):\n$logText")
                }
                dexFiles.forEach { it.delete() }
                tmpOut.listFiles()?.filter { it.name.endsWith(".dex") }?.forEach { f ->
                    Files.copy(f.toPath(), File(outDir, f.name).toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                println("[KitKatDex] Repacked ${tmpOut.listFiles()?.count { it.name.endsWith(".dex") } ?: 0} DEX shards with API 19 main-dex closure")
            }
        }
    }
}
