package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.startup.StartupIntroPolicy
import me.rerere.rikkahub.startup.StartupMotion
import me.rerere.rikkahub.startup.StartupPreferences
import me.rerere.rikkahub.startup.animatorDurationScale
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.hooks.rememberAmoledDarkMode
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingPreferencesThemePage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var amoledDarkMode by rememberAmoledDarkMode()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val navController = LocalNavController.current

    // 启动动效是独立的小偏好（SharedPreferences），不进 Settings，所以在这里按需读写。
    val context = LocalContext.current
    var startupMotion by remember { mutableStateOf(StartupPreferences.readMotion(context)) }
    val animatorScale = remember { context.animatorDurationScale() }
    val startupMotionSkipped = remember(animatorScale) {
        StartupIntroPolicy.isReducedMotion(animatorScale)
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.setting_page_preferences_theme))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = me.rerere.rikkahub.ui.theme.LocalPageContainer.current
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item(key = "appearance") {
                AppearanceSettingsCard(
                    appearance = settings.displaySetting.appearance,
                    persistChange = vm::persistAppearance,
                    onChange = { appearance ->
                        vm.updateSettings { latest ->
                            latest.copy(displaySetting = latest.displaySetting.copy(appearance = appearance))
                        }
                    },
                )
            }
            item {
                CardGroup {
                    switchItem(
                        checked = settings.dynamicColor,
                        onCheckedChange = { vm.updateSettings { latest -> latest.copy(dynamicColor = it) } },
                        supportingContent = { Text(stringResource(R.string.setting_page_dynamic_color_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_dynamic_color)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingTheme) },
                        headlineContent = { Text(stringResource(R.string.setting_page_theme_setting)) },
                        supportingContent = { Text(stringResource(R.string.setting_page_theme_setting_desc)) },
                        trailingContent = { Icon(HugeIcons.ArrowRight01, contentDescription = null) },
                    )
                    switchItem(
                        checked = amoledDarkMode,
                        onCheckedChange = { amoledDarkMode = it },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_amoled_dark_mode_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_display_page_amoled_dark_mode_title)) },
                    )
                }
            }
            // 启动动效放在主题设置里：它和配色、壁纸一样属于"观感"，也跟着这里一起被找到。
            item(key = "startup-motion") {
                CardGroup {
                    formItem(
                        headlineContent = { Text(stringResource(R.string.r21_startup_motion_title)) },
                        supportingContent = {
                            Text(
                                stringResource(
                                    if (startupMotionSkipped) {
                                        R.string.r21_startup_motion_reduced
                                    } else {
                                        R.string.r21_startup_motion_desc
                                    }
                                )
                            )
                        },
                    ) {
                        val options = listOf(
                            StartupMotion.RING to R.string.r21_startup_motion_ring,
                            StartupMotion.DOTS to R.string.r21_startup_motion_dots,
                            StartupMotion.OFF to R.string.r21_startup_motion_off,
                        )
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            options.forEachIndexed { index, (motion, label) ->
                                SegmentedButton(
                                    selected = startupMotion == motion,
                                    onClick = {
                                        if (startupMotion != motion) {
                                            startupMotion = motion
                                            StartupPreferences.writeMotion(context, motion)
                                        }
                                    },
                                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                                ) {
                                    Text(stringResource(label))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
