// Top-level build file. It only declares which plugins exist (and their versions);
// "apply false" means: don't apply them here, the app module will apply what it needs.

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
