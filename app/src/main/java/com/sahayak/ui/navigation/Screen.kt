package com.sahayak.ui.navigation

sealed class Screen(val route: String) {
    object Welcome : Screen("welcome")
    object Home : Screen("home")
    object FormHelper : Screen("form_helper")
    object Result : Screen("result")
    object Sentinel : Screen("sentinel")
}
