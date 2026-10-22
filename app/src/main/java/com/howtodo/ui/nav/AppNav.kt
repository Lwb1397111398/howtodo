package com.howtodo.ui.nav

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material.icons.outlined.Workspaces
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.howtodo.ui.chat.ChatScreen
import com.howtodo.ui.knowledge.KnowledgeScreen
import com.howtodo.ui.materials.MaterialsScreen
import com.howtodo.ui.materials.PreviewScreen
import com.howtodo.ui.project.ProjectScreen
import com.howtodo.ui.project.ProposalScreen
import com.howtodo.ui.projects.ProjectsScreen
import com.howtodo.ui.settings.ProviderScreen
import com.howtodo.ui.settings.SettingsScreen
import com.howtodo.ui.today.TodayScreen

object Routes {
    const val TODAY = "today"
    const val PROJECTS = "projects"
    const val MATERIALS = "materials"
    const val KNOWLEDGE = "knowledge"
    const val SETTINGS = "settings"

    const val PROJECT = "project/{projectId}"
    const val PROPOSAL = "proposal/{proposalId}"
    const val PREVIEW = "preview/{sourceId}?chunkStart={chunkStart}"
    const val CHAT = "chat?projectId={projectId}"
    const val PROVIDER = "provider/{providerId}"

    fun project(id: String) = "project/$id"
    fun proposal(id: String) = "proposal/$id"
    fun preview(sourceId: String, chunkStart: Int = -1) = "preview/$sourceId?chunkStart=$chunkStart"
    fun chat(projectId: String? = null) = "chat?projectId=${projectId.orEmpty()}"
    fun provider(id: String) = "provider/$id"
}

private data class TabItem(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    TabItem(Routes.TODAY, "今日", Icons.Outlined.Today),
    TabItem(Routes.PROJECTS, "工作", Icons.Outlined.Workspaces),
    TabItem(Routes.MATERIALS, "材料", Icons.Outlined.FolderOpen),
    TabItem(Routes.KNOWLEDGE, "知识", Icons.Outlined.Lightbulb),
    TabItem(Routes.SETTINGS, "设置", Icons.Outlined.Settings)
)

@Composable
fun AppNav(startProjectId: String? = null, onStartConsumed: () -> Unit = {}) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showBar = tabs.any { it.route == currentRoute }

    Scaffold(
        bottomBar = {
            if (showBar) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp
                ) {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                if (currentRoute != tab.route) {
                                    nav.navigate(tab.route) {
                                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label, style = MaterialTheme.typography.labelSmall) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary
                            )
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            }
        }
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Routes.TODAY,
            modifier = Modifier.padding(padding)
        ) {
            composable(Routes.TODAY) {
                TodayScreen(
                    onOpenProject = { nav.navigate(Routes.project(it)) },
                    onOpenProposal = { nav.navigate(Routes.proposal(it)) },
                    onOpenMaterials = { nav.navigate(Routes.MATERIALS) },
                    onOpenChat = { nav.navigate(Routes.chat(it)) },
                    onOpenSettings = { nav.navigate(Routes.SETTINGS) }
                )
            }
            composable(Routes.PROJECTS) {
                ProjectsScreen(
                    onOpenProject = { nav.navigate(Routes.project(it)) },
                    onOpenMaterials = { nav.navigate(Routes.MATERIALS) }
                )
            }
            composable(Routes.MATERIALS) {
                MaterialsScreen(
                    onOpenPreview = { nav.navigate(Routes.preview(it)) }
                )
            }
            composable(Routes.KNOWLEDGE) {
                KnowledgeScreen()
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onOpenProvider = { nav.navigate(Routes.provider(it)) }
                )
            }
            composable(Routes.PROVIDER, arguments = listOf(navArgument("providerId") { type = NavType.StringType })) { entry ->
                ProviderScreen(
                    providerId = entry.arguments?.getString("providerId").orEmpty().ifBlank { "new" },
                    onBack = { nav.popBackStack() }
                )
            }
            composable(
                Routes.PROJECT,
                arguments = listOf(navArgument("projectId") { type = NavType.StringType })
            ) { entry ->
                ProjectScreen(
                    projectId = entry.arguments?.getString("projectId").orEmpty(),
                    onBack = { nav.popBackStack() },
                    onOpenProposal = { nav.navigate(Routes.proposal(it)) },
                    onOpenPreview = { nav.navigate(Routes.preview(it)) },
                    onOpenChat = { nav.navigate(Routes.chat(it)) }
                )
            }
            composable(
                Routes.PROPOSAL,
                arguments = listOf(navArgument("proposalId") { type = NavType.StringType })
            ) { entry ->
                ProposalScreen(
                    proposalId = entry.arguments?.getString("proposalId").orEmpty(),
                    onBack = { nav.popBackStack() },
                    onOpenPreview = { sid, start -> nav.navigate(Routes.preview(sid, start)) }
                )
            }
            composable(
                Routes.PREVIEW,
                arguments = listOf(
                    navArgument("sourceId") { type = NavType.StringType },
                    navArgument("chunkStart") { type = NavType.IntType; defaultValue = -1 }
                )
            ) { entry ->
                PreviewScreen(
                    sourceId = entry.arguments?.getString("sourceId").orEmpty(),
                    highlightStart = entry.arguments?.getInt("chunkStart") ?: -1,
                    onBack = { nav.popBackStack() }
                )
            }
            composable(
                Routes.CHAT,
                arguments = listOf(navArgument("projectId") { type = NavType.StringType; defaultValue = "" })
            ) { entry ->
                ChatScreen(
                    projectId = entry.arguments?.getString("projectId").orEmpty().ifBlank { null },
                    onBack = { nav.popBackStack() },
                    onOpenPreview = { sid, start -> nav.navigate(Routes.preview(sid, start)) }
                )
            }
        }

        // 从提醒通知点进来 → 直接落到对应工作，省一次翻找
        LaunchedEffect(startProjectId) {
            val pid = startProjectId
            if (!pid.isNullOrBlank()) {
                nav.navigate(Routes.project(pid))
                onStartConsumed()
            }
        }
    }
}
