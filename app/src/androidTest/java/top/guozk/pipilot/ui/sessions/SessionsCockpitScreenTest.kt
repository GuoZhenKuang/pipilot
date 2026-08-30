package top.guozk.pipilot.ui.sessions

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import top.guozk.pipilot.coresessions.SessionKey
import top.guozk.pipilot.coresessions.SessionRecord
import top.guozk.pipilot.hosts.HostProfile
import top.guozk.pipilot.sessions.HostSessionStatus
import top.guozk.pipilot.sessions.HostSessionStatusKind
import top.guozk.pipilot.sessions.SessionCockpitFilter
import top.guozk.pipilot.sessions.SessionCockpitItem
import top.guozk.pipilot.sessions.SessionFreshnessFilter
import top.guozk.pipilot.sessions.SessionsUiState
import top.guozk.pipilot.ui.theme.PiMobileTheme
import top.guozk.pipilot.ui.theme.ThemePreference

@RunWith(AndroidJUnit4::class)
class SessionsCockpitScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rendersNoHostAndExplicitHiddenRecoveryEmptyStates() {
        var state by mutableStateOf(SessionsUiState())
        composeRule.setContent {
            PiMobileTheme(themePreference = ThemePreference.SYSTEM) {
                SessionsScreen(state = state, statusMessage = null, callbacks = SessionsCallbacks())
            }
        }

        composeRule.onNodeWithText("No hosts configured yet.").assertIsDisplayed()

        composeRule.runOnIdle {
            state =
                SessionsUiState(
                    hosts = listOf(host),
                    selectedHostId = host.id,
                    filter = SessionCockpitFilter(hiddenOnly = true),
                )
        }

        composeRule.onNodeWithText("No hidden sessions.").assertIsDisplayed()
    }

    @Test
    fun cockpitFiltersInvokeCallbacksAndExposePerHostStaleness() {
        var pinned = false
        var hidden = false
        var active = false
        var freshness: SessionFreshnessFilter? = null
        composeRule.setContent {
            PiMobileTheme(themePreference = ThemePreference.SYSTEM) {
                SessionsScreen(
                    state =
                        SessionsUiState(
                            hosts = listOf(host),
                            selectedHostId = host.id,
                            items = listOf(item()),
                            hostStatuses =
                                listOf(
                                    HostSessionStatus(
                                        hostId = host.id,
                                        hostLabel = host.name,
                                        kind = HostSessionStatusKind.STALE,
                                    ),
                                ),
                        ),
                    statusMessage = null,
                    callbacks =
                        SessionsCallbacks(
                            onTogglePinned = { pinned = true },
                            onToggleHidden = { hidden = true },
                            onToggleActive = { active = true },
                            onFreshnessSelected = { freshness = it },
                        ),
                )
            }
        }

        composeRule.onNodeWithText("Alpha host: stale").assertIsDisplayed()
        composeRule.onNodeWithText("Pinned").performClick()
        composeRule.onNodeWithText("Hidden").performClick()
        composeRule.onNodeWithText("Active").performClick()
        composeRule.onNodeWithText("Stale").performClick()

        composeRule.runOnIdle {
            assertTrue(pinned)
            assertTrue(hidden)
            assertTrue(active)
            assertEquals(SessionFreshnessFilter.STALE, freshness)
        }
    }

    @Test
    fun normalCardTextAndSemanticsDoNotExposeSessionPathOrFullCwd() {
        val privatePath = "/home/private/work/session.jsonl"
        val privateCwd = "/home/private/work"
        composeRule.setContent {
            PiMobileTheme(themePreference = ThemePreference.SYSTEM) {
                SessionsScreen(
                    state =
                        SessionsUiState(
                            hosts = listOf(host),
                            selectedHostId = host.id,
                            items = listOf(item(privatePath, privateCwd)),
                        ),
                    statusMessage = null,
                    callbacks = SessionsCallbacks(),
                )
            }
        }

        composeRule.onNodeWithText("Safe title").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText(privatePath, substring = true).fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithText(privateCwd, substring = true).fetchSemanticsNodes().isEmpty())
    }

    private fun item(
        path: String = "/private/session.jsonl",
        cwd: String = "/private/work",
    ): SessionCockpitItem {
        val key = SessionKey(host.id, "session-a")
        val record =
            SessionRecord(
                sessionPath = path,
                cwd = cwd,
                createdAt = "2026-01-01T00:00:00Z",
                updatedAt = "2026-01-02T00:00:00Z",
                displayName = "Safe title",
                sessionId = key.sessionId,
                isSessionIdUnique = true,
            )
        return SessionCockpitItem(
            listKey = "${key.hostProfileId}:${key.sessionId}",
            key = key,
            hostId = host.id,
            hostLabel = host.name,
            workspaceLabel = "work",
            title = "Safe title",
            preview = "Safe preview",
            model = "model",
            messageCount = 2,
            updatedAt = record.updatedAt,
            record = record,
            isPinned = false,
            isHidden = false,
            isActive = false,
            freshness = HostSessionStatusKind.FRESH,
        )
    }

    private companion object {
        val host = HostProfile("host-a", "Alpha host", "alpha", 8787, false)
    }
}
