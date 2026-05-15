package com.sahayak.ui.navigation

sealed class Screen(val route: String) {
    object Welcome    : Screen("welcome")
    object Home       : Screen("home")
    object FormHelper : Screen("form_helper")
    object Sentinel   : Screen("sentinel")
    object Profile    : Screen("profile")
}
