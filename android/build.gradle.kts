import com.android.build.gradle.LibraryExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

allprojects {
    repositories {
        google()
        mavenCentral()
    }
    configurations.configureEach {
        resolutionStrategy {
            force("androidx.core:core:1.12.0")
            force("androidx.core:core-ktx:1.12.0")
            force("androidx.appcompat:appcompat:1.6.1")
            force("androidx.appcompat:appcompat-resources:1.6.1")
            force("androidx.activity:activity:1.8.2")
            force("androidx.activity:activity-ktx:1.8.2")
            force("androidx.fragment:fragment:1.6.2")
            force("androidx.fragment:fragment-ktx:1.6.2")
            force("androidx.lifecycle:lifecycle-common:2.6.2")
            force("androidx.lifecycle:lifecycle-runtime:2.6.2")
            force("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
            force("androidx.lifecycle:lifecycle-viewmodel:2.6.2")
            force("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.2")
            force("androidx.lifecycle:lifecycle-viewmodel-savedstate:2.6.2")
            force("androidx.lifecycle:lifecycle-livedata:2.6.2")
            force("androidx.lifecycle:lifecycle-livedata-core:2.6.2")
            force("androidx.lifecycle:lifecycle-process:2.6.2")
            force("androidx.savedstate:savedstate:1.2.1")
            force("androidx.annotation:annotation:1.8.2")
            force("androidx.browser:browser:1.5.0")
            force("androidx.preference:preference:1.2.1")
            force("androidx.preference:preference-ktx:1.2.1")
            force("androidx.documentfile:documentfile:1.0.1")
            force("androidx.exifinterface:exifinterface:1.3.7")
            force("androidx.window:window:1.2.0")
            force("androidx.window:window-java:1.2.0")
            force("com.google.android.material:material:1.6.1")
        }
    }
}

val newBuildDir: Directory = rootProject.layout.buildDirectory.dir("../../build").get()
rootProject.layout.buildDirectory.value(newBuildDir)

subprojects {
    val newSubprojectBuildDir: Directory = newBuildDir.dir(project.name)
    project.layout.buildDirectory.value(newSubprojectBuildDir)
}

subprojects {
    afterEvaluate {
        extensions.findByType(LibraryExtension::class.java)?.apply {
            compileSdk = 35
            ndkVersion = "25.2.9519653"
            defaultConfig {
                minSdk = 19
            }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
            lint {
                checkReleaseBuilds = false
                abortOnError = false
            }
        }
        tasks.withType(KotlinCompile::class.java).configureEach {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_17)
            }
        }
    }
}

subprojects {
    project.evaluationDependsOn(":app")
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
