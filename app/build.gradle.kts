plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("kotlin-parcelize") // Needed to pass Chapter obj with Navigation Component
    id("androidx.navigation.safeargs.kotlin")
}

android {
    namespace = "it.unipi.tarabbo.autobooks"
    compileSdk = 35

    defaultConfig {
        applicationId = "it.unipi.tarabbo.autobooks"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        viewBinding = true
    }
    packaging{
        //Added to resolve conflicts after Drive API
        resources.excludes.add("META-INF/DEPENDENCIES")
        resources.excludes.add("META-INF/LICENSE")
        resources.excludes.add("META-INF/LICENSE.txt")
        resources.excludes.add("META-INF/NOTICE")
        resources.excludes.add("META-INF/NOTICE.txt")
        resources.excludes.add("META-INF/INDEX.LIST")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)



    // Custom
    //Epub library
    implementation("io.documentnode:epub4j-core:4.2.1") {
        exclude( group = "xmlpull")
    }
    //SaveArgs
    implementation("androidx.navigation:navigation-fragment-ktx:2.7.7")
    implementation("androidx.navigation:navigation-ui-ktx:2.7.7")
    implementation("com.google.android.material:material:1.8.0") // progess bar

   // Cloud Save
    implementation(libs.androidx.credentials)
    implementation("androidx.credentials:credentials-play-services-auth:1.5.0") //https://mvnrepository.com/artifact/androidx.credentials/credentials-play-services-auth
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1") //https://mvnrepository.com/artifact/com.google.android.libraries.identity.googleid/googleid

    implementation("com.google.android.gms:play-services-auth:21.3.0")
    implementation("com.google.api-client:google-api-client-android:2.7.2") {
        exclude( group = "org.apache.httpcomponents")
    }
    implementation("com.google.apis:google-api-services-drive:v3-rev20250511-2.0.0") {
        exclude( group = "org.apache.httpcomponents")
    }
    implementation("com.google.http-client:google-http-client-android:1.47.0") //https://mvnrepository.com/artifact/com.google.http-client/google-http-client

    // Glide for image loading
    implementation ("com.github.bumptech.glide:glide:4.16.0")
    annotationProcessor ("com.github.bumptech.glide:compiler:4.16.0")


    //lifecycleScopes
    implementation ("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
}