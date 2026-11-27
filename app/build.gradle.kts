plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.tashu.player"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tashu.player"
        // 28 = DynamicsProcessing (EQ graphique + gain ReplayGain).
        minSdk = 28
        targetSdk = 35
        versionCode = 5
        versionName = "0.2.3"
        // Téléphones récents uniquement (ARM 64 bits) : APK nettement plus léger.
        ndk { abiFilters += listOf("arm64-v8a") }

        // Client ID OAuth de type "Web application" (pas Android !) — voir README.
        // À mettre dans ~/.gradle/gradle.properties ou local.properties : TASHU_WEB_CLIENT_ID=xxx.apps.googleusercontent.com
        val webClientId = (project.findProperty("TASHU_WEB_CLIENT_ID") as String?) ?: ""
        buildConfigField("String", "WEB_CLIENT_ID", "\"$webClientId\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Même clé que le debug : la connexion Google (client OAuth Android) est liée à son empreinte SHA-1.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=androidx.media3.common.util.UnstableApi")
    }
    // Les .so de Filament (scène 3D) ne sont pas alignés en 16 ko : on les laisse extraire à l'installation
    // plutôt que charger depuis l'APK, ce qui évite l'avertissement de compatibilité d'Android 15+.
    packaging {
        jniLibs.useLegacyPackaging = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.common)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.cast)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.androidx.compiler)

    implementation(libs.work.runtime)
    implementation(libs.datastore.preferences)

    implementation(libs.credentials)
    implementation(libs.credentials.play.services)
    implementation(libs.googleid)
    implementation(libs.play.services.auth)

    implementation(libs.coroutines.android)
    implementation(libs.coroutines.play.services)
    implementation(libs.coroutines.guava)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.coil.compose)
    // Pochettes animées (GIF, WebP animé, APNG) : embarquées dans le fichier ou cover.gif dans le dossier Drive.
    implementation(libs.coil.gif)
    implementation(libs.androidx.palette)
    implementation(libs.material.kolor)

    // Rendu 3D (Filament) du lecteur CD en paysage.
    implementation(libs.sceneview)

    // Verre dépoli (flou d'arrière-plan) pour la barre de navigation et le mini-lecteur.
    implementation(libs.haze)

}
